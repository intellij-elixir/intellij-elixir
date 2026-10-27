defmodule ExUnitForms.UsesDepCaseTest do
  use ExUnitForms.DepCase, async: true

  test "from dependency template", context do
    assert context.from_template
  end
end
