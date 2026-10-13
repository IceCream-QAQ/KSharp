@file:OptIn(org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class)

package dev.dotktr

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinContentScopeRefiner
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.idea.base.fir.projectStructure.FirKaModuleFactory
import org.jetbrains.kotlin.idea.base.fir.projectStructure.provider.K2IDEProjectStructureProvider
import org.jetbrains.kotlin.idea.base.projectStructure.KaSourceModuleKind
import org.jetbrains.kotlin.idea.base.projectStructure.RootKindFilter
import org.jetbrains.kotlin.idea.base.projectStructure.RootKindMatcher
import org.jetbrains.kotlin.idea.base.analysis.RootKindMatcherImpl
import org.jetbrains.kotlin.psi.KtFile

data class DotKtModuleBinding(val module: Module, val sourceRoot: VirtualFile, val sourceFiles: List<VirtualFile>,
                             val referenceAssemblies: Map<String, String> = emptyMap(), val projectFile: String? = null,
                             val generation: Long = 0, val targetFramework: String = "") {
    companion object {
        val KEY = Key.create<DotKtModuleBinding>("dotktr.module.binding")
        val CLR_LIBRARIES = Key.create<Set<String>>("dotktr.clr.libraries")
    }
}

/** Explicit ownership prevents Rider's asynchronously updated .NET module from claiming Kotlin files. */
class DotKtModuleFactory : FirKaModuleFactory {
    override fun createKaModuleByPsiFile(file: PsiFile): KaModule? {
        if (file !is KtFile || !file.isPhysical) return null
        val binding = file.project.getUserData(DotKtModuleBinding.KEY) ?: return null
        if (file.virtualFile !in binding.sourceFiles) return null
        val provider = KotlinProjectStructureProvider.getInstance(file.project) as K2IDEProjectStructureProvider
        return provider.getKaSourceModule(binding.module, KaSourceModuleKind.PRODUCTION)
    }
}

class DotKtContentScopeRefiner : KotlinContentScopeRefiner {
    override fun getEnlargementScopes(module: KaModule): List<GlobalSearchScope> {
        if (module !is KaSourceModule) return emptyList()
        val binding = module.project.getUserData(DotKtModuleBinding.KEY) ?: return emptyList()
        val provider = KotlinProjectStructureProvider.getInstance(module.project) as K2IDEProjectStructureProvider
        if (provider.getOpenapiModule(module) != binding.module) return emptyList()
        return listOf(GlobalSearchScope.filesScope(module.project, binding.sourceFiles))
    }
    override fun getRestrictionScopes(module: KaModule): List<GlobalSearchScope> = getEnlargementScopes(module)
}

/** Rider's project index doesn't classify .ktproj files as Java/Kotlin source roots.
 * Use the explicit imported source set for Kotlin's highlighting eligibility checks. */
class DotKtRootKindMatcher(private val project: Project) : RootKindMatcher {
    private val fallback = RootKindMatcherImpl(project)
    override fun matches(filter: RootKindFilter, virtualFile: VirtualFile): Boolean {
        val binding = project.getUserData(DotKtModuleBinding.KEY)
        if (binding != null && !binding.module.isDisposed && virtualFile in binding.sourceFiles) {
            return filter.includeProjectSourceFiles
        }
        return fallback.matches(filter, virtualFile)
    }
}
