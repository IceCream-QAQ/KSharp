package dev.dotktr

import com.intellij.openapi.components.*
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import javax.swing.*
import java.awt.BorderLayout
import java.awt.GridLayout

@Service(Service.Level.PROJECT)
@State(name = "DotKtR", storages = [Storage("dotktr.xml")])
class DotKtSettings : PersistentStateComponent<DotKtSettings.Options> {
    data class Options(var autoImport: Boolean = true, var dotnetPath: String = "",
                       var projectPath: String = "", var targetFramework: String = "")
    private var options = Options()
    override fun getState(): Options = options
    override fun loadState(state: Options) { options = state }
}

class DotKtConfigurable(private val project: Project) : SearchableConfigurable {
    private val automatic = JCheckBox("Import and refresh DotKt project automatically")
    private val executable = JTextField()
    private val projectFile = JTextField()
    private val framework = JTextField()
    override fun getId() = "dev.dotktr.settings"
    override fun getDisplayName() = "DotKt"
    override fun createComponent(): JComponent = JPanel(BorderLayout(0, 12)).apply {
        val fields = JPanel(GridLayout(0, 1, 0, 6))
        fields.add(automatic)
        fields.add(JLabel("dotnet executable (empty: PATH or ~/.dotnet)")); fields.add(executable)
        fields.add(JLabel(".ktproj path (empty: the only DotKt project in the solution)")); fields.add(projectFile)
        fields.add(JLabel("Target framework (empty: project's single target framework)")); fields.add(framework)
        fields.add(JLabel(project.getService(DotKtProjectService::class.java).status.message))
        add(fields, BorderLayout.NORTH)
        reset()
    }
    private fun edited() = DotKtSettings.Options(automatic.isSelected, executable.text.trim(), projectFile.text.trim(), framework.text.trim())
    override fun isModified() = edited() != project.getService(DotKtSettings::class.java).state
    override fun apply() {
        project.getService(DotKtSettings::class.java).loadState(edited())
        project.getService(DotKtProjectService::class.java).refresh(manual = true)
    }
    override fun reset() {
        project.getService(DotKtSettings::class.java).state.let {
            automatic.isSelected = it.autoImport; executable.text = it.dotnetPath
            projectFile.text = it.projectPath; framework.text = it.targetFramework
        }
    }
}
