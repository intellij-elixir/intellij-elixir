defmodule ExUnitBodyOrigins.WrapperTest do
  use ExUnit.Case

  test "one", c do
    c
  end

  test "two", c do
    a = c
    a
  end

  test "three", c do
    a = c
    b = a
    b
  end

  test "ok last", c do
    a = c
    :ok
  end
end
