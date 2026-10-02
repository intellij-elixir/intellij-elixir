# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :json do
  @moduledoc ~S"""
  A library for encoding and decoding JSON.

  This module implements [EEP68](https://github.com/erlang/eep/blob/master/eeps/eep-0068.md).

  Both encoder and decoder fully conform to
  [RFC 8259](https://tools.ietf.org/html/rfc8259) and
  [ECMA 404](https://ecma-international.org/publications-and-standards/standards/ecma-404/)
  standards. The decoder is tested using [JSONTestSuite](https://github.com/nst/JSONTestSuite).
  """

  # Types

  @type array_finish_fun :: (arrayAcc :: dynamic(), oldAcc :: dynamic() -> {dynamic(), dynamic()})

  @type array_push_fun :: (value :: dynamic(), acc :: dynamic() -> newAcc :: dynamic())

  @type array_start_fun :: (acc :: dynamic() -> arrayAcc :: dynamic())

  @opaque continuation_state :: tuple()

  @type decode_value :: (integer() | float() | boolean() | :null | binary() | [decode_value()] | %{optional(binary()) => decode_value()})

  @type decoders :: %{optional(:array_start) => array_start_fun(), optional(:array_push) => array_push_fun(), optional(:array_finish) => array_finish_fun(), optional(:object_start) => object_start_fun(), optional(:object_push) => object_push_fun(), optional(:object_finish) => object_finish_fun(), optional(:float) => from_binary_fun(), optional(:integer) => from_binary_fun(), optional(:string) => from_binary_fun(), optional(:null) => term()}

  @typedoc ~S"""
  Simple JSON value encodeable with `json:encode/1`.
  """
  @type encode_value :: (integer() | float() | boolean() | :null | binary() | atom() | [encode_value()] | encode_map(encode_value()))

  @type encoder :: (dynamic(), encoder() -> iodata())

  @type formatter :: (term :: dynamic(), encoder :: formatter(), state :: map() -> iodata())

  @type from_binary_fun :: (binary() -> dynamic())

  @type object_finish_fun :: (objectAcc :: dynamic(), oldAcc :: dynamic() -> {dynamic(), dynamic()})

  @type object_push_fun :: (key :: dynamic(), value :: dynamic(), acc :: dynamic() -> newAcc :: dynamic())

  @type object_start_fun :: (acc :: dynamic() -> objectAcc :: dynamic())

  # Private Types

  @typep acc :: dynamic()

  @typep decode :: decode()

  @typep encode_map(value) :: %{optional((binary() | atom() | integer())) => value}

  @typep stack :: [(:array | :object | binary() | acc())]

  # Functions

  @doc ~S"""
  Parses a JSON value from `Binary`.

  Supports basic data mapping:

  | **JSON** | **Erlang**             |
  |----------|------------------------|
  | Number   | `integer() \| float()` |
  | Boolean  | `true \| false`        |
  | Null     | `null`                 |
  | String   | `binary()`             |
  | Object   | `#{binary() => _}`     |

  ## Errors

  * `error(unexpected_end)` if `Binary` contains incomplete JSON value
  * `error({invalid_byte, Byte})` if `Binary` contains unexpected byte or invalid UTF-8 byte
  * `error({unexpected_sequence, Bytes})` if `Binary` contains invalid UTF-8 escape

  ## Example

  ```erlang
  > json:decode(<<"{\"foo\": 1}">>).
  #{<<"foo">> => 1}
  ```
  """
  @spec decode(binary()) :: decode_value()
  def decode(binary) when is_binary(binary) do
    case value(binary, binary, 0, :ok, [], decode()) do
      {result, _Acc, <<>>} ->
        result
      {_, _, rest} ->
        invalid_byte(rest, 0)
      {:continue, {_Bin, _Acc, [], _Decode, {:number, number}}} ->
        number
      {:continue, {_, _, _, _, {:float_error, token, skip}}} ->
        unexpected_sequence(token, skip)
      {:continue, _} ->
        error(:unexpected_end)
    end
  end

  @doc ~S"""
  Parses a JSON value from `Binary`.

  Similar to `decode/1` except the decoding process
  can be customized with the callbacks specified in
  `Decoders`. The callbacks will use the `Acc` value
  as the initial accumulator.

  Any leftover, unparsed data in `Binary` will be returned.

  ## Default callbacks

  All callbacks are optional. If not provided, they will fall back to
  implementations used by the `decode/1` function:

  * for `array_start`: `fun(_) -> [] end`
  * for `array_push`: `fun(Elem, Acc) -> [Elem | Acc] end`
  * for `array_finish`: `fun(Acc, OldAcc) -> {lists:reverse(Acc), OldAcc} end`
  * for `object_start`: `fun(_) -> [] end`
  * for `object_push`: `fun(Key, Value, Acc) -> [{Key, Value} | Acc] end`
  * for `object_finish`: `fun(Acc, OldAcc) -> {maps:from_list(Acc), OldAcc} end`
  * for `float`: `fun erlang:binary_to_float/1`
  * for `integer`: `fun erlang:binary_to_integer/1`
  * for `string`: `fun (Value) -> Value end`
  * for `null`: the atom `null`

  ## Errors

  * `error({invalid_byte, Byte})` if `Binary` contains unexpected byte or invalid UTF-8 byte
  * `error({unexpected_sequence, Bytes})` if `Binary` contains invalid UTF-8 escape
  * `error(unexpected_end)` if `Binary` contains incomplete JSON value

  ## Example

  Decoding object keys as atoms:

  ```erlang
  > Push = fun(Key, Value, Acc) -> [{binary_to_existing_atom(Key), Value} | Acc] end.
  > json:decode(<<"{\"foo\": 1}">>, ok, #{object_push => Push}).
  {#{foo => 1},ok,<<>>}
  ```
  """
  @spec decode(binary(), dynamic(), decoders()) :: {result :: dynamic(), acc :: dynamic(), binary()}
  def decode(binary, acc0, decoders) when is_binary(binary) do
    decode = :maps.fold(&parse_decoder/3, decode(), decoders)
    case value(binary, binary, 0, acc0, [], decode) do
      {:continue, {_Bin, acc, [], _Decode, {:number, val}}} ->
        {val, acc, <<>>}
      {:continue, {_, _, _, _, {:float_error, token, skip}}} ->
        unexpected_sequence(token, skip)
      {:continue, _} ->
        error(:unexpected_end)
      result ->
        result
    end
  end

  @doc ~S"""
  Continue parsing a stream of bytes of a JSON value.

  Similar to `decode_start/3`, if the function returns `{continue, State}` and
  there is no more data, use `end_of_input` instead of a binary.

  ```erlang
  > {continue, State} = json:decode_start(<<"{\"foo\":">>, ok, #{}).
  > json:decode_continue(<<"1}">>, State).
  {#{foo => 1},ok,<<>>}
  ```
  ```erlang
  > {continue, State} = json:decode_start(<<"123">>, ok, #{}).
  > json:decode_continue(end_of_input, State).
  {123,ok,<<>>}
  ```
  """
  @spec decode_continue((binary() | :end_of_input), state :: continuation_state()) :: ({result :: dynamic(), acc :: dynamic(), binary()} | {:continue, continuation_state()})
  def decode_continue(:end_of_input, state) do
    case state do
      {_, acc, [], _Decode, {:number, val}} ->
        {val, acc, <<>>}
      {_, _, _, _, {:float_error, token, skip}} ->
        unexpected_sequence(token, skip)
      _ ->
        error(:unexpected_end)
    end
  end

  def decode_continue(cont, {rest, acc, stack, decode() = decode, funcData}) when is_binary(cont) do
    binary = <<rest :: binary, cont :: binary>>
    case funcData do
      :value ->
        value(binary, binary, 0, acc, stack, decode)
      {:number, _} ->
        value(binary, binary, 0, acc, stack, decode)
      {:float_error, _Token, _Skip} ->
        value(binary, binary, 0, acc, stack, decode)
      {:array_push, val} ->
        array_push(binary, binary, 0, acc, stack, decode, val)
      {:object_value, key} ->
        object_value(binary, binary, 0, acc, stack, decode, key)
      {:object_push, value, key} ->
        object_push(binary, binary, 0, acc, stack, decode, value, key)
      :object_key ->
        object_key(binary, binary, 0, acc, stack, decode)
    end
  end

  @doc ~S"""
  Begin parsing a stream of bytes of a JSON value.

  Similar to `decode/3` but returns when a complete JSON value can be parsed or
  returns `{continue, State}` for incomplete data,
  the `State` can be fed to the `decode_continue/2` function when more data is available.
  """
  @spec decode_start(binary(), dynamic(), decoders()) :: ({result :: dynamic(), acc :: dynamic(), binary()} | {:continue, continuation_state()})
  def decode_start(binary, acc, decoders) when is_binary(binary) do
    decode = :maps.fold(&parse_decoder/3, decode(), decoders)
    value(binary, binary, 0, acc, [], decode)
  end

  @doc ~S"""
  Generates JSON corresponding to `Term`.

  Supports basic data mapping:

  | **Erlang**             | **JSON** |
  |------------------------|----------|
  | `integer() \| float()` | Number   |
  | `true \| false `       | Boolean  |
  | `null`                 | Null     |
  | `binary()`             | String   |
  | `atom()`               | String   |
  | `list()`               | Array    |
  | `#{binary() => _}`     | Object   |
  | `#{atom() => _}`       | Object   |
  | `#{integer() => _}`    | Object   |

  This is equivalent to `encode(Term, fun json:encode_value/2)`.

  ## Examples

  ```erlang
  > iolist_to_binary(json:encode(#{foo => <<"bar">>})).
  <<"{\"foo\":\"bar\"}">>
  ```
  """
  @spec encode(encode_value()) :: iodata()
  def encode(term), do: encode(term, &do_encode/2)

  @doc ~S"""
  Generates JSON corresponding to `Term`.

  Can be customised with the `Encoder` callback.
  The callback will be recursively called for all the data
  to be encoded and is expected to return the corresponding
  encoded JSON as iodata.

  Various `encode_*` functions in this module can be used
  to help in constructing such callbacks.

  ## Examples

  An encoder that uses a heuristic to differentiate object-like
  lists of key-value pairs from plain lists:

  ```erlang
  > encoder([{_, _} | _] = Value, Encode) -> json:encode_key_value_list(Value, Encode);
  > encoder(Other, Encode) -> json:encode_value(Other, Encode).
  > custom_encode(Value) -> json:encode(Value, fun(Value, Encode) -> encoder(Value, Encode) end).
  > iolist_to_binary(custom_encode([{a, []}, {b, 1}])).
  <<"{\"a\":[],\"b\":1}">>
  ```
  """
  @spec encode(dynamic(), encoder()) :: iodata()
  def encode(term, encoder) when is_function(encoder, 2), do: encoder.(term, encoder)

  @doc ~S"""
  Default encoder for atoms used by `json:encode/1`.

  Encodes the atom `null` as JSON `null`,
  atoms `true` and `false` as JSON booleans,
  and everything else as JSON strings calling the `Encode`
  callback with the corresponding binary.
  """
  @spec encode_atom(atom(), encoder()) :: iodata()
  def encode_atom(:null, _Encode), do: "null"

  def encode_atom(true, _Encode), do: "true"

  def encode_atom(false, _Encode), do: "false"

  def encode_atom(other, encode), do: encode.(atom_to_binary(other, :utf8), encode)

  @doc ~S"""
  Default encoder for binaries as JSON strings used by `json:encode/1`.

  ## Errors

  * `error(unexpected_end)` if the binary contains incomplete UTF-8 sequences.
  * `error({invalid_byte, Byte})` if the binary contains invalid UTF-8 sequences.
  """
  @spec encode_binary(binary()) :: iodata()
  def encode_binary(bin) when is_binary(bin), do: escape_binary(bin)

  @doc ~S"""
  Encoder for binaries as JSON strings producing pure-ASCII JSON.

  For any non-ASCII unicode character, a corresponding `\\uXXXX` sequence is used.

  ## Errors

  * `error(unexpected_end)` if the binary contains incomplete UTF-8 sequences.
  * `error({invalid_byte, Byte})` if the binary contains invalid UTF-8 sequences.
  """
  @spec encode_binary_escape_all(binary()) :: iodata()
  def encode_binary_escape_all(bin) when is_binary(bin), do: escape_all(bin)

  @doc ~S"""
  Default encoder for floats as JSON numbers used by `json:encode/1`.
  """
  @spec encode_float(float()) :: iodata()
  def encode_float(float), do: float_to_binary(float, [:short])

  @doc ~S"""
  Default encoder for integers as JSON numbers used by `json:encode/1`.
  """
  @spec encode_integer(integer()) :: iodata()
  def encode_integer(integer), do: integer_to_binary(integer)

  @doc ~S"""
  Encoder for lists of key-value pairs as JSON objects.

  Accepts lists with atom, binary, integer, or float keys.
  """
  @spec encode_key_value_list([{term(), term()}], encoder()) :: iodata()
  def encode_key_value_list(list, encode) when is_function(encode, 2) do
    encode_object((for {key, value} <- list do
      [?,, key(key, encode), ?: | encode.(value, encode)]
    end))
  end

  @doc ~S"""
  Encoder for lists of key-value pairs as JSON objects.

  Accepts lists with atom, binary, integer, or float keys.
  Verifies that no duplicate keys will be produced in the
  resulting JSON object.

  ## Errors

  Raises `error({duplicate_key, Key})` if there are duplicates.
  """
  @spec encode_key_value_list_checked([{term(), term()}], encoder()) :: iodata()
  def encode_key_value_list_checked(list, encode), do: do_encode_checked(list, encode)

  @doc ~S"""
  Default encoder for lists as JSON arrays used by `json:encode/1`.
  """
  @spec encode_list([], encoder()) :: iodata()
  def encode_list(list, encode) when is_list(list), do: do_encode_list(list, encode)

  @doc ~S"""
  Default encoder for maps as JSON objects used by `json:encode/1`.

  Accepts maps with atom, binary, integer, or float keys.
  """
  @spec encode_map(encode_map(dynamic()), encoder()) :: iodata()
  def encode_map(map, encode) when is_map(map), do: do_encode_map(map, encode)

  @doc ~S"""
  Encoder for maps as JSON objects.

  Accepts maps with atom, binary, integer, or float keys.
  Verifies that no duplicate keys will be produced in the
  resulting JSON object.

  ## Errors

  Raises `error({duplicate_key, Key})` if there are duplicates.
  """
  @spec encode_map_checked(map(), encoder()) :: iodata()
  def encode_map_checked(map, encode), do: do_encode_checked(:maps.to_list(map), encode)

  @doc ~S"""
  Default encoder used by `json:encode/1`.

  Recursively calls `Encode` on all the values in `Value`.
  """
  @spec encode_value(dynamic(), encoder()) :: iodata()
  def encode_value(value, encode), do: do_encode(value, encode)

  @doc ~S"""
  Generates formatted JSON corresponding to `Term`.

  Similiar to `encode/1` but with added whitespaces for formatting.

  ```erlang
  > io:put_chars(json:format(#{foo => <<"bar">>, baz => 52})).
  {
    "baz": 52,
    "foo": "bar"
  }
  ok
  ```
  """
  @spec format(term :: encode_value()) :: iodata()
  def format(term) do
    enc = &format_value/3
    format(term, enc, %{})
  end

  @doc ~S"""
  Generates formatted JSON corresponding to `Term`.

  Equivalent to `format(Term, fun json:format_value/3, Options)` or `format(Term, Encoder, #{})`
  """
  @spec format(term :: encode_value(), opts :: map()) :: iodata()
  @spec format(term :: dynamic(), encoder :: formatter()) :: iodata()
  def format(term, options) when is_map(options) do
    enc = &format_value/3
    format(term, enc, options)
  end

  def format(term, encoder) when is_function(encoder, 3), do: format(term, encoder, %{})

  @doc ~S"""
  Generates formatted JSON corresponding to `Term`.

  Similar to `encode/2`, can be customised with the `Encoder` callback and `Options`.

  `Options` can include 'indent' to specify number of spaces per level and 'max' which loosely limits
  the width of lists.

  The `Encoder` will get a 'State' argument which contains the 'Options' maps merged with other data
  when recursing through 'Term'.

  `format_value/3` or various `encode_*` functions in this module can be used
  to help in constructing such callbacks.

  ```erlang
  > formatter({posix_time, SysTimeSecs}, Encode, State) ->
      TimeStr = calendar:system_time_to_rfc3339(SysTimeSecs, [{offset, "Z"}]),
      json:format_value(unicode:characters_to_binary(TimeStr), Encode, State);
  > formatter(Other, Encode, State) -> json:format_value(Other, Encode, State).
  >
  > Fun = fun(Value, Encode, State) -> formatter(Value, Encode, State) end.
  > Options = #{indent => 4}.
  > Term = #{id => 1, time => {posix_time, erlang:system_time(seconds)}}.
  >
  > io:put_chars(json:format(Term, Fun, Options)).
  {
      "id": 1,
      "time": "2024-05-23T16:07:48Z"
  }
  ok
  ```
  """
  @spec format(term :: dynamic(), encoder :: formatter(), options :: map()) :: iodata()
  def format(term, encoder, options) when is_function(encoder, 3) do
    def = %{:level => 0, :col => 0, :indent => 2, :max => 100}
    [encoder.(term, encoder, :maps.merge(def, options)), ?\n]
  end

  @doc ~S"""
  Format function for lists of key-value pairs as JSON objects.

  Accepts lists with atom, binary, integer, or float keys.
  """
  @spec format_key_value_list([{term(), term()}], encode :: formatter(), state :: map()) :: iodata()
  def format_key_value_list(kVList, userEnc, %{:level => level} = state) do
    {_, indent} = indent(state)
    nextState = %{state | :level => level + 1}
    {kISize, keyIndent} = indent(nextState)
    encKeyFun = fn keyVal, _Fun ->
        userEnc.(keyVal, userEnc, nextState)
    end
    entryFun = fn {key, value} ->
        encKey = key(key, encKeyFun)
        valState = %{nextState | :col => kISize + 2 + :erlang.iolist_size(encKey)}
        [?,, keyIndent, encKey, ': ' | userEnc.(value, userEnc, valState)]
    end
    format_object(:lists.map(entryFun, kVList), indent)
  end

  @doc ~S"""
  Format function for lists of key-value pairs as JSON objects.

  Accepts lists with atom, binary, integer, or float keys.
  Verifies that no duplicate keys will be produced in the
  resulting JSON object.

  ## Errors

  Raises `error({duplicate_key, Key})` if there are duplicates.
  """
  @spec format_key_value_list_checked([{term(), term()}], encoder :: formatter(), state :: map()) :: iodata()
  def format_key_value_list_checked(kVList, userEnc, state) when is_function(userEnc, 3) do
    {_, indent} = indent(state)
    format_object(do_format_checked(kVList, userEnc, state), indent)
  end

  @doc ~S"""
  Default format function used by `json:format/1`.

  Recursively calls `Encode` on all the values in `Value`,
  and indents objects and lists.
  """
  @spec format_value(value :: dynamic(), encode :: formatter(), state :: map()) :: iodata()
  def format_value(atom, userEnc, state) when is_atom(atom) do
    :json.encode_atom(atom, fn value, enc ->
        userEnc.(value, enc, state)
    end)
  end

  def format_value(bin, _Enc, _State) when is_binary(bin), do: :json.encode_binary(bin)

  def format_value(int, _Enc, _State) when is_integer(int), do: :json.encode_integer(int)

  def format_value(float, _Enc, _State) when is_float(float), do: :json.encode_float(float)

  def format_value(list, userEnc, state) when is_list(list), do: format_list(list, userEnc, state)

  def format_value(map, userEnc, state) when is_map(map) do
    orderedKV = :maps.to_list(:maps.iterator(map, :ordered))
    format_key_value_list(orderedKV, userEnc, state)
  end

  def format_value(other, _Enc, _State), do: error({:unsupported_type, other})

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  # Private Functions

  defp unquote(:"-do_encode_map/2-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-do_format_checked/3-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-do_format_checked/3-fun-1-")(arg1, arg2, arg3, arg4, arg5, arg6, arg7) do
    # body not decompiled
  end

  defp unquote(:"-do_format_checked/3-inlined-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-encode_key_value_list/2-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-format_key_value_list/3-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-format_key_value_list/3-fun-1-")(arg1, arg2, arg3, arg4, arg5, arg6) do
    # body not decompiled
  end

  defp unquote(:"-format_key_value_list/3-inlined-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-format_value/3-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp array_push(<<byte, rest :: bits>>, original, skip, acc, stack, decode, value) when byte === ?\s or byte === ?\t or byte === ?\r or byte === ?\n, do: array_push(rest, original, skip + 1, acc, stack, decode, value)

  defp array_push(<<"]", rest :: bits>>, original, skip, acc0, stack0, decode, value) do
    acc = case decode(decode, :array_push) do
      :undefined ->
        [value | acc0]
      push ->
        push.(value, acc0)
    end
    [_, oldAcc | stack] = stack0
    {arrayValue, newAcc} = case decode(decode, :array_finish) do
      :undefined ->
        {:lists.reverse(acc), oldAcc}
      finish ->
        finish.(acc, oldAcc)
    end
    continue(rest, original, skip + 1, newAcc, stack, decode, arrayValue)
  end

  defp array_push(<<?,, rest :: bits>>, original, skip0, acc, stack, decode, value) do
    skip = skip0 + 1
    case decode(decode, :array_push) do
      :undefined ->
        value(rest, original, skip, [value | acc], stack, decode)
      fun ->
        value(rest, original, skip, fun.(value, acc), stack, decode)
    end
  end

  defp array_push(_, original, skip, acc, stack, decode, value), do: unexpected(original, skip, acc, stack, decode, 0, 0, {:array_push, value})

  defp array_start(<<byte, rest :: bits>>, original, skip, acc, stack, decode, len) when byte === ?\s or byte === ?\t or byte === ?\r or byte === ?\n, do: array_start(rest, original, skip, acc, stack, decode, len + 1)

  defp array_start(<<"]", rest :: bits>>, original, skip, acc, stack, decode, len) do
    {value, newAcc} = case {decode(decode, :array_start), decode(decode, :array_finish)} do
      {:undefined, :undefined} ->
        {[], acc}
      {start, :undefined} ->
        {:lists.reverse(start.(acc)), acc}
      {:undefined, finish} ->
        finish.([], acc)
      {start, finish} ->
        finish.(start.(acc), acc)
    end
    continue(rest, original, skip + len + 1, newAcc, stack, decode, value)
  end

  defp array_start(<<>>, original, skip, acc, stack, decode, len), do: unexpected(original, skip, acc, stack, decode, len, 0, :value)

  defp array_start(rest, original, skip, oldAcc, stack, decode, len) do
    case decode(decode, :array_start) do
      :undefined ->
        value(rest, original, skip + len, [], [:array, oldAcc | stack], decode)
      fun ->
        value(rest, original, skip + len, fun.(oldAcc), [:array, oldAcc | stack], decode)
    end
  end

  defp continue(<<rest :: bits>>, original, skip, acc, stack0, decode, value) do
    case stack0 do
      [] ->
        terminate(rest, original, skip, acc, value)
      [:array | _] ->
        array_push(rest, original, skip, acc, stack0, decode, value)
      [:object | _] ->
        object_value(rest, original, skip, acc, stack0, decode, value)
      [key | stack] ->
        object_push(rest, original, skip, acc, stack, decode, value, key)
    end
  end

  @spec do_encode(dynamic(), encoder()) :: iodata()
  defp do_encode(value, encode) when is_atom(value), do: encode_atom(value, encode)

  defp do_encode(value, _Encode) when is_binary(value), do: escape_binary(value)

  defp do_encode(value, _Encode) when is_integer(value), do: encode_integer(value)

  defp do_encode(value, _Encode) when is_float(value), do: encode_float(value)

  defp do_encode(value, encode) when is_list(value), do: do_encode_list(value, encode)

  defp do_encode(value, encode) when is_map(value), do: do_encode_map(value, encode)

  defp do_encode(other, _Encode), do: error({:unsupported_type, other})

  defp do_encode_checked(list, encode) when is_function(encode, 2), do: encode_object(do_encode_checked(list, encode, %{}))

  defp do_encode_checked([{key, value} | rest], encode, visited0) do
    encodedKey = iolist_to_binary(key(key, encode))
    case is_map_key(encodedKey, visited0) do
      true ->
        error({:duplicate_key, key})
      _ ->
        visited = %{visited0 | encodedKey => true}
        [[?,, encodedKey, ?: | encode.(value, encode)] | do_encode_checked(rest, encode, visited)]
    end
  end

  defp do_encode_checked([], _, _), do: []

  defp do_encode_list([], _Encode), do: "[]"

  defp do_encode_list([first | rest], encode) when is_function(encode, 2), do: [?[, encode.(first, encode) | list_loop(rest, encode)]

  defp do_encode_map(map, encode) when is_function(encode, 2) do
    encode_object((for {key, value} <- map do
      [?,, key(key, encode), ?: | encode.(value, encode)]
    end))
  end

  defp do_format_checked([], _, _), do: []

  defp do_format_checked(kVList, userEnc, %{:level => level} = state) do
    nextState = %{state | :level => level + 1}
    {kISize, keyIndent} = indent(nextState)
    encKeyFun = fn keyVal, _Fun ->
        userEnc.(keyVal, userEnc, nextState)
    end
    encListFun = fn {key, value}, {acc, visited0} ->
        encKey = iolist_to_binary(key(key, encKeyFun))
        case is_map_key(encKey, visited0) do
          true ->
            error({:duplicate_key, key})
          false ->
            visited1 = %{visited0 | encKey => true}
            valState = %{nextState | :col => kISize + 2 + :erlang.iolist_size(encKey)}
            encEntry = [?,, keyIndent, encKey, ': ' | userEnc.(value, userEnc, valState)]
            {[encEntry | acc], visited1}
        end
    end
    {encKVList, _} = :lists.foldl(encListFun, {[], %{}}, kVList)
    :lists.reverse(encKVList)
  end

  defp error_info(skip), do: [{:error_info, %{:cause => %{:position => skip}, :module => :erl_stdlib_errors}}]

  defp escape_all(<<byte, rest :: binary>>, acc, orig, skip, len) when byte === 32 or byte === 33 or byte === 35 or byte === 36 or byte === 37 or byte === 38 or byte === 39 or byte === 40 or byte === 41 or byte === 42 or byte === 43 or byte === 44 or byte === 45 or byte === 46 or byte === 47 or byte === 48 or byte === 49 or byte === 50 or byte === 51 or byte === 52 or byte === 53 or byte === 54 or byte === 55 or byte === 56 or byte === 57 or byte === 58 or byte === 59 or byte === 60 or byte === 61 or byte === 62 or byte === 63 or byte === 64 or byte === 65 or byte === 66 or byte === 67 or byte === 68 or byte === 69 or byte === 70 or byte === 71 or byte === 72 or byte === 73 or byte === 74 or byte === 75 or byte === 76 or byte === 77 or byte === 78 or byte === 79 or byte === 80 or byte === 81 or byte === 82 or byte === 83 or byte === 84 or byte === 85 or byte === 86 or byte === 87 or byte === 88 or byte === 89 or byte === 90 or byte === 91 or byte === 93 or byte === 94 or byte === 95 or byte === 96 or byte === 97 or byte === 98 or byte === 99 or byte === 100 or byte === 101 or byte === 102 or byte === 103 or byte === 104 or byte === 105 or byte === 106 or byte === 107 or byte === 108 or byte === 109 or byte === 110 or byte === 111 or byte === 112 or byte === 113 or byte === 114 or byte === 115 or byte === 116 or byte === 117 or byte === 118 or byte === 119 or byte === 120 or byte === 121 or byte === 122 or byte === 123 or byte === 124 or byte === 125 or byte === 126 or byte === 127, do: escape_all(rest, acc, orig, skip, len + 1)

  defp escape_all(<<byte, rest :: bits>>, acc, orig, skip, len) when byte === 0 or byte === 1 or byte === 2 or byte === 3 or byte === 4 or byte === 5 or byte === 6 or byte === 7 or byte === 8 or byte === 9 or byte === 10 or byte === 11 or byte === 12 or byte === 13 or byte === 14 or byte === 15 or byte === 16 or byte === 17 or byte === 18 or byte === 19 or byte === 20 or byte === 21 or byte === 22 or byte === 23 or byte === 24 or byte === 25 or byte === 26 or byte === 27 or byte === 28 or byte === 29 or byte === 30 or byte === 31 or byte === 34 or byte === 92 do
    escape = escape(byte)
    case len do
      0 ->
        escape_all(rest, [acc | escape], orig, skip + 1, 0)
      _ ->
        part = binary_part(orig, skip, len)
        escape_all(rest, [acc, part | escape], orig, skip + len + 1, 0)
    end
  end

  defp escape_all(<<char :: utf8, rest :: bits>>, acc, orig, skip, 0), do: escape_char(rest, acc, orig, skip, char)

  defp escape_all(<<char :: utf8, rest :: bits>>, acc, orig, skip, len) do
    part = binary_part(orig, skip, len)
    escape_char(rest, [acc | part], orig, skip + len, char)
  end

  defp escape_all(<<>>, _Acc, orig, 0, _Len), do: [?", orig, ?"]

  defp escape_all(<<>>, acc, _Orig, _Skip, 0), do: [acc, ?"]

  defp escape_all(<<>>, acc, orig, skip, len) do
    part = binary_part(orig, skip, len)
    [acc, part, ?"]
  end

  defp escape_all(_Other, _Acc, orig, skip, len), do: invalid_byte(orig, skip + len)

  defp escape_all_ascii(binary, acc, orig, skip, len) do
    case binary do
      <<w :: 56, rest :: binary>> when w &&& 36170086419038336 === 0 and w + 27127564814278752 &&& 36170086419038336 === 36170086419038336 and w ^^^ 9607679205057058 - 282578800148737 &&& 36170086419038336 === 0 and w ^^^ 25997249613683804 - 282578800148737 &&& 36170086419038336 === 0 ->
        escape_all_ascii(rest, acc, orig, skip, len + 7)
      other ->
        escape_all(other, acc, orig, skip, len)
    end
  end

  defp escape_binary(<<byte, rest :: binary>>, acc, orig, skip, len) when byte === 32 or byte === 33 or byte === 35 or byte === 36 or byte === 37 or byte === 38 or byte === 39 or byte === 40 or byte === 41 or byte === 42 or byte === 43 or byte === 44 or byte === 45 or byte === 46 or byte === 47 or byte === 48 or byte === 49 or byte === 50 or byte === 51 or byte === 52 or byte === 53 or byte === 54 or byte === 55 or byte === 56 or byte === 57 or byte === 58 or byte === 59 or byte === 60 or byte === 61 or byte === 62 or byte === 63 or byte === 64 or byte === 65 or byte === 66 or byte === 67 or byte === 68 or byte === 69 or byte === 70 or byte === 71 or byte === 72 or byte === 73 or byte === 74 or byte === 75 or byte === 76 or byte === 77 or byte === 78 or byte === 79 or byte === 80 or byte === 81 or byte === 82 or byte === 83 or byte === 84 or byte === 85 or byte === 86 or byte === 87 or byte === 88 or byte === 89 or byte === 90 or byte === 91 or byte === 93 or byte === 94 or byte === 95 or byte === 96 or byte === 97 or byte === 98 or byte === 99 or byte === 100 or byte === 101 or byte === 102 or byte === 103 or byte === 104 or byte === 105 or byte === 106 or byte === 107 or byte === 108 or byte === 109 or byte === 110 or byte === 111 or byte === 112 or byte === 113 or byte === 114 or byte === 115 or byte === 116 or byte === 117 or byte === 118 or byte === 119 or byte === 120 or byte === 121 or byte === 122 or byte === 123 or byte === 124 or byte === 125 or byte === 126 or byte === 127, do: escape_binary(rest, acc, orig, skip, len + 1)

  defp escape_binary(<<byte, rest :: binary>>, acc, orig, skip0, len) when byte === 0 or byte === 1 or byte === 2 or byte === 3 or byte === 4 or byte === 5 or byte === 6 or byte === 7 or byte === 8 or byte === 9 or byte === 10 or byte === 11 or byte === 12 or byte === 13 or byte === 14 or byte === 15 or byte === 16 or byte === 17 or byte === 18 or byte === 19 or byte === 20 or byte === 21 or byte === 22 or byte === 23 or byte === 24 or byte === 25 or byte === 26 or byte === 27 or byte === 28 or byte === 29 or byte === 30 or byte === 31 or byte === 34 or byte === 92 do
    escape = escape(byte)
    skip = skip0 + len + 1
    case len do
      0 ->
        escape_binary_ascii(rest, [acc | escape], orig, skip, 0)
      _ ->
        part = binary_part(orig, skip0, len)
        escape_binary_ascii(rest, [acc, part | escape], orig, skip, 0)
    end
  end

  defp escape_binary(<<byte, rest :: binary>>, acc, orig, skip, len) do
    case element(byte - 127, utf8s0()) do
      12 ->
        invalid_byte(orig, skip + len)
      state ->
        escape_binary_utf8(rest, acc, orig, skip, len, state)
    end
  end

  defp escape_binary(_, _Acc, orig, 0, _Len), do: [?", orig, ?"]

  defp escape_binary(_, acc, _Orig, _Skip, 0), do: [acc, ?"]

  defp escape_binary(_, acc, orig, skip, len) do
    part = binary_part(orig, skip, len)
    [acc, part, ?"]
  end

  defp escape_binary_ascii(binary, acc, orig, skip, len) do
    case binary do
      <<w :: 56, rest :: binary>> when w &&& 36170086419038336 === 0 and w + 27127564814278752 &&& 36170086419038336 === 36170086419038336 and w ^^^ 9607679205057058 - 282578800148737 &&& 36170086419038336 === 0 and w ^^^ 25997249613683804 - 282578800148737 &&& 36170086419038336 === 0 ->
        escape_binary_ascii(rest, acc, orig, skip, len + 7)
      other ->
        escape_binary(other, acc, orig, skip, len)
    end
  end

  defp escape_binary_utf8(<<byte, rest :: binary>>, acc, orig, skip, len, state0) do
    type = element(byte + 1, utf8t())
    case element(state0 + type, utf8s()) do
      0 ->
        escape_binary_ascii(rest, acc, orig, skip, len + 2)
      12 ->
        invalid_byte(orig, skip + len + 1)
      state ->
        escape_binary_utf8(rest, acc, orig, skip, len + 1, state)
    end
  end

  defp escape_binary_utf8(_, _Acc, orig, skip, len, _State), do: unexpected_utf8(orig, skip + len + 1)

  defp escape_char(<<rest :: bits>>, acc, orig, skip, char) when char <= 255 do
    acc1 = [acc, '\\u00' | integer_to_binary(char, 16)]
    escape_all(rest, acc1, orig, skip + 2, 0)
  end

  defp escape_char(<<rest :: bits>>, acc, orig, skip, char) when char <= 2047 do
    acc1 = [acc, '\\u0' | integer_to_binary(char, 16)]
    escape_all(rest, acc1, orig, skip + 2, 0)
  end

  defp escape_char(<<rest :: bits>>, acc, orig, skip, char) when char <= 4095 do
    acc1 = [acc, '\\u0' | integer_to_binary(char, 16)]
    escape_all(rest, acc1, orig, skip + 3, 0)
  end

  defp escape_char(<<rest :: bits>>, acc, orig, skip, char) when char <= 65535 do
    acc1 = [acc, '\\u' | integer_to_binary(char, 16)]
    escape_all(rest, acc1, orig, skip + 3, 0)
  end

  defp escape_char(<<rest :: bits>>, acc, orig, skip, char0) do
    char = char0 - 65536
    first = integer_to_binary(2048 ||| char >>> 10, 16)
    second = integer_to_binary(3072 ||| char &&& 1023, 16)
    acc1 = [acc, '\\uD', first, '\\uD' | second]
    escape_all(rest, acc1, orig, skip + 4, 0)
  end

  defp unquote(:false)(<<"alse", rest :: bits>>, original, skip, acc, stack, decode), do: continue(rest, original, skip + 5, acc, stack, decode, false)

  defp unquote(:false)(_Rest, original, skip, acc, stack, decode), do: unexpected(original, skip, acc, stack, decode, 1, 4, :value)

  defp float_decode(<<>>, original, skip, acc, stack, decode, len, token) do
    try do
      decode(decode, :float)(token)
    catch
      {_, _, _} ->
        unexpected(original, skip, acc, stack, decode, len, 0, {:float_error, token, skip})
    else
      float ->
        unexpected(original, skip, acc, stack, decode, len, 0, {:number, float})
    end
  end

  defp float_decode(<<rest :: bits>>, original, skip, acc, stack, decode, len, token) do
    try do
      decode(decode, :float)(token)
    catch
      {_, _, _} ->
        unexpected_sequence(token, skip)
    else
      float ->
        continue(rest, original, skip + len, acc, stack, decode, float)
    end
  end

  defp format_list([head | rest], userEnc, %{:level => level, :col => col0, :max => max} = state0) do
    state1 = %{state0 | :level => level + 1}
    {len, indentElement} = indent(state1)
    cond do
      is_list(head) or is_map(head) or is_binary(head) or col0 > max ->
        state = %{state1 | :col => len}
        first = userEnc.(head, userEnc, state)
        {_, indLast} = indent(state0)
        [?[, indentElement, first, format_tail(rest, userEnc, state, indentElement, indentElement), indLast, ?]]
      true ->
        first = userEnc.(head, userEnc, state1)
        col = col0 + 1 + :erlang.iolist_size(first)
        [?[, first, format_tail(rest, userEnc, %{state1 | :col => col}, [], indentElement), ?]]
    end
  end

  defp format_list([], _, _), do: "[]"

  defp format_object([], _), do: "{}"

  defp format_object([[_Comma, keyIndent | entry]], indent) do
    [_Key, _Colon | value] = entry
    {_, rest} = :string.take(value, [?\s, ?\n])
    [cP | _] = :string.next_codepoint(rest)
    cond do
      cP === ?{ ->
        [?{, keyIndent, entry, indent, ?}]
      cP === ?[ ->
        [?{, keyIndent, entry, indent, ?}]
      true ->
        ['{ ', entry, ' }']
    end
  end

  defp format_object([[_Comma, keyIndent | entry] | rest], indent), do: [?{, keyIndent, entry, rest, indent, ?}]

  defp format_tail([head | tail], enc, %{:max => max, :col => col0} = state, [], indentRow) when col0 < max do
    encHead = enc.(head, enc, state)
    string = [?, | encHead]
    col = col0 + 1 + :erlang.iolist_size(encHead)
    [string | format_tail(tail, enc, %{state | :col => col}, [], indentRow)]
  end

  defp format_tail([head | tail], enc, state, [], indentRow) do
    encHead = enc.(head, enc, state)
    string = [[?, | indentRow] | encHead]
    col = :erlang.iolist_size(string) - 2
    [string | format_tail(tail, enc, %{state | :col => col}, [], indentRow)]
  end

  defp format_tail([head | tail], enc, state, indentAll, indentRow) do
    encHead = enc.(head, enc, state)
    string = [[?, | indentAll] | encHead]
    [string | format_tail(tail, enc, state, indentAll, indentRow)]
  end

  defp format_tail([], _, _, _, _), do: []

  defp indent(%{:level => level, :indent => indent}) do
    steps = level * indent
    {steps, steps(steps)}
  end

  defp invalid_byte(bin, skip) do
    byte = :binary.at(bin, skip)
    error({:invalid_byte, byte}, :none, error_info(skip))
  end

  defp key(key, encode) when is_binary(key), do: encode.(key, encode)

  defp key(key, encode) when is_atom(key), do: encode.(atom_to_binary(key, :utf8), encode)

  defp key(key, _Encode) when is_integer(key), do: [?", encode_integer(key), ?"]

  defp key(key, _Encode) when is_float(key), do: [?", encode_float(key), ?"]

  defp list_loop([], _Encode), do: ']'

  defp list_loop([elem | rest], encode), do: [?,, encode.(elem, encode) | list_loop(rest, encode)]

  defp null(<<"ull", rest :: bits>>, original, skip, acc, stack, decode), do: continue(rest, original, skip + 4, acc, stack, decode, decode(decode, :null))

  defp null(_Rest, original, skip, acc, stack, decode), do: unexpected(original, skip, acc, stack, decode, 1, 3, :value)

  defp number(<<num, rest :: bits>>, original, skip, acc, stack, decode, len) when num >= ?0 and num <= ?9, do: number(rest, original, skip, acc, stack, decode, len + 1)

  defp number(<<?., rest :: bits>>, original, skip, acc, stack, decode, len), do: number_frac(rest, original, skip, acc, stack, decode, len + 1)

  defp number(<<e, rest :: bits>>, original, skip, acc, stack, decode, len) when e === ?E or e === ?e do
    prefix = binary_part(original, skip, len)
    number_exp_copy(rest, original, skip, acc, stack, decode, len + 1, prefix)
  end

  defp number(<<>>, original, skip, acc, stack, decode, len) do
    int = decode(decode, :integer)(binary_part(original, skip, len))
    unexpected(original, skip, acc, stack, decode, len, 0, {:number, int})
  end

  defp number(rest, original, skip, acc, stack, decode, len) do
    int = decode(decode, :integer)(binary_part(original, skip, len))
    continue(rest, original, skip + len, acc, stack, decode, int)
  end

  defp number_exp(<<byte, rest :: bits>>, original, skip, acc, stack, decode, len) when byte >= ?0 and byte <= ?9, do: number_exp_cont(rest, original, skip, acc, stack, decode, len + 1)

  defp number_exp(<<sign, rest :: bits>>, original, skip, acc, stack, decode, len) when sign === ?+ or sign === ?-, do: number_exp_sign(rest, original, skip, acc, stack, decode, len + 1)

  defp number_exp(_, original, skip, acc, stack, decode, len), do: unexpected(original, skip, acc, stack, decode, len, 0, :value)

  defp number_exp_cont(<<byte, rest :: bits>>, original, skip, acc, stack, decode, len) when byte >= ?0 and byte <= ?9, do: number_exp_cont(rest, original, skip, acc, stack, decode, len + 1)

  defp number_exp_cont(rest, original, skip, acc, stack, decode, len) do
    token = binary_part(original, skip, len)
    float_decode(rest, original, skip, acc, stack, decode, len, token)
  end

  defp number_exp_cont(<<byte, rest :: bits>>, original, skip, acc, stack, decode, len, prefix, expLen) when byte >= ?0 and byte <= ?9, do: number_exp_cont(rest, original, skip, acc, stack, decode, len, prefix, expLen + 1)

  defp number_exp_cont(rest, original, skip, acc, stack, decode, len, prefix, expLen) do
    suffix = binary_part(original, skip + len, expLen)
    token = <<prefix :: binary, ".0e", suffix :: binary>>
    float_decode(rest, original, skip, acc, stack, decode, len + expLen, token)
  end

  defp number_exp_copy(<<byte, rest :: bits>>, original, skip, acc, stack, decode, len, prefix) when byte >= ?0 and byte <= ?9, do: number_exp_cont(rest, original, skip, acc, stack, decode, len, prefix, 1)

  defp number_exp_copy(<<sign, rest :: bits>>, original, skip, acc, stack, decode, len, prefix) when sign === ?+ or sign === ?-, do: number_exp_sign(rest, original, skip, acc, stack, decode, len, prefix, 1)

  defp number_exp_copy(_, original, skip, acc, stack, decode, len, _Prefix), do: unexpected(original, skip, acc, stack, decode, len, 0, :value)

  defp number_exp_sign(<<byte, rest :: bits>>, original, skip, acc, stack, decode, len) when byte >= ?0 and byte <= ?9, do: number_exp_cont(rest, original, skip, acc, stack, decode, len + 1)

  defp number_exp_sign(_, original, skip, acc, stack, decode, len), do: unexpected(original, skip, acc, stack, decode, len, 0, :value)

  defp number_exp_sign(<<byte, rest :: bits>>, original, skip, acc, stack, decode, len, prefix, expLen) when byte >= ?0 and byte <= ?9, do: number_exp_cont(rest, original, skip, acc, stack, decode, len, prefix, expLen + 1)

  defp number_exp_sign(_, original, skip, acc, stack, decode, len, _Prefix, expLen), do: unexpected(original, skip, acc, stack, decode, len + expLen, 0, :value)

  defp number_frac(<<byte, rest :: bits>>, original, skip, acc, stack, decode, len) when byte >= ?0 and byte <= ?9, do: number_frac_cont(rest, original, skip, acc, stack, decode, len + 1)

  defp number_frac(_, original, skip, acc, stack, decode, len), do: unexpected(original, skip, acc, stack, decode, len, 0, :value)

  defp number_frac_cont(<<byte, rest :: bits>>, original, skip, acc, stack, decode, len) when byte >= ?0 and byte <= ?9, do: number_frac_cont(rest, original, skip, acc, stack, decode, len + 1)

  defp number_frac_cont(<<e, rest :: bits>>, original, skip, acc, stack, decode, len) when e === ?e or e === ?E, do: number_exp(rest, original, skip, acc, stack, decode, len + 1)

  defp number_frac_cont(rest, original, skip, acc, stack, decode, len) do
    token = binary_part(original, skip, len)
    float_decode(rest, original, skip, acc, stack, decode, len, token)
  end

  defp number_minus(<<?0, rest :: bits>>, original, skip, acc, stack, decode), do: number_zero(rest, original, skip, acc, stack, decode, 2)

  defp number_minus(<<num, rest :: bits>>, original, skip, acc, stack, decode) when num === ?1 or num === ?2 or num === ?3 or num === ?4 or num === ?5 or num === ?6 or num === ?7 or num === ?8 or num === ?9, do: number(rest, original, skip, acc, stack, decode, 2)

  defp number_minus(_Rest, original, skip, acc, stack, decode), do: unexpected(original, skip, acc, stack, decode, 1, 0, :value)

  defp number_zero(<<?., rest :: bits>>, original, skip, acc, stack, decode, len), do: number_frac(rest, original, skip, acc, stack, decode, len + 1)

  defp number_zero(<<e, rest :: bits>>, original, skip, acc, stack, decode, len) when e === ?E or e === ?e, do: number_exp_copy(rest, original, skip, acc, stack, decode, len + 1, "0")

  defp number_zero(<<>>, original, skip, acc, stack, decode, len) do
    value = decode(decode, :integer)("0")
    unexpected(original, skip, acc, stack, decode, len, 0, {:number, value})
  end

  defp number_zero(rest, original, skip, acc, stack, decode, len) do
    value = decode(decode, :integer)("0")
    continue(rest, original, skip + len, acc, stack, decode, value)
  end

  defp object_key(<<byte, rest :: bits>>, original, skip, acc, stack, decode) when byte === ?\s or byte === ?\t or byte === ?\r or byte === ?\n, do: object_key(rest, original, skip + 1, acc, stack, decode)

  defp object_key(<<?", rest :: bits>>, original, skip, acc, stack, decode), do: string(rest, original, skip + 1, acc, stack, decode)

  defp object_key(_, original, skip, acc, stack, decode), do: unexpected(original, skip, acc, stack, decode, 0, 0, :object_key)

  defp object_push(<<byte, rest :: bits>>, original, skip, acc, stack, decode, value, key) when byte === ?\s or byte === ?\t or byte === ?\r or byte === ?\n, do: object_push(rest, original, skip + 1, acc, stack, decode, value, key)

  defp object_push(<<"}", rest :: bits>>, original, skip, acc0, stack0, decode, value, key) do
    acc = case decode(decode, :object_push) do
      :undefined ->
        [{key, value} | acc0]
      fun ->
        fun.(key, value, acc0)
    end
    [_, oldAcc | stack] = stack0
    {objectValue, newAcc} = case decode(decode, :object_finish) do
      :undefined ->
        {:maps.from_list(acc), oldAcc}
      finish ->
        finish.(acc, oldAcc)
    end
    continue(rest, original, skip + 1, newAcc, stack, decode, objectValue)
  end

  defp object_push(<<?,, rest :: bits>>, original, skip, acc0, stack, decode, value, key) do
    case decode(decode, :object_push) do
      :undefined ->
        object_key(rest, original, skip + 1, [{key, value} | acc0], stack, decode)
      fun ->
        object_key(rest, original, skip + 1, fun.(key, value, acc0), stack, decode)
    end
  end

  defp object_push(_, original, skip, acc, stack, decode, value, key), do: unexpected(original, skip, acc, stack, decode, 0, 0, {:object_push, value, key})

  defp object_start(<<byte, rest :: bits>>, original, skip, acc, stack, decode, len) when byte === ?\s or byte === ?\t or byte === ?\r or byte === ?\n, do: object_start(rest, original, skip, acc, stack, decode, len + 1)

  defp object_start(<<"}", rest :: bits>>, original, skip, acc, stack, decode, len) do
    {value, newAcc} = case {decode(decode, :object_start), decode(decode, :object_finish)} do
      {:undefined, :undefined} ->
        {%{}, acc}
      {start, :undefined} ->
        {:maps.from_list(start.(acc)), acc}
      {:undefined, finish} ->
        finish.([], acc)
      {start, finish} ->
        finish.(start.(acc), acc)
    end
    continue(rest, original, skip + len + 1, newAcc, stack, decode, value)
  end

  defp object_start(<<?", rest :: bits>>, original, skip0, oldAcc, stack0, decode, len) do
    stack = [:object, oldAcc | stack0]
    skip = skip0 + len + 1
    case decode(decode, :object_start) do
      :undefined ->
        string(rest, original, skip, [], stack, decode)
      fun ->
        acc = fun.(oldAcc)
        string(rest, original, skip, acc, stack, decode)
    end
  end

  defp object_start(_, original, skip, acc, stack, decode, len), do: unexpected(original, skip, acc, stack, decode, len, 0, :value)

  defp object_value(<<byte, rest :: bits>>, original, skip, acc, stack, decode, key) when byte === ?\s or byte === ?\t or byte === ?\r or byte === ?\n, do: object_value(rest, original, skip + 1, acc, stack, decode, key)

  defp object_value(<<?:, rest :: bits>>, original, skip, acc, stack, decode, key), do: value(rest, original, skip + 1, acc, [key | stack], decode)

  defp object_value(_, original, skip, acc, stack, decode, key), do: unexpected(original, skip, acc, stack, decode, 0, 0, {:object_value, key})

  defp parse_decoder(:array_start, fun, decode) when is_function(fun, 1), do: decode(decode, array_start: fun)

  defp parse_decoder(:array_push, fun, decode) when is_function(fun, 2), do: decode(decode, array_push: fun)

  defp parse_decoder(:array_finish, fun, decode) when is_function(fun, 2), do: decode(decode, array_finish: fun)

  defp parse_decoder(:object_start, fun, decode) when is_function(fun, 1), do: decode(decode, object_start: fun)

  defp parse_decoder(:object_push, fun, decode) when is_function(fun, 3), do: decode(decode, object_push: fun)

  defp parse_decoder(:object_finish, fun, decode) when is_function(fun, 2), do: decode(decode, object_finish: fun)

  defp parse_decoder(:float, fun, decode) when is_function(fun, 1), do: decode(decode, float: fun)

  defp parse_decoder(:integer, fun, decode) when is_function(fun, 1), do: decode(decode, integer: fun)

  defp parse_decoder(:string, fun, decode) when is_function(fun, 1), do: decode(decode, string: fun)

  defp parse_decoder(:null, null, decode), do: decode(decode, null: null)

  defp steps(0), do: <<"\n" :: utf8>>

  defp steps(2), do: <<"\n  " :: utf8>>

  defp steps(4), do: <<"\n    " :: utf8>>

  defp steps(6), do: <<"\n      " :: utf8>>

  defp steps(8), do: <<"\n        " :: utf8>>

  defp steps(10), do: <<"\n          " :: utf8>>

  defp steps(12), do: <<"\n            " :: utf8>>

  defp steps(14), do: <<"\n              " :: utf8>>

  defp steps(16), do: <<"\n                " :: utf8>>

  defp steps(18), do: <<"\n                  " :: utf8>>

  defp steps(20), do: <<"\n                    " :: utf8>>

  defp steps(n), do: ['\n', :lists.duplicate(n, ' ')]

  @spec string(binary(), binary(), integer(), acc(), stack(), decode(), integer()) :: dynamic()
  defp string(<<byte, rest :: bits>>, orig, skip, acc, stack, decode, len) when byte === 32 or byte === 33 or byte === 35 or byte === 36 or byte === 37 or byte === 38 or byte === 39 or byte === 40 or byte === 41 or byte === 42 or byte === 43 or byte === 44 or byte === 45 or byte === 46 or byte === 47 or byte === 48 or byte === 49 or byte === 50 or byte === 51 or byte === 52 or byte === 53 or byte === 54 or byte === 55 or byte === 56 or byte === 57 or byte === 58 or byte === 59 or byte === 60 or byte === 61 or byte === 62 or byte === 63 or byte === 64 or byte === 65 or byte === 66 or byte === 67 or byte === 68 or byte === 69 or byte === 70 or byte === 71 or byte === 72 or byte === 73 or byte === 74 or byte === 75 or byte === 76 or byte === 77 or byte === 78 or byte === 79 or byte === 80 or byte === 81 or byte === 82 or byte === 83 or byte === 84 or byte === 85 or byte === 86 or byte === 87 or byte === 88 or byte === 89 or byte === 90 or byte === 91 or byte === 93 or byte === 94 or byte === 95 or byte === 96 or byte === 97 or byte === 98 or byte === 99 or byte === 100 or byte === 101 or byte === 102 or byte === 103 or byte === 104 or byte === 105 or byte === 106 or byte === 107 or byte === 108 or byte === 109 or byte === 110 or byte === 111 or byte === 112 or byte === 113 or byte === 114 or byte === 115 or byte === 116 or byte === 117 or byte === 118 or byte === 119 or byte === 120 or byte === 121 or byte === 122 or byte === 123 or byte === 124 or byte === 125 or byte === 126 or byte === 127, do: string(rest, orig, skip, acc, stack, decode, len + 1)

  defp string(<<?\\, rest :: bits>>, orig, skip, acc, stack, decode, len) do
    part = binary_part(orig, skip, len)
    sAcc = <<>>
    unescape(rest, orig, skip, acc, stack, decode, skip - 1, len, <<sAcc :: binary, part :: binary>>)
  end

  defp string(<<?", rest :: bits>>, orig, skip0, acc, stack, decode, len) do
    value = binary_part(orig, skip0, len)
    skip = skip0 + len + 1
    case decode(decode, :string) do
      :undefined ->
        continue(rest, orig, skip, acc, stack, decode, value)
      fun ->
        continue(rest, orig, skip, acc, stack, decode, fun.(value))
    end
  end

  defp string(<<byte, _ :: bits>>, orig, skip, _Acc, _Stack, _Decode, len) when byte === 0 or byte === 1 or byte === 2 or byte === 3 or byte === 4 or byte === 5 or byte === 6 or byte === 7 or byte === 8 or byte === 9 or byte === 10 or byte === 11 or byte === 12 or byte === 13 or byte === 14 or byte === 15 or byte === 16 or byte === 17 or byte === 18 or byte === 19 or byte === 20 or byte === 21 or byte === 22 or byte === 23 or byte === 24 or byte === 25 or byte === 26 or byte === 27 or byte === 28 or byte === 29 or byte === 30 or byte === 31 or byte === 34 or byte === 92, do: invalid_byte(orig, skip + len)

  defp string(<<byte, rest :: bytes>>, orig, skip, acc, stack, decode, len) do
    case element(byte - 127, utf8s0()) do
      12 ->
        invalid_byte(orig, skip + len)
      state ->
        string_utf8(rest, orig, skip, acc, stack, decode, len, state)
    end
  end

  defp string(_, orig, skip, acc, stack, decode, len), do: unexpected(orig, skip - 1, acc, stack, decode, len + 1, 0, :value)

  @spec string(binary(), binary(), integer(), acc(), stack(), decode(), integer(), integer(), binary()) :: dynamic()
  defp string(<<byte, rest :: bits>>, orig, skip, acc, stack, decode, start, len, sAcc) when byte === 32 or byte === 33 or byte === 35 or byte === 36 or byte === 37 or byte === 38 or byte === 39 or byte === 40 or byte === 41 or byte === 42 or byte === 43 or byte === 44 or byte === 45 or byte === 46 or byte === 47 or byte === 48 or byte === 49 or byte === 50 or byte === 51 or byte === 52 or byte === 53 or byte === 54 or byte === 55 or byte === 56 or byte === 57 or byte === 58 or byte === 59 or byte === 60 or byte === 61 or byte === 62 or byte === 63 or byte === 64 or byte === 65 or byte === 66 or byte === 67 or byte === 68 or byte === 69 or byte === 70 or byte === 71 or byte === 72 or byte === 73 or byte === 74 or byte === 75 or byte === 76 or byte === 77 or byte === 78 or byte === 79 or byte === 80 or byte === 81 or byte === 82 or byte === 83 or byte === 84 or byte === 85 or byte === 86 or byte === 87 or byte === 88 or byte === 89 or byte === 90 or byte === 91 or byte === 93 or byte === 94 or byte === 95 or byte === 96 or byte === 97 or byte === 98 or byte === 99 or byte === 100 or byte === 101 or byte === 102 or byte === 103 or byte === 104 or byte === 105 or byte === 106 or byte === 107 or byte === 108 or byte === 109 or byte === 110 or byte === 111 or byte === 112 or byte === 113 or byte === 114 or byte === 115 or byte === 116 or byte === 117 or byte === 118 or byte === 119 or byte === 120 or byte === 121 or byte === 122 or byte === 123 or byte === 124 or byte === 125 or byte === 126 or byte === 127, do: string(rest, orig, skip, acc, stack, decode, start, len + 1, sAcc)

  defp string(<<?\\, rest :: bits>>, orig, skip, acc, stack, decode, start, len, sAcc) do
    part = binary_part(orig, skip, len)
    unescape(rest, orig, skip, acc, stack, decode, start, len, <<sAcc :: binary, part :: binary>>)
  end

  defp string(<<?", rest :: bits>>, orig, skip0, acc, stack, decode, _Start, len, sAcc) do
    part = binary_part(orig, skip0, len)
    value = <<sAcc :: binary, part :: binary>>
    skip = skip0 + len + 1
    case decode(decode, :string) do
      :undefined ->
        continue(rest, orig, skip, acc, stack, decode, value)
      fun ->
        continue(rest, orig, skip, acc, stack, decode, fun.(value))
    end
  end

  defp string(<<byte, _ :: bits>>, orig, skip, _Acc, _Stack, _Decode, _Start, len, _SAcc) when byte === 0 or byte === 1 or byte === 2 or byte === 3 or byte === 4 or byte === 5 or byte === 6 or byte === 7 or byte === 8 or byte === 9 or byte === 10 or byte === 11 or byte === 12 or byte === 13 or byte === 14 or byte === 15 or byte === 16 or byte === 17 or byte === 18 or byte === 19 or byte === 20 or byte === 21 or byte === 22 or byte === 23 or byte === 24 or byte === 25 or byte === 26 or byte === 27 or byte === 28 or byte === 29 or byte === 30 or byte === 31 or byte === 34 or byte === 92, do: invalid_byte(orig, skip + len)

  defp string(<<byte, rest :: bytes>>, orig, skip, acc, stack, decode, start, len, sAcc) do
    case element(byte - 127, utf8s0()) do
      12 ->
        invalid_byte(orig, skip + len)
      state ->
        string_utf8(rest, orig, skip, acc, stack, decode, start, len, sAcc, state)
    end
  end

  defp string(_, orig, skip, acc, stack, decode, start, len, _SAcc) do
    extra = skip - start
    unexpected(orig, start, acc, stack, decode, len + extra, 0, :value)
  end

  defp string_ascii(binary, original, skip, acc, stack, decode, len) do
    case binary do
      <<w :: 56, rest :: binary>> when w &&& 36170086419038336 === 0 and w + 27127564814278752 &&& 36170086419038336 === 36170086419038336 and w ^^^ 9607679205057058 - 282578800148737 &&& 36170086419038336 === 0 and w ^^^ 25997249613683804 - 282578800148737 &&& 36170086419038336 === 0 ->
        string_ascii(rest, original, skip, acc, stack, decode, len + 7)
      other ->
        string(other, original, skip, acc, stack, decode, len)
    end
  end

  defp string_ascii(binary, original, skip, acc, stack, decode, start, len, sAcc) do
    case binary do
      <<w :: 56, rest :: binary>> when w &&& 36170086419038336 === 0 and w + 27127564814278752 &&& 36170086419038336 === 36170086419038336 and w ^^^ 9607679205057058 - 282578800148737 &&& 36170086419038336 === 0 and w ^^^ 25997249613683804 - 282578800148737 &&& 36170086419038336 === 0 ->
        string_ascii(rest, original, skip, acc, stack, decode, start, len + 7, sAcc)
      other ->
        string(other, original, skip, acc, stack, decode, start, len, sAcc)
    end
  end

  defp string_utf8(<<byte, rest :: binary>>, orig, skip, acc, stack, decode, len, state0) do
    type = element(byte + 1, utf8t())
    case element(state0 + type, utf8s()) do
      0 ->
        string_ascii(rest, orig, skip, acc, stack, decode, len + 2)
      12 ->
        invalid_byte(orig, skip + len + 1)
      state ->
        string_utf8(rest, orig, skip, acc, stack, decode, len + 1, state)
    end
  end

  defp string_utf8(_, orig, skip, acc, stack, decode, len, _State0), do: unexpected(orig, skip - 1, acc, stack, decode, len + 2, 0, :value)

  defp string_utf8(<<byte, rest :: binary>>, orig, skip, acc, stack, decode, start, len, sAcc, state0) do
    type = element(byte + 1, utf8t())
    case element(state0 + type, utf8s()) do
      0 ->
        string_ascii(rest, orig, skip, acc, stack, decode, start, len + 2, sAcc)
      12 ->
        invalid_byte(orig, skip + len + 1)
      state ->
        string_utf8(rest, orig, skip, acc, stack, decode, start, len + 1, sAcc, state)
    end
  end

  defp string_utf8(_, orig, skip, acc, stack, decode, start, len, _SAcc, _State0) do
    extra = skip - start
    unexpected(orig, start, acc, stack, decode, len + 1 + extra, 0, :value)
  end

  defp terminate(<<byte, rest :: bits>>, original, skip, acc, value) when byte === ?\s or byte === ?\t or byte === ?\r or byte === ?\n, do: terminate(rest, original, skip, acc, value)

  defp terminate(<<>>, _, _Skip, acc, value), do: {value, acc, <<>>}

  defp terminate(<<_ :: bits>>, original, skip, acc, value) do
    <<_ :: size(skip)-binary, rest :: binary>> = original
    {value, acc, rest}
  end

  defp unquote(:true)(<<"rue", rest :: bits>>, original, skip, acc, stack, decode), do: continue(rest, original, skip + 4, acc, stack, decode, true)

  defp unquote(:true)(_Rest, original, skip, acc, stack, decode), do: unexpected(original, skip, acc, stack, decode, 1, 3, :value)

  defp unescape(<<byte, rest :: bits>>, original, skip, acc, stack, decode, start, len, sAcc) do
    val = case byte do
      ?b ->
        ?
      ?f ->
        ?
      ?n ->
        ?\n
      ?r ->
        ?\r
      ?t ->
        ?\t
      ?" ->
        ?"
      ?\\ ->
        ?\\
      ?/ ->
        ?/
      ?u ->
        :unicode
      _ ->
        :error
    end
    case val do
      :unicode ->
        unescapeu(rest, original, skip, acc, stack, decode, start, len, sAcc)
      :error ->
        invalid_byte(original, skip + len + 1)
      int ->
        string_ascii(rest, original, skip + len + 2, acc, stack, decode, start, 0, <<sAcc :: binary, int>>)
    end
  end

  defp unescape(_, original, skip, acc, stack, decode, start, len, _SAcc) do
    extra = skip - start
    unexpected(original, start, acc, stack, decode, len + 1 + extra, 0, :value)
  end

  defp unescape_surrogate(<<"\\u", e1, e2, e3, e4, rest :: bits>>, original, skip, acc, stack, decode, start, len, sAcc, hi) do
    try do
      hex_to_int(e1, e2, e3, e4)
    catch
      {_, _, _} ->
        unexpected_sequence(binary_part(original, skip + len, 12), skip + len)
    else
      lo when lo >= 56320 and lo <= 57343 ->
        cP = 65536 + hi &&& 1023 <<< 10 + lo &&& 1023
        try do
          <<sAcc :: binary, cP :: utf8>>
        catch
          {_, _, _} ->
            unexpected_sequence(binary_part(original, skip + len, 12), skip + len)
        else
          sAcc1 ->
            string_ascii(rest, original, skip + len + 12, acc, stack, decode, start, 0, sAcc1)
        end
      _ ->
        unexpected_sequence(binary_part(original, skip + len, 12), skip + len)
    end
  end

  defp unescape_surrogate(_Rest, original, skip, acc, stack, decode, start, len, _SAcc, _Hi) do
    extra = skip - start
    unexpected(original, start, acc, stack, decode, len + 6 + extra, 5, :value)
  end

  defp unescapeu(<<e1, e2, e3, e4, rest :: bits>>, original, skip, acc, stack, decode, start, len, sAcc) do
    try do
      hex_to_int(e1, e2, e3, e4)
    catch
      {_, _, _} ->
        unexpected_sequence(binary_part(original, skip + len, 6), skip + len)
    else
      cP when cP >= 55296 and cP <= 56319 ->
        unescape_surrogate(rest, original, skip, acc, stack, decode, start, len, sAcc, cP)
      cP ->
        try do
          <<sAcc :: binary, cP :: utf8>>
        catch
          {_, _, _} ->
            unexpected_sequence(binary_part(original, skip + len, 6), skip + len)
        else
          sAcc1 ->
            string_ascii(rest, original, skip + len + 6, acc, stack, decode, start, 0, sAcc1)
        end
    end
  end

  defp unescapeu(_Rest, original, skip, acc, stack, decode, start, len, _SAcc) do
    extra = skip - start
    unexpected(original, start, acc, stack, decode, len + 2 + extra, 4, :value)
  end

  defp unexpected(original, skip, acc, stack, decode, pos, len, funcData) do
    requiredSize = skip + pos + len
    origSize = byte_size(original)
    case origSize <= requiredSize do
      true ->
        <<_ :: size(skip)-binary, rest :: binary>> = original
        {:continue, {rest, acc, stack, decode, funcData}}
      false ->
        invalid_byte(original, skip + pos)
    end
  end

  @spec unexpected_sequence(binary(), non_neg_integer()) :: no_return()
  defp unexpected_sequence(value, skip), do: error({:unexpected_sequence, value}, :none, error_info(skip))

  @spec unexpected_utf8(binary(), non_neg_integer()) :: no_return()
  defp unexpected_utf8(original, skip) when byte_size(original) === skip, do: error(:unexpected_end)

  defp unexpected_utf8(original, skip), do: invalid_byte(original, skip)

  defp value(<<byte, rest :: bits>>, original, skip, acc, stack, decode) when byte === ?\s or byte === ?\t or byte === ?\r or byte === ?\n, do: value(rest, original, skip + 1, acc, stack, decode)

  defp value(<<?0, rest :: bits>>, original, skip, acc, stack, decode), do: number_zero(rest, original, skip, acc, stack, decode, 1)

  defp value(<<byte, rest :: bits>>, original, skip, acc, stack, decode) when byte === ?1 or byte === ?2 or byte === ?3 or byte === ?4 or byte === ?5 or byte === ?6 or byte === ?7 or byte === ?8 or byte === ?9, do: number(rest, original, skip, acc, stack, decode, 1)

  defp value(<<?-, rest :: bits>>, original, skip, acc, stack, decode), do: number_minus(rest, original, skip, acc, stack, decode)

  defp value(<<?t, rest :: bits>>, original, skip, acc, stack, decode), do: apply(__MODULE__, true, [rest, original, skip, acc, stack, decode])

  defp value(<<?f, rest :: bits>>, original, skip, acc, stack, decode), do: apply(__MODULE__, false, [rest, original, skip, acc, stack, decode])

  defp value(<<?n, rest :: bits>>, original, skip, acc, stack, decode), do: null(rest, original, skip, acc, stack, decode)

  defp value(<<?", rest :: bits>>, original, skip, acc, stack, decode), do: string(rest, original, skip + 1, acc, stack, decode)

  defp value(<<?[, rest :: bits>>, original, skip, acc, stack, decode), do: array_start(rest, original, skip, acc, stack, decode, 1)

  defp value(<<?{, rest :: bits>>, original, skip, acc, stack, decode), do: object_start(rest, original, skip, acc, stack, decode, 1)

  defp value(<<byte, _ :: bits>>, original, skip, _Acc, _Stack, _Decode) when byte === 32 or byte === 33 or byte === 35 or byte === 36 or byte === 37 or byte === 38 or byte === 39 or byte === 40 or byte === 41 or byte === 42 or byte === 43 or byte === 44 or byte === 45 or byte === 46 or byte === 47 or byte === 48 or byte === 49 or byte === 50 or byte === 51 or byte === 52 or byte === 53 or byte === 54 or byte === 55 or byte === 56 or byte === 57 or byte === 58 or byte === 59 or byte === 60 or byte === 61 or byte === 62 or byte === 63 or byte === 64 or byte === 65 or byte === 66 or byte === 67 or byte === 68 or byte === 69 or byte === 70 or byte === 71 or byte === 72 or byte === 73 or byte === 74 or byte === 75 or byte === 76 or byte === 77 or byte === 78 or byte === 79 or byte === 80 or byte === 81 or byte === 82 or byte === 83 or byte === 84 or byte === 85 or byte === 86 or byte === 87 or byte === 88 or byte === 89 or byte === 90 or byte === 91 or byte === 93 or byte === 94 or byte === 95 or byte === 96 or byte === 97 or byte === 98 or byte === 99 or byte === 100 or byte === 101 or byte === 102 or byte === 103 or byte === 104 or byte === 105 or byte === 106 or byte === 107 or byte === 108 or byte === 109 or byte === 110 or byte === 111 or byte === 112 or byte === 113 or byte === 114 or byte === 115 or byte === 116 or byte === 117 or byte === 118 or byte === 119 or byte === 120 or byte === 121 or byte === 122 or byte === 123 or byte === 124 or byte === 125 or byte === 126 or byte === 127, do: invalid_byte(original, skip)

  defp value(_, original, skip, acc, stack, decode), do: unexpected(original, skip, acc, stack, decode, 0, 0, :value)
end
