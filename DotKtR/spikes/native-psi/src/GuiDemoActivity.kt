package dotktr.probe

import com.google.gson.GsonBuilder
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Only the explicitly configured demo solution is imported; ordinary projects are untouched. */
class GuiDemoActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!java.lang.Boolean.getBoolean("dotktr.probe.ui")) return
        val source = System.getProperty("dotktr.probe.analysis.source") ?: return
        val expectedCompletion = System.getProperty("dotktr.probe.completion.names", "Read,Value,Describe").split(',')
        val solution = Path.of(System.getProperty("dotktr.probe.solution"))
        val basePath = project.basePath ?: return
        if (!Path.of(basePath).normalize().startsWith(solution.parent)) return
        withContext(Dispatchers.IO) {
            val report = linkedMapOf<String, Any?>(
                "mode" to "interactive", "ide" to ApplicationInfo.getInstance().fullApplicationName,
                "build" to ApplicationInfo.getInstance().build.asString(),
                "processId" to ProcessHandle.current().pid(), "source" to source,
            )
            fun save() {
                Files.writeString(Path.of(System.getProperty("dotktr.probe.report")),
                    GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n")
            }
            try {
                report["phase"] = "waiting-for-solution"
                save()
                report["backendReady"] = NavigationProbeClient.waitReady(project)
                report["phase"] = "importing-kotlin"
                save()
                val analysis = K2Probe.run(project, source,
                    System.getProperty("dotktr.probe.analysis.klibs"),
                    System.getProperty("dotktr.probe.analysis.stdlib"))
                report["k2"] = analysis
                // A user may reopen the demo while its source contains errors or additional calls.
                // Only automated fixture checks require the original seven-call baseline to be clean.
                if (java.lang.Boolean.getBoolean("dotktr.probe.ui.checks")) {
                    check(analysis["semanticSuccess"] == true) { "Demo K2 analysis failed" }
                }
                report["phase"] = "opening-editor"
                save()
                ApplicationManager.getApplication().invokeAndWait {
                    ShowDemoAction.open(project, completion = false)
                    FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
                        report["editorClass"] = editor.javaClass.name
                        report["editorFile"] = source
                        if (editor is EditorEx) {
                            val iterator = editor.highlighter.createIterator(0)
                            val tokens = mutableSetOf<String>()
                            while (!iterator.atEnd()) {
                                tokens += iterator.tokenType.toString()
                                iterator.advance()
                            }
                            report["syntaxTokenTypes"] = tokens.sorted()
                        }
                    }
                    NotificationGroupManager.getInstance().getNotificationGroup("DotKt Demo")
                        .createNotification("DotKt 示例已打开", System.getProperty("dotktr.probe.ui.notification",
                            "可尝试 box. 补全、Api.Pick(42L) 类型错误及参数与悬停提示。"), NotificationType.INFORMATION)
                        .notify(project)
                }
                if (java.lang.Boolean.getBoolean("dotktr.probe.ui.checks")) {
                    report["editorChecks"] = GuiEditorChecks.run(project) { progress ->
                        report["editorChecks"] = progress
                        save()
                    }
                }
                if (System.getProperty("dotktr.probe.completion.anchor") != null) {
                    report["editors"] = captureSourceEditors(project)
                    save()
                }
                ApplicationManager.getApplication().invokeAndWait {
                    ShowDemoAction.open(project, completion = false)
                }
                // Wait for the real window to acquire focus before invoking the native UI handler.
                delay(1500)
                ApplicationManager.getApplication().invokeAndWait {
                    ShowDemoAction.open(project, completion = true)
                }
                var candidates = emptyList<Map<String, String>>()
                for (attempt in 0 until 100) {
                    delay(200)
                    ApplicationManager.getApplication().invokeAndWait {
                        candidates = LookupManager.getInstance(project).activeLookup?.items?.map {
                            mapOf("name" to it.lookupString, "objectClass" to it.`object`.javaClass.name)
                        }.orEmpty()
                    }
                    if (candidates.map { it["name"] }.containsAll(expectedCompletion)) break
                }
                report["visibleCompletion"] = candidates
                report["completionSuccess"] = candidates.map { it["name"] }.containsAll(expectedCompletion)
                ApplicationManager.getApplication().invokeAndWait {
                    (LookupManager.getInstance(project).activeLookup as? LookupImpl)?.let { lookup ->
                        report["completionPopupShown"] = lookup.isShown
                        val component = lookup.component
                        if (component.width > 0 && component.height > 0) {
                            val bitmap = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_RGB)
                            val graphics = bitmap.createGraphics()
                            try { component.paint(graphics) } finally { graphics.dispose() }
                            ImageIO.write(bitmap, "png", Path.of(System.getProperty("dotktr.probe.report")).parent.resolve("ui-project-completion.png").toFile())
                        }
                    }
                }
                report["phase"] = "ready"
                report["startupSuccess"] = true
                report["success"] = report["completionSuccess"] == true &&
                    analysis["semanticSuccess"] == true &&
                    (!java.lang.Boolean.getBoolean("dotktr.probe.ui.checks") ||
                        (report["editorChecks"] as? Map<*, *>)?.get("success") == true)
                if (java.lang.Boolean.getBoolean("dotktr.probe.navigation.checks")) {
                    report["dotnetNavigation"] = DotNetNavigationChecks.run(project) { progress ->
                        report["dotnetNavigation"] = progress
                        save()
                    }
                    report["success"] = report["success"] == true &&
                        (report["dotnetNavigation"] as Map<*, *>)["success"] == true
                }
                if (java.lang.Boolean.getBoolean("dotktr.probe.run.checks")) {
                    report["mainRun"] = MainRunChecks.run(project) { progress ->
                        report["mainRun"] = progress
                        save()
                    }
                    report["success"] = report["success"] == true && (report["mainRun"] as Map<*, *>)["success"] == true
                }
                System.getProperty("dotktr.probe.ui.focus.file")?.let { relativePath ->
                    val binding = project.getUserData(DotKtModuleBinding.KEY)!!
                    binding.sourceFiles.singleOrNull { it.path == binding.sourceRoot.path + "/" + relativePath }?.let { file ->
                        ApplicationManager.getApplication().invokeAndWait {
                            LookupManager.getInstance(project).hideActiveLookup()
                            val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)
                            editor?.let { com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(it.contentComponent, true) }
                            report["focusedFile"] = file.path
                        }
                    }
                }
                save()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                report["phase"] = "failed"
                report["success"] = false
                report["error"] = failure.stackTraceToString()
                save()
                NotificationGroupManager.getInstance().getNotificationGroup("DotKt Demo")
                    .createNotification("DotKt 示例加载失败", failure.message ?: failure.javaClass.name, NotificationType.ERROR)
                    .notify(project)
            }
        }
    }

    private suspend fun captureSourceEditors(project: Project): List<Map<String, Any?>> {
        val files = project.getUserData(DotKtModuleBinding.KEY)!!.sourceFiles
        val results = mutableListOf<Map<String, Any?>>()
        for (file in files) {
            var editor: com.intellij.openapi.editor.Editor? = null
            ApplicationManager.getApplication().invokeAndWait {
                editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)
                PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it, "Inspect project editor") }
            }
            var finished = false
            for (attempt in 0 until 100) {
                delay(200)
                ApplicationManager.getApplication().invokeAndWait {
                    val psi = PsiManager.getInstance(project).findFile(file)!!
                    finished = (DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl).isAllAnalysisFinished(psi)
                }
                if (finished) break
            }
            ApplicationManager.getApplication().invokeAndWait {
                val current = editor!!
                val errors = DaemonCodeAnalyzerImpl.getHighlights(current.document, HighlightSeverity.ERROR, project).map {
                    mapOf("start" to it.startOffset, "end" to it.endOffset, "message" to it.description)
                }
                val component = current.component
                val bitmap = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_RGB)
                val graphics = bitmap.createGraphics()
                try { component.paint(graphics) } finally { graphics.dispose() }
                val screenshot = Path.of(System.getProperty("dotktr.probe.report")).parent.resolve("ui-" + file.name + ".png")
                ImageIO.write(bitmap, "png", screenshot.toFile())
                results += mapOf("file" to file.path, "analysisFinished" to finished, "errors" to errors, "image" to screenshot.toString())
            }
        }
        return results
    }
}

class ShowDemoAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { open(it, completion = true) }
    }

    companion object {
        fun open(project: Project, completion: Boolean) {
            val source = System.getProperty("dotktr.probe.analysis.source") ?: return
            val file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(source)) ?: return
            val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true) ?: return
            val offset = editor.document.text.indexOf(System.getProperty("dotktr.probe.completion.anchor", "box.Read()"))
            if (offset >= 0) editor.caretModel.moveToOffset(offset + System.getProperty("dotktr.probe.completion.prefix", "box.").length)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            java.awt.Desktop.getDesktop().requestForeground(true)
            WindowManager.getInstance().getFrame(project)?.toFront()
            com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
            PsiManager.getInstance(project).findFile(file)?.let {
                DaemonCodeAnalyzer.getInstance(project).restart(it, "DotKt demo imported")
            }
            if (completion) CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor)
        }
    }
}
