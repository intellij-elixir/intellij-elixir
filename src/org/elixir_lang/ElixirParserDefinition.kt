package org.elixir_lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import org.elixir_lang.parser.TwoPassParser
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirTypes

class ElixirParserDefinition : ParserDefinition {
    override fun createElement(node: ASTNode): PsiElement = ElixirTypes.Factory.createElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = ElixirFile(viewProvider)
    override fun createLexer(project: Project): Lexer = ElixirLexer(project)
    override fun createParser(project: Project): PsiParser = TwoPassParser()
    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getFileNodeType(): IFileElementType = org.elixir_lang.psi.stub.type.File.INSTANCE
    override fun getStringLiteralElements(): TokenSet = STRING_LITERALS
    override fun getWhitespaceTokens(): TokenSet = WHITE_SPACES

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode, right: ASTNode): ParserDefinition.SpaceRequirements =
            if (left.elementType === ElixirTypes.KEYWORD_PAIR_COLON) {
                ParserDefinition.SpaceRequirements.MUST
            } else {
                ParserDefinition.SpaceRequirements.MAY
            }

    companion object {
        val COMMENTS = TokenSet.create(ElixirTypes.COMMENT)
        val STRING_LITERALS = TokenSet.create(ElixirTypes.FRAGMENT)
        val WHITE_SPACES = TokenSet.create(TokenType.WHITE_SPACE)
    }
}
