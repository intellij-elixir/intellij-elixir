# Elixir's expansion of lib/probe.ex's macro, which escapes `Ecto.Repo.Supervisor.compile_config/2` for the fixture's
# Repo, written as ETF to compile_config.etf in the directory ECTO_FIXTURE_ORACLE names: the call, then what it expands
# to.
require EctoFixture.Probe

call = quote(do: EctoFixture.Probe.compile_config())

File.write!(
  Path.join(System.fetch_env!("ECTO_FIXTURE_ORACLE"), "compile_config.etf"),
  :erlang.term_to_binary({call, Macro.expand_once(call, __ENV__)})
)
