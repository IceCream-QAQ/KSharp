package dev.dotktr

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CachedKlib(val hash: String, val fileName: String, val sourceAssembly: String?, val assemblyIdentity: String?)
data class CachedLibraries(val inputKey: String, val entries: List<CachedKlib>)

/** Immutable, content-addressed KLIB objects; no live library is ever overwritten or deleted.
 * Only private staging directories are removed during an IDE session. */
class KlibCache(val root: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val digestPattern = Regex("[a-f0-9]{64}")

    fun fingerprint(inputs: ProjectionInputs, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) { digest.update(value.toByteArray(UTF_8)); digest.update(0) }
        add("dotktr-projection-cache-v1")
        add(inputs.framework); add(inputs.runtimeIdentifier); add(inputs.kotlinVersion)
        val files = inputFiles(inputs)
        for (path in files) {
            checkCancelled()
            add(path.toString()); add(stableFileHash(path, checkCancelled))
        }
        return digest.digest().hex()
    }

    fun inputFiles(inputs: ProjectionInputs): List<Path> {
        val tools = Files.list(inputs.projector.parent).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.') in setOf("dll", "jar", "json", "exe") }.toList()
        }
        return (inputs.references + listOf(inputs.stdlib, inputs.projector) + tools).distinct().sortedBy { it.toString() }
    }

    fun createStaging(): Path {
        Files.createDirectories(root.resolve("work"))
        return Files.createTempDirectory(root.resolve("work"), "projection-")
    }

    fun removeStaging(path: Path) {
        require(path.toAbsolutePath().normalize().parent == root.toAbsolutePath().normalize().resolve("work"))
        if (Files.exists(path)) Files.walk(path).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    fun load(inputKey: String, checkCancelled: () -> Unit = {}): CachedLibraries? = try {
        require(digestPattern.matches(inputKey))
        val file = root.resolve("sets/$inputKey.json")
        if (!Files.isRegularFile(file)) null else {
            val data = JsonParser.parseString(Files.readString(file)).asJsonObject
            require(data["schema"].asInt == 1 && data["inputKey"].asString == inputKey)
            val entries = data["entries"].asJsonArray.map { element ->
                val item = element.asJsonObject
                fun optional(name: String) = item[name]?.takeUnless { it.isJsonNull }?.asString
                CachedKlib(item["hash"].asString, item["fileName"].asString, optional("sourceAssembly"), optional("assemblyIdentity"))
            }
            require(entries.isNotEmpty() && entries.count { it.sourceAssembly == null } == 1)
            entries.forEach { entry ->
                checkCancelled()
                require(entry.sourceAssembly == null || entry.assemblyIdentity != null)
                require(archiveHash(path(entry), checkCancelled) == entry.hash) { "Cached KLIB is missing or corrupt" }
            }
            CachedLibraries(inputKey, entries)
        }
    } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    fun store(inputKey: String, snapshot: MsBuildSnapshot, identities: Map<String, String>,
              checkCancelled: () -> Unit = {}): CachedLibraries {
        require(digestPattern.matches(inputKey))
        val entries = snapshot.libraries.map { source ->
            checkCancelled()
            val temporary = createStaging()
            try {
                val before = Files.readAttributes(source, BasicFileAttributes::class.java)
                val copy = temporary.resolve(source.fileName.toString())
                Files.copy(source, copy)
                val after = Files.readAttributes(source, BasicFileAttributes::class.java)
                require(sameFileState(before, after)) { "KLIB changed while being copied: $source" }
                val hash = archiveHash(copy, checkCancelled)
                val assembly = snapshot.assemblies[source.toString()]
                var entry = CachedKlib(hash, source.fileName.toString(), assembly,
                    assembly?.let { identities[it] ?: error("Missing assembly identity: $it") })
                val lockFile = root.resolve("locks/$hash.lock").toAbsolutePath().normalize()
                Files.createDirectories(lockFile.parent)
                objectLocks.computeIfAbsent(lockFile) { ReentrantLock() }.withLock {
                    FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                        channel.lock().use {
                            checkCancelled()
                            var destination = path(entry)
                            if (Files.exists(destination) && runCatching { archiveHash(destination) }.getOrNull() != hash) {
                                // Never overwrite a path the IDE may still have open, even to
                                // repair corruption. Publish a new physical identity instead.
                                entry = entry.copy(fileName = source.fileName.toString().removeSuffix(".klib") + "-recovered-${UUID.randomUUID()}.klib")
                                destination = path(entry)
                            }
                            Files.createDirectories(destination.parent)
                            if (!Files.exists(destination)) {
                                try { Files.move(copy, destination, StandardCopyOption.ATOMIC_MOVE) }
                                catch (_: AtomicMoveNotSupportedException) { Files.move(copy, destination) }
                            }
                        }
                    }
                }
                entry
            } finally { removeStaging(temporary) }
        }
        require(entries.count { it.sourceAssembly == null } == 1) { "Expected one CLR stdlib" }
        val result = CachedLibraries(inputKey, entries)
        atomicJson(root.resolve("sets/$inputKey.json"), mapOf("schema" to 1, "inputKey" to inputKey, "entries" to entries))
        return result
    }

    fun snapshot(inputs: ProjectionInputs, libraries: CachedLibraries): MsBuildSnapshot = MsBuildSnapshot(
        inputs.projectFile, inputs.framework, inputs.kotlinVersion, inputs.sources, libraries.entries.map(::path),
        libraries.entries.mapNotNull { entry -> entry.sourceAssembly?.let { path(entry).toString() to it } }.toMap(),
        inputs.runtimeIdentifier, libraries.entries.mapNotNull { entry -> entry.sourceAssembly?.let { it to entry.assemblyIdentity!! } }.toMap(),
        libraries.inputKey, inputFiles(inputs).map { it.toString() }.toSet())

    fun remember(snapshot: MsBuildSnapshot) {
        atomicJson(projectState(snapshot.projectFile), mapOf("schema" to 1, "project" to snapshot.projectFile.toString(),
            "framework" to snapshot.framework, "rid" to snapshot.runtimeIdentifier, "kotlin" to snapshot.kotlinVersion,
            "sources" to snapshot.sources.map { it.toString() }, "inputKey" to snapshot.inputKey,
            "watchedFiles" to snapshot.watchedFiles))
    }

    fun restore(project: Path, requestedFramework: String, checkCancelled: () -> Unit = {}): MsBuildSnapshot? = try {
        val file = projectState(project)
        if (!Files.isRegularFile(file)) null else {
            val data = JsonParser.parseString(Files.readString(file)).asJsonObject
            require(data["schema"].asInt == 1 && data["project"].asString == project.toString() && data["kotlin"].asString == "2.4.10")
            val framework = data["framework"].asString
            require(requestedFramework.isEmpty() || framework == requestedFramework)
            val sources = data["sources"].asJsonArray.map { Path.of(it.asString).normalize() }
                .filter { it.startsWith(project.parent) && Files.isRegularFile(it) }
            require(sources.isNotEmpty())
            val set = load(data["inputKey"].asString, checkCancelled) ?: error("Cached library set is incomplete")
            MsBuildSnapshot(project, framework, "2.4.10", sources, set.entries.map(::path),
                set.entries.mapNotNull { entry -> entry.sourceAssembly?.let { path(entry).toString() to it } }.toMap(),
                data["rid"].asString,
                set.entries.mapNotNull { entry -> entry.sourceAssembly?.let { it to entry.assemblyIdentity!! } }.toMap(), set.inputKey,
                data["watchedFiles"]?.asJsonArray?.map { it.asString }?.toSet().orEmpty())
        }
    } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    fun path(entry: CachedKlib): Path {
        require(digestPattern.matches(entry.hash))
        require(entry.fileName.endsWith(".klib") && entry.fileName == Path.of(entry.fileName).fileName.toString() &&
            '/' !in entry.fileName && '\\' !in entry.fileName)
        return root.resolve("objects").resolve(entry.hash).resolve(entry.fileName)
    }

    private fun projectState(project: Path) = root.resolve("projects/" +
        MessageDigest.getInstance("SHA-256").digest(project.toString().toByteArray(UTF_8)).hex() + ".json")

    private fun atomicJson(file: Path, value: Any) {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling(file.fileName.toString() + "." + UUID.randomUUID() + ".tmp")
        try {
            Files.writeString(temporary, gson.toJson(value))
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        private val objectLocks = ConcurrentHashMap<Path, ReentrantLock>()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun sameFileState(a: BasicFileAttributes, b: BasicFileAttributes) =
            a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime() && a.fileKey() == b.fileKey()

        private fun stableFileHash(path: Path, checkCancelled: () -> Unit): String {
            val before = Files.readAttributes(path, BasicFileAttributes::class.java)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { stream ->
                val bytes = ByteArray(128 * 1024)
                while (true) {
                    checkCancelled(); val n = stream.read(bytes); if (n < 0) break
                    digest.update(bytes, 0, n)
                }
            }
            require(sameFileState(before, Files.readAttributes(path, BasicFileAttributes::class.java))) { "Input changed while being read: $path" }
            return digest.digest().hex()
        }

        /** Hash actual archive entries, not ZIP timestamps/compression, to reuse equivalent
         * projections without changing the IDE's library paths after an unrelated rebuild. */
        fun archiveHash(path: Path, checkCancelled: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }.toList()
                val manifest = entries.firstOrNull { it.name == "manifest" || it.name.endsWith("/manifest") }
                    ?: error("Not a KLIB: missing manifest in $path")
                val properties = Properties().apply { zip.getInputStream(manifest).use(::load) }
                require(!properties.getProperty("unique_name").isNullOrBlank() && entries.any { "/linkdata/" in "/${it.name}" }) { "Incomplete KLIB: $path" }
                val buffer = ByteArray(128 * 1024)
                for (entry in entries) {
                    checkCancelled()
                    digest.update(entry.name.toByteArray(UTF_8)); digest.update(0)
                    digest.update(entry.size.toString().toByteArray(UTF_8)); digest.update(0)
                    zip.getInputStream(entry).use { input ->
                        while (true) { checkCancelled(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    }
                }
            }
            return digest.digest().hex()
        }
    }
}
