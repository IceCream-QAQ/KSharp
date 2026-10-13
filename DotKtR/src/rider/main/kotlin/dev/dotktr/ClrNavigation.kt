@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.analysis.api.KaImplementationDetail::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class)

package dev.dotktr

import com.intellij.navigation.DirectNavigationProvider
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationRequests
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.session.useSiteSession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaTypeParameterOwnerSymbol
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import javax.swing.Icon

/** Immutable data only: no KaSymbol or analysis session escapes the read action. */
data class ClrNavigationTarget(
    val owner: String,
    val xmlDocId: String,
    val sourceAssembly: String?,
    val kotlinName: String,
    val targetKind: String,
    val fallbackReason: String? = null,
    val consumerProject: String? = null,
    val generation: Long = 0,
)

object ClrNavigationResolver {
    fun resolve(element: PsiElement): ClrNavigationTarget? {
        val file = element.containingFile as? KtFile ?: return null
        val binding = file.project.getUserData(DotKtModuleBinding.KEY) ?: return null
        if (file.virtualFile !in binding.sourceFiles || binding.module.isDisposed) return null
        val reference = PsiTreeUtil.getParentOfType(element, KtNameReferenceExpression::class.java, false) ?: return null
        return analyze(reference) {
          with(useSiteSession) {
            val call = reference.parent as? KtCallExpression
            var symbol = (if (call?.calleeExpression == reference) call.resolveSymbol() else reference.resolveSymbol()) as? KaDeclarationSymbol
                ?: return@with null
            if (symbol is KaCallableSymbol) symbol = symbol.fakeOverrideOriginal
            if (symbol is KaConstructorSymbol) symbol = findClass(symbol.containingClassId ?: return@with null) ?: return@with null
            if (symbol is KaSamConstructorSymbol) symbol = symbol.functionalInterface
            val declarationPath = symbol.psi?.containingFile?.virtualFile?.path ?: return@with null
            if (!declarationPath.contains(".klib!/")) return@with null

            fun annotation(declaration: KaDeclarationSymbol, name: String): Map<String, String>? {
                val value = declaration.annotations.firstOrNull { it.classId?.asSingleFqName()?.asString() == name } ?: return null
                return value.arguments.mapNotNull { argument ->
                    val constant = (argument.expression as? KaAnnotationValue.ConstantValue)?.value?.value as? String
                    constant?.let { argument.name.asString() to it }
                }.toMap()
            }
            fun physicalClass(clazz: KaClassLikeSymbol): String? =
                annotation(clazz, "kotlin.clr.ClrExternal")?.get("physicalOwner") ?:
                    annotation(clazz, "kotlin.clr.ClrTypeAlias")?.get("name")

            val callable = symbol as? KaCallableSymbol
            val clazz = (symbol as? KaNamedClassSymbol) ?: (symbol.containingDeclaration as? KaNamedClassSymbol) ?:
                callable?.callableId?.classId?.let { findClass(it) }
            val ownOrigin = annotation(symbol, "kotlin.clr.ClrExternal")
            val intrinsic = annotation(symbol, "kotlin.clr.ClrIntrinsic")?.get("name")
            val owner = ownOrigin?.get("physicalOwner") ?: clazz?.let(::physicalClass) ?:
                intrinsic?.takeIf { '.' in it }?.substringBeforeLast('.') ?: return@with null
            val sourceAssembly = binding.referenceAssemblies[declarationPath.substringBefore("!/")]
            val kotlinName = callable?.callableId?.toString() ?: (symbol as? KaNamedClassSymbol)?.classId?.asSingleFqName()?.asString()
                ?: reference.getReferencedName()
            val ownerInId = owner.replace('+', '.')
            fun typeTarget(reason: String? = null) = ClrNavigationTarget(owner, "T:$ownerInId", sourceAssembly, kotlinName, "type", reason, binding.projectFile, binding.generation)
            if (symbol is KaNamedClassSymbol) return@with typeTarget()

            // XML documentation signatures describe formal declarations, never inferred argument types.
            // Unknown projections deliberately navigate to the owning type instead of guessing an overload.
            val valueTypes = setOf("System.Boolean", "System.Byte", "System.SByte", "System.Int16", "System.UInt16",
                "System.Int32", "System.UInt32", "System.Int64", "System.UInt64", "System.Single", "System.Double",
                "System.Decimal", "System.Char", "System.IntPtr", "System.UIntPtr")
            fun typeName(input: KaType): String {
                val type = if (input is KaFlexibleType) input.lowerBound else input
                if (type is KaTypeParameterType) {
                    val container = type.symbol.containingDeclaration as? KaTypeParameterOwnerSymbol
                        ?: error("Type parameter owner unavailable")
                    val ordinal = container.typeParameters.indexOf(type.symbol)
                    check(ordinal >= 0)
                    return (if (container is KaFunctionSymbol) "``" else "`") + ordinal
                }
                val nominal = type as? KaClassType ?: error("Unsupported signature type: $type")
                val id = nominal.classId.asSingleFqName().asString()
                if (id == "kotlin.Array") {
                    val argument = nominal.typeArguments.singleOrNull() as? KaTypeArgumentWithVariance ?: error("Star array")
                    return typeName(argument.type) + "[]"
                }
                val primitiveArray = mapOf("kotlin.BooleanArray" to "System.Boolean", "kotlin.ByteArray" to "System.SByte",
                    "kotlin.ShortArray" to "System.Int16", "kotlin.IntArray" to "System.Int32", "kotlin.LongArray" to "System.Int64",
                    "kotlin.FloatArray" to "System.Single", "kotlin.DoubleArray" to "System.Double", "kotlin.CharArray" to "System.Char")
                primitiveArray[id]?.let { return "$it[]" }
                var name = physicalClass(nominal.symbol) ?: error("No CLR provenance for $id")
                if (nominal.typeArguments.isNotEmpty()) {
                    check('+' !in name) { "Nested generic signature requires an explicit origin map" }
                    name = name.replace(Regex("`[0-9]+"), "") + nominal.typeArguments.joinToString(",", "{", "}") {
                        typeName((it as? KaTypeArgumentWithVariance)?.type ?: error("Star projection"))
                    }
                }
                if (type.isMarkedNullable && name in valueTypes) name = "System.Nullable{$name}"
                return name.replace('+', '.')
            }

            try {
                val name = (symbol as? KaNamedSymbol)?.name?.asString() ?: return@with typeTarget("Unnamed projection")
                if (annotation(symbol, "kotlin.clr.ClrAwaitBridge") != null || annotation(symbol, "kotlin.clr.ClrFlagsOperation") != null)
                    return@with typeTarget("Compiler-synthesized declaration has no standalone CLR member")
                val member = intrinsic?.substringAfterLast('.') ?: name
                when (symbol) {
                    is KaFunctionSymbol -> {
                        val parameters = listOfNotNull(symbol.receiverParameter?.returnType) + symbol.valueParameters.map { it.returnType }
                        val generics = (symbol as? KaTypeParameterOwnerSymbol)?.typeParameters?.size ?: 0
                        val arity = if (generics == 0) "" else "``$generics"
                        val signature = if (parameters.isEmpty()) "" else parameters.joinToString(",", "(", ")", transform = ::typeName)
                        ClrNavigationTarget(owner, "M:$ownerInId.$member$arity$signature", sourceAssembly, kotlinName, "method", consumerProject = binding.projectFile, generation = binding.generation)
                    }
                    is KaPropertySymbol -> {
                        val prefix = if (annotation(symbol, "kotlin.clr.ClrField") != null) "F" else "P"
                        val property = annotation(symbol, "kotlin.clr.ClrProperty")?.get("name") ?: member
                        ClrNavigationTarget(owner, "$prefix:$ownerInId.$property", sourceAssembly, kotlinName, "property", consumerProject = binding.projectFile, generation = binding.generation)
                    }
                    else -> typeTarget("Unsupported declaration kind")
                }
            } catch (unsupported: IllegalStateException) {
                typeTarget(unsupported.message)
            }
          }
        }
    }
}

/** Direct navigation takes precedence over the Kotlin KLIB declaration target. */
class DotNetDirectNavigationProvider : DirectNavigationProvider {
    override fun getNavigationElement(element: PsiElement): PsiElement? =
        ClrNavigationResolver.resolve(element)?.let { DotNetNavigationElement(element.project, it) }
}

class DotNetNavigationElement(private val projectRef: Project, val target: ClrNavigationTarget) :
    LightElement(PsiManager.getInstance(projectRef), KotlinLanguage.INSTANCE), NavigationItem {
    override fun getName(): String = target.xmlDocId
    override fun toString(): String = "DotKt .NET navigation: ${target.xmlDocId}"
    override fun getText(): String = target.xmlDocId
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = target.xmlDocId.substringAfter(':')
        override fun getLocationString(): String = ".NET source"
        override fun getIcon(unused: Boolean): Icon? = null
    }
    override fun canNavigate(): Boolean = !projectRef.isDisposed
    override fun canNavigateToSource(): Boolean = canNavigate()
    override fun navigate(requestFocus: Boolean) {
        projectRef.getService(DotNetNavigationService::class.java).navigate(target)
    }
    override fun navigationRequest(): NavigationRequest? = NavigationRequests.getInstance().rawNavigationRequest(object : Navigatable {
        override fun canNavigate(): Boolean = this@DotNetNavigationElement.canNavigate()
        override fun canNavigateToSource(): Boolean = canNavigate()
        override fun navigate(requestFocus: Boolean) = this@DotNetNavigationElement.navigate(requestFocus)
    })
}
