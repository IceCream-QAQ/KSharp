@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package dotktr.probe

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.session.useSiteSession
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path

object DocumentEditProbe {
    @JvmStatic
    fun run(project: Project, source: String): Map<String, Any?> {
        val path = Path.of(source)
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)!!
        val manager = FileDocumentManager.getInstance()
        val document = manager.getDocument(file)!!
        val original = document.text
        check("Api.Pick(42)" in original)
        val diskBefore = Files.readString(path)
        val dependency = Path.of(System.getProperty("dotktr.probe.analysis.klibs"), "Api.klib")
        val dependencyStamp = Files.getLastModifiedTime(dependency)
        fun replace(text: String) {
            ApplicationManager.getApplication().invokeAndWait {
                WriteAction.run<RuntimeException> {
                    document.setText(text)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }
            }
        }
        fun inspect(): Map<String, Any?> = ReadAction.compute<Map<String, Any?>, RuntimeException> {
            val started = System.nanoTime()
            val kt = PsiManager.getInstance(project).findFile(file) as KtFile
            val directModule = DotKtModuleFactory().createKaModuleByPsiFile(kt)
            val call = PsiTreeUtil.findChildrenOfType(kt, KtCallExpression::class.java)
                .filter { it.calleeExpression?.text == "Pick" }.minBy { it.textOffset }
            analyze(kt) {
                with(useSiteSession) {
                    val symbol = call.resolveSymbol()
                    mapOf("call" to call.text, "parameters" to symbol?.valueParameters?.map { it.returnType.toString() },
                        "analysisModule" to useSiteModule.javaClass.name,
                        "psiPhysical" to kt.isPhysical,
                        "virtualFile" to kt.virtualFile?.path,
                        "virtualFileClass" to kt.virtualFile?.javaClass?.name,
                        "bindingRoot" to project.getUserData(DotKtModuleBinding.KEY)?.sourceRoot?.path,
                        "bindingRootValid" to project.getUserData(DotKtModuleBinding.KEY)?.sourceRoot?.isValid,
                        "bindingModuleDisposed" to project.getUserData(DotKtModuleBinding.KEY)?.module?.isDisposed,
                        "sameParent" to (kt.virtualFile.parent == project.getUserData(DotKtModuleBinding.KEY)?.sourceRoot),
                        "directModule" to directModule?.javaClass?.name,
                        "directScopeContains" to directModule?.contentScope?.contains(file),
                        "dependencies" to useSiteModule.directRegularDependencies.size,
                        "syntaxErrorCount" to PsiTreeUtil.findChildrenOfType(kt, PsiErrorElement::class.java).size,
                        "unsaved" to manager.isDocumentUnsaved(document), "elapsedMs" to (System.nanoTime() - started) / 1_000_000.0)
                }
            }
        }
        try {
            val before = inspect()
            replace(original.replace("Api.Pick(42)", "Api.Pick(\"unsaved\")"))
            val edited = inspect()
            replace(original + "\nfun incomplete(\n")
            val incomplete = inspect()
            replace(original)
            val restored = inspect()
            return mapOf("before" to before, "edited" to edited, "incomplete" to incomplete, "restored" to restored,
                "diskUnchanged" to (Files.readString(path) == diskBefore),
                "klibUnchanged" to (Files.getLastModifiedTime(dependency) == dependencyStamp),
                "success" to (before["parameters"] == listOf("kotlin/Int") && edited["parameters"] == listOf("kotlin/String") &&
                    edited["unsaved"] == true && (incomplete["syntaxErrorCount"] as Int) > 0 &&
                    restored["parameters"] == listOf("kotlin/Int") && Files.readString(path) == diskBefore &&
                    Files.getLastModifiedTime(dependency) == dependencyStamp))
        } finally {
            if (document.text != original) replace(original)
        }
    }
}
