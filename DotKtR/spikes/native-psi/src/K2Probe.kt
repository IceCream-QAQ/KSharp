@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package dotktr.probe

import com.intellij.facet.FacetManager
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.stubs.StubUpdatingIndex
import com.intellij.psi.stubs.StubElementRegistryService
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.session.useSiteSession
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.cli.common.arguments.K2MetadataCompilerArguments
import org.jetbrains.kotlin.config.CompilerSettings
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.idea.base.platforms.KotlinCommonLibraryKind
import org.jetbrains.kotlin.idea.facet.KotlinFacetConfigurationImpl
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path

object K2Probe {
    @JvmStatic
    fun run(project: Project, source: String, klibDirectory: String, stdlib: String): Map<String, Any?> {
        val report = linkedMapOf<String, Any?>()
        val sourcePath = Path.of(source)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourcePath)!!
        val snapshot = JsonParser.parseString(Files.readString(Path.of(System.getProperty("dotktr.probe.analysis.snapshot")))).asJsonObject
        val libraries = snapshot["references"].asJsonArray.map { Path.of(it.asJsonObject["klib"].asString) } +
            listOf(Path.of(snapshot["stdlib"].asString))
        check(Path.of(stdlib).normalize() == Path.of(snapshot["stdlib"].asString).normalize())
        val originalRoot = Path.of(snapshot["sourceRoot"].asString)
        val sourceFiles = snapshot["sourceFiles"].asJsonArray.map { entry ->
            val relative = originalRoot.relativize(Path.of(entry.asString))
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourcePath.parent.resolve(relative))!!
        }
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> {
                val moduleManager = ModuleManager.getInstance(project)
                moduleManager.findModuleByName("dotktr-kotlin")?.let { moduleManager.disposeModule(it) }
                // This graph is imported from .ktproj/MSBuild, not an .iml file. A persistent module
                // is removed when the Java plugin's delayed JPS load replaces file-owned entities.
                val module = moduleManager.newNonPersistentModule("dotktr-kotlin", "JAVA_MODULE")
                Disposer.register(module) {
                    Files.writeString(Path.of(System.getProperty("dotktr.probe.root"), "module-disposal.txt"),
                        Throwable("DotKt module disposed").stackTraceToString())
                }
                val roots = ModuleRootManager.getInstance(module).modifiableModel
                roots.addContentEntry(virtualFile.parent).addSourceFolder(virtualFile.parent, false)
                for (path in libraries) {
                    val library = roots.moduleLibraryTable.createLibrary(path.fileName.toString())
                    val model = library.modifiableModel as LibraryEx.ModifiableModelEx
                    model.kind = KotlinCommonLibraryKind
                    model.addRoot("jar://${path.toAbsolutePath()}!/", OrderRootType.CLASSES)
                    model.commit()
                }
                roots.commit()
                val configuration = KotlinFacetType.INSTANCE.createDefaultConfiguration()
                configuration.settings.apply {
                    useProjectSettings = false
                    targetPlatform = CommonPlatforms.defaultCommonPlatform
                    languageLevel = LanguageVersion.KOTLIN_2_4
                    apiLevel = LanguageVersion.KOTLIN_2_4
                    compilerArguments = K2MetadataCompilerArguments().apply {
                        languageVersion = "2.4"
                        apiVersion = "2.4"
                        multiPlatform = true
                    }
                    compilerSettings = CompilerSettings().apply {
                        additionalArguments = "-XXLanguage:+CompanionBlocksAndExtensions"
                    }
                    updateMergedArguments()
                }
                val manager = FacetManager.getInstance(module)
                val facet = manager.createFacet(KotlinFacetType.INSTANCE, "DotKt", configuration, null)
                val facets = manager.createModifiableModel()
                facets.addFacet(facet)
                facets.commit()
                val referenceAssemblies = snapshot["references"].asJsonArray.associate {
                    it.asJsonObject["klib"].asString to it.asJsonObject["sourceAssembly"].asString
                }
                val projectFile = virtualFile.parent.children.singleOrNull { it.extension == "ktproj" }?.path
                project.putUserData(DotKtModuleBinding.KEY, DotKtModuleBinding(module, virtualFile.parent, sourceFiles, referenceAssemblies, projectFile))
                report["module"] = module.name
                report["klibCount"] = libraries.size
            }
        }
        DumbService.getInstance(project).waitForSmartMode()
        ReadAction.run<RuntimeException> {
            val kt = PsiManager.getInstance(project).findFile(virtualFile) as KtFile
            report["sourceIndexing"] = sourceFiles.map { file ->
                val psi = PsiManager.getInstance(project).findFile(file) as KtFile
                mapOf("file" to file.path, "fileType" to file.fileType.name,
                    "canHaveStub" to StubUpdatingIndex.canHaveStub(file),
                    "stubDefinition" to StubElementRegistryService.getInstance().getStubDescriptor(psi.language)?.stubDefinition?.javaClass?.name)
            }
            val fileIndex = ProjectFileIndex.getInstance(project)
            report["fileIndexClass"] = fileIndex.javaClass.name
            report["sourceRoot"] = fileIndex.getSourceRootForFile(virtualFile)?.path
            report["fileModule"] = fileIndex.getModuleForFile(virtualFile)?.name
            report["isInSource"] = fileIndex.isInSourceContent(virtualFile)
            report["modules"] = ModuleManager.getInstance(project).modules.map { module ->
                mapOf("name" to module.name, "sources" to ModuleRootManager.getInstance(module).sourceRoots.map { it.path })
            }
            report["sourcePsi"] = kt.javaClass.name
            analyze(kt) {
              with(useSiteSession) {
                report["analysisModule"] = useSiteModule.javaClass.name
                report["platform"] = useSiteModule.targetPlatform.toString()
                report["dependencyCount"] = useSiteModule.directRegularDependencies.size
                report["stringSymbol"] = findClass(ClassId.topLevel(FqName("kotlin.String")))?.let { symbol ->
                    mapOf("origin" to symbol.origin.toString(), "file" to symbol.psi?.containingFile?.virtualFile?.path,
                        "superTypes" to symbol.superTypes.map { it.toString() })
                }
                val calls = PsiTreeUtil.findChildrenOfType(kt, KtCallExpression::class.java).map { call ->
                    val symbol = call.resolveSymbol()
                    mapOf("text" to call.text, "offset" to call.textOffset,
                        "symbol" to (symbol?.callableId?.toString() ?: (symbol as? KaConstructorSymbol)?.containingClassId?.toString()),
                        "resolved" to (symbol != null),
                        "expressionType" to call.expressionType?.toString(),
                        "parameters" to symbol?.valueParameters?.map { it.returnType.toString() },
                        "returnType" to symbol?.returnType?.toString(),
                        "origin" to symbol?.origin?.toString(),
                        "declarationFile" to symbol?.psi?.containingFile?.virtualFile?.path)
                }
                report["calls"] = calls
                val diagnostics = kt.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS).map {
                    mapOf("factory" to it.factoryName, "severity" to it.severity.toString(), "message" to it.defaultMessage)
                }
                report["diagnostics"] = diagnostics
                report["semanticSuccess"] = calls.size == (snapshot["expectedCallCount"]?.asInt ?: 7) && calls.all { it["resolved"] == true } &&
                    diagnostics.none { it["severity"] == "ERROR" }
              }
            }
            val otherFiles = sourceFiles.filter { it != virtualFile }.map { file ->
                val other = PsiManager.getInstance(project).findFile(file) as KtFile
                analyze(other) {
                  with(useSiteSession) {
                    mapOf("source" to file.path,
                        "calls" to PsiTreeUtil.findChildrenOfType(other, KtCallExpression::class.java).map { call ->
                            mapOf("text" to call.text, "symbol" to call.resolveSymbol()?.callableId?.toString(),
                                "expressionType" to call.expressionType?.toString())
                        },
                        "diagnostics" to other.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS).map {
                            mapOf("factory" to it.factoryName, "severity" to it.severity.toString(), "message" to it.defaultMessage)
                        })
                  }
                }
            }
            report["additionalFiles"] = otherFiles
            val secondaryErrors = otherFiles.any { file ->
                (file["diagnostics"] as List<Map<String, Any?>>).any { it["severity"] == "ERROR" }
            }
            if (secondaryErrors) report["semanticSuccess"] = false
        }
        return report
    }
}
