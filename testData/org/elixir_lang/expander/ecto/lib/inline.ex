defmodule EctoFixture.Inline do
  use Ecto.Schema

  embedded_schema do
    embeds_one :meta, Meta do
      field :note, :string
    end
  end
end
