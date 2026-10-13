package dev.dotktr

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class KlibCacheTest {
    private fun klib(path: Path, value: String, time: Long = 1_000_000_000_000): Path {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, text) in mapOf("default/manifest" to "unique_name=fixture\nabi_version=2.4.0\n",
                "default/linkdata/package_fixture/0_fixture.knm" to value)) {
                zip.putNextEntry(ZipEntry(name).apply { this.time = time }); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        return path
    }
    private fun temporary(body: (Path) -> Unit) {
        val root = Files.createTempDirectory("dotktr-cache-test-")
        try { body(root) } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private fun snapshot(root: Path, api: String = "api-v1"): MsBuildSnapshot {
        val project = Files.writeString(root.resolve("App.ktproj"), "<Project/>")
        val source = Files.writeString(root.resolve("Program.kt"), "fun main() {}")
        val reference = klib(root.resolve("obj/Library.klib"), api)
        val stdlib = klib(root.resolve("obj/stdlib.klib"), "stdlib")
        val dll = Files.writeString(root.resolve("Library.dll"), "metadata")
        return MsBuildSnapshot(project, "net10.0", "2.4.10", listOf(source), listOf(reference, stdlib), mapOf(reference.toString() to dll.toString()))
    }
    private fun identities(snapshot: MsBuildSnapshot) = snapshot.assemblies.values.associateWith { "Library, Version=1.0.0.0, Culture=neutral, PublicKeyToken=null" }
    private fun inputs(s: MsBuildSnapshot, tool: Path) = ProjectionInputs(s.projectFile, s.framework, s.runtimeIdentifier, s.kotlinVersion,
        s.sources, s.assemblies.values.map(Path::of), s.libraries.last(), tool)

    @Test fun survivesCleanAndReopensWithoutOriginalDllsOrKlibs() = temporary { root ->
        val source = snapshot(root)
        val cache = KlibCache(root.resolve("ide"))
        val libraries = cache.store("a".repeat(64), source, identities(source))
        val cached = cache.snapshot(inputs(source, root.resolve("tool.dll")), libraries)
        cache.remember(cached)
        source.libraries.forEach(Files::delete)
        source.assemblies.values.forEach { Files.delete(Path.of(it)) }
        val restored = cache.restore(source.projectFile, "net10.0")!!
        assertTrue(restored.libraries.all(Files::isRegularFile))
        assertEquals(cached.assemblies, restored.assemblies)
        assertEquals(identities(source), restored.assemblyIdentities)
        assertNull(cache.restore(source.projectFile, "net9.0"))
    }

    @Test fun zipTimestampChangesDoNotReplaceLiveFiles() = temporary { root ->
        val source = snapshot(root)
        val cache = KlibCache(root.resolve("ide"))
        val first = cache.store("a".repeat(64), source, identities(source))
        val file = cache.path(first.entries.first())
        val bytes = Files.readAllBytes(file)
        klib(source.libraries.first(), "api-v1", 1_100_000_000_000)
        val second = cache.store("b".repeat(64), source, identities(source))
        assertEquals(first.entries, second.entries)
        assertContentEquals(bytes, Files.readAllBytes(file))
    }

    @Test fun newApiUsesNewPathAndFailedGenerationKeepsLastGood() = temporary { root ->
        val source = snapshot(root)
        val cache = KlibCache(root.resolve("ide"))
        val first = cache.store("a".repeat(64), source, identities(source))
        cache.remember(cache.snapshot(inputs(source, root.resolve("tool.dll")), first))
        val oldPath = cache.path(first.entries.first())
        val oldBytes = Files.readAllBytes(oldPath)
        klib(source.libraries.first(), "api-v2")
        val second = cache.store("b".repeat(64), source, identities(source))
        assertNotEquals(oldPath, cache.path(second.entries.first()))
        assertContentEquals(oldBytes, Files.readAllBytes(oldPath))
        Files.writeString(source.libraries.first(), "incomplete output")
        assertFails { cache.store("c".repeat(64), source, identities(source)) }
        assertEquals("a".repeat(64), cache.restore(source.projectFile, "")!!.inputKey)
    }

    @Test fun corruptObjectIsRepairedAtAnotherPath() = temporary { root ->
        val source = snapshot(root)
        val cache = KlibCache(root.resolve("ide"))
        val first = cache.store("a".repeat(64), source, identities(source))
        val bad = cache.path(first.entries.first())
        Files.writeString(bad, "corrupted")
        assertNull(cache.load(first.inputKey))
        val repaired = cache.store(first.inputKey, source, identities(source))
        assertNotEquals(bad, cache.path(repaired.entries.first()))
        assertNotNull(cache.load(repaired.inputKey))
        assertEquals("corrupted", Files.readString(bad))
    }

    @Test fun inputKeyTracksContentFrameworkRidToolAndReferenceSet() = temporary { root ->
        val source = snapshot(root)
        val tool = Files.createDirectories(root.resolve("tool")).resolve("dll2klib.dll")
        Files.writeString(tool, "tool-v1")
        val plan = inputs(source, tool)
        val cache = KlibCache(root.resolve("ide"))
        val first = cache.fingerprint(plan)
        Files.writeString(source.sources.single(), "fun changedSource() {}")
        assertEquals(first, cache.fingerprint(plan))
        val reference = plan.references.single()
        val time = Files.getLastModifiedTime(reference)
        Files.writeString(reference, "new-data")
        Files.setLastModifiedTime(reference, time)
        assertNotEquals(first, cache.fingerprint(plan))
        val current = cache.fingerprint(plan)
        assertNotEquals(current, cache.fingerprint(plan.copy(framework = "net9.0")))
        assertNotEquals(current, cache.fingerprint(plan.copy(runtimeIdentifier = "osx-arm64")))
        assertNotEquals(current, cache.fingerprint(plan.copy(references = emptyList())))
        Files.writeString(tool, "tool-v2")
        assertNotEquals(current, cache.fingerprint(plan))
    }
}
