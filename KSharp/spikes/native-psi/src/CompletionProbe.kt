package dotktr.probe

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProcess
import com.intellij.codeInsight.completion.CompletionProcessEx
import com.intellij.codeInsight.completion.OffsetMap
import com.intellij.codeInsight.completion.OffsetsInFile
import com.intellij.codeInsight.completion.CompletionService
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupArranger
import com.intellij.patterns.ElementPattern
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import java.nio.file.Path
import java.util.function.Supplier
import javax.swing.Icon

object CompletionProbe {
    @JvmStatic
    fun run(project: Project, source: String): Map<String, Any?> {
        val file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(source))!!
        lateinit var editor: Editor
        lateinit var lookup: Lookup
        ApplicationManager.getApplication().invokeAndWait {
            editor = EditorFactory.getInstance().createEditor(FileDocumentManager.getInstance().getDocument(file)!!, project)
            lookup = LookupManager.getInstance(project).createLookup(editor, LookupElement.EMPTY_ARRAY, "", LookupArranger.DefaultArranger())
        }
        try {
            val variants = sortedSetOf<String>()
            val objects = sortedMapOf<String, MutableSet<String>>()
            ReadAction.run<RuntimeException> {
                val psi = PsiManager.getInstance(project).findFile(file)!!
                val call = PsiTreeUtil.findChildrenOfType(psi, KtCallExpression::class.java)
                    .single { it.calleeExpression?.text == "Read" }
                val offset = call.calleeExpression!!.textOffset
                // IntelliJ completion runs contributors on a non-physical PSI copy, while retaining
                // the original editor file as the module/context anchor.
                val completionFile = psi.copy() as com.intellij.psi.PsiFile
                val position = completionFile.findElementAt(offset)!!
                val offsets = OffsetMap(editor.document)
                val hostOffsets = OffsetsInFile(psi, offsets)
                lateinit var parameters: CompletionParameters
                // Only the UI lifecycle is supplied by the harness; all candidates come from the
                // installed contributors and the real K2 analysis session.
                val process = object : UserDataHolderBase(), CompletionProcessEx {
                    override fun isAutopopupCompletion() = false
                    override fun getProject() = project
                    override fun getEditor() = editor
                    override fun getCaret() = editor.caretModel.currentCaret
                    override fun getOffsetMap() = offsets
                    override fun getHostOffsets() = hostOffsets
                    override fun getLookup() = lookup
                    override fun getParameters() = parameters
                    override fun setParameters(value: CompletionParameters) { parameters = value }
                    override fun registerChildDisposable(supplier: Supplier<out Disposable>) { Disposer.register(project, supplier.get()) }
                    override fun itemSelected(item: LookupElement?, completionChar: Char) { error("This probe collects candidates only") }
                    override fun addAdvertisement(text: String, icon: Icon?) {}
                    override fun addWatchedPrefix(startOffset: Int, pattern: ElementPattern<String>) {}
                    override fun scheduleRestart() {}
                    override fun prefixUpdated() {}
                }
                parameters = CompletionParameters(position, psi, CompletionType.BASIC, offset, 1, editor, process)
                parameters.setTestingMode(true)
                CompletionService.getCompletionService().performCompletion(parameters) { result ->
                    variants.add(result.lookupElement.lookupString)
                    objects.getOrPut(result.lookupElement.lookupString) { sortedSetOf() }
                        .add(result.lookupElement.`object`.javaClass.name)
                }
                offsets.dispose()
            }
            val expected = setOf("Read", "Value", "Describe")
            val kotlinVariants = objects.filterValues { classes -> classes.any {
                it.startsWith("org.jetbrains.kotlin.idea.completion.impl.k2.lookups.")
            } }.keys
            return mapOf("position" to "box.<caret>Read()", "variants" to variants.toList(),
                "candidateObjects" to objects, "kotlinVariants" to kotlinVariants.toList(), "expected" to expected.toList(),
                "success" to (kotlinVariants.containsAll(expected) && kotlinVariants.intersect(setOf("Pick", "Identity", "Echo")).isEmpty()))
        } finally {
            ApplicationManager.getApplication().invokeAndWait { EditorFactory.getInstance().releaseEditor(editor) }
        }
    }
}
