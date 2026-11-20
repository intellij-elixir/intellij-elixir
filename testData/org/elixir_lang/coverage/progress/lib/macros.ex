defmodule Progress.Macros do
  defmacro wrap(x) do
    :erlang.send(:erlang.self(), :decline)

    receive do
      :decline -> x
      y -> y
    after
      0 -> x
    end
  end
end
