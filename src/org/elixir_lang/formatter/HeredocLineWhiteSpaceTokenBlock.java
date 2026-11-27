package org.elixir_lang.formatter;

import com.intellij.formatting.Block;
import com.intellij.formatting.Spacing;
import com.intellij.formatting.SpacingBuilder;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.formatter.common.AbstractBlock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

public class HeredocLineWhiteSpaceTokenBlock extends AbstractBlock {
    private final int textStart;
    @NotNull
    private final SpacingBuilder spacingBuilder;
    @Nullable
    private TextRange textRange;

    /** @param textStart where the line's decoded text starts in {@code child}, which must be shorter than it */
    HeredocLineWhiteSpaceTokenBlock(ASTNode child, int textStart, SpacingBuilder spacingBuilder) {
        super(child, null, null);

        assert textStart < child.getTextLength() : "HeredocLineWhiteSpaceTokenBlocks that end before the line's text starts should not be created";

        this.textStart = textStart;
        this.spacingBuilder = spacingBuilder;
    }

    @NotNull
    @Override
    protected List<Block> buildChildren() {
        return Collections.emptyList();
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
    public Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
        return spacingBuilder.getSpacing(this, child1, child2);
    }

    @NotNull
    @Override
    public TextRange getTextRange() {
        if (textRange == null) {
            TextRange heredocLinePrefixTextRange = super.getTextRange();
            textRange = new TextRange(
                    heredocLinePrefixTextRange.getStartOffset() + textStart,
                    heredocLinePrefixTextRange.getEndOffset()
            );
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
        return true;
    }

}
