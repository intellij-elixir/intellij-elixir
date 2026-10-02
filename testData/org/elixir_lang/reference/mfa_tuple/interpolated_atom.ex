defmodule Target do
  def map(_left, _right), do: :ok
  def unquote(:"\"ma\#{x}p\"")(_left, _right), do: :ok
end

defmodule Usage do
  def run(x) do
    {Target, :"m<caret>a#{x}p", 2}
  end
end
