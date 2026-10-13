package dotktr.probe

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.codeInsight.hint.ParameterInfoControllerBase
import com.intellij.codeInsight.hint.ShowParameterInfoHandler
import com.intellij.ide.DataManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorMouseHoverPopupManager
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.idea.highlighting.shouldHighlightErrors
import org.jetbrains.kotlin.idea.highlighting.shouldHighlightFile
import org.jetbrains.kotlin.psi.KtFile
import kotlinx.coroutines.delay
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JComponent
import com.intellij.ui.popup.AbstractPopup

/** Observes real daemon-produced editor marks and the visible native completion lookup. */
object GuiEditorChecks {
    private fun <T> edt(action: () -> T): T {
        var value: T? = null
        ApplicationManager.getApplication().invokeAndWait { value = action() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    suspend fun run(project: Project, saveProgress: (Map<String, Any?>) -> Unit): Map<String, Any?> {
        val editor = edt { FileEditorManager.getInstance(project).selectedTextEditor!! }
        val document = editor.document
        val original = edt { document.text }
        val report = linkedMapOf<String, Any?>()
        fun save(phase: String) { report["phase"] = phase; saveProgress(report.toMap()) }
        fun render(name: String, component: Component = editor.component) = edt {
            if (component.width <= 0 || component.height <= 0) {
                report["image-$name-unavailable"] = "Component has no visible layout"
                return@edt
            }
            val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try { component.paint(graphics) } finally { graphics.dispose() }
            val path = Path.of(System.getProperty("dotktr.probe.report")).parent.resolve("ui-$name.png")
            ImageIO.write(image, "png", path.toFile())
            report["image-$name"] = path.toString()
        }
        suspend fun focusEditor() {
            edt {
                java.awt.Desktop.getDesktop().requestForeground(true)
                WindowManager.getInstance().getFrame(project)?.toFront()
                IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
            }
            for (attempt in 0 until 50) {
                delay(100)
                if (edt { editor.contentComponent.isFocusOwner }) break
            }
            report["editorFocus"] = edt {
                mapOf("showing" to editor.contentComponent.isShowing,
                    "isFocusOwner" to editor.contentComponent.isFocusOwner,
                    "activeWindow" to java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow?.javaClass?.name,
                    "focusOwner" to java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.javaClass?.name)
            }
        }
        fun componentText(component: Component): List<String> {
            val own = when (component) {
                is JLabel -> listOfNotNull(component.text)
                is JEditorPane -> listOf(component.text)
                else -> emptyList()
            }
            return own + if (component is Container) component.components.flatMap(::componentText) else emptyList()
        }
        fun state(): Map<String, Any?> = edt {
            val file = PsiDocumentManager.getInstance(project).getPsiFile(document) as KtFile
            val daemon = DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl
            mapOf("highlightingAvailable" to daemon.isHighlightingAvailable(file),
                "kotlinHighlightFile" to file.shouldHighlightFile(),
                "kotlinHighlightErrors" to file.shouldHighlightErrors(),
                "filters" to ProblemHighlightFilter.EP_NAME.extensionList.map {
                    mapOf("class" to it.javaClass.name, "allows" to it.shouldHighlight(file))
                },
                "analysisFinished" to daemon.isAllAnalysisFinished(file),
                "errors" to DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.ERROR, project).map {
                    mapOf("start" to it.startOffset, "end" to it.endOffset, "message" to it.description,
                        "severity" to it.severity.toString(), "text" to document.text.substring(it.startOffset.coerceAtLeast(0), it.endOffset.coerceAtMost(document.textLength)))
                })
        }
        fun replace(text: String) = edt {
            LookupManager.getInstance(project).hideActiveLookup()
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(text)
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            val file = PsiDocumentManager.getInstance(project).getPsiFile(document)!!
            DaemonCodeAnalyzer.getInstance(project).restart(file, "DotKt GUI verification edit")
        }
        suspend fun waitForErrors(expect: Boolean): Map<String, Any?> {
            var last = state()
            for (attempt in 0 until 100) {
                delay(200)
                last = state()
                val errors = last["errors"] as List<*>
                if (last["analysisFinished"] == true && (errors.isNotEmpty() == expect)) return last
            }
            return last
        }
        try {
            save("baseline")
            report["baseline"] = waitForErrors(false)
            render("baseline")
            save("long-argument")
            replace(original.replace("Api.Pick(42)", "Api.Pick(42L)"))
            report["longArgument"] = waitForErrors(true)
            render("long-error")
            save("unterminated-string")
            replace(original.replace("Api.Pick(42)", "Api.Pick(\"unterminated)"))
            report["unterminatedString"] = waitForErrors(true)
            render("syntax-error")
            save("restored")
            replace(original)
            report["restored"] = waitForErrors(false)
            save("completion")
            replace(original.replace("box.Read()", "box."))
            focusEditor()
            edt {
                editor.caretModel.moveToOffset(document.text.indexOf("val method: String = box.") + "val method: String = box.".length)
                CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor)
            }
            var items = emptyList<Map<String, String>>()
            for (attempt in 0 until 100) {
                delay(100)
                items = edt {
                    LookupManager.getInstance(project).activeLookup?.items?.map {
                        mapOf("name" to it.lookupString, "objectClass" to it.`object`.javaClass.name)
                    }.orEmpty()
                }
                if (items.map { it["name"] }.containsAll(listOf("Read", "Value", "Describe"))) break
            }
            report["completion"] = items
            val hasCompletion = items.map { it["name"] }.containsAll(listOf("Read", "Value", "Describe"))
            report["completionSuccess"] = hasCompletion
            if (hasCompletion) {
                edt { render("completion", (LookupManager.getInstance(project).activeLookup as LookupImpl).component) }
                // Replace the existing call using a normal native lookup selection, not a synthetic edit.
                edt {
                    val lookup = LookupManager.getInstance(project).activeLookup as LookupImpl
                    lookup.currentItem = lookup.items.first { it.lookupString == "Read" }
                    lookup.finishLookup(Lookup.REPLACE_SELECT_CHAR)
                }
                report["afterInsertion"] = edt { document.text }
                report["insertionSuccess"] = edt { document.text.contains("val method: String = box.Read()") }
            }
            save("auto-completion")
            replace(original.replace("box.Read()", "box"))
            focusEditor()
            edt {
                editor.caretModel.moveToOffset(document.text.indexOf("val method: String = box") + "val method: String = box".length)
                TypedAction.getInstance().actionPerformed(editor, '.', DataManager.getInstance().getDataContext(editor.contentComponent))
            }
            var autoItems = emptyList<String>()
            for (attempt in 0 until 100) {
                delay(100)
                autoItems = edt { LookupManager.getInstance(project).activeLookup?.items?.map { it.lookupString }.orEmpty() }
                if (autoItems.containsAll(listOf("Read", "Value", "Describe"))) break
            }
            report["autoCompletion"] = autoItems
            report["autoCompletionSuccess"] = autoItems.containsAll(listOf("Read", "Value", "Describe"))
            replace(original)
            save("parameter-info")
            focusEditor()
            val braceOffset = original.indexOf("Api.Pick(42)") + "Api.Pick".length
            edt {
                editor.caretModel.moveToOffset(braceOffset + 1)
                ShowParameterInfoHandler().invoke(project, editor, PsiDocumentManager.getInstance(project).getPsiFile(document)!!)
            }
            var controller: ParameterInfoControllerBase? = null
            for (attempt in 0 until 100) {
                delay(100)
                controller = edt { ParameterInfoControllerBase.findControllerAtOffset(editor, braceOffset) }
                if (edt { controller?.isHintShown(false) == true }) break
            }
            edt {
                report["parameterInfoVisible"] = controller?.isHintShown(false) == true
                report["parameterInfoObjects"] = controller?.objects?.map { it.toString() }
                controller?.let {
                    val field = it.javaClass.getDeclaredField("myComponent").apply { isAccessible = true }
                    val component = field.get(it) as JComponent
                    report["parameterInfoText"] = componentText(component)
                    render("parameter-info", component)
                    it.dispose()
                }
            }
            save("hover")
            focusEditor()
            val hoverListener = object : EditorMouseMotionListener {
                override fun mouseMoved(event: EditorMouseEvent) {
                    report["hoverEvent"] = mapOf("offset" to event.offset, "overText" to event.isOverText,
                        "area" to event.area.toString(), "consumed" to event.isConsumed)
                }
            }
            edt {
                EditorMouseHoverPopupManager.getInstance()
                editor.addEditorMouseMotionListener(hoverListener)
                report["hoverEnabled"] = EditorSettingsExternalizable.getInstance().isShowQuickDocOnMouseOverElement
            }
            repeat(2) { movement ->
              edt {
                val offset = original.indexOf("Api.Pick(42)") + "Api.".length + 1
                val point = editor.offsetToXY(offset)
                editor.contentComponent.dispatchEvent(MouseEvent(editor.contentComponent, MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, point.x + 2 + movement, point.y + editor.lineHeight / 2, 0, false))
              }
              delay(250)
            }
            var hover: AbstractPopup? = null
            for (attempt in 0 until 100) {
                delay(100)
                hover = edt {
                    val manager = EditorMouseHoverPopupManager.getInstance()
                    val method = manager.javaClass.getDeclaredMethod("getCurrentHint").apply { isAccessible = true }
                    method.invoke(manager) as? AbstractPopup
                }
                if (hover?.isVisible == true) break
            }
            edt {
                editor.removeEditorMouseMotionListener(hoverListener)
                report["hoverVisible"] = hover?.isVisible == true
                hover?.let {
                    report["hoverText"] = componentText(it.content)
                    render("hover", it.content)
                    it.cancel()
                }
            }
            fun hasErrors(key: String) = ((report[key] as Map<*, *>)["errors"] as List<*>).isNotEmpty()
            report["success"] = !hasErrors("baseline") && hasErrors("longArgument") && hasErrors("unterminatedString") &&
                !hasErrors("restored") && hasCompletion && report["insertionSuccess"] == true &&
                report["autoCompletionSuccess"] == true && report["parameterInfoVisible"] == true && report["hoverVisible"] == true
            save("finished")
        } finally {
            replace(original)
        }
        return report
    }
}
