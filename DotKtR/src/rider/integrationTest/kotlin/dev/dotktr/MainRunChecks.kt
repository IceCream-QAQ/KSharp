package dev.dotktr

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.jetbrains.rider.model.runnableProjectsModel
import com.jetbrains.rider.projectView.solution
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.awt.image.BufferedImage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

/** Runs only in an explicitly requested fresh test sandbox and restores all fixture text. */
object MainRunChecks {
    private fun <T> edt(action: () -> T): T {
        var value: T? = null
        ApplicationManager.getApplication().invokeAndWait { value = action() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    suspend fun run(project: Project, progress: (Map<String, Any?>) -> Unit): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        fun phase(name: String) { result["phase"] = name; progress(result.toMap()) }
        val binding = project.getUserData(DotKtModuleBinding.KEY)!!
        val file = binding.sourceFiles.single { it.name == "Program.kt" }
        val document = edt { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)!!.document }
        val original = edt { document.text }
        val routesFile = binding.sourceFiles.single { it.name == "Routes.kt" }
        val routesDocument = edt { FileDocumentManager.getInstance().getDocument(routesFile)!! }
        val originalRoutes = edt { routesDocument.text }
        fun replace(text: String) = edt {
            WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
            PsiDocumentManager.getInstance(project).commitDocument(document)
            FileDocumentManager.getInstance().saveDocument(document)
        }
        val handler = AtomicReference<ProcessHandler?>()
        val failed = AtomicReference<String?>()
        val output = StringBuffer()
        val executionEvents = java.util.concurrent.CopyOnWriteArrayList<String>()
        val connection = project.messageBus.connect()
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            private fun ours(env: ExecutionEnvironment) =
                (env.runProfile as? DotNetProjectConfiguration)?.parameters?.projectFilePath == binding.projectFile
            override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
                if (ours(env)) executionEvents += "scheduled"
            }
            override fun processStarting(executorId: String, env: ExecutionEnvironment) {
                if (ours(env)) executionEvents += "starting"
            }
            override fun processStarted(executorId: String, env: ExecutionEnvironment, processHandler: ProcessHandler) {
                if (!ours(env)) return
                executionEvents += "started"
                handler.set(processHandler)
                processHandler.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) { output.append(event.text) }
                })
            }
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                if (ours(env)) failed.set("Native .NET execution did not start")
            }
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) {
                if (ours(env)) failed.set(cause?.stackTraceToString() ?: "Native .NET execution did not start")
            }
        })
        suspend fun stop() {
            handler.get()?.let { process ->
                edt { if (!process.isProcessTerminated) ExecutionManagerImpl.stopProcess(process) }
                withTimeout(20_000) { while (!process.isProcessTerminated) delay(100) }
            }
        }
        suspend fun runMainAction() {
            withTimeout(60_000) { while (DumbService.isDumb(project)) delay(100) }
            val fromMain = ReadAction.compute<com.intellij.execution.RunnerAndConfigurationSettings, RuntimeException> {
                val main = (PsiManager.getInstance(project).findFile(file) as KtFile).declarations.filterIsInstance<KtNamedFunction>().single { it.name == "main" }
                val choices = ConfigurationContext(main.nameIdentifier!!).configurationsFromContext.orEmpty()
                check(choices.size == 1 && choices.single().configuration is DotNetProjectConfiguration)
                choices.single().configurationSettings
            }
            edt {
                ProgramRunnerUtil.executeConfiguration(fromMain, DefaultRunExecutor.getRunExecutorInstance())
            }
        }
        suspend fun listeningUrl(): String = withTimeout(180_000) {
            var found: String? = null
            while (found == null) {
                failed.get()?.let { error(it) }
                check(handler.get()?.isProcessTerminated != true) { "Process exited: $output" }
                found = Regex("Now listening on: (http://127\\.0\\.0\\.1:[0-9]+)").find(output.toString())?.groupValues?.get(1)
                delay(100)
            }
            found
        }
        try {
            phase("waiting-for-native-runnable-project")
            withTimeout(60_000) {
                while (project.solution.runnableProjectsModel.projects.valueOrNull.orEmpty().none { it.projectFilePath == binding.projectFile }) delay(200)
            }
            result["runnableProjects"] = project.solution.runnableProjectsModel.projects.valueOrNull.orEmpty().map {
                mapOf("project" to it.projectFilePath, "kind" to it.kind.toString(), "outputs" to it.projectOutputs.map { o -> o.exePath })
            }
            phase("waiting-for-smart-mode")
            withTimeout(60_000) { while (DumbService.isDumb(project)) delay(100) }
            result["activeProducers"] = RunConfigurationProducer.getProducers(project).map { it.javaClass.name }.filter { "DotKt" in it || "KotlinRun" in it || "DotNetProject" in it }
            val context = ReadAction.compute<ConfigurationContext, RuntimeException> {
                val main = (PsiManager.getInstance(project).findFile(file) as KtFile).declarations.filterIsInstance<KtNamedFunction>().single { it.name == "main" }
                result["marker"] = DotKtRunLineMarkerContributor().getSlowInfo(main.nameIdentifier!!) != null
                result["jvmMarkerHidden"] = DotKtJvmRunMarkerHider().shouldHideRunLineMarker(main.nameIdentifier!!)
                ConfigurationContext(main.nameIdentifier!!)
            }
            val configurations = ReadAction.compute<List<com.intellij.execution.actions.ConfigurationFromContext>, RuntimeException> {
                result["directProduced"] = DotKtRunConfigurationProducer().createConfigurationFromContext(context)?.configuration?.name
                context.configurationsFromContext.orEmpty()
            }
            result["contextConfigurations"] = configurations.map { mapOf("name" to it.configuration.name, "type" to it.configurationType.id, "implementation" to it.configuration.javaClass.name) }
            check(configurations.size == 1 && configurations.single().configuration is DotNetProjectConfiguration) { "main must produce one native .NET project configuration" }
            val settings = configurations.single().configurationSettings
            val configuration = settings.configuration as DotNetProjectConfiguration
            val expectedOutput = ReadAction.compute<String, RuntimeException> { DotKtMainContext.runnable(context.psiLocation!!)!!.projectOutputs.single().exePath }
            result["beforeRunTasks"] = configuration.beforeRunTasks.map { mapOf("provider" to it.providerId.toString(), "enabled" to it.isEnabled) }
            result["configuration"] = mapOf("project" to configuration.parameters.projectFilePath, "tfm" to configuration.parameters.projectTfm,
                "exe" to configuration.parameters.exePath, "workingDirectory" to configuration.parameters.workingDirectory,
                "tracksProjectOutput" to configuration.parameters.trackProjectExePath, "expectedOutput" to expectedOutput)
            check(!Files.exists(Path.of(expectedOutput))) { "Fresh fixture must have no prior output" }
            edt { RunManager.getInstance(project).addConfiguration(settings); RunManager.getInstance(project).selectedConfiguration = settings }
            // Ephemeral port avoids disturbing the user's other ASP.NET Core demo sessions.
            check(original.contains("http://127.0.0.1:5088"))
            replace(original.replace("http://127.0.0.1:5088", "http://127.0.0.1:0"))
            edt {
                LookupManager.getInstance(project).hideActiveLookup()
                java.awt.Desktop.getDesktop().requestForeground(true)
                FileEditorManager.getInstance(project).selectedTextEditor!!.let {
                    it.caretModel.moveToOffset(it.document.text.indexOf("fun main") + 4)
                    IdeFocusManager.getInstance(project).requestFocus(it.contentComponent, true)
                }
            }
            delay(700)
            result["visibleMarkers"] = edt { DaemonCodeAnalyzerImpl.getLineMarkers(document, project).map { mapOf("start" to it.startOffset, "tooltip" to it.lineMarkerTooltip) } }
            phase("native-build-and-run")
            edt {
                result["runActionClass"] = ActionManager.getInstance().getAction("RunClass").javaClass.name
            }
            runMainAction()
            delay(1000)
            edt {
                val component = WindowManager.getInstance().getFrame(project)!!
                val bitmap = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_RGB)
                val graphics = bitmap.createGraphics()
                try { component.paint(graphics) } finally { graphics.dispose() }
                ImageIO.write(bitmap, "png", Path.of(System.getProperty("dotktr.integration.report")).parent.resolve("main-run-start.png").toFile())
            }
            result["action"] = "ProgramRunnerUtil.executeConfiguration from main ConfigurationContext"
            result["executionEvents"] = executionEvents
            phase("waiting-for-process")
            val url = listeningUrl()
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            result["http"] = listOf("/", "/health", "/api/hello/Ada").map { route ->
                val response = client.send(HttpRequest.newBuilder(URI.create(url + route)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
                check(response.statusCode() == 200) { "$route returned ${response.statusCode()}" }
                mapOf("route" to route, "status" to response.statusCode(), "body" to response.body())
            }
            result["processHandler"] = handler.get()!!.javaClass.name
            result["outputExists"] = Files.exists(Path.of(expectedOutput))
            result["console"] = output.toString()
            edt {
                val component = WindowManager.getInstance().getFrame(project)!!
                val bitmap = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_RGB)
                val graphics = bitmap.createGraphics()
                try { component.paint(graphics) } finally { graphics.dispose() }
                ImageIO.write(bitmap, "png", Path.of(System.getProperty("dotktr.integration.report")).parent.resolve("main-run.png").toFile())
            }
            phase("native-stop")
            stop()
            result["stopped"] = handler.get()!!.isProcessTerminated
            handler.set(null)
            failed.set(null)
            output.setLength(0)
            replace(original.replace("http://127.0.0.1:5088", "http://127.0.0.1:0") + "\nval dotktrBroken: Int = \"wrong\"\n")
            phase("rejecting-compile-error")
            runMainAction()
            withTimeout(120_000) { while (failed.get() == null && handler.get() == null) delay(100) }
            result["compileFailure"] = mapOf("executionFailure" to failed.get(), "processStarted" to (handler.get() != null))
            check(failed.get() != null && handler.get() == null) { "Failed Kotlin compilation must not run the previous binary" }

            replace(original.replace("http://127.0.0.1:5088", "http://127.0.0.1:0"))
            val replacementBody = "Hello from Rider main rerun!"
            check(originalRoutes.contains("Hello from Kotlin on ASP.NET Core!"))
            edt {
                WriteCommandAction.runWriteCommandAction(project) {
                    routesDocument.setText(originalRoutes.replace("Hello from Kotlin on ASP.NET Core!", replacementBody))
                }
                PsiDocumentManager.getInstance(project).commitDocument(routesDocument)
                FileDocumentManager.getInstance().saveDocument(routesDocument)
            }
            failed.set(null)
            output.setLength(0)
            phase("rebuild-after-kotlin-edit")
            runMainAction()
            val rerunUrl = listeningUrl()
            val rerun = client.send(HttpRequest.newBuilder(URI.create(rerunUrl + "/")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
            result["rerun"] = mapOf("status" to rerun.statusCode(), "body" to rerun.body())
            check(rerun.statusCode() == 200 && rerun.body() == replacementBody) { "Run must rebuild edited Kotlin source" }
            stop()
            result["rerunStopped"] = handler.get()!!.isProcessTerminated
            result["success"] = result["marker"] == true && result["jvmMarkerHidden"] == true && result["outputExists"] == true &&
                result["stopped"] == true && result["rerunStopped"] == true
            phase("complete")
        } catch (failure: Throwable) {
            result["success"] = false
            result["error"] = failure.stackTraceToString()
            result["console"] = output.toString()
            phase("failed")
        } finally {
            try { stop() } finally {
                replace(original)
                edt {
                    WriteCommandAction.runWriteCommandAction(project) { routesDocument.setText(originalRoutes) }
                    PsiDocumentManager.getInstance(project).commitDocument(routesDocument)
                    FileDocumentManager.getInstance().saveDocument(routesDocument)
                }
                connection.disconnect()
            }
        }
        return result
    }
}
