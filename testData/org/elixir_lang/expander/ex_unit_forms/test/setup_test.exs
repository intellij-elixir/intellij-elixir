defmodule ExUnitForms.SetupTest do
  use ExUnit.Case

  setup_all do
    [all: 1]
  end

  setup_all :all_by_atom

  setup do
    [b: 1]
  end

  setup context do
    [c: context.b]
  end

  setup :by_atom
  setup [:by_list_a, :by_list_b]

  test "uses setups", %{c: c, all: all} do
    assert c == all
  end

  defp all_by_atom(_), do: :ok
  defp by_atom(_), do: [atom: true]
  defp by_list_a(_), do: :ok
  defp by_list_b(_), do: :ok
end
