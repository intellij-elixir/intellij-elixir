defmodule ApplySites do
  def renamee(x), do: x

  def fresh(x, y), do: {x, y}

  def run(x), do: apply(ApplySites, :renamee, [x])

  def run(x, y), do: apply(ApplySites, :fresh, [x, y])
end
