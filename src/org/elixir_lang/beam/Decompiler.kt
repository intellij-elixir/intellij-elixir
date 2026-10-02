package org.elixir_lang.beam

import com.ericsson.otp.erlang.OtpErlangBinary
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.BinaryFileDecompiler
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.beam.MacroNameArity.MACRO_ORDER
import org.elixir_lang.beam.chunk.CallDefinitions
import org.elixir_lang.beam.chunk.Chunk.TypeID
import org.elixir_lang.beam.chunk.DebugInfo
import org.elixir_lang.beam.chunk.beam_documentation.Documentation
import org.elixir_lang.beam.chunk.beam_documentation.docs.Documented
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.Hidden
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.MarkdownByLanguage
import org.elixir_lang.beam.chunk.beam_documentation.docs.documented.None
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.AbstractCodeCompileOptions
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.Spec
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.Type
import org.elixir_lang.beam.decompiler.ClauseSource
import org.elixir_lang.beam.decompiler.Default
import org.elixir_lang.beam.decompiler.Options
import org.elixir_lang.beam.decompiler.ReservedTypeName
import org.elixir_lang.beam.decompiler.appendNotDecompiledBody
import org.elixir_lang.beam.decompiler.clauseSource
import org.elixir_lang.beam.term.inspect
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.model.psi.type.TypeBuiltins.BUILTIN_ARITY_BY_NAME
import org.elixir_lang.psi.call.name.Function.DEF
import org.elixir_lang.psi.call.name.Function.DEFMACRO
import org.elixir_lang.psi.call.name.Function.DEFMACROP
import org.elixir_lang.psi.call.name.Function.DEFP
import org.jetbrains.annotations.TestOnly
import java.util.*

internal class Decompiler : BinaryFileDecompiler {
    override fun decompile(virtualFile: VirtualFile): CharSequence =
        BeamReader.read(virtualFile) { reader -> decompiled(reader, virtualFile) }
            ?: (DECOMPILATION_ERROR + "BEAM format could not be read")
}

private val logger = Logger.getInstance(Decompiler::class.java)
private val HEADER_NAME_BY_MACRO: Map<String, String> = mapOf(
    DEFMACRO to "Macros",
    DEFMACROP to "Private Macros",
    DEF to "Functions",
    DEFP to "Private Functions"
)

private const val DECOMPILATION_ERROR = "# Decompilation Error: "

private fun decompiled(reader: BeamReader, virtualFile: VirtualFile): CharSequence {
    val atoms = reader.atoms
        ?: return DECOMPILATION_ERROR + when (val read = reader.atomsResult) {
            is ReadResult.Unreadable -> read.reason
            else -> "No " + TypeID.ATOM + " chunk found in BEAM"
        }
    val moduleName = atoms.moduleName()
        ?: return DECOMPILATION_ERROR + "No module name found in " + TypeID.ATOM + " chunk in BEAM"
    val decompiled = StringBuilder()
        .append("# Source code recreated from a .beam file by IntelliJ Elixir\n")
        .append("defmodule ")
        .append(defmoduleArgument(moduleName))
        .append(" do\n")
    val documentation = reader.documentation ?: Documentation.fromExternalChunk(virtualFile)

    documentation?.moduleDocs?.englishDocs?.let { moduleDocs ->
        appendDocumentation(decompiled, "moduledoc", moduleDocs)
    }

    val debugInfo = reader.debugInfo

    appendTypes(decompiled, moduleName, debugInfo, documentation)
    appendCallDefinitions(decompiled, reader, debugInfo, documentation)

    return decompiled.append("end\n")
}

private fun appendTypes(
    decompiled: StringBuilder,
    moduleName: String,
    debugInfo: DebugInfo?,
    documentation: Documentation?
) {
    // fake built-in types being defined in `erlang`, so that built-in type resolution can point to a single location
    if (moduleName == "erlang") {
        decompiled
            .append('\n')
            .append("  # Built-in types (not actually declared in :erlang)\n")
            .append('\n')

        for (name in BUILTIN_ARITY_BY_NAME.keys.sorted()) {
            for (arity in BUILTIN_ARITY_BY_NAME[name]!!.sorted()) {
                val parameters = when {
                    (name == "maybe_improper_list" ||
                            name == "nonempty_improper_list" ||
                            name == "nonempty_maybe_improper_list") && arity == 2 ->
                        "element :: term(), tail :: term()"

                    name == "non_empty_list" && arity == 1 -> "element :: term()"
                    else -> (1..arity).joinToString(", ") { "type$it" }
                }

                // Routed through appendTypeDeclaration so a built-in that is a reserved Elixir word
                // would be commented out rather than emitted as invalid source - the same rule
                // TypeDefinitions applies when deciding which built-ins get a stub. None is today;
                // keeping the two derived from one rule is what stops them drifting apart.
                appendTypeDeclaration(decompiled, name, "@type $name($parameters) :: ...")
            }
        }
    }

    if (moduleName == "erlang") {
        // `erlang` keeps its legacy type rendering precedence in addition to the faked built-ins above.
        documentation?.docs?.typeDocumentedByArityByName()?.let { appendTypes(decompiled, it) }
            ?: debugInfo?.let { appendTypes(decompiled, it, Options()) }
    } else {
        // For Erlang-compiled modules the abstract code is the authoritative source of type definitions:
        // it carries the real bodies, type parameters (arity) and `-opaque` declarations that the docs
        // chunk only summarizes.  Render from it, merging any `@typedoc` from the docs chunk.  Beams
        // without Erlang abstract code (e.g. Elixir-compiled beams) fall back to the docs chunk.
        val abstractCodeCompileOptions = debugInfo as? AbstractCodeCompileOptions

        if (abstractCodeCompileOptions != null) {
            appendTypes(decompiled, abstractCodeCompileOptions, documentation, Options())
        } else {
            documentation?.docs?.typeDocumentedByArityByName()?.let { appendTypes(decompiled, it) }
        }
    }
}

/**
 * Appends a single `@type`/`@opaque` declaration line, indented two spaces.  If [name] is a reserved
 * Elixir word it cannot name an Elixir type, so the declaration is emitted commented out (still
 * indented) to keep the source parseable - see [ReservedTypeName].
 */
private fun appendTypeDeclaration(decompiled: StringBuilder, name: String, declaration: String) {
    val rendered = if (ReservedTypeName.isReserved(name)) {
        ReservedTypeName.commentOut(name, declaration)
    } else {
        declaration
    }

    // Indent only the first line; a commented multi-line block carries its own continuation indent
    // (see ReservedTypeName.commentOut), matching how the abstract-code type path appends attributes.
    rendered.lineSequence().forEachIndexed { index, line ->
        if (index == 0) {
            decompiled.append("  ").append(line).append('\n')
        } else {
            decompiled.append(line).append('\n')
        }
    }
}

private fun appendTypes(decompiled: StringBuilder, documentedByArityByName: Map<String, Map<Int, Documented>>) {
    if (documentedByArityByName.isNotEmpty()) {
        decompiled
            .append('\n')
            .append("  # Types\n")
            .append('\n')
    }

    for ((name, documentedByArity) in documentedByArityByName) {
        for ((arity, documented) in documentedByArity) {
            documented.doc?.let { doc ->
                when (doc) {
                    is None -> Unit
                    is Hidden -> appendDocumentation(decompiled, "typedoc")
                    is MarkdownByLanguage -> {
                        for (formatted in doc.formattedByLanguage.values) {
                            appendDocumentation(decompiled, "typedoc", formatted)
                        }
                    }
                }
            }

            val signatures = documented.signatures

            if (signatures.isNotEmpty()) {
                for (signature in signatures) {
                    val cleaned = signature.replace("\r", "")
                    val (attr, body) = erlangTypeSignatureToElixir(cleaned)
                    appendTypeDeclaration(decompiled, name, "@$attr $body")
                }
            } else {
                val head = "$name(" + (1..arity).joinToString(", ") { "type$it" } + ")"
                appendTypeDeclaration(decompiled, name, "@type $head :: ...")
            }
        }
    }
}

private fun appendTypes(decompiled: StringBuilder, debugInfo: DebugInfo, options: Options) {
    when (debugInfo) {
        is AbstractCodeCompileOptions -> appendTypes(decompiled, debugInfo, options)
        else -> Unit
    }
}

private fun appendTypes(
    decompiled: StringBuilder,
    abstractCodeCompileOptions: AbstractCodeCompileOptions,
    decompilerOptions: Options
) {
    val macroStringAttributesByElixirAttributeName =
        abstractCodeCompileOptions.attributes.macroStringAttributes.filterIsInstance<Type>()
            .groupBy { it.elixirAttributeName }

    appendTypes(decompiled, macroStringAttributesByElixirAttributeName, "type", "Types", decompilerOptions)
    appendTypes(
        decompiled,
        macroStringAttributesByElixirAttributeName,
        "typep",
        "Private Types",
        decompilerOptions
    )
}

private fun appendTypes(
    decompiled: StringBuilder,
    macroStringAttributesByElixirAttributeName: Map<String, List<Type>>,
    elixirAttributeName: String,
    title: String,
    options: Options
) {
    val macroStringAttributes = macroStringAttributesByElixirAttributeName[elixirAttributeName]

    if (!macroStringAttributes.isNullOrEmpty()) {
        appendHeader(decompiled, title)

        for (macroStringAttribute in macroStringAttributes.sortedBy { it.name }) {
            decompiled
                .append('\n')
                .append("  ").append(macroStringAttribute.toMacroString(options)).append('\n')
        }
    }
}

private fun appendTypes(
    decompiled: StringBuilder,
    abstractCodeCompileOptions: AbstractCodeCompileOptions,
    documentation: Documentation?,
    decompilerOptions: Options
) {
    val typesByElixirAttributeName =
        abstractCodeCompileOptions.attributes.macroStringAttributes.filterIsInstance<Type>()
            .groupBy { it.elixirAttributeName }
    val typeDocumentedByArityByName = documentation?.docs?.typeDocumentedByArityByName() ?: emptyMap()

    // `@type` and `@opaque` are both public and share the "Types" section; `@typep` is private.
    appendTypeSection(
        decompiled,
        "Types",
        typesByElixirAttributeName["type"].orEmpty() + typesByElixirAttributeName["opaque"].orEmpty(),
        typeDocumentedByArityByName,
        decompilerOptions
    )
    appendTypeSection(
        decompiled,
        "Private Types",
        typesByElixirAttributeName["typep"].orEmpty(),
        typeDocumentedByArityByName,
        decompilerOptions
    )
}

private fun appendTypeSection(
    decompiled: StringBuilder,
    title: String,
    types: List<Type>,
    typeDocumentedByArityByName: Map<String, Map<Int, Documented>>,
    options: Options
) {
    if (types.isNotEmpty()) {
        appendHeader(decompiled, title)

        for (type in types.sortedBy { it.name }) {
            decompiled.append('\n')
            appendTypeDoc(decompiled, type, typeDocumentedByArityByName)
            decompiled.append("  ").append(type.toMacroString(options)).append('\n')
        }
    }
}

private fun appendTypeDoc(
    decompiled: StringBuilder,
    type: Type,
    typeDocumentedByArityByName: Map<String, Map<Int, Documented>>
) {
    val name = type.name ?: return
    val documented = typeDocumentedByArityByName[name]?.get(type.arity) ?: return

    documented.doc?.let { doc ->
        when (doc) {
            is None -> Unit
            is Hidden -> appendDocumentation(decompiled, "typedoc")
            is MarkdownByLanguage ->
                for (formatted in doc.formattedByLanguage.values) {
                    appendDocumentation(decompiled, "typedoc", formatted)
                }
        }
    }
}

private fun appendCallDefinitions(
    decompiled: StringBuilder,
    reader: BeamReader,
    debugInfo: DebugInfo?,
    documentation: Documentation?
) {
    val macroNameAritySortedSetByMacro = CallDefinitions.macroNameAritySortedSetByMacro(reader)
    appendCallDefinitions(decompiled, macroNameAritySortedSetByMacro, debugInfo, documentation)
}

private fun macroToHeaderName(macro: String): String = HEADER_NAME_BY_MACRO[macro]!!

private fun appendCallDefinitions(
    decompiled: StringBuilder,
    macroNameAritySortedSetByMacro: Map<String, SortedSet<MacroNameArity>>,
    debugInfo: DebugInfo?,
    documentation: Documentation?
) {
    val options = decompilerOptions(macroNameAritySortedSetByMacro)

    for (macro in MACRO_ORDER) {
        val macroNameAritySortedSet = macroNameAritySortedSetByMacro[macro]

        if (macroNameAritySortedSet != null && options.decompileMacro(macro)) {
            for ((index, macroNameArity) in macroNameAritySortedSet.withIndex()) {
                if (index == 0) {
                    appendHeader(decompiled, macroToHeaderName(macro))
                }
                decompiled.append("\n")

                if (documentation != null) {
                    documentation.docs?.let { docs ->
                        docs.deprecated(macroNameArity)?.let { deprecated ->
                            when (deprecated) {
                                is OtpErlangBinary ->
                                    decompiled.append("\n  @deprecated \"\"\"\n")
                                        .append("  ")
                                        .append(String(deprecated.binaryValue()))
                                        .append("\n  \"\"\"\n\n")
                                else -> {
                                    logger.error(
                                        "Don't know how to decompiled @deprecated value (${
                                            inspect(
                                                deprecated
                                            )
                                        })"
                                    )
                                }
                            }
                        }

                        docs.doc(macroNameArity)?.let { doc ->
                            when (doc) {
                                is None -> Unit
                                is Hidden -> appendDocumentation(decompiled, "doc")
                                is MarkdownByLanguage -> {
                                    for (formatted in doc.formattedByLanguage.values) {
                                        appendDocumentation(decompiled, "doc", formatted)
                                    }
                                }
                            }
                        }
                    }
                }
                appendSpec(decompiled, macroNameArity, debugInfo, options)
                appendMacroNameArity(decompiled, macroNameArity, debugInfo, documentation, options)
            }
        }
    }
}

@Volatile
private var definitionLimit = Int.MAX_VALUE

/**
 * Past [limit] definitions a module decompiles as public heads only, while its stubs still hold every
 * definition, so the mirror misses some stubs.
 */
@TestOnly
fun setDefinitionLimitForTests(limit: Int, parentDisposable: Disposable) {
    val previous = definitionLimit
    definitionLimit = limit
    Disposer.register(parentDisposable) { definitionLimit = previous }
}

internal fun decompilerOptions(macroNameAritySortedSet: Map<String, SortedSet<MacroNameArity>>): Options {
    val defmacroCount = macroNameAritySortedSet[DEFMACRO]?.size ?: 0
    val defCount = macroNameAritySortedSet[DEF]?.size ?: 0
    val publicCount = defmacroCount + defCount

    return if (publicCount > definitionLimit) {
        Options(decompileBodies = false, decompileMacros = setOf(DEFMACRO, DEF))
    } else {
        val defmacropCount = macroNameAritySortedSet[DEFMACROP]?.size ?: 0
        val defpCount = macroNameAritySortedSet[DEFP]?.size ?: 0
        val privateCount = defmacropCount + defpCount

        if (publicCount + privateCount > definitionLimit) {
            Options(decompileBodies = false, decompileMacros = setOf(DEFMACRO, DEF))
        } else {
            Options(decompileBodies = true, decompileMacros = setOf(DEFMACRO, DEFMACROP, DEF, DEFP))
        }
    }
}

private fun appendHeader(decompiled: StringBuilder, name: String) {
    decompiled
        .append("\n")
        .append("  # ")
        .append(name)
        .append("\n")
}

private fun appendDocumentation(decompiled: StringBuilder, moduleAttribute: String) {
    decompiled.append("  @").append(moduleAttribute).append(' ').append("false").append('\n')
}

private fun appendDocumentation(decompiled: StringBuilder, moduleAttribute: String, text: String) {
    val sanitizedText = text.replace("\r", "")
    val safePromoterTerminator = safePromoterTerminator(sanitizedText)
    val promoterTerminator: String = safePromoterTerminator ?: "\"\"\""
    decompiled
        .append("  @")
        .append(moduleAttribute)
        // Use ~S sigil to stop interpolation in docs as an interpolation stored in the docs was
        // escaped in the original source.
        .append(" ~S")
        .append(promoterTerminator)
        .append('\n')
    appendDocumentationText(decompiled, safePromoterTerminator, sanitizedText)
    decompiled
        .append("\n  ")
        .append(promoterTerminator)
        .append('\n')
}

private const val CHARLIST_HEREDOC_PROMOTER_TERMINATOR = "'''"
private const val STRING_HEREDOC_PROMOTER_TERMINATOR = "\"\"\""
private fun safePromoterTerminator(text: String): String? {
    val containsCharlistHeredoc = text.contains(CHARLIST_HEREDOC_PROMOTER_TERMINATOR)
    val containsStringHeredoc = text.contains(STRING_HEREDOC_PROMOTER_TERMINATOR)

    return if (containsCharlistHeredoc && containsStringHeredoc) {
        null
    } else if (containsCharlistHeredoc) {
        STRING_HEREDOC_PROMOTER_TERMINATOR
    } else if (containsStringHeredoc) {
        CHARLIST_HEREDOC_PROMOTER_TERMINATOR
    } else {
        // Default to String since it is what actual developers would use most often
        STRING_HEREDOC_PROMOTER_TERMINATOR
    }
}

private fun appendDocumentationText(decompiled: StringBuilder, safePromoterTerminator: String?, text: String) {
    val lines = text.split("\n").toTypedArray()
    val lastI = lines.size - 1

    for (i in lines.indices) {
        val line = lines[i]
        val stripped: String = line.trimEnd()

        if (stripped.isNotEmpty()) {
            decompiled.append("  ")

            if (safePromoterTerminator == null) {
                decompiled.append(stripped.replace(STRING_HEREDOC_PROMOTER_TERMINATOR, "\"\"\""))
            } else {
                decompiled.append(stripped)
            }
        }

        if (i != lastI) {
            decompiled.append("\n")
        }
    }
}

private fun appendSpec(
    decompiled: StringBuilder,
    macroNameArity: MacroNameArity,
    debugInfo: DebugInfo?,
    options: Options
) {
    when (debugInfo) {
        is AbstractCodeCompileOptions -> appendSpec(decompiled, macroNameArity, debugInfo, options)
        else -> Unit
    }
}

private fun appendSpec(
    decompiled: StringBuilder,
    macroNameArity: MacroNameArity,
    debugInfo: AbstractCodeCompileOptions,
    options: Options
) {
    debugInfo
        .attributes.macroStringAttributes
        .find {
            it is Spec &&
                    it.name == macroNameArity.name &&
                    it.arity == macroNameArity.arity.toBigInteger()
        }
        ?.let { spec ->
            decompiled.append(spec.toMacroString(options).prependIndentToNonBlank()).append('\n')
        }
}

private fun appendMacroNameArity(
    decompiled: StringBuilder,
    macroNameArity: MacroNameArity,
    debugInfo: DebugInfo?,
    documentation: Documentation?,
    options: Options
) {
    when (val source = clauseSource(macroNameArity, debugInfo) { documentation }) {
        is ClauseSource.ErlangAbstractCode -> {
            var macroString = source.function.toMacroString(options)

            // The Erlang abstract code Function always hardcodes macro as DEF because
            // the AST doesn't distinguish exported/unexported. The Decompiler knows the
            // correct macro from the export table, so fix it up here.
            if (macroNameArity.macro == DEFP) {
                macroString = macroString.replaceDefWithDefp()
            }

            decompiled.append(macroString.prependIndentToNonBlank()).append('\n')
        }
        is ClauseSource.ElixirDebugInfo ->
            decompiled.append(source.definition.toMacroString(options)!!.prependIndentToNonBlank()).append('\n')
        is ClauseSource.DocsSignatures ->
            for (signature in source.signatures) {
                decompiled.append("  ").append(macroNameArity.macro).append(' ')
                decompiled.append(signature.replace("\r", ""))
                appendNotDecompiledBody(decompiled)
            }
        is ClauseSource.CoveringDocsSignature ->
            Default.INSTANCE.append(decompiled, macroNameArity, source.parameters.toTypedArray())
        is ClauseSource.Generated -> source.decompiler.append(decompiled, macroNameArity)
        null -> Unit
    }
}

fun defmoduleArgument(moduleName: String): String = InspectAtom.literal(moduleName)

/**
 * Converts an Erlang type signature like `-type ascii_binary() :: binary().` or
 * `-opaque queue(Item) :: {list(Item), list(Item)}.` into an Elixir attribute
 * name and body: `("type", "ascii_binary() :: binary()")` or
 * `("opaque", "queue(Item) :: {list(Item), list(Item)}")`.
 *
 * Strips the leading `-type `/`-opaque ` prefix and trailing `.` that are
 * part of Erlang's syntax but invalid in Elixir `@type`/`@opaque` attributes.
 */
private fun erlangTypeSignatureToElixir(signature: String): Pair<String, String> {
    var body = signature
    val attr = when {
        body.startsWith("-opaque ") -> {
            body = body.removePrefix("-opaque ")
            "opaque"
        }
        body.startsWith("-type ") -> {
            body = body.removePrefix("-type ")
            "type"
        }
        else -> "type"
    }
    // Strip trailing Erlang period
    if (body.endsWith(".")) {
        body = body.dropLast(1)
    }
    return Pair(attr, body)
}

fun String.prependIndentToNonBlank(indent: String = "  "): String =
    lineSequence()
        .map {
            when {
                it.isBlank() -> it
                else -> indent + it
            }
        }
        .joinToString("\n")

/**
 * Replaces `def ` with `defp ` at the start of each clause in a multi-clause function string
 * produced by [org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Function.toMacroString].
 *
 * The Erlang abstract code [Function] always produces `def` because the AST doesn't encode
 * export information. This extension is used by the [Decompiler] when the export table indicates
 * the function is private.
 */
private fun String.replaceDefWithDefp(): String =
    lineSequence()
        .map { line ->
            if (line.startsWith("def ")) {
                "defp " + line.removePrefix("def ")
            } else {
                line
            }
        }
        .joinToString("\n")
