max(some_value, [1, 2, {3, 4}, %{b => 6, a: 5}, <<7, 8>>, [key: :value]])
fn x -> x end
fn
  1 -> :one
  y when is_integer(y) -> y
end
for x <- [1], into: %{}, do: {x, x}
with {:ok, x} <- {:ok, 1}, do: x, else: (_ -> :error)
