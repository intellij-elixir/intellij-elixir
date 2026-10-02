# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :erlang do

  # Built-in types (not actually declared in :erlang)

  @type any() :: ...
  @type arity() :: ...
  @type as_boolean(type1) :: ...
  @type atom() :: ...
  @type binary() :: ...
  @type bitstring() :: ...
  @type boolean() :: ...
  @type byte() :: ...
  @type char() :: ...
  @type charlist() :: ...
  @type dynamic() :: ...
  @type dynamic(type1) :: ...
  @type float() :: ...
  @type fun() :: ...
  @type function() :: ...
  @type identifier() :: ...
  @type integer() :: ...
  @type iodata() :: ...
  @type iolist() :: ...
  @type keyword() :: ...
  @type keyword(type1) :: ...
  @type list() :: ...
  @type list(type1) :: ...
  @type map() :: ...
  @type maybe_improper_list() :: ...
  @type maybe_improper_list(element :: term(), tail :: term()) :: ...
  @type mfa() :: ...
  @type module() :: ...
  @type neg_integer() :: ...
  @type no_return() :: ...
  @type node() :: ...
  @type non_empty_list() :: ...
  @type non_empty_list(element :: term()) :: ...
  @type non_neg_integer() :: ...
  @type none() :: ...
  @type nonempty_charlist() :: ...
  @type nonempty_improper_list() :: ...
  @type nonempty_improper_list(element :: term(), tail :: term()) :: ...
  @type nonempty_maybe_improper_list(element :: term(), tail :: term()) :: ...
  @type number() :: ...
  @type pid() :: ...
  @type port() :: ...
  @type pos_integer() :: ...
  @type reference() :: ...
  @type struct() :: ...
  @type term() :: ...
  @type timeout() :: ...
  @type tuple() :: ...

  # Types

  @type deprecated_time_unit :: (:seconds | :milli_seconds | :micro_seconds | :nano_seconds)

  @type iovec :: [binary()]

  @type max_heap_size :: size :: (non_neg_integer() | %{optional(:size) => non_neg_integer(), optional(:kill) => boolean(), optional(:error_logger) => boolean()})

  @type message_queue_data :: (:off_heap | :on_heap)

  @type priority_level :: (:low | :normal | :high | :max)

  @type spawn_opt_option :: (:link | :monitor | {:priority, level :: priority_level()} | {:fullsweep_after, number :: non_neg_integer()} | {:min_heap_size, size :: non_neg_integer()} | {:min_bin_vheap_size, vSize :: non_neg_integer()} | {:max_heap_size, size :: max_heap_size()} | {:message_queue_data, mQD :: message_queue_data()})

  @type time_unit :: (pos_integer() | :second | :millisecond | :microsecond | :nanosecond | :native | :perf_counter | deprecated_time_unit())

  @type timestamp :: {megaSecs :: non_neg_integer(), secs :: non_neg_integer(), microSecs :: non_neg_integer()}

  # Private Types

  @typep bitstring_list :: maybe_improper_list((byte() | bitstring() | bitstring_list()), (bitstring() | []))

  @typep cpu_topology :: ([levelEntry :: level_entry()] | :undefined)

  @typep dst :: (pid() | port() | regName :: atom() | {regName :: atom(), node :: node()})

  @typep ext_binary :: binary()

  @typep ext_iovec :: iovec()

  @typep fun_info_item :: (:arity | :env | :index | :name | :module | :new_index | :new_uniq | :pid | :type | :uniq)

  @typep info_list :: []

  @typep level_entry :: ({levelTag :: level_tag(), subLevel :: sub_level()} | {levelTag :: level_tag(), infoList :: info_list(), subLevel :: sub_level()})

  @typep level_tag :: (:core | :node | :processor | :thread)

  @typep match_variable :: atom()

  @typep memory_type :: (:total | :processes | :processes_used | :system | :atom | :atom_used | :binary | :code | :ets)

  @typep module_info_key :: (:attributes | :compile | :exports | :functions | :md5 | :module | :native | :native_addresses | :nifs)

  @typep monitor_port_identifier :: (port() | registered_name())

  @typep monitor_process_identifier :: (pid() | registered_process_identifier())

  @typep process_info_item :: (:backtrace | :binary | :catchlevel | :current_function | :current_location | :current_stacktrace | :dictionary | :error_handler | :garbage_collection | :garbage_collection_info | :group_leader | :heap_size | :initial_call | :links | :last_calls | :memory | :message_queue_len | :messages | :min_heap_size | :min_bin_vheap_size | :monitored_by | :monitors | :message_queue_data | :priority | :reductions | :registered_name | :sequential_trace_token | :stack_size | :status | :suspending | :total_heap_size | :trace | :trap_exit)

  @typep process_info_result_item :: ({:backtrace, bin :: binary()} | {:binary, binInfo :: [{non_neg_integer(), non_neg_integer(), non_neg_integer()}]} | {:catchlevel, catchLevel :: non_neg_integer()} | {:current_function, ({module :: module(), function :: atom(), arity :: arity()} | :undefined)} | {:current_location, {module :: module(), function :: atom(), arity :: arity(), location :: [({:file, filename :: charlist()} | {:line, line :: pos_integer()})]}} | {:current_stacktrace, stack :: [stack_item()]} | {:dictionary, dictionary :: [{key :: term(), value :: term()}]} | {:error_handler, module :: module()} | {:garbage_collection, gCInfo :: [{atom(), non_neg_integer()}]} | {:garbage_collection_info, gCInfo :: [{atom(), non_neg_integer()}]} | {:group_leader, groupLeader :: pid()} | {:heap_size, size :: non_neg_integer()} | {:initial_call, mfa()} | {:links, pidsAndPorts :: [(pid() | port())]} | {:last_calls, (false | calls :: [mfa()])} | {:memory, size :: non_neg_integer()} | {:message_queue_len, messageQueueLen :: non_neg_integer()} | {:messages, messageQueue :: [term()]} | {:min_heap_size, minHeapSize :: non_neg_integer()} | {:min_bin_vheap_size, minBinVHeapSize :: non_neg_integer()} | {:max_heap_size, maxHeapSize :: max_heap_size()} | {:monitored_by, monitoredBy :: [(pid() | port() | nif_resource())]} | {:monitors, monitors :: [{(:process | :port), pid :: (pid() | port() | {regName :: atom(), node :: node()})}]} | {:message_queue_data, mQD :: message_queue_data()} | {:priority, level :: priority_level()} | {:reductions, number :: non_neg_integer()} | {:registered_name, ([] | atom :: atom())} | {:sequential_trace_token, ([] | sequentialTraceToken :: term())} | {:stack_size, size :: non_neg_integer()} | {:status, status :: (:exiting | :garbage_collecting | :waiting | :running | :runnable | :suspended)} | {:suspending, suspendeeList :: [{suspendee :: pid(), activeSuspendCount :: non_neg_integer(), outstandingSuspendCount :: non_neg_integer()}]} | {:total_heap_size, size :: non_neg_integer()} | {:trace, internalTraceFlags :: non_neg_integer()} | {:trap_exit, boolean :: boolean()})

  @typep raise_stacktrace :: ([({module(), atom(), (arity() | [term()])} | {function(), [term()]})] | [({module(), atom(), (arity() | [term()]), [{atom(), term()}]} | {function(), [term()], [{atom(), term()}]})])

  @typep registered_name :: atom()

  @typep registered_process_identifier :: (registered_name() | {registered_name(), node()})

  @typep scheduler_bind_type :: (:no_node_processor_spread | :no_node_thread_spread | :no_spread | :processor_spread | :spread | :thread_spread | :thread_no_node_processor_spread | :unbound)

  @typep seq_trace_info :: (:send | :receive | :print | :timestamp | :monotonic_timestamp | :strict_monotonic_timestamp | :label | :serial)

  @typep seq_trace_info_returns :: ({seq_trace_info(), (non_neg_integer() | boolean() | {non_neg_integer(), non_neg_integer()})} | [])

  @typep stack_item :: {module :: module(), function :: atom(), arity :: (arity() | args :: [term()]), location :: [({:file, filename :: charlist()} | {:line, line :: pos_integer()})]}

  @typep sub_level :: ([levelEntry :: level_entry()] | logicalCpuId :: {:logical, non_neg_integer()})

  @typep system_monitor_option :: (:busy_port | :busy_dist_port | {:long_gc, non_neg_integer()} | {:long_schedule, non_neg_integer()} | {:large_heap, non_neg_integer()})

  @typep system_profile_option :: (:exclusive | :runnable_ports | :runnable_procs | :scheduler | :timestamp | :monotonic_timestamp | :strict_monotonic_timestamp)

  @typep trace_flag :: (:all | :send | :receive | :procs | :ports | :call | :arity | :return_to | :silent | :running | :exiting | :running_procs | :running_ports | :garbage_collection | :timestamp | :cpu_timestamp | :monotonic_timestamp | :strict_monotonic_timestamp | :set_on_spawn | :set_on_first_spawn | :set_on_link | :set_on_first_link | {:tracer, (pid() | port())} | {:tracer, module(), term()})

  @typep trace_info_flag :: (:send | :receive | :set_on_spawn | :call | :return_to | :procs | :set_on_first_spawn | :set_on_link | :running | :garbage_collection | :timestamp | :monotonic_timestamp | :strict_monotonic_timestamp | :arity)

  @typep trace_info_item_result :: ({:traced, (:global | :local | false | :undefined)} | {:match_spec, (trace_match_spec() | false | :undefined)} | {:meta, (pid() | port() | false | :undefined | [])} | {:meta, module(), term()} | {:meta_match_spec, (trace_match_spec() | false | :undefined)} | {:call_count, (non_neg_integer() | boolean() | :undefined)} | {:call_time, ([{pid(), non_neg_integer(), non_neg_integer(), non_neg_integer()}] | boolean() | :undefined)})

  @typep trace_info_return :: (:undefined | {:flags, [trace_info_flag()]} | {:tracer, (pid() | port() | [])} | {:tracer, module(), term()} | trace_info_item_result() | {:all, ([trace_info_item_result()] | false | :undefined)})

  @typep trace_match_spec :: [{([term()] | :_ | match_variable()), [term()], [term()]}]

  @typep trace_pattern_flag :: (:global | :local | :meta | {:meta, pid :: pid()} | {:meta, tracerModule :: module(), tracerState :: term()} | :call_count | :call_time)

  @typep trace_pattern_mfa :: ({atom(), atom(), (arity() | :_)} | :on_load)

  # Functions

  def unquote(:!)(_Dst, _Msg), do: :erlang.nif_error(:undefined)

  def _A * _B, do: :erlang.nif_error(:undefined)

  def (+_A), do: :erlang.nif_error(:undefined)

  def _A + _B, do: :erlang.nif_error(:undefined)

  def _A ++ _B, do: :erlang.nif_error(:undefined)

  def (-_A), do: :erlang.nif_error(:undefined)

  def _A - _B, do: :erlang.nif_error(:undefined)

  def _A -- _B, do: :erlang.nif_error(:undefined)

  def _A / _B, do: :erlang.nif_error(:undefined)

  def unquote(:"/=")(_A, _B), do: :erlang.nif_error(:undefined)

  def _A < _B, do: :erlang.nif_error(:undefined)

  def unquote(:"=/=")(_A, _B), do: :erlang.nif_error(:undefined)

  def unquote(:"=:=")(_A, _B), do: :erlang.nif_error(:undefined)

  def unquote(:"=<")(_A, _B), do: :erlang.nif_error(:undefined)

  def _A == _B, do: :erlang.nif_error(:undefined)

  def _A > _B, do: :erlang.nif_error(:undefined)

  def _A >= _B, do: :erlang.nif_error(:undefined)

  @spec abs(float) :: float() when float: float()
  @spec abs(int) :: non_neg_integer() when int: integer()
  def abs(_Number), do: :erlang.nif_error(:undefined)

  def adler32(_Data), do: :erlang.nif_error(:undefined)

  def adler32(_OldAdler, _Data), do: :erlang.nif_error(:undefined)

  def adler32_combine(_FirstAdler, _SecondAdler, _SecondSize), do: :erlang.nif_error(:undefined)

  def alloc_info(allocs), do: get_alloc_info(:allocator, allocs)

  def alloc_sizes(allocs), do: get_alloc_info(:allocator_sizes, allocs)

  def _A and _B, do: :erlang.nif_error(:undefined)

  def append(_List, _Tail), do: :erlang.nif_error(:undefined)

  def append_element(_Tuple1, _Term), do: :erlang.nif_error(:undefined)

  @spec apply(fun, args) :: term() when fun: function(), args: [term()]
  def apply(fun, args), do: :erlang.apply(fun, args)

  @spec apply(module, function, args) :: term() when module: module(), function: atom(), args: [term()]
  def apply(mod, name, args), do: :erlang.apply(mod, name, args)

  @spec atom_to_binary(atom) :: binary() when atom: atom()
  def atom_to_binary(atom), do: :erlang.atom_to_binary(atom, :utf8)

  @spec atom_to_binary(atom, encoding) :: binary() when atom: atom(), encoding: (:latin1 | :unicode | :utf8)
  def atom_to_binary(_Atom, _Encoding), do: :erlang.nif_error(:undefined)

  @spec atom_to_list(atom) :: charlist() when atom: atom()
  def atom_to_list(_Atom), do: :erlang.nif_error(:undefined)

  def band(_A, _B), do: :erlang.nif_error(:undefined)

  @spec binary_part(subject, posLen) :: binary() when subject: binary(), posLen: {start :: non_neg_integer(), length :: integer()}
  def binary_part(_Subject, _PosLen), do: :erlang.nif_error(:undefined)

  @spec binary_part(subject, start, length) :: binary() when subject: binary(), start: non_neg_integer(), length: integer()
  def binary_part(_Subject, _Start, _Length), do: :erlang.nif_error(:undefined)

  @spec binary_to_atom(binary) :: atom() when binary: binary()
  def binary_to_atom(binary), do: :erlang.binary_to_atom(binary, :utf8)

  @spec binary_to_atom(binary, encoding) :: atom() when binary: binary(), encoding: (:latin1 | :unicode | :utf8)
  def binary_to_atom(_Binary, _Encoding), do: :erlang.nif_error(:undefined)

  @spec binary_to_existing_atom(binary) :: atom() when binary: binary()
  def binary_to_existing_atom(binary), do: :erlang.binary_to_existing_atom(binary, :utf8)

  @spec binary_to_existing_atom(binary, encoding) :: atom() when binary: binary(), encoding: (:latin1 | :unicode | :utf8)
  def binary_to_existing_atom(_Binary, _Encoding), do: :erlang.nif_error(:undefined)

  @spec binary_to_float(binary) :: float() when binary: binary()
  def binary_to_float(_Binary), do: :erlang.nif_error(:undefined)

  @spec binary_to_integer(binary) :: integer() when binary: binary()
  def binary_to_integer(_Binary), do: :erlang.nif_error(:undefined)

  @spec binary_to_integer(binary, base) :: integer() when binary: binary(), base: 2..36
  def binary_to_integer(_Binary, _Base), do: :erlang.nif_error(:undefined)

  @spec binary_to_list(binary) :: [byte()] when binary: binary()
  def binary_to_list(_Binary), do: :erlang.nif_error(:undefined)

  @spec binary_to_list(binary, start, stop) :: [byte()] when binary: binary(), start: pos_integer(), stop: pos_integer()
  def binary_to_list(_Binary, _Start, _Stop), do: :erlang.nif_error(:undefined)

  @spec binary_to_term(binary) :: term() when binary: ext_binary()
  def binary_to_term(_Binary), do: :erlang.nif_error(:undefined)

  @spec binary_to_term(binary, opts) :: (term() | {term(), used}) when binary: ext_binary(), opt: (:safe | :used), opts: [opt], used: pos_integer()
  def binary_to_term(_Binary, _Opts), do: :erlang.nif_error(:undefined)

  @spec bit_size(bitstring) :: non_neg_integer() when bitstring: bitstring()
  def bit_size(_Bitstring), do: :erlang.nif_error(:undefined)

  @spec bitsize(p1) :: non_neg_integer() when p1: bitstring()
  def bitsize(_P1), do: :erlang.nif_error(:undefined)

  @spec bitstring_to_list(bitstring) :: [(byte() | bitstring())] when bitstring: bitstring()
  def bitstring_to_list(_Bitstring), do: :erlang.nif_error(:undefined)

  def bnot(_A), do: :erlang.nif_error(:undefined)

  def bor(_A, _B), do: :erlang.nif_error(:undefined)

  def bsl(_A, _B), do: :erlang.nif_error(:undefined)

  def bsr(_A, _B), do: :erlang.nif_error(:undefined)

  def bump_reductions(_Reductions), do: :erlang.nif_error(:undefined)

  def bxor(_A, _B), do: :erlang.nif_error(:undefined)

  @spec byte_size(bitstring) :: non_neg_integer() when bitstring: bitstring()
  def byte_size(_Bitstring), do: :erlang.nif_error(:undefined)

  def call_on_load_function(_P1), do: :erlang.nif_error(:undefined)

  def cancel_timer(_TimerRef), do: :erlang.nif_error(:undefined)

  def cancel_timer(_TimerRef, _Options), do: :erlang.nif_error(:undefined)

  @spec ceil(number) :: integer() when number: number()
  def ceil(_), do: :erlang.nif_error(:undef)

  @spec check_old_code(module) :: boolean() when module: module()
  def check_old_code(_Module), do: :erlang.nif_error(:undefined)

  @spec check_process_code(pid, module) :: checkResult when pid: pid(), module: module(), checkResult: boolean()
  def check_process_code(pid, module) do
    try do
      :erts_internal.check_process_code(pid, module, [{:allow_gc, true}])
    catch
      {:error, error, _} ->
        :erlang.error(error, [pid, module])
    end
  end

  @spec check_process_code(pid, module, optionList) :: (checkResult | :async) when pid: pid(), module: module(), requestId: term(), option: ({:async, requestId} | {:allow_gc, boolean()}), optionList: [option], checkResult: (boolean() | :aborted)
  def check_process_code(pid, module, optionList) do
    try do
      :erts_internal.check_process_code(pid, module, optionList)
    catch
      {:error, error, _} ->
        :erlang.error(error, [pid, module, optionList])
    end
  end

  def convert_time_unit(time, fromUnit, toUnit) do
    try do
      fU = case fromUnit do
      :native ->
        :erts_internal.time_unit()
      :perf_counter ->
        :erts_internal.perf_counter_unit()
      :nanosecond ->
        1000 * 1000 * 1000
      :microsecond ->
        1000 * 1000
      :millisecond ->
        1000
      :second ->
        1
      :nano_seconds ->
        1000 * 1000 * 1000
      :micro_seconds ->
        1000 * 1000
      :milli_seconds ->
        1000
      :seconds ->
        1
      _ when fromUnit > 0 ->
        fromUnit
    end
    tU = case toUnit do
      :native ->
        :erts_internal.time_unit()
      :perf_counter ->
        :erts_internal.perf_counter_unit()
      :nanosecond ->
        1000 * 1000 * 1000
      :microsecond ->
        1000 * 1000
      :millisecond ->
        1000
      :second ->
        1
      :nano_seconds ->
        1000 * 1000 * 1000
      :micro_seconds ->
        1000 * 1000
      :milli_seconds ->
        1000
      :seconds ->
        1
      _ when toUnit > 0 ->
        toUnit
    end
    div((case time < 0 do
      true ->
        tU * time - fU - 1
      false ->
        tU * time
    end), fU)
    catch
      {_, _, _} ->
        :erlang.error(:badarg, [time, fromUnit, toUnit])
    end
  end

  def crc32(_Data), do: :erlang.nif_error(:undefined)

  def crc32(_OldCrc, _Data), do: :erlang.nif_error(:undefined)

  def crc32_combine(_FirstCrc, _SecondCrc, _SecondSize), do: :erlang.nif_error(:undefined)

  @spec date() :: date when date: :calendar.date()
  def date(), do: :erlang.nif_error(:undefined)

  def decode_packet(_Type, _Bin, _Options), do: :erlang.nif_error(:undefined)

  def delay_trap(result, 0) do
    :erlang.yield()
    result
  end

  def delay_trap(result, timeout) do
    receive do
    after
      timeout ->
        result
    end
  end

  def delete_element(_Index, _Tuple1), do: :erlang.nif_error(:undefined)

  @spec delete_module(module) :: (true | :undefined) when module: module()
  def delete_module(_Module), do: :erlang.nif_error(:undefined)

  @spec demonitor(monitorRef) :: true when monitorRef: reference()
  def demonitor(_MonitorRef), do: :erlang.nif_error(:undefined)

  @spec demonitor(monitorRef, optionList) :: boolean() when monitorRef: reference(), optionList: [option], option: (:flush | :info)
  def demonitor(_MonitorRef, _OptionList), do: :erlang.nif_error(:undefined)

  @spec disconnect_node(node) :: (boolean() | :ignored) when node: node()
  def disconnect_node(node), do: :net_kernel.disconnect(node)

  def display(_Term), do: :erlang.nif_error(:undefined)

  def display_nl(), do: :erlang.nif_error(:undefined)

  def display_string(_P1), do: :erlang.nif_error(:undefined)

  def dist_ctrl_get_data(_DHandle), do: :erlang.nif_error(:undefined)

  def dist_ctrl_get_data_notification(_DHandle), do: :erlang.nif_error(:undefined)

  def dist_ctrl_get_opt(_DHandle, _Opt), do: :erlang.nif_error(:undefined)

  def dist_ctrl_input_handler(_DHandle, _InputHandler), do: :erlang.nif_error(:undefined)

  def dist_ctrl_put_data(_DHandle, _Data), do: :erlang.nif_error(:undefined)

  def dist_ctrl_set_opt(_DHandle, _Opt, _Val), do: :erlang.nif_error(:undefined)

  def dist_get_stat(_DHandle), do: :erlang.nif_error(:undefined)

  def div(_A, _B), do: :erlang.nif_error(:undefined)

  def dmonitor_node(node, _Flag, []) do
    send(:erlang.self(), {:nodedown, node})
    true
  end

  def dmonitor_node(node, flag, opts) do
    case :lists.member(:allow_passive_connect, opts) do
      true ->
        case :net_kernel.passive_cnct(node) do
          true ->
            :erlang.monitor_node(node, flag, opts)
          false ->
            send(:erlang.self(), {:nodedown, node})
            true
        end
      _ ->
        dmonitor_node(node, flag, [])
    end
  end

  def dt_append_vm_tag_data(_IoData), do: :erlang.nif_error(:undefined)

  def dt_get_tag(), do: :erlang.nif_error(:undefined)

  def dt_get_tag_data(), do: :erlang.nif_error(:undefined)

  def dt_prepend_vm_tag_data(_IoData), do: :erlang.nif_error(:undefined)

  def dt_put_tag(_IoData), do: :erlang.nif_error(:undefined)

  def dt_restore_tag(_TagData), do: :erlang.nif_error(:undefined)

  def dt_spread_tag(_Bool), do: :erlang.nif_error(:undefined)

  @spec element(n, tuple) :: term() when n: pos_integer(), tuple: tuple()
  def element(_N, _Tuple), do: :erlang.nif_error(:undefined)

  @spec erase() :: [{key, val}] when key: term(), val: term()
  def erase(), do: :erlang.nif_error(:undefined)

  @spec erase(key) :: (val | :undefined) when key: term(), val: term()
  def erase(_Key), do: :erlang.nif_error(:undefined)

  @spec error(reason) :: no_return() when reason: term()
  def error(_Reason), do: :erlang.nif_error(:undefined)

  @spec error(reason, args) :: no_return() when reason: term(), args: [term()]
  def error(_Reason, _Args), do: :erlang.nif_error(:undefined)

  @spec exit(reason) :: no_return() when reason: term()
  def exit(_Reason), do: :erlang.nif_error(:undefined)

  @spec exit(pid, reason) :: true when pid: (pid() | port()), reason: term()
  def exit(_Pid, _Reason), do: :erlang.nif_error(:undefined)

  def exit_signal(_Pid, _Reason), do: :erlang.nif_error(:undefined)

  def external_size(_Term), do: :erlang.nif_error(:undefined)

  def external_size(_Term, _Options), do: :erlang.nif_error(:undefined)

  def finish_after_on_load(_P1, _P2), do: :erlang.nif_error(:undefined)

  def finish_loading(_List), do: :erlang.nif_error(:undefined)

  @spec float(number) :: float() when number: number()
  def float(_Number), do: :erlang.nif_error(:undefined)

  @spec float_to_binary(float) :: binary() when float: float()
  def float_to_binary(_Float), do: :erlang.nif_error(:undefined)

  @spec float_to_binary(float, options) :: binary() when float: float(), options: [option], option: ({:decimals, decimals :: 0..253} | {:scientific, decimals :: 0..249} | :compact)
  def float_to_binary(_Float, _Options), do: :erlang.nif_error(:undefined)

  @spec float_to_list(float) :: charlist() when float: float()
  def float_to_list(_Float), do: :erlang.nif_error(:undefined)

  @spec float_to_list(float, options) :: charlist() when float: float(), options: [option], option: ({:decimals, decimals :: 0..253} | {:scientific, decimals :: 0..249} | :compact)
  def float_to_list(_Float, _Options), do: :erlang.nif_error(:undefined)

  @spec floor(number) :: integer() when number: number()
  def floor(_), do: :erlang.nif_error(:undef)

  def format_cpu_topology(internalCpuTopology) do
    try do
      cput_i2e(internalCpuTopology)
    catch
      {_, _, _} ->
        :erlang.error(:internal_error, [internalCpuTopology])
    end
  end

  def fun_info(fun) when :erlang.is_function(fun) do
    keys = [:type, :env, :arity, :name, :uniq, :index, :new_uniq, :new_index, :module, :pid]
    fun_info_1(keys, fun, [])
  end

  def fun_info(_Fun, _Item), do: :erlang.nif_error(:undefined)

  def fun_info_mfa(_Fun), do: :erlang.nif_error(:undefined)

  def fun_to_list(_Fun), do: :erlang.nif_error(:undefined)

  def function_exported(_Module, _Function, _Arity), do: :erlang.nif_error(:undefined)

  @spec garbage_collect() :: true
  def garbage_collect(), do: :erts_internal.garbage_collect(:major)

  @spec garbage_collect(pid) :: gCResult when pid: pid(), gCResult: boolean()
  def garbage_collect(pid) do
    try do
      :erlang.garbage_collect(pid, [])
    catch
      {:error, error, _} ->
        :erlang.error(error, [pid])
    end
  end

  @spec garbage_collect(pid, optionList) :: (gCResult | :async) when pid: pid(), requestId: term(), option: ({:async, requestId} | {:type, (:major | :minor)}), optionList: [option], gCResult: boolean()
  def garbage_collect(pid, optionList) do
    try do
      gcOpts = get_gc_opts(optionList, gcopt())
    case gcopt(gcOpts, :async) do
      {:async, reqId} ->
        {:priority, prio} = :erlang.process_info(:erlang.self(), :priority)
        :erts_internal.request_system_task(pid, prio, {:garbage_collect, reqId, gcopt(gcOpts, :type)})
        :async
      :sync ->
        case pid == :erlang.self() do
          true ->
            :erts_internal.garbage_collect(gcopt(gcOpts, :type))
          false ->
            {:priority, prio} = :erlang.process_info(:erlang.self(), :priority)
            reqId = :erlang.make_ref()
            :erts_internal.request_system_task(pid, prio, {:garbage_collect, reqId, gcopt(gcOpts, :type)})
            receive do
            {:garbage_collect, ^reqId, gCResult} ->
                gCResult
            end
        end
    end
    catch
      {:error, error, _} ->
        :erlang.error(error, [pid, optionList])
    end
  end

  def garbage_collect_message_area(), do: :erlang.nif_error(:undefined)

  def gather_gc_info_result(ref) when :erlang.is_reference(ref), do: gc_info(ref, :erlang.system_info(:schedulers), {0, 0})

  @spec get() :: [{key, val}] when key: term(), val: term()
  def get(), do: :erlang.nif_error(:undefined)

  @spec get(key) :: (val | :undefined) when key: term(), val: term()
  def get(_Key), do: :erlang.nif_error(:undefined)

  def get_cookie(), do: :auth.get_cookie()

  @spec get_keys() :: [key] when key: term()
  def get_keys(), do: :erlang.nif_error(:undefined)

  @spec get_keys(val) :: [key] when val: term(), key: term()
  def get_keys(_Val), do: :erlang.nif_error(:undefined)

  def get_module_info(_Module), do: :erlang.nif_error(:undefined)

  def get_module_info(_Module, _Item), do: :erlang.nif_error(:undefined)

  def get_stacktrace(), do: :erlang.nif_error(:undefined)

  @spec group_leader() :: pid()
  def group_leader(), do: :erlang.nif_error(:undefined)

  @spec group_leader(groupLeader, pid) :: true when groupLeader: pid(), pid: pid()
  def group_leader(groupLeader, pid) do
    case :erts_internal.group_leader(groupLeader, pid) do
      false ->
        ref = :erlang.make_ref()
        :erts_internal.group_leader(groupLeader, pid, ref)
        receive do
        {^ref, msgRes} ->
            msgRes
        end
      res ->
        res
    end
    |> case do
      true ->
        true
      error ->
        :erlang.error(error, [groupLeader, pid])
    end
  end

  @spec halt() :: no_return()
  def halt(), do: :erlang.halt(0, [])

  @spec halt(status) :: no_return() when status: (non_neg_integer() | :abort | charlist())
  def halt(status), do: :erlang.halt(status, [])

  @spec halt(status, options) :: no_return() when status: (non_neg_integer() | :abort | charlist()), options: [option], option: {:flush, boolean()}
  def halt(_Status, _Options), do: :erlang.nif_error(:undefined)

  def has_prepared_code_on_load(_PreparedCode), do: :erlang.nif_error(:undefined)

  @spec hd(list) :: term() when list: [term(), ...]
  def hd(_List), do: :erlang.nif_error(:undefined)

  def hibernate(_Module, _Function, _Args), do: :erlang.nif_error(:undefined)

  def insert_element(_Index, _Tuple1, _Term), do: :erlang.nif_error(:undefined)

  @spec integer_to_binary(integer) :: binary() when integer: integer()
  def integer_to_binary(_Integer), do: :erlang.nif_error(:undefined)

  @spec integer_to_binary(integer, base) :: binary() when integer: integer(), base: 2..36
  def integer_to_binary(_I, _Base), do: :erlang.nif_error(:undefined)

  @spec integer_to_list(integer) :: charlist() when integer: integer()
  def integer_to_list(_Integer), do: :erlang.nif_error(:undefined)

  @spec integer_to_list(integer, base) :: charlist() when integer: integer(), base: 2..36
  def integer_to_list(_I, _Base), do: :erlang.nif_error(:undefined)

  @spec iolist_size(item) :: non_neg_integer() when item: (iolist() | binary())
  def iolist_size(_Item), do: :erlang.nif_error(:undefined)

  @spec iolist_to_binary(ioListOrBinary) :: binary() when ioListOrBinary: (iolist() | binary())
  def iolist_to_binary(_IoListOrBinary), do: :erlang.nif_error(:undefined)

  def iolist_to_iovec(_IoListOrBinary), do: :erlang.nif_error(:undefined)

  @spec is_alive() :: boolean()
  def is_alive(), do: :erlang.nif_error(:undefined)

  @spec is_atom(term) :: boolean() when term: term()
  def is_atom(_Term), do: :erlang.nif_error(:undefined)

  @spec is_binary(term) :: boolean() when term: term()
  def is_binary(_Term), do: :erlang.nif_error(:undefined)

  @spec is_bitstring(term) :: boolean() when term: term()
  def is_bitstring(_Term), do: :erlang.nif_error(:undefined)

  @spec is_boolean(term) :: boolean() when term: term()
  def is_boolean(_Term), do: :erlang.nif_error(:undefined)

  def is_builtin(_Module, _Function, _Arity), do: :erlang.nif_error(:undefined)

  @spec is_float(term) :: boolean() when term: term()
  def is_float(_Term), do: :erlang.nif_error(:undefined)

  @spec is_function(term) :: boolean() when term: term()
  def is_function(_Term), do: :erlang.nif_error(:undefined)

  @spec is_function(term, arity) :: boolean() when term: term(), arity: arity()
  def is_function(_Term, _Arity), do: :erlang.nif_error(:undefined)

  @spec is_integer(term) :: boolean() when term: term()
  def is_integer(_Term), do: :erlang.nif_error(:undefined)

  @spec is_list(term) :: boolean() when term: term()
  def is_list(_Term), do: :erlang.nif_error(:undefined)

  @spec is_map(term) :: boolean() when term: term()
  def is_map(_Term), do: :erlang.nif_error(:undefined)

  @spec is_map_key(key, map) :: boolean() when key: term(), map: map()
  def is_map_key(_, _), do: :erlang.nif_error(:undef)

  @spec is_number(term) :: boolean() when term: term()
  def is_number(_Term), do: :erlang.nif_error(:undefined)

  @spec is_pid(term) :: boolean() when term: term()
  def is_pid(_Term), do: :erlang.nif_error(:undefined)

  @spec is_port(term) :: boolean() when term: term()
  def is_port(_Term), do: :erlang.nif_error(:undefined)

  @spec is_process_alive(pid) :: boolean() when pid: pid()
  def is_process_alive(_Pid), do: :erlang.nif_error(:undefined)

  @spec is_record(term, recordTag) :: boolean() when term: term(), recordTag: atom()
  def is_record(_Term, _RecordTag), do: :erlang.nif_error(:undefined)

  @spec is_record(term, recordTag, size) :: boolean() when term: term(), recordTag: atom(), size: non_neg_integer()
  def is_record(_Term, _RecordTag, _Size), do: :erlang.nif_error(:undefined)

  @spec is_reference(term) :: boolean() when term: term()
  def is_reference(_Term), do: :erlang.nif_error(:undefined)

  @spec is_tuple(term) :: boolean() when term: term()
  def is_tuple(_Term), do: :erlang.nif_error(:undefined)

  @spec length(list) :: non_neg_integer() when list: [term()]
  def length(_List), do: :erlang.nif_error(:undefined)

  @spec link(pidOrPort) :: true when pidOrPort: (pid() | port())
  def link(_PidOrPort), do: :erlang.nif_error(:undefined)

  @spec list_to_atom(string) :: atom() when string: charlist()
  def list_to_atom(_String), do: :erlang.nif_error(:undefined)

  @spec list_to_binary(ioList) :: binary() when ioList: iolist()
  def list_to_binary(_IoList), do: :erlang.nif_error(:undefined)

  @spec list_to_bitstring(bitstringList) :: bitstring() when bitstringList: bitstring_list()
  def list_to_bitstring(_BitstringList), do: :erlang.nif_error(:undefined)

  @spec list_to_existing_atom(string) :: atom() when string: charlist()
  def list_to_existing_atom(_String), do: :erlang.nif_error(:undefined)

  @spec list_to_float(string) :: float() when string: charlist()
  def list_to_float(_String), do: :erlang.nif_error(:undefined)

  @spec list_to_integer(string) :: integer() when string: charlist()
  def list_to_integer(_String), do: :erlang.nif_error(:undefined)

  @spec list_to_integer(string, base) :: integer() when string: charlist(), base: 2..36
  def list_to_integer(_String, _Base), do: :erlang.nif_error(:undefined)

  @spec list_to_pid(string) :: pid() when string: charlist()
  def list_to_pid(_String), do: :erlang.nif_error(:undefined)

  @spec list_to_port(string) :: port() when string: charlist()
  def list_to_port(_String), do: :erlang.nif_error(:undefined)

  @spec list_to_ref(string) :: reference() when string: charlist()
  def list_to_ref(_String), do: :erlang.nif_error(:undefined)

  @spec list_to_tuple(list) :: tuple() when list: [term()]
  def list_to_tuple(_List), do: :erlang.nif_error(:undefined)

  @spec load_module(module, binary) :: ({:module, module} | {:error, reason}) when module: module(), binary: binary(), reason: (:badfile | :not_purged | :on_load)
  def load_module(mod, code) do
    case :erlang.prepare_loading(mod, code) do
      {:error, _} = error ->
        error
      prep when :erlang.is_reference(prep) ->
        case :erlang.finish_loading([prep]) do
          :ok ->
            {:module, mod}
          {error, [mod]} ->
            {:error, error}
        end
    end
  end

  def load_nif(_Path, _LoadInfo), do: :erlang.nif_error(:undefined)

  def loaded(), do: :erlang.nif_error(:undefined)

  def localtime(), do: :erlang.nif_error(:undefined)

  def localtime_to_universaltime(localtime), do: :erlang.localtime_to_universaltime(localtime, :undefined)

  def localtime_to_universaltime(_Localtime, _IsDst), do: :erlang.nif_error(:undefined)

  def make_fun(_Module, _Function, _Arity), do: :erlang.nif_error(:undefined)

  @spec make_ref() :: reference()
  def make_ref(), do: :erlang.nif_error(:undefined)

  def make_tuple(_Arity, _InitialValue), do: :erlang.nif_error(:undefined)

  def make_tuple(_Arity, _DefaultValue, _InitList), do: :erlang.nif_error(:undefined)

  @spec map_get(key, map) :: value when map: map(), key: any(), value: any()
  def map_get(_Key, _Map), do: :erlang.nif_error(:undefined)

  @spec map_size(map) :: non_neg_integer() when map: map()
  def map_size(_Map), do: :erlang.nif_error(:undefined)

  def match_spec_test(_P1, _P2, _P3), do: :erlang.nif_error(:undefined)

  @spec max(term1, term2) :: maximum when term1: term(), term2: term(), maximum: term()
  def max(a, b) when a < b, do: b

  def max(a, _), do: a

  def md5(_Data), do: :erlang.nif_error(:undefined)

  def md5_final(_Context), do: :erlang.nif_error(:undefined)

  def md5_init(), do: :erlang.nif_error(:undefined)

  def md5_update(_Context, _Data), do: :erlang.nif_error(:undefined)

  def memory() do
    case aa_mem_data(au_mem_data(:erlang.system_info(:alloc_util_allocators) -- [:mseg_alloc])) do
      :notsup ->
        :erlang.error(:notsup)
      mem ->
        [{:total, memory(mem, :total)}, {:processes, memory(mem, :processes)}, {:processes_used, memory(mem, :processes_used)}, {:system, memory(mem, :system)}, {:atom, memory(mem, :atom)}, {:atom_used, memory(mem, :atom_used)}, {:binary, memory(mem, :binary)}, {:code, memory(mem, :code)}, {:ets, memory(mem, :ets)}]
    end
  end

  def memory(type) when :erlang.is_atom(type) do
    try do
      case aa_mem_data(au_mem_data(:erlang.system_info(:alloc_util_allocators) -- [:mseg_alloc])) do
      :notsup ->
        :erlang.error(:notsup)
      mem ->
        get_memval(type, mem)
    end
    catch
      {:error, :badarg, _} ->
        :erlang.error(:badarg)
    end
  end

  def memory(types) when :erlang.is_list(types) do
    try do
      case aa_mem_data(au_mem_data(:erlang.system_info(:alloc_util_allocators) -- [:mseg_alloc])) do
      :notsup ->
        :erlang.error(:notsup)
      mem ->
        memory_1(types, mem)
    end
    catch
      {:error, :badarg, _} ->
        :erlang.error(:badarg)
    end
  end

  @spec min(term1, term2) :: minimum when term1: term(), term2: term(), minimum: term()
  def min(a, b) when a > b, do: b

  def min(a, _), do: a

  def module_info() do
    # body not decompiled
  end

  def module_info(p0) do
    # body not decompiled
  end

  @spec module_loaded(module) :: boolean() when module: module()
  def module_loaded(_Module), do: :erlang.nif_error(:undefined)

  @spec monitor(:process, monitor_process_identifier()) :: monitorRef when monitorRef: reference()
  @spec monitor(:port, monitor_port_identifier()) :: monitorRef when monitorRef: reference()
  @spec monitor(:time_offset, :clock_service) :: monitorRef when monitorRef: reference()
  def monitor(_Type, _Item), do: :erlang.nif_error(:undefined)

  @spec monitor_node(node, flag) :: true when node: node(), flag: boolean()
  def monitor_node(_Node, _Flag), do: :erlang.nif_error(:undefined)

  def monitor_node(_Node, _Flag, _Options), do: :erlang.nif_error(:undefined)

  def monotonic_time(), do: :erlang.nif_error(:undefined)

  def monotonic_time(_Unit), do: :erlang.nif_error(:undefined)

  def nif_error(_Reason), do: :erlang.nif_error(:undefined)

  def nif_error(_Reason, _Args), do: :erlang.nif_error(:undefined)

  @spec node() :: node when node: node()
  def node(), do: :erlang.nif_error(:undefined)

  @spec node(arg) :: node when arg: (pid() | port() | reference()), node: node()
  def node(_Arg), do: :erlang.nif_error(:undefined)

  @spec nodes() :: nodes when nodes: [node()]
  def nodes(), do: :erlang.nodes(:visible)

  @spec nodes(arg) :: nodes when arg: (nodeType | [nodeType]), nodeType: (:visible | :hidden | :connected | :this | :known), nodes: [node()]
  def nodes(_Arg), do: :erlang.nif_error(:undefined)

  def unquote(:not)(_A), do: :erlang.nif_error(:undefined)

  @spec now() :: timestamp when timestamp: timestamp()
  def now(), do: :erlang.nif_error(:undefined)

  @spec open_port(portName, portSettings) :: port() when portName: ({:spawn, command :: (charlist() | binary())} | {:spawn_driver, command :: (charlist() | binary())} | {:spawn_executable, fileName :: :file.name_all()} | {:fd, erlangVariableIn :: non_neg_integer(), out :: non_neg_integer()}), portSettings: [opt], opt: ({:packet, n :: (1 | 2 | 4)} | :stream | {:line, l :: non_neg_integer()} | {:cd, dir :: (charlist() | binary())} | {:env, env :: [{name :: :os.env_var_name(), val :: (:os.env_var_value() | false)}]} | {:args, [(charlist() | binary())]} | {:arg0, (charlist() | binary())} | :exit_status | :use_stdio | :nouse_stdio | :stderr_to_stdout | :in | :out | :binary | :eof | {:parallelism, boolean :: boolean()} | :hide | {:busy_limits_port, ({non_neg_integer(), non_neg_integer()} | :disabled)} | {:busy_limits_msgq, ({non_neg_integer(), non_neg_integer()} | :disabled)})
  def open_port(portName, portSettings) do
    case :erts_internal.open_port(portName, portSettings) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      port when :erlang.is_port(port) ->
        port
      error ->
        :erlang.error(error, [portName, portSettings])
    end
  end

  def _A or _B, do: :erlang.nif_error(:undefined)

  def phash(_Term, _Range), do: :erlang.nif_error(:undefined)

  def phash2(_Term), do: :erlang.nif_error(:undefined)

  def phash2(_Term, _Range), do: :erlang.nif_error(:undefined)

  @spec pid_to_list(pid) :: charlist() when pid: pid()
  def pid_to_list(_Pid), do: :erlang.nif_error(:undefined)

  def port_call(port, data) do
    case :erts_internal.port_call(port, 0, data) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      {:ok, result} ->
        result
      error ->
        :erlang.error(error, [port, data])
    end
  end

  def port_call(port, operation, data) do
    case :erts_internal.port_call(port, operation, data) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      {:ok, result} ->
        result
      error ->
        :erlang.error(error, [port, operation, data])
    end
  end

  @spec port_close(port) :: true when port: (port() | atom())
  def port_close(port) do
    case :erts_internal.port_close(port) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      true ->
        true
      error ->
        :erlang.error(error, [port])
    end
  end

  @spec port_command(port, data) :: true when port: (port() | atom()), data: iodata()
  def port_command(port, data) do
    case :erts_internal.port_command(port, data, []) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      true ->
        true
      error ->
        :erlang.error(error, [port, data])
    end
  end

  @spec port_command(port, data, optionList) :: boolean() when port: (port() | atom()), data: iodata(), option: (:force | :nosuspend), optionList: [option]
  def port_command(port, data, flags) do
    case :erts_internal.port_command(port, data, flags) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      bool when bool == true or bool == false ->
        bool
      error ->
        :erlang.error(error, [port, data, flags])
    end
  end

  @spec port_connect(port, pid) :: true when port: (port() | atom()), pid: pid()
  def port_connect(port, pid) do
    case :erts_internal.port_connect(port, pid) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      true ->
        true
      error ->
        :erlang.error(error, [port, pid])
    end
  end

  @spec port_control(port, operation, data) :: (iodata() | binary()) when port: (port() | atom()), operation: integer(), data: iodata()
  def port_control(port, operation, data) do
    case :erts_internal.port_control(port, operation, data) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      :badarg ->
        :erlang.error(:badarg, [port, operation, data])
      result ->
        result
    end
  end

  def port_get_data(_Port), do: :erlang.nif_error(:undefined)

  def port_info(port) do
    case :erts_internal.port_info(port) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      :badarg ->
        :erlang.error(:badarg, [port])
      result ->
        result
    end
  end

  def port_info(port, item) do
    case :erts_internal.port_info(port, item) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      :badarg ->
        :erlang.error(:badarg, [port, item])
      result ->
        result
    end
  end

  def port_set_data(_Port, _Data), do: :erlang.nif_error(:undefined)

  @spec port_to_list(port) :: charlist() when port: port()
  def port_to_list(_Port), do: :erlang.nif_error(:undefined)

  def ports(), do: :erlang.nif_error(:undefined)

  def posixtime_to_universaltime(_P1), do: :erlang.nif_error(:undefined)

  @spec pre_loaded() :: [module()]
  def pre_loaded(), do: :erlang.nif_error(:undefined)

  def prepare_loading(_Module, _Code), do: :erlang.nif_error(:undefined)

  def process_display(pid, type) do
    case :erts_internal.process_display(pid, type) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      :badarg ->
        :erlang.error(:badarg, [pid, type])
      result ->
        result
    end
  end

  @spec process_flag(:trap_exit, boolean) :: oldBoolean when boolean: boolean(), oldBoolean: boolean()
  @spec process_flag(:error_handler, module) :: oldModule when module: atom(), oldModule: atom()
  @spec process_flag(:min_heap_size, minHeapSize) :: oldMinHeapSize when minHeapSize: non_neg_integer(), oldMinHeapSize: non_neg_integer()
  @spec process_flag(:min_bin_vheap_size, minBinVHeapSize) :: oldMinBinVHeapSize when minBinVHeapSize: non_neg_integer(), oldMinBinVHeapSize: non_neg_integer()
  @spec process_flag(:max_heap_size, maxHeapSize) :: oldMaxHeapSize when maxHeapSize: max_heap_size(), oldMaxHeapSize: max_heap_size()
  @spec process_flag(:message_queue_data, mQD) :: oldMQD when mQD: message_queue_data(), oldMQD: message_queue_data()
  @spec process_flag(:priority, level) :: oldLevel when level: priority_level(), oldLevel: priority_level()
  @spec process_flag(:save_calls, n) :: oldN when n: 0..10000, oldN: 0..10000
  @spec process_flag(:sensitive, boolean) :: oldBoolean when boolean: boolean(), oldBoolean: boolean()
  @spec process_flag({:monitor_nodes, term()}, term()) :: term()
  @spec process_flag(:monitor_nodes, term()) :: term()
  def process_flag(_Flag, _Value), do: :erlang.nif_error(:undefined)

  @spec process_flag(pid, flag, value) :: oldValue when pid: pid(), flag: :save_calls, value: non_neg_integer(), oldValue: non_neg_integer()
  def process_flag(pid, flag, value) do
    case :erts_internal.process_flag(pid, flag, value) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      :badarg ->
        :erlang.error(:badarg, [pid, flag, value])
      result ->
        result
    end
  end

  @spec process_info(pid) :: info when pid: pid(), info: ([infoTuple] | :undefined), infoTuple: process_info_result_item()
  def process_info(_Pid), do: :erlang.nif_error(:undefined)

  @spec process_info(pid, item) :: (infoTuple | [] | :undefined) when pid: pid(), item: process_info_item(), infoTuple: process_info_result_item()
  @spec process_info(pid, itemList) :: (infoTupleList | [] | :undefined) when pid: pid(), itemList: [item], item: process_info_item(), infoTupleList: [infoTuple], infoTuple: process_info_result_item()
  def process_info(_Pid, _ItemSpec), do: :erlang.nif_error(:undefined)

  @spec processes() :: [pid()]
  def processes(), do: :erlang.nif_error(:undefined)

  @spec purge_module(module) :: true when module: atom()
  def purge_module(module) when :erlang.is_atom(module) do
    case :erts_code_purger.purge(module) do
      {false, _} ->
        :erlang.error(:badarg, [module])
      {true, _} ->
        true
    end
  end

  def purge_module(arg), do: :erlang.error(:badarg, [arg])

  @spec put(key, val) :: term() when key: term(), val: term()
  def put(_Key, _Val), do: :erlang.nif_error(:undefined)

  def raise(_Class, _Reason, _Stacktrace), do: :erlang.nif_error(:undefined)

  def read_timer(_TimerRef), do: :erlang.nif_error(:undefined)

  def read_timer(_TimerRef, _Options), do: :erlang.nif_error(:undefined)

  @spec ref_to_list(ref) :: charlist() when ref: reference()
  def ref_to_list(_Ref), do: :erlang.nif_error(:undefined)

  @spec register(regName, pidOrPort) :: true when regName: atom(), pidOrPort: (port() | pid())
  def register(_RegName, _PidOrPort), do: :erlang.nif_error(:undefined)

  @spec registered() :: [regName] when regName: atom()
  def registered(), do: :erlang.nif_error(:undefined)

  def rem(_A, _B), do: :erlang.nif_error(:undefined)

  def resume_process(_Suspendee), do: :erlang.nif_error(:undefined)

  @spec round(number) :: integer() when number: number()
  def round(_Number), do: :erlang.nif_error(:undefined)

  @spec self() :: pid()
  def self(), do: :erlang.nif_error(:undefined)

  def send(_Dest, _Msg), do: :erlang.nif_error(:undefined)

  def send(_Dest, _Msg, _Options), do: :erlang.nif_error(:undefined)

  def send_after(_Time, _Dest, _Msg), do: :erlang.nif_error(:undefined)

  def send_after(_Time, _Dest, _Msg, _Options), do: :erlang.nif_error(:undefined)

  def send_nosuspend(pid, msg), do: send_nosuspend(pid, msg, [])

  def send_nosuspend(pid, msg, opts) do
    case :erlang.send(pid, msg, [:nosuspend | opts]) do
      :ok ->
        true
      _ ->
        false
    end
  end

  def seq_trace(_P1, _P2), do: :erlang.nif_error(:undefined)

  def seq_trace_info(_What), do: :erlang.nif_error(:undefined)

  def seq_trace_print(_P1), do: :erlang.nif_error(:undefined)

  def seq_trace_print(_P1, _P2), do: :erlang.nif_error(:undefined)

  def set_cookie(node, c) when node !== :nonode@nohost and :erlang.is_atom(node) do
    case :erlang.is_atom(c) do
      true ->
        :auth.set_cookie(node, c)
      false ->
        :erlang.error(:badarg)
    end
  end

  def set_cpu_topology(cpuTopology) do
    try do
      format_cpu_topology(:erlang.system_flag(:internal_cpu_topology, cput_e2i(cpuTopology)))
    catch
      {class, exception, _} when class !== :error or exception !== :internal_error ->
        :erlang.error(:badarg, [cpuTopology])
    end
  end

  @spec setelement(index, tuple1, value) :: tuple2 when index: pos_integer(), tuple1: tuple(), tuple2: tuple(), value: term()
  def setelement(_Index, _Tuple1, _Value), do: :erlang.nif_error(:undefined)

  def setnode(_P1, _P2), do: :erlang.nif_error(:undefined)

  def setnode(node, distCtrlr, {_Flags, _Ver, _Creation} = opts) do
    case :erts_internal.create_dist_channel(node, distCtrlr, opts) do
      {:ok, dH} ->
        dH
      {:message, ref} ->
        receive do
        {^ref, res} ->
            res
        end
      err ->
        err
    end
    |> case do
      error when :erlang.is_atom(error) ->
        :erlang.error(error, [node, distCtrlr, opts])
      dHandle ->
        dHandle
    end
  end

  def setnode(node, distCtrlr, opts), do: :erlang.error(:badarg, [node, distCtrlr, opts])

  @spec size(item) :: non_neg_integer() when item: (tuple() | binary())
  def size(_Item), do: :erlang.nif_error(:undefined)

  @spec spawn(fun) :: pid() when fun: function()
  def spawn(f) when :erlang.is_function(f), do: :erlang.spawn(:erlang, :apply, [f, []])

  def spawn({m, f} = mF) when :erlang.is_atom(m) and :erlang.is_atom(f), do: :erlang.spawn(:erlang, :apply, [mF, []])

  def spawn(f), do: :erlang.error(:badarg, [f])

  @spec spawn(node, fun) :: pid() when node: node(), fun: function()
  def spawn(n, f) when n === :erlang.node(), do: :erlang.spawn(f)

  def spawn(n, f) when :erlang.is_function(f), do: :erlang.spawn(n, :erlang, :apply, [f, []])

  def spawn(n, {m, f} = mF) when :erlang.is_atom(m) and :erlang.is_atom(f), do: :erlang.spawn(n, :erlang, :apply, [mF, []])

  def spawn(n, f), do: :erlang.error(:badarg, [n, f])

  @spec spawn(module, function, args) :: pid() when module: module(), function: atom(), args: [term()]
  def spawn(_Module, _Function, _Args), do: :erlang.nif_error(:undefined)

  @spec spawn(node, module, function, args) :: pid() when node: node(), module: module(), function: atom(), args: [term()]
  def spawn(n, m, f, a) when n === :erlang.node() and :erlang.is_atom(m) and :erlang.is_atom(f) and :erlang.is_list(a), do: :erlang.spawn(m, f, a)

  def spawn(n, m, f, a) when :erlang.is_atom(n) and :erlang.is_atom(m) and :erlang.is_atom(f) do
    try do
      :erlang.spawn_opt(n, m, f, a, [])
    catch
      {_, reason, _} ->
        :erlang.error(reason, [n, m, f, a])
    end
  end

  def spawn(n, m, f, a), do: :erlang.error(:badarg, [n, m, f, a])

  @spec spawn_link(fun) :: pid() when fun: function()
  def spawn_link(f) when :erlang.is_function(f), do: :erlang.spawn_link(:erlang, :apply, [f, []])

  def spawn_link({m, f} = mF) when :erlang.is_atom(m) and :erlang.is_atom(f), do: :erlang.spawn_link(:erlang, :apply, [mF, []])

  def spawn_link(f), do: :erlang.error(:badarg, [f])

  @spec spawn_link(node, fun) :: pid() when node: node(), fun: function()
  def spawn_link(n, f) when n === :erlang.node(), do: spawn_link(f)

  def spawn_link(n, f) when :erlang.is_function(f), do: spawn_link(n, :erlang, :apply, [f, []])

  def spawn_link(n, {m, f} = mF) when :erlang.is_atom(m) and :erlang.is_atom(f), do: spawn_link(n, :erlang, :apply, [mF, []])

  def spawn_link(n, f), do: :erlang.error(:badarg, [n, f])

  @spec spawn_link(module, function, args) :: pid() when module: module(), function: atom(), args: [term()]
  def spawn_link(_Module, _Function, _Args), do: :erlang.nif_error(:undefined)

  @spec spawn_link(node, module, function, args) :: pid() when node: node(), module: module(), function: atom(), args: [term()]
  def spawn_link(n, m, f, a) when n === :erlang.node() and :erlang.is_atom(m) and :erlang.is_atom(f) and :erlang.is_list(a), do: :erlang.spawn_link(m, f, a)

  def spawn_link(n, m, f, a) when :erlang.is_atom(n) and :erlang.is_atom(m) and :erlang.is_atom(f) do
    try do
      :erlang.spawn_opt(n, m, f, a, [:link])
    catch
      {_, reason, _} ->
        :erlang.error(reason, [n, m, f, a])
    end
  end

  def spawn_link(n, m, f, a), do: :erlang.error(:badarg, [n, m, f, a])

  @spec spawn_monitor(fun) :: {pid(), reference()} when fun: function()
  def spawn_monitor(f) when :erlang.is_function(f, 0), do: :erlang.spawn_opt(:erlang, :apply, [f, []], [:monitor])

  def spawn_monitor(f), do: :erlang.error(:badarg, [f])

  @spec spawn_monitor(node, fun) :: {pid(), reference()} when node: node(), fun: function()
  def spawn_monitor(node, f) when :erlang.is_atom(node) and :erlang.is_function(f, 0) do
    try do
      :erlang.spawn_monitor(node, :erlang, :apply, [f, []])
    catch
      {:error, err, _} ->
        :erlang.error(err, [node, f])
    end
  end

  def spawn_monitor(node, f), do: :erlang.error(:badarg, [node, f])

  @spec spawn_monitor(module, function, args) :: {pid(), reference()} when module: module(), function: atom(), args: [term()]
  def spawn_monitor(m, f, a) when :erlang.is_atom(m) and :erlang.is_atom(f) and :erlang.is_list(a), do: :erlang.spawn_opt(m, f, a, [:monitor])

  def spawn_monitor(m, f, a), do: :erlang.error(:badarg, [m, f, a])

  @spec spawn_monitor(node, module, function, args) :: {pid(), reference()} when node: node(), module: module(), function: atom(), args: [term()]
  def spawn_monitor(n, m, f, a) when n === :erlang.node() and :erlang.is_atom(m) and :erlang.is_atom(f) and :erlang.is_list(a) do
    try do
      :erlang.spawn_monitor(m, f, a)
    catch
      {:error, err, _} ->
        :erlang.error(err, [n, m, f, a])
    end
  end

  def spawn_monitor(n, m, f, a) when :erlang.is_atom(n) and :erlang.is_atom(m) and :erlang.is_atom(f) do
    ref = try do
      :erlang.spawn_request(n, m, f, a, [:monitor])
    catch
      {:error, err0, _} ->
        :erlang.error(err0, [n, m, f, a])
    end
    receive do
    {:spawn_reply, ^ref, :ok, pid} when :erlang.is_pid(pid) ->
        {pid, ref}
      {:spawn_reply, ^ref, :error, :badopt} ->
        :erlang.error(:badarg, [n, m, f, a])
      {:spawn_reply, ^ref, :error, :noconnection} ->
        try do
          :erlang.spawn_opt(:erts_internal, :crasher, [n, m, f, a, [:monitor], :noconnection], [:monitor])
        catch
          {_, err1, _} ->
            :erlang.error(err1, [n, m, f, a])
        end
      {:spawn_reply, ^ref, :error, err2} ->
        :erlang.error(err2, [n, m, f, a])
    end
  end

  def spawn_monitor(n, m, f, a), do: :erlang.error(:badarg, [n, m, f, a])

  @spec spawn_opt(fun, options) :: (pid() | {pid(), reference()}) when fun: function(), options: [spawn_opt_option()]
  def spawn_opt(f, o) when :erlang.is_function(f), do: :erlang.spawn_opt(:erlang, :apply, [f, []], o)

  def spawn_opt({m, f} = mF, o) when :erlang.is_atom(m) and :erlang.is_atom(f), do: :erlang.spawn_opt(:erlang, :apply, [mF, []], o)

  def spawn_opt(f, o), do: :erlang.error(:badarg, [f, o])

  @spec spawn_opt(node, fun, options) :: (pid() | {pid(), reference()}) when node: node(), fun: function(), options: [(:monitor | :link | otherOption)], otherOption: term()
  def spawn_opt(n, f, o) when n === :erlang.node(), do: :erlang.spawn_opt(f, o)

  def spawn_opt(n, f, o) when :erlang.is_function(f, 0), do: :erlang.spawn_opt(n, :erlang, :apply, [f, []], o)

  def spawn_opt(n, {m, f} = mF, o) when :erlang.is_atom(m) and :erlang.is_atom(f), do: :erlang.spawn_opt(n, :erlang, :apply, [mF, []], o)

  def spawn_opt(n, f, o), do: :erlang.error(:badarg, [n, f, o])

  @spec spawn_opt(module, function, args, options) :: (pid() | {pid(), reference()}) when module: module(), function: atom(), args: [term()], options: [spawn_opt_option()]
  def spawn_opt(_Module, _Function, _Args, _Options), do: :erlang.nif_error(:undefined)

  @spec spawn_opt(node, module, function, args, options) :: (pid() | {pid(), reference()}) when node: node(), module: module(), function: atom(), args: [term()], options: [(:monitor | :link | otherOption)], otherOption: term()
  def spawn_opt(n, m, f, a, o) when n === :erlang.node() and :erlang.is_atom(m) and :erlang.is_atom(f) and :erlang.is_list(a) and :erlang.is_list(o), do: :erlang.spawn_opt(m, f, a, o)

  def spawn_opt(n, m, f, a, o) when :erlang.is_atom(n) and :erlang.is_atom(m) and :erlang.is_atom(f) do
    {ref, monOpt} = case :erts_internal.dist_spawn_request(n, {m, f, a}, o, :spawn_opt) do
      {r, mO} when :erlang.is_reference(r) ->
        {r, mO}
      :badarg ->
        :erlang.error(:badarg, [n, m, f, a, o])
    end
    receive do
    {:spawn_reply, ^ref, :ok, pid} when :erlang.is_pid(pid) ->
        case monOpt do
          true ->
            {pid, ref}
          false ->
            pid
        end
      {:spawn_reply, ^ref, :error, :badopt} ->
        :erlang.error(:badarg, [n, m, f, a, o])
      {:spawn_reply, ^ref, :error, :noconnection} ->
        try do
          :erlang.spawn_opt(:erts_internal, :crasher, [n, m, f, a, o, :noconnection], o)
        catch
          {_, err1, _} ->
            :erlang.error(err1, [n, m, f, a, o])
        end
      {:spawn_reply, ^ref, :error, :notsup} ->
        case old_remote_spawn_opt(n, m, f, a, o) do
          pid when :erlang.is_pid(pid) ->
            pid
          err2 ->
            :erlang.error(err2, [n, m, f, a, o])
        end
      {:spawn_reply, ^ref, :error, err3} ->
        :erlang.error(err3, [n, m, f, a, o])
    end
  end

  def spawn_opt(n, m, f, a, o), do: :erlang.error(:badarg, [n, m, f, a, o])

  @spec spawn_request(fun) :: reqId when fun: function(), reqId: reference()
  def spawn_request(f) when :erlang.is_function(f, 0) do
    try do
      :erlang.spawn_request(:erlang, :apply, [f, []], [])
    catch
      {:error, err, _} ->
        :erlang.error(err, [f])
    end
  end

  def spawn_request(f), do: :erlang.error(:badarg, [f])

  @spec spawn_request(fun, options) :: reqId when fun: function(), option: ({:reply_tag, replyTag} | {:reply, reply} | spawn_opt_option()), replyTag: term(), reply: (:yes | :no | :error_only | :success_only), options: [option], reqId: reference()
  @spec spawn_request(node, fun) :: reqId when node: node(), fun: function(), reqId: reference()
  def spawn_request(f, o) when :erlang.is_function(f, 0) do
    try do
      :erlang.spawn_request(:erlang, :apply, [f, []], o)
    catch
      {:error, err, _} ->
        :erlang.error(err, [f, o])
    end
  end

  def spawn_request(n, f) when :erlang.is_function(f, 0) do
    try do
      :erlang.spawn_request(n, :erlang, :apply, [f, []], [])
    catch
      {:error, err, _} ->
        :erlang.error(err, [n, f])
    end
  end

  def spawn_request(a1, a2), do: :erlang.error(:badarg, [a1, a2])

  @spec spawn_request(node, fun, options) :: reqId when node: node(), fun: function(), options: [option], option: (:monitor | :link | {:reply_tag, replyTag} | {:reply, reply} | otherOption), replyTag: term(), reply: (:yes | :no | :error_only | :success_only), otherOption: term(), reqId: reference()
  @spec spawn_request(module, function, args) :: reqId when module: module(), function: atom(), args: [term()], reqId: reference()
  def spawn_request(n, f, o) when :erlang.is_function(f, 0) do
    try do
      :erlang.spawn_request(n, :erlang, :apply, [f, []], o)
    catch
      {:error, err, _} ->
        :erlang.error(err, [n, f, o])
    end
  end

  def spawn_request(m, f, a) do
    try do
      :erlang.spawn_request(m, f, a, [])
    catch
      {:error, err, _} ->
        :erlang.error(err, [m, f, a])
    end
  end

  @spec spawn_request(node, module, function, args) :: reqId when node: node(), module: module(), function: atom(), args: [term()], reqId: reference()
  @spec spawn_request(module, function, args, options) :: reqId when module: module(), function: atom(), args: [term()], option: ({:reply_tag, replyTag} | {:reply, reply} | spawn_opt_option()), replyTag: term(), reply: (:yes | :no | :error_only | :success_only), options: [option], reqId: reference()
  def spawn_request(n, m, f, a) when :erlang.is_atom(f) do
    try do
      :erlang.spawn_request(n, m, f, a, [])
    catch
      {:error, err, _} ->
        :erlang.error(err, [n, m, f, a])
    end
  end

  def spawn_request(m, f, a, o) do
    case :erts_internal.spawn_request(m, f, a, o) do
      ref when :erlang.is_reference(ref) ->
        ref
      :badarg ->
        :erlang.error(:badarg, [m, f, a, o])
    end
  end

  @spec spawn_request(node, module, function, args, options) :: reqId when node: node(), module: module(), function: atom(), args: [term()], options: [option], option: (:monitor | :link | {:reply_tag, replyTag} | {:reply, reply} | otherOption), replyTag: term(), reply: (:yes | :no | :error_only | :success_only), otherOption: term(), reqId: reference()
  def spawn_request(n, m, f, a, o) when n === :erlang.node() do
    try do
      :erlang.spawn_request(m, f, a, o)
    catch
      {:error, err, _} ->
        :erlang.error(err, [n, m, f, a, o])
    end
  end

  def spawn_request(n, m, f, a, o) do
    case :erts_internal.dist_spawn_request(n, {m, f, a}, o, :spawn_request) do
      ref when :erlang.is_reference(ref) ->
        ref
      :badarg ->
        :erlang.error(:badarg, [n, m, f, a, o])
    end
  end

  @spec spawn_request_abandon(reqId :: reference()) :: boolean()
  def spawn_request_abandon(_ReqId), do: :erlang.nif_error(:undefined)

  @spec split_binary(bin, pos) :: {binary(), binary()} when bin: binary(), pos: non_neg_integer()
  def split_binary(_Bin, _Pos), do: :erlang.nif_error(:undefined)

  def start_timer(_Time, _Dest, _Msg), do: :erlang.nif_error(:undefined)

  def start_timer(_Time, _Dest, _Msg, _Options), do: :erlang.nif_error(:undefined)

  @spec statistics(:active_tasks) :: [activeTasks] when activeTasks: non_neg_integer()
  @spec statistics(:active_tasks_all) :: [activeTasks] when activeTasks: non_neg_integer()
  @spec statistics(:context_switches) :: {contextSwitches, 0} when contextSwitches: non_neg_integer()
  @spec statistics(:exact_reductions) :: {total_Exact_Reductions, exact_Reductions_Since_Last_Call} when total_Exact_Reductions: non_neg_integer(), exact_Reductions_Since_Last_Call: non_neg_integer()
  @spec statistics(:garbage_collection) :: {number_of_GCs, words_Reclaimed, 0} when number_of_GCs: non_neg_integer(), words_Reclaimed: non_neg_integer()
  @spec statistics(:io) :: {{:input, input}, {:output, output}} when input: non_neg_integer(), output: non_neg_integer()
  @spec statistics(:microstate_accounting) :: ([mSAcc_Thread] | :undefined) when mSAcc_Thread: %{required(:type) => mSAcc_Thread_Type, required(:id) => mSAcc_Thread_Id, required(:counters) => mSAcc_Counters}, mSAcc_Thread_Type: (:async | :aux | :dirty_io_scheduler | :dirty_cpu_scheduler | :poll | :scheduler), mSAcc_Thread_Id: non_neg_integer(), mSAcc_Counters: %{optional(mSAcc_Thread_State) => non_neg_integer()}, mSAcc_Thread_State: (:alloc | :aux | :bif | :busy_wait | :check_io | :emulator | :ets | :gc | :gc_fullsweep | :nif | :other | :port | :send | :sleep | :timers)
  @spec statistics(:reductions) :: {total_Reductions, reductions_Since_Last_Call} when total_Reductions: non_neg_integer(), reductions_Since_Last_Call: non_neg_integer()
  @spec statistics(:run_queue) :: non_neg_integer()
  @spec statistics(:run_queue_lengths) :: [runQueueLength] when runQueueLength: non_neg_integer()
  @spec statistics(:run_queue_lengths_all) :: [runQueueLength] when runQueueLength: non_neg_integer()
  @spec statistics(:runtime) :: {total_Run_Time, time_Since_Last_Call} when total_Run_Time: non_neg_integer(), time_Since_Last_Call: non_neg_integer()
  @spec statistics(:scheduler_wall_time) :: ([{schedulerId, activeTime, totalTime}] | :undefined) when schedulerId: pos_integer(), activeTime: non_neg_integer(), totalTime: non_neg_integer()
  @spec statistics(:scheduler_wall_time_all) :: ([{schedulerId, activeTime, totalTime}] | :undefined) when schedulerId: pos_integer(), activeTime: non_neg_integer(), totalTime: non_neg_integer()
  @spec statistics(:total_active_tasks) :: activeTasks when activeTasks: non_neg_integer()
  @spec statistics(:total_active_tasks_all) :: activeTasks when activeTasks: non_neg_integer()
  @spec statistics(:total_run_queue_lengths) :: totalRunQueueLengths when totalRunQueueLengths: non_neg_integer()
  @spec statistics(:total_run_queue_lengths_all) :: totalRunQueueLengths when totalRunQueueLengths: non_neg_integer()
  @spec statistics(:wall_clock) :: {total_Wallclock_Time, wallclock_Time_Since_Last_Call} when total_Wallclock_Time: non_neg_integer(), wallclock_Time_Since_Last_Call: non_neg_integer()
  def statistics(_Item), do: :erlang.nif_error(:undefined)

  def subtract(_, _), do: :erlang.nif_error(:undefined)

  def suspend_process(suspendee) do
    case :erts_internal.suspend_process(suspendee, []) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      true ->
        true
      false ->
        :erlang.error(:internal_error, [suspendee])
      error ->
        :erlang.error(error, [suspendee])
    end
  end

  def suspend_process(suspendee, optList) do
    case :erts_internal.suspend_process(suspendee, optList) do
      ref when :erlang.is_reference(ref) ->
        receive do
        {^ref, res} ->
            res
        end
      res ->
        res
    end
    |> case do
      true ->
        true
      false ->
        false
      error ->
        :erlang.error(error, [suspendee, optList])
    end
  end

  def system_flag(_Flag, _Value), do: :erlang.nif_error(:undefined)

  def system_info(_Item), do: :erlang.nif_error(:undefined)

  def system_monitor(), do: :erlang.nif_error(:undefined)

  def system_monitor(_Arg), do: :erlang.nif_error(:undefined)

  def system_monitor(_MonitorPid, _Options), do: :erlang.nif_error(:undefined)

  def system_profile(), do: :erlang.nif_error(:undefined)

  def system_profile(_ProfilerPid, _Options), do: :erlang.nif_error(:undefined)

  def system_time(), do: :erlang.nif_error(:undefined)

  def system_time(_Unit), do: :erlang.nif_error(:undefined)

  @spec term_to_binary(term) :: ext_binary() when term: term()
  def term_to_binary(_Term), do: :erlang.nif_error(:undefined)

  @spec term_to_binary(term, options) :: ext_binary() when term: term(), options: [(:compressed | {:compressed, level :: 0..9} | {:minor_version, version :: 0..2})]
  def term_to_binary(_Term, _Options), do: :erlang.nif_error(:undefined)

  @spec term_to_iovec(term) :: ext_iovec() when term: term()
  def term_to_iovec(_Term), do: :erlang.nif_error(:undefined)

  @spec term_to_iovec(term, options) :: ext_iovec() when term: term(), options: [(:compressed | {:compressed, level :: 0..9} | {:minor_version, version :: 0..2})]
  def term_to_iovec(_Term, _Options), do: :erlang.nif_error(:undefined)

  @spec throw(any) :: no_return() when any: term()
  def throw(_Any), do: :erlang.nif_error(:undefined)

  @spec time() :: time when time: :calendar.time()
  def time(), do: :erlang.nif_error(:undefined)

  def time_offset(), do: :erlang.nif_error(:undefined)

  def time_offset(_Unit), do: :erlang.nif_error(:undefined)

  def timestamp(), do: :erlang.nif_error(:undefined)

  @spec tl(list) :: term() when list: [term(), ...]
  def tl(_List), do: :erlang.nif_error(:undefined)

  def trace(pidPortSpec, how, flagList) do
    case :lists.keyfind(:tracer, 1, flagList) do
      {:tracer, module, state} when :erlang.is_atom(module) ->
        case :erlang.module_loaded(module) do
          false ->
            module.enabled(:trace_status, :erlang.self(), state)
          true ->
            :ok
        end
      _ ->
        :ignore
    end
    try do
      :erts_internal.trace(pidPortSpec, how, flagList)
    catch
      {e, r, _} ->
        {_, [_ | cST]} = :erlang.process_info(:erlang.self(), :current_stacktrace)
        :erlang.raise(e, r, [{:erlang, :trace, [pidPortSpec, how, flagList], []} | cST])
    else
      res ->
        res
    end
  end

  def trace_delivered(_Tracee), do: :erlang.nif_error(:undefined)

  def trace_info(_PidPortFuncEvent, _Item), do: :erlang.nif_error(:undefined)

  def trace_pattern(mFA, matchSpec) do
    try do
      :erts_internal.trace_pattern(mFA, matchSpec, [])
    catch
      {e, r, _} ->
        {_, [_ | cST]} = :erlang.process_info(:erlang.self(), :current_stacktrace)
        :erlang.raise(e, r, [{:erlang, :trace_pattern, [mFA, matchSpec], []} | cST])
    else
      res ->
        res
    end
  end

  def trace_pattern(mFA, matchSpec, flagList) do
    case :lists.keyfind(:meta, 1, flagList) do
      {:meta, module, state} when :erlang.is_atom(module) ->
        case :erlang.module_loaded(module) do
          false ->
            module.enabled(:trace_status, :erlang.self(), state)
          true ->
            :ok
        end
      _ ->
        :ignore
    end
    try do
      :erts_internal.trace_pattern(mFA, matchSpec, flagList)
    catch
      {e, r, _} ->
        {_, [_ | cST]} = :erlang.process_info(:erlang.self(), :current_stacktrace)
        :erlang.raise(e, r, [{:erlang, :trace_pattern, [mFA, matchSpec, flagList], []} | cST])
    else
      res ->
        res
    end
  end

  @spec trunc(number) :: integer() when number: number()
  def trunc(_Number), do: :erlang.nif_error(:undefined)

  @spec tuple_size(tuple) :: non_neg_integer() when tuple: tuple()
  def tuple_size(_Tuple), do: :erlang.nif_error(:undefined)

  @spec tuple_to_list(tuple) :: [term()] when tuple: tuple()
  def tuple_to_list(_Tuple), do: :erlang.nif_error(:undefined)

  def unique_integer(), do: :erlang.nif_error(:undefined)

  def unique_integer(_ModifierList), do: :erlang.nif_error(:undefined)

  def universaltime(), do: :erlang.nif_error(:undefined)

  def universaltime_to_localtime(_Universaltime), do: :erlang.nif_error(:undefined)

  def universaltime_to_posixtime(_P1), do: :erlang.nif_error(:undefined)

  @spec unlink(id) :: true when id: (pid() | port())
  def unlink(_Id), do: :erlang.nif_error(:undefined)

  @spec unregister(regName) :: true when regName: atom()
  def unregister(_RegName), do: :erlang.nif_error(:undefined)

  @spec whereis(regName) :: (pid() | port() | :undefined) when regName: atom()
  def whereis(_RegName), do: :erlang.nif_error(:undefined)

  def xor(_A, _B), do: :erlang.nif_error(:undefined)

  def yield(), do: :erlang.yield()

  # Private Functions

  defp unquote(:"-old_remote_spawn_opt/5-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp aa_mem_data(:notsup), do: :notsup

  defp aa_mem_data(eMD), do: aa_mem_data(eMD, :erlang.system_info(:allocated_areas))

  defp aa_mem_data(memory() = mem, [{:total, tot} | rest]), do: aa_mem_data(memory(mem, total: tot, system: 0), rest)

  defp aa_mem_data(memory(atom: atom, atom_used: atomU) = mem, [{:atom_space, alloced, used} | rest]), do: aa_mem_data(memory(mem, atom: atom + alloced, atom_used: atomU + used), rest)

  defp aa_mem_data(memory(atom: atom, atom_used: atomU) = mem, [{:atom_table, sz} | rest]), do: aa_mem_data(memory(mem, atom: atom + sz, atom_used: atomU + sz), rest)

  defp aa_mem_data(memory(ets: ets) = mem, [{:ets_misc, sz} | rest]), do: aa_mem_data(memory(mem, ets: ets + sz), rest)

  defp aa_mem_data(memory(processes: proc, processes_used: procU, system: sys) = mem, [{procData, sz} | rest]) when procData == :bif_timer or procData == :process_table, do: aa_mem_data(memory(mem, processes: proc + sz, processes_used: procU + sz, system: sys - sz), rest)

  defp aa_mem_data(memory(code: code) = mem, [{codeData, sz} | rest]) when codeData == :module_table or codeData == :export_table or codeData == :export_list or codeData == :fun_table or codeData == :module_refs or codeData == :loaded_code, do: aa_mem_data(memory(mem, code: code + sz), rest)

  defp aa_mem_data(eMD, [{_, _} | rest]), do: aa_mem_data(eMD, rest)

  defp aa_mem_data(memory(total: tot, processes: proc, system: sys) = mem, []) when sys <= 0, do: memory(mem, system: tot - proc)

  defp aa_mem_data(eMD, []), do: eMD

  defp acc_blocks_size([{:size, sz, _, _} | rest], acc), do: acc_blocks_size(rest, acc + sz)

  defp acc_blocks_size([{:size, sz} | rest], acc), do: acc_blocks_size(rest, acc + sz)

  defp acc_blocks_size([_ | rest], acc), do: acc_blocks_size(rest, acc)

  defp acc_blocks_size([], acc), do: acc

  defp au_mem_acc(memory(total: tot, processes: proc, processes_used: procU) = mem, :eheap_alloc, data) do
    sz = acc_blocks_size(data, 0)
    memory(mem, total: tot + sz, processes: proc + sz, processes_used: procU + sz)
  end

  defp au_mem_acc(memory(total: tot, system: sys, ets: ets) = mem, :ets_alloc, data) do
    sz = acc_blocks_size(data, 0)
    memory(mem, total: tot + sz, system: sys + sz, ets: ets + sz)
  end

  defp au_mem_acc(memory(total: tot, system: sys, binary: bin) = mem, :binary_alloc, data) do
    sz = acc_blocks_size(data, 0)
    memory(mem, total: tot + sz, system: sys + sz, binary: bin + sz)
  end

  defp au_mem_acc(memory(total: tot, system: sys) = mem, _Type, data) do
    sz = acc_blocks_size(data, 0)
    memory(mem, total: tot + sz, system: sys + sz)
  end

  defp au_mem_blocks([{:blocks, l} | rest], mem0) do
    mem = au_mem_blocks_1(l, mem0)
    au_mem_blocks(rest, mem)
  end

  defp au_mem_blocks([_ | rest], mem), do: au_mem_blocks(rest, mem)

  defp au_mem_blocks([], mem), do: mem

  defp au_mem_blocks_1([{type, sizeList} | rest], mem), do: au_mem_blocks_1(rest, au_mem_acc(mem, type, sizeList))

  defp au_mem_blocks_1([], mem), do: mem

  defp au_mem_current(mem, type, [{:mbcs_pool, stats} | rest]), do: au_mem_current(au_mem_blocks(stats, mem), type, rest)

  defp au_mem_current(mem, type, [{:mbcs, stats} | rest]), do: au_mem_current(au_mem_blocks(stats, mem), type, rest)

  defp au_mem_current(mem, type, [{:sbcs, stats} | rest]), do: au_mem_current(au_mem_blocks(stats, mem), type, rest)

  defp au_mem_current(mem, type, [_ | rest]), do: au_mem_current(mem, type, rest)

  defp au_mem_current(mem, _Type, []), do: mem

  defp au_mem_data(allocs) do
    ref = :erlang.make_ref()
    :erlang.system_info({:memory_internal, ref, allocs})
    receive_emd(ref)
  end

  defp au_mem_data(:notsup, _), do: :notsup

  defp au_mem_data(_, [{_, false} | _]), do: :notsup

  defp au_mem_data(memory() = mem0, [{:fix_alloc, _, data} | rest]) do
    mem = au_mem_fix(mem0, data)
    au_mem_data(au_mem_current(mem, :fix_alloc, data), rest)
  end

  defp au_mem_data(memory() = mem, [{type, _, data} | rest]), do: au_mem_data(au_mem_current(mem, type, data), rest)

  defp au_mem_data(eMD, []), do: eMD

  defp au_mem_fix(memory(processes: proc, processes_used: procU, system: sys) = mem, data) do
    case fix_proc(data, {0, 0}) do
      {a, u} ->
        memory(mem, processes: proc + a, processes_used: procU + u, system: sys - a)
      {mask, a, u} ->
        memory(mem, processes: mask &&& proc + a, processes_used: mask &&& procU + u, system: mask &&& sys - a)
    end
  end

  defp cput_e2i(:undefined), do: :undefined

  defp cput_e2i(e), do: rvrs(cput_e2i(e, -1, -1, cpu(), 0, cput_e2i_clvl(e, 0), []))

  defp cput_e2i([], _NId, _PId, _IS, _PLvl, _Lvl, res), do: res

  defp cput_e2i([e | es], nId0, pId, iS, pLvl, lvl, res0) do
    case cput_e2i(e, nId0, pId, iS, pLvl, lvl, res0) do
      [] ->
        cput_e2i(es, nId0, pId, iS, pLvl, lvl, res0)
      [cpu(node: n, processor: p, processor_node: pN) = cPU | _] = res1 ->
        nId1 = case n > pN do
          true ->
            n
          false ->
            pN
        end
        cput_e2i(es, nId1, p, cPU, pLvl, lvl, res1)
    end
  end

  defp cput_e2i({tag, [], tagList}, nid, pId, cPU, pLvl, lvl, res), do: cput_e2i({tag, tagList}, nid, pId, cPU, pLvl, lvl, res)

  defp cput_e2i({:node, nL}, nid0, pId, _CPU, 0, cpu(:node), res) do
    nid1 = nid0 + 1
    lvl = cput_e2i_clvl(nL, cpu(:node))
    cput_e2i(nL, nid1, pId, cpu(node: nid1), cpu(:node), lvl, res)
  end

  defp cput_e2i({:processor, pL}, nid, pId0, _CPU, 0, cpu(:node), res) do
    pId1 = pId0 + 1
    lvl = cput_e2i_clvl(pL, cpu(:processor))
    cput_e2i(pL, nid, pId1, cpu(processor: pId1), cpu(:processor), lvl, res)
  end

  defp cput_e2i({:processor, pL}, nid, pId0, cPU, pLvl, cLvl, res) when pLvl < cpu(:processor) and cLvl <= cpu(:processor) do
    pId1 = pId0 + 1
    lvl = cput_e2i_clvl(pL, cpu(:processor))
    cput_e2i(pL, nid, pId1, cpu(cPU, processor: pId1, processor_node: -1, core: -1, thread: -1), cpu(:processor), lvl, res)
  end

  defp cput_e2i({:node, nL}, nid0, pId, cPU, cpu(:processor), cpu(:processor_node), res) do
    nid1 = nid0 + 1
    lvl = cput_e2i_clvl(nL, cpu(:processor_node))
    cput_e2i(nL, nid1, pId, cpu(cPU, processor_node: nid1), cpu(:processor_node), lvl, res)
  end

  defp cput_e2i({:core, cL}, nid, pId, cpu(core: c0) = cPU, pLvl, cpu(:core), res) when pLvl < cpu(:core) do
    lvl = cput_e2i_clvl(cL, cpu(:core))
    cput_e2i(cL, nid, pId, cpu(cPU, core: c0 + 1, thread: -1), cpu(:core), lvl, res)
  end

  defp cput_e2i({:thread, tL}, nid, pId, cpu(thread: t0) = cPU, pLvl, cpu(:thread), res) when pLvl < cpu(:thread) do
    lvl = cput_e2i_clvl(tL, cpu(:thread))
    cput_e2i(tL, nid, pId, cpu(cPU, thread: t0 + 1), cpu(:thread), lvl, res)
  end

  defp cput_e2i({:logical, iD}, _Nid, pId, cpu(processor: p, core: c, thread: t) = cPU, pLvl, cpu(:logical), res) when pLvl < cpu(:logical) and :erlang.is_integer(iD) and 0 <= iD and iD < 65536 do
    [cpu(cPU, processor: case p do
      -1 ->
        pId + 1
      _ ->
        p
    end, core: case c do
      -1 ->
        0
      _ ->
        c
    end, thread: case t do
      -1 ->
        0
      _ ->
        t
    end, logical: iD) | res]
  end

  defp cput_e2i_clvl({:logical, _}, _PLvl), do: cpu(:logical)

  defp cput_e2i_clvl([e | _], pLvl) do
    case :erlang.element(1, e) do
      :node ->
        case pLvl do
          0 ->
            cpu(:node)
          cpu(:processor) ->
            cpu(:processor_node)
        end
      :processor ->
        case pLvl do
          0 ->
            cpu(:node)
          cpu(:node) ->
            cpu(:processor)
        end
      :core ->
        cpu(:core)
      :thread ->
        cpu(:thread)
    end
  end

  defp cput_i2e(:undefined), do: :undefined

  defp cput_i2e(is), do: cput_i2e(is, true, cpu(:node), cput_i2e_tag_map())

  defp cput_i2e([], _Frst, _Lvl, _TM), do: []

  defp cput_i2e([cpu(logical: lID) | _], _Frst, lvl, _TM) when lvl == cpu(:logical), do: {:logical, lID}

  defp cput_i2e([cpu() = i | is], frst, lvl, tM), do: cput_i2e(:erlang.element(lvl, i), frst, is, [i], lvl, tM)

  defp cput_i2e(v, frst, [i | is], sameV, lvl, tM) when v === :erlang.element(lvl, i), do: cput_i2e(v, frst, is, [i | sameV], lvl, tM)

  defp cput_i2e(-1, true, [], sameV, lvl, tM), do: cput_i2e(rvrs(sameV), true, lvl + 1, tM)

  defp cput_i2e(_V, true, [], sameV, lvl, tM) when lvl !== cpu(:processor) and lvl !== cpu(:processor_node), do: cput_i2e(rvrs(sameV), true, lvl + 1, tM)

  defp cput_i2e(-1, _Frst, is, sameV, cpu(:node), tM), do: cput_i2e(rvrs(sameV), true, cpu(:processor), tM) ++ cput_i2e(is, false, cpu(:node), tM)

  defp cput_i2e(_V, _Frst, is, sameV, lvl, tM), do: [{cput_i2e_tag(lvl, tM), cput_i2e(rvrs(sameV), true, lvl + 1, tM)} | cput_i2e(is, false, lvl, tM)]

  defp cput_i2e_tag(lvl, tM) do
    case :erlang.element(lvl, tM) do
      :processor_node ->
        :node
      other ->
        other
    end
  end

  defp cput_i2e_tag_map(), do: :erlang.list_to_tuple([:cpu | record_info(:fields, :cpu)])

  defp fix_proc([{:fix_types, sizeList} | _Rest], acc), do: get_fix_proc(sizeList, acc)

  defp fix_proc([{:fix_types, mask, sizeList} | _Rest], acc) do
    {a, u} = get_fix_proc(sizeList, acc)
    {mask, a, u}
  end

  defp fix_proc([_ | rest], acc), do: fix_proc(rest, acc)

  defp fix_proc([], acc), do: acc

  defp fun_info_1([k | ks], fun, a) do
    case :erlang.fun_info(fun, k) do
      {k, :undefined} ->
        fun_info_1(ks, fun, a)
      {k, _} = p ->
        fun_info_1(ks, fun, [p | a])
    end
  end

  defp fun_info_1([], _, a), do: a

  defp gc_info(_Ref, 0, {colls, recl}), do: {colls, recl, 0}

  defp gc_info(ref, n, {origColls, origRecl}) do
    receive do
    {^ref, {_, colls, recl}} ->
        gc_info(ref, n - 1, {colls + origColls, recl + origRecl})
    end
  end

  defp get_alloc_info(type, aAtom) when :erlang.is_atom(aAtom) do
    [{^aAtom, result}] = get_alloc_info(type, [aAtom])
    result
  end

  defp get_alloc_info(type, aList) when :erlang.is_list(aList) do
    ref = :erlang.make_ref()
    :erlang.system_info({type, ref, aList})
    receive_allocator(ref, :erlang.system_info(:schedulers), mk_res_list(aList))
  end

  defp get_fix_proc([{procType, a1, u1} | rest], {a0, u0}) when procType == :proc or procType == :monitor or procType == :link or procType == :msg_ref or procType == :ll_ptimer or procType == :hl_ptimer or procType == :bif_timer or procType == :accessor_bif_timer, do: get_fix_proc(rest, {a0 + a1, u0 + u1})

  defp get_fix_proc([_ | rest], acc), do: get_fix_proc(rest, acc)

  defp get_fix_proc([], acc), do: acc

  defp get_gc_opts([{:async, _ReqId} = asyncTuple | options], gcOpt = gcopt()), do: get_gc_opts(options, gcopt(gcOpt, async: asyncTuple))

  defp get_gc_opts([{:type, t} | options], gcOpt = gcopt()), do: get_gc_opts(options, gcopt(gcOpt, type: t))

  defp get_gc_opts([], gcOpt), do: gcOpt

  defp get_memval(:total, memory(total: v)), do: v

  defp get_memval(:processes, memory(processes: v)), do: v

  defp get_memval(:processes_used, memory(processes_used: v)), do: v

  defp get_memval(:system, memory(system: v)), do: v

  defp get_memval(:atom, memory(atom: v)), do: v

  defp get_memval(:atom_used, memory(atom_used: v)), do: v

  defp get_memval(:binary, memory(binary: v)), do: v

  defp get_memval(:code, memory(code: v)), do: v

  defp get_memval(:ets, memory(ets: v)), do: v

  defp get_memval(_, memory()), do: :erlang.error(:badarg)

  defp insert_info([], ys), do: ys

  defp insert_info([{a, false} | xs], [{^a, _IList} | ys]), do: insert_info(xs, [{a, false} | ys])

  defp insert_info([{a, n, i} | xs], [{^a, iList} | ys]), do: insert_info(xs, [{a, insert_instance(i, n, iList)} | ys])

  defp insert_info([{a1, _} | _] = xs, [{a2, _} = y | ys]) when a1 != a2, do: [y | insert_info(xs, ys)]

  defp insert_info([{a1, _, _} | _] = xs, [{a2, _} = y | ys]) when a1 != a2, do: [y | insert_info(xs, ys)]

  defp insert_instance(i, n, rest) when :erlang.is_atom(n), do: [{n, i} | rest]

  defp insert_instance(i, n, []), do: [{:instance, n, i}]

  defp insert_instance(i, n, [{:instance, m, _} | _] = rest) when n < m, do: [{:instance, n, i} | rest]

  defp insert_instance(i, n, [prev | rest]), do: [prev | insert_instance(i, n, rest)]

  defp memory_1([type | types], mem), do: [{type, get_memval(type, mem)} | memory_1(types, mem)]

  defp memory_1([], _Mem), do: []

  defp mk_res_list([]), do: []

  defp mk_res_list([alloc | rest]), do: [{alloc, []} | mk_res_list(rest)]

  defp old_remote_spawn_opt(n, m, f, a, o) do
    case :lists.member(:monitor, o) do
      true ->
        :badarg
      _ ->
        {l, nO} = :lists.foldl(fn :link, {_, newOpts} ->
            {:link, newOpts}
          opt, {lO, newOpts} ->
            {lO, [opt | newOpts]}
        end, {:no_link, []}, o)
        try do
          :gen_server.call({:net_kernel, n}, {:spawn_opt, m, f, a, nO, l, :erlang.group_leader()}, :infinity)
        catch
          error -> error
        end
        |> case do
          pid when :erlang.is_pid(pid) ->
            pid
          error ->
            case remote_spawn_error(error, {l, n, m, f, a, nO}) do
              {:fault, fault} ->
                fault
              pid ->
                pid
            end
        end
    end
  end

  defp receive_allocator(_Ref, 0, acc), do: acc

  defp receive_allocator(ref, n, acc) do
    receive do
    {^ref, _, infoList} ->
        receive_allocator(ref, n - 1, insert_info(infoList, acc))
    end
  end

  defp receive_emd(ref), do: receive_emd(ref, memory(), :erlang.system_info(:schedulers))

  defp receive_emd(_Ref, eMD, 0), do: eMD

  defp receive_emd(ref, eMD, n) do
    receive do
    {^ref, _, data} ->
        receive_emd(ref, au_mem_data(eMD, data), n - 1)
    end
  end

  defp remote_spawn_error({:EXIT, {{:nodedown, n}, _}}, {l, ^n, m, f, a, o}) do
    {opts, lL} = case l === :link do
      true ->
        {[:link | o], [:link]}
      false ->
        {o, []}
    end
    :erlang.spawn_opt(:erts_internal, :crasher, [n, m, f, a, opts, :noconnection], lL)
  end

  defp remote_spawn_error({:EXIT, {reason, _}}, _), do: {:fault, reason}

  defp remote_spawn_error({:EXIT, reason}, _), do: {:fault, reason}

  defp remote_spawn_error(other, _), do: {:fault, other}

  defp rvrs([_] = l), do: l

  defp rvrs(xs), do: rvrs(xs, [])

  defp rvrs([], ys), do: ys

  defp rvrs([x | xs], ys), do: rvrs(xs, [x | ys])
end
