defmodule ApplyTarget do
  def my_key(list), do: list
end

defmodule Usage do
  def apply_option(opts) do
    apply(ApplyTarget, Keyword.get(opts, :"my_<caret>key"), [[1, 2, 3]])
  end
end
