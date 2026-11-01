defmodule EctoFixture.Probe do
  defmacro compile_config do
    Macro.escape(Ecto.Repo.Supervisor.compile_config(EctoFixture.Repo, otp_app: :ecto_fixture, adapter: Ecto.Adapters.Postgres))
  end

  defmacro can_await, do: Code.can_await_module_compilation?()

  defmacro called(module, function, args), do: Macro.escape(apply(Macro.expand(module, __CALLER__), function, args))
end
