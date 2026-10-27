defmodule EctoFixture.Probe do
  defmacro compile_config do
    Macro.escape(Ecto.Repo.Supervisor.compile_config(EctoFixture.Repo, otp_app: :ecto_fixture, adapter: Ecto.Adapters.Postgres))
  end
end
