defmodule OpenInterval do
  def foo(a, unquote_splicing(rest)), do: [a | rest]

  def caller do
    foo()
  end
end
