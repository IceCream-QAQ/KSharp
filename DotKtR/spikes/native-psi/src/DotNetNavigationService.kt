package dotktr.probe

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.jetbrains.rd.framework.FrameworkMarshallers
import com.jetbrains.rd.framework.impl.RdCall
import com.jetbrains.rider.projectView.solution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class DotNetNavigationService(private val project: Project, private val scope: CoroutineScope) {
    private val requestId = AtomicLong()
    val startedRequests: Long get() = requestId.get()
    @Volatile var lastEvent: JsonObject? = null
        private set

    fun navigate(target: ClrNavigationTarget) {
        val ticket = requestId.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            val event = JsonObject().apply {
                addProperty("requestId", ticket)
                add("target", Gson().toJsonTree(target))
            }
            try {
                val response = request(target)
                event.add("backend", response)
                check(response["success"].asBoolean) { response["error"]?.asString ?: "没有找到唯一匹配的 .NET 声明" }
                val editor = awaitDotNetEditor(response)
                event.add("editor", editor)
                event.addProperty("success", true)
                target.fallbackReason?.let {
                    notify("已打开对应 .NET 类型", "该投影暂不能精确定位成员，已打开其所属类型。", NotificationType.INFORMATION)
                }
            } catch (cancelled: CancellationException) {
                if (project.isDisposed) throw cancelled
                event.addProperty("success", false)
                event.addProperty("error", cancelled.message)
                notify(".NET 导航尚未完成", "请等待源码加载后重试。", NotificationType.WARNING)
            } catch (failure: Throwable) {
                event.addProperty("success", false)
                event.addProperty("error", failure.stackTraceToString())
                notify("无法定位 .NET 声明", failure.message ?: "请确认项目引用已经加载。", NotificationType.WARNING)
            }
            lastEvent = event
            val reportPath = System.getProperty("dotktr.probe.report") ?: return@launch
            Files.writeString(Path.of(reportPath).parent.resolve("dotnet-navigation.jsonl"), event.toString() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private suspend fun request(target: ClrNavigationTarget): JsonObject = withTimeout(30_000) {
        val model = project.solution
        lateinit var call: RdCall<String, String>
        ApplicationManager.getApplication().invokeAndWait {
            call = model.getOrCreateExtension("dotktr.probe.lookup") {
                RdCall(FrameworkMarshallers.String, FrameworkMarshallers.String).apply { async = true }
            }
        }
        while (model.isLoaded.valueOrNull != true) delay(100)
        val json = Gson().toJsonTree(target).asJsonObject.apply {
            addProperty("action", "navigate")
            addProperty("consumerProject", project.getUserData(DotKtModuleBinding.KEY)?.projectFile)
        }
        // Initial cache readiness can precede external reference availability. Only retry a
        // missing module/type; ambiguous or mismatched signatures must fail immediately.
        var response = JsonObject()
        repeat(40) { attempt ->
            response = JsonParser.parseString(call.startSuspending(json.toString())).asJsonObject
            response.addProperty("lookupAttempts", attempt + 1)
            if (response["success"].asBoolean || response["retryable"]?.asBoolean != true) return@withTimeout response
            delay(250)
        }
        response
    }

    private suspend fun awaitDotNetEditor(response: JsonObject): JsonObject = withTimeout(30_000) {
        val symbol = response["symbol"].asString
        val declarations = response["declarations"].asJsonArray
        while (true) {
            var state: JsonObject? = null
            ApplicationManager.getApplication().invokeAndWait {
                FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
                    FileDocumentManager.getInstance().getFile(editor.document)?.let { file ->
                        val caret = editor.caretModel.offset
                        val text = editor.document.text
                        val matchesSourceRange = declarations.any {
                            val declaration = it.asJsonObject
                            declaration["file"].asString == file.path &&
                                caret in declaration["start"].asInt..declaration["end"].asInt
                        }
                        // An external source tab is first opened at offset zero, then the
                        // native navigation service positions its caret asynchronously.
                        val matchesExternalName = declarations.size() == 0 && text.startsWith(symbol, caret) &&
                            (caret + symbol.length == text.length || !Character.isJavaIdentifierPart(text[caret + symbol.length]))
                        if (file.fileType.defaultExtension == "cs" && (matchesSourceRange || matchesExternalName)) {
                            val line = editor.document.getLineNumber(caret)
                            state = JsonObject().apply {
                                addProperty("file", file.path)
                                addProperty("fileType", file.fileType.name)
                                addProperty("caret", caret)
                                addProperty("line", line + 1)
                                addProperty("lineText", text.substring(editor.document.getLineStartOffset(line), editor.document.getLineEndOffset(line)))
                                val decompiled = text.contains("Decompiled with JetBrains") || text.contains("Decompiled with")
                                addProperty("decompiled", decompiled)
                                addProperty("sourceKind", if (decompiled) "decompiled" else if (declarations.size() > 0) "solution-source" else "external-source")
                            }
                        }
                    }
                }
            }
            state?.let { return@withTimeout it }
            delay(100)
        }
        error("Unreachable")
    }

    private fun notify(title: String, text: String, type: NotificationType) {
        if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("DotKt Demo")
            .createNotification(title, text, type).notify(project)
    }
}
