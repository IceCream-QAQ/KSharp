package dev.dotktr

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import java.nio.file.Path

class DotKtStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().messageBus.connect(project)
            .subscribe(VirtualFileManager.VFS_CHANGES, DotKtFilesListener(project))
        project.messageBus.connect(project).subscribe(com.intellij.execution.RunManagerListener.TOPIC, DotKtRunManagerListener(project))
        project.getService(DotKtProjectService::class.java).refresh()
    }
}

class RefreshDotKtAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) { event.project?.getService(DotKtProjectService::class.java)?.refresh(manual = true) }
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
}

class DotKtFilesListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val binding = project.getUserData(DotKtModuleBinding.KEY) ?: return
        val projectRoot = binding.sourceRoot.path + "/"
        val changed = events.any {
            val path = it.path
            FileUtil.pathsEqual(path, binding.projectFile) || binding.watchedFiles.any { input -> FileUtil.pathsEqual(path, input) } ||
                (path.startsWith(projectRoot) && !path.startsWith(projectRoot + "obj/") && !path.startsWith(projectRoot + "bin/") &&
                    ((path.endsWith(".kt") && it !is VFileContentChangeEvent) ||
                    path.endsWith(".props") || path.endsWith(".targets")))
        }
        if (changed) project.getService(DotKtProjectService::class.java).refresh(debounce = true)
    }
}
