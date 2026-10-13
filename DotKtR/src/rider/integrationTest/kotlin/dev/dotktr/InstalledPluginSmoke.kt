@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)
package dev.dotktr

import com.google.gson.GsonBuilder
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import kotlinx.coroutines.*
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.session.useSiteSession
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path

/** A separate test-only plugin. The distribution never contains this activity or fixtures. */
class InstalledPluginSmoke : ProjectActivity {
    override suspend fun execute(project: Project) {
        val reportPath = System.getProperty("dotktr.integration.report") ?: return
        withContext(Dispatchers.IO) {
            val report = linkedMapOf<String, Any?>()
            fun save() { Files.writeString(Path.of(reportPath), GsonBuilder().setPrettyPrinting().create().toJson(report)) }
            val service = project.getService(DotKtProjectService::class.java)
            try {
                report["phase"] = "automatic-import"; save()
                withTimeout(360_000) {
                    while (service.status.phase != "ready") {
                        report["import"] = service.status; save()
                        check(service.status.phase != "failed") { service.status.message }
                        delay(250)
                    }
                }
                report["import"] = service.status
                report["indexingFlag"] = System.getProperty("kotlin.index.non.source.roots")
                DumbService.getInstance(project).waitForSmartMode()
                val binding = project.getUserData(DotKtModuleBinding.KEY)!!
                val diagnostics = ReadAction.compute<List<Map<String, Any?>>, RuntimeException> {
                    binding.sourceFiles.map { file ->
                        val psi = PsiManager.getInstance(project).findFile(file) as KtFile
                        analyze(psi) { with(useSiteSession) {
                            mapOf("file" to file.name, "errors" to psi.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                                .filter { it.severity.toString() == "ERROR" }.map { it.defaultMessage })
                        } }
                    }
                }
                report["diagnostics"] = diagnostics
                check(diagnostics.all { (it["errors"] as List<*>).isEmpty() }) { "Imported project has Kotlin errors" }
                report["phase"] = "automatic-source-refresh"; save()
                val extra = Path.of(binding.sourceRoot.path, "AddedForImportTest.kt")
                check(!Files.exists(extra))
                try {
                    Files.writeString(extra, "package dotkt.web\nfun importedExtra(): String = \"ready\"\n")
                    ApplicationManager.getApplication().invokeAndWait { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(extra) }
                    withTimeout(90_000) {
                        while (service.status.phase != "ready" || service.status.generation <= binding.generation || service.status.sourceCount != 4) {
                            check(service.status.phase != "failed") { service.status.message }; delay(100)
                        }
                    }
                    val added = service.status
                    ApplicationManager.getApplication().invokeAndWait {
                        WriteAction.run<RuntimeException> { LocalFileSystem.getInstance().findFileByNioFile(extra)!!.delete(this) }
                    }
                    withTimeout(90_000) {
                        while (service.status.phase != "ready" || service.status.generation <= added.generation || service.status.sourceCount != 3) {
                            check(service.status.phase != "failed") { service.status.message }; delay(100)
                        }
                    }
                    report["sourceRefresh"] = mapOf("before" to binding.generation, "afterAdd" to added,
                        "afterDelete" to service.status, "success" to true)
                } finally { Files.deleteIfExists(extra) }
                DumbService.getInstance(project).waitForSmartMode()
                val source = binding.sourceFiles.single { it.name == "Program.kt" }
                ApplicationManager.getApplication().invokeAndWait {
                    val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, source), true)!!
                    editor.caretModel.moveToOffset(editor.document.text.indexOf("app.Run(") + 4)
                    java.awt.Desktop.getDesktop().requestForeground(true)
                    IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
                }
                delay(1000)
                ApplicationManager.getApplication().invokeAndWait {
                    val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
                    CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor)
                }
                var candidates = emptyList<String>()
                withTimeout(20_000) {
                    while (!candidates.containsAll(listOf("Run", "MapControllers", "mapDotKtEndpoints"))) {
                        ApplicationManager.getApplication().invokeAndWait {
                            candidates = LookupManager.getInstance(project).activeLookup?.items?.map { it.lookupString }.orEmpty()
                        }
                        delay(100)
                    }
                }
                report["completion"] = candidates
                ApplicationManager.getApplication().invokeAndWait { LookupManager.getInstance(project).hideActiveLookup() }
                report["phase"] = "navigation"; save()
                report["navigation"] = DotNetNavigationChecks.run(project) { report["navigation"] = it; save() }
                check((report["navigation"] as Map<*, *>)["success"] == true) { "Installed plugin navigation failed" }
                report["phase"] = "run"; save()
                report["run"] = MainRunChecks.run(project) { report["run"] = it; save() }
                check((report["run"] as Map<*, *>)["success"] == true) { "Installed plugin Run failed" }
                report["success"] = true; report["phase"] = "complete"
            } catch (failure: Throwable) {
                report["success"] = false; report["phase"] = "failed"; report["error"] = failure.stackTraceToString()
            }
            save()
        }
    }
}
