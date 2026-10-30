defmodule ExUnitForms.UsesSourceCaseTest do
  use ExUnitForms.SourceCase, async: true

  test "from source template", context do
    assert context.from_template
  end
end
