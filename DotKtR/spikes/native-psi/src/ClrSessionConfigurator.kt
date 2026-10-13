@file:OptIn(
    org.jetbrains.kotlin.fir.SessionConfiguration::class,
    org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals::class,
    org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
)

package dotktr.probe

import kotc.pipeline.ClrStaticConflictResolverFactory
import kotc.pipeline.ClrStaticVisibilityChecker
import kotc.pipeline.installClrStaticDeserialization
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSession
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionConfigurator
import org.jetbrains.kotlin.fir.FirVisibilityChecker
import org.jetbrains.kotlin.fir.FirSessionComponent
import org.jetbrains.kotlin.fir.deserialization.FirDeserializationExtension
import org.jetbrains.kotlin.fir.resolve.calls.overloads.ConeCallConflictResolverFactory

/** Experimental adapter: reuse the pinned dotkt source rules inside the IDE's FIR sessions. */
class ClrSessionConfigurator : LLFirSessionConfigurator {
    override fun configure(session: LLFirSession) {
        if (!java.lang.Boolean.getBoolean("dotktr.probe.clr.sessions")) return
        // The IDE composes JVM/JS/Native deserializers for Common modules. dotkt explicitly disables
        // JVM deserialization in its metadata factory; retain that boundary rather than importing
        // JVM-only Serializable supertypes into the CLR stdlib. Upcast to replace, not compose.
        session.register(FirDeserializationExtension::class,
            object : FirDeserializationExtension(session) {} as FirSessionComponent)
        installClrStaticDeserialization(session)
        session.register(FirVisibilityChecker::class, ClrStaticVisibilityChecker)
        session.register(ConeCallConflictResolverFactory::class, ClrStaticConflictResolverFactory)
    }
}
