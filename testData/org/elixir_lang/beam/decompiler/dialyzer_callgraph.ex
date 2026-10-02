# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :dialyzer_callgraph do

  # Types

  @opaque callgraph :: callgraph()

  @type callgraph_edge :: {mfa_or_funlbl(), mfa_or_funlbl()}

  @type mfa_or_funlbl :: (label() | mfa())

  @type mod_deps :: :dict.dict(module(), [module()])

  # Private Types

  @typep active_digraph :: ({:d, :digraph.graph()} | {:e, out :: :ets.tid(), erlangVariableIn :: :ets.tid(), map :: :ets.tid()})

  @typep anal_type :: (:succ_typings | :plt_build)

  @typep anal_type1 :: (anal_type() | :plt_add | :plt_check | :plt_remove)

  @typep call_tab :: :sets.set(mfa_call())

  @typep contr_constr :: {:subtype, :erl_types.erl_type(), :erl_types.erl_type()}

  @typep contract_pair :: {:erl_types.erl_type(), [contr_constr()]}

  @typep dial_define :: {atom(), term()}

  @typep dial_error :: any()

  @typep dial_option :: {atom(), term()}

  @typep dial_options :: [dial_option()]

  @typep dial_ret :: (0 | 1 | 2)

  @typep dial_warn_tag :: (:warn_return_no_exit | :warn_return_only_exit | :warn_not_called | :warn_non_proper_list | :warn_matching | :warn_opaque | :warn_fun_app | :warn_failing_call | :warn_bin_construction | :warn_contract_types | :warn_contract_syntax | :warn_contract_not_equal | :warn_contract_subtype | :warn_contract_supertype | :warn_callgraph | :warn_umatched_return | :warn_race_condition | :warn_behaviour | :warn_contract_range | :warn_undefined_callbacks | :warn_unknown | :warn_map_construction)

  @typep dial_warn_tags :: :ordsets.ordset(dial_warn_tag())

  @typep dial_warning :: {dial_warn_tag(), file_line(), {atom(), [term()]}}

  @typep doc_plt :: (:undefined | :dialyzer_plt.plt())

  @typep file_line :: {:file.filename(), non_neg_integer()}

  @typep fopt :: (:basename | :fullpath)

  @typep format :: (:formatted | :raw)

  @typep iopt :: boolean()

  @typep label :: non_neg_integer()

  @typep m_or_mfa :: (module() | mfa())

  @typep mfa_call :: {mfa_or_funlbl(), mfa_or_funlbl()}

  @typep mfa_calls :: [mfa_call()]

  @typep raw_warning :: {dial_warn_tag(), warning_info(), {atom(), [term()]}}

  @typep rep_mode :: (:quiet | :normal | :verbose)

  @typep scc :: [mfa_or_funlbl()]

  @typep solver :: (:v1 | :v2)

  @typep start_from :: (:byte_code | :src_code)

  @typep warning_info :: {:file.filename(), non_neg_integer(), m_or_mfa()}

  # Functions

  @spec add_edges([callgraph_edge()], callgraph()) :: :ok
  def add_edges([], _CG), do: :ok

  def add_edges(edges, callgraph(digraph: digraph)), do: digraph_add_edges(edges, digraph)

  @spec add_edges([callgraph_edge()], [mfa_or_funlbl()], callgraph()) :: :ok
  def add_edges(edges, mFAs, callgraph(digraph: dG) = cG) do
    digraph_confirm_vertices(mFAs, dG)
    add_edges(edges, cG)
  end

  @spec all_nodes(callgraph()) :: [mfa()]
  def all_nodes(callgraph(digraph: dG)), do: digraph_vertices(dG)

  @spec cleanup(callgraph()) :: callgraph()
  def cleanup(callgraph(digraph: digraph, name_map: nameMap, rev_name_map: revNameMap, race_data_server: raceDataServer)), do: callgraph(digraph: digraph, name_map: nameMap, rev_name_map: revNameMap, race_data_server: :dialyzer_race_data_server.duplicate(raceDataServer))

  @spec delete(callgraph()) :: true
  def delete(callgraph(digraph: digraph)), do: digraph_delete(digraph)

  @spec dispose_race_server(callgraph()) :: :ok
  def dispose_race_server(callgraph(race_data_server: raceDataServer)), do: :dialyzer_race_data_server.stop(raceDataServer)

  @spec duplicate(callgraph()) :: callgraph()
  def duplicate(callgraph(race_data_server: raceDataServer) = callgraph), do: callgraph(callgraph, race_data_server: :dialyzer_race_data_server.duplicate(raceDataServer))

  @spec finalize(callgraph()) :: {[scc()], callgraph()}
  def finalize(callgraph(digraph: dG) = cG) do
    {activeDG, postorder} = condensation(dG)
    {postorder, callgraph(cG, active_digraph: activeDG)}
  end

  @spec get_behaviour_api_calls(callgraph()) :: [{mfa(), mfa()}]
  def get_behaviour_api_calls(callgraph(race_data_server: raceDataServer)), do: :dialyzer_race_data_server.call(:get_behaviour_api_calls, raceDataServer)

  @spec get_depends_on((scc() | module()), callgraph()) :: [scc()]
  def get_depends_on(sCC, callgraph(active_digraph: {:e, out, _In, maps})), do: lookup_scc(sCC, out, maps)

  def get_depends_on(sCC, callgraph(active_digraph: {:d, dG})), do: :digraph.out_neighbours(dG, sCC)

  @spec get_digraph(callgraph()) :: :digraph.graph()
  def get_digraph(callgraph(digraph: digraph)), do: digraph

  @spec get_named_tables(callgraph()) :: [charlist()]
  def get_named_tables(callgraph(race_data_server: raceDataServer)), do: :dialyzer_race_data_server.call(:get_named_tables, raceDataServer)

  @spec get_public_tables(callgraph()) :: [label()]
  def get_public_tables(callgraph(race_data_server: raceDataServer)), do: :dialyzer_race_data_server.call(:get_public_tables, raceDataServer)

  @spec get_race_code(callgraph()) :: :dict.dict()
  def get_race_code(callgraph(race_data_server: raceDataServer)), do: :dialyzer_race_data_server.call(:get_race_code, raceDataServer)

  @spec get_race_detection(callgraph()) :: boolean()
  def get_race_detection(callgraph(race_detection: rD)), do: rD

  @spec in_neighbours(mfa_or_funlbl(), callgraph()) :: (:none | [mfa_or_funlbl(), ...])
  def in_neighbours(label, callgraph(digraph: digraph) = cG) when is_integer(label) do
    name = case lookup_name(label, cG) do
      {:ok, val} ->
        val
      :error ->
        label
    end
    digraph_in_neighbours(name, digraph)
  end

  def in_neighbours({_, _, _} = mFA, callgraph(digraph: digraph)), do: digraph_in_neighbours(mFA, digraph)

  @spec is_escaping(label(), callgraph()) :: boolean()
  def is_escaping(label, callgraph(esc: esc)) when is_integer(label), do: ets_lookup_set(label, esc)

  @spec is_self_rec(mfa_or_funlbl(), callgraph()) :: boolean()
  def is_self_rec(mfaOrLabel, callgraph(self_rec: selfRecs)), do: ets_lookup_set(mfaOrLabel, selfRecs)

  @spec lookup_call_site(label(), callgraph()) :: (:error | {:ok, [any()]})
  def lookup_call_site(label, callgraph(calls: calls)) when is_integer(label), do: ets_lookup_dict(label, calls)

  @spec lookup_label(mfa_or_funlbl(), callgraph()) :: (:error | {:ok, integer()})
  def lookup_label({_, _, _} = mFA, callgraph(rev_name_map: revNameMap)), do: ets_lookup_dict(mFA, revNameMap)

  def lookup_label(label, callgraph()) when is_integer(label), do: {:ok, label}

  @spec lookup_letrec(label(), callgraph()) :: (:error | {:ok, label()})
  def lookup_letrec(label, callgraph(letrec_map: letrecMap)) when is_integer(label), do: ets_lookup_dict(label, letrecMap)

  @spec lookup_name(label(), callgraph()) :: (:error | {:ok, mfa()})
  def lookup_name(label, callgraph(name_map: nameMap)) when is_integer(label), do: ets_lookup_dict(label, nameMap)

  @spec lookup_rec_var(label(), callgraph()) :: (:error | {:ok, mfa()})
  def lookup_rec_var(label, callgraph(rec_var_map: recVarMap)) when is_integer(label), do: ets_lookup_dict(label, recVarMap)

  @spec module_deps(callgraph()) :: mod_deps()
  def module_deps(callgraph(digraph: dG)) do
    edges = :lists.foldl(&edge_fold/2, :sets.new(), digraph_edges(dG))
    nodes = :sets.from_list((for {m, _F, _A} <- digraph_vertices(dG) do
      m
    end))
    mDG = :digraph.new()
    digraph_confirm_vertices(:sets.to_list(nodes), mDG)
    foreach = fn {m1, m2} ->
        check_add_edge(mDG, m1, m2)
    end
    :lists.foreach(foreach, :sets.to_list(edges))
    deps = for n <- :sets.to_list(nodes) do
      {n, :ordsets.from_list(:digraph.in_neighbours(mDG, n))}
    end
    digraph_delete(mDG)
    :dict.from_list(deps)
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(p0) do
    # body not decompiled
  end

  @spec module_postorder_from_funs([mfa_or_funlbl()], callgraph()) :: {[module()], callgraph()}
  def module_postorder_from_funs(funs, callgraph(digraph: dG, active_digraph: aDG) = cG) do
    active_digraph_delete(aDG)
    subGraph = digraph_reaching_subgraph(funs, dG)
    {pO, active} = module_postorder(callgraph(cG, digraph: subGraph))
    digraph_delete(subGraph)
    {pO, callgraph(cG, active_digraph: active)}
  end

  @spec modules(callgraph()) :: [module()]
  def modules(callgraph(digraph: dG)) do
    :ordsets.from_list((for {m, _F, _A} <- digraph_vertices(dG) do
      m
    end))
  end

  @spec new() :: callgraph()
  def new() do
    [eTSEsc, eTSNameMap, eTSRevNameMap, eTSRecVarMap, eTSLetrecMap, eTSSelfRec, eTSCalls] = for n <- [:callgraph_esc, :callgraph_name_map, :callgraph_rev_name_map, :callgraph_rec_var_map, :callgraph_letrec_map, :callgraph_self_rec, :callgraph_calls] do
      :ets.new(n, [:public, {:read_concurrency, true}])
    end
    callgraph(esc: eTSEsc, letrec_map: eTSLetrecMap, name_map: eTSNameMap, rev_name_map: eTSRevNameMap, rec_var_map: eTSRecVarMap, self_rec: eTSSelfRec, calls: eTSCalls)
  end

  @spec non_local_calls(callgraph()) :: mfa_calls()
  def non_local_calls(callgraph(digraph: dG)) do
    edges = digraph_edges(dG)
    find_non_local_calls(edges, :sets.new())
  end

  @spec put_behaviour_api_calls([{mfa(), mfa()}], callgraph()) :: callgraph()
  def put_behaviour_api_calls(calls, callgraph(race_data_server: raceDataServer) = cG) do
    :ok = :dialyzer_race_data_server.cast({:put_behaviour_api_calls, calls}, raceDataServer)
    cG
  end

  @spec put_digraph(:digraph.graph(), callgraph()) :: callgraph()
  def put_digraph(digraph, callgraph), do: callgraph(callgraph, digraph: digraph)

  @spec put_named_tables([charlist()], callgraph()) :: callgraph()
  def put_named_tables(namedTables, callgraph(race_data_server: raceDataServer) = cG) do
    :ok = :dialyzer_race_data_server.cast({:put_named_tables, namedTables}, raceDataServer)
    cG
  end

  @spec put_public_tables([label()], callgraph()) :: callgraph()
  def put_public_tables(publicTables, callgraph(race_data_server: raceDataServer) = cG) do
    :ok = :dialyzer_race_data_server.cast({:put_public_tables, publicTables}, raceDataServer)
    cG
  end

  @spec put_race_code(:dict.dict(), callgraph()) :: callgraph()
  def put_race_code(raceCode, callgraph(race_data_server: raceDataServer) = cG) do
    :ok = :dialyzer_race_data_server.cast({:put_race_code, raceCode}, raceDataServer)
    cG
  end

  @spec put_race_detection(boolean(), callgraph()) :: callgraph()
  def put_race_detection(raceDetection, callgraph), do: callgraph(callgraph, race_detection: raceDetection)

  @spec race_code_new(callgraph()) :: callgraph()
  def race_code_new(callgraph(race_data_server: raceDataServer) = cG) do
    :ok = :dialyzer_race_data_server.cast(:race_code_new, raceDataServer)
    cG
  end

  @spec remove_external(callgraph()) :: {callgraph(), [tuple()]}
  def remove_external(callgraph(digraph: dG) = cG) do
    {^dG, external} = digraph_remove_external(dG)
    {cG, external}
  end

  @spec renew_race_code(:dialyzer_races.races(), callgraph()) :: callgraph()
  def renew_race_code(races, callgraph(race_data_server: raceDataServer) = cG) do
    fun = :dialyzer_races.get_curr_fun(races)
    funArgs = :dialyzer_races.get_curr_fun_args(races)
    code = :lists.reverse(:dialyzer_races.get_race_list(races))
    :ok = :dialyzer_race_data_server.cast({:renew_race_code, {fun, funArgs, code}}, raceDataServer)
    cG
  end

  @spec renew_race_info(callgraph(), :dict.dict(), [label()], [charlist()]) :: callgraph()
  def renew_race_info(callgraph(race_data_server: raceDataServer) = cG, raceCode, publicTables, namedTables) do
    :ok = :dialyzer_race_data_server.cast({:renew_race_info, {raceCode, publicTables, namedTables}}, raceDataServer)
    cG
  end

  @spec renew_race_public_tables(label(), callgraph()) :: callgraph()
  def renew_race_public_tables(varLabel, callgraph(race_data_server: raceDataServer) = cG) do
    :ok = :dialyzer_race_data_server.cast({:renew_race_public_tables, varLabel}, raceDataServer)
    cG
  end

  @spec reset_from_funs([mfa_or_funlbl()], callgraph()) :: {[scc()], callgraph()}
  def reset_from_funs(funs, callgraph(digraph: dG, active_digraph: aDG) = cG) do
    active_digraph_delete(aDG)
    subGraph = digraph_reaching_subgraph(funs, dG)
    {newActiveDG, postorder} = condensation(subGraph)
    digraph_delete(subGraph)
    {postorder, callgraph(cG, active_digraph: newActiveDG)}
  end

  @spec scan_core_tree(:cerl.c_module(), callgraph()) :: {[mfa_or_funlbl()], [callgraph_edge()]}
  def scan_core_tree(tree, callgraph(calls: eTSCalls, esc: eTSEsc, letrec_map: eTSLetrecMap, name_map: eTSNameMap, rec_var_map: eTSRecVarMap, rev_name_map: eTSRevNameMap, self_rec: eTSSelfRec)) do
    build_maps(tree, eTSRecVarMap, eTSNameMap, eTSRevNameMap, eTSLetrecMap)
    {deps0, escapingFuns, calls, letrecs} = :dialyzer_dep.analyze(tree)
    true = :ets.insert(eTSCalls, :dict.to_list(calls))
    true = :ets.insert(eTSLetrecMap, :dict.to_list(letrecs))
    true = :ets.insert(eTSEsc, (for e <- escapingFuns do
      {e}
    end))
    labelEdges = get_edges_from_deps(deps0)
    selfRecs0 = :lists.foldl(fn {key, key}, acc ->
        case ets_lookup_dict(key, eTSNameMap) do
          :error ->
            [key | acc]
          {:ok, name} ->
            [key, name | acc]
        end
      _, acc ->
        acc
    end, [], labelEdges)
    true = :ets.insert(eTSSelfRec, (for s <- selfRecs0 do
      {s}
    end))
    namedEdges1 = name_edges(labelEdges, eTSNameMap)
    namedEdges2 = scan_core_funs(tree)
    names1 = :lists.append((for {x, y} <- namedEdges1 do
      [x, y]
    end))
    names2 = :ordsets.from_list(names1)
    names3 = :ordsets.del_element(:top, names2)
    newNamedEdges2 = for {from, to} = e <- namedEdges2, from !== :top, to !== :top do
      e
    end
    newNamedEdges1 = for {from, to} = e <- namedEdges1, from !== :top, to !== :top do
      e
    end
    namedEdges3 = newNamedEdges1 ++ newNamedEdges2
    {names3, namedEdges3}
  end

  @spec strip_module_deps(mod_deps(), :sets.set(module())) :: mod_deps()
  def strip_module_deps(modDeps, stripSet) do
    filterFun1 = fn val ->
        not :sets.is_element(val, stripSet)
    end
    mapFun = fn _Key, valSet ->
        :ordsets.filter(filterFun1, valSet)
    end
    modDeps1 = :dict.map(mapFun, modDeps)
    filterFun2 = fn _Key, valSet ->
        valSet !== []
    end
    :dict.filter(filterFun2, modDeps1)
  end

  @spec to_dot(callgraph(), :file.filename()) :: :ok
  def to_dot(callgraph(digraph: dG, esc: esc) = cG, file) do
    fun = fn l ->
        case lookup_name(l, cG) do
          :error ->
            l
          {:ok, name} ->
            name
        end
    end
    escaping = for l <- (for {e} <- :ets.tab2list(esc) do
      e
    end), l !== :external do
      {fun.(l), {:color, :red}}
    end
    vertices = digraph_edges(dG)
    :hipe_dot.translate_list(vertices, file, 'CG', escaping)
  end

  @spec to_ps(callgraph(), :file.filename(), charlist()) :: :ok
  def to_ps(callgraph() = cG, file, args) do
    dot_File = :filename.rootname(file) ++ '.dot'
    to_dot(cG, dot_File)
    command = :io_lib.format('dot -Tps ~ts -o ~ts ~ts', [args, file, dot_File])
    _ = :os.cmd(command)
    :ok
  end

  # Private Functions

  defp unquote(:"-build_maps/5-fun-0-")(p0, p1, p2, p3, p4, p5) do
    # body not decompiled
  end

  defp unquote(:"-condensation/1-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-do_condensation/2-fun-3-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-do_condensation/2-fun-5-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-do_condensation/2-fun-6-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-do_condensation/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-do_condensation/2-lc$^1/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-do_condensation/2-lc$^2/1-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-do_condensation/2-lc$^4/1-3-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-fun.edge_fold/2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-get_edges_from_deps/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-get_edges_from_deps/1-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-lookup_scc/3-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-module_deps/1-fun-2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-module_deps/1-lc$^1/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-module_deps/1-lc$^3/1-1-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-module_postorder/1-fun-2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-module_postorder/1-lc$^1/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-modules/1-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-name_edges/2-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-new/0-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-remove_unconfirmed/3-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-scan_core_funs/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-scan_core_tree/2-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-scan_core_tree/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-scan_core_tree/2-lc$^2/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-scan_core_tree/2-lc$^3/1-2-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-scan_core_tree/2-lc$^4/1-3-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-scan_core_tree/2-lc$^5/1-4-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-scan_one_core_fun/2-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-strip_module_deps/2-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-strip_module_deps/2-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-strip_module_deps/2-fun-2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-to_dot/2-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-to_dot/2-lc$^1/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-to_dot/2-lc$^2/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp active_digraph_delete({:d, dG}), do: :digraph.delete(dG)

  defp active_digraph_delete({:e, out, erlangVariableIn, maps}) do
    :ets.delete(out)
    :ets.delete(erlangVariableIn)
    :ets.delete(maps)
  end

  defp build_maps(tree, eTSRecVarMap, eTSNameMap, eTSRevNameMap, eTSLetrecMap) do
    defs = :cerl.module_defs(tree)
    mod = :cerl.atom_val(:cerl.module_name(tree))
    fun = fn {var, function} ->
        funName = :cerl.fname_id(var)
        arity = :cerl.fname_arity(var)
        mFA = {mod, funName, arity}
        funLabel = get_label(function)
        varLabel = get_label(var)
        true = :ets.insert(eTSLetrecMap, {varLabel, funLabel})
        true = :ets.insert(eTSNameMap, {funLabel, mFA})
        true = :ets.insert(eTSRevNameMap, {mFA, funLabel})
        true = :ets.insert(eTSRecVarMap, {varLabel, mFA})
    end
    :lists.foreach(fun, defs)
  end

  defp check_add_edge(g, v1, v2) do
    case :digraph.add_edge(g, v1, v2) do
      {:error, error} ->
        exit({:add_edge, v1, v2, error})
      _Edge ->
        :ok
    end
  end

  defp check_add_edge(g, e, v1, v2, l) do
    case :digraph.add_edge(g, e, v1, v2, l) do
      {:error, error} ->
        exit({:add_edge, e, v1, v2, l, error})
      _Edge ->
        :ok
    end
  end

  defp condensation(g) do
    {pid, ref} = :erlang.spawn_monitor(do_condensation(g, self()))
    receive do
    {:DOWN, ^ref, :process, ^pid, result} ->
        {sCCInts, outETS, inETS, mapsETS} = result
        newSCCs = for sCCInt <- sCCInts do
          :ets.lookup_element(mapsETS, sCCInt, 2)
        end
        {{:e, outETS, inETS, mapsETS}, newSCCs}
    end
  end

  defp digraph_add_edge(from, to, dG) do
    case :digraph.vertex(dG, from) do
      false ->
        :digraph.add_vertex(dG, from)
      {from, _} ->
        :ok
    end
    case :digraph.vertex(dG, to) do
      false ->
        :digraph.add_vertex(dG, to)
      {to, _} ->
        :ok
    end
    check_add_edge(dG, {from, to}, from, to, [])
    :ok
  end

  defp digraph_add_edges([{from, to} | left], dG) do
    digraph_add_edge(from, to, dG)
    digraph_add_edges(left, dG)
  end

  defp digraph_add_edges([], _DG), do: :ok

  defp digraph_confirm_vertices([mFA | left], dG) do
    :digraph.add_vertex(dG, mFA, :confirmed)
    digraph_confirm_vertices(left, dG)
  end

  defp digraph_confirm_vertices([], _DG), do: :ok

  defp digraph_delete(dG), do: :digraph.delete(dG)

  defp digraph_edges(dG), do: :digraph.edges(dG)

  defp digraph_in_neighbours(v, dG) do
    case :digraph.in_neighbours(dG, v) do
      [] ->
        :none
      list ->
        list
    end
  end

  defp digraph_reaching_subgraph(funs, dG) do
    vertices = :digraph_utils.reaching(funs, dG)
    :digraph_utils.subgraph(dG, vertices)
  end

  defp digraph_remove_external(dG) do
    vertices = :digraph.vertices(dG)
    unconfirmed = remove_unconfirmed(vertices, dG)
    {dG, unconfirmed}
  end

  defp digraph_vertices(dG), do: :digraph.vertices(dG)

  @spec do_condensation(:digraph.graph(), pid()) :: (() -> no_return())
  defp do_condensation(g, parent) do
    fn () ->
        [outETS, inETS, mapsETS] = for name <- [:callgraph_deps_out, :callgraph_deps_in, :callgraph_scc_map] do
          :ets.new(name, [{:read_concurrency, true}])
        end
        sCCs = :digraph_utils.strong_components(g)
        ints = :lists.seq(1, length(sCCs))
        intToSCC = :lists.zip(ints, sCCs)
        intScc = :sofs.relation(intToSCC, [{:int, :scc}])
        :ets.insert(mapsETS, intToSCC)
        c2V = :sofs.relation((for sC <- sCCs, v <- sC do
          {sC, v}
        end), [{:scc, :v}])
        i2V = :sofs.relative_product(intScc, c2V)
        es = :sofs.relation(:digraph.edges(g), [{:v, :v}])
        r1 = :sofs.relative_product(i2V, es)
        r2 = :sofs.relative_product(i2V, :sofs.converse(r1))
        r2Strict = :sofs.strict_relation(r2)
        out = :sofs.relation_to_family(:sofs.converse(r2Strict))
        :ets.insert(outETS, :sofs.to_external(out))
        dG = :sofs.family_to_digraph(out)
        :lists.foreach(fn i ->
            :digraph.add_vertex(dG, i)
        end, ints)
        sCCInts0 = :digraph_utils.topsort(dG)
        :digraph.delete(dG)
        sCCInts = :lists.reverse(sCCInts0)
        erlangVariableIn = :sofs.relation_to_family(r2Strict)
        :ets.insert(inETS, :sofs.to_external(erlangVariableIn))
        :ets.insert(mapsETS, :lists.zip((for sCC <- sCCs do
          {:scc, sCC}
        end), ints))
        :lists.foreach(fn e ->
            true = :ets.give_away(e, parent, :any)
        end, [outETS, inETS, mapsETS])
        exit({sCCInts, outETS, inETS, mapsETS})
    end
  end

  defp edge_fold({{m1, _, _}, {m2, _, _}}, set) do
    case m1 !== m2 do
      true ->
        :sets.add_element({m1, m2}, set)
      false ->
        set
    end
  end

  defp edge_fold(_, set), do: set

  defp ets_lookup_dict(key, table) do
    try do
      :ets.lookup_element(table, key, 2)
    catch
      {_, _, _} ->
        :error
    else
      val ->
        {:ok, val}
    end
  end

  defp ets_lookup_set(key, table), do: :ets.lookup(table, key) !== []

  @spec find_non_local_calls([{mfa_or_funlbl(), mfa_or_funlbl()}], call_tab()) :: mfa_calls()
  defp find_non_local_calls([{{m, _, _}, {m, _, _}} | left], set), do: find_non_local_calls(left, set)

  defp find_non_local_calls([{{m1, _, _}, {m2, _, _}} = edge | left], set) when m1 !== m2, do: find_non_local_calls(left, :sets.add_element(edge, set))

  defp find_non_local_calls([{{_, _, _}, label} | left], set) when is_integer(label), do: find_non_local_calls(left, set)

  defp find_non_local_calls([{label, {_, _, _}} | left], set) when is_integer(label), do: find_non_local_calls(left, set)

  defp find_non_local_calls([{label1, label2} | left], set) when is_integer(label1) and is_integer(label2), do: find_non_local_calls(left, set)

  defp find_non_local_calls([], set), do: :sets.to_list(set)

  defp get_edges_from_deps(deps) do
    edges = :dict.fold(fn :external, _Set, acc ->
        acc
      caller, set, acc ->
        [for callee <- set, callee !== :external do
          {caller, callee}
        end | acc]
    end, [], deps)
    :lists.flatten(edges)
  end

  defp get_label(t) do
    case :cerl.get_ann(t) do
      [{:label, l} | _] when is_integer(l) ->
        l
      _ ->
        :erlang.error({:missing_label, t})
    end
  end

  defp lookup_scc(sCC, table, maps) do
    case ets_lookup_dict({:scc, sCC}, maps) do
      {:ok, sCCInt} ->
        case ets_lookup_dict(sCCInt, table) do
          {:ok, ints} ->
            for int <- ints do
              :ets.lookup_element(maps, int, 2)
            end
          :error ->
            []
        end
      :error ->
        []
    end
  end

  @spec module_postorder(callgraph()) :: {[module()], {:d, :digraph.graph()}}
  defp module_postorder(callgraph(digraph: dG)) do
    edges = :lists.foldl(&edge_fold/2, :sets.new(), digraph_edges(dG))
    nodes = :sets.from_list((for {m, _F, _A} <- digraph_vertices(dG) do
      m
    end))
    mDG = :digraph.new([:acyclic])
    digraph_confirm_vertices(:sets.to_list(nodes), mDG)
    foreach = fn {m1, m2} ->
        _ = :digraph.add_edge(mDG, m1, m2)
    end
    :lists.foreach(foreach, :sets.to_list(edges))
    {:lists.reverse(:digraph_utils.topsort(mDG)), {:d, mDG}}
  end

  defp name_edges(edges, eTSNameMap) do
    mapFun = fn x ->
        case ets_lookup_dict(x, eTSNameMap) do
          :error ->
            x
          {:ok, mFA} ->
            mFA
        end
    end
    name_edges(edges, mapFun, [])
  end

  defp name_edges([{from, to} | left], mapFun, acc) do
    newFrom = mapFun.(from)
    newTo = mapFun.(to)
    name_edges(left, mapFun, [{newFrom, newTo} | acc])
  end

  defp name_edges([], _MapFun, acc), do: acc

  defp remove_unconfirmed(vertexes, dG), do: remove_unconfirmed(vertexes, dG, [])

  defp remove_unconfirmed([v | left], dG, unconfirmed) do
    case :digraph.vertex(dG, v) do
      {v, :confirmed} ->
        remove_unconfirmed(left, dG, unconfirmed)
      {v, []} ->
        remove_unconfirmed(left, dG, [v | unconfirmed])
    end
  end

  defp remove_unconfirmed([], dG, unconfirmed) do
    badCalls = :lists.append((for v <- unconfirmed do
      :digraph.in_edges(dG, v)
    end))
    badCallsSorted = :lists.keysort(1, badCalls)
    :digraph.del_vertices(dG, unconfirmed)
    badCallsSorted
  end

  defp scan_core_funs(tree) do
    defs = :cerl.module_defs(tree)
    mod = :cerl.atom_val(:cerl.module_name(tree))
    deepEdges = :lists.foldl(fn {var, function}, edges ->
        funName = :cerl.fname_id(var)
        arity = :cerl.fname_arity(var)
        mFA = {mod, funName, arity}
        [scan_one_core_fun(function, mFA) | edges]
    end, [], defs)
    :lists.flatten(deepEdges)
  end

  defp scan_one_core_fun(topTree, funName) do
    foldFun = fn tree, acc ->
        case :cerl.type(tree) do
          :call ->
            calleeM = :cerl.call_module(tree)
            calleeF = :cerl.call_name(tree)
            calleeArgs = :cerl.call_args(tree)
            a = length(calleeArgs)
            case :cerl.is_c_atom(calleeM) and :cerl.is_c_atom(calleeF) do
              true ->
                m = :cerl.atom_val(calleeM)
                f = :cerl.atom_val(calleeF)
                case :erl_bif_types.is_known(m, f, a) do
                  true ->
                    case {m, f, a} do
                      {:erlang, :make_fun, 3} ->
                        [cA1, cA2, cA3] = calleeArgs
                        case :cerl.is_c_atom(cA1) and :cerl.is_c_atom(cA2) and :cerl.is_c_int(cA3) do
                          true ->
                            mM = :cerl.atom_val(cA1)
                            fF = :cerl.atom_val(cA2)
                            aA = :cerl.int_val(cA3)
                            case :erl_bif_types.is_known(mM, fF, aA) do
                              true ->
                                acc
                              false ->
                                [{funName, {mM, fF, aA}} | acc]
                            end
                          false ->
                            acc
                        end
                      _ ->
                        acc
                    end
                  false ->
                    [{funName, {m, f, a}} | acc]
                end
              false ->
                acc
            end
          _ ->
            acc
        end
    end
    :cerl_trees.fold(foldFun, [], topTree)
  end
end
