# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :inet_db do

  # Private Types

  @typep state :: state()

  # Functions

  def add_alt_ns(iP), do: add_alt_ns(iP, 53)

  def add_alt_ns(iP, port), do: call({:listop, :alt_nameservers, :add, {iP, port}})

  def add_host(iP, names), do: call({:add_host, iP, names})

  def add_hosts(file) do
    case :inet_parse.hosts(file) do
      {:ok, res} ->
        :lists.foreach(fn {iP, name, aliases} ->
            add_host(iP, [name | aliases])
        end, res)
      error ->
        error
    end
  end

  def add_ns(iP), do: add_ns(iP, 53)

  def add_ns(iP, port), do: call({:listop, :nameservers, :add, {iP, port}})

  def add_rc(file) do
    case :file.consult(file) do
      {:ok, list} ->
        add_rc_list(list)
      error ->
        error
    end
  end

  def add_rc_bin(bin) do
    try do
      binary_to_term(bin)
    catch
      error -> error
    end
    |> case do
      list when is_list(list) ->
        add_rc_list(list)
      _ ->
        {:error, :badarg}
    end
  end

  def add_rc_list(list), do: call({:add_rc_list, list})

  def add_resolv(file) do
    case :inet_parse.resolv(file) do
      {:ok, res} ->
        add_rc_list(res)
      error ->
        error
    end
  end

  def add_rr(rR), do: res_cache_answer([rR])

  def add_rr(domain, class, type, tTL, data) do
    rR = dns_rr(domain: domain, class: class, type: type, ttl: tTL, data: data)
    res_cache_answer([rR])
  end

  def add_search(domain) when is_list(domain), do: call({:listop, :search, :add, domain})

  def add_socks_methods(ms), do: call({:add_socks_methods, ms})

  def add_socks_noproxy({net, mask}), do: call({:add_socks_noproxy, {net, mask}})

  def clear_cache(), do: call(:clear_cache)

  def clear_hosts(), do: call(:clear_hosts)

  def del_alt_ns(iP), do: del_alt_ns(iP, 53)

  def del_alt_ns(iP, port), do: call({:listop, :alt_nameservers, :del, {iP, port}})

  def del_host(iP), do: call({:del_host, iP})

  def del_ns(iP), do: del_ns(iP, 53)

  def del_ns(iP, port), do: call({:listop, :nameservers, :del, {iP, port}})

  def del_rr(domain, class, type, data), do: call({:del_rr, dns_rr_match(tolower(domain), class, type, data)})

  def del_search(domain), do: call({:listop, :search, :del, domain})

  def del_socks_methods(), do: call(:del_socks_methods)

  def del_socks_methods(ms), do: call({:del_socks_methods, ms})

  def del_socks_noproxy(net), do: call({:del_socks_noproxy, net})

  def eq_domains([a | as], [b | bs]) do
    cond do
      is_integer(a) and 0 <= a and a <= 1114111 and is_integer(b) and 0 <= b and b <= 1114111 ->
        xor = a ^^^ b
        cond do
          xor === 0 ->
            eq_domains(as, bs)
          xor === ?A ^^^ ?a ->
            erlangVariableAnd = a &&& b
            cond do
              ?A &&& ?a <= erlangVariableAnd and erlangVariableAnd <= ?Z &&& ?z ->
                eq_domains(as, bs)
              true ->
                false
            end
          true ->
            false
        end
    end
  end

  def eq_domains([], []), do: true

  def eq_domains(as, bs) when is_list(as) and is_list(bs), do: false

  def get_hosts_file(), do: get_rc_hosts([], [], :inet_hosts_file_byaddr)

  def get_rc(), do: get_rc([:hosts, :domain, :nameservers, :search, :alt_nameservers, :timeout, :retry, :servfail_retry_timeout, :inet6, :usevc, :edns, :udp_payload_size, :resolv_conf, :hosts_file, :socks5_server, :socks5_port, :socks5_methods, :socks5_noproxy, :udp, :sctp, :tcp, :host, :cache_size, :cache_refresh, :lookup], [])

  def get_searchlist() do
    case res_option(:search) do
      [] ->
        [res_option(:domain)]
      l ->
        l
    end
  end

  def getbyname(name, type) do
    {embeddedDots, trailingDot} = :inet_parse.dots(name)
    dot = cond do
      trailingDot ->
        ""
      true ->
        '.'
    end
    cond do
      trailingDot ->
        hostent_by_domain(name, type)
      embeddedDots === 0 ->
        getbysearch(name, dot, get_searchlist(), type, {:error, :nxdomain})
      true ->
        case hostent_by_domain(name, type) do
          {:error, _} = error ->
            getbysearch(name, dot, get_searchlist(), type, error)
          other ->
            other
        end
    end
  end

  def gethostbyaddr(domain, iP) do
    :ok
    case resolve_cnames(domain, :ptr, &lookup_cache_data/2) do
      {:error, _} = error ->
        error
      {_D, domains, _Aliases} ->
        ent_gethostbyaddr(domains, iP)
    end
  end

  def gethostname(), do: db_get(:hostname)

  @spec handle_call(term(), {pid(), term()}, state()) :: ({:reply, term(), state()} | {:stop, :normal, :ok, state()})
  def handle_call(request, from, state(db: db) = state) do
    case request do
      {:load_hosts_file, iPNmAs} when is_list(iPNmAs) ->
        load_hosts_list(iPNmAs, state(state, :hosts_file_byname), state(state, :hosts_file_byaddr))
        {:reply, :ok, state}
      {:add_host, {a, b, c, d} = iP, [n | as] = names} when a ||| b ||| c ||| d &&& ~~~255 === 0 and is_list(n) and is_list(as) ->
        do_add_host(state(state, :hosts_byname), state(state, :hosts_byaddr), names, :inet, iP)
        {:reply, :ok, state}
      {:add_host, {a, b, c, d, e, f, g, h} = iP, [n | as] = names} when a ||| b ||| c ||| d ||| e ||| f ||| g ||| h &&& ~~~65535 === 0 and is_list(n) and is_list(as) ->
        do_add_host(state(state, :hosts_byname), state(state, :hosts_byaddr), names, :inet6, iP)
        {:reply, :ok, state}
      {:del_host, {a, b, c, d} = iP} when a ||| b ||| c ||| d &&& ~~~255 === 0 ->
        do_del_host(state(state, :hosts_byname), state(state, :hosts_byaddr), iP)
        {:reply, :ok, state}
      {:del_host, {a, b, c, d, e, f, g, h} = iP} when a ||| b ||| c ||| d ||| e ||| f ||| g ||| h &&& ~~~65535 === 0 ->
        do_del_host(state(state, :hosts_byname), state(state, :hosts_byaddr), iP)
        {:reply, :ok, state}
      {:add_rrs, rRs} ->
        :ok
        {:reply, do_add_rrs(rRs, db, state), state}
      {:del_rr, rR} when is_record(rR, :dns_rr) ->
        cache = state(state, :cache)
        :ets.match_delete(cache, rR)
        {:reply, :ok, state}
      {:listop, opt, op, e} ->
        el = [e]
        case res_check_option(opt, el) do
          true ->
            optname = res_optname(opt)
            es = :ets.lookup_element(db, optname, 2)
            newEs = case op do
              :ins ->
                [e | lists_delete(e, es)]
              :add ->
                lists_delete(e, es) ++ el
              :del ->
                lists_delete(e, es)
            end
            :ets.insert(db, {optname, newEs})
            {:reply, :ok, state}
          false ->
            {:reply, :error, state}
        end
      {:listreplace, opt, els} ->
        case res_check_option(opt, els) do
          true ->
            :ets.insert(db, {res_optname(opt), els})
            {:reply, :ok, state}
          false ->
            {:reply, :error, state}
        end
      {:set_hostname, name} ->
        case :inet_parse.visible_string(name) do
          true ->
            :ets.insert(db, {:hostname, name})
            {:reply, :ok, state}
          false ->
            {:reply, :error, state}
        end
      {:res_set, :hosts_file_name = option, fname} ->
        handle_set_file(option, fname, :res_hosts_file_tm, :res_hosts_file_info, :undefined, from, state)
      {:res_set, :resolv_conf_name = option, fname} ->
        handle_set_file(option, fname, :res_resolv_conf_tm, :res_resolv_conf_info, :undefined, from, state)
      {:res_set, :hosts_file = option, fname_or_Tm} ->
        handle_set_file(option, fname_or_Tm, :res_hosts_file_tm, :res_hosts_file_info, fn file, bin ->
            case :inet_parse.hosts(file, {:chars, bin}) do
              {:ok, opts} ->
                [{:load_hosts_file, opts}]
              _ ->
                :error
            end
        end, from, state)
      {:res_set, :resolv_conf = option, fname_or_Tm} ->
        handle_set_file(option, fname_or_Tm, :res_resolv_conf_tm, :res_resolv_conf_info, fn file, bin ->
            case :inet_parse.resolv(file, {:chars, bin}) do
              {:ok, opts} ->
                search = :lists.foldl(fn {:search, l}, _ ->
                    l
                  {:domain, ""}, s ->
                    s
                  {:domain, d}, _ ->
                    [d]
                  _, s ->
                    s
                end, [], opts)
                nSs = for {:nameserver, nS} <- opts do
                  {nS, 53}
                end
                [{:replace_search, search}, {:replace_ns, nSs}, :clear_cache]
              _ ->
                :error
            end
        end, from, state)
      {:res_set, opt, value} ->
        case res_optname(opt) do
          :undefined ->
            {:reply, :error, state}
          optname ->
            case res_check_option(opt, value) do
              true ->
                :ets.insert(db, {optname, value})
                {:reply, :ok, state}
              false ->
                {:reply, :error, state}
            end
        end
      {:set_resolv_conf_tm, tM} ->
        :ets.insert(db, {:res_resolv_conf_tm, tM})
        {:reply, :ok, state}
      {:set_hosts_file_tm, tM} ->
        :ets.insert(db, {:res_hosts_file_tm, tM})
        {:reply, :ok, state}
      {:set_socks_server, {a, b, c, d}} when a ||| b ||| c ||| d &&& ~~~255 === 0 ->
        :ets.insert(db, {:socks5_server, {a, b, c, d}})
        {:reply, :ok, state}
      {:set_socks_port, port} when is_integer(port) ->
        :ets.insert(db, {:socks5_port, port})
        {:reply, :ok, state}
      {:add_socks_methods, ls} ->
        as = :ets.lookup_element(db, :socks5_methods, 2)
        as1 = lists_subtract(as, ls)
        :ets.insert(db, {:socks5_methods, as1 ++ ls})
        {:reply, :ok, state}
      {:del_socks_methods, ls} ->
        as = :ets.lookup_element(db, :socks5_methods, 2)
        as1 = lists_subtract(as, ls)
        case :lists.member(:none, as1) do
          false ->
            :ets.insert(db, {:socks5_methods, as1 ++ [:none]})
          true ->
            :ets.insert(db, {:socks5_methods, as1})
        end
        {:reply, :ok, state}
      :del_socks_methods ->
        :ets.insert(db, {:socks5_methods, [:none]})
        {:reply, :ok, state}
      {:add_socks_noproxy, {{a, b, c, d}, {mA, mB, mC, mD}}} when a ||| b ||| c ||| d &&& ~~~255 === 0 and mA ||| mB ||| mC ||| mD &&& ~~~255 === 0 ->
        as = :ets.lookup_element(db, :socks5_noproxy, 2)
        :ets.insert(db, {:socks5_noproxy, as ++ [{{a, b, c, d}, {mA, mB, mC, mD}}]})
        {:reply, :ok, state}
      {:del_socks_noproxy, {a, b, c, d} = iP} when a ||| b ||| c ||| d &&& ~~~255 === 0 ->
        as = :ets.lookup_element(db, :socks5_noproxy, 2)
        :ets.insert(db, {:socks5_noproxy, lists_keydelete(iP, 1, as)})
        {:reply, :ok, state}
      {:set_tcp_module, mod} when is_atom(mod) ->
        :ets.insert(db, {:tcp_module, mod})
        {:reply, :ok, state}
      {:set_udp_module, mod} when is_atom(mod) ->
        :ets.insert(db, {:udp_module, mod})
        {:reply, :ok, state}
      {:set_sctp_module, fam} when is_atom(fam) ->
        :ets.insert(db, {:sctp_module, fam})
        {:reply, :ok, state}
      {:set_cache_size, size} when is_integer(size) and size >= 0 ->
        :ets.insert(db, {:cache_size, size})
        {:reply, :ok, state}
      {:set_cache_refresh, time} when is_integer(time) and time > 0 ->
        time1 = div(time + 999, 1000) * 1000
        :ets.insert(db, {:cache_refresh_interval, time1})
        _ = stop_timer(state(state, :cache_timer))
        {:reply, :ok, state(state, cache_timer: init_timer())}
      :clear_hosts ->
        :ets.delete_all_objects(state(state, :hosts_byname))
        :ets.delete_all_objects(state(state, :hosts_byaddr))
        {:reply, :ok, state}
      :clear_cache ->
        :ets.delete_all_objects(state(state, :cache))
        {:reply, :ok, state}
      :reset ->
        reset_db(db)
        _ = stop_timer(state(state, :cache_timer))
        {:reply, :ok, state(state, cache_timer: init_timer())}
      {:add_rc_list, list} ->
        handle_rc_list(list, from, state)
      {:put_socket_type, mRef, type} ->
        reply = handle_put_socket_type(state(state, :sockets), mRef, type)
        {:reply, reply, state}
      {:take_socket_type, mRef} ->
        reply = handle_take_socket_type(state(state, :sockets), mRef)
        {:reply, reply, state}
      :stop ->
        {:stop, :normal, :ok, state}
      _ ->
        {:reply, :error, state}
    end
  end

  @spec handle_cast(term(), state()) :: {:noreply, state()}
  def handle_cast(_Msg, state), do: {:noreply, state}

  @spec handle_info(term(), state()) :: {:noreply, state()}
  def handle_info(:refresh_timeout, state) do
    _ = delete_expired(state(state, :cache), times())
    {:noreply, state(state, cache_timer: init_timer())}
  end

  def handle_info(_Info, state), do: {:noreply, state}

  @spec init([]) :: {:ok, state()}
  def init([]) do
    process_flag(:trap_exit, true)
    case :application.get_env(:kernel, :inet_backend) do
      {:ok, flag} when flag === :inet or flag === :socket ->
        :persistent_term.put({:kernel, :inet_backend}, flag)
      _ ->
        :ok
    end
    db = :ets.new(:inet_db, [:public, :named_table])
    reset_db(db)
    cacheOpts = [:public, :bag, {:keypos, dns_rr(:bm)}, :named_table]
    cache = :ets.new(:inet_cache, cacheOpts)
    hostsByname = :ets.new(:inet_hosts_byname, [:named_table])
    hostsByaddr = :ets.new(:inet_hosts_byaddr, [:named_table])
    hostsFileByname = :ets.new(:inet_hosts_file_byname, [:named_table])
    hostsFileByaddr = :ets.new(:inet_hosts_file_byaddr, [:named_table])
    sockets = :ets.new(:inet_sockets, [:protected, :set, :named_table])
    {:ok, state(db: db, cache: cache, hosts_byname: hostsByname, hosts_byaddr: hostsByaddr, hosts_file_byname: hostsFileByname, hosts_file_byaddr: hostsFileByaddr, sockets: sockets, cache_timer: init_timer())}
  end

  def ins_alt_ns(iP), do: ins_alt_ns(iP, 53)

  def ins_alt_ns(iP, port), do: call({:listop, :alt_nameservers, :ins, {iP, port}})

  def ins_ns(iP), do: ins_ns(iP, 53)

  def ins_ns(iP, port), do: call({:listop, :nameservers, :ins, {iP, port}})

  def ins_search(domain) when is_list(domain), do: call({:listop, :search, :ins, domain})

  def lookup_socket(socket) when is_port(socket) do
    try do
      :erlang.port_get_data(socket)
    catch
      {:error, :badarg, _} ->
        {:error, :closed}
    else
      module when is_atom(module) ->
        {:ok, module}
      _ ->
        {:error, :closed}
    end
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  def put_socket_type(mRef, type), do: call({:put_socket_type, mRef, type})

  def register_socket(socket, module) when is_port(socket) and is_atom(module) do
    try do
      :erlang.port_set_data(socket, module)
    catch
      {:error, :badarg, _} ->
        false
    end
  end

  def res_check_option(:nameserver, nSs), do: res_check_list(nSs, &res_check_ns/1)

  def res_check_option(:alt_nameserver, nSs), do: res_check_list(nSs, &res_check_ns/1)

  def res_check_option(:nameservers, nSs), do: res_check_list(nSs, &res_check_ns/1)

  def res_check_option(:alt_nameservers, nSs), do: res_check_list(nSs, &res_check_ns/1)

  def res_check_option(:domain, dom), do: dom === "" or :inet_parse.visible_string(dom)

  def res_check_option(:lookup, methods) do
    try do
      lists_subtract(methods, valid_lookup())
    catch
      {:error, _, _} ->
        false
    else
      [] ->
        true
      _ ->
        false
    end
  end

  def res_check_option(:recurse, r) when r === 0 or r === 1, do: true

  def res_check_option(:recurse, r) when is_boolean(r), do: true

  def res_check_option(:search, searchList), do: res_check_list(searchList, &res_check_search/1)

  def res_check_option(:retry, n) when is_integer(n) and n > 0, do: true

  def res_check_option(:servfail_retry_timeout, t) when is_integer(t) and t >= 0, do: true

  def res_check_option(:timeout, t) when is_integer(t) and t > 0, do: true

  def res_check_option(:inet6, bool) when is_boolean(bool), do: true

  def res_check_option(:usevc, bool) when is_boolean(bool), do: true

  def res_check_option(:edns, v) when v === false or v === 0, do: true

  def res_check_option(:udp_payload_size, s) when is_integer(s) and s >= 512, do: true

  def res_check_option(:resolv_conf, ""), do: true

  def res_check_option(:resolv_conf, f), do: res_check_option_absfile(f)

  def res_check_option(:resolv_conf_name, ""), do: true

  def res_check_option(:resolv_conf_name, f), do: res_check_option_absfile(f)

  def res_check_option(:hosts_file, ""), do: true

  def res_check_option(:hosts_file, f), do: res_check_option_absfile(f)

  def res_check_option(:hosts_file_name, ""), do: true

  def res_check_option(:hosts_file_name, f), do: res_check_option_absfile(f)

  def res_check_option(_, _), do: false

  def res_gethostbyaddr(domain, iP, rec) do
    rRs = res_filter_rrs(:ptr, dns_rec(rec, :anlist))
    :ok
    lookupFun = res_lookup_fun(rRs)
    case resolve_cnames(domain, :ptr, lookupFun) do
      {:error, _} = error ->
        error
      {_D, domains, _Aliases} ->
        case ent_gethostbyaddr(domains, iP) do
          {:ok, _HEnt} = result ->
            res_cache_answer(rRs)
            result
          {:error, _} = error ->
            error
        end
    end
  end

  def res_hostent_by_domain(domain, type, rec) do
    rRs = res_filter_rrs(type, dns_rec(rec, :anlist))
    :ok
    lookupFun = res_lookup_fun(rRs)
    case resolve_cnames(stripdot(domain), type, lookupFun) do
      {:error, _} = error ->
        error
      {d, addrs, aliases} ->
        res_cache_answer(rRs)
        {:ok, make_hostent(d, addrs, aliases, type)}
    end
  end

  def res_option(:next_id) do
    cnt = :ets.update_counter(:inet_db, :res_id, 1)
    case cnt &&& 65535 do
      0 ->
        :ets.update_counter(:inet_db, :res_id, -cnt)
        0
      id ->
        id
    end
  end

  def res_option(option) do
    case res_optname(option) do
      :undefined ->
        :erlang.error(:badarg, [option])
      resOptname ->
        db_get(resOptname)
    end
  end

  def res_option(option, value) do
    case res_optname(option) do
      :undefined ->
        :erlang.error(:badarg, [option, value])
      _ ->
        call({:res_set, option, value})
    end
  end

  def res_update_conf(), do: res_update(:resolv_conf, :res_resolv_conf_tm)

  def res_update_hosts(), do: res_update(:hosts_file, :res_hosts_file_tm)

  def reset(), do: call(:reset)

  def sctp_module(), do: db_get(:sctp_module)

  def set_cache_refresh(time), do: call({:set_cache_refresh, time})

  def set_cache_size(limit), do: call({:set_cache_size, limit})

  def set_domain(domain), do: res_option(:domain, domain)

  def set_edns(version), do: res_option(:edns, version)

  def set_hostname(name), do: call({:set_hostname, name})

  def set_hosts_file(fname) when is_list(fname), do: res_option(:hosts_file, fname)

  def set_inet6(bool), do: res_option(:inet6, bool)

  def set_lookup(methods), do: res_option(:lookup, methods)

  def set_recurse(flag), do: res_option(:recurse, flag)

  def set_resolv_conf(fname) when is_list(fname), do: res_option(:resolv_conf, fname)

  def set_retry(n), do: res_option(:retry, n)

  def set_sctp_module(family), do: call({:set_sctp_module, family})

  def set_servfail_retry_timeout(time) when is_integer(time) and time >= 0, do: res_option(:servfail_retry_timeout, time)

  def set_socks_port(port), do: call({:set_socks_port, port})

  def set_socks_server(server), do: call({:set_socks_server, server})

  def set_tcp_module(module), do: call({:set_tcp_module, module})

  def set_timeout(time), do: res_option(:timeout, time)

  def set_udp_module(module), do: call({:set_udp_module, module})

  def set_udp_payload_size(size), do: res_option(:udp_payload_size, size)

  def set_usevc(bool), do: res_option(:usevc, bool)

  def socks_option(:server), do: db_get(:socks5_server)

  def socks_option(:port), do: db_get(:socks5_port)

  def socks_option(:methods), do: db_get(:socks5_methods)

  def socks_option(:noproxy), do: db_get(:socks5_noproxy)

  def start() do
    case :gen_server.start({:local, :inet_db}, :inet_db, [], []) do
      {:ok, _Pid} = ok ->
        :inet_config.init()
        ok
      error ->
        error
    end
  end

  def start_link() do
    case :gen_server.start_link({:local, :inet_db}, :inet_db, [], []) do
      {:ok, _Pid} = ok ->
        :inet_config.init()
        ok
      error ->
        error
    end
  end

  def stop(), do: call(:stop)

  def take_socket_type(mRef), do: call({:take_socket_type, mRef})

  def tcp_module(), do: db_get(:tcp_module)

  @spec terminate(term(), state()) :: :ok
  def terminate(_Reason, state) do
    _ = stop_timer(state(state, :cache_timer))
    :ok
  end

  def tolower(domain) do
    case rfc_4343_lc(domain) do
      :ok ->
        domain
      lcDomain ->
        lcDomain
    end
  end

  def udp_module(), do: db_get(:udp_module)

  def unregister_socket(socket) when is_port(socket), do: :ok

  # Private Functions

  defp unquote(:"-add_hosts/1-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-add_ip_bynms/5-fun-0-")(arg1, arg2, arg3, arg4, arg5) do
    # body not decompiled
  end

  defp unquote(:"-del_ip_bynms/4-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-do_add_host/5-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-do_add_host/5-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-do_add_host/5-lc$^2/1-2-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-do_add_host/5-lc$^3/1-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-do_add_rrs/4-lc$^0/1-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-do_add_rrs/4-lc$^1/1-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-do_del_host/3-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-handle_call/3-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-handle_call/3-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-handle_call/3-fun-3-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-handle_call/3-lc$^2/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-lists_subtract/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-load_hosts_list/3-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-load_hosts_list/3-lc$^1/1-1-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-lookup_cache_data/2-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-match_rr/6-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-match_rr/6-lc$^1/1-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-rc_opt_req/1-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-res_cache_answer/1-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-res_filter_rrs/2-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-res_lookup_fun/1-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-res_lookup_fun/1-lc$^0/1-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp add_ip_bynms(byname, fam, iP, nms, names) do
    :lists.foreach(fn nm ->
        key = {fam, nm}
        case :ets.lookup(byname, key) do
          [{_Key, [iP | _] = iPs, _Names_1}] ->
            true = :ets.insert(byname, {key, iPs, names})
          [{_Key, iPs, names_0}] ->
            case :lists.member(iP, iPs) do
              true ->
                :ok
              false ->
                true = :ets.insert(byname, {key, iPs ++ [iP], names_0})
            end
          [] ->
            true = :ets.insert(byname, {key, [iP], names})
        end
    end, nms)
  end

  defp alloc_entry(db, cacheDb, tM) do
    size = :ets.lookup_element(db, :cache_size, 2)
    cond do
      size <= 0 ->
        false
      true ->
        curSize = :ets.info(cacheDb, :size)
        cond do
          size <= curSize ->
            n = div(size - 1, 10) + 1
            _ = delete_oldest(cacheDb, tM, n)
            true
          true ->
            true
        end
    end
  end

  defp cache_refresh() do
    case db_get(:cache_refresh_interval) do
      :undefined ->
        60 * 60 * 1000
      val ->
        val
    end
  end

  defp call(req), do: :gen_server.call(:inet_db, req, :infinity)

  defp db_get(name) do
    try do
      :ets.lookup_element(:inet_db, name, 2)
    catch
      {:error, :badarg, _} ->
        :undefined
    end
  end

  defp del_ip_bynms(byname, fam, iP, nms) do
    :lists.foreach(fn nm ->
        key = {fam, nm}
        case :ets.lookup(byname, key) do
          [{_Key, [iP], _Names}] ->
            true = :ets.delete(byname, key)
          [{_Key, iPs_0, names_0}] ->
            case :lists.member(iP, iPs_0) do
              true ->
                iPs = :lists.delete(iP, iPs_0)
                true = :ets.insert(byname, {key, iPs, names_0})
              false ->
                :ok
            end
          [] ->
            :ok
        end
    end, nms)
  end

  defp delete_expired(cacheDb, tM), do: :ets.select_delete(cacheDb, [{dns_rr_match_tm_ttl_cnt(:"$1", :"$2", :_), [], [{:<, {:+, :"$1", :"$2"}, {:const, tM}}]}])

  defp delete_oldest(cacheDb, tM, n) do
    case :lists.sort(:ets.select(cacheDb, [{dns_rr_match_cnt(:"$1"), [], [:"$1"]}])) do
      [] ->
        0
      [oldestTM | _] = tMs ->
        delTM_A = div(tM - oldestTM, 3) + oldestTM
        delTM_B = lists_nth(n, tMs, delTM_A)
        delTM = min(delTM_A, delTM_B)
        :ets.select_delete(cacheDb, [{dns_rr_match_tm_ttl_cnt(:"$1", :"$2", :"$3"), [], [{:orelse, {:"=<", :"$3", {:const, delTM}}, {:<, {:+, :"$1", :"$2"}, {:const, tM}}}]}])
    end
  end

  defp do_add_host(byname, byaddr, names, type, iP) do
    nms = for nm <- names do
      tolower(nm)
    end
    add_ip_bynms(byname, type, iP, nms, names)
    key = {type, iP}
    try do
      :ets.lookup_element(byaddr, key, 2)
    catch
      {:error, :badarg, _} ->
        :ok
    else
      names_0 ->
        nmsSet = :lists.foldl(fn nm, set ->
            :maps.put(nm, [], set)
        end, %{}, nms)
        del_ip_bynms(byname, type, iP, (for nm <- (for name <- names_0 do
          tolower(name)
        end), not :maps.is_key(nm, nmsSet) do
          nm
        end))
    end
    :ets.insert(byaddr, {key, names})
    :ok
  end

  defp do_add_rrs(rRs, db, state) do
    cacheDb = state(state, :cache)
    do_add_rrs(rRs, db, state, cacheDb)
  end

  defp do_add_rrs([], _Db, _State, _CacheDb), do: :ok

  defp do_add_rrs([rR | rRs], db, state, cacheDb) do
    case alloc_entry(db, cacheDb, dns_rr(:tm)) do
      true ->
        dns_rr(bm: lcDomain, class: class, type: type, data: data) = rR
        deleteRRs = :ets.match_object(cacheDb, dns_rr_match(lcDomain, class, type, data))
        case :lists.member(rR, deleteRRs) do
          true ->
            _ = for delRR <- deleteRRs, delRR !== rR do
              :ets.delete_object(cacheDb, delRR)
            end
            :ok
          false ->
            :ets.insert(cacheDb, rR)
            _ = for delRR <- deleteRRs do
              :ets.delete_object(cacheDb, delRR)
            end
            :ok
        end
        do_add_rrs(rRs, db, state, cacheDb)
      false ->
        :ok
    end
  end

  defp do_del_host(byname, byaddr, iP) do
    fam = inet_family(iP)
    key = {fam, iP}
    try do
      :ets.lookup_element(byaddr, key, 2)
    catch
      {:error, :badarg, _} ->
        :ok
    else
      names ->
        del_ip_bynms(byname, fam, iP, (for name <- names do
          tolower(name)
        end))
        true = :ets.delete(byaddr, key)
        :ok
    end
  end

  defp ent_gethostbyaddr([domain], iP) do
    hEnt = cond do
      tuple_size(iP) === 4 ->
        hostent(h_name: domain, h_aliases: [], h_addr_list: [iP], h_addrtype: :inet, h_length: 4)
      tuple_size(iP) === 8 ->
        hostent(h_name: domain, h_aliases: [], h_addr_list: [iP], h_addrtype: :inet6, h_length: 16)
    end
    {:ok, hEnt}
  end

  defp ent_gethostbyaddr([_ | _] = _Domains, _IP) do
    :ok
    {:error, :nxdomain}
  end

  defp ets_clean_map_keys(tab, map) do
    true = :ets.safe_fixtable(tab, true)
    ets_clean_map_keys(tab, map, :ets.first(tab))
    true = :ets.safe_fixtable(tab, false)
    :ok
  end

  defp ets_clean_map_keys(_Tab, _Map, :"$end_of_table"), do: :ok

  defp ets_clean_map_keys(tab, map, key) do
    case :maps.is_key(key, map) do
      true ->
        ets_clean_map_keys(tab, map, :ets.next(tab, key))
      false ->
        true = :ets.delete(tab, key)
        ets_clean_map_keys(tab, map, :ets.next(tab, key))
    end
  end

  defp get_rc([k | ks], ls) do
    case k do
      :hosts ->
        get_rc_hosts(ks, ls, :inet_hosts_byaddr)
      :domain ->
        get_rc(:domain, :res_domain, "", ks, ls)
      :nameservers ->
        get_rc_ns(db_get(:res_ns), :nameservers, ks, ls)
      :alt_nameservers ->
        get_rc_ns(db_get(:res_alt_ns), :alt_nameservers, ks, ls)
      :search ->
        get_rc(:search, :res_search, [], ks, ls)
      :timeout ->
        get_rc(:timeout, :res_timeout, 2000, ks, ls)
      :retry ->
        get_rc(:retry, :res_retry, 3, ks, ls)
      :servfail_retry_timeout ->
        get_rc(:servfail_retry_timeout, :res_servfail_retry_timeout, 1500, ks, ls)
      :inet6 ->
        get_rc(:inet6, :res_inet6, false, ks, ls)
      :usevc ->
        get_rc(:usevc, :res_usevc, false, ks, ls)
      :edns ->
        get_rc(:edns, :res_edns, false, ks, ls)
      :udp_payload_size ->
        get_rc(:udp_payload_size, :res_udp_payload_size, 1280, ks, ls)
      :resolv_conf ->
        get_rc(:resolv_conf, :res_resolv_conf, :undefined, ks, ls)
      :hosts_file ->
        get_rc(:hosts_file, :res_hosts_file, :undefined, ks, ls)
      :tcp ->
        get_rc(:tcp, :tcp_module, :inet_tcp, ks, ls)
      :udp ->
        get_rc(:udp, :udp_module, :inet_udp, ks, ls)
      :sctp ->
        get_rc(:sctp, :sctp_module, :inet_sctp, ks, ls)
      :lookup ->
        get_rc(:lookup, :res_lookup, [:native, :file], ks, ls)
      :cache_size ->
        get_rc(:cache_size, :cache_size, 100, ks, ls)
      :cache_refresh ->
        get_rc(:cache_refresh, :cache_refresh_interval, 60 * 60 * 1000, ks, ls)
      :socks5_server ->
        get_rc(:socks5_server, :socks5_server, "", ks, ls)
      :socks5_port ->
        get_rc(:socks5_port, :socks5_port, 1080, ks, ls)
      :socks5_methods ->
        get_rc(:socks5_methods, :socks5_methods, [:none], ks, ls)
      :socks5_noproxy ->
        case db_get(:socks5_noproxy) do
          [] ->
            get_rc(ks, ls)
          noProxy ->
            get_rc_noproxy(noProxy, ks, ls)
        end
      _ ->
        get_rc(ks, ls)
    end
  end

  defp get_rc([], ls), do: :lists.reverse(ls)

  defp get_rc(name, key, default, ks, ls) do
    case db_get(key) do
      default ->
        get_rc(ks, ls)
      value ->
        get_rc(ks, [{name, value} | ls])
    end
  end

  defp get_rc_hosts([], ls), do: ls

  defp get_rc_hosts([{{_Fam, iP}, names} | hosts], ls), do: get_rc_hosts(hosts, [{:host, iP, names} | ls])

  defp get_rc_hosts(ks, ls, tab), do: get_rc(ks, get_rc_hosts(:ets.tab2list(tab), ls))

  defp get_rc_noproxy([{net, mask} | ms], ks, ls), do: get_rc_noproxy(ms, ks, [{:socks5_noproxy, net, mask} | ls])

  defp get_rc_noproxy([], ks, ls), do: get_rc(ks, ls)

  defp get_rc_ns([{iP, 53} | ns], tag, ks, ls), do: get_rc_ns(ns, tag, ks, [{tag, iP} | ls])

  defp get_rc_ns([{iP, port} | ns], tag, ks, ls), do: get_rc_ns(ns, tag, ks, [{tag, iP, port} | ls])

  defp get_rc_ns([], _Tag, ks, ls), do: get_rc(ks, ls)

  defp getbysearch(name, dot, [dom | ds], type, _) do
    case hostent_by_domain(name ++ dot ++ dom, type) do
      {:ok, _HEnt} = ok ->
        ok
      error ->
        getbysearch(name, dot, ds, type, error)
    end
  end

  defp getbysearch(_Name, _Dot, [], _Type, error), do: error

  defp handle_calls([], _From, state), do: {:reply, :ok, state}

  defp handle_calls([req | reqs], from, state) do
    case handle_call(req, from, state) do
      {:reply, :ok, newState} ->
        handle_calls(reqs, from, newState)
      {:reply, _, newState} ->
        {:reply, :error, newState}
    end
  end

  defp handle_calls(req, from, state), do: handle_call(req, from, state)

  defp handle_put_socket_type(db, mRef, type) do
    key = {:type, mRef}
    case :ets.lookup(db, key) do
      [_] ->
        :error
      [] ->
        :ets.insert(db, {key, type})
        :ok
    end
  end

  defp handle_rc_list([], _From, state), do: {:reply, :ok, state}

  defp handle_rc_list([opt | opts], from, state) do
    case rc_opt_req(opt) do
      :undefined ->
        {:reply, {:error, {:badopt, opt}}, state}
      req ->
        case handle_calls(req, from, state) do
          {:reply, :ok, newState} ->
            handle_rc_list(opts, from, newState)
          result ->
            result
        end
    end
  end

  defp handle_rc_list(_, _From, state), do: {:reply, :error, state}

  defp handle_set_file(parseFun, file, bin, from, state) do
    case parseFun.(file, bin) do
      :error ->
        {:reply, :error, state}
      opts ->
        handle_rc_list(opts, from, state)
    end
  end

  defp handle_set_file(option, tm, tagTm, tagInfo, parseFun, from, state(db: db) = state) when is_integer(tm) do
    try do
      :ets.lookup_element(db, tagTm, 2)
    catch
      {:error, :badarg, _} ->
        {:reply, :ok, state}
    else
      ^tm ->
        file = :ets.lookup_element(db, res_optname(option), 2)
        finfo = :ets.lookup_element(db, tagInfo, 2)
        handle_update_file(finfo, file, tagTm, tagInfo, parseFun, from, state)
      _ ->
        {:reply, :ok, state}
    end
  end

  defp handle_set_file(option, fname, tagTm, tagInfo, parseFun, from, state(db: db) = state) do
    case res_check_option(option, fname) do
      true when fname === "" ->
        :ets.insert(db, {res_optname(option), fname})
        :ets.delete(db, tagInfo)
        :ets.delete(db, tagTm)
        handle_set_file(parseFun, fname, <<>>, from, state)
      true when parseFun === :undefined ->
        file = :filename.flatten(fname)
        :ets.insert(db, {res_optname(option), file})
        :ets.insert(db, {tagInfo, :undefined})
        timeZero = times() - 5 + 1
        :ets.insert(db, {tagTm, timeZero})
        {:reply, :ok, state}
      true ->
        file = :filename.flatten(fname)
        :ets.insert(db, {res_optname(option), file})
        handle_update_file(:undefined, file, tagTm, tagInfo, parseFun, from, state)
      false ->
        {:reply, :error, state}
    end
  end

  defp handle_take_socket_type(db, mRef) do
    key = {:type, mRef}
    case :ets.take(db, key) do
      [{key, type}] ->
        {:ok, type}
      [] ->
        :error
    end
  end

  defp handle_update_file(finfo, file, tagTm, tagInfo, parseFun, from, state(db: db) = state) do
    :ets.insert(db, {tagTm, times()})
    case :erl_prim_loader.read_file_info(file) do
      {:ok, finfo} ->
        {:reply, :ok, state}
      {:ok, finfo_1} ->
        :ets.insert(db, {tagInfo, finfo_1})
        bin = case :erl_prim_loader.get_file(file) do
          {:ok, b, _} ->
            b
          _ ->
            <<>>
        end
        handle_set_file(parseFun, file, bin, from, state)
      _ ->
        :ets.insert(db, {tagInfo, :undefined})
        handle_set_file(parseFun, file, <<>>, from, state)
    end
  end

  defp hostent_by_domain(domain, type) do
    :ok
    case resolve_cnames(stripdot(domain), type, &lookup_cache_data/2) do
      {:error, _} = error ->
        error
      {d, addrs, aliases} ->
        {:ok, make_hostent(d, addrs, aliases, type)}
    end
  end

  defp inet_family(t) when tuple_size(t) === 4, do: :inet

  defp inet_family(t) when tuple_size(t) === 8, do: :inet6

  defp init_timer(), do: :erlang.send_after(cache_refresh(), self(), :refresh_timeout)

  defp is_reqname(:reset), do: true

  defp is_reqname(:clear_cache), do: true

  defp is_reqname(:clear_hosts), do: true

  defp is_reqname(_), do: false

  defp is_res_set(:domain), do: true

  defp is_res_set(:lookup), do: true

  defp is_res_set(:timeout), do: true

  defp is_res_set(:servfail_retry_timeout), do: true

  defp is_res_set(:retry), do: true

  defp is_res_set(:inet6), do: true

  defp is_res_set(:usevc), do: true

  defp is_res_set(:edns), do: true

  defp is_res_set(:udp_payload_size), do: true

  defp is_res_set(:resolv_conf), do: true

  defp is_res_set(:hosts_file), do: true

  defp is_res_set(_), do: false

  defp lists_delete(_, []), do: []

  defp lists_delete(e, [^e | es]), do: lists_delete(e, es)

  defp lists_delete(e, [x | es]), do: [x | lists_delete(e, es)]

  defp lists_keydelete(_, _, []), do: []

  defp lists_keydelete(k, n, [t | ts]) when element(n, t) === k, do: lists_keydelete(k, n, ts)

  defp lists_keydelete(k, n, [x | ts]), do: [x | lists_keydelete(k, n, ts)]

  defp lists_nth(0, list, default) when is_list(list), do: default

  defp lists_nth(1, [h | _], _Default), do: h

  defp lists_nth(_N, [], default), do: default

  defp lists_nth(n, [_ | t], default), do: lists_nth(n - 1, t, default)

  defp lists_subtract(as0, bs) do
    :lists.foldl(fn e, as ->
        lists_delete(e, as)
    end, as0, bs)
  end

  defp load_hosts_list(hosts), do: load_hosts_list_byaddr(hosts, %{}, [])

  defp load_hosts_list(hosts, byname, byaddr) do
    {byaddrMap, bynameMap} = load_hosts_list(hosts)
    :ets.insert(byaddr, (for {addr, namesR} <- :maps.to_list(byaddrMap) do
      {addr, :lists.reverse(namesR)}
    end))
    :ets.insert(byname, (for {fam_Nm, {iPsR, names}} <- :maps.to_list(bynameMap) do
      {fam_Nm, :lists.reverse(iPsR), names}
    end))
    ets_clean_map_keys(byaddr, byaddrMap)
    ets_clean_map_keys(byname, bynameMap)
  end

  defp load_hosts_list_byaddr([], byaddrMap, addrs), do: load_hosts_list_byname(:lists.reverse(addrs), byaddrMap, %{})

  defp load_hosts_list_byaddr([{iP, name, aliases} | hosts], byaddrMap, addrs) do
    addr = {inet_family(iP), iP}
    case byaddrMap do
      %{addr => namesR} ->
        load_hosts_list_byaddr(hosts, %{byaddrMap | addr => :lists.reverse(aliases, [name | namesR])}, addrs)
      %{} ->
        load_hosts_list_byaddr(hosts, %{byaddrMap | addr => :lists.reverse(aliases, [name])}, [addr | addrs])
    end
  end

  defp load_hosts_list_byname([], byaddrMap, bynameMap), do: {byaddrMap, bynameMap}

  defp load_hosts_list_byname([{fam, iP} = addr | addrs], byaddrMap, bynameMap) do
    names = :lists.reverse(:maps.get(addr, byaddrMap))
    load_hosts_list_byname(addrs, byaddrMap, load_hosts_list_byname(fam, iP, bynameMap, names, names))
  end

  defp load_hosts_list_byname(_Fam, _IP, bynameMap, _Names_0, []), do: bynameMap

  defp load_hosts_list_byname(fam, iP, bynameMap, names_0, [name | names]) do
    key = {fam, tolower(name)}
    case bynameMap do
      %{key => {iPsR, names_1}} ->
        load_hosts_list_byname(fam, iP, %{bynameMap | key => {[iP | iPsR], names_1}}, names_0, names)
      %{} ->
        load_hosts_list_byname(fam, iP, %{bynameMap | key => {[iP], names_0}}, names_0, names)
    end
  end

  defp lookup_cache_data(lcDomain, type) do
    for dns_rr(data: data) <- match_rr(dns_rr_match(lcDomain, :in, type)) do
      data
    end
  end

  defp make_hostent(name, addrs, aliases, :a), do: hostent(h_name: name, h_addrtype: :inet, h_length: 4, h_addr_list: addrs, h_aliases: aliases)

  defp make_hostent(name, addrs, aliases, :aaaa), do: hostent(h_name: name, h_addrtype: :inet6, h_length: 16, h_addr_list: addrs, h_aliases: aliases)

  defp make_hostent(name, datas, aliases, type), do: hostent(h_name: name, h_addrtype: type, h_length: length(datas), h_addr_list: datas, h_aliases: aliases)

  defp match_rr(matchRR) do
    cacheDb = :inet_cache
    rRs = :ets.match_object(cacheDb, matchRR)
    match_rr(cacheDb, rRs, times(), %{}, %{}, [])
  end

  defp match_rr(cacheDb, [], _Time, resultRRs, insertRRs, deleteRRs) do
    _ = for rR <- :maps.values(insertRRs) do
      :ets.insert(cacheDb, rR)
    end
    _ = for rR <- deleteRRs do
      :ets.delete_object(cacheDb, rR)
    end
    :maps.values(resultRRs)
  end

  defp match_rr(cacheDb, [rR | rRs], time, resultRRs, insertRRs, deleteRRs) do
    dns_rr(ttl: tTL, tm: tM, cnt: cnt) = rR
    cond do
      tM + tTL < time ->
        match_rr(cacheDb, rRs, time, resultRRs, insertRRs, [rR | deleteRRs])
      time <= cnt ->
        key = match_rr_key(rR)
        match_rr(cacheDb, rRs, time, %{resultRRs | key => rR}, insertRRs, deleteRRs)
      true ->
        key = match_rr_key(rR)
        match_rr(cacheDb, rRs, time, %{resultRRs | key => rR}, %{insertRRs | key => dns_rr(rR, cnt: time)}, [rR | deleteRRs])
    end
  end

  defp rc_opt_req({:nameserver, ns}), do: {:listop, :nameservers, :add, {ns, 53}}

  defp rc_opt_req({:nameserver, ns, port}), do: {:listop, :nameservers, :add, {ns, port}}

  defp rc_opt_req({:alt_nameserver, ns}), do: {:listop, :alt_nameservers, :add, {ns, 53}}

  defp rc_opt_req({:alt_nameserver, ns, port}), do: {:listop, :alt_nameservers, :add, {ns, port}}

  defp rc_opt_req({:socks5_noproxy, iP, mask}), do: {:add_socks_noproxy, {iP, mask}}

  defp rc_opt_req({:search, ds}) when is_list(ds) do
    try do
      for d <- ds do
      {:listop, :search, :add, d}
    end
    catch
      {:error, _, _} ->
        :undefined
    end
  end

  defp rc_opt_req({:host, iP, aliases}), do: {:add_host, iP, aliases}

  defp rc_opt_req({:load_hosts_file, _} = req), do: req

  defp rc_opt_req({:lookup, ls}) do
    try do
      {:res_set, :lookup, translate_lookup(ls)}
    catch
      {:error, _, _} ->
        :undefined
    end
  end

  defp rc_opt_req({:replace_ns, ns}), do: {:listreplace, :nameservers, ns}

  defp rc_opt_req({:replace_search, search}), do: {:listreplace, :search, search}

  defp rc_opt_req({name, arg}) do
    case rc_reqname(name) do
      :undefined ->
        case is_res_set(name) do
          true ->
            {:res_set, name, arg}
          false ->
            :undefined
        end
      req ->
        {req, arg}
    end
  end

  defp rc_opt_req(:clear_ns), do: [{:listreplace, :nameservers, []}, {:listreplace, :alt_nameservers, []}]

  defp rc_opt_req(:clear_search), do: {:listreplace, :search, []}

  defp rc_opt_req(opt) when is_atom(opt) do
    case is_reqname(opt) do
      true ->
        opt
      false ->
        :undefined
    end
  end

  defp rc_opt_req(_), do: :undefined

  defp rc_reqname(:socks5_server), do: :set_socks_server

  defp rc_reqname(:socks5_port), do: :set_socks_port

  defp rc_reqname(:socks5_methods), do: :set_socks_methods

  defp rc_reqname(:cache_refresh), do: :set_cache_refresh

  defp rc_reqname(:cache_size), do: :set_cache_size

  defp rc_reqname(:udp), do: :set_udp_module

  defp rc_reqname(:sctp), do: :set_sctp_module

  defp rc_reqname(:tcp), do: :set_tcp_module

  defp rc_reqname(_), do: :undefined

  defp res_cache_answer(rRs) do
    tM = times()
    call({:add_rrs, for dns_rr(ttl: tTL) = rR <- rRs, 0 < ^tTL do
      dns_rr(rR, bm: tolower(dns_rr(rR, :domain)), tm: tM, cnt: ^tM)
    end})
  end

  defp res_check_list([], _Fun), do: true

  defp res_check_list([h | t], fun), do: fun.(h) and res_check_list(t, fun)

  defp res_check_list(_, _Fun), do: false

  defp res_check_ns({{a, b, c, d, e, f, g, h}, port}) when a ||| b ||| c ||| d ||| e ||| f ||| g ||| h &&& ~~~65535 === 0 and port &&& 65535 === port, do: true

  defp res_check_ns({{a, b, c, d}, port}) when a ||| b ||| c ||| d &&& ~~~255 === 0 and port &&& 65535 === port, do: true

  defp res_check_ns(_), do: false

  defp res_check_option_absfile(f) do
    try do
      :filename.pathtype(f)
    catch
      {_, _, _} ->
        false
    else
      :absolute ->
        true
      _ ->
        false
    end
  end

  defp res_check_search(""), do: true

  defp res_check_search(dom), do: :inet_parse.visible_string(dom)

  defp res_filter_rrs(type, rRs) do
    for dns_rr(domain: n, class: :in, type: t) = rR <- rRs, ^t === ^type or ^t === :cname do
      dns_rr(rR, bm: tolower(n))
    end
  end

  defp res_lookup_fun(rRs) do
    fn lcDomain, type ->
        for dns_rr(bm: lcD, type: t, data: data) <- rRs, lcD === lcDomain, t === type do
          data
        end
    end
  end

  defp res_optname(:nameserver), do: :res_ns

  defp res_optname(:alt_nameserver), do: :res_alt_ns

  defp res_optname(:nameservers), do: :res_ns

  defp res_optname(:alt_nameservers), do: :res_alt_ns

  defp res_optname(:domain), do: :res_domain

  defp res_optname(:lookup), do: :res_lookup

  defp res_optname(:recurse), do: :res_recurse

  defp res_optname(:search), do: :res_search

  defp res_optname(:retry), do: :res_retry

  defp res_optname(:servfail_retry_timeout), do: :res_servfail_retry_timeout

  defp res_optname(:timeout), do: :res_timeout

  defp res_optname(:inet6), do: :res_inet6

  defp res_optname(:usevc), do: :res_usevc

  defp res_optname(:edns), do: :res_edns

  defp res_optname(:udp_payload_size), do: :res_udp_payload_size

  defp res_optname(:resolv_conf), do: :res_resolv_conf

  defp res_optname(:resolv_conf_name), do: :res_resolv_conf

  defp res_optname(:hosts_file), do: :res_hosts_file

  defp res_optname(:hosts_file_name), do: :res_hosts_file

  defp res_optname(_), do: :undefined

  defp res_update(option, tagTm) do
    case db_get(tagTm) do
      :undefined ->
        :ok
      tm ->
        case times() do
          now when now >= tm + 5 ->
            res_option(option, tm)
          _ ->
            :ok
        end
    end
  end

  defp reset_db(db), do: :ets.insert(db, [{:hostname, []}, {:res_ns, []}, {:res_alt_ns, []}, {:res_search, []}, {:res_domain, ""}, {:res_lookup, []}, {:res_recurse, true}, {:res_usevc, false}, {:res_id, 0}, {:res_retry, 3}, {:res_servfail_retry_timeout, 1500}, {:res_timeout, 2000}, {:res_inet6, false}, {:res_edns, false}, {:res_udp_payload_size, 1280}, {:cache_size, 100}, {:cache_refresh_interval, 60 * 60 * 1000}, {:socks5_server, ""}, {:socks5_port, 1080}, {:socks5_methods, [:none]}, {:socks5_noproxy, []}, {:tcp_module, :inet_tcp}, {:udp_module, :inet_udp}, {:sctp_module, :inet_sctp}])

  defp resolve_cnames(domain, type, lookupFun), do: resolve_cnames(domain, type, lookupFun, tolower(domain), [], [])

  defp resolve_cnames(domain, type, lookupFun, lcDomain, aliases, lcAliases) do
    case lookupFun.(lcDomain, type) do
      [] ->
        case lookupFun.(lcDomain, :cname) do
          [] ->
            {:error, :nxdomain}
          [cName] ->
            lcCname = tolower(cName)
            case :lists.member(lcCname, [lcDomain | lcAliases]) do
              true ->
                {:error, :nxdomain}
              false ->
                resolve_cnames(cName, type, lookupFun, lcCname, [domain | aliases], [lcDomain, lcAliases])
            end
          [_ | _] = _CNames ->
            :ok
            {:error, :nxdomain}
        end
      [_ | _] = results ->
        {domain, results, aliases}
    end
  end

  defp rfc_4343_lc([]), do: :ok

  defp rfc_4343_lc([c | cs]) when is_integer(c) and 0 <= c and c <= 1114111 do
    cond do
      ?A <= c and c <= ?Z ->
        [c - ?A + ?a | (case rfc_4343_lc(cs) do
          :ok ->
            cs
          lCs ->
            lCs
        end)]
      true ->
        case rfc_4343_lc(cs) do
          :ok ->
            :ok
          lCs ->
            [c | lCs]
        end
    end
  end

  defp stop_timer(:undefined), do: :undefined

  defp stop_timer(timer), do: :erlang.cancel_timer(timer)

  defp stripdot(name) do
    case stripdot_1(name) do
      false ->
        name
      n ->
        n
    end
  end

  defp stripdot_1([?.]), do: []

  defp stripdot_1([]), do: false

  defp stripdot_1([h | t]) do
    case stripdot_1(t) do
      false ->
        false
      n ->
        [h | n]
    end
  end

  defp times(), do: :erlang.monotonic_time(:second)

  defp translate_lookup(['bind' | ls]), do: [:dns | translate_lookup(ls)]

  defp translate_lookup(['dns' | ls]), do: [:dns | translate_lookup(ls)]

  defp translate_lookup(['hosts' | ls]), do: [:file | translate_lookup(ls)]

  defp translate_lookup(['files' | ls]), do: [:file | translate_lookup(ls)]

  defp translate_lookup(['file' | ls]), do: [:file | translate_lookup(ls)]

  defp translate_lookup(['yp' | ls]), do: [:yp | translate_lookup(ls)]

  defp translate_lookup(['nis' | ls]), do: [:nis | translate_lookup(ls)]

  defp translate_lookup(['nisplus' | ls]), do: [:nisplus | translate_lookup(ls)]

  defp translate_lookup(['native' | ls]), do: [:native | translate_lookup(ls)]

  defp translate_lookup([m | ls]) when is_atom(m), do: translate_lookup([atom_to_list(m) | ls])

  defp translate_lookup([_ | ls]), do: translate_lookup(ls)

  defp translate_lookup([]), do: []

  defp valid_lookup(), do: [:dns, :file, :yp, :nis, :nisplus, :native]
end
