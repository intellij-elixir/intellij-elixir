defmodule Target do
  def map(_left, _right), do: :ok
end

defmodule Usage do
  @doc delegate_to: {:"Elixir.Target", :ma<caret>p, 2}
end
