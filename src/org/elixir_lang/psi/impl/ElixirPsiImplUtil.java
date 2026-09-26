package org.elixir_lang.psi.impl;

import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import kotlin.NotImplementedError;
import kotlin.jvm.functions.Function1;
import org.elixir_lang.call.Visibility;
import org.elixir_lang.psi.*;
import org.elixir_lang.psi.call.Call;
import org.elixir_lang.psi.call.StubBased;
import org.elixir_lang.psi.call.arguments.None;
import org.elixir_lang.psi.call.arguments.star.NoParentheses;
import org.elixir_lang.psi.call.arguments.star.NoParenthesesOneArgument;
import org.elixir_lang.psi.call.arguments.star.Parentheses;
import org.elixir_lang.psi.impl.call.CallImpl;
import org.elixir_lang.psi.impl.call.CallImplKt;
import org.elixir_lang.psi.impl.call.CanonicallyNamedImpl;
import org.elixir_lang.psi.impl.declarations.UseScopeImpl;
import org.elixir_lang.psi.impl.operation.capture.NonNumericImplKt;
import org.elixir_lang.psi.operation.*;
import org.elixir_lang.psi.operation.capture.NonNumeric;
import org.elixir_lang.psi.qualification.Qualified;
import org.elixir_lang.psi.qualification.Unqualified;
import org.elixir_lang.psi.stub.call.Stub;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.elixir_lang.psi.impl.PsiElementImplKt.siblingExpression;
import static org.elixir_lang.psi.impl.PsiElementImplKt.stripAccessExpression;

// The rawtypes and unused paramteters are needed to confirm to the generated parser codes API.
@SuppressWarnings({"rawtypes", "unused"})
public class ElixirPsiImplUtil {
    public static final String DEFAULT_OPERATOR = "\\\\";
    public static final Key<PsiElement> ENTRANCE = new Key<>("ENTRANCE");

    public static final TokenSet ADDITION_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.ADDITION_OPERATOR, ElixirTypes.SUBTRACTION_OPERATOR);
    public static final TokenSet AND_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.AND_SYMBOL_OPERATOR, ElixirTypes.AND_WORD_OPERATOR);
    public static final TokenSet AT_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.AT_OPERATOR);
    public static final TokenSet CAPTURE_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.CAPTURE_OPERATOR);
    public static final TokenSet COMPARISON_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.COMPARISON_OPERATOR);
    public static final TokenSet DOT_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.DOT_OPERATOR);
    public static final TokenSet IN_MATCH_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.IN_MATCH_OPERATOR);
    public static final TokenSet IN_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.IN_OPERATOR);
    public static final TokenSet MATCH_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.MATCH_OPERATOR);
    private static final TokenSet NOT_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.NOT_OPERATOR);
    public static final TokenSet MULTIPLICATIVE_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.DIVISION_OPERATOR, ElixirTypes.MULTIPLICATION_OPERATOR);
    public static final TokenSet OR_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.OR_SYMBOL_OPERATOR, ElixirTypes.OR_WORD_OPERATOR);
    public static final TokenSet PIPE_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.PIPE_OPERATOR);
    public static final TokenSet POWER_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.POWER_OPERATOR);
    public static final TokenSet RELATIONAL_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.RELATIONAL_OPERATOR);
    public static final TokenSet STAB_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.STAB_OPERATOR);
    public static final TokenSet STRUCT_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.STRUCT_OPERATOR);
    public static final TokenSet THREE_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.THREE_OPERATOR);
    public static final TokenSet TERNARY_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.TERNARY_OPERATOR);
    public static final TokenSet TWO_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.RANGE_OPERATOR, ElixirTypes.TWO_OPERATOR);
    public static final TokenSet TYPE_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.TYPE_OPERATOR);
    public static final TokenSet UNARY_OPERATOR_TOKEN_SET = TokenSet.create(
            ElixirTypes.NEGATE_OPERATOR,
            ElixirTypes.NOT_OPERATOR,
            ElixirTypes.NUMBER_OR_BADARITH_OPERATOR,
            ElixirTypes.SIGN_OPERATOR,
            ElixirTypes.UNARY_OPERATOR
    );
    public static final TokenSet IDENTIFIER_TOKEN_SET = TokenSet.create(ElixirTypes.IDENTIFIER_TOKEN);
    // A sign whose reading ElixirParserUtil.escapedNewlineSwapsDualOperator swapped, and a prefix `//` or `...`
    private static final TokenSet ADDITION_INFIX_OPERATOR_TOKEN_SET = TokenSet.orSet(
            ADDITION_OPERATOR_TOKEN_SET,
            TokenSet.create(ElixirTypes.NEGATE_OPERATOR, ElixirTypes.NUMBER_OR_BADARITH_OPERATOR)
    );
    private static final TokenSet UNARY_PREFIX_OPERATOR_TOKEN_SET = TokenSet.orSet(
            UNARY_OPERATOR_TOKEN_SET,
            ADDITION_OPERATOR_TOKEN_SET,
            TERNARY_OPERATOR_TOKEN_SET,
            IDENTIFIER_TOKEN_SET
    );
    public static final Function1<? super PsiElement, ? extends PsiElement> NEXT_SIBLING =
            (Function1<PsiElement, PsiElement>) PsiElement::getNextSibling;
    public static final Function1<? super PsiElement, ? extends PsiElement> PREVIOUS_SIBLING =
            (Function1<PsiElement, PsiElement>) PsiElement::getPrevSibling;
    public static final TokenSet ARROW_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.ARROW_OPERATOR);
    public static final TokenSet WHEN_OPERATOR_TOKEN_SET = TokenSet.create(ElixirTypes.WHEN_OPERATOR);

    @Contract(pure = true)
    @NotNull
    public static PsiElement[] arguments(@NotNull final ElixirMapConstructionArguments mapConstructionArguments) {
        return mapConstructionArguments.getChildren();
    }

    @Contract(pure = true)
    @NotNull
    public static PsiElement[] arguments(@NotNull final ElixirNoParenthesesOneArgument noParenthesesOneArgument) {
        return ArgumentsImpl.arguments(noParenthesesOneArgument);
    }

    @Contract(pure = true)
    @NotNull
    public static PsiElement[] arguments(@NotNull final ElixirNoParenthesesStrict noParenthesesStrict) {
        return ArgumentsImpl.arguments(noParenthesesStrict);
    }

    @Contract(pure = true)
    @NotNull
    public static PsiElement[] arguments(@NotNull final ElixirParenthesesArguments parenthesesArguments) {
        return ArgumentsImpl.arguments(parenthesesArguments);
    }

    @Contract(pure = true)
    public static int base(@NotNull final ElixirBinaryDigits binaryDigits) {
        return DigitsImpl.base(binaryDigits);
    }

    @Contract(pure = true)
    public static int base(@NotNull final ElixirBinaryWholeNumber binaryWholeNumber) {
        return WholeNumberImpl.base(binaryWholeNumber);
    }

    @Contract(pure = true)
    public static int base(@NotNull final ElixirDecimalDigits decimalDigits) {
        return DigitsImpl.base(decimalDigits);
    }

    @Contract(pure = true)
    public static int base(@NotNull final ElixirDecimalWholeNumber decimalWholeNumber) {
        return WholeNumberImpl.base(decimalWholeNumber);
    }

    @Contract(pure = true)
    public static int base(@NotNull final ElixirHexadecimalDigits hexadecimalDigits) {
        return DigitsImpl.base(hexadecimalDigits);
    }

    @Contract(pure = true)
    public static int base(@NotNull final ElixirHexadecimalWholeNumber hexadecimalWholeNumber) {
        return WholeNumberImpl.base(hexadecimalWholeNumber);
    }

    @Contract(pure = true)
    public static int base(@NotNull final ElixirOctalDigits octalDigits) {
        return DigitsImpl.base(octalDigits);
    }

    @Contract(pure = true)
    public static int base(@NotNull final ElixirOctalWholeNumber octalWholeNumber) {
        return WholeNumberImpl.base(octalWholeNumber);
    }

    @Contract(pure = true)
    public static int base(@NotNull final ElixirUnknownBaseDigits unknownBaseDigits) {
        return DigitsImpl.base(unknownBaseDigits);
    }

    @Contract(pure = true)
    public static int base(@NotNull final ElixirUnknownBaseWholeNumber unknownBaseWholeNumber) {
        return WholeNumberImpl.base(unknownBaseWholeNumber);
    }

    @NotNull
    public static String javaString(@NotNull OtpErlangBinary elixirString) {
        byte[] bytes = elixirString.binaryValue();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @RequiresReadLock
    @Nullable
    public static String canonicalName(@NotNull StubBased stubBased) {
        return CanonicallyNamedImpl.INSTANCE.canonicalName(stubBased);
    }

    @RequiresReadLock
    @NotNull
    public static Set<String> canonicalNameSet(@NotNull StubBased stubBased) {
        return CanonicallyNamedImpl.INSTANCE.canonicalNameSet(stubBased);
    }

    // @return -1 if codePoint cannot be parsed.
    public static int codePoint(@NotNull ElixirEscapedCharacter escapedCharacter) {
        return EscapeSequenceImpl.codePoint(escapedCharacter);
    }

    @Contract(pure = true)
    public static int codePoint(ElixirEscapedEOL escapedEOL) {
        return EscapeSequenceImpl.codePoint(escapedEOL);
    }

    // @return -1 if codePoint cannot be parsed.
    public static int codePoint(@NotNull EscapedHexadecimalDigits hexadecimalEscapeSequence) {
        return EscapeSequenceImpl.codePoint(hexadecimalEscapeSequence);
    }

    // @return -1 if codePoint cannot be parsed.
    public static int codePoint(@NotNull ElixirQuoteHexadecimalEscapeSequence quoteHexadecimalEscapeSequence) {
        return EscapeSequenceImpl.codePoint(quoteHexadecimalEscapeSequence);
    }

    public static int codePoint(@NotNull ElixirSigilHexadecimalEscapeSequence sigilHexadecimalEscapeSequence) {
        return EscapeSequenceImpl.codePoint(sigilHexadecimalEscapeSequence);
    }

    @NotNull
    public static List<Digits> digitsList(@NotNull ElixirBinaryWholeNumber binaryWholeNumber) {
        return WholeNumberImpl.digitsList(binaryWholeNumber);
    }

    @NotNull
    public static List<Digits> digitsList(@NotNull ElixirDecimalWholeNumber decimalWholeNumber) {
        return WholeNumberImpl.digitsList(decimalWholeNumber);
    }

    @NotNull
    static List<Digits> digitsList(@NotNull ElixirHexadecimalWholeNumber hexadecimalWholeNumber) {
        return WholeNumberImpl.digitsList(hexadecimalWholeNumber);
    }

    @NotNull
    public static List<Digits> digitsList(@NotNull ElixirOctalWholeNumber octalWholeNumber) {
        return WholeNumberImpl.digitsList(octalWholeNumber);
    }

    @NotNull
    public static List<Digits> digitsList(@NotNull ElixirUnknownBaseWholeNumber unknownBaseWholeNumber) {
        return WholeNumberImpl.digitsList(unknownBaseWholeNumber);
    }

    public static boolean inBase(@NotNull final Digits digits) {
        return DigitsImpl.inBase(digits);
    }

    @RequiresReadLock
    public static boolean isCalling(@NotNull final Call call,
                                    @NotNull final String resolvedModuleName,
                                    @NotNull final String functionName) {
        return CallImpl.isCalling(call, resolvedModuleName, functionName);
    }

    @RequiresReadLock
    public static boolean isCalling(@NotNull final Call call,
                                    @NotNull final String resolvedModuleName,
                                    @NotNull final String functionName,
                                    final int resolvedFinalArity) {
        return CallImpl.isCalling(call, resolvedModuleName, functionName, resolvedFinalArity);
    }

    @RequiresReadLock
    public static boolean isCallingMacro(@NotNull final Call call,
                                         @NotNull final String resolvedModuleName,
                                         @NotNull final String functionName) {
        return CallImpl.isCallingMacro(call, resolvedModuleName, functionName);
    }

    @RequiresReadLock
    @Contract(pure = true)
    public static boolean isCallingMacro(@NotNull final Call call,
                                         @NotNull final String resolvedModuleName,
                                         @NotNull final String functionName,
                                         final int resolvedFinalArity) {
        return CallImpl.isCallingMacro(call, resolvedModuleName, functionName, resolvedFinalArity);
    }

    public static boolean isCharList(ElixirLine elixirLine) {
        return elixirLine.getNode().getFirstChildNode().getText().equals("'");
    }

    public static boolean isCharList(ElixirHeredoc elixirHeredoc) {
        return elixirHeredoc.getNode().getFirstChildNode().getText().equals("'''");
    }

    @RequiresReadLock
    public static boolean isExported(@NotNull final UnqualifiedNoParenthesesCall unqualifiedNoParenthesesCall) {
        CallableDeclaration.Definer definer = CallableDeclaration.INSTANCE.definerOf(unqualifiedNoParenthesesCall);

        return definer != null && definer.getVisibility() == Visibility.PUBLIC;
    }

    public static boolean isModuleName(@NotNull final ElixirAccessExpression accessExpression) {
        return MaybeModuleNameImpl.isModuleName(accessExpression);
    }

    public static boolean isModuleName(@NotNull final QualifiableAlias qualifiableAlias) {
        return MaybeModuleNameImpl.isModuleName(qualifiableAlias);
    }

    public static boolean isModuleName(@NotNull final ElixirNoParenthesesOneArgument noParenthesesOneArgument) {
        return MaybeModuleNameImpl.isModuleName(noParenthesesOneArgument);
    }

    public static boolean isValidHost(@NotNull final Parent parent) {
        return org.elixir_lang.injection.PsiLanguageInjectionHost.isValidHost(parent);
    }

    public static PsiLanguageInjectionHost updateText(@NotNull final Parent parent, @NotNull String text) {
        throw new NotImplementedError();
    }

    public static @NotNull LiteralTextEscaper<? extends PsiLanguageInjectionHost> createLiteralTextEscaper(@NotNull final Parent parent) {
        return org.elixir_lang.injection.PsiLanguageInjectionHost.createLiteralTextEscaper(parent);
    }

    @Contract(pure = true)
    @Nullable
    public static Quotable leftOperand(@NotNull final ElixirStabOperation stabOperation) {
        Quotable leftOperand = stabOperation.getStabParenthesesSignature();

        if (leftOperand == null) {
            leftOperand = stabOperation.getStabNoParenthesesSignature();
        }

        return leftOperand;
    }

    @Contract(pure = true)
    @Nullable
    public static Quotable leftOperand(Infix infix) {
        return org.elixir_lang.psi.operation.infix.Normalized.leftOperand(infix);
    }

    @Contract(pure = true)
    @Nullable
    public static Quotable leftOperand(@NotNull NotIn notIn) {
        return org.elixir_lang.psi.operation.not_in.Normalized.leftOperand(notIn);
    }

    @Contract(pure = true)
    @NotNull
    public static String moduleAttributeName(@NotNull final AtOperation atNonNumericOperation) {
        return atNonNumericOperation.getText();
    }

    @Contract(pure = true)
    @NotNull
    public static String moduleAttributeName(@NotNull final AtUnqualifiedNoParenthesesCall atUnqualifiedNoParenthesesCall) {
        return atUnqualifiedNoParenthesesCall.getAtIdentifier().getText();
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static String moduleName(@NotNull AtUnqualifiedNoParenthesesCall atUnqualifiedNoParenthesesCall) {
        return CallImpl.moduleName(atUnqualifiedNoParenthesesCall);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static String moduleName(@NotNull DotCall dotCall) {
        return CallImpl.moduleName(dotCall);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static String moduleName(@NotNull NotIn notIn) {
        return CallImpl.moduleName(notIn);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static String moduleName(@NotNull Operation operation) {
        return CallImpl.moduleName(operation);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static String moduleName(@NotNull Unqualified unqualified) {
        return CallImpl.moduleName(unqualified);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static String moduleName(@NotNull final Qualified qualified) {
        return CallImpl.moduleName(qualified);
    }

    @Contract(pure = true)
    @Nullable
    public static Quotable operand(Prefix prefix) {
        return org.elixir_lang.psi.operation.prefix.Normalized.operand(prefix);
    }

    @Contract(pure = true)
    @NotNull
    public static Operator operator(@NotNull final ElixirStabOperation stabOperation) {
        return stabOperation.getStabInfixOperator();
    }

    @Contract(pure = true)
    @NotNull
    public static Operator operator(Infix infix) {
        return Normalized.operator(infix);
    }

    @Contract(pure = true)
    @NotNull
    public static Operator operator(Prefix prefix) {
        return Normalized.operator(prefix);
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirAdditionInfixOperator additionInfixOperator) {
        return ADDITION_INFIX_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirAndInfixOperator andInfixOperator) {
        return AND_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirArrowInfixOperator arrowInfixOperator) {
        return ARROW_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirAtPrefixOperator atPrefixOperator) {
        return AT_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirCapturePrefixOperator capturePrefixOperator) {
        return CAPTURE_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirComparisonInfixOperator comparisonInfixOperator) {
        return COMPARISON_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirDotInfixOperator dotInfixOperator) {
        return DOT_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirMultiplicationInfixOperator multiplicationInfixOperator) {
        return MULTIPLICATIVE_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirInInfixOperator inInfixOperator) {
        return IN_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirInMatchInfixOperator inMatchInfixOperator) {
        return IN_MATCH_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirMapPrefixOperator mapPrefixOperator) {
        return STRUCT_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirMatchInfixOperator matchInfixOperator) {
        return MATCH_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirNotInfixOperator notInfixOperator) {
        return NOT_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirOrInfixOperator orInfixOperator) {
        return OR_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirPipeInfixOperator pipeInfixOperator) {
        return PIPE_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirPowerInfixOperator pipeInfixOperator) {
        return POWER_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirRelationalInfixOperator relationalInfixOperator) {
        return RELATIONAL_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirStabInfixOperator stabInfixOperator) {
        return STAB_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirTernaryInfixOperator ternaryInfixOperator) {
        return TERNARY_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirThreeInfixOperator threeInfixOperator) {
        return THREE_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirTwoInfixOperator twoInfixOperator) {
        return TWO_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirTypeInfixOperator typeInfixOperator) {
        return TYPE_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirUnaryPrefixOperator unaryPrefixOperator) {
        return UNARY_PREFIX_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @NotNull
    public static TokenSet operatorTokenSet(@SuppressWarnings("unused") final ElixirWhenInfixOperator whenInfixOperator) {
        return WHEN_OPERATOR_TOKEN_SET;
    }

    @Contract(pure = true)
    @Nullable
    public static PsiElement previousSiblingExpression(@NotNull PsiElement element) {
        return siblingExpression(element, PREVIOUS_SIBLING);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static PsiElement[] primaryArguments(@NotNull final DotCall dotCall) {
        return CallImpl.primaryArguments(dotCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static PsiElement[] primaryArguments(@NotNull final Infix infix) {
        return CallImpl.primaryArguments(infix);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static PsiElement[] primaryArguments(
            @NotNull final ElixirUnqualifiedNoParenthesesManyArgumentsCall unqualifiedNoParenthesesManyArgumentsCall
    ) {
        return CallImpl.primaryArguments(unqualifiedNoParenthesesManyArgumentsCall);
    }

    @Contract(pure = true)
    @Nullable
    public static PsiElement[] primaryArguments(@NotNull None none) {
        return CallImpl.primaryArguments(none);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static PsiElement[] primaryArguments(@NotNull final NotIn notIn) {
        return CallImpl.primaryArguments(notIn);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static PsiElement[] primaryArguments(@NotNull final NoParenthesesOneArgument noParenthesesOneArgument) {
        return CallImpl.primaryArguments(noParenthesesOneArgument);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static PsiElement[] primaryArguments(@NotNull final Parentheses parentheses) {
        return CallImpl.primaryArguments(parentheses);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static PsiElement[] primaryArguments(@NotNull final Prefix prefix) {
        return CallImpl.primaryArguments(prefix);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @Nullable
    public static Integer primaryArity(@NotNull final Call call) {
        return CallImpl.primaryArity(call);
    }

    @RequiresReadLock
    public static boolean processDeclarations(@NotNull final ElixirUnmatchedAtUnqualifiedNoParenthesesCall atUnqualifiedNoParenthesesCall,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull PsiElement place) {
        return ProcessDeclarationsImpl.processDeclarations(atUnqualifiedNoParenthesesCall, processor, state, lastParent, place);
    }

    @RequiresReadLock
    public static boolean processDeclarations(@NotNull final And and,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull PsiElement place) {
        return ProcessDeclarationsImpl.processDeclarations(and, processor, state, lastParent, place);
    }

    @RequiresReadLock
    public static boolean processDeclarations(@NotNull final Call call,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull PsiElement place) {
        return ProcessDeclarationsImpl.processDeclarations(call, processor, state, lastParent, place);
    }

    @RequiresReadLock
    public static boolean processDeclarations(@NotNull final Match match,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull PsiElement place) {
        return ProcessDeclarationsImpl.processDeclarations(match, processor, state, lastParent, place);
    }

    @RequiresReadLock
    public static boolean processDeclarations(@NotNull final Type type,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull PsiElement place) {
        return ProcessDeclarationsImpl.processDeclarations(type, processor, state);
    }

    public static boolean processDeclarations(@NotNull final ElixirAlias alias,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull PsiElement place) {
        return ProcessDeclarationsImpl.processDeclarations(alias, processor, state, lastParent, place);
    }

    public static boolean processDeclarations(@NotNull final ElixirMultipleAliases multipleAliases,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull PsiElement entrance) {
        return ProcessDeclarationsImpl.processDeclarations(multipleAliases, processor, state, lastParent, entrance);
    }

    public static boolean processDeclarations(@NotNull final ElixirEex scope,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull PsiElement place) {
        return ProcessDeclarationsImpl.processDeclarations(scope, processor, state, lastParent, place);
    }

    public static boolean processDeclarations(@NotNull final ElixirEexTag scope,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull PsiElement place) {
        return ProcessDeclarationsImpl.processDeclarations(scope, processor, state, lastParent, place);
    }

    public static boolean processDeclarations(@NotNull final ElixirStabBody scope,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull @SuppressWarnings("unused") PsiElement place) {
        return ProcessDeclarationsImpl.processDeclarations(scope, processor, state, lastParent, place);
    }

    public static boolean processDeclarations(@NotNull final ElixirStabOperation stabOperation,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull PsiElement place) {
        return ProcessDeclarationsImpl.processDeclarations(stabOperation, processor, state, lastParent, place);
    }

    public static boolean processDeclarations(@NotNull final QualifiedAlias qualifiedAlias,
                                              @NotNull PsiScopeProcessor processor,
                                              @NotNull ResolveState state,
                                              PsiElement lastParent,
                                              @NotNull PsiElement place) {
        return ProcessDeclarationsImpl.processDeclarations(qualifiedAlias, processor, state, lastParent, place);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull AssociationOperation associationOperation) {
        return QuotableImpl.quote(associationOperation);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull Infix infix) {
        return QuotableImpl.quote(infix);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull NotIn notIn) {
        return QuotableImpl.quote(notIn);
    }


    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull Ternary ternary) {
        return QuotableImpl.quote(ternary);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirBitString bitString) {
        return QuotableImpl.quote(bitString);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirBlockIdentifier blockIdentifier) {
        return QuotableImpl.quote(blockIdentifier);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirBlockItem blockItem) {
        return QuotableImpl.quote(blockItem);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirBracketArguments bracketArguments) {
        return QuotableImpl.quote(bracketArguments);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull Digits digits) {
        return QuotableImpl.quote(digits);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirAccessExpression accessExpression) {
        return QuotableImpl.quote(accessExpression);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirAlias alias) {
        return QuotableImpl.quote(alias);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirAnonymousFunction anonymousFunction) {
        return QuotableImpl.quote(anonymousFunction);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirAssociations associations) {
        return QuotableImpl.quote(associations);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirAssociationsBase associationsBase) {
        return QuotableImpl.quote(associationsBase);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirAtom atom) {
        return QuotableImpl.quote(atom);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirAtomKeyword atomKeyword) {
        return QuotableImpl.quote(atomKeyword);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirLine elixirLine) {
        return QuotableImpl.quote(elixirLine);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirCharToken charToken) {
        return QuotableImpl.quote(charToken);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirContainerAssociationOperation containerAssociationOperation) {
        return QuotableImpl.quote(containerAssociationOperation);
    }

    @Contract(pure = true)
    @Nullable
    public static ASTNode excessWhitespace(@NotNull final ElixirHeredocLinePrefix heredocLinePrefix,
                                           @NotNull final IElementType fragmentType,
                                           final int prefixLength) {
        return ElixirHeredocLinePrefixImplKt.excessWhitespace(heredocLinePrefix, fragmentType, prefixLength);
    }

    @RequiresReadLock
    @Contract(pure = true)
    public static int exportedArity(@NotNull final UnqualifiedNoParenthesesCall unqualifiedNoParenthesesCall,
                                    @NotNull ResolveState state) {
        return UnqualifiedNoParenthesesCallImplKt.exportedArity(unqualifiedNoParenthesesCall, state);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @Nullable
    public static String exportedName(@NotNull final UnqualifiedNoParenthesesCall unqualifiedNoParenthesesCall) {
        return UnqualifiedNoParenthesesCallImplKt.cachedExportedName(unqualifiedNoParenthesesCall);
    }

    @Contract(pure = true)
    @NotNull
    public static String fullyQualifiedName(@NotNull final ElixirAlias alias) {
        return QualifiableAliasImpl.fullyQualifiedName(alias);
    }

    @Contract(pure = true)
    public static @NotNull String fullyQualifiedName(@NotNull final QualifiableAlias qualifiableAlias) {
        return QualifiableAliasImpl.fullyQualifiedName(qualifiableAlias);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @Nullable
    public static String functionName(@NotNull final Call call) {
        return CallImpl.functionName(call);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static PsiElement functionNameElement(
            @NotNull final AtUnqualifiedNoParenthesesCall atUnqualifiedNoParenthesesCall
    ) {
        return CallImpl.functionNameElement(atUnqualifiedNoParenthesesCall);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static PsiElement functionNameElement(@NotNull final DotCall dotCall) {
        return CallImpl.functionNameElement(dotCall);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static PsiElement functionNameElement(@NotNull final NotIn notIn) {
        return CallImpl.functionNameElement(notIn);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static PsiElement functionNameElement(@NotNull final Operation operation) {
        return CallImpl.functionNameElement(operation);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static PsiElement functionNameElement(@NotNull final Qualified qualified) {
        return CallImpl.functionNameElement(qualified);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static PsiElement functionNameElement(@NotNull final Unqualified unqualified) {
        return CallImpl.functionNameElement(unqualified);
    }

    public static Body getBody(ElixirHeredocLine elixirHeredocLine) {
        return elixirHeredocLine.getHeredocLineBody();
    }

    public static Body getBody(ElixirInterpolatedHeredocLine elixirInterpolatedHeredocLine) {
        return elixirInterpolatedHeredocLine.getInterpolatedHeredocLineBody();
    }

    public static Body getBody(ElixirLiteralHeredocLine elixirLiteralHeredocLine) {
        return elixirLiteralHeredocLine.getLiteralHeredocLineBody();
    }

    public static Body getBody(ElixirInterpolatedSigilLine elixirInterpolatedSigilLine) {
        return elixirInterpolatedSigilLine.getInterpolatedSigilLineBody();
    }

    @Nullable
    public static Body getBody(@NotNull final ElixirLine elixirLine) {
        return elixirLine.getLineBody();
    }

    public static Body getBody(ElixirLiteralSigilLine literalSigilLine) {
        return literalSigilLine.getLiteralSigilLineBody();
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static ElixirDoBlock getDoBlock(
            @NotNull ElixirUnqualifiedNoParenthesesManyArgumentsCall unqualifiedNoParenthesesManyArgumentsCall
    ) {
        return CallImpl.getDoBlock(unqualifiedNoParenthesesManyArgumentsCall);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static ElixirDoBlock getDoBlock(@NotNull NotIn notIn) {
        return CallImpl.getDoBlock(notIn);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static ElixirDoBlock getDoBlock(@NotNull Operation operation) {
        return CallImpl.getDoBlock(operation);
    }

    @Nullable
    public static ElixirDoBlock getDoBlock(@NotNull MatchedCall matchedCall) {
        return CallImpl.getDoBlock(matchedCall);
    }

    @NotNull
    @Contract(pure = true)
    static SearchScope getUseScope(@NotNull AtUnqualifiedNoParenthesesCall atUnqualifiedNoParenthesesCall) {
        return UseScopeImpl.get(atUnqualifiedNoParenthesesCall);
    }

    @NotNull
    @Contract(pure = true)
    static SearchScope getUseScope(@NotNull UnqualifiedNoArgumentsCall unqualifiedNoArgumentsCall) {
        return UseScopeImpl.get(unqualifiedNoArgumentsCall);
    }

    @NotNull
    public static List<? extends HeredocLineable> getHeredocLineList(ElixirInterpolatedSigilHeredoc interpolatedSigilHeredoc) {
        return interpolatedSigilHeredoc.getInterpolatedHeredocLineList();
    }

    @NotNull
    public static List<? extends HeredocLineable> getHeredocLineList(ElixirLiteralSigilHeredoc literalSigilHeredoc) {
        return literalSigilHeredoc.getLiteralHeredocLineList();
    }

    @NotNull
    public static Quotable getKeywordValue(ElixirKeywordPair keywordPair) {
        return QuotableKeywordPairImpl.getKeywordValue(keywordPair);
    }

    @NotNull
    public static Quotable getKeywordValue(ElixirNoParenthesesKeywordPair noParenthesesKeywordPair) {
        return QuotableKeywordPairImpl.getKeywordValue(noParenthesesKeywordPair);
    }

    @RequiresReadLock
    @NotNull
    public static String getName(@NotNull ElixirAlias alias) {
        return PsiNamedElementImpl.getName(alias);
    }

    @RequiresReadLock
    @NotNull
    public static String getName(@NotNull QualifiedAlias qualifiedAlias) {
        return PsiNamedElementImpl.getName(qualifiedAlias);
    }

    @RequiresReadLock
    @Nullable
    public static String getName(@NotNull ElixirAtom atom) {
        return PsiNamedElementImpl.getName(atom);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @Nullable
    public static String getName(@NotNull NamedElement namedElement) {
        return PsiNamedElementImpl.getName(namedElement);
    }

    @Nullable
    public static PsiElement getNameIdentifier(@NotNull ElixirAlias alias) {
        return PsiNameIdentifierOwnerImpl.getNameIdentifier(alias);
    }

    @Nullable
    public static PsiElement getNameIdentifier(@NotNull ElixirKeywordKey keywordKey) {
        return PsiNameIdentifierOwnerImpl.getNameIdentifier(keywordKey);
    }

    @Contract(pure = true)
    @NotNull
    public static PsiElement getNameIdentifier(@NotNull ElixirVariable variable) {
        return PsiNameIdentifierOwnerImpl.getNameIdentifier(variable);
    }

    @RequiresReadLock
    @Nullable
    public static PsiElement getNameIdentifier(@NotNull org.elixir_lang.psi.call.Named named) {
        if (named instanceof NonNumeric nonNumeric) {
            return NonNumericImplKt.getNameIdentifier(nonNumeric);
        }
        return PsiNameIdentifierOwnerImpl.getNameIdentifier(named);
    }

    @Nullable
    public static PsiElement getNameIdentifier(@NotNull QualifiableAlias qualifiableAlias) {
        return PsiNameIdentifierOwnerImpl.getNameIdentifier(qualifiableAlias);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static ItemPresentation getPresentation(@NotNull final Call call) {
        return PresentationImpl.getPresentation(call);
    }

    @Contract(pure = true)
    @Nullable
    public static ItemPresentation getPresentation(@NotNull final ElixirIdentifier identifier) {
        return PresentationImpl.getPresentation(identifier);
    }

    @Contract(pure = true)
    @NotNull
    public static ItemPresentation getPresentation(@NotNull final ElixirKeywordKey keywordKey) {
        return PresentationImpl.getPresentation(keywordKey);
    }

    public static @NotNull ItemPresentation getPresentation(@NotNull final QualifiableAlias qualifiableAlias) {
        return PresentationImpl.getPresentation(qualifiableAlias);
    }

    @RequiresReadLock
    public static @Nullable String implementedProtocolName(@NotNull final Call call) {
        String protocolName;

        if (Implementation.is(call)) {
            protocolName = Implementation.protocolName(call);
        } else {
            protocolName = null;
        }

        return protocolName;
    }

    @Nullable
    public static PsiReference getReference(@NotNull AtUnqualifiedNoParenthesesCall atUnqualifiedNoParenthesesCall) {
        return null;
    }

    @Nullable
    public static PsiReference getReference(@NotNull DotCall dotCall) {
        // DotCalls do not reference something, instead the expression before the `.` would have the reference.
        return null;
    }

    @Nullable
    public static PsiReference getReference(@NotNull Call call) {
        if (call instanceof NonNumeric nonNumeric) {
            return NonNumericImplKt.getReference(nonNumeric);
        }
        return CallImplKt.cachedReference(call);
    }

    @Nullable
    public static PsiReference getReference(@NotNull ElixirAtom atom) {
        return ElixirAtomImplKt.getReference(atom);
    }

    @Nullable
    public static PsiReference getReference(@NotNull ElixirIdentifier identifier) {
        // migrated to Symbol API
        return null;
    }

    @Nullable
    public static PsiPolyVariantReference getReference(@NotNull QualifiableAlias qualifiableAlias) {
        return QualifiableAliasImplKt.cachedReference(qualifiableAlias);
    }

    @Contract(pure = true)
    @Nullable
    public static PsiReference getReference(@NotNull final ElixirAtIdentifier atIdentifier) {
        return null;
    }

    @Nullable
    public static PsiReference getReference(@NotNull final AtOperation atNonNumericOperation) {
        return AtNonNumericOperationImplKt.cachedReference(atNonNumericOperation);
    }

    @RequiresReadLock
    public static boolean hasDoBlockOrKeyword(@NotNull final Call call) {
        return CallImpl.hasDoBlockOrKeyword(call);
    }

    @RequiresReadLock
    public static boolean hasDoBlockOrKeyword(@NotNull final StubBased<Stub> stubBased) {
        return CallImpl.hasDoBlockOrKeyword(stubBased);
    }

    @Contract(pure = true)
    @NotNull
    public static PsiElement qualifier(@NotNull final Qualified qualified) {
        return stripAccessExpression(qualified.getFirstChild());
    }

    public static List<QuotableKeywordPair> quotableKeywordPairList(ElixirKeywords keywords) {
        return new ArrayList<>(keywords.getKeywordPairList());
    }

    public static List<QuotableKeywordPair> quotableKeywordPairList(ElixirNoParenthesesKeywords noParenthesesKeywords) {
        return new ArrayList<>(noParenthesesKeywords.getNoParenthesesKeywordPairList());
    }

    @RequiresReadLock
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirDecimalFloat decimalFloat) {
        return QuotableImpl.quote(decimalFloat);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirEmptyParentheses emptyParentheses) {
        return QuotableImpl.quote(emptyParentheses);
    }

    @RequiresReadLock
    public static OtpErlangObject quote(@NotNull ElixirFile file) {
        return QuotableImpl.quote(file);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final WholeNumber wholeNumber) {
        return QuotableImpl.quote(wholeNumber);
    }

    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quoteAsAtom(@NotNull final ElixirLine line) {
        return AtomableImplKt.quoteLineAsAtom(line);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirIdentifier identifier) {
        return QuotableImpl.quote(identifier);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(ElixirRelativeIdentifier relativeIdentifier) {
        return QuotableImpl.quote(relativeIdentifier);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(ElixirSigilModifiers sigilModifiers) {
        return QuotableImpl.quote(sigilModifiers);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(ElixirStab stab) {
        return QuotableImpl.quote(stab);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirStabBody stabBody) {
        return QuotableImpl.quote(stabBody);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirStabNoParenthesesSignature stabNoParenthesesSignature) {
        return QuotableImpl.quote(stabNoParenthesesSignature);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(ElixirStabOperation stabOperation) {
        return QuotableImpl.quote(stabOperation);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirStabParenthesesSignature stabParenthesesSignature) {
        return QuotableImpl.quote(stabParenthesesSignature);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirStructOperation structOperation) {
        return QuotableImpl.quote(structOperation);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirNullaryRangeOperation nullaryRangeOperation) {
        return QuotableImpl.quote(nullaryRangeOperation);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirSteppedRangeKeywordCall steppedRangeKeywordCall) {
        return QuotableImpl.quote(steppedRangeKeywordCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirTuple tuple) {
        return QuotableImpl.quote(tuple);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final HeredocLiteral heredocLiteral) {
        return QuotableImpl.quote(heredocLiteral);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final HeredocLineable heredocLineable, @NotNull final HeredocLiteral heredocLiteral, int prefixLength) {
        return QuotableImpl.quote(heredocLineable, heredocLiteral, prefixLength);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final QuotableKeywordList quotableKeywordList) {
        return QuotableImpl.quote(quotableKeywordList);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final QuotableKeywordPair quotableKeywordPair) {
        return QuotableImpl.quote(quotableKeywordPair);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirKeywordKey keywordKey) {
        return QuotableImpl.quote(keywordKey);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirList list) {
        return QuotableImpl.quote(list);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirMapArguments mapArguments) {
        return QuotableImpl.quote(mapArguments);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirMapOperation mapOperation) {
        return QuotableImpl.quote(mapOperation);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirMapUpdateArguments mapUpdateArguments) {
        return QuotableImpl.quote(mapUpdateArguments);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull ElixirMultipleAliases multipleAliases) {
        return QuotableImpl.quote(multipleAliases);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(
            @NotNull
            ElixirNoParenthesesManyStrictNoParenthesesExpression noParenthesesManyStrictNoParenthesesExpression
    ) {
        return QuotableImpl.quote(noParenthesesManyStrictNoParenthesesExpression);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull AtNumericBracketOperation atUnqualifiedBracketOperation) {
        return QuotableImpl.quote(atUnqualifiedBracketOperation);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull AtUnqualifiedBracketOperation atUnqualifiedBracketOperation) {
        return QuotableImpl.quote(atUnqualifiedBracketOperation);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull AtUnqualifiedNoParenthesesCall atUnqualifiedNoParenthesesCall) {
        return QuotableImpl.quote(atUnqualifiedNoParenthesesCall);
    }

    @RequiresReadLock
    @NotNull
    public static OtpErlangObject quote(@NotNull BracketOperation bracketOperation) {
        return QuotableImpl.quote(bracketOperation);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull DotCall dotCall) {
        return QuotableImpl.quote(dotCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull In in) {
        return QuotableImpl.quote(in);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull QualifiedAlias qualifiedAlias) {
        return QuotableImpl.quote(qualifiedAlias);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull QualifiedMultipleAliases qualifiedMultipleAliases) {
        return QuotableImpl.quote(qualifiedMultipleAliases);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull QualifiedBracketOperation qualifiedBracketOperation) {
        return QuotableImpl.quote(qualifiedBracketOperation);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull QualifiedNoArgumentsCall qualifiedNoArgumentsCall) {
        return QuotableImpl.quote(qualifiedNoArgumentsCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull QualifiedNoParenthesesCall qualifiedNoParenthesesCall) {
        return QuotableImpl.quote(qualifiedNoParenthesesCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull QualifiedParenthesesCall qualifiedParenthesesCall) {
        return QuotableImpl.quote(qualifiedParenthesesCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull UnqualifiedBracketOperation unqualifiedBracketOperation) {
        return QuotableImpl.quote(unqualifiedBracketOperation);
    }

    /* Replaces `nil` argument in variables with the quoted ElixirMatchedNotParenthesesArguments.
     *
     */
    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull UnqualifiedNoParenthesesCall unqualifiedNoParenthesesCall) {
        return QuotableImpl.quote(unqualifiedNoParenthesesCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final UnqualifiedNoArgumentsCall unqualifiedNoArgumentsCall) {
        return QuotableImpl.quote(unqualifiedNoArgumentsCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final UnqualifiedParenthesesCall unqualifiedParenthesesCall) {
        return QuotableImpl.quote(unqualifiedParenthesesCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirParentheticalStab parentheticalStab) {
        return QuotableImpl.quote(parentheticalStab);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull final ElixirVariable variable) {
        return QuotableImpl.quote(variable);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(Operator operator) {
        return QuotableImpl.quote(operator);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(Prefix prefix) {
        return QuotableImpl.quote(prefix);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(PsiFile file) {
        return QuotableImpl.quote(file);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(
            @NotNull
            ElixirUnqualifiedNoParenthesesManyArgumentsCall unqualifiedNoParenthesesManyArgumentsCall
    ) {
        return QuotableImpl.quote(unqualifiedNoParenthesesManyArgumentsCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(SigilHeredocLiteral sigilHeredoc) {
        return QuotableImpl.quote(sigilHeredoc);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(SigilLine sigilLine) {
        return QuotableImpl.quote(sigilLine);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject[] quoteArguments(@NotNull final Arguments arguments) {
        return QuotableArgumentsImpl.quoteArguments(arguments);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject[] quoteArguments(@NotNull final ElixirBlockList blockList) {
        return QuotableArgumentsImpl.quoteArguments(blockList);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject[] quoteArguments(
            @NotNull ElixirUnqualifiedNoParenthesesManyArgumentsCall unqualifiedNoParenthesesManyArgumentsCall
    ) {
        return QuotableArgumentsImpl.quoteArguments(unqualifiedNoParenthesesManyArgumentsCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject[] quoteArguments(@NotNull final ElixirDoBlock doBlock) {
        return QuotableArgumentsImpl.quoteArguments(doBlock);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject[] quoteArguments(
            @NotNull final ElixirMapConstructionArguments mapConstructionArguments
    ) {
        return QuotableArgumentsImpl.quoteArguments(mapConstructionArguments);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject[] quoteArguments(@NotNull final ElixirNoParenthesesArguments noParenthesesArguments) {
        return QuotableArgumentsImpl.quoteArguments(noParenthesesArguments);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject[] quoteArguments(ElixirParenthesesArguments parenthesesArguments) {
        return QuotableArgumentsImpl.quoteArguments(parenthesesArguments);
    }

    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quoteBinary(ElixirLine elixirLine, OtpErlangList metadata, List<OtpErlangObject> argumentList) {
        return ParentImpl.quoteBinary(elixirLine, metadata, argumentList);
    }

    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quoteBinary(ElixirHeredoc elixirHeredoc, OtpErlangList metadata, List<OtpErlangObject> argumentList) {
        return ParentImpl.quoteBinary(elixirHeredoc, metadata, argumentList);
    }

    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quoteBinary(Sigil sigil, OtpErlangList metadata, List<OtpErlangObject> argumentList) {
        return ParentImpl.quoteBinary(metadata, argumentList);
    }

    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quoteEmpty(Quote quote) {
        return ParentImpl.quoteEmpty(quote);
    }

    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quoteEmpty(Sigil sigil) {
        return ParentImpl.quoteEmpty();
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quoteInterpolation(Quote quote, ElixirInterpolation interpolation) {
        return ParentImpl.quoteInterpolation(quote, interpolation);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quoteInterpolation(Sigil sigil, ElixirInterpolation interpolation) {
        return ParentImpl.quoteInterpolation(interpolation);
    }

    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quoteLiteral(Quote quote, List<Integer> codePointList) {
        return ParentImpl.quoteLiteral(quote, codePointList);
    }

    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quoteLiteral(Sigil sigil, List<Integer> codePointList) {
        return ParentImpl.quoteLiteral(codePointList);
    }

    @RequiresReadLock
    @Contract(pure = true)
    public static int resolvedFinalArity(@NotNull Call call) {
        return CallImpl.resolvedFinalArity(call);
    }

    @RequiresReadLock
    @Contract(pure = true)
    public static int resolvedFinalArity(@NotNull org.elixir_lang.psi.call.StubBased<Stub> stubBased) {
        return CallImpl.resolvedFinalArity(stubBased);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static ArityInterval resolvedFinalArityInterval(@NotNull Call call) {
        return CallImpl.resolvedFinalArityInterval(call);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static String resolvedModuleName(@NotNull AtUnqualifiedNoParenthesesCall atUnqualifiedNoParenthesesCall) {
        return CallImpl.resolvedModuleName(atUnqualifiedNoParenthesesCall);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static String resolvedModuleName(@NotNull DotCall dotCall) {
        return CallImpl.resolvedModuleName(dotCall);
    }

    @Contract(pure = true)
    @NotNull
    public static String resolvedModuleName(@NotNull Infix infix) {
        return CallImpl.resolvedModuleName(infix);
    }

    @Contract(pure = true)
    @NotNull
    public static String resolvedModuleName(@NotNull NotIn notIn) {
        return CallImpl.resolvedModuleName(notIn);
    }

    @Contract(pure = true)
    @NotNull
    public static String resolvedModuleName(@NotNull Prefix prefix) {
        return CallImpl.resolvedModuleName(prefix);
    }

    @RequiresReadLock
    @NotNull
    public static String resolvedModuleName(@NotNull org.elixir_lang.psi.call.qualification.Qualified qualified) {
        return CallImpl.resolvedModuleName(qualified);
    }

    @NotNull
    public static String resolvedModuleName(@NotNull Unqualified unqualified) {
        return CallImpl.resolvedModuleName(unqualified);
    }

    @Contract(pure = true)
    @NotNull
    public static String resolvedModuleName(@NotNull UnqualifiedNoArgumentsCall unqualifiedNoArgumentsCall) {
        return CallImpl.resolvedModuleName(unqualifiedNoArgumentsCall);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @Nullable
    public static Integer resolvedPrimaryArity(@NotNull Call call) {
        return CallImpl.resolvedPrimaryArity(call);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @Nullable
    public static Integer resolvedSecondaryArity(@NotNull Call call) {
        return CallImpl.resolvedSecondaryArity(call);
    }

    @Contract(pure = true)
    @Nullable
    public static Quotable rightOperand(ElixirStabOperation stabOperation) {
        return stabOperation.getStabBody();
    }

    @Contract(pure = true)
    @Nullable
    public static Quotable rightOperand(Infix infix) {
        return org.elixir_lang.psi.operation.infix.Normalized.rightOperand(infix);
    }

    @Contract(pure = true)
    @Nullable
    public static Quotable rightOperand(@NotNull NotIn notIn) {
        return org.elixir_lang.psi.operation.not_in.Normalized.rightOperand(notIn);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @Nullable
    public static PsiElement[] secondaryArguments(@NotNull DotCall dotCall) {
        return CallImpl.secondaryArguments(dotCall);
    }

    @Contract(pure = true)
    @Nullable
    public static PsiElement[] secondaryArguments(@NotNull Infix infix) {
        return CallImpl.secondaryArguments(infix);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static PsiElement[] secondaryArguments(@NotNull None none) {
        return CallImpl.secondaryArguments(none);
    }

    @Contract(pure = true)
    @Nullable
    public static PsiElement[] secondaryArguments(@NotNull NotIn notIn) {
        return CallImpl.secondaryArguments(notIn);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static PsiElement[] secondaryArguments(@NotNull NoParentheses noParentheses) {
        return CallImpl.secondaryArguments(noParentheses);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @Nullable
    public static PsiElement[] secondaryArguments(@NotNull Parentheses parentheses) {
        return CallImpl.secondaryArguments(parentheses);
    }

    @Contract(pure = true, value = "_ -> null")
    @Nullable
    public static PsiElement[] secondaryArguments(@NotNull Prefix prefix) {
        return CallImpl.secondaryArguments(prefix);
    }

    @RequiresReadLock
    @Contract(pure = true)
    @Nullable
    public static Integer secondaryArity(@NotNull Call call) {
        return CallImpl.secondaryArity(call);
    }

    @NotNull
    public static PsiElement setName(@NotNull PsiElement element, @NotNull String newName) {
        return PsiNamedElementImpl.setName(element, newName);
    }

    @NotNull
    public static PsiElement setName(@NotNull final AtUnqualifiedNoParenthesesCall atUnqualifiedNoParenthesesCall,
                                     @NotNull final String newName) {
        return PsiNamedElementImpl.setName(atUnqualifiedNoParenthesesCall, newName);
    }

    @NotNull
    public static PsiElement setName(@NotNull ElixirVariable variable, @NotNull String newName) {
        return PsiNamedElementImpl.setName(variable, newName);
    }

    @RequiresReadLock
    @NotNull
    public static PsiElement setName(@NotNull final org.elixir_lang.psi.call.Named named,
                                     @NotNull final String newName) {
        return PsiNamedElementImpl.setName(named, newName);
    }

    @Nullable
    public static Integer indentation(@NotNull org.elixir_lang.psi.SigilLine sigilLine) {
        return null;
    }

    @NotNull
    public static Integer indentation(@NotNull SigilHeredocLiteral sigilHeredoc) {
        return SigilHeredocImpl.indentation(sigilHeredoc);
    }

    @NotNull
    public static String sigilDelimiter(@NotNull org.elixir_lang.psi.Sigil sigil) {
        return SigilImpl.sigilDelimiter(sigil);
    }

    @NotNull
    public static String sigilName(@NotNull org.elixir_lang.psi.Sigil sigil) {
        return SigilImpl.sigilName(sigil);
    }

    public static char terminator(@NotNull SigilLine sigilLine) {
        return SigilLineImplKt.terminator(sigilLine);
    }

    @NotNull
    public static IElementType validElementType(@NotNull ElixirBinaryDigits binaryDigits) {
        return DigitsImpl.validElementType(binaryDigits);
    }

    @Contract(pure = true)
    @NotNull
    public static IElementType validElementType(@NotNull ElixirDecimalDigits decimalDigits) {
        return DigitsImpl.validElementType(decimalDigits);
    }

    @NotNull
    public static IElementType validElementType(@NotNull ElixirHexadecimalDigits hexadecimalDigits) {
        return DigitsImpl.validElementType(hexadecimalDigits);
    }

    @NotNull
    public static IElementType validElementType(@NotNull ElixirOctalDigits octalDigits) {
        return DigitsImpl.validElementType(octalDigits);
    }

    @Nullable
    public static IElementType validElementType(@NotNull ElixirUnknownBaseDigits unknownBaseDigits) {
        return DigitsImpl.validElementType(unknownBaseDigits);
    }

    /*
     * Private static methods
     */

    @RequiresReadLock
    @Contract(pure = true)
    @NotNull
    public static OtpErlangObject quote(@NotNull Sigil sigil, @NotNull OtpErlangObject quotedContent) {
        return QuotableImpl.quote(sigil, quotedContent);
    }

    @NotNull
    public static List<Integer> addEscapedCharacterCodePoints(@NotNull Quote parent,
                                                              @Nullable List<Integer> codePointList,
                                                              @NotNull ASTNode child) {
        return ParentImpl.addEscapedCharacterCodePoints(parent, codePointList, child);
    }

    @NotNull
    public static List<Integer> addEscapedCharacterCodePoints(@NotNull Sigil parent,
                                                              @Nullable List<Integer> codePointList,
                                                              @NotNull ASTNode child) {
        return ParentImpl.addEscapedCharacterCodePoints(parent, codePointList, child);
    }

    @RequiresReadLock
    @NotNull
    public static List<Integer> addEscapedEOL(@NotNull Parent parent,
                                              @Nullable List<Integer> maybeCodePointList,
                                              @NotNull ASTNode child) {
        return ParentImpl.addEscapedEOL(parent, maybeCodePointList);
    }

    @RequiresReadLock
    @NotNull
    public static List<Integer> addEscapedTerminator(@NotNull Parent parent,
                                                     @Nullable List<Integer> maybeCodePointList,
                                                     @NotNull ASTNode child) {
        return ParentImpl.addEscapedTerminator(parent, maybeCodePointList, child);
    }

    @NotNull
    public static List<Integer> addFragmentCodePoints(@NotNull Parent parent,
                                                      @Nullable List<Integer> codePointList,
                                                      @NotNull ASTNode child) {
        return ParentImpl.addFragmentCodePoints(codePointList, child);
    }

    @NotNull
    public static List<Integer> addHexadecimalEscapeSequenceCodePoints(@NotNull Quote parent,
                                                                       @Nullable List<Integer> codePointList,
                                                                       @NotNull ASTNode child) {
        return ParentImpl.addHexadecimalEscapeSequenceCodePoints(parent, codePointList, child);
    }

    @NotNull
    public static List<Integer> addHexadecimalEscapeSequenceCodePoints(@NotNull Sigil parent,
                                                                       @Nullable List<Integer> codePointList,
                                                                       @NotNull ASTNode child) {
        return ParentImpl.addHexadecimalEscapeSequenceCodePoints(parent, codePointList, child);
    }
}
