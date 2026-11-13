defmodule EctoFixture.InlineAsker do
  alias EctoFixture.Inline.Meta

  def shout(meta), do: Meta.shout(meta)
  def fields, do: Meta.__schema__(:fields)
  def changeset, do: Meta.__changeset__()
  def struct, do: Meta.__struct__()
end
