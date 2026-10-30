defmodule ExUnitForms.AsyncTest do
  use ExUnit.Case, async: true

  setup do
    [user: :alice]
  end

  test "pattern", %{user: user} do
    assert user == :alice
  end

  test "context", context do
    assert context.user == :alice
  end

  test "pattern and context", %{user: user} = context do
    assert user == context.user
  end

  test "inline", %{user: user}, do: assert(user == :alice)

  test """
  heredoc name
  """ do
    assert true
  end

  test "interpolated #{1 + 1}" do
    assert true
  end

  test "line #{__ENV__.line}" do
    assert true
  end
end
