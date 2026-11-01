defmodule PhoenixComponentFixture.Remote do
  use Phoenix.Component

  attr :label, :string, required: true

  def pill(assigns), do: Map.take(assigns, [:label])
end

defmodule PhoenixComponentFixture.Forms do
  use Phoenix.Component

  @tones [:info, :warn]
  @default_tone :info

  @doc "A badge."
  attr :label, :string, required: true, doc: "What it says."
  attr :tone, :atom, default: @default_tone, values: @tones
  slot :inner_block

  def bodied(assigns), do: Map.take(assigns, [:label, :tone])

  attr :on, :boolean, default: false

  def headed(assigns)
  def headed(%{on: true} = assigns), do: Map.put(assigns, :state, :on)
  def headed(assigns), do: Map.put(assigns, :state, :off)

  attr :secret, :string, default: "s"

  defp private(assigns), do: Map.take(assigns, [:secret])

  attr :rows, :list, default: []

  slot :col, required: true, doc: "A column." do
    attr :title, :string, required: true
    attr :width, :integer
  end

  def table(assigns), do: Map.take(assigns, [:rows, :col])

  attr :label, :string, default: "both"

  def calling(assigns), do: {bodied(assigns), private(%{}), PhoenixComponentFixture.Remote.pill(assigns)}
end

defmodule PhoenixComponentFixture.Globals do
  use Phoenix.Component, global_prefixes: ~w(x-)

  attr :rest, :global, include: ~w(form), default: %{"class" => "a"}

  def spread(assigns), do: assigns.rest
end
