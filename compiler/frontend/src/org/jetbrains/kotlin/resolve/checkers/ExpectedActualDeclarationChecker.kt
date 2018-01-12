/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.checkers

import com.intellij.openapi.vfs.VfsUtilCore
import org.jetbrains.kotlin.config.AnalysisFlag
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.diagnostics.DiagnosticSink
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.psiUtil.hasActualModifier
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.descriptorUtil.isAnnotationConstructor
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.multiplatform.ExpectedActualResolver
import org.jetbrains.kotlin.resolve.multiplatform.ExpectedActualResolver.Compatibility
import org.jetbrains.kotlin.resolve.multiplatform.ExpectedActualResolver.Compatibility.Compatible
import org.jetbrains.kotlin.resolve.multiplatform.ExpectedActualResolver.Compatibility.Incompatible
import org.jetbrains.kotlin.resolve.source.PsiSourceFile
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.io.File

object ExpectedActualDeclarationChecker : DeclarationChecker {
    override fun check(
        declaration: KtDeclaration,
        descriptor: DeclarationDescriptor,
        diagnosticHolder: DiagnosticSink,
        bindingContext: BindingContext,
        languageVersionSettings: LanguageVersionSettings,
        expectActualTracker: ExpectActualTracker
    ) {
        if (!languageVersionSettings.supportsFeature(LanguageFeature.MultiPlatformProjects)) return

        if (declaration !is KtNamedDeclaration) return
        if (descriptor !is MemberDescriptor || DescriptorUtils.isEnumEntry(descriptor)) return

        if (descriptor.isExpect) {
            checkExpectedDeclarationHasActual(declaration, descriptor, diagnosticHolder, descriptor.module, expectActualTracker)
        } else {
            val checkActual = !languageVersionSettings.getFlag(AnalysisFlag.multiPlatformDoNotCheckActual)
            checkActualDeclarationHasExpected(declaration, descriptor, diagnosticHolder, checkActual)
        }
    }

    fun checkExpectedDeclarationHasActual(
        reportOn: KtNamedDeclaration,
        descriptor: MemberDescriptor,
        diagnosticHolder: DiagnosticSink,
        platformModule: ModuleDescriptor,
        expectActualTracker: ExpectActualTracker
    ) {
        // Only look for top level actual members; class members will be handled as a part of that expected class
        if (descriptor.containingDeclaration !is PackageFragmentDescriptor) return

        val compatibility = ExpectedActualResolver.findActualForExpected(descriptor, platformModule) ?: return

        val shouldReportError =
            compatibility.allStrongIncompatibilities() ||
                    Compatible !in compatibility && compatibility.values.flatMapTo(hashSetOf()) { it }.all { actual ->
                val expectedOnes = ExpectedActualResolver.findExpectedForActual(actual, descriptor.module)
                expectedOnes != null && Compatible in expectedOnes.keys
            }

        if (shouldReportError) {
            assert(compatibility.keys.all { it is Incompatible })
            @Suppress("UNCHECKED_CAST")
            val incompatibility = compatibility as Map<Incompatible, Collection<MemberDescriptor>>
            diagnosticHolder.report(Errors.NO_ACTUAL_FOR_EXPECT.on(reportOn, descriptor, platformModule, incompatibility))
        } else {
            val actualMembers = compatibility.asSequence()
                .filter { (compatibility, _) ->
                    compatibility is Compatible || (compatibility is Incompatible && compatibility.kind != Compatibility.IncompatibilityKind.STRONG)
                }.flatMap { it.value.asSequence() }

            expectActualTracker.reportExpectActual(expected = descriptor, actualMembers = actualMembers)
        }
    }

    private fun ExpectActualTracker.reportExpectActual(expected: MemberDescriptor, actualMembers: Sequence<MemberDescriptor>) {
        if (this is ExpectActualTracker.DoNothing) return

        val expectedFile = sourceFile(expected) ?: return
        for (actual in actualMembers) {
            val actualFile = sourceFile(actual) ?: continue
            report(expectedFile = expectedFile, actualFile = actualFile)
        }
    }

    private fun sourceFile(descriptor: MemberDescriptor): File? =
        descriptor.source
            .containingFile
            .safeAs<PsiSourceFile>()
            ?.run { VfsUtilCore.virtualToIoFile(psiFile.virtualFile) }

    private fun Map<out Compatibility, Collection<MemberDescriptor>>.allStrongIncompatibilities(): Boolean =
        this.keys.all { it is Incompatible && it.kind == Compatibility.IncompatibilityKind.STRONG }

    private fun checkActualDeclarationHasExpected(
        reportOn: KtNamedDeclaration, descriptor: MemberDescriptor, diagnosticHolder: DiagnosticSink, checkActual: Boolean
    ) {
        // Using the platform module instead of the common module is sort of fine here because the former always depends on the latter.
        // However, it would be clearer to find the common module this platform module implements and look for expected there instead.
        // TODO: use common module here
        val compatibility = ExpectedActualResolver.findExpectedForActual(descriptor, descriptor.module) ?: return

        val hasActualModifier = descriptor.isActual && reportOn.hasActualModifier()
        if (!hasActualModifier) {
            if (compatibility.allStrongIncompatibilities()) return

            if (Compatible in compatibility) {
                // we suppress error, because annotation classes can only have one constructor and it's a 100% boilerplate
                // to require every annotation constructor with additional parameters with default values be marked with the `actual` modifier
                if (checkActual && !descriptor.isAnnotationConstructor()) {
                    diagnosticHolder.report(Errors.ACTUAL_MISSING.on(reportOn))
                }

                return
            }
        }

        // 'firstOrNull' is needed because in diagnostic tests, common sources appear twice, so the same class is duplicated
        // TODO: replace with 'singleOrNull' as soon as multi-module diagnostic tests are refactored
        val singleIncompatibility = compatibility.keys.firstOrNull()
        if (singleIncompatibility is Incompatible.ClassScopes) {
            assert(descriptor is ClassDescriptor || descriptor is TypeAliasDescriptor) {
                "Incompatible.ClassScopes is only possible for a class or a typealias: $descriptor"
            }

            // Do not report "expected members have no actual ones" for those expected members, for which there's a clear
            // (albeit maybe incompatible) single actual suspect, declared in the actual class.
            // This is needed only to reduce the number of errors. Incompatibility errors for those members will be reported
            // later when this checker is called for them
            fun hasSingleActualSuspect(
                expectedWithIncompatibility: Pair<MemberDescriptor, Map<Incompatible, Collection<MemberDescriptor>>>
            ): Boolean {
                val (expectedMember, incompatibility) = expectedWithIncompatibility
                val actualMember = incompatibility.values.singleOrNull()?.singleOrNull()
                return actualMember != null &&
                        actualMember.isExplicitActualDeclaration() &&
                        !incompatibility.allStrongIncompatibilities() &&
                        ExpectedActualResolver.findExpectedForActual(
                            actualMember,
                            expectedMember.module
                        )?.values?.singleOrNull()?.singleOrNull() == expectedMember
            }

            val nonTrivialUnfulfilled = singleIncompatibility.unfulfilled.filterNot(::hasSingleActualSuspect)

            if (nonTrivialUnfulfilled.isNotEmpty()) {
                val classDescriptor =
                    (descriptor as? TypeAliasDescriptor)?.expandedType?.constructor?.declarationDescriptor as? ClassDescriptor
                            ?: (descriptor as ClassDescriptor)
                diagnosticHolder.report(
                    Errors.NO_ACTUAL_CLASS_MEMBER_FOR_EXPECTED_CLASS.on(
                        reportOn, classDescriptor, nonTrivialUnfulfilled
                    )
                )
            }
        } else if (Compatible !in compatibility) {
            assert(compatibility.keys.all { it is Incompatible })
            @Suppress("UNCHECKED_CAST")
            val incompatibility = compatibility as Map<Incompatible, Collection<MemberDescriptor>>
            diagnosticHolder.report(Errors.ACTUAL_WITHOUT_EXPECT.on(reportOn, descriptor, incompatibility))
        }
    }

    // This should ideally be handled by CallableMemberDescriptor.Kind, but default constructors have kind DECLARATION and non-empty source.
    // Their source is the containing KtClass instance though, as opposed to explicit constructors, whose source is KtConstructor
    private fun MemberDescriptor.isExplicitActualDeclaration(): Boolean =
        when (this) {
            is ConstructorDescriptor -> DescriptorToSourceUtils.getSourceFromDescriptor(this) is KtConstructor<*>
            is CallableMemberDescriptor -> kind == CallableMemberDescriptor.Kind.DECLARATION
            else -> true
        }
}
