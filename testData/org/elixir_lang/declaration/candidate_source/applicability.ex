defmodule Target do
  def foo(x), do: x
  def foo_bar(x), do: x
  def bar(x), do: x
  def bar_baz(x), do: x
end

defmodule Chain do
  defdelegate size(x), to: Target, as: :bar
end

defmodule Uses do
  require EEx
  require Mix.Generator

  defdelegate foo(x), to: Target
  defdelegate renamed(x), to: Target, as: :bar
  defdelegate chained(x), to: Chain, as: :size
  defexception [:message]
  def fo_local(x), do: x
  EEx.function_from_string(:def, :"quoted_eex", "<%= a %>", [:a])
  Mix.Generator.embed_template(:page, "<%= @a %>")

  def uses do
    fo(1)
    foo(1)
    ren(1)
    chained(1, 2)
    exception(1)
    quoted_eex(1, 2)
    page_template()
  end

  defmacro dynamic(f) do
    quote do
      Uses.unquote(f)(1)
    end
  end
end

defmodule FitTarget do
  def f(a, b), do: {a, b}
  def h(a, b \\ 1), do: {a, b}
end

defmodule FitDelegator do
  defdelegate f(a, b \\ 1), to: FitTarget
  defdelegate one(a), to: FitTarget, as: :f
  defdelegate two(a, b), to: FitTarget, as: :h

  defmacro dynamic(name) do
    quote do
      FitDelegator.unquote(name)(1)
    end
  end
end

defmodule FitChain do
  defdelegate g(a), to: FitDelegator, as: :f

  defmacro dynamic(name) do
    quote do
      FitChain.unquote(name)(1)
    end
  end
end

defmodule OpenTarget do
  def open(a, b), do: {a, b}
  def open(a, b, c), do: {a, b, c}
end

defmodule OpenDelegator do
  defdelegate open(a, unquote_splicing(rest)), to: OpenTarget

  def uses, do: OpenDelegator.open(1, 2)
end

defmodule PairTarget do
  def pair(a, b), do: {a, b}
end

defmodule PairDelegator do
  defdelegate pair(a, b \\ 1), to: PairTarget
end

defmodule PairUser do
  import PairDelegator, only: [pair: 2]

  def uses, do: pair(1)
end

defmodule OpenUser do
  import OpenDelegator, only: [open: 2]

  def uses, do: open(1)
end

defmodule PrefixUses do
  def uses do
    Uses.fo_local(1)
    Uses.foo(1)
    Uses.renamed(1)
  end
end
