# Elixir's answers for EctoFixtureTest, written as ETF to the directory ECTO_FIXTURE_ORACLE names, once the fixture has
# compiled: the functions and macros of the Queries, Repo and schema modules, each schema's struct keys, each call of a
# schema's functions through lib/probe.ex and what it expands to, and each `Macro.expand_once/2` step of every function
# body in lib/queries.ex, in the env Elixir expands it in.
out = System.fetch_env!("ECTO_FIXTURE_ORACLE")

schemas = [EctoFixture.Comment, EctoFixture.Post, EctoFixture.Tag, EctoFixture.Author, EctoFixture.Book]

info =
  for module <- [EctoFixture.Queries, EctoFixture.Repo | schemas], into: %{} do
    {module, %{functions: module.__info__(:functions), macros: module.__info__(:macros)}}
  end

File.write!(Path.join(out, "info.etf"), :erlang.term_to_binary(info))

File.write!(
  Path.join(out, "structs.etf"),
  :erlang.term_to_binary(for module <- schemas, into: %{}, do: {module, Enum.sort(Map.keys(struct(module)) -- [:__struct__])})
)

File.write!(
  Path.join(out, "derived.etf"),
  :erlang.term_to_binary(for module <- [Inspect.EctoFixture.Tag, Inspect.EctoFixture.Author, Inspect.EctoFixture.Book], into: %{}, do: {module, Code.ensure_loaded?(module)})
)

Code.ensure_loaded(Ecto.Schema)
File.write!(Path.join(out, "after_compile.etf"), :erlang.term_to_binary(function_exported?(Ecto.Schema, :__after_compile__, 2)))

require EctoFixture.Probe

called = fn module, function, args -> quote(do: EctoFixture.Probe.called(unquote(module), unquote(function), unquote(args))) end

reflections = [:source, :prefix, :fields, :query_fields, :primary_key, :associations, :embeds, :virtual_fields, :redact_fields, :autogenerate_id, :loaded, :hash]

calls =
  Enum.flat_map(schemas, fn module ->
    fields = module.__schema__(:fields) ++ module.__schema__(:virtual_fields)

    [called.(module, :__struct__, []), called.(module, :__changeset__, [])] ++
      Enum.map(reflections, &called.(module, :__schema__, [&1])) ++
      Enum.flat_map(fields, &[called.(module, :__schema__, [:type, &1]), called.(module, :__schema__, [:field_source, &1])]) ++
      Enum.map(module.__schema__(:associations), &called.(module, :__schema__, [:association, &1])) ++
      Enum.map(module.__schema__(:embeds), &called.(module, :__schema__, [:embed, &1]))
  end)

File.write!(Path.join(out, "calls.etf"), :erlang.term_to_binary(Enum.map(calls, &{&1, Macro.expand_once(&1, __ENV__)})))

can_await = called.(EctoFixture.CompileTime, :can_await, [])

File.write!(Path.join(out, "can_await.etf"), :erlang.term_to_binary({can_await, Macro.expand_once(can_await, __ENV__)}))

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
