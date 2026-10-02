# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :ssh_options do

  # Types

  @type private_options :: %{required(:socket_options) => socket_options(), required(:internal_options) => internal_options(), optional(option_key()) => any()}

  # Private Types

  @typep accept_callback :: ((peerName :: charlist(), fingerprint() -> boolean()) | (peerName :: charlist(), port :: :inet.port_number(), fingerprint() -> boolean()))

  @typep accept_hosts :: (boolean() | accept_callback() | {hashAlgoSpec :: fp_digest_alg(), accept_callback()})

  @typep alg_entry :: ({:kex, [kex_alg()]} | {:public_key, [pubkey_alg()]} | {:cipher, double_algs(cipher_alg())} | {:mac, double_algs(mac_alg())} | {:compression, double_algs(compression_alg())})

  @typep algs_list :: [alg_entry()]

  @typep auth_methods_common_option :: {:auth_methods, charlist()}

  @typep authentication_client_options :: ({:user, charlist()} | {:password, charlist()})

  @typep authentication_daemon_options :: (:ssh_file.system_dir_daemon_option() | {:auth_method_kb_interactive_data, prompt_texts()} | {:user_passwords, [{userName :: charlist(), pwd :: charlist()}]} | {:pk_check_user, boolean()} | {:password, charlist()} | {:pwdfun, (pwdfun_2() | pwdfun_4())})

  @typep callbacks_daemon_options :: ({:failfun, (user :: charlist(), peerAddress :: :inet.ip_address(), reason :: term() -> any())} | {:connectfun, (user :: charlist(), peerAddress :: :inet.ip_address(), method :: charlist() -> any())})

  @typep cipher_alg :: (:"3des-cbc" | :AEAD_AES_128_GCM | :AEAD_AES_256_GCM | :"aes128-cbc" | :"aes128-ctr" | :"aes128-gcm@openssh.com" | :"aes192-ctr" | :"aes192-cbc" | :"aes256-cbc" | :"aes256-ctr" | :"aes256-gcm@openssh.com" | :"chacha20-poly1305@openssh.com")

  @typep client_option :: (:ssh_file.pubkey_passphrase_client_options() | host_accepting_client_options() | authentication_client_options() | diffie_hellman_group_exchange_client_option() | connect_timeout_client_option() | recv_ext_info_client_option() | opaque_client_options() | :gen_tcp.connect_option() | common_option())

  @typep client_options :: [client_option()]

  @typep common_option :: (:ssh_file.user_dir_common_option() | profile_common_option() | max_idle_time_common_option() | key_cb_common_option() | disconnectfun_common_option() | unexpectedfun_common_option() | ssh_msg_debug_fun_common_option() | rekey_limit_common_option() | id_string_common_option() | pref_public_key_algs_common_option() | preferred_algorithms_common_option() | modify_algorithms_common_option() | auth_methods_common_option() | inet_common_option() | fd_common_option())

  @typep common_options :: [common_option()]

  @typep compression_alg :: (:none | :zlib | :"zlib@openssh.com")

  @typep connect_timeout_client_option :: {:connect_timeout, timeout()}

  @typep daemon_option :: (subsystem_daemon_option() | shell_daemon_option() | exec_daemon_option() | ssh_cli_daemon_option() | tcpip_tunnel_out_daemon_option() | tcpip_tunnel_in_daemon_option() | authentication_daemon_options() | diffie_hellman_group_exchange_daemon_option() | negotiation_timeout_daemon_option() | hello_timeout_daemon_option() | hardening_daemon_options() | callbacks_daemon_options() | send_ext_info_daemon_option() | opaque_daemon_options() | :gen_tcp.listen_option() | common_option())

  @typep daemon_options :: [daemon_option()]

  @typep deprecated_exec_opt :: (function() | mod_fun_args())

  @typep diffie_hellman_group_exchange_client_option :: {:dh_gex_limits, {min :: pos_integer(), i :: pos_integer(), max :: pos_integer()}}

  @typep diffie_hellman_group_exchange_daemon_option :: ({:dh_gex_groups, ([explicit_group()] | explicit_group_file() | ssh_moduli_file())} | {:dh_gex_limits, {min :: pos_integer(), max :: pos_integer()}})

  @typep disconnectfun_common_option :: {:disconnectfun, (reason :: term() -> (:void | any()))}

  @typep double_algs(algType) :: ([({:client2server, [algType]} | {:server2client, [algType]})] | [algType])

  @typep error :: {:error, {:eoptions, any()}}

  @typep exec_daemon_option :: {:exec, exec_spec()}

  @typep exec_fun :: (unquote(:"exec_fun/1")() | unquote(:"exec_fun/2")() | unquote(:"exec_fun/3")())

  @typep unquote(:"exec_fun/1") :: (cmd :: charlist() -> exec_result())

  @typep unquote(:"exec_fun/2") :: (cmd :: charlist(), user :: charlist() -> exec_result())

  @typep unquote(:"exec_fun/3") :: (cmd :: charlist(), user :: charlist(), clientAddr :: ip_port() -> exec_result())

  @typep exec_result :: ({:ok, result :: term()} | {:error, reason :: term()})

  @typep exec_spec :: ({:direct, exec_fun()} | :disabled | deprecated_exec_opt())

  @typep explicit_group :: {size :: pos_integer(), g :: pos_integer(), p :: pos_integer()}

  @typep explicit_group_file :: {:file, charlist()}

  @typep fd_common_option :: {:fd, :gen_tcp.socket()}

  @typep fingerprint :: (charlist() | [charlist()])

  @typep fp_digest_alg :: (:md5 | :crypto.sha1() | :crypto.sha2())

  @typep hardening_daemon_options :: ({:max_sessions, pos_integer()} | {:max_channels, pos_integer()} | {:parallel_login, boolean()} | {:minimal_remote_max_packet_size, pos_integer()})

  @typep hello_timeout_daemon_option :: {:hello_timeout, timeout()}

  @typep host :: (charlist() | :inet.ip_address() | :loopback)

  @typep host_accepting_client_options :: ({:silently_accept_hosts, accept_hosts()} | {:user_interaction, boolean()} | {:save_accepted_host, boolean()} | {:quiet_mode, boolean()})

  @typep id_string_common_option :: {:id_string, (charlist() | :random | {:random, nmin :: pos_integer(), nmax :: pos_integer()})}

  @typep inet_common_option :: {:inet, (:inet | :inet6)}

  @typep internal_options :: :ssh_options.private_options()

  @typep ip_port :: {:inet.ip_address(), :inet.port_number()}

  @typep kb_int_fun_3 :: (peer :: ip_port(), user :: charlist(), service :: charlist() -> kb_int_tuple())

  @typep kb_int_fun_4 :: (peer :: ip_port(), user :: charlist(), service :: charlist(), state :: any() -> kb_int_tuple())

  @typep kb_int_tuple :: {name :: charlist(), instruction :: charlist(), prompt :: charlist(), echo :: boolean()}

  @typep kex_alg :: (:"diffie-hellman-group-exchange-sha1" | :"diffie-hellman-group-exchange-sha256" | :"diffie-hellman-group1-sha1" | :"diffie-hellman-group14-sha1" | :"diffie-hellman-group14-sha256" | :"diffie-hellman-group16-sha512" | :"diffie-hellman-group18-sha512" | :"curve25519-sha256" | :"curve25519-sha256@libssh.org" | :"curve448-sha512" | :"ecdh-sha2-nistp256" | :"ecdh-sha2-nistp384" | :"ecdh-sha2-nistp521")

  @typep key_cb_common_option :: {:key_cb, module :: (atom() | {module :: atom(), opts :: [term()]})}

  @typep limit_bytes :: (non_neg_integer() | :infinity)

  @typep limit_time :: (pos_integer() | :infinity)

  @typep mac_alg :: (:AEAD_AES_128_GCM | :AEAD_AES_256_GCM | :"hmac-sha1" | :"hmac-sha1-etm@openssh.com" | :"hmac-sha1-96" | :"hmac-sha2-256" | :"hmac-sha2-512" | :"hmac-sha2-256-etm@openssh.com" | :"hmac-sha2-512-etm@openssh.com")

  @typep max_idle_time_common_option :: {:idle_time, timeout()}

  @typep mod_args :: {module :: atom(), args :: []}

  @typep mod_fun_args :: {module :: atom(), function :: atom(), args :: []}

  @typep modify_algorithms_common_option :: {:modify_algorithms, modify_algs_list()}

  @typep modify_algs_list :: [({:append, algs_list()} | {:prepend, algs_list()} | {:rm, algs_list()})]

  @typep negotiation_timeout_daemon_option :: {:negotiation_timeout, timeout()}

  @typep opaque_client_options :: ({:keyboard_interact_fun, (name :: iodata(), instruction :: iodata(), prompts :: [{prompt :: iodata(), echo :: boolean()}] -> [response :: iodata()])} | opaque_common_options())

  @typep opaque_common_options :: ({:transport, {atom(), atom(), atom()}} | {:vsn, {non_neg_integer(), non_neg_integer()}} | {:tstflg, [term()]} | :ssh_file.user_dir_fun_common_option() | {:max_random_length_padding, non_neg_integer()})

  @typep opaque_daemon_options :: ({:infofun, function()} | opaque_common_options())

  @typep open_socket :: :gen_tcp.socket()

  @typep option_class :: (:internal_options | :socket_options | :user_options)

  @typep option_declaration :: %{required(:class) => (:user_option | :undoc_user_option), required(:chk) => (any() -> (boolean() | {true, any()})), optional(:default) => any()}

  @typep option_declarations :: %{required(option_key()) => option_declaration()}

  @typep option_in :: (:proplists.property() | :proplists.proplist())

  @typep option_key :: atom()

  @typep pref_public_key_algs_common_option :: {:pref_public_key_algs, [pubkey_alg()]}

  @typep preferred_algorithms_common_option :: {:preferred_algorithms, algs_list()}

  @typep profile_common_option :: {:profile, atom()}

  @typep prompt_texts :: (kb_int_tuple() | kb_int_fun_3() | kb_int_fun_4())

  @typep pubkey_alg :: (:"ecdsa-sha2-nistp256" | :"ecdsa-sha2-nistp384" | :"ecdsa-sha2-nistp521" | :"ssh-ed25519" | :"ssh-ed448" | :"rsa-sha2-256" | :"rsa-sha2-512" | :"ssh-dss" | :"ssh-rsa")

  @typep pwdfun_2 :: (user :: charlist(), password :: (charlist() | :pubkey) -> boolean())

  @typep pwdfun_4 :: (user :: charlist(), password :: (charlist() | :pubkey), peerAddress :: ip_port(), state :: any() -> (boolean() | :disconnect | {boolean(), newState :: any()}))

  @typep recv_ext_info_client_option :: {:recv_ext_info, boolean()}

  @typep rekey_limit_common_option :: {:rekey_limit, bytes :: (limit_bytes() | {minutes :: limit_time(), bytes :: limit_bytes()})}

  @typep role :: (:client | :server)

  @typep send_ext_info_daemon_option :: {:send_ext_info, boolean()}

  @typep shell_daemon_option :: {:shell, shell_spec()}

  @typep shell_fun :: (unquote(:"shell_fun/1")() | unquote(:"shell_fun/2")())

  @typep unquote(:"shell_fun/1") :: (user :: charlist() -> pid())

  @typep unquote(:"shell_fun/2") :: (user :: charlist(), peerAddr :: :inet.ip_address() -> pid())

  @typep shell_spec :: (mod_fun_args() | shell_fun() | :disabled)

  @typep socket_options :: [(:gen_tcp.connect_option() | :gen_tcp.listen_option())]

  @typep ssh_cli_daemon_option :: {:ssh_cli, (mod_args() | :no_cli)}

  @typep ssh_moduli_file :: {:ssh_moduli_file, charlist()}

  @typep ssh_msg_debug_fun_common_option :: {:ssh_msg_debug_fun, (:ssh.connection_ref(), alwaysDisplay :: boolean(), msg :: binary(), languageTag :: binary() -> any())}

  @typep subsystem_daemon_option :: {:subsystems, subsystem_specs()}

  @typep subsystem_spec :: {name :: charlist(), mod_args()}

  @typep subsystem_specs :: [subsystem_spec()]

  @typep tcpip_tunnel_in_daemon_option :: {:tcpip_tunnel_in, boolean()}

  @typep tcpip_tunnel_out_daemon_option :: {:tcpip_tunnel_out, boolean()}

  @typep unexpectedfun_common_option :: {:unexpectedfun, (message :: term(), {host :: term(), port :: term()} -> (:report | :skip))}

  # Functions

  def check_preferred_algorithms(algs) when is_list(algs) do
    check_input_ok(algs)
    {true, normalize_mod_algs(algs, true)}
  end

  def check_preferred_algorithms(_), do: error_in_check(:modify_algorithms, 'Bad option value. List expected.')

  @spec default((role() | :common)) :: option_declarations()
  def default(:server) do
    %{default(:common) | :subsystems => %{:default => [:ssh_sftpd.subsystem_spec([])], :chk => fn l ->
        is_list(l) and :lists.all(fn {name, {cB, args}} ->
            check_string(name) and is_atom(cB) and is_list(args)
          _ ->
            false
        end, l)
    end, :class => :user_option}, :shell => %{:default => {:shell, :start, []}, :chk => fn {m, f, a} ->
        is_atom(m) and is_atom(f) and is_list(a)
      :disabled ->
        true
      v ->
        check_function1(v) or check_function2(v)
    end, :class => :user_option}, :exec => %{:default => :undefined, :chk => fn {:direct, v} ->
        check_function1(v) or check_function2(v) or check_function3(v)
      :disabled ->
        true
      {m, f, a} ->
        is_atom(m) and is_atom(f) and is_list(a)
      v ->
        check_function1(v) or check_function2(v) or check_function3(v)
    end, :class => :user_option}, :ssh_cli => %{:default => :undefined, :chk => fn {cb, as} ->
        is_atom(cb) and is_list(as)
      v ->
        v == :no_cli
    end, :class => :user_option}, :tcpip_tunnel_out => %{:default => false, :chk => fn v ->
        :erlang.is_boolean(v)
    end, :class => :user_option}, :tcpip_tunnel_in => %{:default => false, :chk => fn v ->
        :erlang.is_boolean(v)
    end, :class => :user_option}, :system_dir => %{:default => '/etc/ssh', :chk => fn v ->
        check_string(v) and check_dir(v)
    end, :class => :user_option}, :auth_method_kb_interactive_data => %{:default => :undefined, :chk => fn {s1, s2, s3, b} ->
        check_string(s1) and check_string(s2) and check_string(s3) and is_boolean(b)
      f ->
        check_function3(f) or check_function4(f)
    end, :class => :user_option}, :user_passwords => %{:default => [], :chk => fn v ->
        is_list(v) and :lists.all(fn {s1, s2} ->
            check_string(s1) and check_string(s2)
        end, v)
    end, :class => :user_option}, :pk_check_user => %{:default => false, :chk => fn v ->
        :erlang.is_boolean(v)
    end, :class => :user_option}, :password => %{:default => :undefined, :chk => fn v ->
        check_string(v)
    end, :class => :user_option}, :dh_gex_groups => %{:default => :undefined, :chk => fn v ->
        check_dh_gex_groups(v)
    end, :class => :user_option}, :dh_gex_limits => %{:default => {0, :infinity}, :chk => fn {i1, i2} ->
        check_pos_integer(i1) and check_pos_integer(i2) and i1 < i2
      _ ->
        false
    end, :class => :user_option}, :pwdfun => %{:default => :undefined, :chk => fn v ->
        check_function4(v) or check_function2(v)
    end, :class => :user_option}, :negotiation_timeout => %{:default => 2 * 60 * 1000, :chk => fn v ->
        check_timeout(v)
    end, :class => :user_option}, :hello_timeout => %{:default => 30 * 1000, :chk => &check_timeout/1, :class => :user_option}, :max_sessions => %{:default => :infinity, :chk => fn v ->
        check_pos_integer(v)
    end, :class => :user_option}, :max_channels => %{:default => :infinity, :chk => fn v ->
        check_pos_integer(v)
    end, :class => :user_option}, :parallel_login => %{:default => false, :chk => fn v ->
        :erlang.is_boolean(v)
    end, :class => :user_option}, :minimal_remote_max_packet_size => %{:default => 0, :chk => fn v ->
        check_pos_integer(v)
    end, :class => :user_option}, :failfun => %{:default => fn _, _, _ ->
        :void
    end, :chk => fn v ->
        check_function3(v) or check_function2(v)
    end, :class => :user_option}, :connectfun => %{:default => fn _, _, _ ->
        :void
    end, :chk => fn v ->
        check_function3(v)
    end, :class => :user_option}, :infofun => %{:default => fn _, _, _ ->
        :void
    end, :chk => fn v ->
        check_function3(v) or check_function2(v)
    end, :class => :undoc_user_option}}
  end

  def default(:client) do
    %{default(:common) | :dsa_pass_phrase => %{:default => :undefined, :chk => fn v ->
        check_string(v)
    end, :class => :user_option}, :rsa_pass_phrase => %{:default => :undefined, :chk => fn v ->
        check_string(v)
    end, :class => :user_option}, :ecdsa_pass_phrase => %{:default => :undefined, :chk => fn v ->
        check_string(v)
    end, :class => :user_option}, :silently_accept_hosts => %{:default => false, :chk => fn v ->
        check_silently_accept_hosts(v)
    end, :class => :user_option}, :user_interaction => %{:default => true, :chk => fn v ->
        :erlang.is_boolean(v)
    end, :class => :user_option}, :save_accepted_host => %{:default => true, :chk => fn v ->
        :erlang.is_boolean(v)
    end, :class => :user_option}, :dh_gex_limits => %{:default => {1024, 6144, 8192}, :chk => fn {min, i, max} ->
        :lists.all(&check_pos_integer/1, [min, i, max])
      _ ->
        false
    end, :class => :user_option}, :connect_timeout => %{:default => :infinity, :chk => fn v ->
        check_timeout(v)
    end, :class => :user_option}, :user => %{:default => (env = case :os.type() do
      {:win32, _} ->
        'USERNAME'
      {:unix, _} ->
        'LOGNAME'
    end; case :os.getenv(env) do
      false ->
        case :os.getenv('USER') do
          false ->
            :undefined
          user ->
            user
        end
      user ->
        user
    end), :chk => fn v ->
        check_string(v)
    end, :class => :user_option}, :password => %{:default => :undefined, :chk => fn v ->
        check_string(v)
    end, :class => :user_option}, :quiet_mode => %{:default => false, :chk => fn v ->
        :erlang.is_boolean(v)
    end, :class => :user_option}, :keyboard_interact_fun => %{:default => :undefined, :chk => fn v ->
        check_function3(v)
    end, :class => :undoc_user_option}}
  end

  def default(:common) do
    %{:user_dir => %{:default => false, :chk => fn v ->
        check_string(v) and check_dir(v)
    end, :class => :user_option}, :pref_public_key_algs => %{:default => :undefined, :chk => fn v ->
        check_pref_public_key_algs(v)
    end, :class => :user_option}, :preferred_algorithms => %{:default => :ssh.default_algorithms(), :chk => fn v ->
        check_preferred_algorithms(v)
    end, :class => :user_option}, :modify_algorithms => %{:default => :undefined, :chk => fn v ->
        check_modify_algorithms(v)
    end, :class => :user_option}, :id_string => %{:default => (try do
      {:ok, [_ | _] = vSN} = :application.get_key(:ssh, :vsn)
    'Erlang/' ++ vSN
    catch
      {_, _, _} ->
        ""
    end), :chk => fn :random ->
        {true, {:random, 2, 5}}
      {:random, i1, i2} ->
        check_pos_integer(i1) and check_pos_integer(i2) and i1 <= i2
      v ->
        check_string(v)
    end, :class => :user_option}, :key_cb => %{:default => {:ssh_file, []}, :chk => fn {mod, opts} ->
        is_atom(mod) and is_list(opts)
      mod when is_atom(mod) ->
        {true, {mod, []}}
      _ ->
        false
    end, :class => :user_option}, :profile => %{:default => :default, :chk => fn v ->
        :erlang.is_atom(v)
    end, :class => :user_option}, :idle_time => %{:default => :infinity, :chk => fn v ->
        check_timeout(v)
    end, :class => :user_option}, :disconnectfun => %{:default => fn _ ->
        :void
    end, :chk => fn v ->
        check_function1(v)
    end, :class => :user_option}, :unexpectedfun => %{:default => fn _, _ ->
        :report
    end, :chk => fn v ->
        check_function2(v)
    end, :class => :user_option}, :ssh_msg_debug_fun => %{:default => fn _, _, _, _ ->
        :void
    end, :chk => fn v ->
        check_function4(v)
    end, :class => :user_option}, :rekey_limit => %{:default => {3600000, 1024000000}, :chk => fn {:infinity, :infinity} ->
        true
      {mins, :infinity} when is_integer(mins) and mins > 0 ->
        {true, {mins * 60 * 1000, :infinity}}
      {:infinity, bytes} when is_integer(bytes) and bytes >= 0 ->
        true
      {mins, bytes} when is_integer(mins) and mins > 0 and is_integer(bytes) and bytes >= 0 ->
        {true, {mins * 60 * 1000, bytes}}
      :infinity ->
        {true, {3600000, :infinity}}
      bytes when is_integer(bytes) and bytes >= 0 ->
        {true, {3600000, bytes}}
      _ ->
        false
    end, :class => :user_option}, :auth_methods => %{:default => 'publickey,keyboard-interactive,password', :chk => fn as ->
        try do
          sup = :string.tokens('publickey,keyboard-interactive,password', ',')
        new = :string.tokens(as, ',')
        [] == (for x <- new, not :lists.member(x, sup) do
          x
        end)
        catch
          {_, _, _} ->
            false
        end
    end, :class => :user_option}, :send_ext_info => %{:default => true, :chk => &:erlang.is_boolean/1, :class => :user_option}, :recv_ext_info => %{:default => true, :chk => &:erlang.is_boolean/1, :class => :user_option}, :transport => %{:default => {:tcp, :gen_tcp, :tcp_closed}, :chk => fn {a, b, c} ->
        is_atom(a) and is_atom(b) and is_atom(c)
    end, :class => :undoc_user_option}, :vsn => %{:default => {2, 0}, :chk => fn {maj, min} ->
        check_non_neg_integer(maj) and check_non_neg_integer(min)
      _ ->
        false
    end, :class => :undoc_user_option}, :tstflg => %{:default => [], :chk => fn v ->
        :erlang.is_list(v)
    end, :class => :undoc_user_option}, :user_dir_fun => %{:default => :undefined, :chk => fn v ->
        check_function1(v)
    end, :class => :undoc_user_option}, :max_random_length_padding => %{:default => 15, :chk => fn v ->
        check_non_neg_integer(v)
    end, :class => :undoc_user_option}}
  end

  @spec delete_key(option_class(), option_key(), private_options(), atom(), non_neg_integer()) :: private_options()
  def delete_key(:user_options, key, opts, _CallerMod, _CallerLine) when is_map(opts) do
    cond do
      is_list(key) ->
        :lists.foldl(&:maps.remove/2, opts, key)
      true ->
        :maps.remove(key, opts)
    end
  end

  def delete_key(class, key, opts, _CallerMod, _CallerLine) when is_map(opts) and class == :socket_options or class == :internal_options do
    %{opts | class => (cond do
      is_list(key) ->
        :lists.foldl(&:maps.remove/2, :maps.get(class, opts), key)
      true ->
        :maps.remove(key, :maps.get(class, opts))
    end)}
  end

  @spec get_value(option_class(), option_key(), private_options(), atom(), non_neg_integer()) :: (any() | no_return())
  def get_value(class, key, opts, _CallerMod, _CallerLine) when is_map(opts) do
    case class do
      :internal_options ->
        :maps.get(key, :maps.get(:internal_options, opts))
      :socket_options ->
        :proplists.get_value(key, :maps.get(:socket_options, opts))
      :user_options ->
        :maps.get(key, opts)
    end
  end

  def get_value(class, key, opts, _CallerMod, _CallerLine), do: error({:bad_options, class, key, opts, _CallerMod, _CallerLine})

  @spec get_value(option_class(), option_key(), private_options(), (() -> any()), atom(), non_neg_integer()) :: (any() | no_return())
  def get_value(:socket_options, key, opts, defFun, _CallerMod, _CallerLine) when is_map(opts), do: :proplists.get_value(key, :maps.get(:socket_options, opts), defFun)

  def get_value(class, key, opts, defFun, callerMod, callerLine) when is_map(opts) do
    try do
      get_value(class, key, opts, callerMod, callerLine)
    catch
      {:error, {:badkey, ^key}, _} ->
        defFun.()
    else
      :undefined ->
        defFun.()
      value ->
        value
    end
  end

  def get_value(class, key, opts, _DefFun, _CallerMod, _CallerLine), do: error({:bad_options, class, key, opts, _CallerMod, _CallerLine})

  @spec handle_options(role(), (client_options() | daemon_options())) :: (private_options() | error())
  def handle_options(role, propList0), do: handle_options(role, propList0, %{:socket_options => [], :internal_options => %{}, :key_cb_options => []})

  def initial_default_algorithms(defList, modList) do
    {true, l0} = check_modify_algorithms(modList)
    rm_non_supported(false, eval_ops(defList, l0))
  end

  @spec keep_set_options((:client | :server), %{}) :: %{}
  def keep_set_options(type, opts) do
    defs = default(type)
    :maps.filter(fn key, value ->
        try do
          %{:default => defVal} = :maps.get(key, defs)
        ^defVal !== ^value
        catch
          {_, _, _} ->
            false
        end
    end, opts)
  end

  @spec keep_user_options((:client | :server), %{}) :: %{}
  def keep_user_options(type, opts) do
    defs = default(type)
    :maps.filter(fn key, _Value ->
        try do
          %{:class => class} = :maps.get(key, defs)
        ^class == :user_option
        catch
          {_, _, _} ->
            false
        end
    end, opts)
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  def no_sensitive(:rm, %{:id_string => _, :tstflg => _}), do: :"*** removed ***"

  def no_sensitive(:filter, opts = %{:id_string => _, :tstflg => _}) do
    sensitive = [:password, :user_passwords, :dsa_pass_phrase, :rsa_pass_phrase, :ecdsa_pass_phrase, :ed25519_pass_phrase, :ed448_pass_phrase]
    :maps.fold(fn k, _V, acc ->
        case :lists.member(k, sensitive) do
          true ->
            %{acc | k => :"***"}
          false ->
            acc
        end
    end, opts, opts)
  end

  def no_sensitive(type, l) when is_list(l) do
    for e <- l do
      no_sensitive(type, e)
    end
  end

  def no_sensitive(type, t) when is_tuple(t), do: list_to_tuple(no_sensitive(type, tuple_to_list(t)))

  def no_sensitive(_, x), do: x

  @spec put_value(option_class(), option_in(), private_options(), atom(), non_neg_integer()) :: private_options()
  def put_value(:user_options, keyVal, opts, _CallerMod, _CallerLine) when is_map(opts), do: put_user_value(keyVal, opts)

  def put_value(:internal_options, keyVal, opts, _CallerMod, _CallerLine) when is_map(opts) do
    internalOpts = :maps.get(:internal_options, opts)
    %{opts | :internal_options => put_internal_value(keyVal, internalOpts)}
  end

  def put_value(:socket_options, keyVal, opts, _CallerMod, _CallerLine) when is_map(opts) do
    socketOpts = :maps.get(:socket_options, opts)
    %{opts | :socket_options => put_socket_value(keyVal, socketOpts)}
  end

  # Private Functions

  defp unquote(:"-check_dh_gex_groups/1-after$^0/0-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-check_dh_gex_groups/1-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-check_dh_gex_groups/1-fun-2-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-check_fun/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-check_input_ok/1-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-check_modify_algorithms/1-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-check_modify_algorithms/1-lc$^1/1-1-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-check_pref_public_key_algs/1-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-check_pref_public_key_algs/1-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-collect_per_size/1-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-1-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-10-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-11-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-12-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-13-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-14-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-15-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-16-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-18-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-19-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-2-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-20-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-21-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-22-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-23-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-24-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-25-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-26-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-27-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-28-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-29-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-3-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-30-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-31-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-32-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-33-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-35-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-36-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-37-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-38-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-39-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-4-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-40-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-41-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-42-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-43-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-44-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-45-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-46-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-47-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-48-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-49-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-5-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-50-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-51-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-52-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-53-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-54-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-55-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-57-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-58-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-59-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-6-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-60-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-61-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-62-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-7-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-8-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-fun-9-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-default/1-lc$^56/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-fun.check_pos_integer/1-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-fun.check_timeout/1-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-fun.eval_op/2-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-fun.put_internal_value/2-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-fun.put_user_value/2-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-handle_options/3-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-handle_options/3-fun-2-")(arg1, arg2, arg3, arg4, arg5) do
    # body not decompiled
  end

  defp unquote(:"-handle_options/3-fun-3-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-handle_options/3-lc$^1/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-keep_set_options/2-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-keep_user_options/2-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-nml/2-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-no_sensitive/2-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-no_sensitive/2-lc$^1/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-rm_non_supported/2-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-rmns/3-lc$^0/1-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-valid_hash/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp check_dh_gex_groups({:file, file}) when is_list(file) do
    case :file.consult(file) do
      {:ok, groupDefs} ->
        check_dh_gex_groups(groupDefs)
      {:error, error} ->
        error_in_check({:file, file}, error)
    end
  end

  defp check_dh_gex_groups({:ssh_moduli_file, file}) when is_list(file) do
    case :file.open(file, [:read]) do
      {:ok, d} ->
        try do
          read_moduli_file(d, 1, [])
        catch
          {_, _, _} ->
            error_in_check({:ssh_moduli_file, file}, 'Bad format in file ' ++ file)
        else
          {:ok, moduli} ->
            check_dh_gex_groups(moduli)
          {:error, error} ->
            error_in_check({:ssh_moduli_file, file}, error)
        end
      {:error, error} ->
        error_in_check({:ssh_moduli_file, file}, error)
    end
  end

  defp check_dh_gex_groups(l0) when is_list(l0) and is_tuple(hd(l0)) do
    {true, collect_per_size(:lists.foldl(fn {n, g, p}, acc when is_integer(n) and n > 0 and is_integer(g) and g > 0 and is_integer(p) and p > 0 ->
        [{n, {g, p}} | acc]
      {n, {g, p}}, acc when is_integer(n) and n > 0 and is_integer(g) and g > 0 and is_integer(p) and p > 0 ->
        [{n, {g, p}} | acc]
      {n, gPs}, acc when is_list(gPs) ->
        :lists.foldr(fn {gi, pi}, acci when is_integer(gi) and gi > 0 and is_integer(pi) and pi > 0 ->
            [{n, {gi, pi}} | acci]
        end, acc, gPs)
    end, [], l0))}
  end

  defp check_dh_gex_groups(_), do: false

  defp check_dir(dir) do
    case :file.read_file_info(dir) do
      {:ok, file_info(type: :directory, access: access)} ->
        case access do
          :read ->
            true
          :read_write ->
            true
          _ ->
            error_in_check(dir, :eacces)
        end
      {:ok, file_info()} ->
        error_in_check(dir, :enotdir)
      {:error, error} ->
        error_in_check(dir, error)
    end
  end

  defp check_fun(key, defs) do
    case :ssh_connection_handler.prohibited_sock_option(key) do
      false ->
        %{:chk => fun} = :maps.get(key, defs)
        ^fun
      true ->
        fn _, _ ->
            :forbidden
        end
    end
  end

  defp check_function1(f), do: is_function(f, 1)

  defp check_function2(f), do: is_function(f, 2)

  defp check_function3(f), do: is_function(f, 3)

  defp check_function4(f), do: is_function(f, 4)

  defp check_input_ok(algs) do
    for kVs <- algs, not is_tuple(kVs) or size(kVs) !== 2 do
      error_in_check(kVs, 'Bad preferred_algorithms')
    end
  end

  defp check_modify_algorithms(m) when is_list(m) do
    for op_KVs <- m, not is_tuple(op_KVs) or size(op_KVs) !== 2 or not :lists.member(element(1, op_KVs), [:append, :prepend, :rm]) do
      error_in_check(op_KVs, 'Bad modify_algorithms')
    end
    {true, for {op, kVs} <- m do
      {op, normalize_mod_algs(kVs, false)}
    end}
  end

  defp check_modify_algorithms(_), do: error_in_check(:modify_algorithms, 'Bad option value. List expected.')

  defp check_non_neg_integer(i), do: is_integer(i) and i >= 0

  defp check_pos_integer(i), do: is_integer(i) and i > 0

  defp check_pref_public_key_algs(v) do
    pKs = :ssh_transport.supported_algorithms(:public_key)
    cHK = fn a, ack ->
        case :lists.member(a, pKs) do
          true ->
            case :lists.member(a, ack) do
              false ->
                [a | ack]
              true ->
                ack
            end
          false ->
            error_in_check(a, 'Not supported public key')
        end
    end
    case :lists.foldr(fn :ssh_dsa, ack ->
        cHK.(:"ssh-dss", ack)
      :ssh_rsa, ack ->
        cHK.(:"ssh-rsa", ack)
      x, ack ->
        cHK.(x, ack)
    end, [], v) do
      v ->
        true
      [] ->
        false
      v1 ->
        {true, v1}
    end
  end

  defp check_silently_accept_hosts(b) when is_boolean(b), do: true

  defp check_silently_accept_hosts(f) when is_function(f, 2), do: true

  defp check_silently_accept_hosts({false, s}) when is_atom(s), do: valid_hash(s)

  defp check_silently_accept_hosts({s, f}) when is_function(f, 2), do: valid_hash(s)

  defp check_silently_accept_hosts(_), do: false

  defp check_string(s), do: is_list(s)

  defp check_timeout(:infinity), do: true

  defp check_timeout(i), do: check_pos_integer(i)

  defp cnf_key(:server), do: :server_options

  defp cnf_key(:client), do: :client_options

  defp collect_per_size(l) do
    :lists.foldr(fn {sz, gP}, [{^sz, gPs} | acc] ->
        [{sz, [gP | gPs]} | acc]
      {sz, gP}, acc ->
        [{sz, [gP]} | acc]
    end, [], :lists.sort(l))
  end

  defp config_val(:modify_algorithms = key, roleCnfs, opts) do
    v = (case :application.get_env(:ssh, key) do
      {:ok, v0} ->
        v0
      _ ->
        []
    end) ++ :proplists.get_value(key, roleCnfs, []) ++ :proplists.get_value(key, opts, [])
    case v do
      [] ->
        :undefined
      _ ->
        {:append, v}
    end
  end

  defp config_val(key, roleCnfs, opts) do
    case :lists.keysearch(key, 1, opts) do
      {:value, {_, v}} ->
        {:ok, v}
      false ->
        case :lists.keysearch(key, 1, roleCnfs) do
          {:value, {_, v}} ->
            {:ok, v}
          false ->
            :application.get_env(:ssh, key)
        end
    end
  end

  defp def_alg(k, false) do
    case :ssh_transport.algo_two_spec_class(k) do
      false ->
        []
      true ->
        [{:client2server, []}, {:server2client, []}]
    end
  end

  defp def_alg(k, true), do: :ssh_transport.default_algorithms(k)

  defp error_if_empty([{k, []} | _]), do: error({:eoptions, k, 'Empty resulting algorithm list'})

  defp error_if_empty([{k, [{:client2server, []}, {:server2client, []}]}]), do: error({:eoptions, k, 'Empty resulting algorithm list'})

  defp error_if_empty([{k, [{:client2server, []} | _]} | _]), do: error({:eoptions, {k, :client2server}, 'Empty resulting algorithm list'})

  defp error_if_empty([{k, [_, {:server2client, []} | _]} | _]), do: error({:eoptions, {k, :server2client}, 'Empty resulting algorithm list'})

  defp error_if_empty([_ | t]), do: error_if_empty(t)

  defp error_if_empty([]), do: :ok

  defp error_in_check(badValue, extra), do: error({:check, {badValue, extra}})

  defp eval_op({op, algKVs}, prefAlgs), do: eval_op(op, algKVs, prefAlgs, [])

  defp eval_op(op, [{c, l1} | t1], [{^c, l2} | t2], acc), do: eval_op(op, t1, t2, [{c, eval_op(op, l1, l2, [])} | acc])

  defp eval_op(_, [], [], acc), do: :lists.reverse(acc)

  defp eval_op(:rm, opt, pref, []) when is_list(opt) and is_list(pref), do: pref -- opt

  defp eval_op(:append, opt, pref, []) when is_list(opt) and is_list(pref), do: pref -- opt ++ opt

  defp eval_op(:prepend, opt, pref, []) when is_list(opt) and is_list(pref), do: opt ++ pref -- opt

  defp eval_ops(prefAlgs, modAlgs), do: :lists.foldl(&eval_op/2, prefAlgs, modAlgs)

  defp final_preferred_algorithms(options0) do
    result = case :ssh_options.get_value(:user_options, :modify_algorithms, options0, :ssh_options, 1181) do
      :undefined ->
        rm_non_supported(true, :ssh_options.get_value(:user_options, :preferred_algorithms, options0, :ssh_options, 1184))
      modAlgs ->
        rm_non_supported(false, eval_ops(:ssh_options.get_value(:user_options, :preferred_algorithms, options0, :ssh_options, 1187), modAlgs))
    end
    error_if_empty(result)
    options1 = :ssh_options.put_value(:user_options, {:preferred_algorithms, result}, options0, :ssh_options, 1191)
    case :ssh_options.get_value(:user_options, :pref_public_key_algs, options1, :ssh_options, 1192) do
      :undefined ->
        :ssh_options.put_value(:user_options, {:pref_public_key_algs, :proplists.get_value(:public_key, result)}, options1, :ssh_options, 1194)
      _ ->
        options1
    end
  end

  defp handle_options(role, optsList0, opts0) when is_map(opts0) and is_list(optsList0) do
    optsList1 = :proplists.unfold(:lists.foldr(fn t, acc when is_tuple(t) and size(t) !== 2 ->
        [{:special_trpt_args, t} | acc]
      x, acc ->
        [x | acc]
    end, [], optsList0))
    try do
      optionDefinitions = default(role)
    roleCnfs = :application.get_env(:ssh, cnf_key(role), [])
    {initialMap, optsList2} = :maps.fold(fn k, %{:default => vd}, {m, pL} ->
        case config_val(k, roleCnfs, optsList1) do
          {:ok, v1} ->
            {%{m | k => v1, :key_cb_options => [{k, v1} | :maps.get(:key_cb_options, m)]}, [{k, v1} | pL]}
          {:append, v1} ->
            newVal = :maps.get(k, m, []) ++ v1
            {%{m | k => newVal, :key_cb_options => [{k, newVal} | :lists.keydelete(k, 1, :maps.get(:key_cb_options, m))]}, [{k, newVal} | :lists.keydelete(k, 1, pL)]}
          :undefined ->
            {%{m | k => vd}, pL}
        end
    end, {%{opts0 | :key_cb_options => :maps.get(:key_cb_options, opts0)}, for {k, v} <- optsList1, not :maps.is_key(k, opts0) do
      {k, v}
    end}, optionDefinitions)
    final_preferred_algorithms(:lists.foldl(fn kV, vals ->
        save(kV, optionDefinitions, vals)
    end, initialMap, optsList2))
    catch
      {:error, {eO, kV, reason}, _} when eO == :eoptions or eO == :eerl_env ->
        cond do
          reason == :undefined ->
            {:error, {eO, kV}}
          is_list(reason) ->
            {:error, {eO, {kV, :lists.flatten(reason)}}}
          true ->
            {:error, {eO, {kV, reason}}}
        end
    end
  end

  defp nml(k, l) do
    for v <- l, not is_atom(v) do
      error_in_check(k, 'Bad value for this key')
    end
    case l -- :lists.usort(l) do
      [] ->
        :ok
      dups ->
        error_in_check({k, dups}, 'Duplicates')
    end
    l
  end

  defp nml1(k, {t, v}) when t == :client2server or t == :server2client, do: {t, nml({k, t}, v)}

  defp normalize_mod_alg_list(k, vs, useDefaultAlgs), do: normalize_mod_alg_list(k, :ssh_transport.algo_two_spec_class(k), vs, def_alg(k, useDefaultAlgs))

  defp normalize_mod_alg_list(_K, _, [], default), do: default

  defp normalize_mod_alg_list(k, true, [{:client2server, l1}], [_, {:server2client, l2}]), do: [nml1(k, {:client2server, l1}), {:server2client, l2}]

  defp normalize_mod_alg_list(k, true, [{:server2client, l2}], [{:client2server, l1}, _]), do: [{:client2server, l1}, nml1(k, {:server2client, l2})]

  defp normalize_mod_alg_list(k, true, [{:server2client, l2}, {:client2server, l1}], _), do: [nml1(k, {:client2server, l1}), nml1(k, {:server2client, l2})]

  defp normalize_mod_alg_list(k, true, [{:client2server, l1}, {:server2client, l2}], _), do: [nml1(k, {:client2server, l1}), nml1(k, {:server2client, l2})]

  defp normalize_mod_alg_list(k, true, l0, _) do
    l = nml(k, l0)
    [{:client2server, l}, {:server2client, l}]
  end

  defp normalize_mod_alg_list(k, false, l, _), do: nml(k, l)

  defp normalize_mod_algs(kVs, useDefaultAlgs), do: normalize_mod_algs(:ssh_transport.algo_classes(), kVs, [], useDefaultAlgs)

  defp normalize_mod_algs([k | ks], kVs0, acc, useDefaultAlgs) do
    {vs1, kVs} = case :lists.keytake(k, 1, kVs0) do
      {:value, {k, vs0}, kVs1} ->
        {vs0, kVs1}
      false ->
        {[], kVs0}
    end
    vs = normalize_mod_alg_list(k, vs1, useDefaultAlgs)
    normalize_mod_algs(ks, kVs, [{k, vs} | acc], useDefaultAlgs)
  end

  defp normalize_mod_algs([], [], acc, _), do: :lists.reverse(acc)

  defp normalize_mod_algs([], [{k, _} | _], _, _) do
    case :ssh_transport.algo_class(k) do
      true ->
        error_in_check(k, 'Duplicate key')
      false ->
        error_in_check(k, 'Unknown key')
    end
  end

  defp normalize_mod_algs([], [x | _], _, _), do: error_in_check(x, 'Bad list element')

  defp put_internal_value(l, intOpts) when is_list(l), do: :lists.foldl(&put_internal_value/2, intOpts, l)

  defp put_internal_value({key, value}, intOpts), do: %{intOpts | key => value}

  defp put_socket_value(l, sockOpts) when is_list(l), do: l ++ sockOpts

  defp put_socket_value({key, value}, sockOpts), do: [{key, value} | sockOpts]

  defp put_socket_value(a, sockOpts) when is_atom(a), do: [a | sockOpts]

  defp put_user_value(l, opts) when is_list(l), do: :lists.foldl(&put_user_value/2, opts, l)

  defp put_user_value({key, value}, opts), do: %{opts | key => value}

  defp read_moduli_file(d, i, acc) do
    case :io.get_line(d, "") do
      {:error, error} ->
        {:error, error}
      :eof ->
        {:ok, acc}
      '#' ++ _ ->
        read_moduli_file(d, i + 1, acc)
      <<"#", _ :: binary>> ->
        read_moduli_file(d, i + 1, acc)
      data ->
        line = cond do
          is_binary(data) ->
            binary_to_list(data)
          is_list(data) ->
            data
        end
        try do
          [_Time, _Class, _Tests, _Tries, size, g, p] = :string.tokens(line, ' \r\n')
        m = {list_to_integer(size), {list_to_integer(g), list_to_integer(p, 16)}}
        read_moduli_file(d, i + 1, [m | acc])
        catch
          {_, _, _} ->
            read_moduli_file(d, i + 1, acc)
        end
    end
  end

  defp rm_non_supported(unsupIsErrorFlg, kVs) do
    for {k, vs} <- kVs do
      {k, rmns(k, vs, unsupIsErrorFlg)}
    end
  end

  defp rm_unsup(a, b, flg, errInf) do
    case a -- b do
      unsup = [_ | _] when flg == true ->
        error({:eoptions, {:preferred_algorithms, {errInf, unsup}}, 'Unsupported value(s) found'})
      unsup ->
        a -- unsup
    end
  end

  defp rmns(k, vs, unsupIsErrorFlg) do
    case :ssh_transport.algo_two_spec_class(k) do
      false ->
        rm_unsup(vs, :ssh_transport.supported_algorithms(k), unsupIsErrorFlg, k)
      true ->
        for {{c, vsx}, {c, sup}} <- :lists.zip(vs, :ssh_transport.supported_algorithms(k)) do
          {c, rm_unsup(vsx, sup, unsupIsErrorFlg, {k, c})}
        end
    end
  end

  defp save({:allow_user_interaction, v}, opts, vals), do: save({:user_interaction, v}, opts, vals)

  defp save(inet, defs, optMap) when inet == :inet or inet == :inet6, do: save({:inet, inet}, defs, optMap)

  defp save({inet, true}, defs, optMap) when inet == :inet or inet == :inet6, do: save({:inet, inet}, defs, optMap)

  defp save({inet, false}, _Defs, optMap) when inet == :inet or inet == :inet6, do: optMap

  defp save({:special_trpt_args, t}, _Defs, optMap) when is_map(optMap), do: %{optMap | :socket_options => [t | :maps.get(:socket_options, optMap)]}

  defp save({key, value}, defs, optMap) when is_map(optMap) do
    try do
      check_fun(key, defs)(value)
    catch
      {:error, {:badkey, :inet}, _} ->
        %{optMap | :socket_options => [value | :maps.get(:socket_options, optMap)]}
      {:error, {:badkey, ^key}, _} ->
        %{optMap | :socket_options => [{key, value} | :maps.get(:socket_options, optMap)]}
      {:error, {:check, {badValue, extra}}, _} ->
        error({:eoptions, {key, badValue}, extra})
    else
      true ->
        %{optMap | key => value}
      {true, modifiedValue} ->
        %{optMap | key => modifiedValue}
      false ->
        error({:eoptions, {key, value}, 'Bad value'})
      :forbidden ->
        error({:eoptions, {key, value}, :io_lib.format('The option \'~s\' is used internally. The user is not allowed to specify this option.', [key])})
    end
  end

  defp save(opt, _Defs, optMap) when is_map(optMap), do: %{optMap | :socket_options => [opt | :maps.get(:socket_options, optMap)]}

  defp valid_hash(s), do: valid_hash(s, :proplists.get_value(:hashs, :crypto.supports()))

  defp valid_hash(s, ss) when is_atom(s), do: :lists.member(s, [:md5, :sha, :sha224, :sha256, :sha384, :sha512]) and :lists.member(s, ss)

  defp valid_hash(l, ss) when is_list(l) do
    :lists.all(fn s ->
        valid_hash(s, ss)
    end, l)
  end

  defp valid_hash(x, _), do: error_in_check(x, 'Expect atom or list in fingerprint spec')
end
