# Elixir's answers for EctoFixtureTest, written as ETF to the directory ECTO_FIXTURE_ORACLE names, once the fixture has
# compiled: the functions and macros of the Queries and Repo modules, and each `Macro.expand_once/2` step of every
# function body in lib/queries.ex, in the env Elixir expands it in.
out = System.fetch_env!("ECTO_FIXTURE_ORACLE")

info =
  for module <- [EctoFixture.Queries, EctoFixture.Repo], into: %{} do
    {module, %{functions: Enum.sort(module.__info__(:functions)), macros: Enum.sort(module.__info__(:macros))}}
  end

File.write!(Path.join(out, "info.etf"), :erlang.term_to_binary(info))

defmodule EctoFixture.Oracle do
  # The body's steps, each until `Macro.expand_once/2` leaves it unchanged, with the body first.
  defmacro steps(form, body) do
    env = __CALLER__

    chain =
      Stream.unfold(body, fn current ->
        expanded = Macro.expand_once(current, env)
        if expanded == current, do: nil, else: {expanded, expanded}
      end)
      |> Enum.to_list()

    File.write!(Path.join([System.fetch_env!("ECTO_FIXTURE_ORACLE"), "steps", "#{form}.etf"]), :erlang.term_to_binary([body | chain]))

    :ok
  end
end

File.mkdir_p!(Path.join(out, "steps"))

# Compiled again under its own name and file, so each body keeps its line and its `{Module, n}` counters.
path = Path.expand("lib/queries.ex")

path
|> File.read!()
|> Code.string_to_quoted!(file: path)
|> Macro.prewalk(fn
  {:defmodule, meta, [alias, [do: {:__block__, block, statements}]]} ->
    {:defmodule, meta, [alias, [do: {:__block__, block, [quote(do: require(EctoFixture.Oracle)) | statements]}]]}

  {:def, meta, [{name, _, _} = head, [do: body]]} ->
    {:def, meta, [head, [do: {{:., meta, [EctoFixture.Oracle, :steps]}, meta, [name, body]}]]}

  other ->
    other
end)
|> Code.compile_quoted(path)
