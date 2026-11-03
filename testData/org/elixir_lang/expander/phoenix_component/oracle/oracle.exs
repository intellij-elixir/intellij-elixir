# Elixir's answers for PhoenixComponentFixtureTest, written as ETF to the directory PHOENIX_COMPONENT_ORACLE names once
# the fixture has compiled: each module's functions and macros, each call of lib/probe.ex's macros and what it expands to,
# and how each module of routes/ compiles.
out = System.fetch_env!("PHOENIX_COMPONENT_ORACLE")

modules = [PhoenixComponentFixture.Remote, PhoenixComponentFixture.Forms, PhoenixComponentFixture.Globals]

info =
  for module <- modules, into: %{} do
    {module, %{functions: Enum.sort(module.__info__(:functions)), macros: Enum.sort(module.__info__(:macros))}}
  end

File.write!(Path.join(out, "info.etf"), :erlang.term_to_binary(info))

require PhoenixComponentFixture.Probe

calls =
  Enum.map(modules, &quote(do: PhoenixComponentFixture.Probe.components(unquote(&1)))) ++
    Enum.map(
      ["x-on", "form", "class", "aria-label", "data-a", "nope"],
      &quote(do: PhoenixComponentFixture.Probe.global?(PhoenixComponentFixture.Globals, unquote(&1)))
    ) ++
    Enum.map(
      [
        {PhoenixComponentFixture.Forms, :bodied, [label: "x"]},
        {PhoenixComponentFixture.Forms, :bodied, [label: "x", tone: :warn]},
        {PhoenixComponentFixture.Forms, :headed, []},
        {PhoenixComponentFixture.Forms, :headed, [on: true]},
        {PhoenixComponentFixture.Forms, :table, [col: []]},
        {PhoenixComponentFixture.Forms, :calling, []},
        {PhoenixComponentFixture.Remote, :pill, [label: "x"]}
      ],
      fn {module, function, assigns} ->
        quote(do: PhoenixComponentFixture.Probe.called(unquote(module), unquote(function), unquote(assigns)))
      end
    )

File.write!(
  Path.join(out, "calls.etf"),
  :erlang.term_to_binary(Enum.map(calls, &{&1, Macro.expand_once(&1, __ENV__)}))
)

# Each module of routes/ compiled on its own, as routes.etf: by file name, how its compile ended, its `__info__` and
# what `@phoenix_verified_routes` ended up holding. Phoenix writes that attribute on every Elixir but reads it back,
# in `__phoenix_verify_routes__/1`, only from 1.14, so each module is compiled with a `@before_compile` that reads it.
defmodule PhoenixComponentFixtureOracle.Routes do
  defmacro __before_compile__(env) do
    :persistent_term.put({__MODULE__, env.module}, Module.get_attribute(env.module, :phoenix_verified_routes))
    nil
  end
end

routes =
  for file <- "routes/*.ex" |> Path.wildcard() |> Enum.sort(), into: %{} do
    source =
      file
      |> File.read!()
      |> String.replace("do\n", "do\n  @before_compile PhoenixComponentFixtureOracle.Routes\n", global: false)

    outcome =
      try do
        [{module, _}] = Code.compile_string(source, file)

        %{
          raised: nil,
          functions: Enum.sort(module.__info__(:functions)),
          macros: Enum.sort(module.__info__(:macros)),
          routes: :persistent_term.get({PhoenixComponentFixtureOracle.Routes, module})
        }
      rescue
        e -> %{raised: e.__struct__}
      end

    {Path.basename(file, ".ex"), outcome}
  end

File.write!(Path.join(out, "routes.etf"), :erlang.term_to_binary(routes))
