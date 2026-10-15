defmodule Shape do
  @callback surface(number) :: number
end

defmodule Geometry do
  def square_area(side), do: side * side
end

defmodule Square do
  @behaviour Shape

  @impl true
  @spec surface(number) :: number
  defdelegate surface(side), to: Geometry, as: :square_area

  def double(side), do: surface(side) * 2

  def capture, do: &surface/1
end

defmodule ShapeClient do
  import Square, only: [surface: 1]

  def by_implementation(side), do: Square.surface(side)
end
