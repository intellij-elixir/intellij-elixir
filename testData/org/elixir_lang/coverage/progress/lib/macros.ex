defmodule Progress.Macros do
  defmacro wrap(x), do: receive(do: (y -> y), after: (0 -> x))
end
