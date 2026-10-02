# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :dict do
  @moduledoc ~S"""
  A Key-value dictionary.

  The representation of a dictionary is not defined.

  This module provides the same interface as the `m:orddict` module. One
  difference is that while this module considers two keys as different
  if they do not match (`=:=`), `orddict` considers two keys as
  different if and only if they do not compare equal (`==`).

  > #### Note {: .info }
  >
  > For new code, prefer `m:maps` over this module.

  ### See Also

  `m:gb_trees`, `m:orddict`, `m:maps`
  """

  # Types

  @type dict :: dict(any(), any())

  @typedoc ~S"""
  Dictionary as returned by `new/0`.
  """
  @opaque dict(key, value) :: dict(segs :: segs(key, value))

  # Private Types

  @typep segs(_Key, _Value) :: tuple()

  # Functions

  @doc ~S"""
  Appends a new `Value` to the current list of values associated with `Key`.

  ## Examples

  ```erlang
  1> Dict0 = dict:from_list([{a,[]}]).
  2> Dict1 = dict:append(a, 1, Dict0).
  3> dict:to_list(Dict1).
  [{a,[1]}]
  4> Dict2 = dict:append(a, 10, Dict1).
  5> dict:to_list(Dict2).
  [{a,[1,10]}]
  ```
  """
  @spec append(key, value, dict1) :: dict2 when dict1: dict(key, value), dict2: dict(key, value)
  def append(key, val, d0) do
    slot = get_slot(d0, key)
    {d1, ic} = on_bucket(fn b0 ->
        append_bkt(key, val, b0)
    end, d0, slot)
    maybe_expand(d1, ic)
  end

  @doc ~S"""
  Appends a list of values `ValList` to the current list of values associated with
  `Key`.

  An exception is generated if the initial value associated with `Key` is
  not a list of values.

  ## Examples

  ```erlang
  1> Dict0 = dict:from_list([{a,[]}]).
  2> Dict1 = dict:append_list(a, [1,2,3], Dict0).
  3> dict:to_list(Dict1).
  [{a,[1,2,3]}]
  ```
  """
  @spec append_list(key, valList, dict1) :: dict2 when dict1: dict(key, value), dict2: dict(key, value), valList: [value]
  def append_list(key, l, d0) do
    slot = get_slot(d0, key)
    {d1, ic} = on_bucket(fn b0 ->
        app_list_bkt(key, l, b0)
    end, d0, slot)
    maybe_expand(d1, ic)
  end

  @doc ~S"""
  Erases an item with a given key from a dictionary.

  ## Examples

  ```erlang
  1> Dict0 = dict:from_list([{2,b},{1,a}]).
  2> Dict1 = dict:erase(2, Dict0).
  3> dict:to_list(Dict1).
  [{1,a}]
  4> Dict2 = dict:erase(99, Dict0).
  ```
  """
  @spec erase(key, dict1) :: dict2 when dict1: dict(key, value), dict2: dict(key, value)
  def erase(key, d0) do
    slot = get_slot(d0, key)
    {d1, dc} = on_bucket(fn b0 ->
        erase_key(key, b0)
    end, d0, slot)
    maybe_contract(d1, dc)
  end

  @doc ~S"""
  Returns the value associated with `Key` in dictionary `Dict`.

  This function assumes that `Key` is present in dictionary `Dict`, and
  an exception is generated if `Key` is not in the dictionary.

  ## Examples

  ```erlang
  1> Dict = dict:from_list([{2,b},{1,a}]).
  2> dict:fetch(1, Dict).
  a
  ```
  """
  @spec fetch(key, dict) :: value when dict: dict(key, value)
  def fetch(key, d) do
    slot = get_slot(d, key)
    bkt = get_bucket(d, slot)
    try do
      fetch_val(key, bkt)
    catch
      {:throw, :badarg, _} ->
        :erlang.error(:badarg, [key, d])
    end
  end

  @doc ~S"""
  Returns a list of all keys in dictionary `Dict`.

  ## Examples

  ```erlang
  1> Dict = dict:from_list([{2,b},{1,a}]).
  2> lists:sort(dict:fetch_keys(Dict)).
  [1,2]
  ```
  """
  @spec fetch_keys(dict) :: keys when dict: dict(key, value :: term()), keys: [key]
  def fetch_keys(d) do
    fold(fn key, _Val, keys ->
        [key | keys]
    end, [], d)
  end

  @doc ~S"""
  Returns a dictionary of all keys and values in `Dict1` for which
  `Pred(Key, Value)` is `true`.

  ## Examples

  ```erlang
  1> Dict0 = dict:from_list([{a,1},{b,2},{c,3}]).
  2> Dict1 = dict:filter(fun(_, V) -> V rem 2 =:= 1 end, Dict0).
  3> lists:sort(dict:to_list(Dict1)).
  [{a,1},{c,3}]
  ```
  """
  @spec filter(pred, dict1) :: dict2 when pred: (key, value -> boolean()), dict1: dict(key, value), dict2: dict(key, value)
  def filter(f, d), do: filter_dict(f, d)

  @doc ~S"""
  Searches for a key in dictionary `Dict`.

  Returns `{ok, Value}`, where `Value` is the value associated with
  `Key`, or `error` if the key is not present in the dictionary.

  ## Examples

  ```erlang
  1> Dict = dict:from_list([{2,b},{1,a}]).
  2> dict:find(1, Dict).
  {ok,a}
  3> dict:find(99, Dict).
  error
  ```
  """
  @spec find(key, dict) :: ({:ok, value} | :error) when dict: dict(key, value)
  def find(key, d) do
    slot = get_slot(d, key)
    bkt = get_bucket(d, slot)
    find_val(key, bkt)
  end

  @doc ~S"""
  Calls `Fun` on successive keys and values of dictionary `Dict` together with an
  extra argument `Acc` (short for accumulator).

  `Fun` must return a new accumulator that is passed to the next
  call. `Acc0` is returned if the dictionary is empty.

  The evaluation order is undefined.

  ## Examples

  ```erlang
  1> Dict0 = dict:from_list([{a,1},{b,2},{c,3}]).
  2> dict:fold(fun(_, N, Acc) -> Acc + N end, 0, Dict0).
  6
  ```
  """
  @spec fold(fun, acc0, dict) :: acc1 when fun: (key, value, accIn -> accOut), dict: dict(key, value), acc0: acc, acc1: acc, accIn: acc, accOut: acc
  def fold(f, acc, d), do: fold_dict(f, acc, d)

  @doc ~S"""
  Converts the `Key`-`Value` list `List` to a dictionary.

  ## Examples

  ```erlang
  1> Dict = dict:from_list([{2,b},{1,a}]).
  2> lists:sort(dict:to_list(Dict)).
  [{1,a},{2,b}]
  ```
  """
  @spec from_list(list) :: dict when dict: dict(key, value), list: [{key, value}]
  def from_list(l) do
    :lists.foldl(fn {k, v}, d ->
        store(k, v, d)
    end, new(), l)
  end

  @doc ~S"""
  Returns `true` if dictionary `Dict` has no elements; otherwise,
  returns `false`.

  ## Examples

  ```erlang
  1> Dict = dict:new().
  2> dict:is_empty(Dict).
  true
  ```
  """
  @spec is_empty(dict) :: boolean() when dict: dict()
  def is_empty(dict(size: n)), do: n === 0

  @doc ~S"""
  Tests whether `Key` is contained in dictionary `Dict`.

  ## Examples

  ```erlang
  1> Dict = dict:from_list([{count,0}]).
  2> dict:is_key(count, Dict).
  true
  3> dict:is_key(table, Dict).
  false
  ```
  """
  @spec is_key(key, dict) :: boolean() when dict: dict(key, value :: term())
  def is_key(key, d) do
    slot = get_slot(d, key)
    bkt = get_bucket(d, slot)
    find_key(key, bkt)
  end

  @doc ~S"""
  Calls `Fun` on successive keys and values of dictionary `Dict1` to return a new
  value for each key.

  The evaluation order is undefined.

  ## Examples

  ```erlang
  1> Dict0 = dict:from_list([{a,1},{b,2},{c,3}]).
  2> Dict1 = dict:map(fun(_, V) -> 2 * V end, Dict0).
  3> lists:sort(dict:to_list(Dict1)).
  [{a,2},{b,4},{c,6}]
  ```
  """
  @spec map(fun, dict1) :: dict2 when fun: (key, value1 -> value2), dict1: dict(key, value1), dict2: dict(key, value2)
  def map(f, d), do: map_dict(f, d)

  @doc ~S"""
  Merges two dictionaries, `Dict1` and `Dict2`, to create a new dictionary.

  All the `Key`-`Value` pairs from both dictionaries are included in the
  new dictionary. If a key occurs in both dictionaries, `Fun` is called
  with the key and both values to return a new value.

  ## Examples

  ```erlang
  1> Dict0 = dict:from_list([{a,1},{b,2}]).
  2> Dict1 = dict:from_list([{b,7},{c,99}]).
  3> Dict2 = dict:merge(fun(_, V1, V2) -> V1 + V2 end, Dict0, Dict1).
  4> lists:sort(dict:to_list(Dict2)).
  [{a,1},{b,9},{c,99}]
  ```
  """
  @spec merge(fun, dict1, dict2) :: dict3 when fun: (key, value1, value2 -> value), dict1: dict(key, value1), dict2: dict(key, value2), dict3: dict(key, value)
  def merge(f, d1, d2) when dict(d1, :size) < dict(d2, :size) do
    fold_dict(fn k, v1, d ->
        update(k, fn v2 ->
            f.(k, v1, v2)
        end, v1, d)
    end, d2, d1)
  end

  def merge(f, d1, d2) do
    fold_dict(fn k, v2, d ->
        update(k, fn v1 ->
            f.(k, v1, v2)
        end, v2, d)
    end, d1, d2)
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @doc ~S"""
  Creates a new dictionary.

  ## Examples

  ```erlang
  1> dict:new().
  ```
  """
  @spec new() :: dict()
  def new() do
    empty = mk_seg(16)
    dict(empty: empty, segs: {empty})
  end

  @doc ~S"""
  Returns the number of elements in dictionary `Dict`.

  ## Examples

  ```erlang
  1> Dict = dict:from_list([{2,b},{1,a}]).
  2> dict:size(Dict).
  2
  ```
  """
  @spec size(dict) :: non_neg_integer() when dict: dict()
  def size(dict(size: n)) when is_integer(n) and n >= 0, do: n

  @doc ~S"""
  Stores a `Key`-`Value` pair in a dictionary.

  If `Key` already exists in `Dict1`, the associated value is replaced
  by `Value`.

  ## Examples

  ```erlang
  1> Dict0 = dict:new().
  2> Dict1 = dict:store(name, arne, Dict0).
  3> dict:to_list(Dict1).
  [{name,arne}]
  4> Dict2 = dict:store(name, kalle, Dict1).
  5> dict:to_list(Dict2).
  [{name,kalle}]
  ```
  """
  @spec store(key, value, dict1) :: dict2 when dict1: dict(key, value), dict2: dict(key, value)
  def store(key, val, d0) do
    slot = get_slot(d0, key)
    {d1, ic} = on_bucket(fn b0 ->
        store_bkt_val(key, val, b0)
    end, d0, slot)
    maybe_expand(d1, ic)
  end

  @doc ~S"""
  This function returns a value from a dictionary and a new dictionary
  without this value.

  Returns `error` if the key is not present in the dictionary.

  ## Examples

  ```erlang
  1> Dict0 = dict:from_list([{2,b},{1,a}]).
  2> {V, Dict2} = dict:take(1, Dict0).
  3> V.
  a
  4> dict:to_list(Dict2).
  [{2,b}]
  ```
  """
  @spec take(key, dict) :: ({value, dict1} | :error) when dict: dict(key, value), dict1: dict(key, value), key: term(), value: term()
  def take(key, d0) do
    slot = get_slot(d0, key)
    case on_bucket(fn b0 ->
        take_key(key, b0)
    end, d0, slot) do
      {d1, {value, dc}} ->
        {value, maybe_contract(d1, dc)}
      {_, :error} ->
        :error
    end
  end

  @doc ~S"""
  Converts a dictionary to a list representation.

  ## Examples

  ```erlang
  1> Dict = dict:from_list([{2,b},{1,a}]).
  2> lists:sort(dict:to_list(Dict)).
  [{1,a},{2,b}]
  ```
  """
  @spec to_list(dict) :: list when dict: dict(key, value), list: [{key, value}]
  def to_list(d) do
    fold(fn key, val, list ->
        [{key, val} | list]
    end, [], d)
  end

  @doc ~S"""
  Updates a value in a dictionary by calling `Fun` on the value to get a new
  value.

  ## Examples

  ```erlang
  1> Dict0 = dict:from_list([{a,10}]).
  2> Dict1 = dict:update(a, fun(N) -> N + 1 end, Dict0).
  3> dict:to_list(Dict1).
  [{a,11}]
  ```

  An exception is generated if `Key` is not present in the dictionary.
  """
  @spec update(key, fun, dict1) :: dict2 when dict1: dict(key, value), dict2: dict(key, value), fun: (value1 :: value -> value2 :: value)
  def update(key, f, d0) do
    slot = get_slot(d0, key)
    try do
      on_bucket(fn b0 ->
        update_bkt(key, f, b0)
    end, d0, slot)
    catch
      {:throw, :badarg, _} ->
        :erlang.error(:badarg, [key, f, d0])
    else
      {d1, _Uv} ->
        d1
    end
  end

  @doc ~S"""
  Updates a value in a dictionary by calling `Fun` on the value to get a new
  value.

  If `Key` is not present in the dictionary, `Initial` is stored as the
  first value.

  ## Examples

  ```erlang
  1> Dict0 = dict:new().
  2> Inc = fun(N) -> N + 1 end.
  3> Dict1 = dict:update(a, Inc, 0, Dict0).
  4> dict:to_list(Dict1).
  [{a,0}]
  5> Dict2 = dict:update(a, Inc, 0, Dict1).
  6> dict:to_list(Dict2).
  [{a,1}]
  ```
  """
  @spec update(key, fun, initial, dict1) :: dict2 when dict1: dict(key, value), dict2: dict(key, value), fun: (value1 :: value -> value2 :: value), initial: value
  def update(key, f, init, d0) do
    slot = get_slot(d0, key)
    {d1, ic} = on_bucket(fn b0 ->
        update_bkt(key, f, init, b0)
    end, d0, slot)
    maybe_expand(d1, ic)
  end

  @doc ~S"""
  Adds `Increment` to the value associated with `Key` and stores this value.

  If `Key` is not present in the dictionary, `Increment` is stored as
  the first value.

  ## Examples

  ```erlang
  1> Dict0 = dict:new().
  2> Dict1 = dict:update_counter(a, 10, Dict0).
  3> dict:to_list(Dict1).
  [{a,10}]
  4> Dict2 = dict:update_counter(a, 10, Dict1).
  5> dict:to_list(Dict2).
  [{a,20}]
  ```
  """
  @spec update_counter(key, increment, dict1) :: dict2 when dict1: dict(key, value), dict2: dict(key, value), increment: number()
  def update_counter(key, incr, d0) when is_number(incr) do
    slot = get_slot(d0, key)
    {d1, ic} = on_bucket(fn b0 ->
        counter_bkt(key, incr, b0)
    end, d0, slot)
    maybe_expand(d1, ic)
  end

  # Private Functions

  defp unquote(:"-append/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-append_list/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-erase/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-fetch_keys/1-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-from_list/1-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-merge/3-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-merge/3-fun-1-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-merge/3-fun-2-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-merge/3-fun-3-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-store/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-take/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-to_list/1-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-update/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-update/4-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-update_counter/3-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp app_list_bkt(key, l, [[^key | bag] | bkt]), do: {[[key | bag ++ l] | bkt], 0}

  defp app_list_bkt(key, l, [other | bkt0]) do
    {bkt1, ic} = app_list_bkt(key, l, bkt0)
    {[other | bkt1], ic}
  end

  defp app_list_bkt(key, l, []), do: {[[key | l]], 1}

  defp append_bkt(key, val, [[^key | bag] | bkt]), do: {[[key | bag ++ [val]] | bkt], 0}

  defp append_bkt(key, val, [other | bkt0]) do
    {bkt1, ic} = append_bkt(key, val, bkt0)
    {[other | bkt1], ic}
  end

  defp append_bkt(key, val, []), do: {[[key, val]], 1}

  defp contract_segs({b1, _}), do: {b1}

  defp contract_segs({b1, b2, _, _}), do: {b1, b2}

  defp contract_segs({b1, b2, b3, b4, _, _, _, _}), do: {b1, b2, b3, b4}

  defp contract_segs({b1, b2, b3, b4, b5, b6, b7, b8, _, _, _, _, _, _, _, _}), do: {b1, b2, b3, b4, b5, b6, b7, b8}

  defp contract_segs({b1, b2, b3, b4, b5, b6, b7, b8, b9, b10, b11, b12, b13, b14, b15, b16, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _}), do: {b1, b2, b3, b4, b5, b6, b7, b8, b9, b10, b11, b12, b13, b14, b15, b16}

  defp contract_segs(segs) do
    ss = div(tuple_size(segs), 2)
    list_to_tuple(:lists.sublist(tuple_to_list(segs), 1, ss))
  end

  defp counter_bkt(key, i, [[^key | val] | bkt]), do: {[[key | val + i] | bkt], 0}

  defp counter_bkt(key, i, [other | bkt0]) do
    {bkt1, ic} = counter_bkt(key, i, bkt0)
    {[other | bkt1], ic}
  end

  defp counter_bkt(key, i, []), do: {[[key | i]], 1}

  defp erase_key(key, [[^key | _Val] | bkt]), do: {bkt, 1}

  defp erase_key(key, [e | bkt0]) do
    {bkt1, dc} = erase_key(key, bkt0)
    {[e | bkt1], dc}
  end

  defp erase_key(_, []), do: {[], 0}

  defp expand_segs({b1}, empty), do: {b1, empty}

  defp expand_segs({b1, b2}, empty), do: {b1, b2, empty, empty}

  defp expand_segs({b1, b2, b3, b4}, empty), do: {b1, b2, b3, b4, empty, empty, empty, empty}

  defp expand_segs({b1, b2, b3, b4, b5, b6, b7, b8}, empty), do: {b1, b2, b3, b4, b5, b6, b7, b8, empty, empty, empty, empty, empty, empty, empty, empty}

  defp expand_segs({b1, b2, b3, b4, b5, b6, b7, b8, b9, b10, b11, b12, b13, b14, b15, b16}, empty), do: {b1, b2, b3, b4, b5, b6, b7, b8, b9, b10, b11, b12, b13, b14, b15, b16, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty}

  defp expand_segs(segs, empty), do: list_to_tuple(tuple_to_list(segs) ++ :lists.duplicate(tuple_size(segs), empty))

  defp fetch_val(k, [[^k | val] | _]), do: val

  defp fetch_val(k, [_ | bkt]), do: fetch_val(k, bkt)

  defp fetch_val(_, []), do: throw(:badarg)

  defp filter_bkt_list(f, [bkt0 | bkts], fbs, fc0) do
    {bkt1, fc1} = filter_bucket(f, bkt0, [], fc0)
    filter_bkt_list(f, bkts, [bkt1 | fbs], fc1)
  end

  defp filter_bkt_list(f, [], fbs, fc) when is_function(f, 2), do: {:lists.reverse(fbs), fc}

  defp filter_bucket(f, [[key | val] = e | bkt], fb, fc) do
    case f.(key, val) do
      true ->
        filter_bucket(f, bkt, [e | fb], fc)
      false ->
        filter_bucket(f, bkt, fb, fc + 1)
    end
  end

  defp filter_bucket(f, [], fb, fc) when is_function(f, 2), do: {:lists.reverse(fb), fc}

  defp filter_dict(f, dict(size: 0) = dict) when is_function(f, 2), do: dict

  defp filter_dict(f, d) do
    segs0 = tuple_to_list(dict(d, :segs))
    {segs1, fc} = filter_seg_list(f, segs0, [], 0)
    maybe_contract(dict(d, segs: list_to_tuple(segs1)), fc)
  end

  defp filter_seg_list(f, [seg | segs], fss, fc0) do
    bkts0 = tuple_to_list(seg)
    {bkts1, fc1} = filter_bkt_list(f, bkts0, [], fc0)
    filter_seg_list(f, segs, [list_to_tuple(bkts1) | fss], fc1)
  end

  defp filter_seg_list(f, [], fss, fc) when is_function(f, 2), do: {:lists.reverse(fss, []), fc}

  defp find_key(k, [[^k | _Val] | _]), do: true

  defp find_key(k, [_ | bkt]), do: find_key(k, bkt)

  defp find_key(_, []), do: false

  defp find_val(k, [[^k | val] | _]), do: {:ok, val}

  defp find_val(k, [_ | bkt]), do: find_val(k, bkt)

  defp find_val(_, []), do: :error

  defp fold_bucket(f, acc, [[key | val] | bkt]), do: fold_bucket(f, f.(key, val, acc), bkt)

  defp fold_bucket(f, acc, []) when is_function(f, 3), do: acc

  defp fold_dict(f, acc, dict(size: 0)) when is_function(f, 3), do: acc

  defp fold_dict(f, acc, d) do
    segs = dict(d, :segs)
    fold_segs(f, acc, segs, tuple_size(segs))
  end

  defp fold_seg(f, acc, seg, i) when i >= 1, do: fold_seg(f, fold_bucket(f, acc, element(i, seg)), seg, i - 1)

  defp fold_seg(f, acc, _, 0) when is_function(f, 3), do: acc

  defp fold_segs(f, acc, segs, i) when i >= 1 do
    seg = element(i, segs)
    fold_segs(f, fold_seg(f, acc, seg, tuple_size(seg)), segs, i - 1)
  end

  defp fold_segs(f, acc, _, 0) when is_function(f, 3), do: acc

  defp get_bucket(t, slot), do: get_bucket_s(dict(t, :segs), slot)

  defp get_bucket_s(segs, slot) do
    segI = div(slot - 1, 16) + 1
    bktI = rem(slot - 1, 16) + 1
    element(bktI, element(segI, segs))
  end

  defp get_slot(t, key) do
    h = :erlang.phash(key, dict(t, :maxn))
    cond do
      h > dict(t, :n) ->
        h - dict(t, :bso)
      true ->
        h
    end
  end

  defp map_bkt_list(f, [bkt0 | bkts]), do: [map_bucket(f, bkt0) | map_bkt_list(f, bkts)]

  defp map_bkt_list(f, []) when is_function(f, 2), do: []

  defp map_bucket(f, [[key | val] | bkt]), do: [[key | f.(key, val)] | map_bucket(f, bkt)]

  defp map_bucket(f, []) when is_function(f, 2), do: []

  defp map_dict(f, dict(size: 0) = dict) when is_function(f, 2), do: dict

  defp map_dict(f, d) do
    segs0 = tuple_to_list(dict(d, :segs))
    segs1 = map_seg_list(f, segs0)
    dict(d, segs: list_to_tuple(segs1))
  end

  defp map_seg_list(f, [seg | segs]) do
    bkts0 = tuple_to_list(seg)
    bkts1 = map_bkt_list(f, bkts0)
    [list_to_tuple(bkts1) | map_seg_list(f, segs)]
  end

  defp map_seg_list(f, []) when is_function(f, 2), do: []

  defp maybe_contract(t, dc) when dict(t, :size) - dc < dict(t, :con_size) and dict(t, :n) > 16 do
    n = dict(t, :n)
    slot1 = n - dict(t, :bso)
    segs0 = dict(t, :segs)
    b1 = get_bucket_s(segs0, slot1)
    slot2 = n
    b2 = get_bucket_s(segs0, slot2)
    segs1 = put_bucket_s(segs0, slot1, b1 ++ b2)
    segs2 = put_bucket_s(segs1, slot2, [])
    n1 = n - 1
    maybe_contract_segs(dict(t, size: dict(t, :size) - dc, n: n1, exp_size: n1 * 5, con_size: n1 * 3, segs: segs2))
  end

  defp maybe_contract(t, dc), do: dict(t, size: dict(t, :size) - dc)

  defp maybe_contract_segs(t) when dict(t, :n) === dict(t, :bso), do: dict(t, maxn: div(dict(t, :maxn), 2), bso: div(dict(t, :bso), 2), segs: contract_segs(dict(t, :segs)))

  defp maybe_contract_segs(t), do: t

  defp maybe_expand(t, 0), do: maybe_expand_aux(t, 0)

  defp maybe_expand(t, 1), do: maybe_expand_aux(t, 1)

  defp maybe_expand_aux(t0, ic) when dict(t0, :size) + ic > dict(t0, :exp_size) do
    t = maybe_expand_segs(t0)
    n = dict(t, :n) + 1
    segs0 = dict(t, :segs)
    slot1 = n - dict(t, :bso)
    b = get_bucket_s(segs0, slot1)
    slot2 = n
    [b1 | b2] = rehash(b, slot1, slot2, dict(t, :maxn))
    segs1 = put_bucket_s(segs0, slot1, b1)
    segs2 = put_bucket_s(segs1, slot2, b2)
    dict(t, size: dict(t, :size) + ic, n: n, exp_size: n * 5, con_size: n * 3, segs: segs2)
  end

  defp maybe_expand_aux(t, ic), do: dict(t, size: dict(t, :size) + ic)

  defp maybe_expand_segs(t) when dict(t, :n) === dict(t, :maxn), do: dict(t, maxn: 2 * dict(t, :maxn), bso: 2 * dict(t, :bso), segs: expand_segs(dict(t, :segs), dict(t, :empty)))

  defp maybe_expand_segs(t), do: t

  defp mk_seg(16), do: {[], [], [], [], [], [], [], [], [], [], [], [], [], [], [], []}

  defp on_bucket(f, t, slot) do
    segI = div(slot - 1, 16) + 1
    bktI = rem(slot - 1, 16) + 1
    segs = dict(t, :segs)
    seg = element(segI, segs)
    b0 = element(bktI, seg)
    {b1, res} = f.(b0)
    {dict(t, segs: setelement(segI, segs, setelement(bktI, seg, b1))), res}
  end

  defp put_bucket_s(segs, slot, bkt) do
    segI = div(slot - 1, 16) + 1
    bktI = rem(slot - 1, 16) + 1
    seg = setelement(bktI, element(segI, segs), bkt)
    setelement(segI, segs, seg)
  end

  defp rehash([[key | _Bag] = keyBag | t], slot1, slot2, maxN) do
    [l1 | l2] = rehash(t, slot1, slot2, maxN)
    case :erlang.phash(key, maxN) do
      slot1 ->
        [[keyBag | l1] | l2]
      slot2 ->
        [l1, keyBag | l2]
    end
  end

  defp rehash([], _Slot1, _Slot2, _MaxN), do: [[]]

  defp store_bkt_val(key, new, [[^key | _Old] | bkt]), do: {[[key | new] | bkt], 0}

  defp store_bkt_val(key, new, [other | bkt0]) do
    {bkt1, ic} = store_bkt_val(key, new, bkt0)
    {[other | bkt1], ic}
  end

  defp store_bkt_val(key, new, []), do: {[[key | new]], 1}

  defp take_key(key, [[^key | val] | bkt]), do: {bkt, {val, 1}}

  defp take_key(key, [e | bkt0]) do
    {bkt1, res} = take_key(key, bkt0)
    {[e | bkt1], res}
  end

  defp take_key(_, []), do: {[], :error}

  defp update_bkt(key, f, [[^key | val] | bkt]) do
    upd = f.(val)
    {[[key | upd] | bkt], upd}
  end

  defp update_bkt(key, f, [other | bkt0]) do
    {bkt1, upd} = update_bkt(key, f, bkt0)
    {[other | bkt1], upd}
  end

  defp update_bkt(_Key, _F, []), do: throw(:badarg)

  defp update_bkt(key, f, _, [[^key | val] | bkt]), do: {[[key | f.(val)] | bkt], 0}

  defp update_bkt(key, f, i, [other | bkt0]) do
    {bkt1, ic} = update_bkt(key, f, i, bkt0)
    {[other | bkt1], ic}
  end

  defp update_bkt(key, f, i, []) when is_function(f, 1), do: {[[key | i]], 1}
end
