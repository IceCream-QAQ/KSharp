package dotktr.probe

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.jetbrains.rd.framework.FrameworkMarshallers
import com.jetbrains.rd.framework.impl.RdCall
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

object NavigationProbeClient {
    @JvmStatic
    fun waitReady(project: Project): Map<String, Any?> {
        val model = project.solution
        lateinit var call: RdCall<String, String>
        ApplicationManager.getApplication().invokeAndWait {
            call = model.getOrCreateExtension("dotktr.probe.lookup") {
                RdCall(FrameworkMarshallers.String, FrameworkMarshallers.String).apply { async = true }
            }
        }
        return runBlocking {
            withTimeout(60_000) {
                while (model.isLoaded.valueOrNull != true) delay(100)
                var ping = JsonParser.parseString(call.startSuspending("{\"action\":\"ping\"}")).asJsonObject
                while (!ping["ready"].asBoolean) {
                    delay(100)
                    ping = JsonParser.parseString(call.startSuspending("{\"action\":\"ping\"}")).asJsonObject
                }
                mapOf("ready" to true, "status" to ping)
            }
        }
    }

    @JvmStatic
    fun run(project: Project, analysis: Map<String, Any?>): Map<String, Any?> {
        val model = project.solution
        lateinit var call: RdCall<String, String>
        ApplicationManager.getApplication().invokeAndWait {
            call = model.getOrCreateExtension("dotktr.probe.lookup") {
                RdCall(FrameworkMarshallers.String, FrameworkMarshallers.String).apply { async = true }
            }
        }
        return runBlocking {
            withTimeout(60_000) {
                while (model.isLoaded.valueOrNull != true) delay(100)
                var ping = JsonParser.parseString(call.startSuspending("{\"action\":\"ping\"}")).asJsonObject
                while (!ping["ready"].asBoolean) {
                    delay(100)
                    ping = JsonParser.parseString(call.startSuspending("{\"action\":\"ping\"}")).asJsonObject
                }
                val calls = analysis["calls"] as List<Map<String, Any?>>
                val overloads = calls.filter { it["symbol"] == "DotKtProbe/Api.Pick" }
                val targets = overloads.map { resolved ->
                    // Initial bridge fixture: exact formal signatures, never name-only overload lookup.
                    // Broader projections need the origin map called out in the validation checklist.
                    val symbol = resolved["symbol"] as String
                    val owner = symbol.substringBeforeLast('.').replace('/', '.')
                    val parameters = (resolved["parameters"] as List<String>).map {
                        when (it) {
                            "kotlin/Int" -> "System.Int32"
                            "kotlin/String" -> "System.String"
                            else -> error("Unsupported signature in navigation fixture: $it")
                        }
                    }
                    val id = "M:$owner.${symbol.substringAfterLast('.')}(${parameters.joinToString(",")})"
                    val navigate = java.lang.Boolean.getBoolean("dotktr.probe.navigate")
                    val request = Gson().toJson(mapOf("action" to (if (navigate) "navigate" else "lookup"), "owner" to owner, "xmlDocId" to id))
                    val target = JsonParser.parseString(call.startSuspending(request)).asJsonObject
                    if (navigate && target["success"].asBoolean) {
                        val declaration = target["declarations"].asJsonArray[0].asJsonObject
                        withTimeout(10_000) {
                            while (true) {
                                var file: String? = null
                                var caret = -1
                                ApplicationManager.getApplication().invokeAndWait {
                                    FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
                                        file = FileDocumentManager.getInstance().getFile(editor.document)?.path
                                        caret = editor.caretModel.offset
                                    }
                                }
                                if (file == declaration["file"].asString && caret in declaration["start"].asInt..declaration["end"].asInt) {
                                    target.addProperty("editorFile", file)
                                    target.addProperty("editorCaret", caret)
                                    break
                                }
                                delay(50)
                            }
                        }
                    }
                    target
                }
                mapOf("ping" to ping, "targets" to targets,
                    "success" to (overloads.size == 2 && targets.all { it["success"].asBoolean }))
            }
        }
    }
}
