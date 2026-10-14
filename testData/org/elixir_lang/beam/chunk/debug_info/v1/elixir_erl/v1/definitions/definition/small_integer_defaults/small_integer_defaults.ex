# The `.beam` beside this is `elixirc small_integer_defaults.ex -o .` of it, by Elixir 1.20.4. The default clauses
# `one/0` and `two/0` call `super` with `[1]` and `[1, 2]`, lists of small integers, which the `.beam` stores as byte
# strings; `two/1` calls it with `[x0, 2]`.
defmodule SmallIntegerDefaults do
  def one(a \\ 1), do: a
  def two(a \\ 1, b \\ 2), do: {a, b}
end
