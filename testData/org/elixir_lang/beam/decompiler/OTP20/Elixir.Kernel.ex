# Source code recreated from a .beam file by IntelliJ Elixir
defmodule Kernel do

  # Macros

  defmacro unquote(:!)(arg1) do
    # body not decompiled
  end

  defmacro left && right do
    # body not decompiled
  end

  defmacro left .. right do
    # body not decompiled
  end

  defmacro left <> right do
    # body not decompiled
  end

  defmacro unquote(:@)(arg1) do
    # body not decompiled
  end

  defmacro alias!(arg1) do
    # body not decompiled
  end

  defmacro left and right do
    # body not decompiled
  end

  defmacro binding() do
    # body not decompiled
  end

  defmacro binding(arg1) do
    # body not decompiled
  end

  defmacro def(arg1) do
    # body not decompiled
  end

  defmacro def(arg1, arg2) do
    # body not decompiled
  end

  defmacro defdelegate(arg1, arg2) do
    # body not decompiled
  end

  defmacro defexception(arg1) do
    # body not decompiled
  end

  defmacro defimpl(arg1, arg2) do
    # body not decompiled
  end

  defmacro defimpl(arg1, arg2, arg3) do
    # body not decompiled
  end

  defmacro defmacro(arg1) do
    # body not decompiled
  end

  defmacro defmacro(arg1, arg2) do
    # body not decompiled
  end

  defmacro defmacrop(arg1) do
    # body not decompiled
  end

  defmacro defmacrop(arg1, arg2) do
    # body not decompiled
  end

  defmacro defmodule(arg1, arg2) do
    # body not decompiled
  end

  defmacro defoverridable(arg1) do
    # body not decompiled
  end

  defmacro defp(arg1) do
    # body not decompiled
  end

  defmacro defp(arg1, arg2) do
    # body not decompiled
  end

  defmacro defprotocol(arg1, arg2) do
    # body not decompiled
  end

  defmacro defstruct(arg1) do
    # body not decompiled
  end

  defmacro destructure(arg1, arg2) do
    # body not decompiled
  end

  defmacro get_and_update_in(arg1, arg2) do
    # body not decompiled
  end

  defmacro if(arg1, arg2) do
    # body not decompiled
  end

  defmacro left in right do
    # body not decompiled
  end

  defmacro is_nil(arg1) do
    # body not decompiled
  end

  defmacro match?(arg1, arg2) do
    # body not decompiled
  end

  defmacro left or right do
    # body not decompiled
  end

  defmacro pop_in(arg1) do
    # body not decompiled
  end

  defmacro put_in(arg1, arg2) do
    # body not decompiled
  end

  defmacro raise(arg1) do
    # body not decompiled
  end

  defmacro raise(arg1, arg2) do
    # body not decompiled
  end

  defmacro reraise(arg1, arg2) do
    # body not decompiled
  end

  defmacro reraise(arg1, arg2, arg3) do
    # body not decompiled
  end

  defmacro sigil_C(arg1, arg2) do
    # body not decompiled
  end

  defmacro sigil_D(arg1, arg2) do
    # body not decompiled
  end

  defmacro sigil_N(arg1, arg2) do
    # body not decompiled
  end

  defmacro sigil_R(arg1, arg2) do
    # body not decompiled
  end

  defmacro sigil_S(arg1, arg2) do
    # body not decompiled
  end

  defmacro sigil_T(arg1, arg2) do
    # body not decompiled
  end

  defmacro sigil_W(arg1, arg2) do
    # body not decompiled
  end

  defmacro sigil_c(arg1, arg2) do
    # body not decompiled
  end

  defmacro sigil_r(arg1, arg2) do
    # body not decompiled
  end

  defmacro sigil_s(arg1, arg2) do
    # body not decompiled
  end

  defmacro sigil_w(arg1, arg2) do
    # body not decompiled
  end

  defmacro to_char_list(arg1) do
    # body not decompiled
  end

  defmacro to_charlist(arg1) do
    # body not decompiled
  end

  defmacro to_string(arg1) do
    # body not decompiled
  end

  defmacro unless(arg1, arg2) do
    # body not decompiled
  end

  defmacro update_in(arg1, arg2) do
    # body not decompiled
  end

  defmacro use(arg1) do
    # body not decompiled
  end

  defmacro use(arg1, arg2) do
    # body not decompiled
  end

  defmacro var!(arg1) do
    # body not decompiled
  end

  defmacro var!(arg1, arg2) do
    # body not decompiled
  end

  defmacro left |> right do
    # body not decompiled
  end

  defmacro left || right do
    # body not decompiled
  end

  # Functions

  @spec term() != term() :: boolean()
  def left_1 != right_1, do: left_1 != right_1

  @spec term() !== term() :: boolean()
  def left_1 !== right_1, do: left_1 !== right_1

  @spec float() * integer() :: float()
  @spec integer() * float() :: float()
  @spec float() * float() :: float()
  @spec integer() * integer() :: integer()
  def left_1 * right_1, do: left_1 * right_1

  @spec (+value) :: value when value: number()
  def (+value_1), do: +value_1

  @spec float() + integer() :: float()
  @spec integer() + float() :: float()
  @spec float() + float() :: float()
  @spec integer() + integer() :: integer()
  def left_1 + right_1, do: left_1 + right_1

  @spec [] ++ term() :: maybe_improper_list()
  def left_1 ++ right_1, do: left_1 ++ right_1

  @spec (-float()) :: float()
  @spec (-neg_integer()) :: pos_integer()
  @spec (-pos_integer()) :: neg_integer()
  @spec (-0) :: 0
  def (-value_1), do: -value_1

  @spec float() - integer() :: float()
  @spec integer() - float() :: float()
  @spec float() - float() :: float()
  @spec integer() - integer() :: integer()
  def left_1 - right_1, do: left_1 - right_1

  @spec [] -- [] :: []
  def left_1 -- right_1, do: left_1 -- right_1

  @spec number() / number() :: float()
  def left_1 / right_1, do: left_1 / right_1

  @spec term() < term() :: boolean()
  def left_1 < right_1, do: left_1 < right_1

  @spec term() <= term() :: boolean()
  def left_1 <= right_1, do: left_1 <= right_1

  @spec term() == term() :: boolean()
  def left_1 == right_1, do: left_1 == right_1

  @spec term() === term() :: boolean()
  def left_1 === right_1, do: left_1 === right_1

  @spec String.t() =~ (String.t() | Regex.t()) :: boolean()
  def left_1 =~ <<>> when :erlang.is_binary(left_1), do: true

  def left_1 =~ right_1 when :erlang.is_binary(left_1) and :erlang.is_binary(right_1), do: :binary.match(left_1, right_1) != :nomatch

  def left_1 =~ right_1 when :erlang.is_binary(left_1), do: Regex.match?(right_1, left_1)

  @spec term() > term() :: boolean()
  def left_1 > right_1, do: left_1 > right_1

  @spec term() >= term() :: boolean()
  def left_1 >= right_1, do: left_1 >= right_1

  @spec __info__((:attributes | :compile | :exports | :functions | :macros | :md5 | :module | :native_addresses)) :: (atom() | [({atom(), any()} | {atom(), byte(), integer()})])
  def __info__(:functions), do: [{:!=, 2}, {:!==, 2}, {:*, 2}, {:+, 1}, {:+, 2}, {:++, 2}, {:-, 1}, {:-, 2}, {:--, 2}, {:/, 2}, {:<, 2}, {:<=, 2}, {:==, 2}, {:===, 2}, {:=~, 2}, {:>, 2}, {:>=, 2}, {:abs, 1}, {:apply, 2}, {:apply, 3}, {:binary_part, 3}, {:bit_size, 1}, {:byte_size, 1}, {:div, 2}, {:elem, 2}, {:exit, 1}, {:function_exported?, 3}, {:get_and_update_in, 3}, {:get_in, 2}, {:hd, 1}, {:inspect, 1}, {:inspect, 2}, {:is_atom, 1}, {:is_binary, 1}, {:is_bitstring, 1}, {:is_boolean, 1}, {:is_float, 1}, {:is_function, 1}, {:is_function, 2}, {:is_integer, 1}, {:is_list, 1}, {:is_map, 1}, {:is_number, 1}, {:is_pid, 1}, {:is_port, 1}, {:is_reference, 1}, {:is_tuple, 1}, {:length, 1}, {:macro_exported?, 3}, {:make_ref, 0}, {:map_size, 1}, {:max, 2}, {:min, 2}, {:node, 0}, {:node, 1}, {:not, 1}, {:pop_in, 2}, {:put_elem, 3}, {:put_in, 3}, {:rem, 2}, {:round, 1}, {:self, 0}, {:send, 2}, {:spawn, 1}, {:spawn, 3}, {:spawn_link, 1}, {:spawn_link, 3}, {:spawn_monitor, 1}, {:spawn_monitor, 3}, {:struct, 1}, {:struct, 2}, {:struct!, 1}, {:struct!, 2}, {:throw, 1}, {:tl, 1}, {:trunc, 1}, {:tuple_size, 1}, {:update_in, 3}]

  def __info__(:macros), do: [{:!, 1}, {:&&, 2}, {:.., 2}, {:<>, 2}, {:@, 1}, {:alias!, 1}, {:and, 2}, {:binding, 0}, {:binding, 1}, {:def, 1}, {:def, 2}, {:defdelegate, 2}, {:defexception, 1}, {:defimpl, 2}, {:defimpl, 3}, {:defmacro, 1}, {:defmacro, 2}, {:defmacrop, 1}, {:defmacrop, 2}, {:defmodule, 2}, {:defoverridable, 1}, {:defp, 1}, {:defp, 2}, {:defprotocol, 2}, {:defstruct, 1}, {:destructure, 2}, {:get_and_update_in, 2}, {:if, 2}, {:in, 2}, {:is_nil, 1}, {:match?, 2}, {:or, 2}, {:pop_in, 1}, {:put_in, 2}, {:raise, 1}, {:raise, 2}, {:reraise, 2}, {:reraise, 3}, {:sigil_C, 2}, {:sigil_D, 2}, {:sigil_N, 2}, {:sigil_R, 2}, {:sigil_S, 2}, {:sigil_T, 2}, {:sigil_W, 2}, {:sigil_c, 2}, {:sigil_r, 2}, {:sigil_s, 2}, {:sigil_w, 2}, {:to_char_list, 1}, {:to_charlist, 1}, {:to_string, 1}, {:unless, 2}, {:update_in, 2}, {:use, 1}, {:use, 2}, {:var!, 1}, {:var!, 2}, {:|>, 2}, {:||, 2}]

  def __info__(info), do: :erlang.get_module_info(Kernel, info)

  @spec abs(number()) :: number()
  def abs(number_1), do: :erlang.abs(number_1)

  @spec apply(function(), [any()]) :: any()
  def apply(fun_1, args_1), do: :erlang.apply(fun_1, args_1)

  @spec apply(module(), atom(), [any()]) :: any()
  def apply(module_1, fun_1, args_1), do: :erlang.apply(module_1, fun_1, args_1)

  @spec binary_part(binary(), pos_integer(), integer()) :: binary()
  def binary_part(binary_1, start_1, length_1), do: :erlang.binary_part(binary_1, start_1, length_1)

  @spec bit_size(bitstring()) :: non_neg_integer()
  def bit_size(bitstring_1), do: :erlang.bit_size(bitstring_1)

  @spec byte_size(bitstring()) :: non_neg_integer()
  def byte_size(bitstring_1), do: :erlang.byte_size(bitstring_1)

  @spec div(integer(), (neg_integer() | pos_integer())) :: integer()
  def div(dividend_1, divisor_1), do: div(dividend_1, divisor_1)

  @spec elem(tuple(), non_neg_integer()) :: term()
  def elem(tuple_1, index_1), do: :erlang.element(index_1 + 1, tuple_1)

  @spec exit(term()) :: no_return()
  def exit(reason_1), do: :erlang.exit(reason_1)

  @spec function_exported?(module(), atom(), arity()) :: boolean()
  def function_exported?(module_1, function_1, arity_1), do: :erlang.function_exported(module_1, function_1, arity_1)

  @spec get_and_update_in(structure :: Access.t(), keys, (term() -> ({get_value, update_value} | :pop))) :: {get_value, structure :: Access.t()} when keys: [any(), ...], update_value: term()
  def get_and_update_in(data_1, [head_1], fun_1) when :erlang.is_function(head_1, 3), do: head_1.(:get_and_update, data_1, fun_1)

  def get_and_update_in(data_1, [head_1 | tail_1], fun_1) when :erlang.is_function(head_1, 3) do
    head_1.(:get_and_update, data_1, fn __1 ->
        Kernel.get_and_update_in(__1, tail_1, fun_1)
    end)
  end

  def get_and_update_in(data_1, [head_1], fun_1) when :erlang.is_function(fun_1, 1), do: Access.get_and_update(data_1, head_1, fun_1)

  def get_and_update_in(data_1, [head_1 | tail_1], fun_1) when :erlang.is_function(fun_1, 1) do
    Access.get_and_update(data_1, head_1, fn __1 ->
        Kernel.get_and_update_in(__1, tail_1, fun_1)
    end)
  end

  @spec get_in(Access.t(), [term(), ...]) :: term()
  def get_in(data_1, [h_1]) when :erlang.is_function(h_1) do
    h_1.(:get, data_1, fn __1 ->
        __1
    end)
  end

  def get_in(data_1, [h_1 | t_1]) when :erlang.is_function(h_1) do
    h_1.(:get, data_1, fn __1 ->
        Kernel.get_in(__1, t_1)
    end)
  end

  def get_in(nil, [_]), do: nil

  def get_in(nil, [_ | t_1]), do: Kernel.get_in(nil, t_1)

  def get_in(data_1, [h_1]), do: Access.get(data_1, h_1)

  def get_in(data_1, [h_1 | t_1]), do: Kernel.get_in(Access.get(data_1, h_1), t_1)

  @spec hd(nonempty_maybe_improper_list(elem, any())) :: elem when elem: term()
  def hd(list_1), do: :erlang.hd(list_1)

  def inspect(x0_1), do: inspect(x0_1, [])

  @spec inspect(Inspect.t(), Keyword.t()) :: String.t()
  def inspect(arg_1, opts_1) when :erlang.is_list(opts_1) do
    opts_2 = Kernel.struct(Inspect.Opts, opts_1)
    limit_1 = case opts_2 do
      %{:pretty => __1} ->
        __1
      __1 when :erlang.is_map(__1) ->
        :erlang.error({:badkey, :pretty, __1})
      __1 ->
        __1.pretty()
    end
    |> case do
      true ->
        case opts_2 do
          %{:width => __2} ->
            __2
          __2 when :erlang.is_map(__2) ->
            :erlang.error({:badkey, :width, __2})
          __2 ->
            __2.width()
        end
      false ->
        :infinity
    end
    :erlang.iolist_to_binary(Inspect.Algebra.format(Inspect.Algebra.to_doc(arg_1, opts_2), limit_1))
  end

  @spec is_atom(term()) :: boolean()
  def is_atom(term_1), do: :erlang.is_atom(term_1)

  @spec is_binary(term()) :: boolean()
  def is_binary(term_1), do: :erlang.is_binary(term_1)

  @spec is_bitstring(term()) :: boolean()
  def is_bitstring(term_1), do: :erlang.is_bitstring(term_1)

  @spec is_boolean(term()) :: boolean()
  def is_boolean(term_1), do: :erlang.is_boolean(term_1)

  @spec is_float(term()) :: boolean()
  def is_float(term_1), do: :erlang.is_float(term_1)

  @spec is_function(term()) :: boolean()
  def is_function(term_1), do: :erlang.is_function(term_1)

  @spec is_function(term(), non_neg_integer()) :: boolean()
  def is_function(term_1, arity_1), do: :erlang.is_function(term_1, arity_1)

  @spec is_integer(term()) :: boolean()
  def is_integer(term_1), do: :erlang.is_integer(term_1)

  @spec is_list(term()) :: boolean()
  def is_list(term_1), do: :erlang.is_list(term_1)

  @spec is_map(term()) :: boolean()
  def is_map(term_1), do: :erlang.is_map(term_1)

  @spec is_number(term()) :: boolean()
  def is_number(term_1), do: :erlang.is_number(term_1)

  @spec is_pid(term()) :: boolean()
  def is_pid(term_1), do: :erlang.is_pid(term_1)

  @spec is_port(term()) :: boolean()
  def is_port(term_1), do: :erlang.is_port(term_1)

  @spec is_reference(term()) :: boolean()
  def is_reference(term_1), do: :erlang.is_reference(term_1)

  @spec is_tuple(term()) :: boolean()
  def is_tuple(term_1), do: :erlang.is_tuple(term_1)

  @spec length([]) :: non_neg_integer()
  def length(list_1), do: :erlang.length(list_1)

  @spec macro_exported?(module(), atom(), arity()) :: boolean()
  def macro_exported?(module_1, macro_1, arity_1) when :erlang.is_atom(module_1) and :erlang.is_atom(macro_1) and :erlang.is_integer(arity_1) and arity_1 >= 0 and arity_1 <= 255 do
    case Kernel.function_exported?(module_1, :__info__, 1) do
      true ->
        :lists.member({macro_1, arity_1}, module_1.__info__(:macros))
      false ->
        false
      __1 ->
        :erlang.error({:badbool, :and, __1})
    end
  end

  @spec make_ref() :: reference()
  def make_ref(), do: :erlang.make_ref()

  @spec map_size(map()) :: non_neg_integer()
  def map_size(map_1), do: :erlang.map_size(map_1)

  @spec max(first, second) :: (first | second) when first: term(), second: term()
  def max(first_1, second_1), do: :erlang.max(first_1, second_1)

  @spec min(first, second) :: (first | second) when first: term(), second: term()
  def min(first_1, second_1), do: :erlang.min(first_1, second_1)

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @spec node() :: node()
  def node(), do: :erlang.node()

  @spec node((pid() | reference() | port())) :: node()
  def node(arg_1), do: :erlang.node(arg_1)

  @spec unquote(:not)(false) :: true
  @spec unquote(:not)(true) :: false
  def unquote(:not)(arg_1), do: not arg_1

  @spec pop_in(Access.t(), [term(), ...]) :: {term(), Access.t()}
  def pop_in(nil, [h_1 | _]), do: Access.pop(nil, h_1)

  def pop_in(data_1, keys_1), do: do_pop_in(data_1, keys_1)

  @spec put_elem(tuple(), non_neg_integer(), term()) :: tuple()
  def put_elem(tuple_1, index_1, value_1), do: :erlang.setelement(index_1 + 1, tuple_1, value_1)

  @spec put_in(Access.t(), [term(), ...], term()) :: Access.t()
  def put_in(data_1, keys_1, value_1) do
    :erlang.element(2, Kernel.get_and_update_in(data_1, keys_1, fn _ ->
        {nil, value_1}
    end))
  end

  @spec rem(integer(), (neg_integer() | pos_integer())) :: integer()
  def rem(dividend_1, divisor_1), do: rem(dividend_1, divisor_1)

  @spec round(value) :: value when value: integer()
  @spec round(float()) :: integer()
  def round(number_1), do: :erlang.round(number_1)

  @spec self() :: pid()
  def self(), do: :erlang.self()

  @spec send(dest :: (pid() | port() | atom() | {atom(), node()}), msg) :: msg when msg: any()
  def send(dest_1, msg_1), do: :erlang.send(dest_1, msg_1)

  @spec spawn((() -> any())) :: pid()
  def spawn(fun_1), do: :erlang.spawn(fun_1)

  @spec spawn(module(), atom(), []) :: pid()
  def spawn(module_1, fun_1, args_1), do: :erlang.spawn(module_1, fun_1, args_1)

  @spec spawn_link((() -> any())) :: pid()
  def spawn_link(fun_1), do: :erlang.spawn_link(fun_1)

  @spec spawn_link(module(), atom(), []) :: pid()
  def spawn_link(module_1, fun_1, args_1), do: :erlang.spawn_link(module_1, fun_1, args_1)

  @spec spawn_monitor((() -> any())) :: {pid(), reference()}
  def spawn_monitor(fun_1), do: :erlang.spawn_monitor(fun_1)

  @spec spawn_monitor(module(), atom(), []) :: {pid(), reference()}
  def spawn_monitor(module_1, fun_1, args_1), do: :erlang.spawn_monitor(module_1, fun_1, args_1)

  def struct(x0_1), do: struct(x0_1, [])

  @spec struct((module() | :elixir.struct()), Enum.t()) :: :elixir.struct()
  def struct(struct_1, kv_1) do
    struct(struct_1, kv_1, fn {key_1, val_1}, acc_1 ->
        case :maps.is_key(key_1, acc_1) do
          true ->
            key_1 != :__struct__
          false ->
            false
          __1 ->
            :erlang.error({:badbool, :and, __1})
        end
        |> case do
          true ->
            :maps.put(key_1, val_1, acc_1)
          false ->
            acc_1
        end
    end)
  end

  def struct!(x0_1), do: struct!(x0_1, [])

  @spec struct!((module() | :elixir.struct()), Enum.t()) :: (:elixir.struct() | no_return())
  def struct!(struct_1, kv_1) when :erlang.is_atom(struct_1), do: struct_1.__struct__(kv_1)

  def struct!(struct_1, kv_1) when :erlang.is_map(struct_1) do
    struct(struct_1, kv_1, fn {:__struct__, _}, acc_1 ->
        acc_1
      {key_1, val_1}, acc_2 ->
        :maps.update(key_1, val_1, acc_2)
    end)
  end

  @spec throw(term()) :: no_return()
  def throw(term_1), do: :erlang.throw(term_1)

  @spec tl(nonempty_maybe_improper_list(elem, tail)) :: (maybe_improper_list(elem, tail) | tail) when elem: term(), tail: term()
  def tl(list_1), do: :erlang.tl(list_1)

  @spec trunc(float()) :: integer()
  @spec trunc(value) :: value when value: integer()
  def trunc(number_1), do: :erlang.trunc(number_1)

  @spec tuple_size(tuple()) :: non_neg_integer()
  def tuple_size(tuple_1), do: :erlang.tuple_size(tuple_1)

  @spec update_in(Access.t(), [term(), ...], (term() -> term())) :: Access.t()
  def update_in(data_1, keys_1, fun_1) when :erlang.is_function(fun_1, 1) do
    :erlang.element(2, Kernel.get_and_update_in(data_1, keys_1, fn x_1 ->
        {nil, fun_1.(x_1)}
    end))
  end

  # Private Functions

  defp unquote(:"-MACRO-binding/2-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-in/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-in/3-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-sigil_r/3-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-sigil_r/3-fun-1-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-use/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-MACRO-|>/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-do_pop_in/2-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-do_pop_in/2-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-do_pop_in/2-fun-2-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-expand_aliases/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-get_and_update_in/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-get_and_update_in/3-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-get_in/2-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-get_in/2-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-in_list/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-put_in/3-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-struct!/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-struct/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-update_in/3-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp alias_meta({:__aliases__, meta_1, _}), do: meta_1

  defp alias_meta(_), do: []

  defp assert_module_scope(env_1, fun_1, arity_1) do
    case env_1 do
      %{:module => __1} ->
        __1
      __1 when :erlang.is_map(__1) ->
        :erlang.error({:badkey, :module, __1})
      __1 ->
        __1.module()
    end
    |> case do
      nil ->
        :erlang.error(ArgumentError.exception(<<"cannot invoke ", (case fun_1 do
          __2 when :erlang.is_binary(__2) ->
            __2
          __3 ->
            String.Chars.to_string(__3)
        end) :: binary, "/", (case arity_1 do
          __4 when :erlang.is_binary(__4) ->
            __4
          __5 ->
            String.Chars.to_string(__5)
        end) :: binary, " outside module">>))
      _ ->
        :ok
    end
  end

  defp assert_no_function_scope(env_1, fun_1, arity_1) do
    case env_1 do
      %{:function => __1} ->
        __1
      __1 when :erlang.is_map(__1) ->
        :erlang.error({:badkey, :function, __1})
      __1 ->
        __1.function()
    end
    |> case do
      nil ->
        :ok
      _ ->
        :erlang.error(ArgumentError.exception(<<"cannot invoke ", (case fun_1 do
          __2 when :erlang.is_binary(__2) ->
            __2
          __3 ->
            String.Chars.to_string(__3)
        end) :: binary, "/", (case arity_1 do
          __4 when :erlang.is_binary(__4) ->
            __4
          __5 ->
            String.Chars.to_string(__5)
        end) :: binary, " inside function/macro">>))
    end
  end

  defp bootstrapped?(_), do: true

  defp build_if(condition_1, [{:do, do_clause_1}]), do: build_if(condition_1, [{:do, do_clause_1}, {:else, nil}])

  defp build_if(condition_1, [{:do, do_clause_1}, {:else, else_clause_1}]), do: optimize_boolean({:case, [], [condition_1, [{:do, [{:->, [], [[{:when, [], [{:x, [], Kernel}, {:in, [{:context, Kernel}, {:import, Kernel}], [{:x, [], Kernel}, [false, nil]]}]}], else_clause_1]}, {:->, [], [[{:_, [], Kernel}], do_clause_1]}]}]]})

  defp build_if(_condition_1, _arguments_1), do: :erlang.error(ArgumentError.exception(<<"invalid or duplicate keys for if, only \"do\" ", "and an optional \"else\" are permitted">>))

  defp build_unless(condition_1, [{:do, do_clause_1}]), do: build_unless(condition_1, [{:do, do_clause_1}, {:else, nil}])

  defp build_unless(condition_1, [{:do, do_clause_1}, {:else, else_clause_1}]), do: {:if, [{:context, Kernel}, {:import, Kernel}], [condition_1, [{:do, else_clause_1}, {:else, do_clause_1}]]}

  defp build_unless(_condition_1, _arguments_1), do: :erlang.error(ArgumentError.exception(<<"invalid or duplicate keys for unless, only \"do\" ", "and an optional \"else\" are permitted">>))

  defp comp(left_1, {:|, _, [h_1, t_1]}), do: {{:., [], [:erlang, :or]}, [], [{{:., [], [:erlang, :"=:="]}, [], [left_1, h_1]}, {:in, [{:context, Kernel}, {:import, Kernel}], [left_1, t_1]}]}

  defp comp(left_1, right_1), do: {{:., [], [:erlang, :"=:="]}, [], [left_1, right_1]}

  defp decreasing_compare(var_1, first_1, last_1), do: {:and, [{:context, Kernel}, {:import, Kernel}], [{{:., [], [:erlang, :"=<"]}, [], [var_1, first_1]}, {{:., [], [:erlang, :>=]}, [], [var_1, last_1]}]}

  defp define(kind_1, call_1, expr_1, env_1) do
    assert_module_scope(env_1, kind_1, 2)
    assert_no_function_scope(env_1, kind_1, 2)
    line_1 = case env_1 do
      %{:line => __1} ->
        __1
      __1 when :erlang.is_map(__1) ->
        :erlang.error({:badkey, :line, __1})
      __1 ->
        __1.line()
    end
    {call_2, unquoted_call_1} = :elixir_quote.escape(call_1, true)
    {expr_2, unquoted_expr_1} = :elixir_quote.escape(expr_1, true)
    check_clauses_1 = not case unquoted_expr_1 do
      true ->
        true
      false ->
        unquoted_call_1
      __2 ->
        :erlang.error({:badbool, :or, __2})
    end
    pos_1 = :elixir_locals.cache_env(env_1)
    {{:., [], [:elixir_def, :store_definition]}, [], [line_1, kind_1, check_clauses_1, call_2, expr_2, pos_1]}
  end

  defp do_at([arg_1], meta_1, name_1, function__1, env_1) do
    line_1 = case :lists.keymember(:context, 1, meta_1) do
      true ->
        nil
      false ->
        case env_1 do
          %{:line => __1} ->
            __1
          __1 when :erlang.is_map(__1) ->
            :erlang.error({:badkey, :line, __1})
          __1 ->
            __1.line()
        end
    end
    case function__1 do
      __2 when __2 != nil and __2 != false ->
        __10 = :erlang.error(ArgumentError.exception(<<"cannot set attribute @", (case name_1 do
          __3 when :erlang.is_binary(__3) ->
            __3
          __4 ->
            String.Chars.to_string(__4)
        end) :: binary, " inside function/macro">>))
        {stack_1, arg_2} = {nil, arg_1}
        __10
      _ ->
        case name_1 == :behavior do
          true ->
            __9 = :elixir_errors.warn((case env_1 do
              %{:line => __5} ->
                __5
              __5 when :erlang.is_map(__5) ->
                :erlang.error({:badkey, :line, __5})
              __5 ->
                __5.line()
            end), (case env_1 do
              %{:file => __6} ->
                __6
              __6 when :erlang.is_map(__6) ->
                :erlang.error({:badkey, :file, __6})
              __6 ->
                __6.file()
            end), "@behavior attribute is not supported, please use @behaviour instead")
            {stack_1, arg_2} = {nil, arg_1}
            __9
          false ->
            case :lists.member(name_1, [:moduledoc, :typedoc, :doc]) do
              __7 when __7 != nil and __7 != false ->
                {stack_1, _} = :elixir_quote.escape(env_stacktrace(env_1), false)
                arg_2 = {case env_1 do
                  %{:line => __8} ->
                    __8
                  __8 when :erlang.is_map(__8) ->
                    :erlang.error({:badkey, :line, __8})
                  __8 ->
                    __8.line()
                end, arg_1}
                {{:., [], [{:__aliases__, [{:alias, false}], [:Module]}, :put_attribute]}, [], [{:__MODULE__, [], Kernel}, name_1, arg_2, stack_1, line_1]}
              _ ->
                {stack_1, arg_2} = {nil, arg_1}
                {{:., [], [{:__aliases__, [{:alias, false}], [:Module]}, :put_attribute]}, [], [{:__MODULE__, [], Kernel}, name_1, arg_1, nil, line_1]}
            end
        end
    end
  end

  defp do_at(args_1, _meta_1, name_1, function__1, env_1) when :erlang.is_atom(args_1) or args_1 == [] do
    stack_1 = env_stacktrace(env_1)
    doc_attr__1 = :lists.member(name_1, [:moduledoc, :typedoc, :doc])
    case function__1 do
      true ->
        value_1 = case Module.get_attribute((case env_1 do
          %{:module => __1} ->
            __1
          __1 when :erlang.is_map(__1) ->
            :erlang.error({:badkey, :module, __1})
          __1 ->
            __1.module()
        end), name_1, stack_1) do
          {_, doc_1} when doc_attr__1 ->
            doc_1
          __2 ->
            __2
        end
        __7 = try do
          :elixir_quote.escape(value_1, false)
        catch
          {:error, __3_1, _} when __3_1 == :badarg or :erlang.tuple_size(__3_1) == 2 and :erlang.element(1, __3_1) == :badarg ->
            ex_1 = Exception.normalize(:error, __3_1)
            :erlang.error(ArgumentError.exception(<<(<<"cannot inject attribute @", (case name_1 do
              __5 when :erlang.is_binary(__5) ->
                __5
              __6 ->
                String.Chars.to_string(__6)
            end) :: binary, " into function/macro because ">>) :: binary, Exception.message(ex_1) :: binary>>))
          {:error, %{:__struct__ => __4_1, :__exception__ => true} = __3_1, _} when __4_1 == ArgumentError ->
            ex_1 = Exception.normalize(:error, __3_1)
            :erlang.error(ArgumentError.exception(<<(<<"cannot inject attribute @", (case name_1 do
              __5 when :erlang.is_binary(__5) ->
                __5
              __6 ->
                String.Chars.to_string(__6)
            end) :: binary, " into function/macro because ">>) :: binary, Exception.message(ex_1) :: binary>>))
        else
          {val_1, _} ->
            val_1
        end
        escaped_1 = nil
        __7
      false ->
        {escaped_1, _} = :elixir_quote.escape(stack_1, false)
        value_1 = nil
        {:with, [], [{:<-, [], [{:when, [], [{{:_, [], Kernel}, {:doc, [], Kernel}}, doc_attr__1]}, {{:., [], [{:__aliases__, [{:alias, false}], [:Module]}, :get_attribute]}, [], [{:__MODULE__, [], Kernel}, name_1, escaped_1]}]}, [{:do, {:doc, [], Kernel}}]]}
    end
  end

  defp do_at(args_1, _meta_1, name_1, _function__1, _env_1) do
    :erlang.error(ArgumentError.exception(<<"expected 0 or 1 argument for @", (case name_1 do
      __1 when :erlang.is_binary(__1) ->
        __1
      __2 ->
        String.Chars.to_string(__2)
    end) :: binary, ", got: ", (case :erlang.length(args_1) do
      __3 when :erlang.is_binary(__3) ->
        __3
      __4 ->
        String.Chars.to_string(__4)
    end) :: binary>>))
  end

  defp do_pop_in(nil, [_ | _]), do: :pop

  defp do_pop_in(data_1, [h_1]) when :erlang.is_function(h_1) do
    h_1.(:get_and_update, data_1, fn _ ->
        :pop
    end)
  end

  defp do_pop_in(data_1, [h_1 | t_1]) when :erlang.is_function(h_1) do
    h_1.(:get_and_update, data_1, fn __1 ->
        do_pop_in(__1, t_1)
    end)
  end

  defp do_pop_in(data_1, [h_1]), do: Access.pop(data_1, h_1)

  defp do_pop_in(data_1, [h_1 | t_1]) do
    Access.get_and_update(data_1, h_1, fn __1 ->
        do_pop_in(__1, t_1)
    end)
  end

  defp env_stacktrace(env_1) do
    case bootstrapped?(Path) do
      true ->
        Macro.Env.stacktrace(env_1)
      false ->
        []
    end
  end

  defp expand_aliases({{:., _, [base_1, :{}]}, _, refs_1}, env_1) do
    base_2 = Macro.expand(base_1, env_1)
    Enum.map(refs_1, fn {:__aliases__, _, ref_1} ->
        Module.concat([base_2 | ref_1])
      ref_2 when :erlang.is_atom(ref_2) ->
        Module.concat(base_2, ref_2)
      other_1 ->
        other_1
    end)
  end

  defp expand_aliases(module_1, env_1), do: [Macro.expand(module_1, env_1)]

  defp expand_module(raw_1, _module_1, _env_1) when :erlang.is_atom(raw_1), do: raw_1

  defp expand_module({:__aliases__, _, [Elixir | t_1]}, module_1, _env_1) when t_1 != [], do: module_1

  defp expand_module({:__aliases__, _, _}, module_1, %{:module => nil}), do: module_1

  defp expand_module({:__aliases__, _, t_1}, _module_1, env_1) do
    :elixir_aliases.concat([case env_1 do
      %{:module => __1} ->
        __1
      __1 when :erlang.is_map(__1) ->
        :erlang.error({:badkey, :module, __1})
      __1 ->
        __1.module()
    end | t_1])
  end

  defp expand_module(_raw_1, module_1, env_1) do
    :elixir_aliases.concat([(case env_1 do
      %{:module => __1} ->
        __1
      __1 when :erlang.is_map(__1) ->
        :erlang.error({:badkey, :module, __1})
      __1 ->
        __1.module()
    end), module_1])
  end

  defp extract_concatenations({:<>, _, [left_1, right_1]}), do: [wrap_concatenation(left_1) | extract_concatenations(right_1)]

  defp extract_concatenations(other_1), do: [wrap_concatenation(other_1)]

  defp in_list(left_1, h_1, t_1) do
    :lists.foldr(fn x_1, acc_1 ->
        {{:., [], [:erlang, :or]}, [], [comp(left_1, x_1), acc_1]}
    end, comp(left_1, h_1), t_1)
  end

  defp in_range(left_1, first_1, last_1) do
    case :erlang.is_integer(first_1) do
      true ->
        :erlang.is_integer(last_1)
      false ->
        false
    end
    |> case do
      true ->
        in_range_literal(left_1, first_1, last_1)
      false ->
        {:and, [{:context, Kernel}, {:import, Kernel}], [{:and, [{:context, Kernel}, {:import, Kernel}], [{:and, [{:context, Kernel}, {:import, Kernel}], [{{:., [], [:erlang, :is_integer]}, [], [left_1]}, {{:., [], [:erlang, :is_integer]}, [], [first_1]}]}, {{:., [], [:erlang, :is_integer]}, [], [last_1]}]}, {:or, [{:context, Kernel}, {:import, Kernel}], [{:and, [{:context, Kernel}, {:import, Kernel}], [{{:., [], [:erlang, :"=<"]}, [], [first_1, last_1]}, increasing_compare(left_1, first_1, last_1)]}, {:and, [{:context, Kernel}, {:import, Kernel}], [{{:., [], [:erlang, :<]}, [], [last_1, first_1]}, decreasing_compare(left_1, first_1, last_1)]}]}]}
    end
  end

  defp in_range_literal(left_1, first_1, ^first_1), do: {{:., [], [:erlang, :"=:="]}, [], [left_1, first_1]}

  defp in_range_literal(left_1, first_1, last_1) when first_1 < last_1, do: {:and, [{:context, Kernel}, {:import, Kernel}], [{{:., [], [:erlang, :is_integer]}, [], [left_1]}, increasing_compare(left_1, first_1, last_1)]}

  defp in_range_literal(left_1, first_1, last_1), do: {:and, [{:context, Kernel}, {:import, Kernel}], [{{:., [], [:erlang, :is_integer]}, [], [left_1]}, decreasing_compare(left_1, first_1, last_1)]}

  defp in_var(false, ast_1, fun_1), do: fun_1.(ast_1)

  defp in_var(true, {atom_1, _, context_1} = var_1, fun_1) when :erlang.is_atom(atom_1) and :erlang.is_atom(context_1), do: fun_1.(var_1)

  defp in_var(true, ast_1, fun_1), do: {:__block__, [], [{:=, [], [{:var, [], Kernel}, ast_1]}, fun_1.({:var, [], Kernel})]}

  defp increasing_compare(var_1, first_1, last_1), do: {:and, [{:context, Kernel}, {:import, Kernel}], [{{:., [], [:erlang, :>=]}, [], [var_1, first_1]}, {{:., [], [:erlang, :"=<"]}, [], [var_1, last_1]}]}

  defp module_nesting(nil, full_1), do: {nil, full_1}

  defp module_nesting(prefix_1, full_1) do
    case split_module(prefix_1) do
      [] ->
        {nil, full_1}
      prefix_2 ->
        module_nesting(prefix_2, split_module(full_1), [], full_1)
    end
  end

  defp module_nesting([x_1 | t1_1], [^x_1 | t2_1], acc_1, full_1), do: module_nesting(t1_1, t2_1, [x_1 | acc_1], full_1)

  defp module_nesting([], [h_1 | _], acc_1, _full_1), do: {:erlang.binary_to_atom(<<"Elixir.", h_1 :: binary>>, :utf8), :elixir_aliases.concat(:lists.reverse([h_1 | acc_1]))}

  defp module_nesting(_, _, _acc_1, full_1), do: {nil, full_1}

  defp module_vars([{key_1, kind_1} | vars_1], counter_1) do
    var_1 = case :erlang.is_atom(kind_1) do
      true ->
        {key_1, [{:generated, true}], kind_1}
      false ->
        {key_1, [{:counter, kind_1}, {:generated, true}], nil}
    end
    under_1 = :erlang.binary_to_atom(<<"_@", :erlang.integer_to_binary(counter_1) :: binary>>, :utf8)
    args_1 = [key_1, kind_1, under_1, var_1]
    [{:{}, [], args_1} | module_vars(vars_1, counter_1 + 1)]
  end

  defp module_vars([], _counter_1), do: []

  defp nest_get_and_update_in([], fun_1), do: fun_1

  defp nest_get_and_update_in(list_1, fun_1), do: {:fn, [], [{:->, [], [[{:x, [], Kernel}], nest_get_and_update_in({:x, [], Kernel}, list_1, fun_1)]}]}

  defp nest_get_and_update_in(h_1, [{:access, key_1} | t_1], fun_1), do: {{:., [], [{:__aliases__, [{:alias, false}], [:Access]}, :get_and_update]}, [], [h_1, key_1, nest_get_and_update_in(t_1, fun_1)]}

  defp nest_get_and_update_in(h_1, [{:map, key_1} | t_1], fun_1), do: {{:., [], [{:__aliases__, [{:alias, false}], [:Map]}, :get_and_update!]}, [], [h_1, key_1, nest_get_and_update_in(t_1, fun_1)]}

  defp nest_pop_in(kind_1, list_1), do: {:fn, [], [{:->, [], [[{:x, [], Kernel}], nest_pop_in(kind_1, {:x, [], Kernel}, list_1)]}]}

  defp nest_pop_in(:map, h_1, [{:access, key_1}]), do: {:case, [], [h_1, [{:do, [{:->, [], [[nil], {nil, nil}]}, {:->, [], [[{:h, [], Kernel}], {{:., [], [{:__aliases__, [{:alias, false}], [:Access]}, :pop]}, [], [{:h, [], Kernel}, key_1]}]}]}]]}

  defp nest_pop_in(_, _, [{:map, key_1}]), do: :erlang.error(ArgumentError.exception(<<"cannot use pop_in when the last segment is a map/struct field. ", (<<"This would effectively remove the field ", Kernel.inspect(key_1) :: binary, " from the map/struct">>) :: binary>>))

  defp nest_pop_in(_, h_1, [{:map, key_1} | t_1]), do: {{:., [], [{:__aliases__, [{:alias, false}], [:Map]}, :get_and_update!]}, [], [h_1, key_1, nest_pop_in(:map, t_1)]}

  defp nest_pop_in(_, h_1, [{:access, key_1}]), do: {:case, [], [h_1, [{:do, [{:->, [], [[nil], :pop]}, {:->, [], [[{:h, [], Kernel}], {{:., [], [{:__aliases__, [{:alias, false}], [:Access]}, :pop]}, [], [{:h, [], Kernel}, key_1]}]}]}]]}

  defp nest_pop_in(_, h_1, [{:access, key_1} | t_1]), do: {{:., [], [{:__aliases__, [{:alias, false}], [:Access]}, :get_and_update]}, [], [h_1, key_1, nest_pop_in(:access, t_1)]}

  defp nest_update_in([], fun_1), do: fun_1

  defp nest_update_in(list_1, fun_1), do: {:fn, [], [{:->, [], [[{:x, [], Kernel}], nest_update_in({:x, [], Kernel}, list_1, fun_1)]}]}

  defp nest_update_in(h_1, [{:map, key_1} | t_1], fun_1), do: {{:., [], [{:__aliases__, [{:alias, false}], [:Map]}, :update!]}, [], [h_1, key_1, nest_update_in(t_1, fun_1)]}

  defp optimize_boolean({:case, meta_1, args_1}), do: {:case, [{:optimize_boolean, true} | meta_1], args_1}

  defp proper_start?({{:., _, [expr_1, _]}, _, _args_1}) when :erlang.is_atom(expr_1) or :erlang.element(1, expr_1) == :__aliases__ or :erlang.element(1, expr_1) == :__MODULE__, do: true

  defp proper_start?({atom_1, _, _args_1}) when :erlang.is_atom(atom_1), do: true

  defp proper_start?(other_1), do: not :erlang.is_tuple(other_1)

  defp split_module(atom_1) do
    case :binary.split(:erlang.atom_to_binary(atom_1, :utf8), ".", [:global]) do
      ["Elixir" | t_1] ->
        t_1
      _ ->
        []
    end
  end

  defp split_words(string_1, []), do: split_words(string_1, [115])

  defp split_words(string_1, [mod_1]) when mod_1 == 115 or mod_1 == 97 or mod_1 == 99 do
    case :erlang.is_binary(string_1) do
      true ->
        parts_1 = String.split(string_1)
        __1 = case mod_1 do
          115 ->
            parts_1
          97 ->
            :lists.map(:erlang.make_fun(String, :to_atom, 1), parts_1)
          99 ->
            :lists.map(:erlang.make_fun(String, :to_charlist, 1), parts_1)
        end
        parts_2 = parts_1
        __1
      false ->
        parts_2 = {{:., [], [{:__aliases__, [{:alias, false}], [:String]}, :split]}, [], [string_1]}
        case mod_1 do
          115 ->
            parts_2
          97 ->
            {{:., [], [:lists, :map]}, [], [{:&, [], [{:/, [{:context, Kernel}, {:import, Kernel}], [{{:., [], [{:__aliases__, [{:alias, false}], [:String]}, :to_atom]}, [], []}, 1]}]}, parts_2]}
          99 ->
            {{:., [], [:lists, :map]}, [], [{:&, [], [{:/, [{:context, Kernel}, {:import, Kernel}], [{{:., [], [{:__aliases__, [{:alias, false}], [:String]}, :to_charlist]}, [], []}, 1]}]}, parts_2]}
        end
    end
  end

  defp split_words(_string_1, _mods_1), do: :erlang.error(ArgumentError.exception("modifier must be one of: s, a, c"))

  defp struct(struct_1, [], _fun_1) when :erlang.is_atom(struct_1) do
    case struct_1 do
      %{:__struct__ => __1} ->
        __1
      __1 when :erlang.is_map(__1) ->
        :erlang.error({:badkey, :__struct__, __1})
      __1 ->
        __1.__struct__()
    end
  end

  defp struct(struct_1, kv_1, fun_1) when :erlang.is_atom(struct_1) do
    struct((case struct_1 do
      %{:__struct__ => __1} ->
        __1
      __1 when :erlang.is_map(__1) ->
        :erlang.error({:badkey, :__struct__, __1})
      __1 ->
        __1.__struct__()
    end), kv_1, fun_1)
  end

  defp struct(%{:__struct__ => _} = struct_1, [], _fun_1), do: struct_1

  defp struct(%{:__struct__ => _} = struct_1, kv_1, fun_1), do: Enum.reduce(kv_1, struct_1, fun_1)

  defp typespec(:type), do: :deftype

  defp typespec(:typep), do: :deftypep

  defp typespec(:opaque), do: :defopaque

  defp typespec(:spec), do: :defspec

  defp typespec(:callback), do: :defcallback

  defp typespec(:macrocallback), do: :defmacrocallback

  defp typespec(:optional_callbacks), do: :defoptional_callbacks

  defp typespec(_), do: false

  defp unnest({{:., _, [Access, :get]}, _, [expr_1, key_1]}, acc_1, _all_map__1, kind_1), do: unnest(expr_1, [{:access, key_1} | acc_1], false, kind_1)

  defp unnest({{:., _, [expr_1, key_1]}, _, []}, acc_1, all_map__1, kind_1) when :erlang.is_tuple(expr_1) and :erlang.element(1, expr_1) != :__aliases__ and :erlang.element(1, expr_1) != :__MODULE__, do: unnest(expr_1, [{:map, key_1} | acc_1], all_map__1, kind_1)

  defp unnest(other_1, [], _all_map__1, kind_1) do
    :erlang.error(ArgumentError.exception(<<"expected expression given to ", (case kind_1 do
      __1 when :erlang.is_binary(__1) ->
        __1
      __2 ->
        String.Chars.to_string(__2)
    end) :: binary, " to access at least one element, got: ", (case Macro.to_string(other_1) do
      __3 when :erlang.is_binary(__3) ->
        __3
      __4 ->
        String.Chars.to_string(__4)
    end) :: binary>>))
  end

  defp unnest(other_1, acc_1, all_map__1, kind_1) do
    case proper_start?(other_1) do
      true ->
        {[other_1 | acc_1], all_map__1}
      false ->
        :erlang.error(ArgumentError.exception(<<(<<"expression given to ", (case kind_1 do
          __1 when :erlang.is_binary(__1) ->
            __1
          __2 ->
            String.Chars.to_string(__2)
        end) :: binary, " must start with a variable, local or remote call ">>) :: binary, (<<"and be followed by an element access, got: ", (case Macro.to_string(other_1) do
          __3 when :erlang.is_binary(__3) ->
            __3
          __4 ->
            String.Chars.to_string(__4)
        end) :: binary>>) :: binary>>))
    end
  end

  defp wrap_binding(true, var_1), do: {:^, [], [var_1]}

  defp wrap_binding(_, var_1), do: var_1

  defp wrap_concatenation(binary_1) when :erlang.is_binary(binary_1), do: binary_1

  defp wrap_concatenation(other_1), do: {:"::", [], [other_1, {:binary, [], nil}]}
end
