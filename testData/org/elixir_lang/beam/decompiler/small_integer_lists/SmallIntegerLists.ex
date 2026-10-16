# One module per shape, so a shape that fails to print loses only its own module.
# Each `body` is a quoted form with a list of integers 0..255 where the printer reads a list.

defmodule SmallIntegerLists.FnClause do
  def body, do: fn 1 -> :a end
end

defmodule SmallIntegerLists.FnClauseArguments do
  def body, do: fn 1, 2 -> :a end
end

defmodule SmallIntegerLists.CaseClause do
  def body(x), do: (case x do 1 -> :a end)
end

defmodule SmallIntegerLists.RemoteCall do
  def body, do: Integer.pow(2, 3)
end

defmodule SmallIntegerLists.LocalCall do
  def body, do: helper(1, 2)
  defp helper(a, b), do: {a, b}
end

defmodule SmallIntegerLists.BinaryOperator do
  def body, do: 1 + 2
end

defmodule SmallIntegerLists.ListLiteral do
  def body, do: [1, 2, 3]
end

defmodule SmallIntegerLists.NestedListLiteral do
  def body, do: [[1, 2], [3]]
end

defmodule SmallIntegerLists.ImproperList do
  def body, do: [1 | 2]
end

defmodule SmallIntegerLists.Block do
  def body, do: (Integer.pow(2, 3); 4)
end

defmodule SmallIntegerLists.IntegerBlock do
  def body, do: (1; 2)
end

defmodule SmallIntegerLists.Tuple do
  def body, do: {1, 2, 3}
end

defmodule SmallIntegerLists.Map do
  def body, do: %{1 => 2}
end
