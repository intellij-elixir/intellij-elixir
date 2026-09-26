# Numbers
0b0101011
1234 ; 0x1A ; 0xbeef ; 0763 ; 0o123
3.14 ; 5.0e21 ; 0.5e-12
100_000_000

# these are not valid numbers
0b012 ; 0xboar ; 0o888
0B01 ; 0XAF ; 0O123

# Characters
?a ; ?1 ; ?<escape-sequence>\n</escape-sequence> ; ?<escape-sequence>\s</escape-sequence> ; ?<escape-sequence>\c</escape-sequence> ; ? ; ?,
?<escape-sequence>\x{12}</escape-sequence> ; ?<escape-sequence>\x{abcd}</escape-sequence>
?<escape-sequence>\x34</escape-sequence> ; ?<escape-sequence>\xF</escape-sequence>

# these show that only the first digit is part of the character
?<escape-sequence>\1</escape-sequence><error><valid-digit>23</valid-digit></error> ; ?<escape-sequence>\1</escape-sequence><error><valid-digit>2</valid-digit></error> ; <escape-sequence>?\7</escape-sequence>

# Atoms
:this ; :that
:<char_list>'complex atom'</char_list>
:<string>"with' <escape-sequence>\"</escape-sequence><escape-sequence>\"</escape-sequence> 'quotes"</string>
:<string>" multi
 line ' <escape-sequence>\s</escape-sequence> <escape-sequence>\1</escape-sequence>23 <escape-sequence>\xff</escape-sequence>
atom"</string>
:... ; :<<>> ; :%{} ; :% ; :{}
:++; :--; :*; :~~~; :::
:% ; :. ; :<-

# Strings
<string>"Hello world"</string>
<string>"Interspersed <escape-sequence>\x{ff}</escape-sequence> codes <escape-sequence>\7</escape-sequence> <escape-sequence>\8</escape-sequence> <escape-sequence>\6</escape-sequence>5 <escape-sequence>\0</escape-sequence>16 and <escape-sequence>\t</escape-sequence><escape-sequence>\s</escape-sequence><escape-sequence>\\</escape-sequence>s<escape-sequence>\z</escape-sequence><escape-sequence>\+</escape-sequence> <escape-sequence>\\</escape-sequence> escapes"</string>
<string>"Quotes ' inside <escape-sequence>\"</escape-sequence> <escape-sequence>\1</escape-sequence>23 the <escape-sequence>\"</escape-sequence><escape-sequence>\"</escape-sequence> <escape-sequence>\xF</escape-sequence> <escape-sequence>\\</escape-sequence>xF string <escape-sequence>\\</escape-sequence><escape-sequence>\"</escape-sequence> end"</string>
<string>"Multiline
   string"</string>

# Char lists
<char_list>'this is a list'</char_list>
<char_list>'escapes <escape-sequence>\'</escape-sequence> <escape-sequence>\t</escape-sequence> <escape-sequence>\\</escape-sequence><escape-sequence>\'</escape-sequence>'</char_list>
<char_list>'Multiline
    char
  list
'</char_list>

# Binaries
<<1, 2, 3>>
<<<string>"hello"</string>::<type>binary</type>, c :: <type>utf8</type>, x:: 4 * 2>> = <string>"hello™1"</string>

# Sigils
<sigil_a>~a"</sigil_a>#{<string>"Interpolating"</string>}<sigil_a> a sigil"</sigil_a>
<sigil_A>~A"Literal A sigil"</sigil_A>

<sigil_b>~b"</sigil_b>#{<string>"Interpolating"</string>}<sigil_b> b sigil"</sigil_b>
<sigil_B>~B"Literal B sigil"</sigil_B>

<sigil_c>~c"</sigil_c>#{<string>"Interpolating"</string>}<sigil_c> c sigil"</sigil_c>
<sigil_C>~C"Literal C sigil"</sigil_C>

<sigil_d>~d"</sigil_d>#{<string>"Interpolating"</string>}<sigil_d> d sigil"</sigil_d>
<sigil_D>~D"Literal D sigil"</sigil_D>

<sigil_e>~e"</sigil_e>#{<string>"Interpolating"</string>}<sigil_e> e sigil"</sigil_e>
<sigil_E>~E"Literal E sigil"</sigil_E>

<sigil_f>~f"</sigil_f>#{<string>"Interpolating"</string>}<sigil_f> f sigil"</sigil_f>
<sigil_F>~F"Literal F sigil"</sigil_F>

<sigil_g>~g"</sigil_g>#{<string>"Interpolating"</string>}<sigil_g> g sigil"</sigil_g>
<sigil_G>~G"Literal G sigil"</sigil_G>

<sigil_h>~h"</sigil_h>#{<string>"Interpolating"</string>}<sigil_h> h sigil"</sigil_h>
<sigil_H>~H"Literal H sigil"</sigil_H>

<sigil_i>~i"</sigil_i>#{<string>"Interpolating"</string>}<sigil_i> i sigil"</sigil_i>
<sigil_I>~I"Literal I sigil"</sigil_I>

<sigil_j>~j"</sigil_j>#{<string>"Interpolating"</string>}<sigil_j> j sigil"</sigil_j>
<sigil_J>~J"Literal J sigil"</sigil_J>

<sigil_k>~k"</sigil_k>#{<string>"Interpolating"</string>}<sigil_k> k sigil"</sigil_k>
<sigil_K>~K"Literal K sigil"</sigil_K>

<sigil_l>~l"</sigil_l>#{<string>"Interpolating"</string>}<sigil_l> l sigil"</sigil_l>
<sigil_L>~L"Literal L sigil"</sigil_L>

<sigil_m>~m"</sigil_m>#{<string>"Interpolating"</string>}<sigil_m> m sigil"</sigil_m>
<sigil_M>~M"Literal M sigil"</sigil_M>

<sigil_n>~n"</sigil_n>#{<string>"Interpolating"</string>}<sigil_n> n sigil"</sigil_n>
<sigil_N>~N"Literal N sigil"</sigil_N>

<sigil_o>~o"</sigil_o>#{<string>"Interpolating"</string>}<sigil_o> o sigil"</sigil_o>
<sigil_O>~O"Literal O sigil"</sigil_O>

<sigil_p>~p"</sigil_p>#{<string>"Interpolating"</string>}<sigil_p> p sigil"</sigil_p>
<sigil_P>~P"Literal P sigil"</sigil_P>

<sigil_q>~q"</sigil_q>#{<string>"Interpolating"</string>}<sigil_q> q sigil"</sigil_q>
<sigil_Q>~Q"Literal Q sigil"</sigil_Q>

<sigil_r>~r/this + i<escape-sequence>\s</escape-sequence> "a" regex/</sigil_r>
<sigil_R>~R'this + i\s "a" regex too'</sigil_R>

<sigil_s>~s{Escapes terminators <escape-sequence>\{</escape-sequence> and <escape-sequence>\}</escape-sequence>, but no {balancing}</sigil_s> # outside of sigil here }
<sigil_S>~S"No escapes \s\t\n and no #{interpolation}"</sigil_S>

<sigil_t>~t"</sigil_t>#{<string>"Interpolating"</string>}<sigil_t> t sigil"</sigil_t>
<sigil_T>~T"Literal T sigil"</sigil_T>

<sigil_u>~u"</sigil_u>#{<string>"Interpolating"</string>}<sigil_u> u sigil"</sigil_u>
<sigil_U>~U"Literal U sigil"</sigil_U>

<sigil_v>~v"</sigil_v>#{<string>"Interpolating"</string>}<sigil_v> v sigil"</sigil_v>
<sigil_V>~V"Literal V sigil"</sigil_V>

<sigil_w>~w(hello </sigil_w>#{ [<string>"has"</string> <> <string>"123"</string>, <char_list>'<escape-sequence>\c</escape-sequence><escape-sequence>\d</escape-sequence>'</char_list>, <string>"<escape-sequence>\1</escape-sequence>23 interpol"</string> | []] }<sigil_w> world)s</sigil_w>
<sigil_W>~W(hello #{no "123" \c\d \123 interpol} world)s</sigil_W>

<sigil_x>~x"</sigil_x>#{<string>"Interpolating"</string>}<sigil_x> x sigil"</sigil_x>
<sigil_X>~X"Literal X sigil"</sigil_X>

<sigil_y>~y"</sigil_y>#{<string>"Interpolating"</string>}<sigil_y> y sigil"</sigil_y>
<sigil_Y>~Y"Literal Y sigil"</sigil_Y>

<sigil_z>~z"</sigil_z>#{<string>"Interpolating"</string>}<sigil_z> z sigil"</sigil_z>
<sigil_Z>~Z"Literal Z sigil"</sigil_Z>

:<string>"atoms work </string>#{<string>"to"</string> <> <string>"o"</string>}<string>"</string>

# Operators
<variable>x</variable> = 1 + 2.0 * 3
<variable>y</variable> = true <predefined-call><macro-call>and</macro-call></predefined-call> false; <variable>z</variable> = false <predefined-call><macro-call>or</macro-call></predefined-call> true
<variable>...</variable> = 144
<variable>...</variable> == !<variable>x</variable> && <variable>y</variable> || <variable>z</variable>
"hello" |> <alias>String</alias>.upcase |> <alias>String</alias>.downcase()
{^<variable>z</variable>, <variable>a</variable>} = {true, <variable>x</variable>}

# Free operators (added in 1.0.0)
<variable>p</variable>  ~>> <variable>f</variable>  = bind(p, f)
<variable>p1</variable> ~>  <variable>p2</variable> = pair_right(p1, p2)
<variable>p1</variable> <~  <variable>p2</variable> = pair_left(<variable>p1</variable>, <variable>p2</variable>)
<variable>p1</variable> <~> <variable>p2</variable> = pair_both(<variable>p1</variable>, <variable>p2</variable>)
<variable>p1</variable> <|> <variable>p2</variable> = either(<variable>p1</variable>, <variable>p2</variable>)

# Lists, tuples, maps, keywords
[1, :a, <char_list>'hello'</char_list>] ++ [2, 3]
[:head | [?t, ?a, ?i, ?l]]

{:one, 2.0, <string>"three"</string>}

[<atom>...:</atom> <string>"this"</string>, <atom><<>></atom>: <string>"is"<string>, <atom>%{}:</atom> <string>"a keyword"</string>, <atom>%:</atom> <string>"list"</string>, <atom>{}:</atom> <string>"too"<string>]
["this is an atom too": 1, "so is this": 2]
[<atom>option:</atom> <string>"value"</string>, <atom>key:</atom> :word]
[<atom>++:</atom> <string>"operator"</string>, <atom>~~~:</atom> :&&&]

<variable>map</variable> = <map>%{<atom>shortcut:</atom> <string>"syntax"<string>}</map>
<map>%{</map><variable>map</variable> | <string>"update"</string> => <string>"me"</string><map>}</map>
<map>%{</map> 12 => 13, :weird => [<char_list>'thing'</char_list>] <map>}</map>

# Comprehensions
<predefined-call><macro-call>for</macro-call></predefined-call> <parameter>x</parameter> <- 1..10, <parameter>x</parameter> < 5, <atom>do:</atom> {<parameter>x</parameter>, <parameter>x</parameter>}
<variable>pixels</variable> = <string>"12345678"</string>
<predefined-call><macro-call>for</macro-call></predefined-call> << <<<parameter>r</parameter>::4, <parameter>g</parameter>::4, <parameter>b</parameter>::4, <parameter>a</parameter>::size(4)>> <- <variable>pixels</variable> >> do
  [<parameter>r</parameter>, {<parameter>g</parameter>, <map>%{</map>"b" => <parameter>a</parameter><map>}</map>}]
end

# String interpolation
<string>"String #{<predefined-call><function-call>inspect</function-call></predefined-call> <string>"interpolation"</string>} is quite #{1+4+7} difficult"</string>

# Identifiers
<variable>abc_123</variable> = 1
<variable>_018OP</variable> = 2
<alias>A__0</alias> == 3

# Modules
<predefined-call><macro-call>defmodule</macro-call></predefined-call> <alias>Long.Module.Name</alias> do
  <documentation-module-attribute>@moduledoc</documentation-module-attribute> "<documentation-text>Simple module docstring</documentation-text>"

  <documentation-module-attribute>@doc</documentation-module-attribute> """
  <documentation-text>Multiline docstring
  "with quotes"
  and </documentation-text>#{ <predefined-call><function-call>inspect</function-call></predefined-call> %{"interpolation" => "in" <> "action"} }<documentation-text>
  now with </documentation-text>#{ {:a, 'tuple'} }<documentation-text>
  and </documentation-text>#{ <predefined-call><function-call>inspect</function-call></predefined-call> {
      :tuple,
      <map>%{</map> <atom>with:</atom> "nested #{ <predefined-call><function-call>inspect</function-call></predefined-call> <map>%{</map> :interpolation => <map>%{</map><map>}</map> <map>}</map> }" <map>}</map>
  } }
  """
  <predefined-call><macro-call>defstruct</macro-call></predefined-call> [:a, :name, :height]

  <documentation-module-attribute>@doc</documentation-module-attribute> ~S'''
  <documentation-text>No #{interpolation} of any kind.
  \000 \x{ff}

  \n #{\x{ff}}</documentation-text>
  '''
  <predefined-call><macro-call>def</macro-call></predefined-call> func(<parameter>a</parameter>, <parameter>b</parameter> \\ []), <atom>do:</atom> :ok

  <documentation-module-attribute>@doc</documentation-module-attribute> false
  <predefined-call><macro-call>def</macro-call></predefined-call> __before_compile__(<ignored>_</ignored>) do
    :ok
  end
end

# Structs
<predefined-call><macro-call>defmodule</macro-call></predefined-call> <alias>Second.Module</alias> do
  <variable>s</variable> = <struct>%</struct><alias>Long.Module.Name</alias>{<atom>name:</atom> "Silly"<struct>}</struct>
  <struct>%</struct><alias>Long.Module.Name</alias><struct>{</struct><variable>s</variable> | <atom>height:</atom> {192, :cm}<struct>}</struct>
  ".. #{<struct>%</struct><alias>Long.Module.Name</alias>{<variable>s</variable> | <atom>height:</atom> {192, :cm}<struct>}</struct>} .."
end

# Types, pseudo-vars, attributes
<predefined-call><macro-call>defmodule</macro-call></predefined-call> <alias>M</alias> do
  <module-attribute>@custom_attr</module-attribute> :some_constant

  <module-attribute>@before_compile</module-attribute> <alias>Long.Module.Name</alias>

  <documentation-module-attribute>@typedoc</documentation-module-attribute> "<documentation-text>This is a type</documentation-text>"
  <module-attribute>@type</module-attribute> <type>typ</type> :: <type>integer</type>

  <documentation-module-attribute>@typedoc</documentation-module-attribute> "<documentation-text>Type with parameters</documentation-text>"
  <module-attribute>@type</module-attribute> <type>parameterized</type>(<type-parameter>type_parameter</type-parameter>) :: <type-parameter>type_parameter</type-parameter>

  <documentation-module-attribute>@typedoc</documentation-module-attribute> """
  <documentation-text>Another type</documentation-text>
  """
  <module-attribute>@opaque</module-attribute> <type>typtyp</type> :: 1..10

  <documentation-module-attribute>@doc</documentation-module-attribute> """
  <documentation-text>Will be called by M to `func` the `typ` with the `typtyp`</documentation-text>
  """
  <module-attribute>@callback</module-attribute> <callback>func</callback>(<type>typ</type>, <type>typtyp</type>) :: :ok | :fail

  <module-attribute>@spec</module-attribute> <specification>func</specification>(<type>typ</type>, <type>typtyp</type>) :: :ok | :fail
  <predefined-call><macro-call>def</macro-call></predefined-call> func(<parameter>a</parameter>, <parameter>b</parameter>) do
    <parameter>a</parameter> || <parameter>b</parameter> || :ok || :fail
    <alias>Path</alias>.expand("..", <predefined-call><macro-call>__DIR__</macro-call></predefined-call>)
    <alias>IO</alias>.inspect <predefined-call><macro-call>__ENV__</macro-call></predefined-call>
    <variable>__NOTAPSEUDOVAR__</variable> = 11
    <predefined-call><macro-call>__MODULE__</macro-call></predefined-call>.func(<parameter>b</parameter>, <parameter>a</parameter>)
  end

  <predefined-call><macro-call>defmacro</macro-call></predefined-call> m() do
    <predefined-call><macro-call>__CALLER__</macro-call></predefined-call>
  end

  <predefined-call><macro-call>defguard</macro-call></predefined-call> <guard-declaration>is_small</guard-declaration>(<parameter>a</parameter>) when <parameter>a</parameter> < 10

  <predefined-call><macro-call>def</macro-call></predefined-call> small?(<parameter>a</parameter>) when <guard-call>is_small</guard-call>(<parameter>a</parameter>), <atom>do:</atom> true
end

# Functions
<variable>anon</variable> = fn <parameter>x</parameter>, <parameter>y</parameter>, <parameter>z</parameter> ->
  fn(<parameter>a</parameter>, <parameter>b</parameter>, <parameter>c</parameter>) ->
    &(<parameter>x</parameter> + <parameter>y</parameter> - <parameter>z</parameter> * <parameter>a</parameter> / &1 + <parameter>b</parameter> + <predefined-call><function-call>div</function-call></predefined-call>(&2, <parameter>c</parameter>))
  end
end

&<alias>Set</alias>.put(&1, &2) ; & <alias>Set</alias>.put(&1, &2) ; &( <alias>Set</alias>.put(&1, &1) )

# Function calls
<variable>anon</variable>.(1, 2, 3); <predefined-call><function-call>self</function-call></predefined-call>; <predefined-call><function-call>hd</function-call></predefined-call>([1,2,3])
<alias>Kernel</alias>.<predefined-call><function-call>spawn</function-call></predefined-call>(fn -> :ok end)
<alias>IO.ANSI</alias>.black

# Control flow
<predefined-call><macro-call>if</macro-call></predefined-call> :this do
  :that
else
  :otherwise
end

<variable>pid</variable> = <predefined-call><function-call>self</function-call></predefined-call>
<predefined-call><macro-call>receive</macro-call></predefined-call> do
  {:EXIT, <ignored>_</ignored>} -> :done
  {^<variable>pid</variable>, :_} -> nil
  after 100 -> :no_luck
end

<predefined-call><macro-call>case</macro-call></predefined-call> <predefined-call><macro-call>__ENV__</macro-call></predefined-call>.line do
  <variable>x</variable> when <predefined-call><function-call>is__integer</function-call></predefined-call>(<variable>x</variable>) -> <variable>x</variable>
  <variable>x</variable> when <variable>x</variable> in 1..12 -> -<variable>x</variable>
end

<predefined-call><macro-call>cond</macro-call></predefined-call> do
  false -> <string>"too bad"</string>
  4 > 5 -> <string>"oops"</string>
  true -> nil
end

# Lexical scope modifiers
<predefined-call><macro-call>import</macro-call></predefined-call> <alias>Kernel</alias>, <atom>except:</atom> [<atom>spawn:</atom> 1, <atom>+:</atom> 2, <atom>/:</atom> 2, <atom>unless:</atom> 2]
<predefined-call><macro-call>alias</macro-call></predefined-call> <alias>Long.Module.Name</alias>, <atom>as:</atom> <alias>N0men123_and4</alias>
<predefined-call><macro-call>use</macro-call></predefined-call> <alias>Bitwise</alias>

4 &&& 5
2 <<< 3

# Protocols
<predefined-call><macro-call>defprotocol</macro-call></predefined-call> <alias>Useless</alias> do
  <predefined-call><macro-call>def</macro-call></predefined-call> func1(<parameter>this</parameter>)
  <predefined-call><macro-call>def</macro-call></predefined-call> func2(<parameter>that</parameter>)
end

<predefined-call><macro-call>defimpl</macro-call></predefined-call> <alias>Useless</alias>, <atom>for:</atom> <alias>Atom</alias> do
end

# Exceptions
<predefined-call><macro-call>defmodule</macro-call></predefined-call> <alias>NotAnError</alias> do
  <predefined-call><macro-call>defexception</macro-call></predefined-call> [:message]
end

<predefined-call><macro-call>raise</macro-call></predefined-call> <alias>NotAnError</alias>, <atom>message:</atom> <string>"This is not an error"</string>
