@file:OptIn(org.jetbrains.kotlin.fir.SessionConfiguration::class,
    org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
    org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class,
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package dev.dotktr

import kotc.pipeline.ClrStaticConflictResolverFactory
import kotc.pipeline.ClrStaticVisibilityChecker
import kotc.pipeline.installClrStaticDeserialization
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSession
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionConfigurator
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.fir.FirVisibilityChecker
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.deserialization.FirDeserializationExtension
import org.jetbrains.kotlin.fir.resolve.calls.overloads.ConeCallConflictResolverFactory
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.idea.base.fir.projectStructure.provider.K2IDEProjectStructureProvider

class ClrSessionConfigurator : LLFirSessionConfigurator {
    override fun configure(session: LLFirSession) {
        val log = Logger.getInstance(ClrSessionConfigurator::class.java)
        if (log.isDebugEnabled) log.debug("FIR module ${session.ktModule.javaClass.name}: ${session.ktModule.moduleDescription}; roots=" +
            (session.ktModule as? KaLibraryModule)?.binaryRoots + "; registered=" + session.project.getUserData(DotKtModuleBinding.CLR_LIBRARIES)?.size)
        val owned = when (val module = session.ktModule) {
            is KaSourceModule -> session.project.getUserData(DotKtModuleBinding.KEY)?.module?.let {
                !it.isDisposed && it.name == module.name
            } == true
            is KaLibraryModule -> {
                val libraries = session.project.getUserData(DotKtModuleBinding.CLR_LIBRARIES).orEmpty()
                // KLIB-backed IDE modules expose no binaryRoots in this SDK. Read the
                // underlying IDE library URLs, whose identities came from MSBuild.
                val provider = KotlinProjectStructureProvider.getInstance(session.project) as K2IDEProjectStructureProvider
                val urls = provider.getOpenapiLibrary(module)?.getUrls(OrderRootType.CLASSES).orEmpty()
                if (log.isDebugEnabled && module.libraryName.contains("stdlib")) log.debug("CLR stdlib URLs=${urls.toList()}; registered stdlib=${libraries.filter { "stdlib" in it }}")
                urls.any { url ->
                    val path = url.removePrefix("jar://").removeSuffix("!/")
                    // Jar and local VFS can expose different casing for the same
                    // file on a case-insensitive filesystem.
                    libraries.any { FileUtil.pathsEqual(path, it) }
                }
            }
            else -> false
        }
        if (!owned) return
        session.register(FirDeserializationExtension::class, object : FirDeserializationExtension(session) {} as FirSessionComponent)
        installClrStaticDeserialization(session)
        session.register(FirVisibilityChecker::class, ClrStaticVisibilityChecker)
        session.register(ConeCallConflictResolverFactory::class, ClrStaticConflictResolverFactory)
    }
}
