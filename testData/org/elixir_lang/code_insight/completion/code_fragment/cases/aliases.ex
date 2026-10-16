defmodule Outer do
  alias Hello.World
  require Logger
  import Enum, only: [map: 2]
  @mod Outer
  def f(x) do
    [World, __MODULE__.Sub, __MODULE__.Sub.Leaf, __MODULE__.Sub.f(), @mod.Sub, x.Sub, x.key.Sub, Outer.Inner.Leaf]
  end
end
