package org.elixir_lang;

import com.intellij.lexer.FlexLexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.elixir_lang.psi.ElixirTypes;
import org.jetbrains.annotations.Nullable;

%%

// public instead of package-local to make testing easier.
%public
%class ElixirFlexLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType
%eof{  return;
%eof}

%{
  @Nullable
  private Project project = null;
  private org.elixir_lang.lexer.Stack stack = new org.elixir_lang.lexer.Stack();

  public int stackSize() {
      return stack.size();
  }

  public void clearStack() {
      stack.clear();
  }
  private void startQuote(CharSequence quotePromoterCharSequence) {
    String quotePromoter = quotePromoterCharSequence.toString();
    stack.push(quotePromoter, yystate());

    if (quotePromoter.equals("\"\"\"") || quotePromoter.equals("'''")) {
      yybegin(GROUP_HEREDOC_START);
    } else {
      yybegin(GROUP);
    }
  }

  private void handleInLastState() {
    org.elixir_lang.lexer.StackFrame stackFrame = pop();
    handleInState(stackFrame.getLastLexicalState());
  }

  private void handleInState(int nextLexicalState) {
    yypushback(yylength());
    yybegin(nextLexicalState);
  }

  private void handleLastEOL() {
    CharSequence text = yytext();
    int length = text.length();

    for (int i = length - 1; i >= 0; i--) {
      if (text.charAt(i) == '\n') {
        if (i == 0 || text.charAt(i - 1) != '\\') {
          yypushback(length - i);
          break;
        }
      }
    }

    pushAndBegin(LAST_EOL);
  }

  private boolean isGroup() {
      return stack.isGroup();
  }

  private boolean isTerminator(CharSequence terminator) {
    return stack.terminator().equals(terminator.toString());
  }

  private boolean isInterpolating() {
    return stack.isInterpolating();
  }

  private boolean isSigil() {
    return stack.isSigil();
  }

  private void nameSigil(CharSequence sigilName) {
    stack.nameSigil(sigilName.toString());
  }

  private org.elixir_lang.lexer.StackFrame pop() {
    return stack.pop();
  }

  private void setPromoter(CharSequence promoter) {
    stack.setPromoter(promoter.toString());
  }

  private IElementType sigilNameType() {
    return stack.sigilNameType();
  }

  private void popAndBegin() {
    org.elixir_lang.lexer.StackFrame stackFrame = pop();
    yybegin(stackFrame.getLastLexicalState());
  }

  // public for testing
  public void pushAndBegin(int lexicalState) {
    stack.push(yystate());

    if (lexicalState == YYINITIAL) {
        throw new org.elixir_lang.lexer.NestedInitial(stack);
    }

    yybegin(lexicalState);
  }

  @Nullable
  public Project getProject() {
    return project;
  }

  public void setProject(@Nullable Project project) {
    this.project = project;
  }
%}

/*
 * Curly / Tuple
 */

OPENING_CURLY = "{"
CLOSING_CURLY = "}"

/*
 * Operator
 *
 * Note: before Atom because operator prefixed by {COLON} are valid Atoms
 */

FOUR_TOKEN_BITSTRING_OPERATOR = "<<>>"
/* Only ever reached as an operator atom, `:..//`, because {OPERATOR} is used in exactly one rule -
   the <ATOM_START> one that returns ATOM_FRAGMENT.  A stepped range in expression position is still
   {RANGE_OPERATOR} and {TERNARY_OPERATOR} separately, as `1..10//2` never puts the four characters
   next to each other.  Elixir accepts `:..//` but rejects `://`, so this is one operator and not two. */
FOUR_TOKEN_STEPPED_RANGE_OPERATOR = "..//"
FOUR_TOKEN_WHEN_OPERATOR = "when"
FOUR_TOKEN_OPERATOR = {FOUR_TOKEN_BITSTRING_OPERATOR} |
                      {FOUR_TOKEN_STEPPED_RANGE_OPERATOR} |
                      {FOUR_TOKEN_WHEN_OPERATOR}

THREE_TOKEN_AND_SYMBOL_OPERATOR = "&&&"
THREE_TOKEN_AND_WORD_OPERATOR = "and"
THREE_TOKEN_ARROW_OPERATOR = "<<<" |
                             "<<~" |
                             "<|>" |
                             "<~>" |
                             ">>>" |
                             "~>>"
THREE_TOKEN_COMPARISON_OPERATOR = "!==" |
                                  "==="
THREE_TOKEN_MAP_OPERATOR = "%" {OPENING_CURLY} {CLOSING_CURLY}
THREE_TOKEN_OR_SYMBOL_OPERATOR = "|||"
// https://github.com/elixir-lang/elixir/commit/3487d00ddb5e90c7cf0e65d03717903b9b27eafd
THREE_TOKEN_THREE_OPERATOR = "^^^"
// Custom operators Elixir parses into the same class as ++, -- and <> without defining them.
// https://github.com/elixir-lang/elixir/pull/9954
THREE_TOKEN_TWO_OPERATOR = "+++" |
                           "---"
THREE_TOKEN_NOT_OPERATOR = "not"
THREE_TOKEN_UNARY_OPERATOR = "~~~"

THREE_TOKEN_OPERATOR = {THREE_TOKEN_AND_SYMBOL_OPERATOR} |
                       {THREE_TOKEN_AND_WORD_OPERATOR} |
                       {THREE_TOKEN_ARROW_OPERATOR} |
                       {THREE_TOKEN_COMPARISON_OPERATOR} |
                       {THREE_TOKEN_MAP_OPERATOR} |
                       {THREE_TOKEN_OR_SYMBOL_OPERATOR} |
                       {THREE_TOKEN_THREE_OPERATOR} |
                       {THREE_TOKEN_TWO_OPERATOR} |
                       {THREE_TOKEN_UNARY_OPERATOR} |
                       {THREE_TOKEN_NOT_OPERATOR} |
                       "..."

TWO_TOKEN_AND_SYMBOL_OPERATOR = "&&"
TWO_TOKEN_ARROW_OPERATOR = "<~" |
                           "|>" |
                           "~>"
TWO_TOKEN_ASSOCIATION_OPERATOR = "=>"
TWO_TOKEN_COMPARISON_OPERATOR = "!=" |
                                "==" |
                                "=~"
TWO_TOKEN_IN_MATCH_OPERATOR = "<-" |
                              "\\\\"
TWO_TOKEN_OR_WORD_OPERATOR = "or"
TWO_TOKEN_OR_SYMBOL_OPERATOR = "||"
TWO_TOKEN_POWER_OPERATOR = "**"
TWO_TOKEN_RANGE_OPERATOR = ".."
TWO_TOKEN_RELATIONAL_OPERATOR = "<=" |
                                ">="
TWO_TOKEN_STAB_OPERATOR = "->"
TWO_TOKEN_TERNARY_OPERATOR = "//"
TWO_TOKEN_TUPLE_OPERATOR = {OPENING_CURLY} {CLOSING_CURLY}
TWO_TOKEN_TWO_OPERATOR = "++" |
                         "--" |
                         "<>"
TWO_TOKEN_TYPE_OPERATOR = "::"

TWO_TOKEN_OPERATOR = {TWO_TOKEN_AND_SYMBOL_OPERATOR} |
                     {TWO_TOKEN_ARROW_OPERATOR} |
                     {TWO_TOKEN_ASSOCIATION_OPERATOR} |
                     {TWO_TOKEN_COMPARISON_OPERATOR} |
                     {TWO_TOKEN_IN_MATCH_OPERATOR} |
                     {TWO_TOKEN_OR_WORD_OPERATOR} |
                     {TWO_TOKEN_OR_SYMBOL_OPERATOR} |
                     {TWO_TOKEN_POWER_OPERATOR} |
                     {TWO_TOKEN_RANGE_OPERATOR} |
                     {TWO_TOKEN_RELATIONAL_OPERATOR} |
                     {TWO_TOKEN_STAB_OPERATOR} |
                     {TWO_TOKEN_TERNARY_OPERATOR} |
                     {TWO_TOKEN_TUPLE_OPERATOR} |
                     {TWO_TOKEN_TWO_OPERATOR} |
                     {TWO_TOKEN_TYPE_OPERATOR}

ONE_TOKEN_ADDITION_OPERATOR = "+"
ONE_TOKEN_AT_OPERATOR = "@"
ONE_TOKEN_CAPTURE_OPERATOR = "&"
ONE_TOKEN_DIVISION_OPERATOR = "/"
ONE_TOKEN_DOT_OPERATOR = "."
ONE_TOKEN_IN_OPERATOR = "in"
ONE_TOKEN_MATCH_OPERATOR = "="
ONE_TOKEN_MINUS_OPERATOR = "-"
ONE_TOKEN_MULTIPLICATION_OPERATOR = "*"
ONE_TOKEN_NEGATE_OPERATOR = "-"
ONE_TOKEN_NUMBER_OR_BADARITH_OPERATOR = "+"
ONE_TOKEN_PIPE_OPERATOR = "|"
ONE_TOKEN_PLUS_OPERATOR = "+"
ONE_TOKEN_RELATIONAL_OPERATOR = "<" |
                                ">"
ONE_TOKEN_SIGN_OPERATOR = "+" |
                          "-"
ONE_TOKEN_STRUCT_OPERATOR = "%"
ONE_TOKEN_SUBTRACTION_OPERATOR = "-"
ONE_TOKEN_UNARY_OPERATOR = "!" |
                           "^"
ONE_TOKEN_REFERENCABLE_OPERATOR = {ONE_TOKEN_AT_OPERATOR} |
                                  {ONE_TOKEN_CAPTURE_OPERATOR} |
                                  {ONE_TOKEN_DIVISION_OPERATOR} |
                                  {ONE_TOKEN_IN_OPERATOR} |
                                  {ONE_TOKEN_MATCH_OPERATOR} |
                                  {ONE_TOKEN_MINUS_OPERATOR} |
                                  {ONE_TOKEN_MULTIPLICATION_OPERATOR} |
                                  {ONE_TOKEN_NUMBER_OR_BADARITH_OPERATOR} |
                                  {ONE_TOKEN_PIPE_OPERATOR} |
                                  {ONE_TOKEN_PLUS_OPERATOR} |
                                  {ONE_TOKEN_RELATIONAL_OPERATOR} |
                                  {ONE_TOKEN_SUBTRACTION_OPERATOR} |
                                  {ONE_TOKEN_UNARY_OPERATOR}
ONE_TOKEN_UNREFERENCABLE_OPERATOR = {ONE_TOKEN_DOT_OPERATOR} |
                                    {ONE_TOKEN_STRUCT_OPERATOR}
ONE_TOKEN_OPERATOR = {ONE_TOKEN_REFERENCABLE_OPERATOR} |
                     {ONE_TOKEN_UNREFERENCABLE_OPERATOR}

ADDITION_OPERATOR = {ONE_TOKEN_ADDITION_OPERATOR}
AND_SYMBOL_OPERATOR = {THREE_TOKEN_AND_SYMBOL_OPERATOR} |
                      {TWO_TOKEN_AND_SYMBOL_OPERATOR}
AND_WORD_OPERATOR = {THREE_TOKEN_AND_WORD_OPERATOR}
ARROW_OPERATOR = {THREE_TOKEN_ARROW_OPERATOR} |
                 {TWO_TOKEN_ARROW_OPERATOR}
ASSOCIATION_OPERATOR = {TWO_TOKEN_ASSOCIATION_OPERATOR}
AT_OPERATOR = {ONE_TOKEN_AT_OPERATOR}
BIT_STRING_OPERATOR = {FOUR_TOKEN_BITSTRING_OPERATOR}
CAPTURE_OPERATOR = {ONE_TOKEN_CAPTURE_OPERATOR}
DIVISION_OPERATOR = {ONE_TOKEN_DIVISION_OPERATOR}
DOT_OPERATOR = {ONE_TOKEN_DOT_OPERATOR}
COMPARISON_OPERATOR = {THREE_TOKEN_COMPARISON_OPERATOR} |
                      {TWO_TOKEN_COMPARISON_OPERATOR}
IN_MATCH_OPERATOR = {TWO_TOKEN_IN_MATCH_OPERATOR}
IN_OPERATOR = {ONE_TOKEN_IN_OPERATOR}
MAP_OPERATOR = {THREE_TOKEN_MAP_OPERATOR}
MATCH_OPERATOR = {ONE_TOKEN_MATCH_OPERATOR}
MINUS_OPERATOR = {ONE_TOKEN_MINUS_OPERATOR}
MULTIPLICATION_OPERATOR = {ONE_TOKEN_MULTIPLICATION_OPERATOR}
NEGATE_OPERATOR = {ONE_TOKEN_NEGATE_OPERATOR}
NUMBER_OR_BADARITH_OPERATOR = {ONE_TOKEN_NUMBER_OR_BADARITH_OPERATOR}
NOT_OPERATOR = {THREE_TOKEN_NOT_OPERATOR}
// The trailing character keeps `not input` from matching.
NOT_IN_OPERATOR = {NOT_OPERATOR}{WHITE_SPACE}+{IN_OPERATOR}[^[:letter:][:digit:]_?!]
OR_WORD_OPERATOR = {TWO_TOKEN_OR_WORD_OPERATOR}
OR_SYMBOL_OPERATOR = {THREE_TOKEN_OR_SYMBOL_OPERATOR} |
                     {TWO_TOKEN_OR_SYMBOL_OPERATOR}
PIPE_OPERATOR = {ONE_TOKEN_PIPE_OPERATOR}
PLUS_OPERATOR = {ONE_TOKEN_PLUS_OPERATOR}
POWER_OPERATOR = {TWO_TOKEN_POWER_OPERATOR}
RANGE_OPERATOR = {TWO_TOKEN_RANGE_OPERATOR}
RELATIONAL_OPERATOR = {TWO_TOKEN_RELATIONAL_OPERATOR} |
                      {ONE_TOKEN_RELATIONAL_OPERATOR}
SIGN_OPERATOR = {ONE_TOKEN_SIGN_OPERATOR}
STAB_OPERATOR = {TWO_TOKEN_STAB_OPERATOR}
TERNARY_OPERATOR = {TWO_TOKEN_TERNARY_OPERATOR}
STRUCT_OPERATOR = {ONE_TOKEN_STRUCT_OPERATOR}
SUBTRACTION_OPERATOR = {ONE_TOKEN_SUBTRACTION_OPERATOR}
// https://github.com/elixir-lang/elixir/commit/3487d00ddb5e90c7cf0e65d03717903b9b27eafd
THREE_OPERATOR = {THREE_TOKEN_THREE_OPERATOR}
TUPLE_OPERATOR = {TWO_TOKEN_TUPLE_OPERATOR}
TWO_OPERATOR = {TWO_TOKEN_TWO_OPERATOR} |
               {THREE_TOKEN_TWO_OPERATOR}
TYPE_OPERATOR = {TWO_TOKEN_TYPE_OPERATOR}
UNARY_OPERATOR = {THREE_TOKEN_UNARY_OPERATOR} |
                 {ONE_TOKEN_UNARY_OPERATOR}
WHEN_OPERATOR = {FOUR_TOKEN_WHEN_OPERATOR}

REFERENCABLE_OPERATOR = {FOUR_TOKEN_OPERATOR} |
                        {THREE_TOKEN_OPERATOR} |
                        {TWO_TOKEN_OPERATOR} |
                        {ONE_TOKEN_REFERENCABLE_OPERATOR}
// OPERATOR is from longest to shortest so longest match wins
OPERATOR = {FOUR_TOKEN_OPERATOR} |
           {THREE_TOKEN_OPERATOR} |
           {TWO_TOKEN_OPERATOR} |
           {ONE_TOKEN_OPERATOR}

/*
 * Atom
 */

ATOM_END = [?!]
ATOM_MIDDLE = [0-9a-zA-Z@_]
UNICODE_ATOM_MIDDLE = [[:letter:][:digit:]\p{Mn}\p{Mc}]
ATOM_START = [a-zA-Z_]
UNICODE_ATOM_START = [[:letter:]]
ATOM = ({ATOM_START} | {UNICODE_ATOM_START}) ({ATOM_MIDDLE} | {UNICODE_ATOM_MIDDLE})* {ATOM_END}? | "..."
COLON = :

/*
 * Block Identifiers
 */

AFTER = "after"
CATCH = "catch"
ELSE = "else"
RESCUE = "rescue"

/*
 * Containers
 */

COMMA = ","

/*
 * Digits
 */

HEXADECIMAL_DIGIT = [A-Fa-f0-9]

/*
 * EOE (End of Expression)
 */

SEMICOLON = ";"

/*
 * EOL
 */

EOL = \n|\r\n

/*
 * Escape Sequences
 */

ESCAPE = "\\"

UNICODE_ESCAPE_CHARACTER = "u"
ESCAPED_EOL = {ESCAPE} {EOL}

/*
 * Char tokens
 */

CHAR_TOKENIZER = "?"

/*
 * White Space
 */

HORIZONTAL_SPACE = [ \t]
VERTICAL_SPACE = [\n\r]
SPACE = {HORIZONTAL_SPACE} | {VERTICAL_SPACE}
WHITE_SPACE=[\ \t\f]
MULTILINE_WHITE_SPACE = ({ESCAPED_EOL} | {EOL} | {WHITE_SPACE})+
LAST_EOL = {MULTILINE_WHITE_SPACE}? {EOL} ({ESCAPED_EOL} | {WHITE_SPACE})*

/*
 *  Comments
 */

COMMENT = "#" [^\r\n]*

/*
 *
 *   Whole Numbers
 *
 */

VALID_DECIMAL_DIGITS = [0-9]+
INVALID_DECIMAL_DIGITS = [A-Za-z]+

NUMBER_SEPARATOR = "_"

/*
 * Non-Base-10
 */

BASE_WHOLE_NUMBER_PREFIX = "0"
BASE_WHOLE_NUMBER_BASE = [A-Za-z]

BINARY_WHOLE_NUMBER_BASE = "b"
OBSOLETE_BINARY_WHOLE_NUMBER_BASE = "B"
VALID_BINARY_DIGITS = [01]+
INVALID_BINARY_DIGITS = [A-Za-z2-9]+

HEXADECIMAL_WHOLE_NUMBER_BASE = "x"
OBSOLETE_HEXADECIMAL_WHOLE_NUMBER_BASE = "X"
VALID_HEXADECIMAL_DIGITS = {HEXADECIMAL_DIGIT}+
INVALID_HEXADECIMAL_DIGITS = [G-Zg-z]+

OCTAL_WHOLE_NUMBER_BASE = "o"
VALID_OCTAL_DIGITS = [0-7]+
INVALID_OCTAL_DIGITS = [A-Za-z8-9]+

INVALID_UNKNOWN_BASE_DIGITS = [A-Za-z0-9]+

/*
 * Identifiers
 */

IDENTIFIER_TOKEN_END = [?!]
IDENTIFIER_TOKEN_MIDDLE = [0-9a-zA-Z_]
IDENTIFIER_TOKEN_START = [a-z_]
// Unicode identifier start: all Unicode letters EXCEPT uppercase (CJK ideographs are Lo "Letter, Other").
// ASCII uppercase is handled by {ALIAS} rules; non-ASCII uppercase is atoms in Elixir, not identifiers.
// Uses JFlex character class subtraction (--) to exclude [:uppercase:] from [:letter:].
UNICODE_IDENTIFIER_START = [[:letter:]--[:uppercase:]]
// Unicode identifier continue: all Unicode letters, digits and combining marks.
UNICODE_IDENTIFIER_CONTINUE = [[:letter:][:digit:]\p{Mn}\p{Mc}]
IDENTIFIER_TOKEN_HEAD = {IDENTIFIER_TOKEN_START} | {UNICODE_IDENTIFIER_START}
IDENTIFIER_TOKEN_TAIL = ({IDENTIFIER_TOKEN_MIDDLE} | {UNICODE_IDENTIFIER_CONTINUE})* {IDENTIFIER_TOKEN_END}?
IDENTIFIER_TOKEN = ({IDENTIFIER_TOKEN_HEAD} {IDENTIFIER_TOKEN_TAIL}  | "...")

/*
 * Aliases
 */

ALIAS_HEAD = [A-Z]
ALIAS = {ALIAS_HEAD} {IDENTIFIER_TOKEN_TAIL}

/*
 * Bit Strings
 */

CLOSING_BIT = ">>"
OPENING_BIT = "<<"

/*
 * Parent
 */

INTERPOLATION_START = "#{"
INTERPOLATION_END = "}"

/*
 * Floats
 */

DECIMAL_MARK = "."
EXPONENT_MARK = [Ee]

/*
 * List
 */

CLOSING_BRACKET = "]"
OPENING_BRACKET = "["

/*
 * Parentheses
 */

CLOSING_PARENTHESIS = ")"
OPENING_PARENTHESIS = "("

OPENING = {OPENING_BIT} | {OPENING_BRACKET} | {OPENING_CURLY} | {OPENING_PARENTHESIS}

/*
 *
 *  Quotes
 *
 */

CHAR_LIST_PROMOTER = "'"
CHAR_LIST_TERMINATOR = "'"

STRING_PROMOTER = "\""
STRING_TERMINATOR = "\""

QUOTE_PROMOTER = {CHAR_LIST_PROMOTER} | {STRING_PROMOTER}
QUOTE_TERMINATOR = {CHAR_LIST_TERMINATOR} | {STRING_TERMINATOR}

/*
 * Quote Heredocs
 */

CHAR_LIST_HEREDOC_PROMOTER = {CHAR_LIST_PROMOTER}{3}
CHAR_LIST_HEREDOC_TERMINATOR = {CHAR_LIST_TERMINATOR}{3}

STRING_HEREDOC_PROMOTER = {STRING_PROMOTER}{3}
STRING_HEREDOC_TERMINATOR = {STRING_TERMINATOR}{3}

QUOTE_HEREDOC_PROMOTER = {CHAR_LIST_HEREDOC_PROMOTER} | {STRING_HEREDOC_PROMOTER}
QUOTE_HEREDOC_TERMINATOR = {CHAR_LIST_HEREDOC_TERMINATOR} | {STRING_HEREDOC_TERMINATOR}

/*
 * Function References
 */

REFERENCE_INFIX_OPERATOR = ({WHITE_SPACE}|{EOL}|{ESCAPED_EOL})*{DIVISION_OPERATOR}

/*
 * Regular Keywords
 */

DO = "do"
END = "end"
FALSE = "false"
FN = "fn"
NIL = "nil"
TRUE = "true"

/*
 *
 *  Sigils
 *
 */

TILDE = "~"
SIGIL_MODIFIER = [A-Za-z]
// Multi-letter uppercase names are from Elixir 1.15 and digits in them from 1.17; both are errors before, so no
// language level.
SIGIL_NAME = [a-z] | [A-Z][A-Z0-9]*

/*
 * Sigil quotes
 *
 * @see https://github.com/elixir-lang/elixir/blob/a4a23b9a937cb22b5c1a2487c02289817b991d8f/lib/elixir/src/elixir.hrl#L59-L60
 */

SIGIL_BRACES_PROMOTER = "{"
SIGIL_BRACES_TERMINATOR = "}"
SIGIL_BRACKETS_PROMOTER = "["
SIGIL_BRACKETS_TERMINATOR = "]"
SIGIL_CHEVRONS_PROMOTER = "<"
SIGIL_CHEVRONS_TERMINATOR = ">"
SIGIL_DOUBLE_QUOTES_PROMOTER = "\""
SIGIL_DOUBLE_QUOTES_TERMINATOR = "\""
SIGIL_FORWARD_SLASH_PROMOTER = "/"
SIGIL_FORWARD_SLASH_TERMINATOR = "/"
SIGIL_PARENTHESES_PROMOTER = "("
SIGIL_PARENTHESES_TERMINATOR = ")"
SIGIL_PIPE_PROMOTER = "|"
SIGIL_PIPE_TERMINATOR = "|"
SIGIL_SINGLE_QUOTES_PROMOTER = "'"
SIGIL_SINGLE_QUOTES_TERMINATOR = "'"

SIGIL_PROMOTER = {SIGIL_BRACES_PROMOTER} |
                 {SIGIL_BRACKETS_PROMOTER} |
                 {SIGIL_CHEVRONS_PROMOTER} |
                 {SIGIL_DOUBLE_QUOTES_PROMOTER} |
                 {SIGIL_FORWARD_SLASH_PROMOTER} |
                 {SIGIL_PARENTHESES_PROMOTER} |
                 {SIGIL_PIPE_PROMOTER} |
                 {SIGIL_SINGLE_QUOTES_PROMOTER}


SIGIL_TERMINATOR = {SIGIL_BRACES_TERMINATOR} |
                   {SIGIL_BRACKETS_TERMINATOR} |
                   {SIGIL_CHEVRONS_TERMINATOR} |
                   {SIGIL_DOUBLE_QUOTES_TERMINATOR} |
                   {SIGIL_FORWARD_SLASH_TERMINATOR} |
                   {SIGIL_PARENTHESES_TERMINATOR} |
                   {SIGIL_PIPE_TERMINATOR} |
                   {SIGIL_SINGLE_QUOTES_TERMINATOR}

/*
 * Sigil heredocs
 */

SIGIL_DOUBLE_QUOTES_HEREDOC_PROMOTER = {SIGIL_DOUBLE_QUOTES_PROMOTER}{3}
SIGIL_SINGLE_QUOTES_HEREDOC_PROMOTER = {SIGIL_SINGLE_QUOTES_PROMOTER}{3}

SIGIL_HEREDOC_PROMOTER = {SIGIL_DOUBLE_QUOTES_HEREDOC_PROMOTER}|{SIGIL_SINGLE_QUOTES_HEREDOC_PROMOTER}

SIGIL_DOUBLE_QUOTES_HEREDOC_TERMINATOR = {SIGIL_DOUBLE_QUOTES_TERMINATOR}{3}
SIGIL_SINGLE_QUOTES_HEREDOC_TERMINATOR = {SIGIL_SINGLE_QUOTES_TERMINATOR}{3}

SIGIL_HEREDOC_TERMINATOR = {SIGIL_DOUBLE_QUOTES_HEREDOC_TERMINATOR}|{SIGIL_SINGLE_QUOTES_HEREDOC_TERMINATOR}

/*
 * Groups
 */

GROUP_TERMINATOR = {QUOTE_TERMINATOR}|{SIGIL_TERMINATOR}
GROUP_HEREDOC_TERMINATOR = {QUOTE_HEREDOC_TERMINATOR}|{SIGIL_HEREDOC_TERMINATOR}

// Unlike `.`, also matches U+000B, U+000C, U+0085, U+2028 and U+2029, which may appear in strings and sigils.
ANY = [^]
EOL_INSENSITIVE = {AND_SYMBOL_OPERATOR} |
                  {AND_WORD_OPERATOR} |
                  {ARROW_OPERATOR} |
                  {ASSOCIATION_OPERATOR} |
                  {COMMENT} |
                  {CLOSING_BIT} |
                  {CLOSING_BRACKET} |
                  {CLOSING_CURLY}  |
                  {CLOSING_PARENTHESIS} |
                  {COMPARISON_OPERATOR} |
                  {DIVISION_OPERATOR} |
                  {DO} |
                  {DOT_OPERATOR} |
                  {END} |
                  {IN_MATCH_OPERATOR} |
                  {IN_OPERATOR} |
                  {MATCH_OPERATOR} |
                  {MULTIPLICATION_OPERATOR} |
                  {NOT_IN_OPERATOR} |
                  {PIPE_OPERATOR} |
                  {POWER_OPERATOR} |
                  {RANGE_OPERATOR} |
                  {RELATIONAL_OPERATOR} |
                  {SEMICOLON} |
                  {STAB_OPERATOR} |
                  {TERNARY_OPERATOR} |
                  {THREE_OPERATOR} |
                  {TWO_OPERATOR} |
                  {TYPE_OPERATOR} |
                  {WHEN_OPERATOR} |
                  {OR_SYMBOL_OPERATOR} |
                  {OR_WORD_OPERATOR}

/*
 *  States - Ordered lexigraphically
 */

%state ADDITION_OR_KEYWORD_PAIR_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE
%state ADDITION_OR_SUBTRACTION_MAYBE
%state ADDITION_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE
%state AFTER_IDENTIFIER_WHITE_SPACE
%state AFTER_RELATIVE_IDENTIFIER
%state AFTER_UNQUALIFIED_IDENTIFIER
%state ATOM_START
%state BASE_WHOLE_NUMBER_BASE
%state BINARY_WHOLE_NUMBER
%state CALL_MAYBE
%state CHAR_TOKENIZATION
%state DECIMAL_EXPONENT
%state DECIMAL_EXPONENT_SIGN
%state DECIMAL_FRACTION
%state DECIMAL_WHOLE_NUMBER
%state DOT_OPERATION
%state ESCAPE_IN_LITERAL
%state ESCAPE_SEQUENCE
%state EXTENDED_HEXADECIMAL_ESCAPE_SEQUENCE
%state GROUP
%state GROUP_HEREDOC_END
%state GROUP_HEREDOC_LINE_BODY
%state GROUP_HEREDOC_LINE_ESCAPED_EOL
%state GROUP_HEREDOC_LINE_START
%state GROUP_HEREDOC_START
%state HEXADECIMAL_ESCAPE_SEQUENCE
%state HEXADECIMAL_WHOLE_NUMBER
%state INTERPOLATION
%state INTERPOLATION_CURLY
%state KEYWORD_PAIR_MAYBE
%state KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE
%state LAST_EOL
%state MULTILINE_WHITE_SPACE_MAYBE
%state NAMED_SIGIL
%state OCTAL_WHOLE_NUMBER
%state REFERENCE_OPERATION
%state SIGIL
%state SIGIL_MODIFIERS
%state UNICODE_ESCAPE_SEQUENCE
%state UNKNOWN_BASE_WHOLE_NUMBER

%%

/* <YYINITIAL> is first even though it isn't lexicographically first because it is the first state.
   Rules that aren't dependent on detecting the end of INTERPOLATION can be shared between <YYINITIAL>,
   <INTERPOLATION>, and <INTERPOLATION_CURLY> */
<YYINITIAL, INTERPOLATION, INTERPOLATION_CURLY> {
  {AFTER}                                    { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.AFTER; }
  // Three tokens, so that the parser can read `..//: 1` as `..(/([/: 1]))` for Elixir before 1.12.0
  {RANGE_OPERATOR} / {DIVISION_OPERATOR}{DIVISION_OPERATOR}{COLON}{SPACE} { pushAndBegin(KEYWORD_PAIR_MAYBE);
                                                                          return ElixirTypes.RANGE_OPERATOR; }
  {DIVISION_OPERATOR} / {DIVISION_OPERATOR}{COLON}{SPACE}                 { pushAndBegin(KEYWORD_PAIR_MAYBE);
                                                                          return ElixirTypes.DIVISION_OPERATOR; }
  // Must be before {DIVISION_OPERATOR} as it is a prefix of {STEP_OPERATOR}
  // Must be before `{REFERENCABLE_OPERATOR} / {REFERENCE_INFIX_OPERATOR}` because a reference to division will be `//2`, which is no longer valid in Elixir 1.13
  {TERNARY_OPERATOR}                         { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.TERNARY_OPERATOR; }
  // Must be before any single operator's match
  // `&` before `/`, `/` captures the `//` operator rather than dividing `&`
  {CAPTURE_OPERATOR} / ({WHITE_SPACE}|{ESCAPED_EOL})*{DIVISION_OPERATOR}({WHITE_SPACE}|{ESCAPED_EOL})*{DIVISION_OPERATOR} { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                                                                                                          return ElixirTypes.CAPTURE_OPERATOR; }
  {REFERENCABLE_OPERATOR} / {REFERENCE_INFIX_OPERATOR} { pushAndBegin(REFERENCE_OPERATION);
                                                         return ElixirTypes.IDENTIFIER_TOKEN; }
  {AND_SYMBOL_OPERATOR}                      { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.AND_SYMBOL_OPERATOR; }
  {AND_WORD_OPERATOR}                        { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.AND_WORD_OPERATOR; }
  {ARROW_OPERATOR}                           { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.ARROW_OPERATOR; }
  {ASSOCIATION_OPERATOR}                     { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.ASSOCIATION_OPERATOR; }
  {ALIAS}                                    { pushAndBegin(ADDITION_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.ALIAS_TOKEN; }
  {AT_OPERATOR}                              { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.AT_OPERATOR; }
  {ATOM} / {COLON}{SPACE}                    { pushAndBegin(KEYWORD_PAIR_MAYBE);
                                               return ElixirTypes.ATOM_FRAGMENT; }
  // ATOM_FRAGMENT because keywordKey accepts no DOT_OPERATOR
  {DOT_OPERATOR} / {COLON}{SPACE}            { pushAndBegin(KEYWORD_PAIR_MAYBE);
                                               return ElixirTypes.ATOM_FRAGMENT; }
  {BASE_WHOLE_NUMBER_PREFIX} / {BASE_WHOLE_NUMBER_BASE} { pushAndBegin(BASE_WHOLE_NUMBER_BASE);
                                                          return ElixirTypes.BASE_WHOLE_NUMBER_PREFIX; }
  /* For bitString rule, OPENING_BIT will be lexed.  This is just for when the operator needs to be one token for
     keywords key */
  {BIT_STRING_OPERATOR} / {COLON}{SPACE}     { // Definitely a Keyword pair, but KEYWORD_PAIR_MAYBE state will still work.
                                               pushAndBegin(KEYWORD_PAIR_MAYBE);
                                               return ElixirTypes.BIT_STRING_OPERATOR; }
  {CATCH}                                    { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.CATCH; }
  {CAPTURE_OPERATOR}                         { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.CAPTURE_OPERATOR; }
  /* Must be after {BIT_STRING_OPERATOR} as {BIT_STRING_OPERATOR} combines {OPENING_BIT} {CLOSING_BIT} and must be
     before {COMPARISON_OPERATOR} as {COMPARISON_OPERATOR} includes ">" which would match the begining of {CLOSING_BIT}
   */
  {CLOSING_BIT}                              { pushAndBegin(ADDITION_OR_SUBTRACTION_MAYBE);
                                               return ElixirTypes.CLOSING_BIT; }
  {CLOSING_BRACKET}                          { pushAndBegin(ADDITION_OR_SUBTRACTION_MAYBE);
                                               return ElixirTypes.CLOSING_BRACKET; }
  {CLOSING_PARENTHESIS}                      { pushAndBegin(ADDITION_OR_SUBTRACTION_MAYBE);
                                               return ElixirTypes.CLOSING_PARENTHESIS; }
  {DO}                                       { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.DO; }
  {ELSE}                                     { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.ELSE; }
  {EOL}                                      { return ElixirTypes.EOL; }
  {END}                                      { pushAndBegin(KEYWORD_PAIR_MAYBE);
                                               return ElixirTypes.END; }
  {ESCAPED_EOL}|{WHITE_SPACE}+                     { return TokenType.WHITE_SPACE; }
  {MULTILINE_WHITE_SPACE} / {EOL_INSENSITIVE}      { return TokenType.WHITE_SPACE; }
  {LAST_EOL} / {REFERENCABLE_OPERATOR}{REFERENCE_INFIX_OPERATOR} { handleLastEOL();
                                                                   return TokenType.WHITE_SPACE; }
  {LAST_EOL}              / {OPENING_BIT}          { handleLastEOL();
                                                     return TokenType.WHITE_SPACE; }
  // Needs to be explicit, so that an atom of the type operator is longer
  {LAST_EOL} / ":::"                               { handleLastEOL();
                                                     return TokenType.WHITE_SPACE; }
  {LAST_EOL} / {IDENTIFIER_TOKEN}                  { handleLastEOL();
                                                     return TokenType.WHITE_SPACE; }
  {CHAR_TOKENIZER}                                      { pushAndBegin(ADDITION_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE);
                                                          pushAndBegin(CHAR_TOKENIZATION);
                                                          return ElixirTypes.CHAR_TOKENIZER; }
  /* So that that atom of comparison operator consumes all 3 ':' instead of {TYPE_OPERATOR} consuming '::'
     and ':' being leftover */
  {COLON} / {TYPE_OPERATOR}                  { pushAndBegin(ATOM_START);
                                               return ElixirTypes.COLON; }
  {COLON} / {SPACE}                          { return ElixirTypes.COLON; }
  // Must be after `{COLON} / {TYPE_OPERATOR}`, so that 3 ':' are consumed before 1.
  {TYPE_OPERATOR}                            { pushAndBegin(MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.TYPE_OPERATOR; }
  // Must be after {TYPE_OPERATOR}, so that 1 ':' is consumed after 2
  {COLON}                                    { pushAndBegin(ATOM_START);
                                               return ElixirTypes.COLON; }
  {COMMA}                                    { return ElixirTypes.COMMA; }
  {COMMENT}                                  { return ElixirTypes.COMMENT; }
  /* Must be after {BIT_STRING_OPERATOR} as {BIT_STRING_OPERATOR} combines {OPENING_BIT} {CLOSING_BIT}; must be after
     {ARROW_OPERATOR} because {ARROW_OPERATOR} includes "<<<", which is a longer match than {OPENING_BIT}'s "<<"; and
     must be before {COMPARISON_OPERATOR} as {COMPARISON_OPERATOR} includes "<" which would match the begining of
     {OPENING_BIT}. */
  {OPENING_BIT}                              { pushAndBegin(MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.OPENING_BIT; }
  {COMPARISON_OPERATOR}                      { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.COMPARISON_OPERATOR; }

  {DIVISION_OPERATOR}                        { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.DIVISION_OPERATOR; }
  // DOT_OPERATOR is not a valid keywordKey, so no need to go to KEYWORD_PAIR_MAYBE
  {DOT_OPERATOR}                             { pushAndBegin(DOT_OPERATION);
                                               return ElixirTypes.DOT_OPERATOR; }
  {FALSE}                                    { pushAndBegin(ADDITION_OR_KEYWORD_PAIR_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.FALSE; }
  {FN}                                       { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.FN; }
  {OPENING_BRACKET}                          { pushAndBegin(MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.OPENING_BRACKET; }
  /* Must be before {OPENING_CURLY} so entire "{}" is matched instead of just "{"

     For tuple rule, OPENING_CURLY will be lexed.  This is just for when the operator needs to be one token for
     keywords key */
  {TUPLE_OPERATOR} / {COLON}{SPACE}          { // Definitely a Keyword pair, but KEYWORD_PAIR_MAYBE state will still work.
                                               pushAndBegin(KEYWORD_PAIR_MAYBE);
                                               return ElixirTypes.TUPLE_OPERATOR; }
  {OPENING_PARENTHESIS}                      { pushAndBegin(MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.OPENING_PARENTHESIS; }
  // Must be before {IDENTIFIER_TOKEN} as "in" would be parsed as an identifier since it's a lowercase alphanumeric.
  {IN_OPERATOR}                              { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.IN_OPERATOR; }
  // Must be before {IDENTIFIER_TOKEN} as "nil" would be parsed as an identifier since it's a lowercase alphanumeric.
  {NIL}                                      { pushAndBegin(ADDITION_OR_KEYWORD_PAIR_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.NIL; }
  // Must be before {IDENTIFIER_TOKEN} as "not" would be parsed as an identifier since it's a lowercase alphanumeric.
  {NOT_OPERATOR}                             { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.NOT_OPERATOR; }
  // Must be before {IDENTIFIER_TOKEN} as "or" would be parsed as an identifier since it's a lowercase alphanumeric.
  {OR_WORD_OPERATOR}                         { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.OR_WORD_OPERATOR; }
  // Must be before {IDENTIFIER_TOKEN} as "rescue" would be parsed as an identifier since it's a lowercase alphanumeric.
  {RESCUE}                                   { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.RESCUE; }
  // Must be before {IDENTIFIER_TOKEN} as "true" would be parsed as an identifier since it's a lowercase alphanumeric.
  {TRUE}                                     { pushAndBegin(ADDITION_OR_KEYWORD_PAIR_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.TRUE; }
  // Must be before {UNARY_OPERATOR} as "^^^" is longer than "^" in {UNARY_OPERATOR}
  {THREE_OPERATOR}                           { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.THREE_OPERATOR; }
  // Must be before {IDENTIFIER_TOKEN} as "when" would be parsed as an identifier since it's a lowercase alphanumeric.
  {WHEN_OPERATOR}                            { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.WHEN_OPERATOR; }
  {IDENTIFIER_TOKEN}                         { pushAndBegin(AFTER_UNQUALIFIED_IDENTIFIER);
                                               return ElixirTypes.IDENTIFIER_TOKEN; }
  {IN_MATCH_OPERATOR}                        { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.IN_MATCH_OPERATOR; }
  /* For map rule, STRUCT_OPERATOR EOL* OPENING_CURLY will be lexed.  This is just for when the operator needs to be one
     token for keywords key */
  {MAP_OPERATOR} / {COLON}{SPACE}            { // Definitely a Keyword pair, but KEYWORD_PAIR_MAYBE state will still work.
                                               pushAndBegin(KEYWORD_PAIR_MAYBE);
                                               return ElixirTypes.MAP_OPERATOR; }
  {MATCH_OPERATOR}                           { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.MATCH_OPERATOR; }
  // Must be before MULTIPLICATION_OPERATOR (*) as it is a prefix of POWER_OPERATOR (**)
  {POWER_OPERATOR}                          { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                              return ElixirTypes.POWER_OPERATOR; }
  {MULTIPLICATION_OPERATOR}                  { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.MULTIPLICATION_OPERATOR; }
  // Must be before NEGATE_OPERATOR (-) or NUMBER_OR_BADARITH_OPERATOR (+)
  // as they are both prefixes of the TWO_OPERATORs (-- and ++)
  {TWO_OPERATOR}                             { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.TWO_OPERATOR; }
  {MINUS_OPERATOR} / {COLON}{SPACE}          { // Definitely a Keyword pair, but KEYWORD_PAIR_MAYBE state will still work.
                                               pushAndBegin(KEYWORD_PAIR_MAYBE);
                                               return ElixirTypes.MINUS_OPERATOR; }
  {NEGATE_OPERATOR}                          { return ElixirTypes.NEGATE_OPERATOR; }
  {PLUS_OPERATOR} / {COLON}{SPACE}           { // Definitely a Keyword pair, but KEYWORD_PAIR_MAYBE state will still work.
                                               pushAndBegin(KEYWORD_PAIR_MAYBE);
                                               return ElixirTypes.PLUS_OPERATOR; }

  {NUMBER_OR_BADARITH_OPERATOR}              { return ElixirTypes.NUMBER_OR_BADARITH_OPERATOR; }
  {OR_SYMBOL_OPERATOR}                       { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.OR_SYMBOL_OPERATOR; }
  {PIPE_OPERATOR}                            { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.PIPE_OPERATOR; }
  // Not a multiline state: a newline after `..` ends a nullary `..`
  {RANGE_OPERATOR}                           { pushAndBegin(KEYWORD_PAIR_MAYBE);
                                               return ElixirTypes.RANGE_OPERATOR; }
  {RELATIONAL_OPERATOR}                      { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.RELATIONAL_OPERATOR; }
  {SEMICOLON}                                { pushAndBegin(MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.SEMICOLON; }
  {STAB_OPERATOR}                            { pushAndBegin(KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE);
                                               return ElixirTypes.STAB_OPERATOR; }
  {STRUCT_OPERATOR}                          { pushAndBegin(KEYWORD_PAIR_MAYBE);
                                               return ElixirTypes.STRUCT_OPERATOR; }
  {TILDE}                                    { pushAndBegin(SIGIL);
                                               return ElixirTypes.TILDE; }

  {UNARY_OPERATOR}                           { pushAndBegin(KEYWORD_PAIR_MAYBE);
                                               return ElixirTypes.UNARY_OPERATOR; }
  {VALID_DECIMAL_DIGITS}                     { pushAndBegin(DECIMAL_WHOLE_NUMBER);
                                               return ElixirTypes.VALID_DECIMAL_DIGITS; }
  {QUOTE_HEREDOC_PROMOTER}                   { pushAndBegin(ADDITION_OR_SUBTRACTION_MAYBE);
                                               startQuote(yytext());
                                               return ElixirTypes.HEREDOC_PROMOTER; }
  /* MUST be after {QUOTE_HEREDOC_PROMOTER} for <BODY, INTERPOLATION> as {QUOTE_HEREDOC_PROMOTER} is prefixed by
     {QUOTE_PROMOTER} */
  {QUOTE_PROMOTER}                           { /* return to KEYWORD_PAIR_MAYBE so that COLON after quote can be parsed
                                                  as KEYWORD_PAIR_COLON to differentiate between valid `<quote><colon>`
                                                  and invalid `<quote><space><colon>`. */
                                               pushAndBegin(ADDITION_OR_KEYWORD_PAIR_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE);
                                               startQuote(yytext());
                                               return ElixirTypes.LINE_PROMOTER; }
}

<ADDITION_OR_KEYWORD_PAIR_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE, ADDITION_OR_SUBTRACTION_MAYBE, ADDITION_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE, CALL_MAYBE> {
  {ESCAPED_EOL}|{WHITE_SPACE}+         { yybegin(ADDITION_OR_SUBTRACTION_MAYBE);
                                         return TokenType.WHITE_SPACE; }
}

<ADDITION_OR_SUBTRACTION_MAYBE, ADDITION_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE,
 AFTER_RELATIVE_IDENTIFIER, AFTER_UNQUALIFIED_IDENTIFIER,
 CALL_MAYBE> {
  // An operator keyword key, as in `f+: 1`, which the state before lexes.
  {TWO_OPERATOR} / {COLON}{SPACE}                               { handleInLastState(); }
  ({ADDITION_OPERATOR}|{SUBTRACTION_OPERATOR}) / {COLON}{SPACE} { handleInLastState(); }
  {TWO_OPERATOR}         { yybegin(MULTILINE_WHITE_SPACE_MAYBE);
                           return ElixirTypes.TWO_OPERATOR; }
  {ADDITION_OPERATOR}    { yybegin(MULTILINE_WHITE_SPACE_MAYBE);
                           return ElixirTypes.ADDITION_OPERATOR; }
  // STAB_OPERATOR is an extension of SUBTRACTION_OPERATOR, so it needs to be before SUBTRACTION_OPERATOR instead of
  // part of {ANY} to be matched preferentially
  {STAB_OPERATOR}        { handleInLastState(); }
  {SUBTRACTION_OPERATOR} { yybegin(MULTILINE_WHITE_SPACE_MAYBE);
                           return ElixirTypes.SUBTRACTION_OPERATOR; }

}

<AFTER_RELATIVE_IDENTIFIER, AFTER_UNQUALIFIED_IDENTIFIER> {
  {WHITE_SPACE}+         { yybegin(AFTER_IDENTIFIER_WHITE_SPACE);
                           return TokenType.WHITE_SPACE; }
  {ESCAPED_EOL}          { yybegin(ADDITION_OR_SUBTRACTION_MAYBE);
                           return TokenType.WHITE_SPACE; }
}

<AFTER_RELATIVE_IDENTIFIER, AFTER_UNQUALIFIED_IDENTIFIER, CALL_MAYBE> {
  {OPENING_BRACKET}|{OPENING_PARENTHESIS} { handleInLastState();
                                            // zero-width token
                                            return ElixirTypes.CALL; }
}

<AFTER_IDENTIFIER_WHITE_SPACE> {
  {ESCAPED_EOL}                                                          { return TokenType.WHITE_SPACE; }
  {ADDITION_OPERATOR} / {MULTILINE_WHITE_SPACE}                          { yybegin(MULTILINE_WHITE_SPACE_MAYBE);
                                                                           return ElixirTypes.ADDITION_OPERATOR; }
  {ADDITION_OPERATOR} / ({OPENING}|{MINUS_OPERATOR}|{STRUCT_OPERATOR})   { popAndBegin();
                                                                           return ElixirTypes.ADDITION_OPERATOR; }
  {SUBTRACTION_OPERATOR} / {MULTILINE_WHITE_SPACE}                       { yybegin(MULTILINE_WHITE_SPACE_MAYBE);
                                                                           return ElixirTypes.SUBTRACTION_OPERATOR; }
  {SUBTRACTION_OPERATOR} / ({OPENING}|{PLUS_OPERATOR}|{STRUCT_OPERATOR}) { popAndBegin();
                                                                           return ElixirTypes.SUBTRACTION_OPERATOR; }
}

/*
 *  Lexical rules - Ordered alphabetically by state name except when the order of the internal rules need to be
 *  maintained to ensure precedence.
 */

/// Must be after {QUOTE_PROMOTER} for <ATOM_START> so that
<ATOM_START> {
  {ATOM}|{OPERATOR} { yybegin(ADDITION_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE);
                      return ElixirTypes.ATOM_FRAGMENT; }
  {QUOTE_PROMOTER}  { /* At the end of the quote, return the state (YYINITIAL or INTERPOLATION) before ATOM_START as
                         anything after the closing quote should be handle by the state prior to ATOM_START.  Without
                         this, EOL and WHITESPACE won't be handled correctly */
                      yybegin(ADDITION_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE);
                      startQuote(yytext());
                      return ElixirTypes.LINE_PROMOTER; }
}

<BASE_WHOLE_NUMBER_BASE> {
  {BINARY_WHOLE_NUMBER_BASE}               { yybegin(BINARY_WHOLE_NUMBER);
                                             return ElixirTypes.BINARY_WHOLE_NUMBER_BASE; }
  {HEXADECIMAL_WHOLE_NUMBER_BASE}          { yybegin(HEXADECIMAL_WHOLE_NUMBER);
                                             return ElixirTypes.HEXADECIMAL_WHOLE_NUMBER_BASE; }
  {OBSOLETE_BINARY_WHOLE_NUMBER_BASE}      { yybegin(BINARY_WHOLE_NUMBER);
                                             return ElixirTypes.OBSOLETE_BINARY_WHOLE_NUMBER_BASE; }
  {OBSOLETE_HEXADECIMAL_WHOLE_NUMBER_BASE} { yybegin(HEXADECIMAL_WHOLE_NUMBER);
                                             return ElixirTypes.OBSOLETE_HEXADECIMAL_WHOLE_NUMBER_BASE; }
  {OCTAL_WHOLE_NUMBER_BASE}                { yybegin(OCTAL_WHOLE_NUMBER);
                                             return ElixirTypes.OCTAL_WHOLE_NUMBER_BASE; }
  // Must be after any specific integer bases
  {BASE_WHOLE_NUMBER_BASE}                 { yybegin(UNKNOWN_BASE_WHOLE_NUMBER);
                                             return ElixirTypes.UNKNOWN_WHOLE_NUMBER_BASE; }
}

<BINARY_WHOLE_NUMBER> {
  {NUMBER_SEPARATOR}      { return ElixirTypes.NUMBER_SEPARATOR; }
  {INVALID_BINARY_DIGITS} { return ElixirTypes.INVALID_BINARY_DIGITS; }
  {VALID_BINARY_DIGITS}   { return ElixirTypes.VALID_BINARY_DIGITS; }
}

<CHAR_TOKENIZATION> {
  {ESCAPE} { yybegin(ESCAPE_SEQUENCE);
             return ElixirTypes.ESCAPE; }
  {ANY}    { popAndBegin();
             return ElixirTypes.FRAGMENT; }
}

<DECIMAL_EXPONENT_SIGN> {
  {SIGN_OPERATOR} { yybegin(DECIMAL_EXPONENT);
                    return ElixirTypes.SIGN_OPERATOR; }
  {ANY}           { handleInState(DECIMAL_EXPONENT); }
}

<DECIMAL_FRACTION> {
  {EXPONENT_MARK} { yybegin(DECIMAL_EXPONENT_SIGN);
                    return ElixirTypes.EXPONENT_MARK; }
}

<DECIMAL_WHOLE_NUMBER> {
  /*
   Error handling with {INVALID_DECIMAL_DIGITS} and {NUMBER_SEPARATOR} can only occur after at least one valid decimal
   digit after the decimal mark because {INVALID_DECIMAL_DIGITS} and {NUMBER_SEPARATOR} will be parsed as an
   identifier immediately after `.`

   ```
   iex> Code.string_to_quoted("1._")
   {:ok, {{:., [line: 1], [1, :_]}, [line: 1], []}}
   iex> Code.string_to_quoted("1.a")
   {:ok, {{:., [line: 1], [1, :a]}, [line: 1], []}}
   ```
  */
  {DECIMAL_MARK} / {VALID_DECIMAL_DIGITS} { yybegin(DECIMAL_FRACTION);
                                            return ElixirTypes.DECIMAL_MARK; }
}

<DECIMAL_EXPONENT,
 DECIMAL_FRACTION,
 DECIMAL_WHOLE_NUMBER> {
  {NUMBER_SEPARATOR}      { return ElixirTypes.NUMBER_SEPARATOR; }
  {INVALID_DECIMAL_DIGITS} { return ElixirTypes.INVALID_DECIMAL_DIGITS; }
  {VALID_DECIMAL_DIGITS}   { return ElixirTypes.VALID_DECIMAL_DIGITS; }
}

<DOT_OPERATION> {
  {AFTER}                                           { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.AFTER; }
  {AND_SYMBOL_OPERATOR}                             { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.AND_SYMBOL_OPERATOR; }
  {AND_WORD_OPERATOR}                               { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.AND_WORD_OPERATOR; }
  {ARROW_OPERATOR}                                  { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.ARROW_OPERATOR; }
  {AT_OPERATOR}                                     { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.AT_OPERATOR; }
  {CATCH}                                           { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.CATCH; }
  {CAPTURE_OPERATOR}                                { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.CAPTURE_OPERATOR; }
  {COMPARISON_OPERATOR}                             { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.COMPARISON_OPERATOR; }
  {DO}                                              { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.DO; }
  {DIVISION_OPERATOR}                               { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.DIVISION_OPERATOR; }
  {END}                                             { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.END; }
  {ELSE}                                            { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.ELSE; }
  {FALSE}                                           { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.FALSE; }
  {IN_MATCH_OPERATOR}                               { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.IN_MATCH_OPERATOR; }
  {IN_OPERATOR}                                     { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.IN_OPERATOR; }
  {MATCH_OPERATOR}                                  { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.MATCH_OPERATOR; }
  {MINUS_OPERATOR}                                  { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.MINUS_OPERATOR; }
  // Must be before MULTIPLICATION_OPERATOR (*) as it is a prefix of (**)
  {POWER_OPERATOR}                                  { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.POWER_OPERATOR; }
  {MULTIPLICATION_OPERATOR}                         { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.MULTIPLICATION_OPERATOR; }
  {NIL}                                             { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.NIL; }
  {OR_SYMBOL_OPERATOR}                              { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.OR_SYMBOL_OPERATOR; }
  {OR_WORD_OPERATOR}                                { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.OR_WORD_OPERATOR; }
  {PIPE_OPERATOR}                                   { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.PIPE_OPERATOR; }
  {PLUS_OPERATOR}                                   { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.PLUS_OPERATOR; }
  {RANGE_OPERATOR}                                  { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.RANGE_OPERATOR; }
  {RELATIONAL_OPERATOR}                             { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.RELATIONAL_OPERATOR; }
  {RESCUE}                                          { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.RESCUE; }
  {STAB_OPERATOR}                                   {
                                                      yybegin(CALL_MAYBE);
                                                      yypushback(1);
                                                      return ElixirTypes.MINUS_OPERATOR;
                                                    }
  {STRUCT_OPERATOR}                                 { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.STRUCT_OPERATOR; }
  {THREE_OPERATOR}                                  { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.THREE_OPERATOR; }
  {TRUE}                                            { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.TRUE; }
  {TWO_OPERATOR}                                    { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.TWO_OPERATOR; }
  {UNARY_OPERATOR}                                  { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.UNARY_OPERATOR; }
  {WHEN_OPERATOR}                                   { yybegin(CALL_MAYBE);
                                                      return ElixirTypes.WHEN_OPERATOR; }

  /* Must be after {AFTER}, {CATCH}, {DO}, {END}, {ELSE}, {IN_OPERATOR}, {OR_OPERATOR} (for 'or'), and {WHEN_OPERATOR}
     as all those keywords would match {IDENTIFIER_TOKEN} */
  {IDENTIFIER_TOKEN}                                { yybegin(AFTER_RELATIVE_IDENTIFIER);
                                                      return ElixirTypes.IDENTIFIER_TOKEN; }

  {MULTILINE_WHITE_SPACE}                           { return TokenType.WHITE_SPACE; }

  /* Be better than strip_space and handle_dot and ignore comments so that IDENTIFIER_TOKEN and operators are parsed the
     same after dots.

     @see https://groups.google.com/forum/#!topic/elixir-lang-core/nnI4oUB-63U
   */
  {COMMENT}                                         { return ElixirTypes.COMMENT; }

  /* Must be before {QUOTE_PROMOTER} as {QUOTE_PROMOTER} is a prefix of {QUOTE_HEREDOC_PROMOTER} */
  {QUOTE_HEREDOC_PROMOTER}                          { /* Does NOT return to CALL_MAYBE because heredocs aren't valid
                                                         relative identifiers.  This clause is only here to prevent a
                                                         prefix match on {QUOTE_PROMOTER}. */
                                                      handleInLastState(); }
  {QUOTE_PROMOTER}                                  { /* return to CALL_MAYBE so that OPENING_BRACKET or
                                                         OPENING_PARENTHESES after quote can be parsed
                                                         with CALL so parser doesn't think call is no parentheses with
                                                         parenthetical or list argument. */
                                                      yybegin(CALL_MAYBE);
                                                      startQuote(yytext());
                                                      return ElixirTypes.LINE_PROMOTER; }

  .                                                 { handleInLastState(); }
}

<ESCAPE_IN_LITERAL> {
  {EOL} {
          popAndBegin();
          return ElixirTypes.EOL;
        }
  {ANY} {
          popAndBegin();
          return ElixirTypes.FRAGMENT;
        }
}

<ESCAPE_SEQUENCE> {
  {EOL}                           { popAndBegin();
                                    return ElixirTypes.EOL; }
 {GROUP_HEREDOC_TERMINATOR}       {
                                     popAndBegin();

                                     if (isGroup() && isTerminator(yytext())) {
                                         return ElixirTypes.HEREDOC_TERMINATOR;
                                     } else {
                                         yypushback(yylength() - 1);
                                         return ElixirTypes.ESCAPED_CHARACTER_TOKEN;
                                     }
                                   }
  {GROUP_TERMINATOR}              {
                                    popAndBegin();

                                    if (isGroup() && isTerminator(yytext())) {
                                        return ElixirTypes.LINE_TERMINATOR;
                                    } else {
                                        return ElixirTypes.ESCAPED_CHARACTER_TOKEN;
                                    }
                                  }
  // An escape no release accepts leaves its letter a plain escaped character, so the quote goes on.
  {HEXADECIMAL_WHOLE_NUMBER_BASE} / ({HEXADECIMAL_DIGIT}|{OPENING_CURLY}{HEXADECIMAL_DIGIT}{1,6}{CLOSING_CURLY})
                                  { yybegin(HEXADECIMAL_ESCAPE_SEQUENCE);
                                    return ElixirTypes.HEXADECIMAL_WHOLE_NUMBER_BASE; }
  {UNICODE_ESCAPE_CHARACTER} / ({HEXADECIMAL_DIGIT}{4}|{OPENING_CURLY}{HEXADECIMAL_DIGIT}{1,6}{CLOSING_CURLY})
                                  { yybegin(UNICODE_ESCAPE_SEQUENCE);
                                    return ElixirTypes.UNICODE_ESCAPE_CHARACTER; }
  {ANY}                           { popAndBegin();
                                    return ElixirTypes.ESCAPED_CHARACTER_TOKEN; }
}

<EXTENDED_HEXADECIMAL_ESCAPE_SEQUENCE> {
  {CLOSING_CURLY}          { popAndBegin();
                             return ElixirTypes.CLOSING_CURLY; }
  {HEXADECIMAL_DIGIT}{1,6} { return ElixirTypes.VALID_HEXADECIMAL_DIGITS; }
  .                        { return TokenType.BAD_CHARACTER; }
}

<GROUP,
 GROUP_HEREDOC_LINE_BODY> {
  {INTERPOLATION_START} {
                          if (isInterpolating()) {
                           pushAndBegin(INTERPOLATION);
                           return ElixirTypes.INTERPOLATION_START;
                          } else {
                           return ElixirTypes.FRAGMENT;
                          }
                        }
  // See https://github.com/elixir-lang/elixir/pull/4341
  // \""" (or \''') is special in ALL heredocs and sigil lines (interpolating and non-interpolating
  // alike): the backslash escapes the closing delimiter sequence so it doesn't end the group/
  // heredoc, and the backslash itself is NOT part of the content.
  // Placed in the shared block so GROUP and GROUP_HEREDOC_LINE_BODY can never diverge on this rule.
  // ESCAPE_SEQUENCE already handles {GROUP_HEREDOC_TERMINATOR} -> HEREDOC_TERMINATOR (see that
  // state's rules), and the parser grammar ESCAPE HEREDOC_TERMINATOR -> ESCAPED_HEREDOC_TERMINATOR
  // already exists, so no further changes are needed beyond this rule.
  {ESCAPE}{GROUP_HEREDOC_TERMINATOR} {
                                       CharSequence heredocTerminator = yytext().subSequence(1, yytext().length());
                                       yypushback(heredocTerminator.length());

                                       if (isTerminator(heredocTerminator) || isInterpolating()) {
                                         // \""" in """ heredoc - delimiter-escape (both interpolating and non-interpolating)
                                         // \''' in """ heredoc (interpolating) - \ is a normal escape character; ESCAPE_SEQUENCE
                                         //   handles {GROUP_HEREDOC_TERMINATOR} by pushing back all-but-first and returning
                                         //   ESCAPED_CHARACTER_TOKEN, so \' + '' is correct.
                                         pushAndBegin(ESCAPE_SEQUENCE);
                                         return ElixirTypes.ESCAPE;
                                       } else {
                                         // \''' in """ heredoc (non-interpolating): \ is a literal fragment;
                                         // ESCAPE_IN_LITERAL handles the pushed-back chars and returns to this state.
                                         pushAndBegin(ESCAPE_IN_LITERAL);
                                         return ElixirTypes.FRAGMENT;
                                       }
                                     }
}

// Rules in GROUP, but not GROUP_HEREDOC_LINE_BODY
<GROUP> {
  {ESCAPE}{GROUP_TERMINATOR} {
                               CharSequence groupTerminator = yytext().subSequence(1, yytext().length());

                               // manual lookahread pushes terminator back
                               yypushback(groupTerminator.length());

                               /* even literal groups have escape sequences because escaping the terminator is still
                                  allowed */
                                if (isTerminator(groupTerminator) || isInterpolating()) {
                                  // matches interpolating behavior from `{ESCAPE}` rule below
                                  pushAndBegin(ESCAPE_SEQUENCE);
                                  return ElixirTypes.ESCAPE;
                                } else {
                                  // matches non-interpolating behavior from `{ESCAPE}` rule below
                                  pushAndBegin(ESCAPE_IN_LITERAL);
                                  return ElixirTypes.FRAGMENT;
                                }
                             }
  {ESCAPE} / {EOL}           {
                               if (isInterpolating()) {
                                 pushAndBegin(ESCAPE_SEQUENCE);
                               } else {
                                 pushAndBegin(ESCAPE_IN_LITERAL);
                               }

                               return ElixirTypes.ESCAPE;
                             }
  {ESCAPE}                   {
                               if (isInterpolating()) {
                                 pushAndBegin(ESCAPE_SEQUENCE);
                                 return ElixirTypes.ESCAPE;
                               } else {
                                 pushAndBegin(ESCAPE_IN_LITERAL);
                                 return ElixirTypes.FRAGMENT;
                               }
                             }
  {GROUP_TERMINATOR}         {
                               if (isTerminator(yytext())) {
                                 if (isSigil()) {
                                   yybegin(SIGIL_MODIFIERS);
                                   return ElixirTypes.LINE_TERMINATOR;
                                 } else {
                                   popAndBegin();
                                   return ElixirTypes.LINE_TERMINATOR;
                                 }
                               } else {
                                 return ElixirTypes.FRAGMENT;
                               }
                             }
  {ANY}                      { return ElixirTypes.FRAGMENT; }
}

<GROUP_HEREDOC_END> {
    {GROUP_HEREDOC_TERMINATOR} {
                                   if (isTerminator(yytext())) {
                                      if (isSigil()) {
                                        yybegin(SIGIL_MODIFIERS);
                                        return ElixirTypes.HEREDOC_TERMINATOR;
                                      } else {
                                        popAndBegin();;
                                        return ElixirTypes.HEREDOC_TERMINATOR;
                                      }
                                   } else {
                                      handleInState(GROUP_HEREDOC_LINE_BODY);
                                   }
                               }
}

// Rules in GROUP_HEREDOC_LINE_BODY, but not GROUP
<GROUP_HEREDOC_LINE_BODY> {
  // See https://github.com/elixir-lang/elixir/pull/4341
  // \<newline> is special in ALL heredocs (interpolating and non-interpolating alike):
  // Elixir's tokenizer (elixir_interpolation.erl extract/8) processes \<newline> via
  // extract_nl() regardless of the Interpol flag, so the structure is syntactically
  // distinct from plain content. The GROUP state also always returns ESCAPE for
  // \<newline> in non-interpolating mode (going to ESCAPE_IN_LITERAL), so we
  // mirror that here and always produce ESCAPE + EOL -> ESCAPED_EOL in the PSI tree.
  {ESCAPE} / {EOL} {
                     yybegin(GROUP_HEREDOC_LINE_ESCAPED_EOL);
                     return ElixirTypes.ESCAPE;
                   }
  // For non-interpolating sigils, a backslash is a literal character; stay in this state so
  // the next character is handled by the normal GROUP_HEREDOC_LINE_BODY rules.
  {ESCAPE}         {
                     if (isInterpolating()) {
                       pushAndBegin(ESCAPE_SEQUENCE);
                       return ElixirTypes.ESCAPE;
                     } else {
                       return ElixirTypes.FRAGMENT;
                     }
                   }
  {EOL}            {
                     yybegin(GROUP_HEREDOC_LINE_START);
                     return ElixirTypes.EOL;
                   }
  {ANY}            { return ElixirTypes.FRAGMENT; }
}

// See https://github.com/elixir-lang/elixir/pull/4341
<GROUP_HEREDOC_LINE_ESCAPED_EOL> {
  {EOL} {
          yybegin(GROUP_HEREDOC_LINE_START);
          return ElixirTypes.EOL;
        }
}

<GROUP_HEREDOC_LINE_START> {
  {WHITE_SPACE}+{GROUP_HEREDOC_TERMINATOR} {
                                             String groupHeredocTerminator = yytext().toString().trim();

                                             // manual lookahead pushes terminator back
                                             yypushback(3);

                                             if (isTerminator(groupHeredocTerminator)) {
                                               yybegin(GROUP_HEREDOC_END);
                                               return ElixirTypes.HEREDOC_PREFIX_WHITE_SPACE;
                                             } else {
                                               yybegin(GROUP_HEREDOC_LINE_BODY);
                                               return ElixirTypes.HEREDOC_LINE_WHITE_SPACE_TOKEN;
                                             }
                                           }
  {WHITE_SPACE}+                           {
                                             yybegin(GROUP_HEREDOC_LINE_BODY);
                                             return ElixirTypes.HEREDOC_LINE_WHITE_SPACE_TOKEN;
                                           }
  {GROUP_HEREDOC_TERMINATOR}               { handleInState(GROUP_HEREDOC_END); }
  {ANY}                                    { handleInState(GROUP_HEREDOC_LINE_BODY); }
}

<GROUP_HEREDOC_START> {
  // Elixir allows spaces and tabs before the opening line's end.
  {HORIZONTAL_SPACE}+         { return TokenType.WHITE_SPACE; }
  // An immediate terminator ends the heredoc, and `InvalidConstruct` reports the missing line end.
  {GROUP_HEREDOC_TERMINATOR} {
                               // Similar to GROUP_HEREDOC_END's GROUP_HEREDOC_TERMINATOR rule, but...
                               if (isTerminator(yytext())) {
                                 if (isSigil()) {
                                   yybegin(SIGIL_MODIFIERS);
                                   return ElixirTypes.HEREDOC_TERMINATOR;
                                 } else {
                                   popAndBegin();
                                   return ElixirTypes.HEREDOC_TERMINATOR;
                                 }
                               } else {
                                 /* ...returns BAD_CHARACTER instead of going to GROUP_HEREDOC_LINE_BODY when the wrong
                                    type of terminator */
                                 return TokenType.BAD_CHARACTER;
                               }
                             }
  {EOL}                      { yybegin(GROUP_HEREDOC_LINE_START);
                               return ElixirTypes.EOL; }
}

<HEXADECIMAL_ESCAPE_SEQUENCE> {
  {OPENING_CURLY}          { yybegin(EXTENDED_HEXADECIMAL_ESCAPE_SEQUENCE);
                             return ElixirTypes.OPENING_CURLY; }
  {HEXADECIMAL_DIGIT}{1,2} { popAndBegin();
                             return ElixirTypes.VALID_HEXADECIMAL_DIGITS; }
}

<HEXADECIMAL_WHOLE_NUMBER> {
  {NUMBER_SEPARATOR}      { return ElixirTypes.NUMBER_SEPARATOR; }
  {INVALID_HEXADECIMAL_DIGITS} { return ElixirTypes.INVALID_HEXADECIMAL_DIGITS; }
  {VALID_HEXADECIMAL_DIGITS}   { return ElixirTypes.VALID_HEXADECIMAL_DIGITS; }
}

<INTERPOLATION, INTERPOLATION_CURLY> {
  {OPENING_CURLY}    { // use stack to match up nested OPENING_CURLY and CLOSING_CURLY
                       pushAndBegin(INTERPOLATION_CURLY);
                       pushAndBegin(MULTILINE_WHITE_SPACE_MAYBE);
                       return ElixirTypes.OPENING_CURLY; }
}

/* Only rules for <INTERPOLATON>, but not <YYINITIAL> go here.
   @note must be after <YYINITIAL, INTERPOLATION> so that BAD_CHARACTER doesn't match a single ' ' instead of
     {WHITE_SPACE}+. */
<INTERPOLATION> {
  {INTERPOLATION_END} { popAndBegin();
                        return ElixirTypes.INTERPOLATION_END; }
}

<INTERPOLATION_CURLY> {
  {CLOSING_CURLY} { // exit CURLY and begin YYINITIAL or INTERPOLATION
                    popAndBegin();
                    // Check for trailing +/-
                    pushAndBegin(ADDITION_OR_SUBTRACTION_MAYBE);
                    return ElixirTypes.CLOSING_CURLY; }
}

<KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE, MULTILINE_WHITE_SPACE_MAYBE> {
  {COMMENT}                           { return ElixirTypes.COMMENT; }
  {MULTILINE_WHITE_SPACE} / {COMMENT} { return TokenType.WHITE_SPACE; }
  {MULTILINE_WHITE_SPACE}             { popAndBegin();
                                        return TokenType.WHITE_SPACE; }
}

<ADDITION_OR_KEYWORD_PAIR_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE, AFTER_RELATIVE_IDENTIFIER, KEYWORD_PAIR_MAYBE, KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE> {
  {COLON} / {SPACE} { yybegin(MULTILINE_WHITE_SPACE_MAYBE);
                      return ElixirTypes.KEYWORD_PAIR_COLON; }
}

<LAST_EOL> {
  {EOL} { popAndBegin();
          return ElixirTypes.EOL; }
}

<NAMED_SIGIL> {
  {SIGIL_HEREDOC_PROMOTER} { setPromoter(yytext());
                             yybegin(GROUP_HEREDOC_START);
                             return ElixirTypes.HEREDOC_PROMOTER; }
  {SIGIL_PROMOTER}         { setPromoter(yytext());
                             yybegin(GROUP);
                             return ElixirTypes.LINE_PROMOTER; }
  {EOL}                    { return TokenType.BAD_CHARACTER; }
}

<OCTAL_WHOLE_NUMBER> {
  {NUMBER_SEPARATOR}      { return ElixirTypes.NUMBER_SEPARATOR; }
  {INVALID_OCTAL_DIGITS} { return ElixirTypes.INVALID_OCTAL_DIGITS; }
  {VALID_OCTAL_DIGITS}   { return ElixirTypes.VALID_OCTAL_DIGITS; }
}

<REFERENCE_OPERATION> {
  {ESCAPED_EOL}|{WHITE_SPACE}+ { return TokenType.WHITE_SPACE; }
  {EOL}                        { return ElixirTypes.EOL; }
  {DIVISION_OPERATOR}          { popAndBegin();
                                 return ElixirTypes.DIVISION_OPERATOR; }
}

<SIGIL> {
  {SIGIL_NAME}               { nameSigil(yytext());
                               yybegin(NAMED_SIGIL);
                               return sigilNameType(); }
  {EOL}                      { return TokenType.BAD_CHARACTER; }
}

<SIGIL_MODIFIERS> {
  {SIGIL_MODIFIER} { return ElixirTypes.SIGIL_MODIFIER; }
}

<UNICODE_ESCAPE_SEQUENCE> {
  {OPENING_CURLY}          { yybegin(EXTENDED_HEXADECIMAL_ESCAPE_SEQUENCE);
                             return ElixirTypes.OPENING_CURLY; }
  {HEXADECIMAL_DIGIT}{1,4} { popAndBegin();
                             return ElixirTypes.VALID_HEXADECIMAL_DIGITS; }
}

<UNKNOWN_BASE_WHOLE_NUMBER> {
  {INVALID_UNKNOWN_BASE_DIGITS} { return ElixirTypes.INVALID_UNKNOWN_BASE_DIGITS; }
}

<YYINITIAL> {
  {OPENING_CURLY} { pushAndBegin(MULTILINE_WHITE_SPACE_MAYBE);
                    return ElixirTypes.OPENING_CURLY; }
  {CLOSING_CURLY} { pushAndBegin(ADDITION_OR_SUBTRACTION_MAYBE);
                    return ElixirTypes.CLOSING_CURLY; }
}

// States where it is known that
<BINARY_WHOLE_NUMBER, DECIMAL_EXPONENT, DECIMAL_FRACTION, DECIMAL_WHOLE_NUMBER, HEXADECIMAL_WHOLE_NUMBER,
 OCTAL_WHOLE_NUMBER, SIGIL_MODIFIERS, UNKNOWN_BASE_WHOLE_NUMBER> {
   {ANY} { handleInState(ADDITION_OR_SUBTRACTION_MAYBE); }
}

<ADDITION_OR_KEYWORD_PAIR_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE, ADDITION_OR_SUBTRACTION_MAYBE, ADDITION_OR_SUBTRACTION_OR_WHITE_SPACE_MAYBE,
 AFTER_RELATIVE_IDENTIFIER, AFTER_IDENTIFIER_WHITE_SPACE, AFTER_UNQUALIFIED_IDENTIFIER,
 ATOM_START,
 CALL_MAYBE,
 HEXADECIMAL_ESCAPE_SEQUENCE,
 KEYWORD_PAIR_MAYBE, KEYWORD_PAIR_OR_MULTILINE_WHITE_SPACE_MAYBE,
 MULTILINE_WHITE_SPACE_MAYBE,
 UNICODE_ESCAPE_SEQUENCE> {
  {ANY} { handleInLastState(); }
}

// MUST go last so that . mapping to BAD_CHARACTER is the rule of last resort for the listed states
<GROUP_HEREDOC_START, INTERPOLATION, NAMED_SIGIL, SIGIL, YYINITIAL> {
  . { return TokenType.BAD_CHARACTER; }
}
