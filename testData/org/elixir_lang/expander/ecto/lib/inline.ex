defmodule EctoFixture.Inline do
  use Ecto.Schema

  embedded_schema do
    embeds_one :meta, Meta do
      field :note, :string

      def shout(%{note: note}), do: note
    end
  end
end
