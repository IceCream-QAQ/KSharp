package dev.dotktr

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.execution.ParametersListUtil
import com.jetbrains.rider.model.RunnableProject
import com.jetbrains.rider.model.runnableProjectsModel
import com.jetbrains.rider.projectView.solution
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfiguration
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfigurationType
import com.jetbrains.rider.run.configurations.project.setUpFromProject
import com.jetbrains.rider.build.tasks.CleanProjectBeforeRunTask
import com.jetbrains.rider.build.tasks.BeforeRunProjectItem
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinMainFunctionDetector
import org.jetbrains.kotlin.idea.codeInsight.KotlinRunLineMarkerHider
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** The compiler chooses the .NET project entry point. No JVM main class is generated here. */
object DotKtMainContext {
    fun binding(element: PsiElement): DotKtModuleBinding? {
        val file = element.containingFile as? KtFile ?: return null
        val binding = element.project.getUserData(DotKtModuleBinding.KEY) ?: return null
        return binding.takeIf { !it.module.isDisposed && file.virtualFile in it.sourceFiles && it.projectFile != null }
    }

    fun main(element: PsiElement?): KtNamedFunction? {
        if (element == null || binding(element) == null) return null
        val function = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false) ?: return null
        if (function.name != "main" || !function.isTopLevel || function.receiverTypeReference != null ||
            function.hasModifier(KtTokens.SUSPEND_KEYWORD) || function.typeParameters.isNotEmpty()) return null
        return function.takeIf { KotlinMainFunctionDetector.getInstanceDumbAware(element.project).isMain(it) }
    }

    fun runnable(element: PsiElement): RunnableProject? {
        val binding = binding(element) ?: return null
        return element.project.solution.runnableProjectsModel.projects.valueOrNull.orEmpty()
            .filter { it.projectFilePath == binding.projectFile && DotNetProjectConfigurationType.isTypeApplicable(it.kind) }
            .singleOrNull()?.takeIf { it.projectOutputs.singleOrNull()?.tfm != null }
    }
}

class DotKtRunConfigurationProducer : LazyRunConfigurationProducer<DotNetProjectConfiguration>(), DumbAware {
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(DotNetProjectConfigurationType::class.java).factory

    override fun setupConfigurationFromContext(configuration: DotNetProjectConfiguration,
                                                context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val main = DotKtMainContext.main(context.psiLocation) ?: return false
        val runnable = DotKtMainContext.runnable(main) ?: return false
        val output = runnable.projectOutputs.single()
        configuration.name = "${runnable.name} (KSharp)"
        configuration.parameters.apply {
            setUpFromProject(runnable)
            projectTfm = output.tfm!!.presentableName
            exePath = output.exePath
            programParameters = ParametersListUtil.join(output.defaultArguments)
            workingDirectory = output.workingDirectory
        }
        // Context ordering first compares PSI specificity, before producer preference.
        // Keep the same token as Kotlin's producer so our CLR preference is considered.
        sourceElement.set(context.psiLocation ?: main)
        DotKtRunTasks.ensureCleanBuild(configuration)
        return true
    }

    override fun isConfigurationFromContext(configuration: DotNetProjectConfiguration, context: ConfigurationContext): Boolean {
        val main = DotKtMainContext.main(context.psiLocation) ?: return false
        val runnable = DotKtMainContext.runnable(main) ?: return false
        // Reuse the user's existing .NET configuration, preserving arguments and environment.
        return configuration.parameters.projectFilePath == runnable.projectFilePath &&
            (configuration.parameters.projectTfm.isEmpty() || configuration.parameters.projectTfm == runnable.projectOutputs.single().tfm!!.presentableName)
    }

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
        !other.isProducedBy(DotKtRunConfigurationProducer::class.java)

    override fun isPreferredConfiguration(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
        shouldReplace(self, other)

    override fun findExistingConfiguration(context: ConfigurationContext): RunnerAndConfigurationSettings? =
        super.findExistingConfiguration(context)?.also {
            (it.configuration as? DotNetProjectConfiguration)?.let(DotKtRunTasks::ensureCleanBuild)
        }
}

/** The pinned DotKt targets can skip IL emission after Rider recompiles the C#
 * placeholder. Use public native project Clean + Build until that upstream
 * incremental edge case is fixed; do not manipulate private compiler stamps. */
object DotKtRunTasks {
    fun ensureCleanBuild(configuration: DotNetProjectConfiguration) {
        val tasks = configuration.beforeRunTasks
        val buildIndex = tasks.indexOfFirst { it.providerId.toString() == "Build" && it.isEnabled }
        if (buildIndex < 0 || tasks.any { it is CleanProjectBeforeRunTask && it.isEnabled }) return
        val clean = CleanProjectBeforeRunTask().apply { isEnabled = true; selectedProject = BeforeRunProjectItem(true) }
        configuration.beforeRunTasks = tasks.toMutableList().apply { add(buildIndex, clean) }
    }
    fun configureExisting(project: com.intellij.openapi.project.Project, path: String) {
        RunManager.getInstance(project).allSettings.mapNotNull { it.configuration as? DotNetProjectConfiguration }
            .filter { it.parameters.projectFilePath == path }.forEach(::ensureCleanBuild)
    }
}

class DotKtRunManagerListener(private val project: com.intellij.openapi.project.Project) : RunManagerListener {
    override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) {
        val configuration = settings.configuration as? DotNetProjectConfiguration ?: return
        if (configuration.parameters.projectFilePath == project.getUserData(DotKtModuleBinding.KEY)?.projectFile)
            DotKtRunTasks.ensureCleanBuild(configuration)
    }
}

/** Suppress only the Kotlin plugin's marker for files owned by this DotKt module. */
class DotKtJvmRunMarkerHider : KotlinRunLineMarkerHider, DumbAware {
    override fun shouldHideRunLineMarker(element: PsiElement): Boolean = DotKtMainContext.binding(element) != null
}

class DotKtRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    override fun getInfo(element: PsiElement): Info? = null
    override fun getSlowInfo(element: PsiElement): Info? {
        val function = element.parent as? KtNamedFunction ?: return null
        if (function.nameIdentifier != element || DotKtMainContext.main(element) == null ||
            DotKtMainContext.runnable(element) == null) return null
        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions()) { "Run .NET project (KSharp)" }
    }
}
