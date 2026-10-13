package dotktr.probe

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiManager
import kotlinx.coroutines.delay
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

object DotNetNavigationChecks {
    private data class Case(val file: String, val anchor: String, val delta: Int, val declaration: String)
    private fun <T> edt(action: () -> T): T {
        var value: T? = null
        ApplicationManager.getApplication().invokeAndWait { value = action() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    suspend fun run(project: Project, progress: (Map<String, Any?>) -> Unit): Map<String, Any?> {
        val webFixture = project.getUserData(DotKtModuleBinding.KEY)!!.sourceFiles.any { it.name == "HelloController.kt" }
        val cases = if (webFixture) listOf(
            Case("HelloController.kt", "ControllerBase()", 1, "class ControllerBase"),
            Case("HelloController.kt", "Ok(GreetingResponse", 1, "Ok([ActionResultObjectValue] object? value)"),
            Case("Program.kt", "MapControllers()", 1, "MapControllers(this IEndpointRouteBuilder endpoints)"),
            Case("Program.kt", "CreateBuilder(args)", 1, "CreateBuilder(string[] args)"),
            Case("Routes.kt", "Response.StatusCode", "Response.".length + 1, "int StatusCode { get; set; }"),
        ) else listOf(
            Case("Program.kt", "Pick(42)", 1, "Pick(int value)"),
            Case("Program.kt", "Pick(\"hello\")", 1, "Pick(string value)"),
        )
        val results = mutableListOf<Map<String, Any?>>()
        val service = project.getService(DotNetNavigationService::class.java)
        for ((name, anchor, delta, expectedDeclaration) in cases) {
            val file = project.getUserData(DotKtModuleBinding.KEY)!!.sourceFiles.single { it.name == name }
            val editor = edt {
                LookupManager.getInstance(project).hideActiveLookup()
                FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)!!.also {
                    java.awt.Desktop.getDesktop().requestForeground(true)
                    IdeFocusManager.getInstance(project).requestFocus(it.contentComponent, true)
                }
            }
            delay(700)
            val offset = edt { editor.document.text.indexOf(anchor).also { check(it >= 0) } + delta }
            val target = ReadAction.compute<ClrNavigationTarget?, RuntimeException> {
                ClrNavigationResolver.resolve(PsiManager.getInstance(project).findFile(file)!!.findElementAt(offset)!!)
            }
            val row = linkedMapOf<String, Any?>("source" to name, "anchor" to anchor, "target" to target)
            results += row
            progress(mapOf("phase" to anchor, "cases" to results.toList()))
            if (target == null) {
                row["success"] = false
                continue
            }
            val before = service.lastEvent
            val startedBefore = service.startedRequests
            edt {
                editor.caretModel.moveToOffset(offset)
                val point = editor.offsetToXY(offset)
                val component = editor.contentComponent
                java.awt.Toolkit.getDefaultToolkit().systemEventQueue.postEvent(MouseEvent(component, MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), InputEvent.META_DOWN_MASK, point.x + 2, point.y + editor.lineHeight / 2,
                    0, false, MouseEvent.NOBUTTON))
            }
            delay(500)
            edt {
                val point = editor.offsetToXY(offset)
                for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                    val modifiers = InputEvent.META_DOWN_MASK or (if (id == MouseEvent.MOUSE_PRESSED) InputEvent.BUTTON1_DOWN_MASK else 0)
                    java.awt.Toolkit.getDefaultToolkit().systemEventQueue.postEvent(MouseEvent(editor.contentComponent, id,
                        System.currentTimeMillis(), modifiers, point.x + 2, point.y + editor.lineHeight / 2, 1, false, MouseEvent.BUTTON1))
                }
            }
            var gesture = "cmd-click"
            for (attempt in 0 until 350) {
                delay(100)
                if (service.lastEvent !== before) break
                // A synthetic component mouse event can be consumed before the IDE's global keymap handler.
                // Record the standard action fallback separately; never call our navigation service directly.
                if (attempt == 50 && service.startedRequests == startedBefore) {
                    gesture = "GotoDeclaration action"
                    edt {
                        ActionUtil.invokeAction(ActionManager.getInstance().getAction("GotoDeclaration"),
                            DataManager.getInstance().getDataContext(editor.contentComponent), ActionPlaces.UNKNOWN, null, null)
                    }
                }
            }
            row["gesture"] = gesture
            val event = service.lastEvent.takeIf { it !== before }
            row["event"] = event
            row["expectedDeclaration"] = expectedDeclaration
            row["success"] = event?.get("success")?.asBoolean == true &&
                event["editor"].asJsonObject["lineText"].asString.contains(expectedDeclaration)
            if (row["success"] == true) edt {
                val component = FileEditorManager.getInstance(project).selectedTextEditor!!.component
                val bitmap = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_RGB)
                val graphics = bitmap.createGraphics()
                try { component.paint(graphics) } finally { graphics.dispose() }
                val path = Path.of(System.getProperty("dotktr.probe.report")).parent.resolve("navigation-${results.size}.png")
                ImageIO.write(bitmap, "png", path.toFile())
                row["image"] = path.toString()
            }
            progress(mapOf("phase" to anchor, "cases" to results.toList()))
        }
        if (webFixture) {
            val sources = project.getUserData(DotKtModuleBinding.KEY)!!.sourceFiles
            val source = sources.single { it.name == "Program.kt" }
            val expected = sources.single { it.name == "Routes.kt" }
            val before = service.startedRequests
            val editor = edt {
                FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, source), true)!!.also {
                    it.caretModel.moveToOffset(it.document.text.indexOf("mapDotKtEndpoints()") + 1)
                    IdeFocusManager.getInstance(project).requestFocus(it.contentComponent, true)
                }
            }
            delay(700)
            val interceptsLocal = ReadAction.compute<Boolean, RuntimeException> {
                ClrNavigationResolver.resolve(PsiManager.getInstance(project).findFile(source)!!.findElementAt(editor.caretModel.offset)!!) != null
            }
            edt {
                ActionUtil.invokeAction(ActionManager.getInstance().getAction("GotoDeclaration"),
                    DataManager.getInstance().getDataContext(editor.contentComponent), ActionPlaces.UNKNOWN, null, null)
            }
            var navigated = false
            for (attempt in 0 until 100) {
                delay(100)
                navigated = edt {
                    FileEditorManager.getInstance(project).selectedTextEditor?.let {
                        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(it.document) == expected &&
                            it.document.text.startsWith("mapDotKtEndpoints", it.caretModel.offset)
                    } == true
                }
                if (navigated) break
            }
            results += mapOf("source" to "Program.kt", "anchor" to "mapDotKtEndpoints()",
                "gesture" to "GotoDeclaration action", "target" to expected.path,
                "bridgeIntercepted" to interceptsLocal, "backendRequests" to service.startedRequests - before,
                "success" to (navigated && !interceptsLocal && service.startedRequests == before))
        }
        return mapOf("cases" to results, "success" to results.all { it["success"] == true })
    }
}
