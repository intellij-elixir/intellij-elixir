# The `.beam` beside this is `elixirc deprecated_copy.ex -o .` of it.
defmodule DeprecatedCopy do
  @deprecated "Use new/0 instead"
  def old, do: 1
end
