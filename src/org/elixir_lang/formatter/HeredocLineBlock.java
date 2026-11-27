package org.elixir_lang.formatter;

import com.intellij.formatting.*;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.formatter.common.AbstractBlock;
import org.elixir_lang.psi.ElixirTypes;
import org.elixir_lang.psi.HeredocLineTextKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Unlike a {@link Block}, whose {@link Block#getTextRange()} is its full {@link ASTNode#getTextRange()}, a
 * HeredocLinkBlock's {@link #getTextRange()} starts {@link #textStart} characters into its {@link #getNode()}
 * {@link #getTextRange()}, so that the heredoc prefix can be realigned and indented with the parent of the heredoc.
 */
class HeredocLineBlock extends AbstractBlock {
    /** Where the line's decoded text starts: after as much of the heredoc's indentation as the line has. */
    private final int textStart;
    @NotNull
    private SpacingBuilder spacingBuilder;
    @Nullable
    private TextRange textRange;

    HeredocLineBlock(ASTNode heredocLine, int heredocPrefixLength, @NotNull SpacingBuilder spacingBuilder) {
        super(heredocLine, Wrap.createWrap(WrapType.NONE, false), null);
        this.textStart = HeredocLineTextKt.heredocLineTextStart(heredocLine.getChars(), 0, heredocPrefixLength);
        this.spacingBuilder = spacingBuilder;
    }

    @Override
    protected List<com.intellij.formatting.Block> buildChildren() {
        return Block.buildChildren(
                myNode,
                (child, childElementType, blockList) -> {
                    if (childElementType == ElixirTypes.HEREDOC_LINE_PREFIX) {
                        blockList.addAll(buildHeredocLinePrefixChildren(child));
                    } else {
                        blockList.add(new Block(child, spacingBuilder));
                    }

                    return blockList;
                }
        );
    }

    @Override
    public Indent getIndent() {
        return Indent.getNoneIndent();
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildHeredocLinePrefixChildren(ASTNode heredocLinePrefix) {
        return Block.buildChildren(
                heredocLinePrefix,
                (child, childElementType, blockList) -> {
                    if (childElementType == ElixirTypes.HEREDOC_LINE_WHITE_SPACE_TOKEN) {
                        /* It is an error to make an empty block.  This can occur if the line's text is indented before
                           the prefix for the terminator like `One` below

                           ```
                            """
                            One
                             """
                           ```
                         */
                        if (textStart < child.getTextLength()) {
                            blockList.add(new HeredocLineWhiteSpaceTokenBlock(child, textStart, spacingBuilder));
                        }
                    } else {
                        blockList.add(new Block(child, spacingBuilder));
                    }

                    return blockList;
                }
        );
    }

    /**
     * Returns a spacing object indicating what spaces and/or line breaks are added between two
     * specified children of this block.
     *
     * @param child1 the first child for which spacing is requested;
     *               {@code null} if given {@code 'child2'} block is the first document block
     * @param child2 the second child for which spacing is requested.
     * @return the spacing instance, or null if no special spacing is required. If null is returned,
     * the formatter does not insert or delete spaces between the child blocks, but may insert
     * a line break if the line wraps at the position between the child blocks.
     * @see Spacing#createSpacing(int, int, int, boolean, int)
     * @see Spacing#getReadOnlySpacing()
     */
    @Nullable
    @Override
    public Spacing getSpacing(@Nullable com.intellij.formatting.Block child1, @NotNull com.intellij.formatting.Block child2) {
        return spacingBuilder.getSpacing(this, child1, child2);
    }

    @NotNull
    @Override
    public TextRange getTextRange() {
        if (textRange == null) {
            TextRange superTextRange = super.getTextRange();
            textRange = new TextRange(superTextRange.getStartOffset() + textStart, superTextRange.getEndOffset());
        }

        return textRange;
    }

    /**
     * Returns true if the specified block may not contain child blocks. Used as an optimization
     * to avoid building the complete formatting model through calls to {@link #getSubBlocks()}.
     *
     * @return true if the block is a leaf block and may not contain child blocks, false otherwise.
     */
    @Override
    public boolean isLeaf() {
        return false;
    }
}
