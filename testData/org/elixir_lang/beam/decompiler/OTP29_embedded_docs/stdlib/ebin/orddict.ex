# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :orddict do
  @moduledoc ~S"""
  Key-value dictionary as ordered list.

  This module provides a `Key`-`Value` dictionary. An `orddict` is a
  representation of a dictionary, where a list of pairs is used to store the keys
  and values. The list is ordered after the keys in the
  [Erlang term order](`e:system:expressions.md#term-comparisons`).

  This module provides the same interface as the `m:dict` module but with a
  defined representation. One difference is that while `dict` considers two keys
  as different if they do not match (`=:=`), this module considers two keys as
  different if and only if they do not compare equal (`==`).

  ### See Also

  `m:dict`, `m:gb_trees`, `m:maps`
  """

  # Types

  @type orddict :: orddict(any(), any())

  @typedoc ~S"""
  Dictionary as returned by `new/0`.
  """
  @type orddict(key, value) :: [{key, value}]

  # Functions

  @doc ~S"""
  Appends a new `Value` to the current list of values associated with `Key`.

  An exception is generated if the initial value associated with `Key`
  is not a list of values.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{x, []}]).
  [{x,[]}]
  2> OrdDict2 = orddict:append(x, 1, OrdDict1).
  [{x,[1]}]
  3> OrdDict3 = orddict:append(x, 2, OrdDict2).
  [{x,[1,2]}]
  4> orddict:append(y, 3, OrdDict3).
  [{x,[1,2]},{y,[3]}]
  ```

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, no_list}]).
  [{a,no_list}]
  2> orddict:append(a, 1, OrdDict1).
  ** exception error: bad argument
       in operator  ++/2
          called as no_list ++ [1]
       in call from orddict:append/3
  ```
  """
  @spec append(key, value, orddict1) :: orddict2 when orddict1: orddict(key, value), orddict2: orddict(key, value)
  def append(key, new, [{k, _} | _] = dict) when key < k, do: [{key, [new]} | dict]

  def append(key, new, [{k, _} = e | dict]) when key > k, do: [e | append(key, new, dict)]

  def append(key, new, [{_K, old} | dict]), do: [{key, old ++ [new]} | dict]

  def append(key, new, []), do: [{key, [new]}]

  @doc ~S"""
  Appends a list of values `ValList` to the current list of values associated with
  `Key`.

  An exception is generated if the initial value associated with `Key`
  is not a list of values.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{x, []}]).
  [{x,[]}]
  2> OrdDict2 = orddict:append_list(x, [1,2], OrdDict1).
  [{x,[1,2]}]
  3> OrdDict3 = orddict:append_list(y, [3,4], OrdDict2).
  [{x,[1,2]},{y,[3,4]}]
  ```
  """
  @spec append_list(key, valList, orddict1) :: orddict2 when valList: [value], orddict1: orddict(key, value), orddict2: orddict(key, value)
  def append_list(key, newList, [{k, _} | _] = dict) when key < k, do: [{key, newList} | dict]

  def append_list(key, newList, [{k, _} = e | dict]) when key > k, do: [e | append_list(key, newList, dict)]

  def append_list(key, newList, [{_K, old} | dict]), do: [{key, old ++ newList} | dict]

  def append_list(key, newList, []), do: [{key, newList}]

  @doc ~S"""
  Removes the item with key `Key` from dictionary `OrdDict1`.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> orddict:erase(a, OrdDict1).
  [{b,2}]
  3> orddict:erase(z, OrdDict1).
  [{a,1},{b,2}]
  ```
  """
  @spec erase(key, orddict1) :: orddict2 when orddict1: orddict(key, value), orddict2: orddict(key, value)
  def erase(key, [{k, _} = e | dict]) when key < k, do: [e | dict]

  def erase(key, [{k, _} = e | dict]) when key > k, do: [e | erase(key, dict)]

  def erase(_Key, [{_K, _Val} | dict]), do: dict

  def erase(_, []), do: []

  @doc ~S"""
  Returns the value associated with `Key` in dictionary `Orddict`.

  This function assumes that the `Key` is present in the dictionary. An
  exception is generated if `Key` is not in the dictionary.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> orddict:fetch(a, OrdDict1).
  1
  3> orddict:fetch(missing, OrdDict1).
  ** exception error: no function clause matching orddict:fetch(missing,[])
  ```
  """
  @spec fetch(key, orddict) :: value when orddict: orddict(key, value)
  def fetch(key, [{k, _} | d]) when key > k, do: fetch(key, d)

  def fetch(key, [{k, value} | _]) when key == k, do: value

  @doc ~S"""
  Returns a list of all keys in a dictionary.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> orddict:fetch_keys(OrdDict1).
  [a,b]
  ```
  """
  @spec fetch_keys(orddict) :: keys when orddict: orddict(key, value :: term()), keys: [key]
  def fetch_keys([{key, _} | dict]), do: [key | fetch_keys(dict)]

  def fetch_keys([]), do: []

  @doc ~S"""
  Filters keys and values in `Orddict1` using predicate function `Pred`.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> orddict:filter(fun (K, V) -> V > 1 end, OrdDict1).
  [{b,2}]
  ```
  """
  @spec filter(pred, orddict1) :: orddict2 when pred: (key, value -> boolean()), orddict1: orddict(key, value), orddict2: orddict(key, value)
  def filter(f, [{key, val} = e | d]) do
    case f.(key, val) do
      true ->
        [e | filter(f, d)]
      false ->
        filter(f, d)
    end
  end

  def filter(f, []) when is_function(f, 2), do: []

  @doc ~S"""
  Searches for a key in a dictionary.

  Returns `{ok, Value}`, where `Value` is the value associated with
  `Key`, or `error` if the key is not present in the dictionary.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> orddict:find(a, OrdDict1).
  {ok,1}
  3> orddict:find(c, OrdDict1).
  error
  ```
  """
  @spec find(key, orddict) :: ({:ok, value} | :error) when orddict: orddict(key, value)
  def find(key, [{k, _} | _]) when key < k, do: :error

  def find(key, [{k, _} | d]) when key > k, do: find(key, d)

  def find(_Key, [{_K, value} | _]), do: {:ok, value}

  def find(_, []), do: :error

  @doc ~S"""
  Calls `Fun` on successive keys and values of `Orddict` together with an extra
  argument `Acc` (short for accumulator).

  `Fun` must return a new accumulator that is passed to the next
  call. `Acc0` is returned if the dictionary is empty.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> orddict:fold(fun (K, V, Acc) -> [{K, V+100} | Acc] end, [], OrdDict1).
  [{b,102},{a,101}]
  ```
  """
  @spec fold(fun, acc0, orddict) :: acc1 when fun: (key, value, accIn -> accOut), orddict: orddict(key, value), acc0: acc, acc1: acc, accIn: acc, accOut: acc
  def fold(f, acc, [{key, val} | d]), do: fold(f, f.(key, val, acc), d)

  def fold(f, acc, []) when is_function(f, 3), do: acc

  @doc ~S"""
  Converts the `Key`-`Value` list `List` to a dictionary.

  ## Examples

  ```erlang
  1> OrdDict = orddict:from_list([{2,b},{1,a},{3,c}]).
  [{1,a},{2,b},{3,c}]
  ```
  """
  @spec from_list(list) :: orddict when list: [{key, value}], orddict: orddict(key, value)
  def from_list([]), do: []

  def from_list([{_, _}] = pair), do: pair

  def from_list(pairs), do: :lists.ukeysort(1, reverse_pairs(pairs, []))

  @doc ~S"""
  Returns `true` if `Orddict` has no elements; otherwise, returns `false`.

  ## Examples

  ```erlang
  1> orddict:is_empty(orddict:new()).
  true
  2> orddict:is_empty(orddict:from_list([{a,1}])).
  false
  ```
  """
  @spec is_empty(orddict) :: boolean() when orddict: orddict()
  def is_empty([]), do: true

  def is_empty([_ | _]), do: false

  @doc ~S"""
  Returns `true` if `Key` is contained in dictionary `Orddict`;
  otherwise, returns `false`.

  ## Examples

  ```erlang
  1> OrdDict = orddict:from_list([{1,a},{2,b},{3,c}]).
  [{1,a},{2,b},{3,c}]
  2> orddict:is_key(2, OrdDict).
  true
  3> orddict:is_key(aa, OrdDict).
  false
  ```
  """
  @spec is_key(key, orddict) :: boolean() when orddict: orddict(key, value :: term())
  def is_key(key, [{k, _} | _]) when key < k, do: false

  def is_key(key, [{k, _} | dict]) when key > k, do: is_key(key, dict)

  def is_key(_Key, [{_K, _Val} | _]), do: true

  def is_key(_, []), do: false

  @doc ~S"""
  Calls `Fun` on successive keys and values of `Orddict1` to return a new value
  for each key.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> orddict:map(fun (_K, V) -> V + 100 end, OrdDict1).
  [{a,101},{b,102}]
  ```
  """
  @spec map(fun, orddict1) :: orddict2 when fun: (key, value1 -> value2), orddict1: orddict(key, value1), orddict2: orddict(key, value2)
  def map(f, [{key, val} | d]), do: [{key, f.(key, val)} | map(f, d)]

  def map(f, []) when is_function(f, 2), do: []

  @doc ~S"""
  Merges two dictionaries, `Orddict1` and `Orddict2`, to create a new dictionary.

  All the `Key`-`Value` pairs from both dictionaries are included in the new
  dictionary.

  If a key occurs in both dictionaries, `Fun` is called with the key
  and both values to return a new value.

  [`merge/3`](`merge/3`) can be defined as follows, but is faster:

  ```erlang
  merge(Fun, D1, D2) ->
      fold(fun (K, V1, D) ->
                   update(K, fun (V2) -> Fun(K, V1, V2) end, V1, D)
           end, D2, D1).
  ```

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> OrdDict2 = orddict:from_list([{b, 7}, {c, 8}]).
  [{b,7},{c,8}]
  3> orddict:merge(fun (K, V1, V2) -> V1 * V2 end, OrdDict1, OrdDict2).
  [{a,1},{b,14},{c,8}]
  ```
  """
  @spec merge(fun, orddict1, orddict2) :: orddict3 when fun: (key, value1, value2 -> value), orddict1: orddict(key, value1), orddict2: orddict(key, value2), orddict3: orddict(key, value)
  def merge(f, [{k1, _} = e1 | d1], [{k2, _} = e2 | d2]) when k1 < k2, do: [e1 | merge(f, d1, [e2 | d2])]

  def merge(f, [{k1, _} = e1 | d1], [{k2, _} = e2 | d2]) when k1 > k2, do: [e2 | merge(f, [e1 | d1], d2)]

  def merge(f, [{k1, v1} | d1], [{_K2, v2} | d2]), do: [{k1, f.(k1, v1, v2)} | merge(f, d1, d2)]

  def merge(f, [], d2) when is_function(f, 3), do: d2

  def merge(f, d1, []) when is_function(f, 3), do: d1

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
  1> orddict:new().
  []
  ```
  """
  @spec new() :: orddict(none(), none())
  def new(), do: []

  @doc ~S"""
  Returns the number of elements in an `Orddict`.

  ## Examples

  ```erlang
  1> orddict:size(orddict:new()).
  0
  2> orddict:size(orddict:from_list([{a,1},{b,2},{c,3}])).
  3
  ```
  """
  @spec size(orddict) :: non_neg_integer() when orddict: orddict()
  def size(d), do: length(d)

  @doc ~S"""
  Stores a `Key`-`Value` pair in a dictionary.

  If the `Key` already exists in `Orddict1`, the associated value is
  replaced by `Value`.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> orddict:store(a, 99, OrdDict1).
  [{a,99},{b,2}]
  3> orddict:store(c, 100, OrdDict1).
  [{a,1},{b,2},{c,100}]
  ```
  """
  @spec store(key, value, orddict1) :: orddict2 when orddict1: orddict(key, value), orddict2: orddict(key, value)
  def store(key, new, [{k, _} | _] = dict) when key < k, do: [{key, new} | dict]

  def store(key, new, [{k, _} = e | dict]) when key > k, do: [e | store(key, new, dict)]

  def store(key, new, [{_K, _Old} | dict]), do: [{key, new} | dict]

  def store(key, new, []), do: [{key, new}]

  @doc ~S"""
  This function returns a value from a dictionary and a new dictionary without
  this value.

  Returns `error` if the key is not present in the dictionary.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> orddict:take(a, OrdDict1).
  {1,[{b,2}]}
  3> orddict:take(missing, OrdDict1).
  error
  ```
  """
  @spec take(key, orddict) :: ({value, orddict1} | :error) when orddict: orddict(key, value), orddict1: orddict(key, value), key: term(), value: term()
  def take(key, dict), do: take_1(key, dict, [])

  @doc ~S"""
  Converts a dictionary to a list representation.

  ## Examples

  ```erlang
  1> OrdDict = orddict:from_list([{2,b},{1,a}]).
  [{1,a},{2,b}]
  2> orddict:to_list(OrdDict).
  [{1,a},{2,b}]
  ```
  """
  @spec to_list(orddict) :: list when orddict: orddict(key, value), list: [{key, value}]
  def to_list(dict), do: dict

  @doc ~S"""
  Updates a value in a dictionary by calling `Fun` on the value to get a new
  value.

  An exception is generated if `Key` is not present in the dictionary.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> orddict:update(a, fun (V) -> V + 100 end, OrdDict1).
  [{a,101},{b,2}]
  ```
  """
  @spec update(key, fun, orddict1) :: orddict2 when fun: (value1 :: value -> value2 :: value), orddict1: orddict(key, value), orddict2: orddict(key, value)
  def update(key, fun, [{k, _} = e | dict]) when key > k, do: [e | update(key, fun, dict)]

  def update(key, fun, [{k, val} | dict]) when key == k, do: [{key, fun.(val)} | dict]

  @doc ~S"""
  Updates a value in a dictionary by calling `Fun` on the value to get a new
  value.

  If `Key` is not present in the dictionary, `Initial` is stored as the
  first value.

  For example, [`append/3`](`append/3`) can be defined as follows:

  ```erlang
  append(Key, Val, D) ->
      update(Key, fun (Old) -> Old ++ [Val] end, [Val], D).
  ```

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a, 1}, {b, 2}]).
  [{a,1},{b,2}]
  2> OrdDict2 = orddict:update(c, fun (V) -> V + 100 end, 99, OrdDict1).
  [{a,1},{b,2},{c,99}]
  3> orddict:update(a, fun (V) -> V + 100 end, 99, OrdDict2).
  [{a,101},{b,2},{c,99}]
  ```
  """
  @spec update(key, fun, initial, orddict1) :: orddict2 when initial: value, fun: (value1 :: value -> value2 :: value), orddict1: orddict(key, value), orddict2: orddict(key, value)
  def update(key, _, init, [{k, _} | _] = dict) when key < k, do: [{key, init} | dict]

  def update(key, fun, init, [{k, _} = e | dict]) when key > k, do: [e | update(key, fun, init, dict)]

  def update(key, fun, _Init, [{_K, val} | dict]), do: [{key, fun.(val)} | dict]

  def update(key, _, init, []), do: [{key, init}]

  @doc ~S"""
  Adds `Increment` to the value associated with `Key` and stores this value.

  If `Key` is not present in the dictionary, `Increment` is stored as
  the first value.

  ## Examples

  ```erlang
  1> OrdDict1 = orddict:from_list([{a,0},{b,0}]).
  [{a,0},{b,0}]
  2> OrdDict2 = orddict:update_counter(a, 1, OrdDict1).
  [{a,1},{b,0}]
  3> OrdDict3 = orddict:update_counter(b, 2, OrdDict2).
  [{a,1},{b,2}]
  4> orddict:update_counter(z, 7, OrdDict3).
  [{a,1},{b,2},{z,7}]
  ```
  """
  @spec update_counter(key, increment, orddict1) :: orddict2 when orddict1: orddict(key, value), orddict2: orddict(key, value), increment: number()
  def update_counter(key, incr, [{k, _} | _] = dict) when key < k, do: [{key, incr} | dict]

  def update_counter(key, incr, [{k, _} = e | dict]) when key > k, do: [e | update_counter(key, incr, dict)]

  def update_counter(key, incr, [{_K, val} | dict]), do: [{key, val + incr} | dict]

  def update_counter(key, incr, []), do: [{key, incr}]

  # Private Functions

  defp reverse_pairs([{_, _} = h | t], acc), do: reverse_pairs(t, [h | acc])

  defp reverse_pairs([], acc), do: acc

  defp take_1(key, [{k, _} | _], _Acc) when key < k, do: :error

  defp take_1(key, [{k, _} = p | d], acc) when key > k, do: take_1(key, d, [p | acc])

  defp take_1(_Key, [{_K, value} | d], acc), do: {value, :lists.reverse(acc, d)}

  defp take_1(_, [], _), do: :error
end
