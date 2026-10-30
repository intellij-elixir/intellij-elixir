defmodule ExUnitForms.DescribeTest do
  use ExUnit.Case

  setup do
    [user: :alice]
  end

  describe "group" do
    setup context do
      [in_describe: context.user]
    end

    setup :describe_atom

    test "inside", %{in_describe: who} do
      assert who == :alice
    end
  end

  defp describe_atom(_), do: :ok
end
