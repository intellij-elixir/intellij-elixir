defmodule :tag_target do
  def f(a), do: a
end

defmodule Usage do
  def example(reason) do
    {:error, :tag_<caret>target, reason}
  end
end
