defmodule Bound do
  def snoc(q, x), do: {q, x}

  def run(a, b), do: (fn snoc -> snoc end).({a, b})
end
