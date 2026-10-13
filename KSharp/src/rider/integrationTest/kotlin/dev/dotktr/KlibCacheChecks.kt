@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
package dev.dotktr

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.session.useSiteSession
import org.jetbrains.kotlin.psi.KtFile
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JButton

object KlibCacheChecks {
    private fun <T> edt(action: () -> T): T {
        var result: T? = null
        ApplicationManager.getApplication().invokeAndWait { result = action() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun find(component: Component, name: String): Component? =
        if (component.name == name) component else (component as? Container)?.components?.firstNotNullOfOrNull { find(it, name) }

    suspend fun run(project: Project, progress: (Map<String, Any?>) -> Unit): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        val service = project.getService(DotKtProjectService::class.java)
        val original = project.getUserData(DotKtModuleBinding.KEY)!!
        val projectFile = Path.of(original.projectFile!!)
        val projectVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectFile)!!
        val reportDir = Path.of(System.getProperty("dotktr.integration.report")).parent
        fun phase(value: String) { result["phase"] = value; progress(result.toMap()) }
        val log = reportDir.resolve("cache-clean-build.log")
        suspend fun command(vararg args: String): String {
            val process = ProcessBuilder(listOf(Path.of(System.getProperty("user.home"), ".dotnet/dotnet").toString()) + args)
                .directory(projectFile.parent.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                withTimeout(180_000) { while (!process.waitFor(100, TimeUnit.MILLISECONDS)) delay(100) }
                val output = Files.readString(log)
                check(process.exitValue() == 0) { output.takeLast(6000) }
                return output
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
        fun openProjectEditor() = edt {
            LookupManager.getInstance(project).hideActiveLookup()
            val manager = FileEditorManager.getInstance(project)
            val editor = manager.openFile(projectVf, true).first()
            java.awt.Desktop.getDesktop().requestForeground(true)
            EditorNotifications.getInstance(project).updateNotifications(projectVf)
            editor
        }
        suspend fun button(): JButton {
            val editor = openProjectEditor()
            return withTimeout(20_000) {
                var value: JButton? = null
                while (value == null) {
                    value = edt {
                        (FileEditorManager.getInstance(project) as FileEditorManagerImpl).getTopComponents(editor)
                            .firstNotNullOfOrNull { find(it, "DotKt.RefreshKlibButton") as? JButton }
                            ?.takeIf { it.isShowing && it.isEnabled }
                    }
                    delay(100)
                }
                value
            }
        }
        suspend fun clickRefresh(expect: String) {
            val before = service.status.generation
            val refresh = button()
            edt { refresh.doClick() }
            withTimeout(180_000) {
                while (service.status.generation <= before || service.status.busy) delay(100)
            }
            check(service.status.phase == expect) { service.status.toString() }
        }
        suspend fun diagnostics(): List<Map<String, Any?>> {
            DumbService.getInstance(project).waitForSmartMode()
            val binding = project.getUserData(DotKtModuleBinding.KEY)!!
            return binding.sourceFiles.map { file ->
                val psi = ReadAction.compute<KtFile, RuntimeException> { PsiManager.getInstance(project).findFile(file) as KtFile }
                val editor = edt {
                    FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)!!.also {
                        DaemonCodeAnalyzer.getInstance(project).restart(psi, "Verify KLIB cache after Clean")
                    }
                }
                withTimeout(30_000) {
                    while (!edt { (DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl).isAllAnalysisFinished(psi) }) delay(100)
                }
                val errors = ReadAction.compute<List<String>, RuntimeException> {
                    analyze(psi) { with(useSiteSession) {
                        psi.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                            .filter { it.severity.toString() == "ERROR" }.map { it.defaultMessage }
                    } }
                }
                val highlights = edt { DaemonCodeAnalyzerImpl.getHighlights(editor.document, HighlightSeverity.ERROR, project).map { it.description } }
                check(errors.isEmpty() && highlights.isEmpty()) { "$file: $errors / $highlights" }
                mapOf("file" to file.name, "errors" to errors, "daemonErrors" to highlights)
            }
        }
        phase("editor-button")
        val refresh = button()
        result["button"] = edt {
            val panel = refresh.parent
            check(refresh.x > panel.width / 2) { "Refresh button must be on the editor's right" }
            val frame = WindowManager.getInstance().getFrame(project)!!
            val bitmap = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
            val graphics = bitmap.createGraphics()
            try { frame.paint(graphics) } finally { graphics.dispose() }
            ImageIO.write(bitmap, "png", reportDir.resolve("ktproj-refresh-button.png").toFile())
            mapOf("text" to refresh.text, "showing" to refresh.isShowing, "x" to refresh.x, "panelWidth" to panel.width)
        }
        clickRefresh("ready")
        result["manualRefresh"] = service.status
        check(service.status.cacheHit && project.getUserData(DotKtModuleBinding.KEY)!!.module == original.module)
        phase("seed-build-owned-klibs")
        val seed = command("msbuild", projectFile.toString(), "-nologo", "-t:DotKtResolveKlibReferences",
            "-p:TargetFramework=${original.targetFramework}", "-getItem:DotKtResolvedKlibReference")
        val generated = MsBuildSnapshot.json(seed)["Items"].asJsonObject["DotKtResolvedKlibReference"].asJsonArray
            .map { Path.of(it.asJsonObject["Identity"].asString) }
        check(generated.isNotEmpty() && generated.all(Files::isRegularFile))
        val cached = project.getUserData(DotKtModuleBinding.KEY)!!.libraryPaths.map(Path::of)
        check(cached.all { FileUtil.isAncestor(service.cache.root.toFile(), it.toFile(), true) && !FileUtil.isAncestor(projectFile.parent.toFile(), it.toFile(), false) })
        val hashes = cached.associateWith { KlibCache.archiveHash(it) }
        phase("clean-without-build")
        command("clean", projectFile.toString(), "--nologo", "-v:q", "-p:TargetFramework=${original.targetFramework}")
        result["buildOwnedKlibsRemoved"] = generated.count { !Files.exists(it) }
        check(result["buildOwnedKlibsRemoved"] == generated.size)
        edt { LocalFileSystem.getInstance().refresh(false) }
        result["afterCleanDiagnostics"] = diagnostics()
        check(cached.all { Files.isRegularFile(it) && KlibCache.archiveHash(it) == hashes[it] })
        val program = original.sourceFiles.single { it.name == "Program.kt" }
        val editor = edt {
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, program), true)!!.also {
                it.caretModel.moveToOffset(it.document.text.indexOf("app.Run(") + 4)
                IdeFocusManager.getInstance(project).requestFocus(it.contentComponent, true)
            }
        }
        delay(700)
        edt { CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor) }
        var candidates = emptyList<String>()
        withTimeout(20_000) {
            while (!candidates.containsAll(listOf("Run", "MapControllers", "mapDotKtEndpoints"))) {
                candidates = edt { LookupManager.getInstance(project).activeLookup?.items?.map { it.lookupString }.orEmpty() }
                delay(100)
            }
        }
        result["afterCleanCompletion"] = candidates
        edt { LookupManager.getInstance(project).hideActiveLookup() }
        clickRefresh("ready")
        check(service.status.cacheHit && project.getUserData(DotKtModuleBinding.KEY)!!.module == original.module)
        result["afterCleanRefresh"] = service.status
        phase("failed-refresh-preserves-snapshot")
        val settings = project.getService(DotKtSettings::class.java).state
        val previousDotnet = settings.dotnetPath
        try {
            settings.dotnetPath = projectFile.parent.resolve("does-not-exist-dotnet").toString()
            clickRefresh("stale")
            check(service.status.usingPreviousSnapshot)
            check(project.getUserData(DotKtModuleBinding.KEY)!!.module == original.module && !original.module.isDisposed)
            result["failedRefresh"] = service.status
            result["afterFailedRefreshDiagnostics"] = diagnostics()
        } finally { settings.dotnetPath = previousDotnet }
        clickRefresh("ready")
        result["recovered"] = service.status
        result["cachedKlibs"] = cached.size
        result["success"] = true
        phase("complete")
        return result
    }
}
