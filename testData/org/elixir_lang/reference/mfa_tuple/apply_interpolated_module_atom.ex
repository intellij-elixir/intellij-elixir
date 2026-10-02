defmodule ApplyTarget do
  def reverse(list), do: list
end

defmodule Usage do
  def apply_reverse(module) do
    apply(:"Elixir.#{module}", :rev<caret>erse, [[1, 2, 3]])
  end
end
