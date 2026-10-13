package dev.dotktr

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.ui.EditorNotifications
import com.intellij.util.EnvironmentUtil
import com.jetbrains.rd.framework.FrameworkMarshallers
import com.jetbrains.rd.framework.impl.RdCall
import com.jetbrains.rider.build.BuildHost
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ImportStatus(val phase: String, val message: String, val generation: Long = 0,
                        val sourceCount: Int = 0, val libraryCount: Int = 0,
                        val projectFile: String? = null, val cacheHit: Boolean = false,
                        val inputKey: String = "", val usingPreviousSnapshot: Boolean = false) {
    val busy: Boolean get() = phase in setOf("loading", "waiting-build", "resolving", "projecting")
}

@Service(Service.Level.PROJECT)
class DotKtProjectService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val sequence = AtomicLong()
    private var job: Job? = null
    private var watchRequests = emptySet<LocalFileSystem.WatchRequest>()
    private var watchedFiles = emptySet<String>()
    val cache = KlibCache(Path.of(PathManager.getSystemPath(), "dotktr", "klib-cache-v1"))
    private val log = Logger.getInstance(DotKtProjectService::class.java)
    @Volatile var status = ImportStatus("idle", "No DotKt project imported.")
        private set(value) {
            field = value
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) EditorNotifications.getInstance(project).updateAllNotifications()
            }
        }

    @Synchronized fun refresh(manual: Boolean = false, debounce: Boolean = false, requestedProject: String? = null) {
        val settings = project.getService(DotKtSettings::class.java).state.copy()
        if (!manual && !settings.autoImport) return
        val ticket = sequence.incrementAndGet()
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            var discovered = false
            var selected: String? = requestedProject
            try {
                if (debounce) delay(1000)
                check(TrustedProjects.isProjectTrusted(project)) { "Trust this project before resolving DotKt dependencies." }
                status = ImportStatus("loading", "Waiting for Rider project information…", ticket, projectFile = selected)
                val backend = backendStatus()
                val projects = backend["projects"].asJsonArray.map { it.asString }.filter { it.endsWith(".ktproj", true) }.distinct()
                if (projects.isEmpty()) {
                    status = ImportStatus("idle", "This solution has no .ktproj project.", ticket)
                    if (manual) notify(status.message, false)
                    return@launch
                }
                discovered = true
                check(java.lang.Boolean.getBoolean("kotlin.index.non.source.roots")) {
                    "KSharp's compatibility host requires -Dkotlin.index.non.source.roots=true in Rider VM options. Restart Rider after adding it; Gradle runIde sets it automatically."
                }
                selected = requestedProject ?: settings.projectPath.takeIf { it.isNotBlank() }?.let {
                    Path.of(project.basePath!!).resolve(it).toAbsolutePath().normalize().toString()
                } ?: projects.singleOrNull() ?: error("Select a .ktproj in Settings | Languages & Frameworks | KSharp. This version imports one Kotlin project at a time.")
                selected = projects.singleOrNull { FileUtil.pathsEqual(it, selected) }
                    ?: error("The selected .ktproj is not loaded in this solution.")
                val projectFile = Path.of(selected)
                val context = currentCoroutineContext()
                val checkCancelled = { context.ensureActive() }
                // On reopen, the last complete snapshot remains usable even if Clean removed
                // every generated file or a reference DLL is temporarily absent.
                if (project.getUserData(DotKtModuleBinding.KEY) == null) {
                    cache.restore(projectFile, settings.targetFramework, checkCancelled)?.let { install(it, ticket) }
                }
                awaitBuildIdle(ticket, selected)
                ManagingFS.getInstance().flushPendingUpdates()
                val executable = dotnet(settings.dotnetPath)
                val metadata = MsBuildSnapshot.json(execute(listOf(executable, "msbuild", selected, "-nologo",
                    "-getProperty:TargetFramework,TargetFrameworks"), projectFile.parent))
                val tfm = MsBuildSnapshot.chooseFramework(metadata["Properties"].asJsonObject, settings.targetFramework)
                val base = listOf(executable, "msbuild", selected, "-nologo", "-p:TargetFramework=$tfm")
                status = ImportStatus("resolving", "Checking DLL dependencies…", ticket, projectFile = selected,
                    usingPreviousSnapshot = project.getUserData(DotKtModuleBinding.KEY) != null)
                val inputJson = MsBuildSnapshot.json(execute(base + listOf("-restore", "-t:ResolveReferences",
                    "-getItem:ReferencePath,ReferencePathWithRefAssemblies,KotlinCompile",
                    "-getProperty:TargetFramework,RuntimeIdentifier,DotKtStdlib,DotKtKotlinVersion,DotKtDll2Klib"), projectFile.parent))
                val inputs = ProjectionInputs.parse(projectFile, tfm, inputJson)
                val inputKey = cache.fingerprint(inputs, checkCancelled)
                var libraries = cache.load(inputKey, checkCancelled)
                val hit = libraries != null
                if (libraries == null) {
                    awaitBuildIdle(ticket, selected)
                    status = ImportStatus("projecting", "Updating KLIB cache…", ticket, projectFile = selected,
                        usingPreviousSnapshot = project.getUserData(DotKtModuleBinding.KEY) != null)
                    val staging = cache.createStaging()
                    try {
                        // These guarded DotKt properties are output-location extension points.
                        // Resolve references once, then project this project's complete set into
                        // a private generation. No active IDE library points to this directory.
                        fun propertyPath(path: Path) = path.toString().replace("%", "%25").replace(";", "%3B")
                        val raw = execute(base + listOf("-t:DotKtResolveKlibReferences", "-p:BuildProjectReferences=false",
                            "-p:DotKtReferenceKlibPath=${propertyPath(staging.resolve("references"))}",
                            "-p:DotKtReferenceKlibResponseFile=${propertyPath(staging.resolve("references.rsp"))}",
                            "-getItem:DotKtResolvedKlibReference,KotlinCompile",
                            "-getProperty:TargetFramework,RuntimeIdentifier,DotKtStdlib,DotKtKotlinVersion"), projectFile.parent)
                        val projected = MsBuildSnapshot.parse(projectFile, tfm, MsBuildSnapshot.json(raw))
                        check(projected.sources.toSet() == inputs.sources.toSet() && projected.runtimeIdentifier == inputs.runtimeIdentifier) {
                            "Project inputs changed during KLIB generation. Refresh again."
                        }
                        check(projected.assemblies.values.all { Path.of(it) in inputs.references }) { "Reference set changed during KLIB generation. Refresh again." }
                        val identityResult = backendRequest(Gson().toJson(mapOf("action" to "assemblyIdentities", "paths" to projected.assemblies.values.distinct())))
                        check(identityResult["success"].asBoolean) { identityResult["error"]?.asString ?: "Cannot read assembly identities" }
                        val identities = identityResult["identities"].asJsonObject.entrySet().associate { it.key to it.value.asString }
                        check(cache.fingerprint(inputs, checkCancelled) == inputKey) { "A DLL or the toolchain changed during KLIB generation. Refresh again." }
                        libraries = cache.store(inputKey, projected, identities, checkCancelled)
                    } finally { cache.removeStaging(staging) }
                }
                val snapshot = cache.snapshot(inputs, libraries)
                checkCancelled()
                install(snapshot, ticket)
                if (ticket != sequence.get()) return@launch
                // Failure to persist the startup pointer must not discard a valid in-memory model.
                runCatching { cache.remember(snapshot) }.onFailure { log.warn("Could not persist the last DotKt snapshot", it) }
                if (requestedProject != null) project.getService(DotKtSettings::class.java).state.projectPath = selected
                status = ImportStatus("ready", "${projectFile.fileName} · $tfm · ${snapshot.libraries.size} cached KLIBs", ticket,
                    snapshot.sources.size, snapshot.libraries.size, selected, hit, inputKey)
                if (manual) notify(if (hit) "KLIB cache is up to date." else "KLIB cache refreshed.", false)
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                failed(ticket, selected, "KSharp refresh timed out. Retry after the build completes.", manual || discovered)
            } catch (failure: Exception) {
                log.warn("DotKt project refresh failed", failure)
                failed(ticket, selected, failure.message ?: failure.javaClass.simpleName, manual || discovered)
            }
        }
    }

    private fun install(snapshot: MsBuildSnapshot, ticket: Long) {
        ApplicationManager.getApplication().invokeAndWait {
            if (ticket == sequence.get() && !project.isDisposed) {
                ModuleImporter.install(project, snapshot, ticket)
                if (watchedFiles != snapshot.watchedFiles) {
                    val vfs = LocalFileSystem.getInstance()
                    val next = vfs.addRootsToWatch(snapshot.watchedFiles.map { Path.of(it).parent.toString() }.distinct(), false)
                    snapshot.watchedFiles.forEach { vfs.refreshAndFindFileByNioFile(Path.of(it)) }
                    vfs.removeWatchedRoots(watchRequests)
                    watchRequests = next
                    watchedFiles = snapshot.watchedFiles
                }
            }
        }
    }

    override fun dispose() {
        job?.cancel()
        LocalFileSystem.getInstance().removeWatchedRoots(watchRequests)
        watchRequests = emptySet()
    }

    private fun failed(ticket: Long, selected: String?, message: String, announce: Boolean) {
        if (ticket != sequence.get()) return
        val previous = project.getUserData(DotKtModuleBinding.KEY)
        val retained = previous != null && !previous.module.isDisposed
        status = ImportStatus(if (retained) "stale" else "failed",
            if (retained) "Refresh failed; keeping the previous KLIB snapshot. $message" else message,
            ticket, previous?.sourceFiles?.size ?: 0, previous?.libraryPaths?.size ?: 0, selected,
            usingPreviousSnapshot = retained)
        if (announce) notify(status.message, true)
    }

    private suspend fun awaitBuildIdle(ticket: Long, selected: String) = withTimeout(300_000) {
        val host = BuildHost.getInstance(project)
        if (host.building.value) status = ImportStatus("waiting-build", "Waiting for the build to finish…", ticket,
            projectFile = selected, usingPreviousSnapshot = project.getUserData(DotKtModuleBinding.KEY) != null)
        while (host.building.value || host.canceling.value) delay(200)
    }

    private suspend fun backendStatus(): JsonObject = withTimeout(90_000) {
        while (true) {
            val value = backendRequest("{\"action\":\"ping\"}")
            if (value["ready"]?.asBoolean == true) return@withTimeout value
            delay(200)
        }
        error("Unreachable")
    }

    private suspend fun backendRequest(json: String): JsonObject = withTimeout(60_000) {
        val model = project.solution
        lateinit var call: RdCall<String, String>
        ApplicationManager.getApplication().invokeAndWait {
            call = model.getOrCreateExtension("dotktr.backend.v1") {
                RdCall(FrameworkMarshallers.String, FrameworkMarshallers.String).apply { async = true }
            }
        }
        while (model.isLoaded.valueOrNull != true) delay(200)
        JsonParser.parseString(call.startSuspending(json)).asJsonObject
    }

    private fun dotnet(configured: String): String {
        if (configured.isNotBlank()) {
            val file = Path.of(configured)
            require(Files.isRegularFile(file) && Files.isExecutable(file)) { "dotnet executable does not exist: $configured" }
            return file.toString()
        }
        val name = if (System.getProperty("os.name").startsWith("Windows")) "dotnet.exe" else "dotnet"
        val candidates = EnvironmentUtil.getValue("PATH").orEmpty().split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }.map { Path.of(it, name) } + listOf(Path.of(System.getProperty("user.home"), ".dotnet", name))
        return candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }?.toString()
            ?: error("Configure the dotnet executable in Settings | Languages & Frameworks | KSharp.")
    }

    private suspend fun execute(command: List<String>, directory: Path): String = withTimeout(300_000) {
        coroutineScope {
            val process = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
            try {
                val output = async(Dispatchers.IO) {
                    process.inputStream.bufferedReader().use { reader ->
                        val text = StringBuilder(); val buffer = CharArray(8192)
                        while (true) {
                            val length = reader.read(buffer); if (length < 0) break
                            check(text.length + length <= 8 * 1024 * 1024) { "MSBuild output exceeded 8 MiB." }
                            text.append(buffer, 0, length)
                        }
                        text.toString()
                    }
                }
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) coroutineContext.ensureActive()
                val text = output.await()
                check(process.exitValue() == 0) { "MSBuild failed (${process.exitValue()}):\n${text.takeLast(6000)}" }
                text
            } finally {
                if (process.isAlive) { process.toHandle().descendants().forEach { it.destroy() }; process.destroyForcibly() }
            }
        }
    }

    private fun notify(message: String, error: Boolean) {
        if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("KSharp")
            .createNotification("KSharp", message, if (error) NotificationType.WARNING else NotificationType.INFORMATION).notify(project)
    }
}
