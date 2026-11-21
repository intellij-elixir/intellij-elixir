defmodule BeamQualifiedCallMultiClauseGoto do
  def cons(x, q) do
    :queue.in_<caret>r(x, q)
  end
end
