defmodule :tag_target do
  def f(a), do: a
end

defmodule Usage do
  def example(reason) do
    {Missing, :tag_<caret>target, reason}
  end
end
