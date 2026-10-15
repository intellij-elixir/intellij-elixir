defmodule ApplySites do
  def fresh(x), do: x

  def renamee(x, y), do: {x, y}

  def run(x), do: apply(ApplySites, :fresh, [x])

  def run(x, y), do: apply(ApplySites, :renamee, [x, y])
end
