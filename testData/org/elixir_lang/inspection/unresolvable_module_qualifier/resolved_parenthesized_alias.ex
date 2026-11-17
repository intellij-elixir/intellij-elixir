defmodule ResolvedParenthesizedAliasTest do
  alias (MyNamespace.Referenced)

  Referenced.changeset(%{})
end
