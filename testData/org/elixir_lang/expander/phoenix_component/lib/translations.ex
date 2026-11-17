defmodule PhoenixComponentFixture.Translations do
  # A function for each catalogue, listed as Gettext's compiler lists them: a pattern relative to where Mix compiles.
  for path <- Path.wildcard(Path.join("priv/gettext", "*/LC_MESSAGES/*.po")) do
    @external_resource path
    locale = path |> Path.dirname() |> Path.dirname() |> Path.basename()
    name = String.to_atom(locale <> "_" <> Path.basename(path, ".po"))

    def unquote(name)(), do: unquote(File.read!(path))
  end
end
