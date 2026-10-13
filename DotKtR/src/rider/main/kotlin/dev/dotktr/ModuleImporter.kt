package dev.dotktr

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.config.CompilerSettings
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.idea.base.platforms.KotlinCommonLibraryKind
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.platform.CommonPlatforms

object ModuleImporter {
    /** Called on EDT after MSBuild has completed and validated all inputs. */
    fun install(project: Project, snapshot: MsBuildSnapshot, generation: Long) = WriteAction.run<RuntimeException> {
        val vfs = LocalFileSystem.getInstance()
        val root = vfs.refreshAndFindFileByNioFile(snapshot.projectFile.parent) ?: error("Project directory disappeared.")
        val sources = snapshot.sources.map { vfs.refreshAndFindFileByNioFile(it) ?: error("Source disappeared: $it") }
        snapshot.libraries.forEach { vfs.refreshAndFindFileByNioFile(it)?.refresh(false, false) }
        val manager = ModuleManager.getInstance(project)
        val previous = project.getUserData(DotKtModuleBinding.KEY)
        val name = "dotktr-" + snapshot.projectFile.toString().hashCode().toUInt().toString(16)
        project.putUserData(DotKtModuleBinding.KEY, null)
        project.putUserData(DotKtModuleBinding.CLR_LIBRARIES, snapshot.libraries.map { it.toString() }.toSet())
        previous?.module?.takeIf { !it.isDisposed }?.let(manager::disposeModule)
        manager.findModuleByName(name)?.let(manager::disposeModule)
        val module = manager.newNonPersistentModule(name, "JAVA_MODULE")
        try {
            val roots = ModuleRootManager.getInstance(module).modifiableModel
            roots.addContentEntry(root).addSourceFolder(root, false)
            snapshot.libraries.forEach { path ->
                val library = roots.moduleLibraryTable.createLibrary(path.fileName.toString())
                val model = library.modifiableModel as LibraryEx.ModifiableModelEx
                model.kind = KotlinCommonLibraryKind
                model.addRoot("jar://$path!/", OrderRootType.CLASSES)
                model.commit()
            }
            roots.commit()
            val configuration = KotlinFacetType.INSTANCE.createDefaultConfiguration()
            configuration.settings.apply {
                useProjectSettings = false
                targetPlatform = CommonPlatforms.defaultCommonPlatform
                languageLevel = LanguageVersion.KOTLIN_2_4; apiLevel = LanguageVersion.KOTLIN_2_4
                compilerArguments = K2MetadataCompilerArguments().apply {
                    languageVersion = "2.4"; apiVersion = "2.4"; multiPlatform = true
                }
                compilerSettings = CompilerSettings().apply { additionalArguments = "-XXLanguage:+CompanionBlocksAndExtensions" }
                updateMergedArguments()
            }
            val facets = FacetManager.getInstance(module)
            val model = facets.createModifiableModel()
            model.addFacet(facets.createFacet(KotlinFacetType.INSTANCE, "DotKt", configuration, null))
            model.commit()
            project.putUserData(DotKtModuleBinding.KEY, DotKtModuleBinding(module, root, sources,
                snapshot.assemblies, snapshot.projectFile.toString(), generation, snapshot.framework))
            DotKtRunTasks.configureExisting(project, snapshot.projectFile.toString())
        } catch (failure: Throwable) {
            manager.disposeModule(module)
            project.putUserData(DotKtModuleBinding.CLR_LIBRARIES, emptySet())
            throw failure
        }
    }
}
