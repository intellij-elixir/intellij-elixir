defmodule Progress.Macros do
  def send_self(module, ref), do: :erlang.send(:erlang.self(), {module, ref})

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
