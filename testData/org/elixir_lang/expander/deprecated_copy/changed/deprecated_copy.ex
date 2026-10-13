# The `.beam` beside this is `elixirc deprecated_copy.ex -o .` of it: `DeprecatedCopy` rebuilt with another reason.
defmodule DeprecatedCopy do
  @deprecated "Use newer/0 instead"
  def old, do: 1
end
