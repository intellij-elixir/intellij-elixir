defmodule Kernel do
  defmacro if(condition, clauses), do: {condition, clauses}
end

defmodule Usage do
  @doc delegate_to: {Kernel, :i<caret>f, 2}
end
