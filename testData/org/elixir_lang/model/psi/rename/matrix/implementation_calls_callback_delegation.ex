defmodule Shape do
  @callback area(number) :: number
end

defmodule Geometry do
  def square_area(side), do: side * side
end

defmodule Square do
  @behaviour Shape

  @impl true
  @spec area(number) :: number
  defdelegate area(side), to: Geometry, as: :square_area

  def double(side), do: area(side) * 2

  def capture, do: &area/1
end

defmodule ShapeClient do
  import Square, only: [area: 1]

  def by_implementation(side), do: Square.area(side)
end
