# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :code do

  # Types

  @type load_error_rsn :: (:badfile | :nofile | :not_purged | :on_load_failure | :sticky_directory)

  @type load_ret :: ({:error, what :: load_error_rsn()} | {:module, module :: module()})

  @type module_status :: (:not_loaded | :loaded | :modified | :removed)

  @opaque prepared_code :: {:"$prepared$", [{module(), {binary(), charlist(), any()}}]}

  # Private Types

  @typep add_path_ret :: (true | {:error, :bad_directory})

  @typep loaded_filename :: (filename :: :file.filename() | loaded_ret_atoms())

  @typep loaded_ret_atoms :: (:cover_compiled | :preloaded)

  @typep prep_fun_type :: (module(), :file.filename(), binary() -> ({:ok, any()} | {:error, any()}))

  # Functions

  @spec add_path(dir) :: add_path_ret() when dir: :file.filename()
  def add_path(dir) when is_list(dir), do: call({:add_path, :last, dir})

  @spec add_patha(dir) :: add_path_ret() when dir: :file.filename()
  def add_patha(dir) when is_list(dir), do: call({:add_path, :first, dir})

  @spec add_paths(dirs) :: :ok when dirs: [dir :: :file.filename()]
  def add_paths(dirs) when is_list(dirs), do: call({:add_paths, :last, dirs})

  @spec add_pathsa(dirs) :: :ok when dirs: [dir :: :file.filename()]
  def add_pathsa(dirs) when is_list(dirs), do: call({:add_paths, :first, dirs})

  @spec add_pathsz(dirs) :: :ok when dirs: [dir :: :file.filename()]
  def add_pathsz(dirs) when is_list(dirs), do: call({:add_paths, :last, dirs})

  @spec add_pathz(dir) :: add_path_ret() when dir: :file.filename()
  def add_pathz(dir) when is_list(dir), do: call({:add_path, :last, dir})

  @spec all_available() :: [{module, filename, loaded}] when module: charlist(), filename: loaded_filename(), loaded: boolean()
  def all_available() do
    case :code.get_mode() do
      :interactive ->
        all_available(get_path(), %{})
      :embedded ->
        all_available([], %{})
    end
  end

  @spec all_loaded() :: [{module, loaded}] when module: module(), loaded: loaded_filename()
  def all_loaded(), do: call(:all_loaded)

  @spec atomic_load(modules) :: (:ok | {:error, [{module, what}]}) when modules: [(module | {module, filename, binary})], module: module(), filename: :file.filename(), binary: binary(), what: (:badfile | :nofile | :on_load_not_allowed | :duplicated | :not_purged | :sticky_directory | :pending_on_load)
  def atomic_load(modules) do
    case do_prepare_loading(modules) do
      {:ok, prep} ->
        finish_loading(prep, false)
      {:error, _} = error ->
        error
      :badarg ->
        error(:function_clause, [modules])
    end
  end

  @spec clash() :: :ok
  def clash() do
    path = get_path()
    struct = :lists.flatten(build(path))
    len = length(search(struct))
    :io.format('** Found ~w name clashes in code paths ~n', [len])
  end

  @spec compiler_dir() :: :file.filename()
  def compiler_dir(), do: call({:dir, :compiler_dir})

  @spec del_path(nameOrDir) :: (boolean() | {:error, what}) when nameOrDir: (name | dir), name: atom(), dir: :file.filename(), what: :bad_name
  def del_path(name) when is_list(name) or is_atom(name), do: call({:del_path, name})

  @spec delete(module) :: boolean() when module: module()
  def delete(mod) when is_atom(mod), do: call({:delete, mod})

  @spec ensure_loaded(module) :: ({:module, module} | {:error, what}) when module: module(), what: (:embedded | :badfile | :nofile | :on_load_failure)
  def ensure_loaded(mod) when is_atom(mod) do
    case :erlang.module_loaded(mod) do
      true ->
        {:module, mod}
      false ->
        call({:ensure_loaded, mod})
    end
  end

  @spec ensure_modules_loaded([module]) :: (:ok | {:error, [{module, what}]}) when module: module(), what: (:badfile | :nofile | :on_load_failure)
  def ensure_modules_loaded(modules) when is_list(modules) do
    case prepare_ensure(modules, []) do
      ms when is_list(ms) ->
        ensure_modules_loaded_1(ms)
      :error ->
        error(:function_clause, [modules])
    end
  end

  @spec finish_loading(prepared) :: (:ok | {:error, [{module, what}]}) when prepared: prepared_code(), module: module(), what: (:not_purged | :sticky_directory | :pending_on_load)
  def finish_loading({:"$prepared$", prepared} = arg) when is_list(prepared) do
    case verify_prepared(prepared) do
      :ok ->
        finish_loading(prepared, false)
      :error ->
        error(:function_clause, [arg])
    end
  end

  @spec get_chunk(bin, chunk) :: (binary() | :undefined) when bin: binary(), chunk: charlist()
  def get_chunk(_, _), do: :erlang.nif_error(:undef)

  @spec get_doc(mod) :: ({:ok, res} | {:error, reason}) when mod: module(), res: docs_v1(), reason: (:non_existing | :missing | :file.posix())
  def get_doc(mod) when is_atom(mod) do
    case which(mod) do
      :preloaded ->
        erlangVariableFn = :filename.join([:code.lib_dir(:erts), 'ebin', atom_to_list(mod) ++ '.beam'])
        get_doc_chunk(erlangVariableFn, mod)
      error when is_atom(error) ->
        {:error, error}
      erlangVariableFn ->
        get_doc_chunk(erlangVariableFn, mod)
    end
  end

  @spec get_mode() :: (:embedded | :interactive)
  def get_mode(), do: call(:get_mode)

  @spec get_object_code(module) :: ({module, binary, filename} | :error) when module: module(), binary: binary(), filename: :file.filename()
  def get_object_code(mod) when is_atom(mod), do: call({:get_object_code, mod})

  @spec get_path() :: path when path: [dir :: :file.filename()]
  def get_path(), do: call(:get_path)

  @spec is_loaded(module) :: ({:file, loaded} | false) when module: module(), loaded: loaded_filename()
  def is_loaded(mod) when is_atom(mod), do: call({:is_loaded, mod})

  @spec is_module_native(module) :: (true | false | :undefined) when module: module()
  def is_module_native(_), do: :erlang.nif_error(:undef)

  @spec is_sticky(module) :: boolean() when module: module()
  def is_sticky(mod) when is_atom(mod), do: call({:is_sticky, mod})

  @spec lib_dir() :: :file.filename()
  def lib_dir(), do: call({:dir, :lib_dir})

  @spec lib_dir(name) :: (:file.filename() | {:error, :bad_name}) when name: atom()
  def lib_dir(app) when is_atom(app) or is_list(app), do: call({:dir, {:lib_dir, app}})

  @spec lib_dir(name, subDir) :: (:file.filename() | {:error, :bad_name}) when name: atom(), subDir: atom()
  def lib_dir(app, subDir) when is_atom(app) and is_atom(subDir), do: call({:dir, {:lib_dir, app, subDir}})

  @spec load_abs(filename) :: load_ret() when filename: :file.filename()
  def load_abs(file) when is_list(file) or is_atom(file) do
    mod = list_to_atom(:filename.basename(file))
    call({:load_abs, file, mod})
  end

  @spec load_abs(filename :: loaded_filename(), module :: module()) :: load_ret()
  def load_abs(file, m) when is_list(file) or is_atom(file) and is_atom(m), do: call({:load_abs, file, m})

  @spec load_binary(module, filename, binary) :: ({:module, module} | {:error, what}) when module: module(), filename: loaded_filename(), binary: binary(), what: (:badarg | load_error_rsn())
  def load_binary(mod, file, bin) when is_atom(mod) and is_list(file) or is_atom(file) and is_binary(bin), do: call({:load_binary, mod, file, bin})

  @spec load_file(module) :: load_ret() when module: module()
  def load_file(mod) when is_atom(mod), do: call({:load_file, mod})

  @spec load_native_partial(module :: module(), binary :: binary()) :: load_ret()
  def load_native_partial(mod, bin) when is_atom(mod) and is_binary(bin), do: call({:load_native_partial, mod, bin})

  @spec load_native_sticky(module :: module(), binary :: binary(), wholeModule :: (false | binary())) :: load_ret()
  def load_native_sticky(mod, bin, wholeModule) when is_atom(mod) and is_binary(bin) and is_binary(wholeModule) or wholeModule === false, do: call({:load_native_sticky, mod, bin, wholeModule})

  @spec make_stub_module(loaderState, beam, info) :: module() when loaderState: binary(), beam: binary(), info: {[], [], binary()}
  def make_stub_module(_, _, _), do: :erlang.nif_error(:undef)

  @spec modified_modules() :: [module()]
  def modified_modules() do
    for {m, :modified} <- module_status() do
      m
    end
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @spec module_md5(binary()) :: (binary() | :undefined)
  def module_md5(_), do: :erlang.nif_error(:undef)

  @spec module_status() :: [{module(), module_status()}]
  def module_status() do
    module_status((for {m, _} <- all_loaded() do
      m
    end))
  end

  @spec module_status(module :: (module() | [module()])) :: (module_status() | [{module(), module_status()}])
  def module_status(modules) when is_list(modules) do
    pathFiles = path_files()
    for m <- modules do
      {m, module_status(m, pathFiles)}
    end
  end

  def module_status(module), do: module_status(module, :code.get_path())

  @spec objfile_extension() :: [char(), ...]
  def objfile_extension(), do: :init.objfile_extension()

  @spec prepare_loading(modules) :: ({:ok, prepared} | {:error, [{module, what}]}) when modules: [(module | {module, filename, binary})], module: module(), filename: :file.filename(), binary: binary(), prepared: prepared_code(), what: (:badfile | :nofile | :on_load_not_allowed | :duplicated)
  def prepare_loading(modules) do
    case do_prepare_loading(modules) do
      {:ok, prep} ->
        {:ok, {:"$prepared$", prep}}
      {:error, _} = error ->
        error
      :badarg ->
        error(:function_clause, [modules])
    end
  end

  @spec priv_dir(name) :: (:file.filename() | {:error, :bad_name}) when name: atom()
  def priv_dir(app) when is_atom(app) or is_list(app), do: call({:dir, {:priv_dir, app}})

  @spec purge(module) :: boolean() when module: module()
  def purge(mod) when is_atom(mod), do: call({:purge, mod})

  @spec rehash() :: :ok
  def rehash() do
    cache_warning()
    :ok
  end

  @spec replace_path(name, dir) :: (true | {:error, what}) when name: atom(), dir: :file.filename(), what: (:bad_directory | :bad_name | {:badarg, any()})
  def replace_path(name, dir) when is_atom(name) or is_list(name) and is_atom(dir) or is_list(dir), do: call({:replace_path, name, dir})

  @spec root_dir() :: :file.filename()
  def root_dir(), do: call({:dir, :root_dir})

  @spec set_path(path) :: (true | {:error, what}) when path: [dir :: :file.filename()], what: :bad_directory
  def set_path(pathList) when is_list(pathList), do: call({:set_path, pathList})

  @spec set_primary_archive(archiveFile :: :file.filename(), archiveBin :: binary(), fileInfo :: :file.file_info(), parserFun :: function()) :: (:ok | {:error, atom()})
  def set_primary_archive(archiveFile0, archiveBin, file_info() = fileInfo, parserFun) when is_list(archiveFile0) and is_binary(archiveBin) do
    archiveFile = :filename.absname(archiveFile0)
    case call({:set_primary_archive, archiveFile, archiveBin, fileInfo, parserFun}) do
      {:ok, []} ->
        :ok
      {:ok, _Mode, ebins} ->
        ebins2 = for e <- ebins do
          :filename.join([archiveFile, e])
        end
        add_pathsa(ebins2)
      {:error, _Reason} = error ->
        error
    end
  end

  @spec soft_purge(module) :: boolean() when module: module()
  def soft_purge(mod) when is_atom(mod), do: call({:soft_purge, mod})

  @spec start_link() :: {:ok, pid()}
  def start_link(), do: do_start()

  @spec stick_dir(dir) :: (:ok | :error) when dir: :file.filename()
  def stick_dir(dir) when is_list(dir), do: call({:stick_dir, dir})

  @spec stick_mod(module :: module()) :: true
  def stick_mod(mod) when is_atom(mod), do: call({:stick_mod, mod})

  @spec stop() :: no_return()
  def stop(), do: call(:stop)

  @spec unstick_dir(dir) :: (:ok | :error) when dir: :file.filename()
  def unstick_dir(dir) when is_list(dir), do: call({:unstick_dir, dir})

  @spec unstick_mod(module :: module()) :: true
  def unstick_mod(mod) when is_atom(mod), do: call({:unstick_mod, mod})

  @spec where_is_file(filename) :: (:non_existing | absname) when filename: :file.filename(), absname: :file.filename()
  def where_is_file(file) when is_list(file) do
    path = get_path()
    where_is_file(path, file)
  end

  @spec where_is_file(path :: [(dir | {dir, files})], filename :: :file.filename()) :: (:non_existing | :file.filename()) when dir: :file.filename(), files: [:file.filename()]
  def where_is_file([], _), do: :non_existing

  def where_is_file([{path, files} | tail], file), do: where_is_file(tail, file, path, files)

  def where_is_file([path | tail], file) do
    case :erl_prim_loader.list_dir(path) do
      {:ok, files} ->
        where_is_file(tail, file, path, files)
      _Error ->
        where_is_file(tail, file)
    end
  end

  @spec which(module) :: which when module: module(), which: (loaded_filename() | :non_existing)
  def which(module) when is_atom(module) do
    case is_loaded(module) do
      false ->
        which(module, get_path())
      {:file, file} ->
        file
    end
  end

  # Private Functions

  defp unquote(:"-all_available/2-F/2-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-all_available/2-anonymous-3-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-all_available/2-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-all_available/2-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-do_par_fun/2-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-do_par_fun/2-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-do_par_fun_2/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-finish_loading/2-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-finish_loading/2-lc$^1/1-1-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-get_function_docs_from_ast/1-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-get_function_docs_from_ast/2-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-get_native_fun/0-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-get_native_fun/0-fun-1-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-load_code_server_prerequisites/0-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-load_mods/1-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-load_native_code_for_all_loaded/1-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-modified_modules/0-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-module_status/0-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-module_status/1-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-partition_on_load/1-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-prepare_loading_3/1-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-prepare_loading_fun/0-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-set_primary_archive/4-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp all_available([path | tail], acc) do
    case :erl_prim_loader.list_dir(path) do
      {:ok, files} ->
        all_available(tail, all_available(path, files, acc))
      _Error ->
        all_available(tail, acc)
    end
  end

  defp all_available([], allModules) do
    allLoaded = for {m, path} <- all_loaded() do
      {atom_to_list(m), path, true}
    end
    allAvailable = :maps.fold(fn file, path, acc ->
        [{:filename.rootname(file), :filename.append(path, file), false} | acc]
    end, [], allModules)
    orderFun = (f = fn
      # Decompiled from an Erlang named fun. Elixir has no named anonymous functions, so
      # `f` is bound to the fn here to keep self-references readable; it will still
      # NOT compile if the body recurses, as Elixir cannot see `f` inside its own
      # definition (recursion must go through a module function).
      {a, _, _}, {b, _, _} ->
        f.(a, b)
      a, b ->
        a <= b
    end)
    :lists.umerge(orderFun, :lists.sort(orderFun, allLoaded), :lists.sort(orderFun, allAvailable))
  end

  defp all_available(path, [file | t], acc) do
    case :filename.extension(file) do
      '.beam' ->
        case :maps.is_key(file, acc) do
          false ->
            all_available(path, t, %{acc | file => path})
          true ->
            all_available(path, t, acc)
        end
      _Else ->
        all_available(path, t, acc)
    end
  end

  defp all_available(_Path, [], acc), do: acc

  defp beam_file_md5(path) do
    case :beam_lib.md5(path) do
      {:ok, {_Mod, mD5}} ->
        mD5
      _ ->
        :undefined
    end
  end

  defp beam_file_native_md5(path, architecture) do
    try do
      get_beam_chunk(path, :hipe_unified_loader.chunk_name(architecture))
    catch
      {_, _, _} ->
        :undefined
    else
      nativeCode when is_binary(nativeCode) ->
        :erlang.md5(nativeCode)
    end
  end

  defp build([]), do: []

  defp build([dir | tail]) do
    files = filter(objfile_extension(), dir, :erl_prim_loader.list_dir(dir))
    [decorate(files, dir) | build(tail)]
  end

  defp cache_warning() do
    w = 'The code path cache functionality has been removed'
    :error_logger.warning_report(w)
  end

  defp call(req), do: :code_server.call(req)

  defp decorate([], _), do: []

  defp decorate([file | tail], dir), do: [{dir, file} | decorate(tail, dir)]

  defp do_par(fun, l) do
    {_, ref} = spawn_monitor(do_par_fun(fun, l))
    receive do
    {:DOWN, ^ref, :process, _, res} ->
        res
    end
  end

  @spec do_par_fun(prep_fun_type(), []) :: (() -> no_return())
  defp do_par_fun(fun, l) do
    fn () ->
        _ = for item <- l do
          spawn_monitor(do_par_fun_2(fun, item))
        end
        exit(do_par_recv(length(l), [], []))
    end
  end

  @spec do_par_fun_2(prep_fun_type(), {module(), :file.filename(), binary()}) :: (() -> no_return())
  defp do_par_fun_2(fun, item) do
    fn () ->
        {mod, filename, bin} = item
        try do
          fun.(mod, filename, bin)
        catch
          {_, error, _} ->
            exit({:bad, {mod, error}})
        else
          {:ok, res} ->
            exit({:good, {mod, res}})
          {:error, error} ->
            exit({:bad, {mod, error}})
        end
    end
  end

  defp do_par_recv(0, good, bad), do: {good, bad}

  defp do_par_recv(n, good, bad) do
    receive do
    {:DOWN, _, :process, _, {:good, res}} ->
        do_par_recv(n - 1, [res | good], bad)
      {:DOWN, _, :process, _, {:bad, res}} ->
        do_par_recv(n - 1, good, [res | bad])
    end
  end

  defp do_prepare_loading(modules) do
    case partition_load(modules, [], []) do
      {modBins, ms} ->
        case prepare_loading_1(modBins, ms) do
          {:error, _} = error ->
            error
          prep when is_list(prep) ->
            {:ok, prep}
        end
      :error ->
        :badarg
    end
  end

  defp do_s(lib) do
    case lib_dir(lib) do
      {:error, _} ->
        :ok
      dir ->
        _ = stick_dir(:filename.append(dir, 'ebin'))
        :ok
    end
  end

  defp do_start() do
    maybe_warn_for_cache()
    load_code_server_prerequisites()
    {:ok, [[root0]]} = :init.get_argument(:root)
    mode = start_get_mode()
    root = :filename.join([root0])
    res = :code_server.start_link([root, mode])
    maybe_stick_dirs(mode)
    architecture = :erlang.system_info(:hipe_architecture)
    load_native_code_for_all_loaded(architecture)
    res
  end

  defp do_stick_dirs() do
    do_s(:compiler)
    do_s(:stdlib)
    do_s(:kernel)
  end

  defp ensure_modules_loaded_1(ms0) do
    ms = :lists.usort(ms0)
    {prep, error0} = load_mods(ms)
    {onLoad, normal} = partition_on_load(prep)
    error1 = case finish_loading(normal, true) do
      :ok ->
        error0
      {:error, err} ->
        err ++ error0
    end
    ensure_modules_loaded_2(onLoad, error1)
  end

  defp ensure_modules_loaded_2([{m, _} | ms], errors) do
    case ensure_loaded(m) do
      {:module, m} ->
        ensure_modules_loaded_2(ms, errors)
      {:error, err} ->
        ensure_modules_loaded_2(ms, [{m, err} | errors])
    end
  end

  defp ensure_modules_loaded_2([], []), do: :ok

  defp ensure_modules_loaded_2([], [_ | _] = errors), do: {:error, errors}

  defp filter(_Ext, dir, :error) do
    :io.format('** Bad path can\'t read ~ts~n', [dir])
    []
  end

  defp filter(ext, _, {:ok, files}), do: filter2(ext, length(ext), files)

  defp filter2(_Ext, _Extlen, []), do: []

  defp filter2(ext, extlen, [file | tail]) do
    case has_ext(ext, extlen, file) do
      true ->
        [file | filter2(ext, extlen, tail)]
      false ->
        filter2(ext, extlen, tail)
    end
  end

  defp finish_loading(prepared0, ensureLoaded) do
    prepared = for {m, {bin, file, _}} <- prepared0 do
      {m, {bin, file}}
    end
    native0 = for {m, {_, _, code}} <- prepared0, code !== :undefined do
      {m, code}
    end
    case call({:finish_loading, prepared, ensureLoaded}) do
      :ok ->
        finish_loading_native(native0)
      {:error, errors} = e when ensureLoaded ->
        s0 = :sofs.relation(errors)
        s1 = :sofs.domain(s0)
        r0 = :sofs.relation(native0)
        r1 = :sofs.drestriction(r0, s1)
        native = :sofs.to_external(r1)
        finish_loading_native(native)
        e
      {:error, _} = e ->
        e
    end
  end

  defp finish_loading_native([{mod, code} | ms]) do
    _ = load_native_partial(mod, code)
    finish_loading_native(ms)
  end

  defp finish_loading_native([]), do: :ok

  defp get_beam_chunk(path, chunk) do
    {:ok, {_, [{_, bin}]}} = :beam_lib.chunks(path, [chunk])
    bin
  end

  defp get_doc_chunk(filename, mod) when is_atom(mod) do
    case :beam_lib.chunks(filename, ['Docs']) do
      {:error, :beam_lib, {:missing_chunk, _, _}} ->
        case get_doc_chunk(filename, atom_to_list(mod)) do
          {:error, :missing} ->
            get_doc_chunk_from_ast(filename)
          error ->
            error
        end
      {:error, :beam_lib, {:file_error, _Filename, :enoent}} ->
        get_doc_chunk(filename, atom_to_list(mod))
      {:ok, {mod, [{'Docs', bin}]}} ->
        {:ok, binary_to_term(bin)}
    end
  end

  defp get_doc_chunk(filename, mod) do
    case :filename.dirname(filename) do
      filename ->
        {:error, :missing}
      dir ->
        chunkFile = :filename.join([dir, 'doc', 'chunks', mod ++ '.chunk'])
        case :file.read_file(chunkFile) do
          {:ok, bin} ->
            {:ok, binary_to_term(bin)}
          {:error, :enoent} ->
            get_doc_chunk(dir, mod)
          {:error, reason} ->
            {:error, reason}
        end
    end
  end

  defp get_doc_chunk_from_ast(filename) do
    case :beam_lib.chunks(filename, [:abstract_code]) do
      {:error, :beam_lib, {:missing_chunk, _, _}} ->
        {:error, :missing}
      {:ok, {_Mod, [{:abstract_code, {:raw_abstract_v1, aST}}]}} ->
        docs = get_function_docs_from_ast(aST)
        {:ok, docs_v1(anno: 0, beam_language: :erlang, module_doc: :none, metadata: %{:generated => true, :otp_doc_vsn => {1, 0, 0}}, docs: docs)}
      {:ok, {_Mod, [{:abstract_code, :no_abstract_code}]}} ->
        {:error, :missing}
      error ->
        error
    end
  end

  defp get_function_docs_from_ast(aST) do
    :lists.flatmap(fn e ->
        get_function_docs_from_ast(e, aST)
    end, aST)
  end

  defp get_function_docs_from_ast({:function, anno, name, arity, _Code}, aST) do
    signature = :io_lib.format('~p/~p', [name, arity])
    specs = :lists.filter(fn {:attribute, _Ln, :spec, {fA, _}} ->
        case fA do
          {f, a} ->
            f === name and a === arity
          {_, f, a} ->
            f === name and a === arity
        end
      _ ->
        false
    end, aST)
    specMd = case specs do
      [s] ->
        %{:signature => [s]}
      [] ->
        %{}
    end
    [{{:function, name, arity}, anno, [:unicode.characters_to_binary(signature)], :none, specMd}]
  end

  defp get_function_docs_from_ast(_, _), do: []

  defp get_native_fun() do
    architecture = :erlang.system_info(:hipe_architecture)
    try do
      :hipe_unified_loader.chunk_name(architecture)
    catch
      {_, _, _} ->
        fn _ ->
            :undefined
        end
    else
      chunkTag ->
        fn beam ->
            :code.get_chunk(beam, chunkTag)
        end
    end
  end

  defp has_ext(ext, extlen, file) do
    l = length(file)
    try do
      :lists.nthtail(l - extlen, file)
    catch
      error -> error
    end
    |> case do
      ext ->
        true
      _ ->
        false
    end
  end

  defp load_all_native(loaded, chunkTag) do
    try do
      load_all_native_1(loaded, chunkTag)
    catch
      error -> error
    end
  end

  defp load_all_native_1([{_, :preloaded} | t], chunkTag), do: load_all_native_1(t, chunkTag)

  defp load_all_native_1([{mod, beamFilename} | t], chunkTag) do
    case :code.is_module_native(mod) do
      false ->
        {:ok, beam} = :prim_file.read_file(beamFilename)
        case :code.get_chunk(beam, chunkTag) do
          :undefined ->
            :ok
          nativeCode when is_binary(nativeCode) ->
            _ = load_native_partial(mod, nativeCode)
            :ok
        end
      true ->
        :ok
    end
    load_all_native_1(t, chunkTag)
  end

  defp load_all_native_1([], _), do: :ok

  defp load_bins([]), do: {[], []}

  defp load_bins(binItems) do
    f = prepare_loading_fun()
    do_par(f, binItems)
  end

  defp load_code_server_prerequisites() do
    needed = [:binary, :ets, :filename, :gb_sets, :gb_trees, :hipe_unified_loader, :lists, :os, :unicode]
    _ = for m <- needed do
      ^m = m.module_info(:module)
    end
    :ok
  end

  defp load_mods([]), do: {[], []}

  defp load_mods(mods) do
    path = get_path()
    f = prepare_loading_fun()
    {:ok, {succ, error0}} = :erl_prim_loader.get_modules(mods, f, path)
    error = for {m, e} <- error0 do
      case e do
      :badfile ->
        {m, e}
      _ ->
        {m, :nofile}
    end
    end
    {succ, error}
  end

  defp load_native_code_for_all_loaded(:undefined), do: :ok

  defp load_native_code_for_all_loaded(architecture) do
    try do
      :hipe_unified_loader.chunk_name(architecture)
    catch
      {_, _, _} ->
        :ok
    else
      chunkTag ->
        loaded = all_loaded()
        _ = spawn(fn () ->
            load_all_native(loaded, chunkTag)
        end)
        :ok
    end
  end

  defp maybe_stick_dirs(:interactive) do
    case :init.get_argument(:nostick) do
      {:ok, [[]]} ->
        :ok
      _ ->
        do_stick_dirs()
    end
  end

  defp maybe_stick_dirs(_), do: :ok

  defp maybe_warn_for_cache() do
    case :init.get_argument(:code_path_cache) do
      {:ok, _} ->
        cache_warning()
      :error ->
        :ok
    end
  end

  defp module_changed_on_disk(module, path) do
    mD5 = :erlang.get_module_info(module, :md5)
    case :erlang.system_info(:hipe_architecture) do
      :undefined ->
        mD5 !== beam_file_md5(path)
      architecture ->
        case :code.is_module_native(module) do
          true ->
            mD5 !== beam_file_native_md5(path, architecture)
          _ ->
            mD5 !== beam_file_md5(path)
        end
    end
  end

  defp module_status(module, pathFiles) do
    case :code.is_loaded(module) do
      false ->
        :not_loaded
      {:file, :preloaded} ->
        :loaded
      {:file, :cover_compiled} ->
        case which(module, pathFiles) do
          :non_existing ->
            :removed
          _File ->
            :modified
        end
      {:file, []} ->
        :loaded
      {:file, oldFile} when is_list(oldFile) ->
        case which(module, pathFiles) do
          :non_existing ->
            :removed
          path ->
            case module_changed_on_disk(module, path) do
              true ->
                :modified
              false ->
                :loaded
            end
        end
    end
  end

  defp partition_load([item | t], bs, ms) do
    case item do
      {m, file, bin} when is_atom(m) and is_list(file) and is_binary(bin) ->
        partition_load(t, [item | bs], ms)
      m when is_atom(m) ->
        partition_load(t, bs, [item | ms])
      _ ->
        :error
    end
  end

  defp partition_load([], bs, ms), do: {bs, ms}

  defp partition_on_load(prep) do
    p = fn {_, {pC, _, _}} ->
        :erlang.has_prepared_code_on_load(pC)
    end
    :lists.partition(p, prep)
  end

  defp path_files(), do: path_files(:code.get_path())

  defp path_files([]), do: []

  defp path_files([path | tail]) do
    case :erl_prim_loader.list_dir(path) do
      {:ok, files} ->
        [{path, files} | path_files(tail)]
      _Error ->
        path_files(tail)
    end
  end

  defp prepare_check_uniq([{m, _, _} | t], ms), do: prepare_check_uniq(t, [m | ms])

  defp prepare_check_uniq([], ms), do: prepare_check_uniq_1(:lists.sort(ms), [])

  defp prepare_check_uniq_1([m | [m | _] = ms], acc), do: prepare_check_uniq_1(ms, [{m, :duplicated} | acc])

  defp prepare_check_uniq_1([_ | ms], acc), do: prepare_check_uniq_1(ms, acc)

  defp prepare_check_uniq_1([], []), do: :ok

  defp prepare_check_uniq_1([], [_ | _] = errors), do: {:error, errors}

  defp prepare_ensure([m | ms], acc) when is_atom(m) do
    case :erlang.module_loaded(m) do
      true ->
        prepare_ensure(ms, acc)
      false ->
        prepare_ensure(ms, [m | acc])
    end
  end

  defp prepare_ensure([], acc), do: acc

  defp prepare_ensure(_, _), do: :error

  defp prepare_loading_1(modBins, ms) do
    case prepare_check_uniq(modBins, ms) do
      :ok ->
        prepare_loading_2(modBins, ms)
      error ->
        error
    end
  end

  defp prepare_loading_2(modBins, ms) do
    {prep0, error0} = load_bins(modBins)
    {prep1, error1} = load_mods(ms)
    case error0 ++ error1 do
      [] ->
        prepare_loading_3(prep0 ++ prep1)
      [_ | _] = error ->
        {:error, error}
    end
  end

  defp prepare_loading_3(prep) do
    case partition_on_load(prep) do
      {[_ | _] = onLoad, _} ->
        error = for {m, _} <- onLoad do
          {m, :on_load_not_allowed}
        end
        {:error, error}
      {[], _} ->
        prep
    end
  end

  @spec prepare_loading_fun() :: prep_fun_type()
  defp prepare_loading_fun() do
    getNative = get_native_fun()
    fn mod, fullName, beam ->
        case :erlang.prepare_loading(mod, beam) do
          {:error, _} = error ->
            error
          prepared ->
            {:ok, {prepared, fullName, getNative.(beam)}}
        end
    end
  end

  defp search([]), do: []

  defp search([{dir, file} | tail]) do
    case :lists.keyfind(file, 2, tail) do
      false ->
        search(tail)
      {dir2, file} ->
        :io.format('** ~ts hides ~ts~n', [:filename.join(dir, file), :filename.join(dir2, file)])
        [:clash | search(tail)]
    end
  end

  defp start_get_mode() do
    case :init.get_argument(:mode) do
      {:ok, [firstMode | rest]} ->
        case rest do
          [] ->
            :ok
          _ ->
            case :logger.allow(:warning, :code) do
              true ->
                :erlang.apply(:logger, :macro_log, [%{:mfa => {:code, :start_get_mode, 0}, :line => 769, :file => 'code.erl'}, :warning, 'Multiple -mode given to erl, using the first, ~p', [firstMode]])
              false ->
                :ok
            end
        end
        case firstMode do
          ['embedded'] ->
            :embedded
          _ ->
            :interactive
        end
      _ ->
        :interactive
    end
  end

  defp verify_prepared([{m, {prep, name, _Native}} | t]) when is_atom(m) and is_list(name) do
    try do
      :erlang.has_prepared_code_on_load(prep)
    catch
      {:error, _, _} ->
        :error
    else
      false ->
        verify_prepared(t)
      _ ->
        :error
    end
  end

  defp verify_prepared([]), do: :ok

  defp verify_prepared(_), do: :error

  defp where_is_file(tail, file, path, files) do
    case :lists.member(file, files) do
      true ->
        :filename.append(path, file)
      false ->
        where_is_file(tail, file)
    end
  end

  defp which(module, path) when is_atom(module) do
    file = atom_to_list(module) ++ objfile_extension()
    where_is_file(path, file)
  end
end
