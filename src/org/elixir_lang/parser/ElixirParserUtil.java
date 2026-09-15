package org.elixir_lang.parser;

import com.intellij.lang.PsiBuilder;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Key;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.elixir_lang.psi.ElixirTypes;
import org.elixir_lang.language_level.ElixirLanguageLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Helpers the grammar calls as external rules, {@code <<name>>}.
 * <p>
 * Must extend {@link GeneratedParserUtilBase}: GrammarKit static-imports this class into the
 * generated parser *instead of* that one, so its helpers have to stay in scope.
 */
// GrammarKit calls every external rule with the recursion level.
@SuppressWarnings("unused")
public class ElixirParserUtil extends GeneratedParserUtilBase {
    /** Set by {@code File.doParseContents}; absent for builders created by any other route. */
    public static final Key<ElixirLanguageLevel> LANGUAGE_LEVEL = Key.create("ELIXIR_PARSE_LANGUAGE_LEVEL");

    /*
     * The rules that open a nesting group, the token each starts with, and the element type its section closes with.
     * All share one generated shape - `nextTokenIs(opening)`, then `enter_section_(b)`, then
     * `exit_section_(b, m, TYPE, r)` - with no pin and no recovery, so a failure rolls the builder back to the opening.
     */
    private static final String[] GROUP_RULES =
            {"anonymousFunction", "bitString", "list", "mapArguments", "parentheticalStab", "tuple"};
    private static final IElementType[] GROUP_OPENINGS = {
            ElixirTypes.FN,
            ElixirTypes.OPENING_BIT,
            ElixirTypes.OPENING_BRACKET,
            ElixirTypes.OPENING_CURLY,
            ElixirTypes.OPENING_PARENTHESIS,
            ElixirTypes.OPENING_CURLY
    };
    private static final IElementType[] GROUP_TYPES = {
            ElixirTypes.ANONYMOUS_FUNCTION,
            ElixirTypes.BIT_STRING,
            ElixirTypes.LIST,
            ElixirTypes.MAP_ARGUMENTS,
            ElixirTypes.PARENTHETICAL_STAB,
            ElixirTypes.TUPLE
    };

    private static final Key<GroupFailures> GROUP_FAILURES = Key.create("ELIXIR_PARSE_GROUP_FAILURES");

    /**
     * GrammarKit's guard, failing at once a nesting group that has already failed at the same token, and every rule
     * below the file's statement list once the recursion limit has been reached at {@value #LIMIT_TOKENS_BEFORE_ABORT}
     * tokens.
     * <p>
     * A group's outcome depends on where it starts and on the room GrammarKit's recursion limit leaves, so a failure
     * that never reached the limit fails the same way whichever route and level reach the group, and one that did fails
     * at every deeper level. A replay leaves no expected tokens for the enclosing sections, so in broken source their
     * error recovery can skip fewer tokens than after the group's real failure.
     * <p>
     * A branch that is tried and abandoned can reach the limit at a few tokens while the accepted parse stays under it.
     * A statement nested past the limit reaches it again after each error recovery resumes, at a new token each time,
     * and on some shapes that costs seconds; the abort keeps the statements before that one, and the file's root section
     * takes the rest as one error. The count restarts only at the file's own statements, so the statements inside one
     * module count together.
     * <p>
     * Hides {@link GeneratedParserUtilBase#recursion_guard_}: the generated parser static-imports this class.
     */
    public static boolean recursion_guard_(PsiBuilder builder, int level, String funcName) {
        GroupFailures failures = builder.getUserData(GROUP_FAILURES);

        if (failures == null) {
            failures = new GroupFailures();
            builder.putUserData(GROUP_FAILURES, failures);
        }

        if (failures.statementListLevel < 0 && "expressionList".equals(funcName)) {
            failures.statementListLevel = level;
        }

        // `expressionList ::= expression (endOfExpression expression)*`: each of the file's statements starts here
        if (level <= failures.statementListLevel + 3 &&
                "expression".equals(funcName) &&
                builder.rawTokenIndex() > failures.lastLimitToken) {
            failures.limitTokens.clear();
        }

        if (failures.limitTokens.size() >= LIMIT_TOKENS_BEFORE_ABORT && level > failures.statementListLevel) {
            return false;
        }

        if (!GeneratedParserUtilBase.recursion_guard_(builder, level, funcName)) {
            int token = builder.rawTokenIndex();
            failures.limitsReached++;
            failures.limitTokens.add(token);
            failures.lastLimitToken = Math.max(failures.lastLimitToken, token);

            return false;
        }

        int group = indexOf(GROUP_RULES, funcName);

        // the rule's own `nextTokenIs` would skip the same whitespace, so the index is where its section starts
        if (group >= 0 && builder.getTokenType() == GROUP_OPENINGS[group]) {
            long key = GroupFailures.key(group, builder.rawTokenIndex());

            if (failures.failed(key, level)) {
                return false;
            }

            failures.enter(group, key, level);
        }

        return true;
    }

    /**
     * Branches that are tried and abandoned reach the recursion limit at a few tokens; source nested past it, at
     * hundreds or thousands.
     */
    static final int LIMIT_TOKENS_BEFORE_ABORT = 16;

    /** Records a nesting group's failure for {@link #recursion_guard_}; hides the base method like it. */
    public static void exit_section_(@NotNull PsiBuilder builder,
                                     @NotNull PsiBuilder.Marker marker,
                                     @Nullable IElementType elementType,
                                     boolean result) {
        GeneratedParserUtilBase.exit_section_(builder, marker, elementType, result);

        int group = indexOf(GROUP_TYPES, elementType);

        if (group >= 0) {
            GroupFailures failures = builder.getUserData(GROUP_FAILURES);

            if (failures != null) {
                failures.exit(group, result);
            }
        }
    }

    private static int indexOf(@NotNull Object[] identities, @Nullable Object object) {
        for (int index = 0; index < identities.length; index++) {
            // rule names are literals, so interned
            if (identities[index] == object) {
                return index;
            }
        }

        return -1;
    }

    /** The groups being parsed, innermost last, and where each group failed. */
    private static final class GroupFailures {
        /** The shallowest level each failure holds at: zero where the failure never reached the recursion limit. */
        private final Map<Long, Integer> failedFromLevel = new HashMap<>();
        private long[] open = new long[16];
        private int[] openGroups = new int[16];
        private int[] openLevels = new int[16];
        private int[] openLimitsReached = new int[16];
        private int openSize = 0;
        int limitsReached = 0;
        /** The tokens where the recursion limit was reached in the current statement of the file. */
        final Set<Integer> limitTokens = new HashSet<>();
        int lastLimitToken = -1;
        /** The recursion level of the file's statement list, which has to finish for the statements to be kept. */
        int statementListLevel = -1;

        static long key(int group, int start) {
            return ((long) start << 8) | group;
        }

        boolean failed(long key, int level) {
            Integer from = failedFromLevel.get(key);

            if (from == null || level < from) {
                return false;
            }

            // the replayed failure reached the limit, so the groups around it depend on their level too
            if (from > 0) {
                limitsReached++;
            }

            return true;
        }

        void enter(int group, long key, int level) {
            if (openSize == open.length) {
                open = Arrays.copyOf(open, openSize * 2);
                openGroups = Arrays.copyOf(openGroups, openSize * 2);
                openLevels = Arrays.copyOf(openLevels, openSize * 2);
                openLimitsReached = Arrays.copyOf(openLimitsReached, openSize * 2);
            }

            open[openSize] = key;
            openGroups[openSize] = group;
            openLevels[openSize] = level;
            openLimitsReached[openSize] = limitsReached;
            openSize++;
        }

        void exit(int group, boolean result) {
            if (openSize == 0 || openGroups[openSize - 1] != group) {
                // a section exited without its entry being seen; drop what cannot be matched
                openSize = 0;
                return;
            }

            long key = open[--openSize];

            if (!result) {
                int from = openLimitsReached[openSize] == limitsReached ? 0 : openLevels[openSize];
                failedFromLevel.merge(key, from, Math::min);
            }
        }
    }

    private static final TokenSet GROUP_OPENERS = TokenSet.create(
            ElixirTypes.DO,
            ElixirTypes.FN,
            ElixirTypes.INTERPOLATION_START,
            ElixirTypes.OPENING_BIT,
            ElixirTypes.OPENING_BRACKET,
            ElixirTypes.OPENING_CURLY,
            ElixirTypes.OPENING_PARENTHESIS
    );

    private static final TokenSet GROUP_CLOSERS = TokenSet.create(
            ElixirTypes.CLOSING_BIT,
            ElixirTypes.CLOSING_BRACKET,
            ElixirTypes.CLOSING_CURLY,
            ElixirTypes.CLOSING_PARENTHESIS,
            ElixirTypes.END,
            ElixirTypes.INTERPOLATION_END
    );

    /** End a {@code do} block's section as {@code end} does, without closing a group of their own. */
    private static final TokenSet BLOCK_IDENTIFIERS = TokenSet.create(
            ElixirTypes.AFTER,
            ElixirTypes.CATCH,
            ElixirTypes.ELSE,
            ElixirTypes.RESCUE
    );

    /** Words the lexer also emits for a name after {@code .}, as in {@code range.end}. */
    private static final TokenSet NAMEABLE_KEYWORDS = TokenSet.create(
            ElixirTypes.AFTER,
            ElixirTypes.CATCH,
            ElixirTypes.DO,
            ElixirTypes.ELSE,
            ElixirTypes.END,
            ElixirTypes.RESCUE
    );

    /** What {@link WordAfterNumber} rewrites, which for {@code do}, {@code end} and the block identifiers matter here. */
    private static final TokenSet REMAPPABLE = TokenSet.create(
            ElixirTypes.INVALID_BINARY_DIGITS,
            ElixirTypes.INVALID_DECIMAL_DIGITS,
            ElixirTypes.INVALID_HEXADECIMAL_DIGITS,
            ElixirTypes.INVALID_OCTAL_DIGITS
    );

    /**
     * The token at {@code steps} as the builder will see it. The remapper rewrites a token only once the builder
     * reaches it, so ahead of it {@link PsiBuilder#rawLookup} still shows invalid digits where it will show a keyword.
     * {@code File.doParseContents} installs the remapper with the {@link #LANGUAGE_LEVEL}; without one nothing is
     * remapped.
     */
    private static @Nullable IElementType remapped(@NotNull PsiBuilder builder, int steps) {
        IElementType tokenType = builder.rawLookup(steps);

        if (!REMAPPABLE.contains(tokenType)) {
            return tokenType;
        }

        ElixirLanguageLevel languageLevel = builder.getUserData(LANGUAGE_LEVEL);

        return languageLevel == null
                ? tokenType
                : new WordAfterNumber(languageLevel).filter(
                        tokenType,
                        builder.rawTokenTypeStart(steps),
                        builder.rawTokenTypeStart(steps + 1),
                        builder.getOriginalText()
                );
    }

    /**
     * Whether the {@code stab} starting here can hold a stab operation. Without the check, a {@code stab} holding no
     * {@code ->} parses its contents twice - once as a signature that then fails for want of {@code ->}, once as a
     * body - and since every parenthesised expression and {@code do} block is a {@code stab}, the doubling compounds
     * once per level of nesting.
     */
    public static boolean stabOperationAhead(@NotNull PsiBuilder builder, int level) {
        return aheadInGroup(builder, ElixirTypes.STAB_OPERATOR, STAB_OPERATOR_ANSWERS);
    }

    /**
     * Whether the arguments starting here can be more than one. Both readings of many arguments parse the first
     * argument and then need a {@code ,}, and they are tried before the reading as one argument, so without the check
     * a single argument is parsed three or more times, once more per level of nesting.
     */
    public static boolean commaAhead(@NotNull PsiBuilder builder, int level) {
        return aheadInGroup(builder, ElixirTypes.COMMA, COMMA_ANSWERS);
    }

    /**
     * Whether the heredoc line starting here has its end of line, rather than being cut off by the end of the file.
     * Without the check, the last line of an unterminated heredoc is parsed as a line, fails for want of its end, and
     * is parsed again as the unterminated last line - once more per heredoc nested in its interpolations.
     * <p>
     * A line's body holds no end of line outside its interpolations, so the first one at depth zero is the line's.
     */
    public static boolean heredocLineEndAhead(@NotNull PsiBuilder builder, int level) {
        int depth = 0;

        for (int steps = 0; ; steps++) {
            ProgressManager.checkCanceled();

            IElementType tokenType = builder.rawLookup(steps);

            if (tokenType == null) {
                return false;
            } else if (tokenType == ElixirTypes.INTERPOLATION_START) {
                depth++;
            } else if (tokenType == ElixirTypes.INTERPOLATION_END) {
                if (depth == 0) {
                    // an interpolation this line never opened: not provably cut off
                    return true;
                }

                depth--;
            } else if (depth == 0 && tokenType == ElixirTypes.EOL) {
                return true;
            }
        }
    }

    private static final Key<ScanAnswers> STAB_OPERATOR_ANSWERS = Key.create("ELIXIR_PARSE_STAB_OPERATOR_ANSWERS");
    private static final Key<ScanAnswers> COMMA_ANSWERS = Key.create("ELIXIR_PARSE_COMMA_ANSWERS");

    /**
     * Whether {@code target} appears at the group nesting the scan starts from before that nesting ends. In valid
     * source what the callers guard reads balanced groups before {@code target}, so a {@code target} it would consume
     * is reached first. Where the groups stop balancing - a closer for another opener, or the end of the file - error
     * recovery can take a {@code target} inside the groups still open as the one it needs, so that also answers yes.
     * <p>
     * A scan from any token it passes at its starting nesting would end the same way, so each of those tokens is
     * answered too. Without that, every statement of a long file of no-parentheses calls scans to the file's end.
     */
    private static boolean aheadInGroup(@NotNull PsiBuilder builder,
                                        @NotNull IElementType target,
                                        @NotNull Key<ScanAnswers> key) {
        ScanAnswers answers = builder.getUserData(key);

        if (answers == null) {
            answers = new ScanAnswers();
            builder.putUserData(key, answers);
        }

        int start = builder.rawTokenIndex();
        byte known = answers.get(start);

        if (known != ScanAnswers.UNKNOWN) {
            return known == ScanAnswers.AHEAD;
        }

        answers.clearVisited();
        boolean ahead = scan(builder, target, answers, start);
        answers.answerVisited(ahead);

        return ahead;
    }

    private static boolean scan(@NotNull PsiBuilder builder,
                                @NotNull IElementType target,
                                @NotNull ScanAnswers answers,
                                int start) {
        IElementType[] openers = new IElementType[8];
        int depth = 0;
        boolean targetInOpenGroup = false;

        for (int steps = 0; ; steps++) {
            ProgressManager.checkCanceled();

            if (depth == 0) {
                answers.visit(start + steps);
                targetInOpenGroup = false;
            }

            IElementType tokenType = remapped(builder, steps);

            if (tokenType == null) {
                return targetInOpenGroup;
            }

            // after `.` these name a field or function, as in `range.end`
            boolean delimits = !NAMEABLE_KEYWORDS.contains(tokenType) || !afterDot(builder, steps);

            if (delimits && GROUP_OPENERS.contains(tokenType)) {
                if (depth == openers.length) {
                    openers = Arrays.copyOf(openers, depth * 2);
                }

                openers[depth++] = tokenType;
            } else if (delimits && GROUP_CLOSERS.contains(tokenType)) {
                if (depth == 0) {
                    return false;
                }

                if (!closes(tokenType, openers[--depth])) {
                    return targetInOpenGroup;
                }
            } else if (delimits && depth == 0 && BLOCK_IDENTIFIERS.contains(tokenType)) {
                return false;
            } else if (tokenType == target) {
                if (depth == 0) {
                    return true;
                }

                targetInOpenGroup = true;
            }
        }
    }

    private static boolean closes(@NotNull IElementType closer, @NotNull IElementType opener) {
        return closer == ElixirTypes.END
                ? opener == ElixirTypes.DO || opener == ElixirTypes.FN
                : opener == OPENER_BY_CLOSER.get(closer);
    }

    private static final Map<IElementType, IElementType> OPENER_BY_CLOSER = Map.of(
            ElixirTypes.CLOSING_BIT, ElixirTypes.OPENING_BIT,
            ElixirTypes.CLOSING_BRACKET, ElixirTypes.OPENING_BRACKET,
            ElixirTypes.CLOSING_CURLY, ElixirTypes.OPENING_CURLY,
            ElixirTypes.CLOSING_PARENTHESIS, ElixirTypes.OPENING_PARENTHESIS,
            ElixirTypes.INTERPOLATION_END, ElixirTypes.INTERPOLATION_START
    );

    /** What {@link #aheadInGroup} found from each token, by token index. */
    private static final class ScanAnswers {
        static final byte UNKNOWN = 0;
        static final byte NOT_AHEAD = 1;
        static final byte AHEAD = 2;

        private byte[] answerByToken = new byte[256];
        private int[] visited = new int[64];
        private int visitedSize = 0;

        byte get(int token) {
            return token < answerByToken.length ? answerByToken[token] : UNKNOWN;
        }

        void clearVisited() {
            visitedSize = 0;
        }

        void visit(int token) {
            if (visitedSize == visited.length) {
                visited = Arrays.copyOf(visited, visited.length * 2);
            }

            visited[visitedSize++] = token;
        }

        void answerVisited(boolean ahead) {
            byte answer = ahead ? AHEAD : NOT_AHEAD;

            for (int index = 0; index < visitedSize; index++) {
                int token = visited[index];

                if (token >= answerByToken.length) {
                    answerByToken = Arrays.copyOf(answerByToken, Math.max(token + 1, answerByToken.length * 2));
                }

                answerByToken[token] = answer;
            }
        }
    }

    /**
     * Whether the token at {@code steps} follows a {@code .}. The lexer reaches the state that names a field or
     * function only through {@code .}, and only whitespace and comments can come between.
     */
    private static boolean afterDot(@NotNull PsiBuilder builder, int steps) {
        int back = steps - 1;
        IElementType previous = builder.rawLookup(back);

        while (previous == TokenType.WHITE_SPACE || previous == ElixirTypes.COMMENT) {
            previous = builder.rawLookup(--back);
        }

        return previous == ElixirTypes.DOT_OPERATOR;
    }

    /**
     * Whether the {@code &} just consumed is joined to what follows, making the two one capture
     * argument such as {@code &1} - see
     * {@link ElixirLanguageLevel#getRequiresAdjacentCaptureArgument()}.
     * <p>
     * Used positively by {@code captureNumericOperation} and negated by {@code nonNumeric}, which is
     * what keeps those two rules exact complements: a spaced {@code & 1} the first rejects has to be
     * accepted by the second, or it matches neither and parses as an error.
     */
    public static boolean captureArgument(@NotNull PsiBuilder builder, int level) {
        if (!languageLevel(builder).getRequiresAdjacentCaptureArgument()) {
            return true;
        }

        /* Whitespace is skipped lazily, so the current lexeme may still be the space itself. Asking
           for the token type forces the skip, which is what makes rawLookup(-1) meaningful here. */
        builder.getTokenType();

        return builder.rawLookup(-1) == ElixirTypes.CAPTURE_OPERATOR;
    }

    /** Whether {@code //} is the step operator - see {@link ElixirLanguageLevel#getHasStepOperator()}. */
    public static boolean stepOperator(@NotNull PsiBuilder builder, int level) {
        return languageLevel(builder).getHasStepOperator();
    }

    /** The characters between a heredoc's opening and its end of line, which the lexer returns as bad characters. */
    public static boolean heredocOpeningContent(@NotNull PsiBuilder builder, int level) {
        if (builder.getTokenType() != TokenType.BAD_CHARACTER) {
            return false;
        }

        while (builder.getTokenType() == TokenType.BAD_CHARACTER) {
            builder.advanceLexer();
        }

        return true;
    }

    /** {@code ...}, which the lexer returns as an identifier. */
    public static boolean ellipsis(@NotNull PsiBuilder builder, int level) {
        return builder.getTokenType() == ElixirTypes.IDENTIFIER_TOKEN && "...".equals(builder.getTokenText());
    }

    /**
     * Whether the {@code +} or {@code -} here takes the other reading from the one the lexer gave it, because the
     * language level does not count an escaped newline as space - see
     * {@link ElixirLanguageLevel#getCountsEscapedNewlineAsSpace()}. The lexer follows the newer reading, so this is true
     * for a binary sign in {@code f -\}+newline+{@code var} and a unary one in {@code f \}+newline+{@code -var}.
     */
    public static boolean escapedNewlineSwapsDualOperator(@NotNull PsiBuilder builder, int level) {
        IElementType tokenType = builder.getTokenType();

        if (tokenType == ElixirTypes.ADDITION_OPERATOR || tokenType == ElixirTypes.SUBTRACTION_OPERATOR) {
            return !languageLevel(builder).getCountsEscapedNewlineAsSpace() &&
                    rawTokenStartsWith(builder, 1, '\\') &&
                    rawTokenStartsWithHorizontalSpace(builder, -1) &&
                    builder.rawLookup(-2) == ElixirTypes.IDENTIFIER_TOKEN;
        }

        if (tokenType == ElixirTypes.NEGATE_OPERATOR || tokenType == ElixirTypes.NUMBER_OR_BADARITH_OPERATOR) {
            if (languageLevel(builder).getCountsEscapedNewlineAsSpace()) {
                return false;
            }

            int steps = -1;
            boolean escapedNewline = false;

            while (builder.rawLookup(steps) == TokenType.WHITE_SPACE) {
                escapedNewline |= rawTokenStartsWith(builder, steps, '\\');
                steps--;
            }

            return escapedNewline &&
                    steps < -1 &&
                    rawTokenStartsWithHorizontalSpace(builder, steps + 1) &&
                    builder.rawLookup(steps) == ElixirTypes.IDENTIFIER_TOKEN;
        }

        return false;
    }

    private static ElixirLanguageLevel languageLevel(@NotNull PsiBuilder builder) {
        ElixirLanguageLevel languageLevel = builder.getUserData(LANGUAGE_LEVEL);

        return languageLevel != null ? languageLevel : ElixirLanguageLevel.getFALLBACK();
    }

    private static boolean rawTokenStartsWithHorizontalSpace(@NotNull PsiBuilder builder, int steps) {
        return rawTokenStartsWith(builder, steps, ' ') || rawTokenStartsWith(builder, steps, '\t');
    }

    private static boolean rawTokenStartsWith(@NotNull PsiBuilder builder, int steps, char character) {
        if (builder.rawLookup(steps) != TokenType.WHITE_SPACE) {
            return false;
        }

        int start = builder.rawTokenTypeStart(steps);
        CharSequence text = builder.getOriginalText();

        return start < text.length() && text.charAt(start) == character;
    }
}
