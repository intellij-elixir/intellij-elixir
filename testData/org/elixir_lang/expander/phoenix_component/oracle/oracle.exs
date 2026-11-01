# Elixir's answers for PhoenixComponentFixtureTest, written as ETF to the directory PHOENIX_COMPONENT_ORACLE names once
# the fixture has compiled: each module's functions and macros, each call of lib/probe.ex's macros and what it expands to.
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
