defmodule Target do
  def map(_left, _right), do: :ok
end

defmodule Usage do
  def run(module) do
    {:"#{module}", :ma<caret>p, 2}
  end
end
