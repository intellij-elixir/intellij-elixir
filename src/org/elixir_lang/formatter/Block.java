package org.elixir_lang.formatter;

import com.intellij.application.options.CodeStyle;
import com.intellij.formatting.*;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.impl.source.tree.CompositeElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.elixir_lang.ElixirLanguage;
import org.elixir_lang.code_style.CodeStyleSettings;
import org.elixir_lang.psi.ElixirTypes;
import org.elixir_lang.psi.call.Call;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static com.intellij.formatting.ChildAttributes.DELEGATE_TO_PREV_CHILD;
import static org.apache.commons.lang3.StringUtils.isWhitespace;
import static org.elixir_lang.psi.ElixirTypes.*;
import static org.elixir_lang.psi.ElixirTypes.DO;
import static org.elixir_lang.psi.ElixirTypes.FN;
import static org.elixir_lang.psi.ElixirTypes.MULTIPLE_ALIASES;
import static org.elixir_lang.psi.call.name.Function.IMPORT;
import static org.elixir_lang.psi.impl.ElixirPsiImplUtil.*;
import static org.elixir_lang.psi.impl.PsiElementImplKt.document;

/**
 * Must implement {@link BlockEx}, or only the generic indent settings are used, not the language-specific ones.
 */
public class Block extends AbstractBlock implements BlockEx {
    private static final TokenSet ARROW_OPERATION_TOKEN_SET = TokenSet.create(
            MATCHED_ARROW_OPERATION,
            UNMATCHED_ARROW_OPERATION
    );
    private static final TokenSet BODY_TOKEN_SET = TokenSet.create(
            HEREDOC_LINE_BODY,
            LINE_BODY,
            INTERPOLATED_SIGIL_LINE_BODY,
            LITERAL_SIGIL_LINE_BODY
    );
    private static final TokenSet BOOLEAN_WORD_OPERATOR_TOKEN_SET = TokenSet.create(
            AND_WORD_OPERATOR,
            OR_WORD_OPERATOR
    );
    private static final TokenSet CAPTURE_NON_NUMERIC_OPERATION_TOKEN_SET = TokenSet.create(
            MATCHED_CAPTURE_NON_NUMERIC_OPERATION,
            UNMATCHED_CAPTURE_NON_NUMERIC_OPERATION
    );
    private static final TokenSet COMPARISON_OPERATION_TOKEN_SET = TokenSet.create(
            MATCHED_COMPARISON_OPERATION,
            UNMATCHED_COMPARISON_OPERATION
    );
    /** The rules a delimiter's container is reached through: a container groups its pairs under a node of its own. */
    private static final TokenSet DELIMITER_GROUPING_TOKEN_SET = TokenSet.create(
            ASSOCIATIONS,
            ASSOCIATIONS_BASE,
            KEYWORDS,
            MAP_CONSTRUCTION_ARGUMENTS
    );
    /**
     * The tokens that punctuate a container: the delimiter it opens with, and the commas and {@code ->} between its
     * elements.
     */
    private static final TokenSet DELIMITER_TOKEN_SET = TokenSet.create(
            COMMA,
            OPENING_BIT,
            OPENING_BRACKET,
            OPENING_CURLY,
            OPENING_PARENTHESIS,
            STAB_OPERATOR
    );
    private static final TokenSet HEREDOC_LINE_TOKEN_SET = TokenSet.create(
            HEREDOC_LINE,
            INTERPOLATED_HEREDOC_LINE,
            LITERAL_HEREDOC_LINE
    );
    private static final TokenSet HEREDOC_TOKEN_SET = TokenSet.create(
            HEREDOC,
            INTERPOLATED_SIGIL_HEREDOC,
            LITERAL_SIGIL_HEREDOC
    );
    private static final TokenSet KEYWORD_PAIR_TOKEN_SET = TokenSet.create(
            KEYWORD_PAIR,
            NO_PARENTHESES_KEYWORD_PAIR
    );
    private static final TokenSet LINE_TOKEN_SET = TokenSet.create(
            LINE,
            INTERPOLATED_SIGIL_LINE,
            LITERAL_SIGIL_LINE
    );
    private static final TokenSet MAP_ARGUMENTS_CHILD_TOKEN_SET = TokenSet.create(
            MAP_CONSTRUCTION_ARGUMENTS,
            MAP_UPDATE_ARGUMENTS
    );
    private static final TokenSet MAP_OPERATOR_TOKEN_SET = TokenSet.create(MAP_OPERATOR);
    private static final TokenSet MAP_TAIL_ARGUMENTS_TOKEN_SET = TokenSet.create(
            ASSOCIATIONS,
            ASSOCIATIONS_BASE,
            KEYWORDS
    );
    private static final TokenSet MAP_TOKEN_SET = TokenSet.create(
            MAP_OPERATION,
            STRUCT_OPERATION
    );
    private static final TokenSet MATCHED_CALL_TOKEN_SET = TokenSet.create(
            MATCHED_AT_UNQUALIFIED_NO_PARENTHESES_CALL,
            MATCHED_DOT_CALL,
            MATCHED_QUALIFIED_NO_ARGUMENTS_CALL,
            MATCHED_QUALIFIED_NO_PARENTHESES_CALL,
            MATCHED_QUALIFIED_PARENTHESES_CALL,
            MATCHED_UNQUALIFIED_NO_ARGUMENTS_CALL,
            MATCHED_UNQUALIFIED_NO_PARENTHESES_CALL,
            MATCHED_UNQUALIFIED_PARENTHESES_CALL
    );
    private static final TokenSet MATCH_OPERATION_TOKEN_SET = TokenSet.create(
            MATCHED_MATCH_OPERATION,
            UNMATCHED_MATCH_OPERATION
    );
    private static final TokenSet ENFORCE_INDENT_TO_CHILDREN_TOKEN_SET = TokenSet.orSet(
            COMPARISON_OPERATION_TOKEN_SET,
            MATCH_OPERATION_TOKEN_SET
    );
    private static final TokenSet MULTIPLICATION_OPERATION_TOKEN_SET = TokenSet.create(
            MATCHED_MULTIPLICATION_OPERATION,
            UNMATCHED_MULTIPLICATION_OPERATION
    );
    private static final TokenSet NO_ARGUMENTS_CALL_TOKEN_SET = TokenSet.create(
            MATCHED_QUALIFIED_NO_ARGUMENTS_CALL,
            MATCHED_UNQUALIFIED_NO_ARGUMENTS_CALL,
            UNMATCHED_QUALIFIED_NO_ARGUMENTS_CALL,
            UNMATCHED_UNQUALIFIED_NO_ARGUMENTS_CALL
    );
    private static final TokenSet NO_PARENTHESES_KEYWORD_PAIR_TOKEN_SET = TokenSet.create(NO_PARENTHESES_KEYWORD_PAIR);
    /** The containers whose elements sit one indent in from the line the container starts on. */
    private static final TokenSet NORMAL_INDENT_CONTAINER_TOKEN_SET = TokenSet.create(
            BIT_STRING,
            BLOCK_IDENTIFIER,
            LIST,
            MAP_ARGUMENTS,
            MULTIPLE_ALIASES,
            STAB_INFIX_OPERATOR,
            TUPLE
    );
    private static final TokenSet OPERATION_TOKEN_SET = TokenSet.orSet(
            TokenSet.create(
                    MATCHED_ADDITION_OPERATION,
                    MATCHED_CAPTURE_NON_NUMERIC_OPERATION,
                    MATCHED_IN_MATCH_OPERATION,
                    MATCHED_IN_OPERATION,
                    MATCHED_MATCH_OPERATION,
                    MATCHED_MULTIPLICATION_OPERATION,
                    MATCHED_RELATIONAL_OPERATION,
                    MATCHED_THREE_OPERATION,
                    MATCHED_UNARY_OPERATION,
                    MATCHED_WHEN_OPERATION,
                    UNMATCHED_ADDITION_OPERATION,
                    UNMATCHED_CAPTURE_NON_NUMERIC_OPERATION,
                    UNMATCHED_IN_MATCH_OPERATION,
                    UNMATCHED_IN_OPERATION,
                    UNMATCHED_MATCH_OPERATION,
                    UNMATCHED_MULTIPLICATION_OPERATION,
                    UNMATCHED_RELATIONAL_OPERATION,
                    UNMATCHED_THREE_OPERATION,
                    UNMATCHED_UNARY_OPERATION,
                    UNMATCHED_WHEN_OPERATION
            ),
            COMPARISON_OPERATION_TOKEN_SET,
            MATCH_OPERATION_TOKEN_SET
    );
    private static final TokenSet OPERATOR_RULE_TOKEN_SET = TokenSet.create(
            ADDITION_INFIX_OPERATOR,
            ARROW_INFIX_OPERATOR,
            AT_PREFIX_OPERATOR,
            CAPTURE_PREFIX_OPERATOR,
            COMPARISON_INFIX_OPERATOR,
            DOT_INFIX_OPERATOR,
            IN_INFIX_OPERATOR,
            IN_MATCH_INFIX_OPERATOR,
            MATCH_INFIX_OPERATOR,
            MULTIPLICATION_INFIX_OPERATOR,
            RELATIONAL_INFIX_OPERATOR,
            STAB_INFIX_OPERATOR,
            THREE_INFIX_OPERATOR,
            UNARY_PREFIX_OPERATOR,
            WHEN_INFIX_OPERATOR
    );
    private static final TokenSet OPERATOR_TOKEN_SET = TokenSet.orSet(
            ADDITION_OPERATOR_TOKEN_SET,
            AND_OPERATOR_TOKEN_SET,
            ARROW_OPERATOR_TOKEN_SET,
            AT_OPERATOR_TOKEN_SET,
            CAPTURE_OPERATOR_TOKEN_SET,
            COMPARISON_OPERATOR_TOKEN_SET,
            DOT_OPERATOR_TOKEN_SET,
            IN_OPERATOR_TOKEN_SET,
            IN_MATCH_OPERATOR_TOKEN_SET,
            MAP_OPERATOR_TOKEN_SET,
            MATCH_OPERATOR_TOKEN_SET,
            MULTIPLICATIVE_OPERATOR_TOKEN_SET,
            PIPE_OPERATOR_TOKEN_SET,
            OR_OPERATOR_TOKEN_SET,
            RELATIONAL_OPERATOR_TOKEN_SET,
            STAB_OPERATOR_TOKEN_SET,
            STRUCT_OPERATOR_TOKEN_SET,
            TERNARY_OPERATOR_TOKEN_SET,
            THREE_OPERATOR_TOKEN_SET,
            TYPE_OPERATOR_TOKEN_SET,
            TWO_OPERATOR_TOKEN_SET,
            UNARY_OPERATOR_TOKEN_SET,
            WHEN_OPERATOR_TOKEN_SET
    );
    private static final TokenSet PIPE_OPERATION_TOKEN_SET = TokenSet.create(
            MATCHED_PIPE_OPERATION,
            UNMATCHED_PIPE_OPERATION
    );
    // "Sometimes" because only the `and` and `or` operators will be affected
    private static final TokenSet SOMETIMES_BOOLEAN_OPERATION_TOKEN_SET = TokenSet.create(
            MATCHED_AND_OPERATION,
            MATCHED_OR_OPERATION,
            UNMATCHED_AND_OPERATION,
            UNMATCHED_OR_OPERATION
    );
    // "Sometimes" because only the `and` and `or` operators will be affected
    private static final TokenSet SOMETIMES_BOOLEAN_OPERATOR_RULE_TOKEN_SET = TokenSet.create(
            AND_INFIX_OPERATOR,
            OR_INFIX_OPERATOR
    );
    private static final TokenSet TWO_OPERATION_TOKEN_SET = TokenSet.create(
            MATCHED_TWO_OPERATION,
            UNMATCHED_TWO_OPERATION
    );
    private static final TokenSet TYPE_OPERATION_TOKEN_SET = TokenSet.create(
            MATCHED_TYPE_OPERATION,
            UNMATCHED_TYPE_OPERATION
    );
    private static final TokenSet UNINDENTED_ONLY_ARGUMENT_TOKEN_SET = TokenSet.orSet(
            TokenSet.create(
                    ANONYMOUS_FUNCTION,
                    BIT_STRING,
                    LIST,
                    TUPLE
            ),
            HEREDOC_TOKEN_SET,
            MAP_TOKEN_SET
    );
    private static final TokenSet UNMATCHED_CALL_TOKEN_SET = TokenSet.create(
            UNMATCHED_AT_UNQUALIFIED_NO_PARENTHESES_CALL,
            UNMATCHED_DOT_CALL,
            UNMATCHED_QUALIFIED_NO_ARGUMENTS_CALL,
            UNMATCHED_QUALIFIED_NO_PARENTHESES_CALL,
            UNMATCHED_QUALIFIED_PARENTHESES_CALL,
            UNMATCHED_UNQUALIFIED_NO_ARGUMENTS_CALL,
            UNMATCHED_UNQUALIFIED_NO_PARENTHESES_CALL,
            UNMATCHED_UNQUALIFIED_PARENTHESES_CALL
    );
    private static final TokenSet WHEN_OPERATION_TOKEN_SET = TokenSet.create(
            MATCHED_WHEN_OPERATION,
            UNMATCHED_WHEN_OPERATION
    );
    private static final TokenSet WHITESPACE_TOKEN_SET = TokenSet.create(EOL, TokenType.WHITE_SPACE);
    @Nullable
    private final Alignment childrenAlignment;
    @Nullable
    private final Wrap childrenWrap;
    @Nullable
    private final Indent indent;
    @NotNull
    private final SpacingBuilder spacingBuilder;

    public Block(@NotNull ASTNode node, @NotNull SpacingBuilder spacingBuilder) {
        this(node, null, null, spacingBuilder, null, null, null);
    }

    public Block(@NotNull ASTNode node,
                 @Nullable Wrap wrap,
                 @Nullable Alignment alignment,
                 @NotNull SpacingBuilder spacingBuilder,
                 @Nullable Indent indent,
                 @Nullable Wrap childrenWrap,
                 @Nullable Alignment childrenAlignment) {
        super(node, wrap, alignment);
        this.spacingBuilder = spacingBuilder;
        this.indent = indent;
        this.childrenWrap = childrenWrap;
        this.childrenAlignment = childrenAlignment;

        IElementType elementType = node.getElementType();

        assert elementType != ACCESS_EXPRESSION : "accessExpressions should be flattened with " +
                "buildAccessExpressionChildren";

        assert elementType != STAB_OPERATION || childrenWrap != null :
                "stabOperation must have a non-null childrenWrap, so that sibling stabOperations and their " +
                        "children are wrapped consistently";
    }

    @NotNull
    static List<com.intellij.formatting.Block> buildChildren(
            @NotNull ASTNode node,
            @NotNull BlockListReducer blockListReducer) {
        List<com.intellij.formatting.Block> blockList = new ArrayList<>();

        ASTNode child = node.getFirstChildNode();

        while (child != null) {
            if (shouldBuildBlock(child)) {
                blockList = blockListReducer.reduce(child, child.getElementType(), blockList);
            }

            child = child.getTreeNext();
        }

        return blockList;
    }

    private static boolean hasAtLeastCountChildren(@NotNull CompositeElement parent,
                                                   @NotNull IElementType childElementType,
                                                   int atLeastCount) {
        int count = 0;
        for (ASTNode child = parent.getFirstChildNode();
             child != null && count < atLeastCount;
             child = child.getTreeNext()) {
            if (child.getElementType() == childElementType) {
                count++;
            }
        }

        return count >= atLeastCount;
    }

    private static boolean shouldBuildBlock(@NotNull IElementType childElementType) {
        return !WHITESPACE_TOKEN_SET.contains(childElementType);
    }

    private static boolean shouldBuildBlock(@NotNull ASTNode child) {
        return shouldBuildBlock(child.getElementType()) && child.getTextLength() > 0;
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildAccessExpressionChildren(@NotNull ASTNode accessExpression) {
        return buildAccessExpressionChildren(accessExpression, null, null, null);
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildAccessExpressionChildren(@NotNull ASTNode accessExpression,
                                                                              @Nullable Alignment childrenAlignment) {
        return buildAccessExpressionChildren(accessExpression, null, childrenAlignment, null);
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildAccessExpressionChildren(@NotNull ASTNode accessExpression,
                                                                              @Nullable Wrap childrenWrap) {
        return buildAccessExpressionChildren(accessExpression, childrenWrap, null, null);
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildAccessExpressionChildren(@NotNull ASTNode accessExpression,
                                                                              @Nullable Wrap childrenWrap,
                                                                              @Nullable Alignment childrenAlignment) {
        return buildAccessExpressionChildren(accessExpression, childrenWrap, childrenAlignment, null);
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildAccessExpressionChildren(@NotNull ASTNode accessExpression,
                                                                              @Nullable Wrap childrenWrap,
                                                                              @Nullable Indent childrenIndent) {
        return buildAccessExpressionChildren(accessExpression, childrenWrap, null, childrenIndent);
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildAccessExpressionChildren(
            @NotNull ASTNode accessExpression,
            @Nullable Wrap childrenWrap,
            @Nullable Alignment childrenAlignment,
            @Nullable Indent childrenIndent
    ) {
        return buildChildren(
                accessExpression,
                (child, childElementType, blockList) -> {
                    blockList.add(buildChild(child, childrenWrap, childrenAlignment, childrenIndent));

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildAlignedOperandsOperationChildren(
            @NotNull ASTNode operation,
            @NotNull Predicate<CodeStyleSettings> alignOperands,
            @NotNull TokenSet operatorRuleTokenSet) {
        return buildAlignedOperandsOperationChildren(operation, alignOperands, operatorRuleTokenSet, null);
    }

    /**
     * @param inheritedOperandAlignment the {@link Alignment} of the operation this one is nested in, so that the
     *                                  operands of both share a column; {@code null} to start a new one
     */
    @NotNull
    private List<com.intellij.formatting.Block> buildAlignedOperandsOperationChildren(
            @NotNull ASTNode operation,
            @NotNull Predicate<CodeStyleSettings> alignOperands,
            @NotNull TokenSet operatorRuleTokenSet,
            @Nullable Alignment inheritedOperandAlignment) {
        Alignment operandAlignment;

        if (alignOperands.test(codeStyleSettings(operation))) {
            operandAlignment =
                    inheritedOperandAlignment != null ? inheritedOperandAlignment : Alignment.createAlignment();
        } else {
            operandAlignment = null;
        }

        return buildChildren(
                operation,
                (child, childElementType, blockList) -> {
                    if (childElementType == ACCESS_EXPRESSION) {
                        blockList.addAll(buildAccessExpressionChildren(child, operandAlignment));
                    } else if (operatorRuleTokenSet.contains(childElementType)) {
                        blockList.addAll(buildOperatorRuleChildren(child, null, null, operandAlignment));
                    } else {
                        blockList.add(buildChild(child, operandAlignment));
                    }

                    return blockList;
                }
        );
    }

    /**
     * Builds anonymousFunction FN, stab, and END as siblings.  If it is a one-liner, the END is setup to wrap once
     * all other part wraps, such as due to chopping.
     */
    @NotNull
    private List<com.intellij.formatting.Block> buildAnonymousFunctionChildren(@NotNull ASTNode anonymousFunction,
                                                                               @Nullable Wrap stabWrap,
                                                                               @Nullable Alignment endAlignment) {
        boolean relativeToIndirectParent = !firstOnLine(anonymousFunction) && !lastArgument(anonymousFunction);
        Indent stabIndent = Indent.getNormalIndent(relativeToIndirectParent);

        return buildContainerChildren(
                anonymousFunction,
                FN,
                null,
                stabIndent,
                stabWrap,
                END,
                endAlignment,
                (child, childElementType, tailWrap, childrenIndent, blockList) -> {
                    if (childElementType == END_OF_EXPRESSION) {
                        blockList.addAll(buildEndOfExpressionChildren(child, null, childrenIndent));
                    } else if (childElementType == STAB) {
                        blockList.addAll(
                                buildStabChildren(
                                        (CompositeElement) child,
                                        /* DO NOT use `tailWrap` because it will be `WrapType.ALWAYS` if `fn .. end` is
                                           over more than one line, but in that case only the `stabBody` of the
                                           `stabOperation` needs to be wrapped UNLESS there are more than one
                                           `stabOperation`, but `buildStabChildren` will determine that */
                                        null
                                )
                        );
                    } else {
                        blockList.add(buildChild(child, tailWrap, childrenIndent));
                    }

                    return blockList;
                }
        );
    }

    /**
     * @param startAlignment the alignment for the start of the arrow operation (pipeline) chain.  If {@code null} is
     *                       given, then the left operand of the {@code arrowOperation} is given an alignment and it
     *                       will be the startAlignment or any arrowOperations in the right operand.
     */
    @NotNull
    private List<com.intellij.formatting.Block> buildArrowOperationChildren(@NotNull ASTNode arrowOperation,
                                                                            @Nullable Alignment startAlignment) {
        Alignment operatorAlignment;
        final Alignment[] operandAlignment = new Alignment[1];
        Alignment nestedStartAlignment;

        if (startAlignment == null) {
            nestedStartAlignment = Alignment.createAlignment();
        } else {
            nestedStartAlignment = startAlignment;
        }

        // if no arrow operations remain, then first operand in buildChildren lambda will be start
        if (arrowOperation.findChildByType(ARROW_OPERATION_TOKEN_SET) == null) {
            operandAlignment[0] = nestedStartAlignment;
        } else {
            operandAlignment[0] = null;
        }

        final Wrap[] operandWrap = {null};
        operatorAlignment = nestedStartAlignment;

        return buildChildren(
                arrowOperation,
                (child, childElementType, blockList) -> {
                    if (childElementType == ACCESS_EXPRESSION) {
                        blockList.addAll(
                                buildAccessExpressionChildren(child, operandWrap[0], operandAlignment[0])
                        );
                    } else if (ARROW_OPERATION_TOKEN_SET.contains(childElementType)) {
                        blockList.addAll(buildArrowOperationChildren(child, nestedStartAlignment));
                    } else if (childElementType == ARROW_INFIX_OPERATOR) {
                        blockList.addAll(
                                buildOperatorRuleChildren(
                                        child,
                                        Wrap.createWrap(WrapType.ALWAYS, true),
                                        operatorAlignment
                                )
                        );
                        /* right operand has alignment only so that any children can align to it instead of operator,
                           which ensures that unmatched call do block's end aligns with start of the call instead of
                           the arrow operator */
                        operandAlignment[0] = Alignment.createAlignment();
                        operandWrap[0] = Wrap.createWrap(WrapType.NONE, true);
                    } else {
                        blockList.add(buildChild(child, operandWrap[0], operandAlignment[0]));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildAssociationsBaseChildren(
            @NotNull ASTNode associationsBase,
            @NotNull Wrap containerAssociationOperationWrap) {
        return buildChildren(
                associationsBase,
                (child, childElementType, blockList) -> {
                    if (childElementType == CONTAINER_ASSOCIATION_OPERATION) {
                        blockList.add(buildChild(child, containerAssociationOperationWrap, Indent.getNormalIndent()));
                    } else {
                        blockList.add(buildChild(child));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildAssociationsChildren(
            @NotNull ASTNode associations,
            @NotNull Wrap associationExpressionWrap
    ) {
        return buildChildren(
                associations,
                (child, childElementType, blockList) -> {
                    if (childElementType == ASSOCIATIONS_BASE) {
                        blockList.addAll(
                                buildAssociationsBaseChildren(
                                        child,
                                        associationExpressionWrap
                                )
                        );
                    } else if (childElementType == COMMA) {
                        blockList.add(buildChild(child, Wrap.createWrap(WrapType.NONE, false)));
                    } else {
                        // comment
                        blockList.add(buildChild(child, (Indent) null));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildBitStringChildren(@NotNull ASTNode list,
                                                                       @Nullable Wrap openingBitWrap,
                                                                       @Nullable Wrap containerArgumentsWrap,
                                                                       @Nullable Indent elementIndent,
                                                                       @Nullable Alignment closingBitAlignment) {
        return buildContainerChildren(
                list,
                OPENING_BIT,
                openingBitWrap,
                elementIndent,
                containerArgumentsWrap,
                CLOSING_BIT,
                closingBitAlignment
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildBlockIdentifierChildren(
            @NotNull ASTNode blockIdentifier,
            @Nullable Alignment blockIdentifierAlignment
    ) {
        Wrap childrenWrap = Wrap.createWrap(WrapType.ALWAYS, true);

        return buildChildren(
                blockIdentifier,
                (child, childElementType, blockList) -> {
                    blockList.add(buildChild(child, childrenWrap, blockIdentifierAlignment));

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildBlockItemChildren(@NotNull ASTNode blockItem,
                                                                       @Nullable Alignment blockItemAlignment) {
        return buildChildren(
                blockItem,
                (child, childElementType, blockList) -> {
                    if (childElementType == BLOCK_IDENTIFIER) {
                        blockList.addAll(buildBlockIdentifierChildren(child, blockItemAlignment));
                    } else if (childElementType == END_OF_EXPRESSION) {
                        blockList.addAll(buildEndOfExpressionChildren(child, null, null));
                    } else if (childElementType == STAB) {
                        Wrap stabBodyChildrenWrap;

                        if (child.findChildByType(STAB_BODY) != null) {
                            stabBodyChildrenWrap = Wrap.createWrap(WrapType.ALWAYS, true);
                        } else {
                            stabBodyChildrenWrap = null;
                        }

                        blockList.addAll(buildStabChildren((CompositeElement) child, stabBodyChildrenWrap));
                    } else {
                        blockList.add(buildChild(child));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildBlockListChildren(@NotNull ASTNode blockListNode,
                                                                       @Nullable Alignment parentAlignment) {
        return buildChildren(
                blockListNode,
                (child, childElementType, blockList) -> {
                    blockList.add(buildChild(child, parentAlignment, Indent.getNoneIndent()));

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildBodyChildren(@NotNull ASTNode body, @Nullable Wrap childrenWrap) {
        return buildChildren(
                body,
                (child, childElementType, blockList) -> {
                    blockList.add(buildChild(child, childrenWrap));

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildCallChildChildren(@NotNull ASTNode child,
                                                                       @NotNull IElementType childElementType,
                                                                       @Nullable Wrap callWrap,
                                                                       @Nullable Alignment callAlignment) {
        List<com.intellij.formatting.Block> blockList = new ArrayList<>();

        if (childElementType == ACCESS_EXPRESSION) {
            blockList.addAll(buildAccessExpressionChildren(child));
        } else if (childElementType == IDENTIFIER) {
            blockList.addAll(buildIdentifierChildren(child));
        } else if (childElementType == MATCHED_PARENTHESES_ARGUMENTS) {
            blockList.addAll(buildMatchedParenthesesArguments(child, callWrap, callAlignment));
        } else if (childElementType == NO_PARENTHESES_ONE_ARGUMENT) {
            blockList.addAll(buildNoParenthesesOneArgument(child, callWrap, callAlignment));
        } else if (OPERATOR_RULE_TOKEN_SET.contains(childElementType)) {
            blockList.addAll(buildOperatorRuleChildren(child));
        } else {
            blockList.add(buildChild(child));
        }

        return blockList;
    }

    /**
     * {@link #getSpacing(com.intellij.formatting.Block, com.intellij.formatting.Block)} only has the parent block and
     * the two direct children as context for evaluating rules, so to distinguish normal division `/` from `/` in
     * `&NAME/ARITY`, for `&...` need to look ahead and down and if `NAME/ARITY` is detected then the `/` operation will
     * need to flattened so that the `DIVISION_OPERATOR` itself is an immediate child of
     * `MATCHED_CAPTURE_NON_NUMERIC_OPERATION` or `UNMATCHED_CAPTURE_NON_NUMERIC_OPERATION`.
     *
     * @param captureNonNumericOperation A capture operation that may be a name/arity reference.
     */
    @NotNull
    private List<com.intellij.formatting.Block> buildCaptureNonNumericOperationChildren(
            ASTNode captureNonNumericOperation
    ) {
        return buildChildren(
                captureNonNumericOperation,
                (child, childElementType, blockList) -> {
                    if (childElementType == ACCESS_EXPRESSION) {
                        blockList.addAll(buildAccessExpressionChildren(child));
                    } else if (childElementType == CAPTURE_PREFIX_OPERATOR) {
                        blockList.addAll(buildOperatorRuleChildren(child));
                    } else if (MULTIPLICATION_OPERATION_TOKEN_SET.contains(childElementType)) {
                        blockList.addAll(
                                buildCapturedMultiplicationOperationChildren(child)
                        );
                    } else {
                        blockList.add(buildChild(child));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildCapturedMultiplicationOperationChildren(
            ASTNode multiplicationOperation
    ) {
        List<com.intellij.formatting.Block> flattenedBlockList = buildOperationChildren(multiplicationOperation);
        List<com.intellij.formatting.Block> blockList = null;

        if (flattenedBlockList.size() == 3) {
            ASTBlock operatorBlock = (ASTBlock) flattenedBlockList.get(1);

            if (Objects.requireNonNull(operatorBlock.getNode()).getElementType() == DIVISION_OPERATOR) {
                ASTBlock rightOperandBlock = (ASTBlock) flattenedBlockList.get(2);

                // `/ARITY` confirmed
                if (Objects.requireNonNull(rightOperandBlock.getNode()).getElementType() == DECIMAL_WHOLE_NUMBER) {
                    ASTBlock leftOperandBlock = (ASTBlock) flattenedBlockList.getFirst();
                    ASTNode leftOperand = Objects.requireNonNull(leftOperandBlock.getNode());
                    IElementType leftOperandElementType = leftOperand.getElementType();

                    // `NAME/ARITY` confirmed
                    if (NO_ARGUMENTS_CALL_TOKEN_SET.contains(leftOperandElementType) &&
                            leftOperand.findChildByType(DO_BLOCK) == null) {
                        blockList = flattenedBlockList;
                    }
                }
            }
        }

        if (blockList == null) {
            // don't flatten
            blockList = Collections.singletonList(buildChild(multiplicationOperation));
        }

        return blockList;
    }

    @NotNull
    private Block buildChild(@NotNull ASTNode child) {
        return new Block(child, spacingBuilder);
    }

    @NotNull
    private Block buildChild(@NotNull ASTNode child, @Nullable Alignment alignment) {
        return new Block(child, null, alignment, spacingBuilder, null, null, null);
    }

    @NotNull
    private Block buildChild(@NotNull ASTNode child, @Nullable Indent indent) {
        return new Block(child, null, null, spacingBuilder, indent, null, null);
    }

    @NotNull
    private Block buildChild(@NotNull ASTNode child, @Nullable Wrap wrap) {
        return new Block(child, wrap, null, spacingBuilder, null, null, null);
    }

    @NotNull
    private Block buildChild(@NotNull ASTNode child, @Nullable Wrap wrap, @Nullable Alignment alignment) {
        return new Block(child, wrap, alignment, spacingBuilder, null, null, null);
    }

    @NotNull
    private Block buildChild(@NotNull ASTNode child,
                             @Nullable Wrap wrap,
                             @Nullable @SuppressWarnings("SameParameterValue") Alignment alignment,
                             @Nullable Alignment childrenAlignment) {
        return new Block(child, wrap, alignment, spacingBuilder, null, null, childrenAlignment);
    }

    @NotNull
    private Block buildChild(@NotNull ASTNode child, @Nullable Wrap wrap, @Nullable Indent indent) {
        return new Block(child, wrap, null, spacingBuilder, indent, null, null);
    }

    @NotNull
    private Block buildChild(@NotNull ASTNode child, @Nullable Alignment alignment, @Nullable Indent indent) {
        return new Block(child, null, alignment, spacingBuilder, indent, null, null);
    }

    @NotNull
    private Block buildChild(@NotNull ASTNode child,
                             @Nullable Wrap wrap,
                             @Nullable Alignment alignment,
                             @Nullable Indent indent) {
        return new Block(child, wrap, alignment, spacingBuilder, indent, null, null);
    }

    @NotNull
    private Block buildChild(@NotNull ASTNode child,
                             @Nullable Wrap wrap,
                             @Nullable Indent indent,
                             @Nullable Wrap childrenWrap) {
        return new Block(
                child,
                wrap,
                null,
                spacingBuilder,
                indent,
                childrenWrap,
                null
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildChildren(@NotNull ASTNode parent,
                                                              @Nullable Wrap parentWrap,
                                                              @Nullable Alignment parentAlignment,
                                                              @Nullable Wrap childrenWrap,
                                                              @Nullable Alignment childrenAlignment) {
        List<com.intellij.formatting.Block> blocks;
        IElementType parentElementType = parent.getElementType();

        if (parentElementType == ANONYMOUS_FUNCTION) {
            blocks = buildAnonymousFunctionChildren(parent, childrenWrap, childrenAlignment);
        } else if (ARROW_OPERATION_TOKEN_SET.contains(parentElementType)) {
            blocks = buildArrowOperationChildren(parent, null);
        } else if (parentElementType == BIT_STRING) {
            blocks = buildBitStringChildren(parent, null, childrenWrap, Indent.getNormalIndent(), childrenAlignment);
        } else if (parentElementType == BLOCK_ITEM) {
            blocks = buildBlockItemChildren(parent, parentAlignment);
        } else if (CAPTURE_NON_NUMERIC_OPERATION_TOKEN_SET.contains(parentElementType)) {
            blocks = buildCaptureNonNumericOperationChildren(parent);
        } else if (parentElementType == CONTAINER_ASSOCIATION_OPERATION) {
            blocks = buildContainerAssociationOperation(parent);
        } else if (HEREDOC_TOKEN_SET.contains(parentElementType)) {
            blocks = buildHeredocChildren((CompositeElement) parent);
        } else if (parentElementType == INTERPOLATION) {
            blocks = buildInterpolationChildren(parent);
        } else if (parentElementType == KEYWORD_PAIR) {
            blocks = buildKeywordPairChildren(parent);
        } else if (parentElementType == KEYWORD_KEY) {
            blocks = buildKeywordKeyChildren(parent);
        } else if (LINE_TOKEN_SET.contains(parentElementType)) {
            blocks = buildLineChildren(parent);
        } else if (parentElementType == LIST) {
            blocks = buildListChildren(parent, null, childrenWrap, Indent.getNormalIndent(), childrenAlignment);
        } else if (MATCHED_CALL_TOKEN_SET.contains(parentElementType)) {
            blocks = buildMatchedCallChildren(parent, parentWrap, parentAlignment);
        } else if (MAP_TOKEN_SET.contains(parentElementType)) {
            blocks = buildMapChildren(parent, null, childrenWrap, null, childrenAlignment);
        } else if (parentElementType == MULTIPLE_ALIASES) {
            blocks = buildMultipleAliasesChildren(parent);
        } else if (parentElementType == NO_PARENTHESES_KEYWORD_PAIR) {
            blocks = buildNoParenthesesKeywordPairChildren(parent);
        } else if (parentElementType == NO_PARENTHESES_KEYWORDS) {
            blocks = buildNoParenthesesKeywordsChildren(parent);
        } else if (parentElementType == PARENTHETICAL_STAB) {
            blocks = buildParentheticalStabChildren(parent);
        } else if (parentElementType == STAB_OPERATION) {
            //noinspection ConstantConditions
            blocks = buildStabOperationChildren(parent, childrenWrap);
        } else if (parentElementType == TUPLE) {
            blocks = buildTupleChildren(parent, null, childrenWrap, Indent.getNormalIndent(), childrenAlignment);
        } else if (WHEN_OPERATION_TOKEN_SET.contains(parentElementType)) {
            blocks = buildWhenOperationChildren(parent);
        } else if (OPERATION_TOKEN_SET.contains(parentElementType)) {
            blocks = buildOperationChildren(parent);
        } else if (PIPE_OPERATION_TOKEN_SET.contains(parentElementType)) {
            blocks = buildPipeOperationChildren(parent, enclosingPipeOperandAlignment(parent, parentAlignment));
        } else if (SOMETIMES_BOOLEAN_OPERATION_TOKEN_SET.contains(parentElementType)) {
            blocks = buildSometimesBooleanOperationChildren(parent);
        } else if (TWO_OPERATION_TOKEN_SET.contains(parentElementType)) {
            blocks = buildTwoOperationChildren(parent);
        } else if (TYPE_OPERATION_TOKEN_SET.contains(parentElementType)) {
            blocks = buildTypeOperationChildren(parent);
        } else if (UNMATCHED_CALL_TOKEN_SET.contains(parentElementType)) {
            blocks = buildUnmatchedCallChildren(parent, parentWrap, parentAlignment);
        } else {
            Wrap commaWrap = Wrap.createChildWrap(childrenWrap, WrapType.NONE, true);

            /* all children need a shared alignment, so that the second child doesn't have an automatic continuation
               indent */
            if (childrenAlignment == null) {
                childrenAlignment = Alignment.createChildAlignment(parentAlignment);
            }

            Alignment finalChildrenAlignment = childrenAlignment;

            blocks = buildChildren(
                    parent,
                    (child, childElementType, blockList) -> {
                        if (childElementType == ACCESS_EXPRESSION) {
                            blockList.addAll(
                                    buildAccessExpressionChildren(child, childrenWrap, finalChildrenAlignment)
                            );
                        } else if (ENFORCE_INDENT_TO_CHILDREN_TOKEN_SET.contains(childElementType)) {
                            blockList.add(
                                    buildChild(
                                            child,
                                            finalChildrenAlignment,
                                            Indent.getIndent(
                                                    Indent.Type.NONE,
                                                    true,
                                                    /* `enforceIndentToChildren = true`, so that `do` blocks none-indent
                                                       aligns to start of child when child is argument to `assert`  */
                                                    true
                                            )
                                    )
                            );
                        } else if (OPERATOR_RULE_TOKEN_SET.contains(childElementType)) {
                            blockList.addAll(buildOperatorRuleChildren(child));
                        } else if (childElementType == COMMA) {
                            blockList.add(buildChild(child, commaWrap));
                        } else if (childElementType == END_OF_EXPRESSION) {
                            blockList.addAll(
                                    // None Indent because comments should align to left
                                    buildEndOfExpressionChildren(child, finalChildrenAlignment, Indent.getNoneIndent())
                            );
                        } else if (childElementType == WHEN_INFIX_OPERATOR) {
                            blockList.addAll(buildOperatorRuleChildren(child));
                        } else {
                            blockList.add(
                                    buildChild(child, childrenWrap, finalChildrenAlignment)
                            );
                        }

                        return blockList;
                    }
            );
        }

        return blocks;
    }

    @Override
    protected List<com.intellij.formatting.Block> buildChildren() {
        return buildChildren(myNode, myWrap, myAlignment, childrenWrap, childrenAlignment);
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildContainerAssociationOperation(
            @NotNull ASTNode containerAssociationOperation
    ) {
        Wrap keyWrap = Wrap.createWrap(WrapType.NORMAL, true);
        Wrap associationOperatorWrap = Wrap.createChildWrap(keyWrap, WrapType.NONE, true);
        Wrap valueWrap = Wrap.createWrap(WrapType.NORMAL, true);
        final Wrap[] operandWrap = {keyWrap};
        final boolean[] rightOperand = {false};

        return buildChildren(
                containerAssociationOperation,
                (child, childElementType, blockList) -> {
                    if (childElementType == ACCESS_EXPRESSION) {
                        if (rightOperand[0]) {
                            blockList.addAll(buildContainerValueAccessExpressionChildren(child, operandWrap[0]));
                        } else {
                            blockList.addAll(buildAccessExpressionChildren(child, operandWrap[0]));
                        }
                    } else if (childElementType == ASSOCIATION_OPERATOR) {
                        blockList.add(buildChild(child, associationOperatorWrap));
                        operandWrap[0] = valueWrap;
                        rightOperand[0] = true;
                    } else {
                        blockList.add(buildChild(child, operandWrap[0]));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildInterpolationContainerChildren(@NotNull ASTNode container,
                                                                                   @Nullable Wrap openingWrap) {
        return buildContainerChildren(
                container,
                INTERPOLATION_START,
                openingWrap,
                null,
                null,
                INTERPOLATION_END,
                null
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildContainerChildren(@NotNull ASTNode container,
                                                                       @NotNull IElementType openingElementType,
                                                                       @Nullable Wrap openingWrap,
                                                                       @Nullable Indent elementIndent,
                                                                       @Nullable Wrap givenTailWrap,
                                                                       @NotNull IElementType closingElementType,
                                                                       @Nullable Alignment closingAlignment) {
        return buildContainerChildren(
                container,
                openingElementType,
                openingWrap,
                elementIndent,
                givenTailWrap,
                closingElementType,
                closingAlignment,
                (child, childElementType, tailWrap, childrenIndent, blockList) -> {
                    if (childElementType == ACCESS_EXPRESSION) {
                        blockList.addAll(buildAccessExpressionChildren(child, tailWrap, childrenIndent));
                    } else if (childElementType == COMMA) {
                        blockList.add(buildChild(child));
                    } else if (childElementType == END_OF_EXPRESSION) {
                        blockList.addAll(buildEndOfExpressionChildren(child, null, childrenIndent));
                    } else if (childElementType == KEYWORDS) {
                        blockList.addAll(buildKeywordsChildren(child, tailWrap));
                    } else {
                        blockList.add(buildChild(child, tailWrap, childrenIndent));
                    }

                    return blockList;
                });
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildContainerChildren(
            @NotNull ASTNode container,
            @NotNull IElementType openingElementType,
            @Nullable Wrap openingWrap,
            @Nullable Indent elementIndent,
            @Nullable Wrap givenTailWrap,
            @NotNull IElementType closingElementType,
            @Nullable Alignment closingAlignment,
            @NotNull ContainerBlockListReducer containerBlockListReducer
    ) {
        Wrap nonEmptyTailWrap;

        nonEmptyTailWrap = Objects.requireNonNullElseGet(
                givenTailWrap,
                () -> tailWrap(container, openingElementType, closingElementType)
        );

        // empty tailWrap
        final Wrap[] tailWrap = {Wrap.createWrap(WrapType.NORMAL, true)};

        return buildChildren(
                container,
                (child, childElementType, blockList) -> {
                    if (childElementType == closingElementType) {
                        blockList.add(buildChild(child, tailWrap[0], closingAlignment, Indent.getNoneIndent()));
                    } else if (childElementType == openingElementType) {
                        blockList.add(buildChild(child, openingWrap));
                    } else {
                        tailWrap[0] = nonEmptyTailWrap;

                        blockList = containerBlockListReducer.reduce(
                                child,
                                childElementType,
                                tailWrap[0],
                                elementIndent,
                                blockList
                        );
                    }

                    return blockList;
                }
        );
    }

    /* Nested list, maps, and structs will be in accessExpressions, so can't use `buildAccessExpression` or the nested
       list, maps, and structs won't be wrapped, so that nested keywordKeys don't appear on the same line */
    @NotNull
    private List<com.intellij.formatting.Block> buildContainerValueAccessExpressionChildren(
            @NotNull ASTNode containerValueAccessExpression,
            @Nullable Wrap openingWrap
    ) {
        return buildChildren(
                containerValueAccessExpression,
                (child, childElementType, blockList) -> {
                    if (childElementType == BIT_STRING) {
                        blockList.addAll(
                                // flatten, so that `>>` will see bitString's parent as direct parent
                                buildBitStringChildren(
                                        child,
                                        openingWrap,
                                        containerValueWrap(child),
                                        Indent.getNormalIndent(true),
                                        null
                                )
                        );
                    } else if (childElementType == LIST) {
                        blockList.addAll(
                                // flatten, so that `]` will see list's parent as direct parent
                                buildListChildren(
                                        child,
                                        openingWrap,
                                        listContainerValueWrap(child),
                                        Indent.getNormalIndent(true),
                                        null
                                )
                        );
                    } else if (MAP_TOKEN_SET.contains(childElementType)) {
                        // flatten, so that `}` will see map/struct's parent as direct parent
                        blockList.addAll(
                                buildMapChildren(
                                        child,
                                        openingWrap,
                                        mapContainerValueWrap(openingWrap, child),
                                        Indent.getNormalIndent(true),
                                        null
                                )
                        );
                    } else if (childElementType == TUPLE) {
                        // flatten, so that `}` will see tuple's parent as direct parent
                        blockList.addAll(
                                buildTupleChildren(
                                        child,
                                        openingWrap,
                                        containerValueWrap(child),
                                        Indent.getNormalIndent(true),
                                        null
                                )
                        );
                    } else {
                        blockList.add(buildChild(child, openingWrap));
                    }

                    return blockList;
                }
        );
    }

    /**
     * Builds doBlock DO, stab.*, and END as siblings, so they can all be indented relative to the parent unmatched call
     * from {@link #buildUnmatchedCallChildren(ASTNode, Wrap, Alignment)}
     *
     * @param doBlock doBlock that is a child of an unmatched call, but needs to be flattened for formatting
     * @return the flattened children of the doBlock: DO, stab.*, and END.
     */
    @NotNull
    private List<com.intellij.formatting.Block> buildDoBlockChildren(@NotNull ASTNode doBlock,
                                                                     @Nullable Alignment parentAlignment) {
        boolean hasBLockLists = hasAtLeastCountChildren((CompositeElement) doBlock, BLOCK_LIST, 1);

        return buildChildren(
                doBlock,
                (child, childElementType, blockList) -> {
                    if (childElementType == BLOCK_LIST) {
                        blockList.addAll(buildBlockListChildren(child, parentAlignment));
                    } else if (childElementType == END) {
                        Wrap endWrap;

                        if (hasBLockLists) {
                            endWrap = Wrap.createWrap(WrapType.ALWAYS, true);
                        } else {
                            endWrap = Wrap.createWrap(WrapType.NORMAL, true);
                        }

                        boolean indentRelativeToDirectParent =
                                codeStyleSettings(child).ALIGN_UNMATCHED_CALL_DO_BLOCKS ==
                                        CodeStyleSettings.UnmatchedCallDoBlockAlignment.CALL.value;

                        blockList.add(
                                buildChild(
                                        child,
                                        endWrap,
                                        parentAlignment,
                                        Indent.getIndent(Indent.Type.NONE, indentRelativeToDirectParent, false)
                                )
                        );
                    } else if (childElementType == END_OF_EXPRESSION) {
                        blockList.addAll(
                                buildEndOfExpressionChildren(child, null, Indent.getNormalIndent())
                        );
                    } else if (childElementType == STAB) {
                        Wrap stabBodyChildrenWrap;

                        if (child.findChildByType(STAB_BODY) != null) {
                            stabBodyChildrenWrap = Wrap.createWrap(WrapType.ALWAYS, true);
                        } else {
                            stabBodyChildrenWrap = null;
                        }

                        blockList.addAll(buildStabChildren((CompositeElement) child, stabBodyChildrenWrap));
                    } else {
                        blockList.add(buildChild(child));
                    }

                    return blockList;
                }
        );
    }

    /**
     * Builds endOfExpression.*.  Importantly, it separates out the EOLs, which are whitespace from the comments that
     * may be interlaced
     */
    @NotNull
    private List<com.intellij.formatting.Block> buildEndOfExpressionChildren(@NotNull ASTNode endOfExpression,
                                                                             @Nullable Alignment childAlignment,
                                                                             @Nullable Indent childIndent) {
        return buildChildren(
                endOfExpression,
                (child, childElementType, blockList) -> {
                    blockList.add(buildChild(child, childAlignment, childIndent));

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildHeredocChildren(CompositeElement heredoc) {
        ASTNode heredocPrefix = heredoc.findChildByType(HEREDOC_PREFIX);
        int heredocPrefixLength = 0;

        if (heredocPrefix != null) {
            heredocPrefixLength = heredocPrefix.getTextLength();
        }

        Indent indent = Indent.getNoneIndent();
        int finalHeredocPrefixLength = heredocPrefixLength;

        return buildChildren(
                heredoc,
                (child, childElementType, blockList) -> {
                    if (HEREDOC_LINE_TOKEN_SET.contains(childElementType)) {
                        blockList.addAll(buildHeredocLineChildren(child, finalHeredocPrefixLength));
                    } else if (childElementType != HEREDOC_PREFIX) {
                        /* The heredocPrefix while important for determining the significant white space in each heredoc
                           line, is normal, insignificant whitespace when shifting the lines, so it has no block */
                        blockList.add(buildChild(child, indent));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildHeredocLineChildren(ASTNode heredocLine, int heredocPrefixLength) {
        List<com.intellij.formatting.Block> blockList;

        if (heredocLine.getTextLength() == 1 && heredocLine.getText().equals("\n")) {
            // prevent insertion of prefix length spaces on blank lines
            blockList = Collections.emptyList();
        } else {
            blockList = Collections.singletonList(
                    new HeredocLineBlock(heredocLine, heredocPrefixLength, spacingBuilder)
            );
        }

        return blockList;
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildIdentifierChildren(ASTNode identifier) {
        return buildChildren(
                identifier,
                (child, childElementType, blockList) -> {
                    blockList.add(buildChild(child));

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildInterpolationChildren(@NotNull ASTNode interpolation) {
        return buildInterpolationContainerChildren(
                interpolation,
                Wrap.createWrap(WrapType.NONE, false)
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildKeywordKeyChildren(@NotNull ASTNode keywordKey) {
        return buildChildren(
                keywordKey,
                (child, childElementType, blockList) -> {
                    blockList.add(buildChild(child));

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildKeywordPairChildren(@NotNull ASTNode keywordPair) {
        Wrap keywordKeyWrap = Wrap.createWrap(WrapType.NORMAL, true);
        Wrap keywordPairColonWrap = Wrap.createChildWrap(keywordKeyWrap, WrapType.NONE, true);
        Wrap keywordValueWrap = Wrap.createWrap(WrapType.NORMAL, true);

        return buildChildren(
                keywordPair,
                (child, childElementType, blockList) -> {
                    if (childElementType == ACCESS_EXPRESSION) {
                        blockList.addAll(
                                buildContainerValueAccessExpressionChildren(
                                        child,
                                        Wrap.createWrap(WrapType.NONE, true)
                                )
                        );
                    } else if (childElementType == KEYWORD_KEY) {
                        blockList.add(buildChild(child, keywordKeyWrap, (Alignment) null));
                    } else if (childElementType == KEYWORD_PAIR_COLON) {
                        blockList.add(buildChild(child, keywordPairColonWrap));
                    } else {
                        blockList.add(buildChild(child, keywordValueWrap, null, (Alignment) null));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildKeywordsChildren(@NotNull ASTNode keywords,
                                                                      @NotNull Wrap keywordPairWrap) {

        return buildChildren(
                keywords,
                (child, childElementType, blockList) -> {
                    if (childElementType == KEYWORD_PAIR) {
                        blockList.add(buildChild(child, keywordPairWrap, Indent.getNormalIndent()));
                    } else {
                        // commas and comments
                        blockList.add(buildChild(child));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildLineChildren(@NotNull ASTNode line) {
        // Everything on a line contains significant white space, so no automatic newlines should be inserted
        Wrap none = Wrap.createWrap(WrapType.NONE, true);

        return buildChildren(
                line,
                (child, childElementType, blockList) -> {
                    if (BODY_TOKEN_SET.contains(childElementType)) {
                        /* Flatten body because its children represent textual elements that can't be aligned without
                           changing their meaning, so there's no need for a formatting block for them */
                        blockList.addAll(buildBodyChildren(child, none));
                    } else if (childElementType == SIGIL_MODIFIERS) {
                        blockList.addAll(buildSigilModifiersChildren(child, none));
                    } else {
                        blockList.add(buildChild(child, none));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildListChildren(@NotNull ASTNode list,
                                                                  @Nullable Wrap openingBracketWrap,
                                                                  @Nullable Wrap listArgumentsWrap,
                                                                  @Nullable Indent elementIndent,
                                                                  @Nullable Alignment closingBracketAlignment) {
        return buildContainerChildren(
                list,
                OPENING_BRACKET,
                openingBracketWrap,
                elementIndent,
                listArgumentsWrap,
                CLOSING_BRACKET,
                closingBracketAlignment
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildMapArgumentsChildren(@NotNull ASTNode mapArguments,
                                                                          @Nullable Wrap openingCurlyWrap,
                                                                          @Nullable Indent elementIndent,
                                                                          @Nullable Wrap mapArgumentsWrap,
                                                                          @Nullable Alignment mapArgumentsAlignment) {
        return buildContainerChildren(
                mapArguments,
                OPENING_CURLY,
                openingCurlyWrap,
                elementIndent,
                mapArgumentsWrap,
                CLOSING_CURLY,
                mapArgumentsAlignment,
                (child, childElementType, tailWrap, childrenIndent, blockList) -> {
                    if (childElementType == MAP_CONSTRUCTION_ARGUMENTS) {
                        blockList.addAll(buildMapConstructArgumentsChildren(child, tailWrap));
                    } else if (childElementType == MAP_UPDATE_ARGUMENTS) {
                        blockList.addAll(buildMapUpdateArgumentsChildren(child, tailWrap));
                    } else {
                        blockList.add(buildChild(child, tailWrap, Indent.getNormalIndent()));
                    }

                    return blockList;
                });
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildMapChildren(@NotNull ASTNode map,
                                                                 @Nullable Wrap openingCurlyWrap,
                                                                 @Nullable Wrap mapArgumentsWrap,
                                                                 @Nullable Indent mapArgumentsIndent,
                                                                 @Nullable Alignment mapArgumentsAlignment) {
        return buildChildren(
                map,
                (child, childElementType, blockList) -> {
                    if (childElementType == MAP_ARGUMENTS) {
                        blockList.addAll(
                                buildMapArgumentsChildren(
                                        child,
                                        openingCurlyWrap,
                                        mapArgumentsIndent,
                                        mapArgumentsWrap,
                                        mapArgumentsAlignment
                                )
                        );
                    } else if (childElementType == MAP_PREFIX_OPERATOR) {
                        blockList.addAll(buildOperatorRuleChildren(child));
                    } else {
                        blockList.add(buildChild(child));
                    }

                    return blockList;
                }
        );
    }

    /**
     * @param mapArgumentsTailWrap {@link Wrap} shared between the mapConstructionArguments and
     *                             {@link ElixirTypes#CLOSING_CURLY}.
     */
    @NotNull
    private List<com.intellij.formatting.Block> buildMapConstructArgumentsChildren(
            @NotNull ASTNode mapConstructionArguments,
            @NotNull Wrap mapArgumentsTailWrap
    ) {
        return buildChildren(
                mapConstructionArguments,
                (child, childElementType, blockList) -> {
                    blockList.addAll(
                            buildMapTailArgumentsChildChildren(child, childElementType, mapArgumentsTailWrap)
                    );

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildMapTailArgumentsChildChildren(
            @NotNull ASTNode child,
            @NotNull IElementType childElementType,
            @NotNull Wrap mapArgumentsTailWrap
    ) {
        List<com.intellij.formatting.Block> blockList = new ArrayList<>();

        if (childElementType == ASSOCIATIONS_BASE) {
            blockList.addAll(buildAssociationsBaseChildren(child, mapArgumentsTailWrap));
        } else if (childElementType == ASSOCIATIONS) {
            blockList.addAll(buildAssociationsChildren(child, mapArgumentsTailWrap));
        } else if (childElementType == KEYWORDS) {
            blockList.addAll(buildKeywordsChildren(child, mapArgumentsTailWrap));
        } else {
            blockList.add(buildChild(child, (Indent) null));
        }

        return blockList;
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildMapUpdateArgumentsChildren(
            @NotNull ASTNode mapUpdateArguments,
            @NotNull Wrap operandWrap) {
        final boolean[] leftOperand = {true};

        return buildChildren(
                mapUpdateArguments,
                (child, childElementType, blockList) -> {
                    if (childElementType == COMMA) {
                        blockList.add(buildChild(child));
                    } else if (childElementType == PIPE_INFIX_OPERATOR) {
                        blockList.addAll(buildOperatorRuleChildren(child));
                        leftOperand[0] = false;
                    } else {
                        if (leftOperand[0]) {
                            if (childElementType == ACCESS_EXPRESSION) {
                                blockList.addAll(buildAccessExpressionChildren(child, operandWrap));
                            } else {
                                blockList.add(buildChild(child, operandWrap));
                            }
                        } else {
                            blockList.addAll(
                                    buildMapTailArgumentsChildChildren(
                                            child,
                                            childElementType,
                                            operandWrap
                                    )
                            );
                        }
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildMatchedCallChildren(@NotNull ASTNode matchedCall,
                                                                         @Nullable Wrap parentWrap,
                                                                         @Nullable Alignment parentAlignment) {
        return buildChildren(
                matchedCall,
                (child, childElementType, blockList) -> {
                    blockList.addAll(
                            buildCallChildChildren(
                                    child,
                                    childElementType,
                                    parentWrap,
                                    parentAlignment
                            )
                    );

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildMatchedParenthesesArguments(
            @NotNull ASTNode matchedParenthesesArguments,
            @Nullable Wrap parentWrap,
            @Nullable Alignment parentAlignment) {
        return buildChildren(
                matchedParenthesesArguments,
                (child, childElementType, blockList) -> {
                    if (childElementType == PARENTHESES_ARGUMENTS) {
                        blockList.addAll(buildParenthesesArgumentsChildren(child, parentWrap, parentAlignment));
                    } else {
                        blockList.add(buildChild(child));
                    }

                    return blockList;
                }
        );
    }

    /**
     * The whole point of adding multiple aliases to Elixir was to allow for compact `import`s and `aliases`, so unlike
     * actual tuples, where `WrapType.ALWAYS` makes sense for multiline tuples, for multi-line multiple aliases, we
     * want the most compact arrangement that still preserves alignment inside the `{` `}` and wrapping when the line is
     * too long.
     */
    @NotNull
    private List<com.intellij.formatting.Block> buildMultipleAliasesChildren(@NotNull ASTNode multipleAliases) {
        // Align all aliases so when they wrap they line up inside the `{` instead of with the `{`.
        Alignment aliasAlignment = Alignment.createAlignment();
        // The same Wrap can be used for multiple comma-separated values in the example code for `Wrap.createWrap` ...
        Wrap aliasWrap = Wrap.createWrap(
                WrapType.NORMAL,
                /* ... that code say that `wrapFirstElement` should be `false` if only those elements past margin should
                   be wrapped. */
                true
        );
        Wrap commaWrap = Wrap.createChildWrap(aliasWrap, WrapType.NONE, true);

        return buildChildren(
                multipleAliases,
                (child, childElementType, blockList) -> {
                    if (childElementType == CLOSING_CURLY) {
                        blockList.add(buildChild(child));
                    } else if (childElementType == OPENING_CURLY) {
                        blockList.add(buildChild(child, Wrap.createWrap(WrapType.NONE, false)));
                    } else if (childElementType == ACCESS_EXPRESSION) {
                        blockList.addAll(buildAccessExpressionChildren(child, aliasWrap, aliasAlignment));
                    } else if (childElementType == COMMA) {
                        blockList.add(buildChild(child, commaWrap));
                    } else {
                        blockList.add(buildChild(child, aliasWrap, aliasAlignment));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildNoParenthesesKeywordPairChildren(
            @NotNull ASTNode noParenthesesKeywordPair
    ) {
        Wrap keywordKeyWrap = Wrap.createWrap(WrapType.CHOP_DOWN_IF_LONG, true);
        Indent keywordKeyIndent = Indent.getIndent(Indent.Type.NONE, true, false);
        Wrap keywordPairColonWrap = Wrap.createChildWrap(keywordKeyWrap, WrapType.NONE, true);
        Wrap keywordValueWrap = Wrap.createChildWrap(keywordKeyWrap, WrapType.NONE, true);

        return buildChildren(
                noParenthesesKeywordPair,
                (child, childElementType, blockList) -> {
                    if (childElementType == ACCESS_EXPRESSION) {
                        blockList.addAll(buildContainerValueAccessExpressionChildren(child, keywordValueWrap));
                    } else if (childElementType == KEYWORD_KEY) {
                        blockList.add(buildChild(child, keywordKeyWrap, keywordKeyIndent));
                    } else if (childElementType == KEYWORD_PAIR_COLON) {
                        blockList.add(buildChild(child, keywordPairColonWrap));
                    } else {
                        blockList.add(buildChild(child, keywordValueWrap));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildNoParenthesesKeywordsChildren(ASTNode noParenthesesKeywords) {
        return buildChildren(
                noParenthesesKeywords,
                (child, childElementType, blockList) -> {
                    if (childElementType == NO_PARENTHESES_KEYWORD_PAIR) {
                        blockList.add(buildChild(child, Indent.getIndent(Indent.Type.NONE, true, false)));
                    } else {
                        // commas and comments
                        blockList.add(buildChild(child));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildNoParenthesesOneArgument(
            @NotNull ASTNode child,
            @Nullable Wrap parentWrap,
            @Nullable Alignment parentAlignment
    ) {
        List<com.intellij.formatting.Block> blockList = buildChildren(
                child,
                parentWrap,
                parentAlignment,
                noParenthesesOneArgumentChildrenWrap(child),
                null
        );

        if (blockList.size() == 1) {
            Block block = (Block) blockList.getFirst();
            ASTNode blockNode = block.myNode;

            if (UNINDENTED_ONLY_ARGUMENT_TOKEN_SET.contains(blockNode.getElementType())) {
                blockList = Collections.singletonList(
                    /* Clear alignment, so that it allows anonymous functions and heredocs to align with call when they
                       are the only argument.  The Alignment.createChildAlignment does this when there is more than one
                       argument.  I don't know why it doesn't work with only 1 argument. */
                        new Block(
                                blockNode,
                                block.myWrap,
                                null,
                                block.spacingBuilder,
                                block.indent,
                                block.childrenWrap,
                                block.childrenAlignment
                        )
                );
            }
        }

        return blockList;
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildOperationChildren(ASTNode operation) {
        return buildChildren(
                operation,
                (child, childElementType, blockList) -> {
                    /* Move the operator token ASTNode up, out of the operator rule ASTNode as the operator rule ASTNode
                       is only there to consume EOLs around the operator token ASTNode and EOLs will ignored */
                    if (childElementType == ACCESS_EXPRESSION) {
                        blockList.addAll(
                                buildAccessExpressionChildren(child)
                        );
                    } else if (OPERATOR_RULE_TOKEN_SET.contains(childElementType)) {
                        blockList.addAll(buildOperatorRuleChildren(child));
                    } else {
                        blockList.add(buildChild(child));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildOperatorRuleChildren(@NotNull ASTNode operatorRule) {
        return buildOperatorRuleChildren(operatorRule, null, null, null);
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildOperatorRuleChildren(@NotNull ASTNode operatorRule,
                                                                          @Nullable Wrap operatorWrap) {
        return buildOperatorRuleChildren(operatorRule, operatorWrap, null, null);
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildOperatorRuleChildren(@NotNull ASTNode operatorRule,
                                                                          @Nullable Wrap operatorWrap,
                                                                          @Nullable Alignment operatorAlignment) {
        return buildOperatorRuleChildren(operatorRule, operatorWrap, operatorAlignment, null);
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildOperatorRuleChildren(
            @NotNull ASTNode operatorRule,
            @Nullable Wrap operatorWrap,
            @Nullable Alignment operatorAlignment,
            @Nullable Alignment rightCommentAlignment
    ) {
        final Alignment[] commentAlignment = {operatorAlignment};
        Indent operatorIndent = Indent.getNoneIndent();
        final Indent[] commentIndent = {operatorIndent};


        return buildChildren(
                operatorRule,
                (child, childElementType, blockList) -> {
                    if (OPERATOR_TOKEN_SET.contains(childElementType)) {
                        blockList.add(buildChild(child, operatorWrap, operatorAlignment, operatorIndent));
                        commentAlignment[0] = rightCommentAlignment;
                        commentIndent[0] = null;
                    } else {
                        blockList.add(buildChild(child, commentAlignment[0], commentIndent[0]));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildParenthesesArgumentsChildren(
            @NotNull ASTNode parenthesesArguments,
            @Nullable Wrap parentWrap,
            @Nullable Alignment parentAlignment) {
        Wrap tailWrap = tailWrap(
                parenthesesArguments,
                OPENING_PARENTHESIS,
                CLOSING_PARENTHESIS
        );

        return buildChildren(
                parenthesesArguments,
                (child, childElementType, blockList) -> {
                    if (childElementType == ACCESS_EXPRESSION) {
                        // arguments
                        blockList.addAll(buildAccessExpressionChildren(child, tailWrap, Indent.getNormalIndent()));
                    } else if (childElementType == CLOSING_PARENTHESIS) {
                        blockList.add(buildChild(child, tailWrap, parentAlignment, Indent.getNoneIndent()));
                    } else if (childElementType == COMMA) {
                        blockList.add(buildChild(child));
                    } else if (childElementType == KEYWORDS) {
                        blockList.addAll(buildKeywordsChildren(child, tailWrap));
                    } else if (childElementType == OPENING_PARENTHESIS) {
                        blockList.add(
                                buildChild(child, Wrap.createChildWrap(parentWrap, WrapType.CHOP_DOWN_IF_LONG, true))
                        );
                    } else {
                        // arguments that aren't keywords.
                        blockList.add(buildChild(child, tailWrap, Indent.getNormalIndent()));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildParentheticalStabChildren(@NotNull ASTNode parentheticalStab) {
        Indent childIndent = Indent.getNormalIndent();
        Wrap stabBodyChildrenWrap;

        if (parentheticalStab.textContains('\n')) {
            stabBodyChildrenWrap = null;
        } else {
            stabBodyChildrenWrap = Wrap.createWrap(WrapType.CHOP_DOWN_IF_LONG, true);
        }

        return buildChildren(
                parentheticalStab,
                (child, childElementType, blockList) -> {
                    if (childElementType == SEMICOLON) {
                        blockList.add(
                                buildChild(child, Wrap.createWrap(WrapType.NONE, true), childIndent)
                        );
                    } else if (childElementType == STAB) {
                        blockList.addAll(buildStabChildren((CompositeElement) child, stabBodyChildrenWrap));
                    } else {
                        blockList.add(buildChild(child));
                    }

                    return blockList;
                }
        );
    }

    /**
     * {@code a | b | c} parses as nested pipe operations, so each level would otherwise create its own
     * {@link Alignment} and anchor the operands one step further right than the level above. Only an alignment
     * handed down by an enclosing pipe operation may be reused; any other is a container's and would drag the
     * operands onto something unrelated.
     */
    @Nullable
    private Alignment enclosingPipeOperandAlignment(@NotNull ASTNode pipeOperation, @Nullable Alignment alignment) {
        ASTNode parent = pipeOperation.getTreeParent();

        return parent != null && PIPE_OPERATION_TOKEN_SET.contains(parent.getElementType()) ? alignment : null;
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildPipeOperationChildren(@NotNull ASTNode pipeOperation,
                                                                           @Nullable Alignment operandAlignment) {
        //noinspection ConstantConditions
        return buildAlignedOperandsOperationChildren(
                pipeOperation,
                (codeStyleSettings) -> codeStyleSettings.ALIGN_PIPE_OPERANDS,
                TokenSet.create(PIPE_INFIX_OPERATOR),
                operandAlignment
        );
    }

    /**
     * sigilModifiers must be flattened because they may be empty and empty blocks SHOULD NOT be created a it messes up
     * the spacing
     */
    @NotNull
    private List<com.intellij.formatting.Block> buildSigilModifiersChildren(@NotNull ASTNode sigilModifiers,
                                                                            @NotNull Wrap childrenWrap) {
        return buildChildren(
                sigilModifiers,
                (child, childElementType, blockList) -> {
                    blockList.add(buildChild(child, childrenWrap));

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildSometimesBooleanOperationChildren(
            @NotNull ASTNode sometimesBooleanOperation
    ) {
        return buildAlignedOperandsOperationChildren(
                sometimesBooleanOperation,
                (codeStyleSettings) -> {
                    ASTNode operatorRule = sometimesBooleanOperation.findChildByType(
                            SOMETIMES_BOOLEAN_OPERATOR_RULE_TOKEN_SET
                    );
                    boolean alignOperands = false;

                    if (operatorRule != null) {
                        ASTNode wordOperator = operatorRule.findChildByType(BOOLEAN_WORD_OPERATOR_TOKEN_SET);

                        if (wordOperator != null && codeStyleSettings != null) {
                            alignOperands = codeStyleSettings.ALIGN_BOOLEAN_OPERANDS;
                        }
                    }

                    return alignOperands;
                },
                SOMETIMES_BOOLEAN_OPERATOR_RULE_TOKEN_SET
        );
    }

    /**
     * Builds stab.stabBody.*
     *
     * @param stabBody       a child of a `stab` in `doBlock`
     * @param childWrap      wrap for all child blocks in `stabBody`
     * @param childAlignment alignment to use for all child blocks of `stabBody`
     * @param childIndent    indent to use for all child blocks of the `stabBody`
     * @return children of stabBody that should be aligned together with the same indent
     */
    @NotNull
    private List<com.intellij.formatting.Block> buildStabBodyChildren(@NotNull ASTNode stabBody,
                                                                      @NotNull Wrap childWrap,
                                                                      @NotNull Alignment childAlignment,
                                                                      @Nullable Indent childIndent) {

        return buildChildren(
                stabBody,
                (child, childElementType, blockList) -> {
                    if (childElementType == ACCESS_EXPRESSION) {
                        blockList.addAll(buildAccessExpressionChildren(child, childWrap, childAlignment, childIndent));
                    } else if (childElementType == COMMENT) {
                        // COMMENTs don't use childWrap as they can either be at end-of-line or on their own line
                        blockList.add(buildChild(child, childAlignment, childIndent));
                    } else if (childElementType == END_OF_EXPRESSION) {
                        blockList.addAll(buildEndOfExpressionChildren(child, childAlignment, childIndent));
                    } else {
                        blockList.add(buildChild(child, childWrap, childAlignment, childIndent));
                    }

                    return blockList;
                }
        );
    }

    /**
     * Builds stab.*
     *
     * @param stab a child of a `doBlock`
     * @return children of stab that should be aligned together and indented normal relative to the call with the
     * doBlock.
     */
    @NotNull
    private List<com.intellij.formatting.Block> buildStabChildren(@NotNull CompositeElement stab,
                                                                  @Nullable Wrap stabBodyChildrenWrap) {
        /* all children share the same alignment as expressions inside a doBlock above the stab are assumed to be
           aligned on the left-side */
        Alignment childAlignment = Alignment.createAlignment();

        Wrap stabOperationWrap = stabOperationWrap(stab);
        Indent childrenIndent = stabChildrenIndent(stab);

        @NotNull Wrap nonNullStabBodyChildrenWrap;

        if (stabBodyChildrenWrap == null) {
            nonNullStabBodyChildrenWrap = Wrap.createChildWrap(stabOperationWrap, WrapType.CHOP_DOWN_IF_LONG, true);
        } else {
            nonNullStabBodyChildrenWrap = stabBodyChildrenWrap;
        }

        return buildChildren(
                stab,
                (child, childElementType, blockList) -> {
                    if (childElementType == END_OF_EXPRESSION) {
                        blockList.addAll(buildEndOfExpressionChildren(child, childAlignment, childrenIndent));
                    } else if (childElementType == STAB_BODY) {
                        blockList.addAll(
                                buildStabBodyChildren(
                                        child,
                                        nonNullStabBodyChildrenWrap,
                                        childAlignment,
                                        childrenIndent
                                )
                        );
                    } else {
                        blockList.add(
                                buildChild(
                                        child,
                                        stabOperationWrap,
                                        childrenIndent,
                                        nonNullStabBodyChildrenWrap
                                )
                        );
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildStabOperationChildren(
            @NotNull ASTNode stabOperation,
            @NotNull Wrap stabBodyChildrenWrap
    ) {
        return buildChildren(
                stabOperation,
                (child, childElementType, blockList) -> {
                    if (childElementType == STAB_BODY) {
                        Indent childrenIndent;

                        // `stabBody` is on line below `->`
                        if (firstOnLine(child)) {
                            ASTNode stab = stabOperation.getTreeParent();

                            // `stabOperation` is on line below `fn`, `blockIdentifier`, `(`, or `do`
                            if (firstOnLine(stabOperation)) {
                                ASTNode stabParent = stab.getTreeParent();
                                IElementType stabParentElementType = stabParent.getElementType();

                                childrenIndent = Indent.getNormalIndent(
                                        stabParentElementType == DO_BLOCK &&
                                                codeStyleSettings(stabParent).ALIGN_UNMATCHED_CALL_DO_BLOCKS ==
                                                        CodeStyleSettings.UnmatchedCallDoBlockAlignment.CALL.value
                                );
                            } else {
                                // `stab` is on line below `fn`, `blockIdentifier`, `(`, or `do`
                                if (firstOnLine(stab)) {
                                    childrenIndent = Indent.getSpaceIndent(normalIndentSize(stab), true);
                                } else {
                                    ASTNode stabParent = stab.getTreeParent();

                                    // `fn`, `blockIdentifier`, `(`, or `do` is start of line
                                    if (firstOnLine(stabParent)) {
                                        childrenIndent = null;
                                    } else {
                                        IElementType stabParentElementType = stabParent.getElementType();

                                        if (stabParentElementType == ANONYMOUS_FUNCTION) {
                                            if (lastArgument(stabParent)) {
                                                /* handles

                                                   ```
                                                   one fn ->
                                                     two
                                                   end
                                                   ```

                                                   and

                                                   ```
                                                   one two, fn ->
                                                     three
                                                   end
                                                   ```
                                                  */
                                                childrenIndent = Indent.getNormalIndent();
                                            } else {
                                                /* Indent needs to adjusted to look like a normal indent relative to
                                                   start of `fn` */
                                                int stabParentStartOffset = stabParent.getStartOffset();
                                                int directParentStartOffset = stabOperation.getStartOffset();
                                                int spaces = normalIndentSize(stabParent) -
                                                        (directParentStartOffset - stabParentStartOffset);

                                                if (spaces < 0) {
                                                    spaces = 0;
                                                }

                                                childrenIndent = Indent.getSpaceIndent(spaces, true);
                                            }
                                        } else {
                                            childrenIndent = null;
                                        }
                                    }
                                }
                            }
                        } else {
                            childrenIndent = null;
                        }

                        blockList.addAll(
                                buildStabBodyChildren(
                                        child,
                                        stabBodyChildrenWrap,
                                        Alignment.createAlignment(),
                                        childrenIndent
                                )
                        );
                    } else if (childElementType == STAB_INFIX_OPERATOR) {
                        blockList.addAll(buildOperatorRuleChildren(child));
                    } else {
                        blockList.add(
                                buildChild(child)
                        );
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildTupleChildren(@NotNull ASTNode tuple,
                                                                   @Nullable Wrap openingCurlyWrap,
                                                                   @Nullable Wrap containerArgumentsWrap,
                                                                   @Nullable Indent elementIndent,
                                                                   @Nullable Alignment closingCurlyAlignment) {
        return buildContainerChildren(
                tuple,
                OPENING_CURLY,
                openingCurlyWrap,
                elementIndent,
                containerArgumentsWrap,
                CLOSING_CURLY,
                closingCurlyAlignment
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildTwoOperationChildren(@NotNull ASTNode twoOperation) {
        //noinspection ConstantConditions
        return buildAlignedOperandsOperationChildren(
                twoOperation,
                (codeStyleSettings) -> codeStyleSettings.ALIGN_TWO_OPERANDS,
                TokenSet.create(TWO_INFIX_OPERATOR)
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildTypeOperationChildren(@NotNull ASTNode typeOperation) {
        final Alignment[] operandAlignment = {null};

        return buildChildren(
                typeOperation,
                (child, childElementType, blockList) -> {
                    if (childElementType == ACCESS_EXPRESSION) {
                        blockList.addAll(buildAccessExpressionChildren(child, operandAlignment[0]));
                    } else if (childElementType == TYPE_INFIX_OPERATOR) {
                        blockList.addAll(buildOperatorRuleChildren(child));

                        if (codeStyleSettings(typeOperation).ALIGN_TYPE_DEFINITION_TO_RIGHT_OF_OPERATOR) {
                            operandAlignment[0] = Alignment.createAlignment();
                        }
                    } else {
                        blockList.add(buildChild(child, operandAlignment[0]));
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildUnmatchedCallChildren(@NotNull ASTNode parentNode,
                                                                           @Nullable Wrap parentWrap,
                                                                           @Nullable Alignment parentAlignment) {
        return buildChildren(
                parentNode,
                (child, childElementType, blockList) -> {
                    /* the elements in the doBlock.stab must be direct children of the call, so that they can be
                       indented relative to parent */
                    if (childElementType == DO_BLOCK) {
                        blockList.addAll(buildDoBlockChildren(child, parentAlignment));
                    } else {
                        blockList.addAll(
                                buildCallChildChildren(child, childElementType, parentWrap, parentAlignment)
                        );
                    }

                    return blockList;
                }
        );
    }

    @NotNull
    private List<com.intellij.formatting.Block> buildWhenOperationChildren(@NotNull ASTNode whenOperation) {
        Wrap operatorWrap = Wrap.createWrap(WrapType.NORMAL, true);

        final Alignment[] operandAlignment = {null};
        final Wrap[] operandWrap = {Wrap.createWrap(WrapType.NONE, false)};

        return buildChildren(
                whenOperation,
                (child, childElementType, blockList) -> {
                    if (childElementType == ACCESS_EXPRESSION) {
                        blockList.addAll(
                                buildAccessExpressionChildren(child, operandWrap[0], operandAlignment[0])
                        );
                    } else if (OPERATOR_RULE_TOKEN_SET.contains(childElementType)) {
                        blockList.addAll(buildOperatorRuleChildren(child, operatorWrap));
                        operandAlignment[0] = Alignment.createAlignment();
                        operandWrap[0] = Wrap.createChildWrap(operatorWrap, WrapType.NORMAL, true);
                    } else {
                        blockList.add(buildChild(child, operandWrap[0], operandAlignment[0]));
                    }

                    return blockList;
                }
        );
    }

    private CodeStyleSettings codeStyleSettings(@NotNull ASTNode node) {
        return CodeStyle.getCustomSettings(node.getPsi().getContainingFile(), CodeStyleSettings.class);
    }

    private CommonCodeStyleSettings commonCodeStyleSettings(@NotNull ASTNode node) {
        return CodeStyle.getLanguageSettings(node.getPsi().getContainingFile(), ElixirLanguage.INSTANCE);
    }

    @NotNull
    private Wrap containerValueWrap(@NotNull ASTNode container) {
        return Wrap.createWrap(containerValueWrapType(container), true);
    }

    @NotNull
    private WrapType containerValueWrapType(@NotNull ASTNode container) {
        WrapType wrapType;

        if (container.findChildByType(KEYWORDS) != null && notOneLinerUnmatchedCallBody(container)) {
            wrapType = WrapType.ALWAYS;
        } else {
            wrapType = WrapType.CHOP_DOWN_IF_LONG;
        }

        return wrapType;
    }

    private boolean firstOnLine(@NotNull ASTNode node) {
        Document document = document(node.getPsi());

        assert document != null;

        int nodeStartOffset = node.getStartOffset();
        int lineNumber = document.getLineNumber(nodeStartOffset);
        int lineStartOffset = document.getLineStartOffset(lineNumber);
        String prefix = document.getText(new TextRange(lineStartOffset, nodeStartOffset));

        return isWhitespace(prefix);
    }

    /**
     * @return {@code null} means to use the default indentation for the default formatter.  Any instance means use the
     * specified indent.
     */
    @Nullable
    @Override
    public Indent getIndent() {
        return indent;
    }

    @NotNull
    @Override
    public ChildAttributes getChildAttributes(int newChildIndex) {
        ChildAttributes childAttributes;
        IElementType elementType = myNode.getElementType();

        if (newChildIndex > 0) {
            Indent afterDelimiterIndent = indentAfterDelimiter(newChildIndex);

            childAttributes = afterDelimiterIndent != null ?
                    new ChildAttributes(afterDelimiterIndent, null) :
                    DELEGATE_TO_PREV_CHILD;
        } else if (elementType == DO) {
            boolean indentRelativeToDirectParent =
                    codeStyleSettings(myNode).ALIGN_UNMATCHED_CALL_DO_BLOCKS ==
                            CodeStyleSettings.UnmatchedCallDoBlockAlignment.CALL.value;
            Indent indent = Indent.getNormalIndent(indentRelativeToDirectParent);
            childAttributes = new ChildAttributes(
                    indent,
                    /* all children share the same alignment as expressions inside a doBlock above the stab are assumed
                       to be aligned on the left-side */
                    Alignment.createAlignment()
            );
        } else {
           childAttributes = super.getChildAttributes(newChildIndex);
        }

        return childAttributes;
    }

    /**
     * The indent to put the caret at when it follows one of the tokens that punctuate a container - the delimiter it
     * opens with, a comma between its elements, {@code ->} or a block identifier. All of these are built without an
     * indent of their own, because they sit beside the element they punctuate rather than starting a line, so
     * delegating to one indents against nothing and the caret falls back to the enclosing statement.
     *
     * <p>The delimiter is recognised by its own ancestry rather than by this block's element type, because a
     * container's children are flattened into the enclosing block's sub-blocks: the block being asked after a comma
     * in an argument list is the call, not the argument list.
     *
     * <p>Every column this produces is the one {@code mix format} puts an element at for the same construct.
     *
     * @return the indent to answer the delegation with, or {@code null} to keep delegating. A comma in a call written
     * without parentheses keeps delegating: {@code mix format} aligns what follows it under the first argument in
     * {@code with a <- one(),} but indents it in {@code import Foo,}, and which of the two applies is decided by the
     * argument that has not been typed yet.
     */
    @Nullable
    private Indent indentAfterDelimiter(int newChildIndex) {
        List<com.intellij.formatting.Block> subBlocks = getSubBlocks();

        if (newChildIndex > subBlocks.size()) {
            return null;
        }

        com.intellij.formatting.Block previousChild = subBlocks.get(newChildIndex - 1);

        if (!(previousChild instanceof Block)) {
            return null;
        }

        ASTNode previousNode = ((Block) previousChild).myNode;
        ASTNode container = previousNode.getTreeParent();

        if (container == null) {
            return null;
        }

        // a block identifier is a rule rather than a token, so its keyword is the leaf the caret follows
        boolean afterDelimiter = DELIMITER_TOKEN_SET.contains(previousNode.getElementType()) ||
                container.getElementType() == BLOCK_IDENTIFIER;

        if (!afterDelimiter) {
            return null;
        }

        while (DELIMITER_GROUPING_TOKEN_SET.contains(container.getElementType())) {
            container = container.getTreeParent();

            if (container == null) {
                return null;
            }
        }

        return indentInContainer(container);
    }

    /**
     * The indent an element of {@code container} written on its own line takes.
     *
     * @return {@code null} when the container is not one whose elements indent, such as the argument list of a call
     * written without parentheses.
     */
    @Nullable
    private Indent indentInContainer(@NotNull ASTNode container) {
        IElementType containerElementType = container.getElementType();
        Indent indent;

        if (containerElementType == PARENTHESES_ARGUMENTS) {
            /* An argument list wraps to the column of the call it belongs to plus one indent, which is where the
               answering block starts - except in a dot call, where the argument list is the answering block and
               already starts at the parenthesis. */
            indent = myNode.getElementType() == PARENTHESES_ARGUMENTS ?
                    Indent.getNormalIndent() :
                    Indent.getNormalIndent(true);
        } else if (containerElementType == MAP_UPDATE_ARGUMENTS) {
            indent = mapUpdatePairIndent(container);
        } else if (NORMAL_INDENT_CONTAINER_TOKEN_SET.contains(containerElementType)) {
            indent = Indent.getNormalIndent();
        } else {
            indent = null;
        }

        return indent;
    }

    /**
     * The indent a map update's pairs take: one indent in from the pipe, wherever the pipe sits.
     *
     * <p>That is two indents in from the line the map starts on while the map starts a line of its own, but not when
     * it is written as the value of an enclosing update, because it then begins on that update's continuation line and
     * its pipe is further right than the line's own indent. Anchoring to the pipe covers both, so the columns follow
     * {@code mix format} at any nesting depth.
     *
     * <p>A space indent is resolved against the indent of the line the answering block starts on, so the pipe's column
     * has to be re-expressed as a distance from there. It cannot be anchored to the block itself: a map update's block
     * starts at the <code>%{</code>, which is to the right of its own pipe, and an indent can never place the caret left of
     * the block it is relative to.
     *
     * @return {@code null} when the pipe or either column cannot be read, so that the caret keeps delegating rather
     * than landing on a column that was guessed.
     */
    @Nullable
    private Indent mapUpdatePairIndent(@NotNull ASTNode mapUpdateArguments) {
        ASTNode pipe = mapUpdateArguments.findChildByType(PIPE_INFIX_OPERATOR);

        if (pipe == null) {
            return null;
        }

        Integer pipeColumn = column(pipe);
        Integer lineIndent = lineIndent(myNode);

        if (pipeColumn == null || lineIndent == null) {
            return null;
        }

        int spaces = pipeColumn + indentSize() - lineIndent;

        return spaces >= 0 ? Indent.getSpaceIndent(spaces) : null;
    }

    /** The column {@code node} starts at, or {@code null} when there is no document to measure against. */
    @Nullable
    private Integer column(@NotNull ASTNode node) {
        Document document = document(node.getPsi());

        if (document == null) {
            return null;
        }

        int startOffset = node.getStartOffset();
        int lineStartOffset = document.getLineStartOffset(document.getLineNumber(startOffset));

        return column(document.getCharsSequence(), lineStartOffset, startOffset);
    }

    /**
     * The indent of the line {@code node} starts on - the column the whitespace before its first non-blank character
     * ends at.
     */
    @Nullable
    private Integer lineIndent(@NotNull ASTNode node) {
        Document document = document(node.getPsi());

        if (document == null) {
            return null;
        }

        int lineNumber = document.getLineNumber(node.getStartOffset());
        int lineStartOffset = document.getLineStartOffset(lineNumber);
        int lineEndOffset = document.getLineEndOffset(lineNumber);
        CharSequence text = document.getCharsSequence();
        int indentEndOffset = lineStartOffset;

        while (indentEndOffset < lineEndOffset && Character.isWhitespace(text.charAt(indentEndOffset))) {
            indentEndOffset++;
        }

        return column(text, lineStartOffset, indentEndOffset);
    }

    /**
     * The column {@code offset} sits at on the line starting at {@code lineStartOffset}.
     *
     * <p>Counting characters would do for a file indented with spaces, but {@link Indent#getSpaceIndent(int)} takes
     * columns and a tab is worth however many columns it takes to reach the next tab stop, so a file indented with
     * tabs would place the caret short of where the block it is measured against sits. This is
     * {@link com.intellij.openapi.editor.ex.util.EditorUtil#calcColumnNumber}'s arithmetic, which there is no editor
     * to ask for here.
     */
    private int column(@NotNull CharSequence text, int lineStartOffset, int offset) {
        int tabSize = Math.max(tabSize(), 1);
        int column = 0;

        for (int charOffset = lineStartOffset; charOffset < offset; charOffset++) {
            column += text.charAt(charOffset) == '\t' ? tabSize - column % tabSize : 1;
        }

        return column;
    }

    private int indentSize() {
        return indentOptions().INDENT_SIZE;
    }

    private int tabSize() {
        return indentOptions().TAB_SIZE;
    }

    @NotNull
    private CommonCodeStyleSettings.IndentOptions indentOptions() {
        return CodeStyle.getIndentOptions(myNode.getPsi().getContainingFile());
    }

    /**
     * Must be set or Elixir settings won't be used
     *
     * @return current block's language (is used to decide on what code style settings should be used for it)
     */
    @NotNull
    @Override
    public Language getLanguage() {
        return ElixirLanguage.INSTANCE;
    }

    /**
     * Returns a spacing object indicating what spaces and/or line breaks are added between two
     * specified children of this block.
     *
     * @param child1 the first child for which spacing is requested;
     *               <code>null</code> if given <code>'child2'</code> block is the first document block
     * @param child2 the second child for which spacing is requested.
     * @return the spacing instance, or null if no special spacing is required. If null is returned,
     * the formatter does not insert or delete spaces between the child blocks, but may insert
     * a line break if the line wraps at the position between the child blocks.
     * @see Spacing#createSpacing(int, int, int, boolean, int)
     * @see Spacing#getReadOnlySpacing()
     */
    @Nullable
    @Override
    public Spacing getSpacing(@Nullable com.intellij.formatting.Block child1,
                              @NotNull com.intellij.formatting.Block child2) {
        Spacing spacing = null;

        // Prevent `& &1` from becoming `&&1`, which is parsed as `&& 1` with a missing left operand
        /* Prevent `& &1 + &2` from becoming `&&1 + &2`, which is parsed as `&& 1 + &2` with missing left operand for
           `&& 1` */
        // Prevent `_ && &1` from becoming `_ &&& 1`
        // Prevent `_ &&& &2` from becoming ` _ &&&& 2`, which has no meaning
        if (child1 instanceof ASTBlock child1ASTBlock && child2 instanceof ASTBlock child2ASTBlock) {
            ASTNode child1Node = child1ASTBlock.getNode();

            if (child1Node instanceof LeafPsiElement child1LeafPsiElement) {
                // capture (`&`) or and symbol operators (`&&` or `&&&`)
                if (child1LeafPsiElement.charAt(child1LeafPsiElement.getTextLength() - 1) == '&') {
                    ASTNode firstLeafElementASTNode =
                            Objects.requireNonNull(child2ASTBlock.getNode()).findLeafElementAt(0);

                    if (firstLeafElementASTNode instanceof LeafPsiElement firstLeaf &&
                            firstLeaf.charAt(0) == '&') {
                        spacing = Spacing.createSpacing(1, 1, 0, true, 0);
                    }
                }
            }
        }

        if (spacing == null) {
            spacing = spacingBuilder.getSpacing(this, child1, child2);
        }

        return spacing;
    }

    /**
     * Returns true if the specified block may not contain child blocks. Used as an optimization
     * to avoid building the complete formatting model through calls to {@link #getSubBlocks()}.
     *
     * @return true if the block is a leaf block and may not contain child blocks, false otherwise.
     */
    @Override
    public boolean isLeaf() {
        return myNode.getFirstChildNode() == null;
    }

    private boolean lastArgument(@NotNull ASTNode node) {
        boolean lastArgument = true;
        ASTNode currentNode = node;

        while (currentNode != null) {
            IElementType elementType = currentNode.getElementType();

            if (elementType == COMMA) {
                lastArgument = false;
                break;
            } else if (elementType == NO_PARENTHESES_ONE_ARGUMENT) {
                break;
            } else {
                ASTNode nextNode = currentNode.getTreeNext();

                if (nextNode == null) {
                    currentNode = currentNode.getTreeParent();
                } else {
                    currentNode = nextNode;
                }
            }
        }

        return lastArgument;
    }

    @NotNull
    private Wrap listContainerValueWrap(@NotNull ASTNode list) {
        return Wrap.createWrap(listContainerValueWrapType(list), true);
    }

    @NotNull
    private WrapType listContainerValueWrapType(@NotNull ASTNode list) {
        WrapType wrapType = containerValueWrapType(list);

        if (wrapType == WrapType.ALWAYS) {
            ASTNode parent = list.getTreeParent();

            if (parent.getElementType() == ACCESS_EXPRESSION) {
                ASTNode grandParent = parent.getTreeParent();

                if (KEYWORD_PAIR_TOKEN_SET.contains(grandParent.getElementType())) {
                    // It is assumed that the parent of a keyword pair is keywords
                    ASTNode keywords = grandParent.getTreeParent();
                    ASTNode keywordsParent = keywords.getTreeParent();
                    IElementType keywordsParentElementType = keywordsParent.getElementType();

                    if (keywordsParentElementType == NO_PARENTHESES_ONE_ARGUMENT) {
                        wrapType = noParenthesesOneArgumentWrapType(keywordsParent);
                    }
                }
            }
        }

        return wrapType;
    }

    @NotNull
    private Wrap mapContainerValueWrap(@Nullable Wrap parentWrap, @NotNull ASTNode mapOrStructOperation) {
        return Wrap.createChildWrap(parentWrap, mapContainerValueWrapType(mapOrStructOperation), true);
    }

    @NotNull
    private WrapType mapContainerValueWrapType(ASTNode mapOrStructOperation) {
        ASTNode mapArguments = mapOrStructOperation.findChildByType(MAP_ARGUMENTS);
        WrapType wrapType = WrapType.CHOP_DOWN_IF_LONG;

        if (mapArguments != null) {
            ASTNode mapArgumentsChild = mapArguments.findChildByType(MAP_ARGUMENTS_CHILD_TOKEN_SET);

            if (mapArgumentsChild != null &&
                    mapArgumentsChild.findChildByType(MAP_TAIL_ARGUMENTS_TOKEN_SET) != null &&
                    notOneLinerUnmatchedCallBody(mapOrStructOperation)) {
                wrapType = WrapType.ALWAYS;
            }
        }

        return wrapType;
    }

    @NotNull
    private Wrap noParenthesesOneArgumentChildrenWrap(@NotNull ASTNode noParenthesesOneArgument) {
        return Wrap.createWrap(
                noParenthesesOneArgumentChildrenWrapType(noParenthesesOneArgument),
                // MUST NOT be `true` as wrapping the first argument disassociates the arguments with the call
                false
        );
    }

    @NotNull
    private WrapType noParenthesesOneArgumentChildrenWrapType(@NotNull ASTNode noParenthesesOneArgument) {
        WrapType childrenWrapType;

        ASTNode noParenthesesKeywords = noParenthesesOneArgument.findChildByType(NO_PARENTHESES_KEYWORDS);

        if (noParenthesesKeywords != null) {
            ASTNode[] noParenthesesKeywordPairs =
                    noParenthesesKeywords.getChildren(NO_PARENTHESES_KEYWORD_PAIR_TOKEN_SET);
            childrenWrapType = null;

            for (ASTNode noParenthesesKeywordPair : noParenthesesKeywordPairs) {
                if (oneLinerKeywordPair(noParenthesesKeywordPair)) {
                    childrenWrapType = WrapType.CHOP_DOWN_IF_LONG;
                    break;
                }
            }

            if (childrenWrapType == null) {
                childrenWrapType = noParenthesesOneArgumentWrapType(noParenthesesOneArgument);

                if (childrenWrapType == WrapType.ALWAYS && !noParenthesesKeywords.textContains('\n')) {
                    childrenWrapType = WrapType.CHOP_DOWN_IF_LONG;
                }
            }
        } else {
            childrenWrapType = WrapType.CHOP_DOWN_IF_LONG;
        }

        return childrenWrapType;
    }

    @NotNull
    private WrapType noParenthesesOneArgumentWrapType(@NotNull ASTNode noParenthesesOneArgument) {
        ASTNode noParenthesesOneArgumentParent = noParenthesesOneArgument.getTreeParent();
        WrapType wrapType = WrapType.ALWAYS;

        if (UNMATCHED_CALL_TOKEN_SET.contains(noParenthesesOneArgumentParent.getElementType())) {
            Call call = noParenthesesOneArgumentParent.getPsi(Call.class);

            if (IMPORT.equals(call.functionName())) {
                                /* Usage of `import` with `only` or `except` is meant for compactness, so applying
                                   keyword exclusivity would go against that */
                wrapType = WrapType.CHOP_DOWN_IF_LONG;
            }
        }

        return wrapType;
    }

    private int normalIndentSize(@NotNull ASTNode node) {
        CommonCodeStyleSettings.IndentOptions indentOptions = commonCodeStyleSettings(node).getIndentOptions();
        int normalIndentSize = 2;

        if (indentOptions != null) {
            normalIndentSize = indentOptions.INDENT_SIZE;
        }

        return normalIndentSize;
    }

    private boolean oneLinerKeywordPair(ASTNode keywordPair) {
        ASTNode keywordKey = keywordPair.findChildByType(KEYWORD_KEY);
        boolean oneLiner = false;

        if (keywordKey != null && keywordKey.getText().equals("do")) {
            ASTNode keywords = keywordPair.getTreeParent();
            ASTNode keywordsParent = keywords.getTreeParent();

            if (keywordsParent.getElementType() == NO_PARENTHESES_ONE_ARGUMENT) {
                ASTNode argumentsParent = keywordsParent.getTreeParent();

                if (UNMATCHED_CALL_TOKEN_SET.contains(argumentsParent.getElementType())) {
                    oneLiner = true;
                }
            }
        }

        return oneLiner;
    }

    private boolean notOneLinerUnmatchedCallBody(@NotNull ASTNode container) {
        ASTNode containerParent = container.getTreeParent();

        if (containerParent.getElementType() == ACCESS_EXPRESSION) {
            ASTNode accessExpressionParent = containerParent.getTreeParent();

            if (KEYWORD_PAIR_TOKEN_SET.contains(accessExpressionParent.getElementType())) {
                return !oneLinerKeywordPair(accessExpressionParent);
            }
        }

        return true;
    }

    @Nullable
    private Indent stabChildrenIndent(@NotNull ASTNode stab) {
        Indent childrenIndent;

        if (firstOnLine(stab)) {
            ASTNode stabParent = stab.getTreeParent();

            childrenIndent = Indent.getNormalIndent(
                    stabParent.getElementType() == DO_BLOCK &&
                            codeStyleSettings(stabParent).ALIGN_UNMATCHED_CALL_DO_BLOCKS ==
                                    CodeStyleSettings.UnmatchedCallDoBlockAlignment.CALL.value
            );
        } else {
            childrenIndent = null;
        }

        return childrenIndent;
    }

    @NotNull
    private Wrap stabOperationWrap(@NotNull CompositeElement stab) {
        return Wrap.createWrap(stabOperationWrapType(stab), true);
    }

    @NotNull
    private WrapType stabOperationWrapType(@NotNull CompositeElement stab) {
        WrapType stabOperationWrapType;

        if (hasAtLeastCountChildren(stab, STAB_OPERATION, 2)) {
            stabOperationWrapType = WrapType.ALWAYS;
        } else {
            stabOperationWrapType = WrapType.NORMAL;
        }

        return stabOperationWrapType;
    }

    /**
     * If already partially wrapped between {@code openingElementType} and {@code closingElementType}, then wrap all
     *
     * @return {@link WrapType#ALWAYS} if elements for {@code openingElementType} and {@code closingElementType} are on
     * different lines; otherwise, {@link WrapType#CHOP_DOWN_IF_LONG}.
     */
    @NotNull
    private Wrap tailWrap(@NotNull ASTNode parent,
                          @NotNull IElementType openingElementType,
                          @NotNull IElementType closingElementType) {
        Document document = document(parent.getPsi());
        Wrap tailWrap = null;

        if (document != null) {
            ASTNode openingElement = parent.findChildByType(openingElementType);

            if (openingElement != null) {
                ASTNode closingElement = parent.findChildByType(closingElementType);

                if (closingElement != null &&
                        document.getLineNumber(openingElement.getStartOffset()) !=
                                document.getLineNumber(closingElement.getStartOffset())) {
                    tailWrap = Wrap.createWrap(WrapType.ALWAYS, true);
                }
            }
        }

        if (tailWrap == null) {
            tailWrap = Wrap.createWrap(WrapType.CHOP_DOWN_IF_LONG, true);
        }

        return tailWrap;
    }
}
