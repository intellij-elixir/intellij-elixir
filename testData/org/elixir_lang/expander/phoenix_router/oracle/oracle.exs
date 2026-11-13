# Run as `mix run --no-start --no-compile oracle/oracle.exs` once the project compiles: for each router the project
# compiled, and each in `routers/`, which Mix doesn't compile, its functions and macros, its `__routes__/0`, its
# `Helpers`' functions and macros, and where the compiled `Helpers` declares each clause, written as one term to
# `$PHOENIX_ROUTER_ORACLE/routers.etf`.
out = System.fetch_env!("PHOENIX_ROUTER_ORACLE")
{:ok, compiled} = :application.get_key(Mix.Project.config()[:app], :modules)

others =
  for file <- Path.wildcard("routers/*.ex"), {module, _} <- Code.compile_file(file), do: module

# Where the compiled module declares each clause of each definition: its file, relative to the project, and its line;
# `nil` for a module with no `.beam`, as one `routers/` compiles in memory.
declared = fn module ->
  with path when is_list(path) <- :code.which(module),
       {:ok, {_, [debug_info: {:debug_info_v1, backend, data}]}} <- :beam_lib.chunks(path, [:debug_info]),
       {:ok, %{file: file, definitions: definitions}} <- backend.debug_info(:elixir_v1, module, data, []) do
    for {{name, arity}, _kind, _meta, clauses} <- definitions, {meta, _, _, _} <- clauses do
      {name, arity, Path.relative_to(file, File.cwd!()), meta[:line]}
    end
  else
    _ -> nil
  end
end

routers =
  for module <- compiled ++ others, Code.ensure_loaded?(module), function_exported?(module, :__routes__, 0), into: %{} do
    helpers = Module.concat(module, Helpers)
    loaded = Code.ensure_loaded?(helpers)

    {Atom.to_string(module),
     %{
       functions: module.__info__(:functions),
       macros: module.__info__(:macros),
       routes: Enum.map(module.__routes__(), &{&1.verb, &1.path, &1.plug, &1.plug_opts}),
       helpers: if(loaded, do: %{functions: helpers.__info__(:functions), macros: helpers.__info__(:macros)}),
       helper_lines: if(loaded, do: declared.(helpers))
     }}
  end

File.write!(Path.join(out, "routers.etf"), :erlang.term_to_binary(routers))
