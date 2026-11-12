package org.elixir_lang.code_insight

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.documentation.SourceFileDocsHelper
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.CanonicallyNamed
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import java.util.concurrent.Callable

/**
 * The signature [RecordedSignature] gives `name/arity` (as [nameArity] writes it) in [module], a module [file] defines,
 * from every clause of that name and full arity in source order.
 */
internal fun recordedIn(file: PsiFile, module: String, nameArity: String): String? =
    ReadAction.nonBlocking(Callable {
        val modular = PsiTreeUtil.findChildrenOfType(file, Call::class.java)
            .single { SourceFileDocsHelper.moduleName(it) == module }
        val name = nameArity.substringBeforeLast('/')
        val arity = nameArity.substringAfterLast('/').toInt()
        val clauses = CallDefinitionClause.modularChildCalls(modular).flatMap { call ->
            DeclaringForm.declaredBy(call)?.let { form ->
                Declarations.of(form, call, ResolveState.initial())
                    .mapNotNull { it.declaration }
                    .filter { declaration ->
                        declaration.name == name &&
                            declaration.arity.arityInterval()?.let { it.definition().first == arity } == true
                    }
                    .mapNotNull { declaration ->
                        DeclaringForm.head(call, form, declaration.name, declaration.arity)
                            ?.let { RecordedSignature.Clause(form, it) }
                    }
            }.orEmpty()
        }

        RecordedSignature.of((modular as? CanonicallyNamed)?.canonicalName(), name, ArityInterval(arity, arity), clauses)?.text
    }).executeSynchronously()
