package org.elixir_lang

import com.intellij.openapi.options.colors.ColorSettingsPage
import java.io.IOException
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.SIGIL_BY_NAME
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.sigilNames
import javax.swing.Icon

/**
 * Created by kadie.enheduanna.inanna on 8/3/14.
 */
class ElixirColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon? = Icons.LANGUAGE

    override fun getHighlighter(): SyntaxHighlighter = ElixirSyntaxHighlighter()

    override fun getDemoText(): String {
        val demoTextInputStream = javaClass.getResourceAsStream("/Color Settings Page Demo Text.ex")
        return try {
            FileUtil.loadTextAndClose(demoTextInputStream)
        } catch (e: IOException) {
            ""
        }
    }

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? =
            TEXT_ATTRIBUTES_KEY_BY_HIGHLIGHTING_TAG

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Elixir"

    companion object {
        private val TEXT_ATTRIBUTES_KEY_BY_HIGHLIGHTING_TAG: Map<String, TextAttributesKey> = mapOf(
                "alias" to ElixirSyntaxHighlighter.ALIAS,
                "atom" to ElixirSyntaxHighlighter.ATOM,
                "callback" to ElixirSyntaxHighlighter.CALLBACK,
                "char_list" to ElixirSyntaxHighlighter.CHAR_LIST,
                "documentation-module-attribute" to ElixirSyntaxHighlighter.DOCUMENTATION_MODULE_ATTRIBUTE,
                "documentation-text" to ElixirSyntaxHighlighter.DOCUMENTATION_TEXT,
                "escape-sequence" to ElixirSyntaxHighlighter.VALID_ESCAPE_SEQUENCE,
                // see https://github.com/JetBrains/intellij-community/blob/9aa6a55984e5d0563013e6c918b6f787587b3bf8/platform/lang-impl/src/com/intellij/openapi/options/colors/pages/GeneralColorsPage.java#L147
                "error" to CodeInsightColors.ERRORS_ATTRIBUTES,
                "function-call" to ElixirSyntaxHighlighter.FUNCTION_CALL,
                "guard-call" to ElixirSyntaxHighlighter.GUARD_CALL,
                "guard-declaration" to ElixirSyntaxHighlighter.GUARD_DECLARATION,
                "ignored" to ElixirSyntaxHighlighter.IGNORED_VARIABLE,
                "macro-call" to ElixirSyntaxHighlighter.MACRO_CALL,
                "map" to ElixirSyntaxHighlighter.MAP,
                "module-attribute" to ElixirSyntaxHighlighter.MODULE_ATTRIBUTE,
                "parameter" to ElixirSyntaxHighlighter.PARAMETER,
                "predefined-call" to ElixirSyntaxHighlighter.PREDEFINED_CALL,
                *SIGIL_BY_NAME.map { (name, sigil) ->
                    "sigil_${name}" to sigil
                }.toTypedArray(),
                "string" to ElixirSyntaxHighlighter.STRING,
                "struct" to ElixirSyntaxHighlighter.STRUCT,
                // needed so that <error></error> doesn't override VALID_DIGIT token highlighting
                "valid-digit" to ElixirSyntaxHighlighter.VALID_DIGIT,
                "variable" to ElixirSyntaxHighlighter.VARIABLE,
                "specification" to ElixirSyntaxHighlighter.SPECIFICATION,
                "type" to ElixirSyntaxHighlighter.TYPE,
                "type-parameter" to ElixirSyntaxHighlighter.TYPE_PARAMETER,
        )
        private val DESCRIPTORS = arrayOf(
                AttributesDescriptor("Alias", ElixirSyntaxHighlighter.ALIAS),
                AttributesDescriptor("Atom", ElixirSyntaxHighlighter.ATOM),
                AttributesDescriptor("Braces and Operators//Bit", ElixirSyntaxHighlighter.BIT),
                AttributesDescriptor("Braces and Operators//Braces", ElixirSyntaxHighlighter.BRACES),
                AttributesDescriptor("Braces and Operators//Brackets", ElixirSyntaxHighlighter.BRACKETS),
                AttributesDescriptor("Braces and Operators//Character Token", ElixirSyntaxHighlighter.CHAR_TOKEN_TOKEN),
                AttributesDescriptor("Braces and Operators//Comma", ElixirSyntaxHighlighter.COMMA),
                AttributesDescriptor("Braces and Operators//Dot", ElixirSyntaxHighlighter.DOT),
                AttributesDescriptor(
                        "Braces and Operators//Interpolation",
                        ElixirSyntaxHighlighter.EXPRESSION_SUBSTITUTION_MARK
                ),
                AttributesDescriptor("Braces and Operators//Maps and Structs//Maps", ElixirSyntaxHighlighter.MAP),
                AttributesDescriptor("Braces and Operators//Maps and Structs//Structs", ElixirSyntaxHighlighter.STRUCT),
                AttributesDescriptor("Braces and Operators//Operation Sign", ElixirSyntaxHighlighter.OPERATION_SIGN),
                AttributesDescriptor("Braces and Operators//Parentheses", ElixirSyntaxHighlighter.PARENTHESES),
                AttributesDescriptor("Braces and Operators//Semicolon", ElixirSyntaxHighlighter.SEMICOLON),
                AttributesDescriptor("Comment", ElixirSyntaxHighlighter.COMMENT),
                AttributesDescriptor("Declarations//Function", ElixirSyntaxHighlighter.FUNCTION_DECLARATION),
                AttributesDescriptor("Declarations//Macro", ElixirSyntaxHighlighter.MACRO_DECLARATION),
                AttributesDescriptor("Declarations//Guard", ElixirSyntaxHighlighter.GUARD_DECLARATION),
                AttributesDescriptor("Identifier", ElixirSyntaxHighlighter.IDENTIFIER),
                AttributesDescriptor("Keywords", ElixirSyntaxHighlighter.KEYWORD),
                AttributesDescriptor("Module Attributes", ElixirSyntaxHighlighter.MODULE_ATTRIBUTE),
                AttributesDescriptor("Module Attributes//Documentation", ElixirSyntaxHighlighter.DOCUMENTATION_MODULE_ATTRIBUTE),
                AttributesDescriptor("Module Attributes//Documentation//Text", ElixirSyntaxHighlighter.DOCUMENTATION_TEXT),
                AttributesDescriptor("Module Attributes//Type Specifications//Callback", ElixirSyntaxHighlighter.CALLBACK),
                AttributesDescriptor("Module Attributes//Type Specifications//Specification", ElixirSyntaxHighlighter.SPECIFICATION),
                AttributesDescriptor("Module Attributes//Type Specifications//Type", ElixirSyntaxHighlighter.TYPE),
                AttributesDescriptor("Module Attributes//Type Specifications//Type Parameter", ElixirSyntaxHighlighter.TYPE_PARAMETER),
                AttributesDescriptor("Numbers//Base Prefix//Non-Decimal", ElixirSyntaxHighlighter.WHOLE_NUMBER_BASE),
                AttributesDescriptor("Numbers//Base Prefix//Obsolete Non-Decimal", ElixirSyntaxHighlighter.OBSOLETE_WHOLE_NUMBER_BASE),
                AttributesDescriptor("Numbers//Decimal Exponent, Mark, and Separator", ElixirSyntaxHighlighter.DECIMAL),
                AttributesDescriptor("Numbers//Digits//Invalid", ElixirSyntaxHighlighter.INVALID_DIGIT),
                AttributesDescriptor("Numbers//Digits//Valid", ElixirSyntaxHighlighter.VALID_DIGIT),
                AttributesDescriptor("Calls//Function", ElixirSyntaxHighlighter.FUNCTION_CALL),
                AttributesDescriptor("Calls//Predefined", ElixirSyntaxHighlighter.PREDEFINED_CALL),
                AttributesDescriptor("Calls//Macro", ElixirSyntaxHighlighter.MACRO_CALL),
                AttributesDescriptor("Calls//Guard", ElixirSyntaxHighlighter.GUARD_CALL),
                AttributesDescriptor("Textual//Character List", ElixirSyntaxHighlighter.CHAR_LIST),
                AttributesDescriptor("Textual//Escape Sequence", ElixirSyntaxHighlighter.VALID_ESCAPE_SEQUENCE),
                AttributesDescriptor("Textual//Sigil", ElixirSyntaxHighlighter.SIGIL),
                *sigilNames { _, _, name ->
                    AttributesDescriptor("Textual//Sigil//${name}", SIGIL_BY_NAME[name] ?: ElixirSyntaxHighlighter.SIGIL)
                }.toTypedArray(),
                AttributesDescriptor("Textual//String", ElixirSyntaxHighlighter.STRING),
                AttributesDescriptor("Variables//Ignored", ElixirSyntaxHighlighter.IGNORED_VARIABLE),
                AttributesDescriptor("Variables//Parameter", ElixirSyntaxHighlighter.PARAMETER),
                AttributesDescriptor("Variables//Variable", ElixirSyntaxHighlighter.VARIABLE)
        )
    }
}
