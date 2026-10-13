defmodule :tag_target do
  def f(a), do: a
end

defmodule Usage do
  def example do
    {:tag_target, :<caret>f, [:a]}
  end
end
