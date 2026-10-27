defmodule ExUnitForms.TagsTest do
  use ExUnit.Case
  @moduletag :mod_tag

  @tag :t
  test "tagged atom" do
    assert true
  end

  @tag k: :v
  test "tagged keyword" do
    assert true
  end

  describe "tagged group" do
    @describetag :d

    test "in group" do
      assert true
    end
  end
end
