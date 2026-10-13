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
        // VFS canonicalizes path spelling (including case on macOS/Windows). Use the
        // very same spelling for library roots, CLR ownership and navigation maps.
        val libraryFiles = snapshot.libraries.associate { path ->
            val file = vfs.refreshAndFindFileByNioFile(path) ?: error("Cached KLIB disappeared: $path")
            path.toString() to file
        }
        val assemblies = snapshot.assemblies.mapKeys { (path, _) -> libraryFiles.getValue(path).path }
        val manager = ModuleManager.getInstance(project)
        val previous = project.getUserData(DotKtModuleBinding.KEY)
        val libraries = libraryFiles.values.map { it.path }.toSet()
        if (previous != null && !previous.module.isDisposed && previous.projectFile == snapshot.projectFile.toString() &&
            previous.targetFramework == snapshot.framework && previous.runtimeIdentifier == snapshot.runtimeIdentifier &&
            previous.sourceFiles.map { it.path }.toSet() == sources.map { it.path }.toSet() &&
            previous.libraryPaths == libraries && previous.referenceAssemblies == assemblies &&
            previous.assemblyIdentities == snapshot.assemblyIdentities) {
            project.putUserData(DotKtModuleBinding.KEY, previous.copy(generation = generation, inputKey = snapshot.inputKey, watchedFiles = snapshot.watchedFiles))
            return@run
        }
        val name = "dotktr-" + snapshot.projectFile.toString().hashCode().toUInt().toString(16) + "-$generation"
        val module = manager.newNonPersistentModule(name, "JAVA_MODULE")
        try {
            val roots = ModuleRootManager.getInstance(module).modifiableModel
            roots.addContentEntry(root).addSourceFolder(root, false)
            libraryFiles.values.forEach { file ->
                val library = roots.moduleLibraryTable.createLibrary(file.name)
                val model = library.modifiableModel as LibraryEx.ModifiableModelEx
                model.kind = KotlinCommonLibraryKind
                model.addRoot("jar://${file.path}!/", OrderRootType.CLASSES)
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
            model.addFacet(facets.createFacet(KotlinFacetType.INSTANCE, "KSharp", configuration, null))
            model.commit()
            project.putUserData(DotKtModuleBinding.CLR_LIBRARIES, libraries)
            project.putUserData(DotKtModuleBinding.KEY, DotKtModuleBinding(module, root, sources,
                assemblies, snapshot.projectFile.toString(), generation, snapshot.framework,
                snapshot.runtimeIdentifier, libraries, snapshot.assemblyIdentities, snapshot.inputKey, snapshot.watchedFiles))
            previous?.module?.takeIf { !it.isDisposed }?.let(manager::disposeModule)
            DotKtRunTasks.configureExisting(project, snapshot.projectFile.toString())
        } catch (failure: Throwable) {
            if (project.getUserData(DotKtModuleBinding.KEY)?.module != module) manager.disposeModule(module)
            throw failure
        }
    }
}
