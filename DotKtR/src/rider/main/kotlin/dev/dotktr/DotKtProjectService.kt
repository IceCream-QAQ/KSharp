package dev.dotktr

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
import com.intellij.util.EnvironmentUtil
import com.jetbrains.rd.framework.FrameworkMarshallers
import com.jetbrains.rd.framework.impl.RdCall
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ImportStatus(val phase: String, val message: String, val generation: Long = 0,
                        val sourceCount: Int = 0, val libraryCount: Int = 0)

@Service(Service.Level.PROJECT)
class DotKtProjectService(private val project: Project, private val scope: CoroutineScope) {
    private val sequence = AtomicLong()
    private var job: Job? = null
    @Volatile var status = ImportStatus("idle", "No DotKt project imported.")
        private set

    @Synchronized fun refresh(manual: Boolean = false, debounce: Boolean = false) {
        val settings = project.getService(DotKtSettings::class.java).state.copy()
        if (!manual && !settings.autoImport) return
        val ticket = sequence.incrementAndGet()
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            var discovered = false
            try {
                if (debounce) delay(1000)
                check(TrustedProjects.isProjectTrusted(project)) { "Trust this project before resolving DotKt dependencies." }
                status = ImportStatus("loading", "Waiting for Rider project information…", ticket)
                val backend = backendStatus()
                val projects = backend["projects"].asJsonArray.map { it.asString }.filter { it.endsWith(".ktproj", true) }.distinct()
                if (projects.isEmpty()) {
                    status = ImportStatus("idle", "This solution has no .ktproj project.", ticket)
                    if (manual) notify(status.message, false)
                    return@launch
                }
                discovered = true
                check(java.lang.Boolean.getBoolean("kotlin.index.non.source.roots")) {
                    "DotKt's compatibility host requires -Dkotlin.index.non.source.roots=true in Rider VM options. Restart Rider after adding it; Gradle runIde sets it automatically."
                }
                val selected = settings.projectPath.takeIf { it.isNotBlank() }?.let {
                    Path.of(project.basePath!!).resolve(it).toAbsolutePath().normalize().toString()
                } ?: projects.singleOrNull() ?: error("Select a .ktproj in Settings | Languages & Frameworks | DotKt. This version imports one Kotlin project at a time.")
                check(selected in projects) { "The selected .ktproj is not loaded in this solution." }
                val projectFile = Path.of(selected)
                val executable = dotnet(settings.dotnetPath)
                status = ImportStatus("resolving", "Resolving ${projectFile.fileName}…", ticket)
                val metadata = MsBuildSnapshot.json(execute(listOf(executable, "msbuild", selected, "-nologo",
                    "-getProperty:TargetFramework,TargetFrameworks"), projectFile.parent))
                val tfm = MsBuildSnapshot.chooseFramework(metadata["Properties"].asJsonObject, settings.targetFramework)
                val raw = execute(listOf(executable, "msbuild", selected, "-nologo", "-restore",
                    "-t:DotKtResolveKlibReferences", "-p:TargetFramework=$tfm",
                    "-getItem:DotKtResolvedKlibReference,KotlinCompile",
                    "-getProperty:TargetFramework,RuntimeIdentifier,DotKtStdlib,DotKtKotlinVersion"), projectFile.parent)
                val snapshot = MsBuildSnapshot.parse(projectFile, tfm, MsBuildSnapshot.json(raw))
                coroutineContext.ensureActive()
                ApplicationManager.getApplication().invokeAndWait {
                    if (ticket == sequence.get() && !project.isDisposed) ModuleImporter.install(project, snapshot, ticket)
                }
                if (ticket != sequence.get()) return@launch
                val cache = Path.of(PathManager.getSystemPath(), "dotktr", project.locationHash)
                Files.createDirectories(cache)
                Files.writeString(cache.resolve("msbuild-inputs.json"), raw)
                status = ImportStatus("ready", "${projectFile.fileName} · $tfm · ${snapshot.sources.size} Kotlin files", ticket,
                    snapshot.sources.size, snapshot.libraries.size)
                if (manual) notify(status.message, false)
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                if (ticket == sequence.get()) {
                    status = ImportStatus("failed", "DotKt import timed out. Restore the project, then refresh DotKt.", ticket)
                    if (manual || discovered) notify(status.message, true)
                }
            } catch (failure: Exception) {
                if (ticket == sequence.get()) {
                    val message = failure.message ?: failure.javaClass.simpleName
                    status = ImportStatus("failed", message, ticket)
                    Logger.getInstance(DotKtProjectService::class.java).warn("DotKt project import failed", failure)
                    if (manual || discovered) notify(message, true)
                }
            }
        }
    }

    private suspend fun backendStatus(): JsonObject = withTimeout(90_000) {
        val model = project.solution
        lateinit var call: RdCall<String, String>
        ApplicationManager.getApplication().invokeAndWait {
            call = model.getOrCreateExtension("dotktr.backend.v1") {
                RdCall(FrameworkMarshallers.String, FrameworkMarshallers.String).apply { async = true }
            }
        }
        while (model.isLoaded.valueOrNull != true) delay(200)
        while (true) {
            val value = JsonParser.parseString(call.startSuspending("{\"action\":\"ping\"}")).asJsonObject
            if (value["ready"]?.asBoolean == true) return@withTimeout value
            delay(200)
        }
        error("Unreachable")
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
            ?: error("Configure the dotnet executable in Settings | Languages & Frameworks | DotKt.")
    }

    private suspend fun execute(command: List<String>, directory: Path): String = withTimeout(300_000) {
        coroutineScope {
            val process = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
            try {
                val output = async(Dispatchers.IO) {
                    process.inputStream.bufferedReader().use { reader ->
                        val text = StringBuilder()
                        val buffer = CharArray(8192)
                        while (true) {
                            val length = reader.read(buffer)
                            if (length < 0) break
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
                if (process.isAlive) {
                    process.toHandle().descendants().forEach { it.destroy() }
                    process.destroyForcibly()
                }
            }
        }
    }

    private fun notify(message: String, error: Boolean) {
        if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("DotKt")
            .createNotification("DotKt", message, if (error) NotificationType.WARNING else NotificationType.INFORMATION).notify(project)
    }
}
