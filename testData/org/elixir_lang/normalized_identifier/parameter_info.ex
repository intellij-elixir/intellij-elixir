defmodule NormalizedParameterInfo do
  def snoć(augend, addend), do: augend + addend

  def caller do
    snoć<caret>
  end
end
