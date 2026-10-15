defmodule ApplySites do
  def renamee(x), do: x

  def renamee(x, y), do: {x, y}

  def run(x), do: apply(ApplySites, :renamee, [x])

  def run(x, y), do: apply(ApplySites, :renamee, [x, y])
end
