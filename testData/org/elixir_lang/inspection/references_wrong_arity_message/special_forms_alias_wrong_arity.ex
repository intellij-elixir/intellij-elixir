defmodule SpecialFormsAliasWrongArity do
  def caller do
    alias(A, B, C)
  end
end
