defmodule Target do
  def foo(x), do: x
  def foo_bar(x), do: x
  def bar(x), do: x
  def bar_baz(x), do: x
end

defmodule Chain do
  defdelegate size(x), to: Target, as: :bar
end

defmodule Forms do
  require EEx
  require Mix.Generator

  defdelegate foo(x), to: Target
  defdelegate renamed(x), to: Target, as: :bar
  defdelegate chained(x), to: Chain, as: :size
  defdelegate quoted_as(x), to: Target, as: :"bar"
  defexception [:message]
  def fo_local(x), do: x
  EEx.function_from_string(:def, :"quoted_eex", "", [])
  Mix.Generator.embed_text(:"quoted", "Quoted")

  def uses do
    fo(1)
    foo(1, 2)
    foo(1)
    renamed(1)
    renamed(1, 2)
    ren(1)
    chained(1)
    chained(1, 2)
    quoted_as(1)
    exception(1)
    message(1)
    e(1)
    quoted_eex()
    quoted_text()
    quoted_eex(1)
  end

  defmacro dynamic(f) do
    quote do
      Forms.unquote(f)(1)
    end
  end

  def two(a, b), do: {a, b}
  def two(a), do: a
  def more, do: two(1)
end

defmodule ChainUse do
  def run, do: Chain.size(1)
end
