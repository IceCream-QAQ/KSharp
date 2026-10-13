package dev.dotktr

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.util.function.Function
import javax.swing.JButton
import javax.swing.JComponent

/** Uses the normal editor-top extension; only .ktproj editors receive this panel. */
class KtProjectEditorNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!file.extension.equals("ktproj", true)) return null
        val service = project.getService(DotKtProjectService::class.java)
        val status = service.status
        return Function {
            val panel = JBPanel<JBPanel<*>>(BorderLayout(12, 0)).apply {
                name = "DotKt.KlibCachePanel"
                border = JBUI.Borders.empty(5, 12)
            }
            val forThisProject = FileUtil.pathsEqual(status.projectFile, file.path)
            val label = JBLabel(if (forThisProject) "KSharp · " + status.message.lineSequence().first().take(160) else
                "KSharp · ${file.name} · Refresh to load this project's KLIB dependencies")
            label.toolTipText = if (forThisProject) status.message else "This preview analyzes one DotKt project at a time."
            panel.add(label, BorderLayout.CENTER)
            panel.add(JButton(if (status.busy && forThisProject) "Refreshing KLIB…" else "Refresh KLIB", AllIcons.Actions.Refresh).apply {
                name = "DotKt.RefreshKlibButton"
                isFocusable = false
                isEnabled = !status.busy
                toolTipText = "Recheck this project's DLL references and update its IDE KLIB cache"
                addActionListener {
                    FileDocumentManager.getInstance().getDocument(file)?.let { document ->
                        FileDocumentManager.getInstance().saveDocument(document)
                    }
                    service.refresh(manual = true, requestedProject = file.path)
                }
            }, BorderLayout.EAST)
            panel
        }
    }
}
