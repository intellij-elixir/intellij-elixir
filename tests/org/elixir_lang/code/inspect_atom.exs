# Prints InspectAtomTest's rows: each atom's class and Macro.inspect_atom/3 in the three modes, as Elixir 1.20.4
# writes them. Run from the repository root and paste the output over the rows in InspectAtomTest.kt:
#
#     mise exec elixir@1.20.4-otp-29 erlang@29 -- elixir tests/org/elixir_lang/code/inspect_atom.exs
#
# Atoms are written here with escapes, so an editor cannot normalise them.

"1.20." <> _ = System.version()

atoms = [
  # identifiers, including the reserved words
  "foo", "foo?", "foo!", "_", "_foo", "__MODULE__", "__aliases__", "__block__", "nil", "true", "false", "fn",
  "do", "end", "after", "else", "catch", "rescue", "unquote", "unquote_splicing", "bnot",
  # capitalised atoms and aliases
  "Foo", "DOWN", "EXIT", "Elixir", "Elixir.Foo", "Elixir.Foo.Bar9_x", "Elixir.Elixir", "Elixir.Elixir.Foo",
  "Elixir.foo", "Elixir.foo bar", "Elixir.Foo.bar", "Elixir.Foo-Bar", "Elixir.my-mod", "Elixir.Benchfella:tests",
  "Elixir.\u{D1}", "Elixir.", "ElixirFoo", "Foo.Bar", "foo.bar", "a.b",
  # not callable
  "%", "%{}", "{}", "<<>>", "...", "..", ".", "..//", "->",
  # operators that must be quoted
  "::", "^^^", "~~~", "<|>",
  # operators written bare
  "+", "-", "*", "/", "**", "++", "--", "+++", "---", "<>", "&&", "&&&", "||", "|||", "|>", "\\\\", "<-", "=",
  "==", "!=", "===", "!==", "=~", "<", "<=", ">=", ">", "<<<", ">>>", "<~", "~>", "<<~", "~>>", "<~>", "|", "&",
  "!", "^", "@", "in", "when", "and", "or", "not",
  # not Elixir operators
  "//", "=:=", "=/=", "/=", "=<", "=>", "~=", "[]", "&1", "not in", "-button_area/1-fun-0-",
  # tokenizer shapes
  "a?b", "foo!?", "foo@bar", "foo@", "Foo@bar", "nonode@nohost", "foo bar", "1a", "", "3des-cbc", "foo-bar",
  # escapes
  "with\"quote", "back\\slash", "has\#{x", "lone#hash", "a\nb", "nul\0x", "del\dx", "bel\ax", "esc\ex", "tab\tx",
  "vt\vx", "ff\fx", "bs\bx", "cr\rx", "soh\u0001x", "zwsp\u{200B}x", "rlo\u{202E}x", "bom\u{FEFF}x", "nbsp\u{A0}x",
  "ls\u{2028}x", "c1\u0085x", "ffff\u{FFFF}x",
  # Unicode identifiers, normalisation and script mixing
  "\u{E9}", "e\u{301}", "\u{B5}", "\u{3BC}", "\u{F1}", "ol\u{E1}", "\u{F3}l\u{E1}?", "\u{43F}\u{440}\u{438}\u{432}\u{435}\u{442}",
  "\u{65E5}\u{672C}", "\u{3053}\u{3093}\u{306B}\u{3061}\u{306F}\u{4E16}\u{754C}", "hello_\u{43F}\u{440}\u{438}\u{432}\u{435}\u{442}",
  "hello\u{43F}\u{440}\u{438}\u{432}\u{435}\u{442}", "\u{430}b", "\u{FB01}x", "\u{210C}x", "\u{D1}", "\u{D1}andu",
  "\u{41F}\u{440}\u{438}\u{432}\u{435}\u{442}", "\u{1F4A5}", "foo_\u{1F4A5}", "\u{2160}", "x\u{2071}"
]

# inner_classify is private; its answer follows from classify_atom and whether :remote_call quotes.
class = fn atom ->
  quoted_remote? = String.starts_with?(Macro.inspect_atom(:remote_call, atom), "\"")

  case Macro.classify_atom(atom) do
    :alias -> "ALIAS"
    :identifier -> "IDENTIFIER"
    :unquoted -> if quoted_remote?, do: "NOT_CALLABLE", else: "UNQUOTED_OPERATOR"
    :quoted -> if quoted_remote?, do: "OTHER", else: "QUOTED_OPERATOR"
  end
end

kotlin = fn string ->
  escaped =
    string
    |> String.to_charlist()
    |> Enum.map(fn
      ?\\ -> "\\\\"
      ?" -> "\\\""
      ?$ -> "\\$"
      c when c in 0x20..0x7E -> <<c>>
      c when c < 0x10000 -> "\\u" <> String.pad_leading(Integer.to_string(c, 16), 4, "0")
      c -> <<c::utf16>> |> then(fn <<h::16, l::16>> -> "\\u#{Integer.to_string(h, 16)}\\u#{Integer.to_string(l, 16)}" end)
    end)

  ~s("#{escaped}")
end

for string <- atoms do
  atom = String.to_atom(string)

  row =
    [
      kotlin.(string),
      class.(atom),
      kotlin.(Macro.inspect_atom(:literal, atom)),
      kotlin.(Macro.inspect_atom(:key, atom)),
      kotlin.(Macro.inspect_atom(:remote_call, atom))
    ]
    |> Enum.join(", ")

  IO.puts("        Row(#{row}),")
end
