defmodule CensusTracer do
  # Mix changes into each dependency's directory to compile it, so the project's own events are the ones from here
  @project File.cwd!()

  def trace({kind, meta, module, name, arity}, env) when kind in [:imported_macro, :remote_macro],
    do: record(kind, meta, module, name, arity, env)

  def trace({:local_macro, meta, name, arity}, env),
    do: record(:local_macro, meta, env.module, name, arity, env)

  def trace(_event, _env), do: :ok

  # Mix evaluates each `mix.exs` outside the compiler's parallel compile, where no module can be awaited
  defp record(kind, meta, receiver, name, arity, env) do
    if Code.can_await_module_compilation?() do
      app = Mix.Project.config()[:app]

      library =
        case :application.get_application(receiver) do
          {:ok, library} -> library
          :undefined -> app
        end

      fields = [
        kind,
        receiver,
        name,
        arity,
        Path.relative_to_cwd(env.file),
        meta[:line] || "none",
        meta[:column] || "none",
        app,
        library,
        File.cwd!() == @project,
        Code.get_compiler_option(:parser_options)[:columns] == true
      ]

      File.write!(
        System.fetch_env!("ELIXIR_LANG_CENSUS_OUTPUT"),
        [Enum.map_join(fields, "\t", &to_string/1), "\n"],
        [:append]
      )
    end

    :ok
  end
end

Code.put_compiler_option(:tracers, [CensusTracer])
