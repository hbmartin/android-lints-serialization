package com.kozaxinan.android.checks

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Detector.UastScanner
import com.android.tools.lint.detector.api.JavaContext
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.impl.source.PsiClassReferenceType
import org.jetbrains.kotlin.asJava.elements.KtLightModifierList
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.toUElement

/**
 * Parent class for finding fields of return type of and Retrofit interface method.
 *
 * Example;
 *
 * interface Api {
 *
 *  \@GET
 *   fun restSomething(): Dto
 * }
 *
 * class Dto(val a, val b)
 *
 * for this example, [Visitor.findAllReturnTypeFieldsOf] will return list of UField for the fields of DTO. {a, b}
 */
@Suppress("UnstableApiUsage")
internal open class RetrofitReturnTypeDetector : Detector(), UastScanner {

    override fun getApplicableUastTypes(): List<Class<UMethod>> = listOf(UMethod::class.java)

    open class Visitor(private val context: JavaContext) : UElementHandler() {

        private val listOfRetrofitAnnotations = listOf(
            "retrofit2.http.DELETE",
            "retrofit2.http.GET",
            "retrofit2.http.POST",
            "retrofit2.http.PUT",
            "DELETE",
            "GET",
            "POST",
            "PUT"
        )

        /**
         * Return all field of return type of a retrofit interface method.
         * Returned list is include recursive fields of complex classes and type information of generic classes.
         *
         * Unit and Void return types are ignored.
         *
         * Static fields are ignored.
         *
         * @param node Method node to be check
         * @return A list of fields of return type of method.
         * Empty list if method doesn't belong to retrofit interface or method doesn't have valid return type.
         */
        fun findAllReturnTypeFieldsOf(node: UMethod): List<UField> {
            if (node.getContainingUClass()?.isInterface != true || !hasRetrofitAnnotation(node)) return emptyList()

            val returnType = node.returnType
            return when {
                node.isSuspend() -> findAllInnerFields(node.parameters.last().type as PsiClassType)
                returnType is PsiClassType && returnType.isNotUnitOrVoid() ->
                    findAllInnerFields(returnType)
                else -> emptyList()
            }
        }

        @Suppress("ReturnCount")
        fun findAllBodyParameterTypeFieldsOf(node: UMethod): Pair<PsiClassType, List<UField>>? {
            if (node.getContainingUClass()?.isInterface != true || !hasRetrofitAnnotation(node)) {
                return null
            }

            node.parameterList.parameters.forEach { parameter ->
                if (parameter.annotations.any { it.qualifiedName == "retrofit2.http.Body" }) {
                    return (parameter.type as? PsiClassType)?.let {
                        it to findAllInnerFields(it)
                    }
                }
            }

            return null
        }

        private fun PsiClassType.isNotUnitOrVoid() =
            !canonicalText.contains("Unit") && !canonicalText.contains("Void")

        private fun hasRetrofitAnnotation(method: UMethod): Boolean {
            return context
                .evaluator
                .getAllAnnotations(method as UAnnotated, true)
                .map { uAnnotation -> uAnnotation.qualifiedName }
                .intersect(listOfRetrofitAnnotations)
                .isNotEmpty()
        }

        private fun findAllInnerFields(typeRef: PsiClassType): List<UField> {
            val actualReturnType = findGenericClassType(typeRef)
            val typeClass = actualReturnType
                .resolve()
                .toUElement() as? UClass
                ?: return emptyList()

            val innerFields: List<UField> = typeClass
                .fields
                .filterNot { it.isStatic && it !is PsiEnumConstant }

            return innerFields +
                innerFields
                    .filterNot { it.isStatic }
                    .map { it.type }
                    .filterIsInstance<PsiClassType>()
                    .map(::findAllInnerFields)
                    .flatten()
        }

        private fun findGenericClassType(returnType: PsiClassType): PsiClassType {
            val substitutor: PsiSubstitutor = returnType
                .resolveGenerics()
                .substitutor
            return if (substitutor == PsiSubstitutor.EMPTY) {
                returnType
            } else {
                when (val psiType: PsiType = substitutor.substitutionMap.values.first()) {
                    is PsiClassReferenceType -> findGenericClassType(psiType)
                    is PsiWildcardType -> {
                        when (val superBound: PsiType = psiType.superBound) {
                            is PsiClassType -> findGenericClassType(superBound)
                            else -> returnType
                        }
                    }
                    else -> returnType
                }
            }
        }

        private fun UMethod.isSuspend(): Boolean {
            val modifiers = modifierList as? KtLightModifierList<*>
            return modifiers?.kotlinOrigin?.hasModifier(KtTokens.SUSPEND_KEYWORD) ?: false
        }
    }
}
