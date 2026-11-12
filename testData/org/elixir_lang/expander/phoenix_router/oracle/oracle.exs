# Run as `mix run --no-start --no-compile oracle/oracle.exs` once the project compiles: for each router the project
# compiled, and each in `routers/`, which Mix doesn't compile, its functions and macros, its `__routes__/0` and its
# `Helpers`' functions and macros, written as one term to `$PHOENIX_ROUTER_ORACLE/routers.etf`.
out = System.fetch_env!("PHOENIX_ROUTER_ORACLE")
{:ok, compiled} = :application.get_key(Mix.Project.config()[:app], :modules)

others =
  for file <- Path.wildcard("routers/*.ex"), {module, _} <- Code.compile_file(file), do: module

routers =
  for module <- compiled ++ others, Code.ensure_loaded?(module), function_exported?(module, :__routes__, 0), into: %{} do
    helpers = Module.concat(module, Helpers)
    loaded = Code.ensure_loaded?(helpers)

    {Atom.to_string(module),
     %{
       functions: module.__info__(:functions),
       macros: module.__info__(:macros),
       routes: Enum.map(module.__routes__(), &{&1.verb, &1.path, &1.plug, &1.plug_opts}),
       helpers: if(loaded, do: %{functions: helpers.__info__(:functions), macros: helpers.__info__(:macros)})
     }}
  end

File.write!(Path.join(out, "routers.etf"), :erlang.term_to_binary(routers))
