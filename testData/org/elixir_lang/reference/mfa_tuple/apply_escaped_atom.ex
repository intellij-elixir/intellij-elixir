defmodule ApplyTarget do
  def reverse(list), do: list
end

defmodule Usage do
  def apply_reverse do
    apply(ApplyTarget, :"\x72ev<caret>erse", [[1, 2, 3]])
  end
end
