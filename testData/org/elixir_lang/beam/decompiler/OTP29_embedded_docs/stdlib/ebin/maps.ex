# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :maps do
  @moduledoc ~S"""
  Maps processing functions.

  This module contains functions for maps processing. The Efficiency Guide
  contains a chapter that describes
  [how to use maps efficiently](`e:system:maps.md`).
  """

  # Types

  @type iterator :: iterator(term(), term())

  @typedoc ~S"""
  An iterator representing the associations in a map with keys of type `Key` and
  values of type `Value`.

  Created using [`maps:iterator/1`](`iterator/1`) or
  [`maps:iterator/2`](`iterator/2`).

  Consumed by:

  - [`maps:next/1`](`next/1`)
  - [`maps:filter/2`](`filter/2`)
  - [`maps:filtermap/2`](`filtermap/2`)
  - [`maps:fold/3`](`fold/3`)
  - [`maps:foreach/2`](`foreach/2`)
  - [`maps:map/2`](`map/2`)
  - [`maps:to_list/1`](`to_list/1`)
  """
  @opaque iterator(key, value) :: ({key, value, iterator(key, value)} | :none | nonempty_improper_list(integer(), %{optional(key) => value}) | nonempty_improper_list([key], %{optional(key) => value}))

  @typedoc ~S"""
  Key-based iterator order option that can be one of `undefined` (default for
  [`maps:iterator/1`](`iterator/1`)), `ordered` (sorted in map-key order),
  `reversed` (sorted in reverse map-key order), or a custom sorting function.

  Used by [`maps:iterator/2`](`iterator/2`).

  The [Expressions section](`e:system:expressions.md#term-comparisons`) contains
  descriptions of how terms are ordered.
  """
  @type iterator_order(key) :: (:undefined | :ordered | :reversed | (a :: key, b :: key -> boolean()))

  @type iterator_order :: iterator_order(term())

  # Functions

  @doc ~S"""
  Returns a map `Map` where each key-value pair from `MapOrIter` satisfies
  the predicate `Pred(Key, Value)`.

  Unless `MapOrIter` is an ordered iterator returned by `iterator/2`,
  the order of the `Pred(Key, Value)` calls is not defined.

  The call fails with a `{badmap,Map}` exception if `MapOrIter` is not a map or
  valid iterator, or with `badarg` if `Pred` is not a function of arity 2.

  ## Examples

  ```erlang
  1> M = #{a => 2, b => 3, "a" => 1, "b" => 2}.
  2> Pred = fun(K, V) -> is_atom(K) andalso V rem 2 =:= 0 end.
  3> maps:filter(Pred, M).
  #{a => 2}
  ```
  """
  @spec filter(pred, mapOrIter) :: map when pred: (key, value -> boolean()), mapOrIter: (%{optional(key) => value} | iterator(key, value)), map: %{optional(key) => value}
  def filter(pred, map) when is_map(map) and is_function(pred, 2), do: :maps.from_list(filter_1(pred, next(iterator(map)), :undefined))

  def filter(pred, iter) when is_function(pred, 2) do
    errorTag = make_ref()
    try do
      filter_1(pred, try_next(iter, errorTag), errorTag)
    catch
      {:error, ^errorTag, _} ->
        error_with_info({:badmap, iter}, [pred, iter])
    else
      result ->
        :maps.from_list(result)
    end
  end

  def filter(pred, map), do: badarg_with_info([pred, map])

  @doc ~S"""
  Calls `Fun(Key, Value1)` on each key-value pair of `MapOrIter` to
  update or remove associations from `MapOrIter`.

  If `Fun(Key, Value1)` returns `true`, the association is copied to the result
  map. If it returns `false`, the association is not copied. If it returns
  `{true, NewValue}`, the value for `Key` is replaced with `NewValue` in the
  result map.

  Unless `MapOrIter` is an ordered iterator returned by `iterator/2`,
  the order of the `Fun(Key, Value1)` calls is not defined.

  The call fails with a `{badmap,Map}` exception if `MapOrIter` is not a map or
  valid iterator, or with `badarg` if `Fun` is not a function of arity 2.

  ## Examples

  ```erlang
  1> Fun = fun(K, V) when is_atom(K) -> {true, V*2};
              (_, V) -> V rem 2 =:= 0
     end.
  2> Map = #{k1 => 1, "k2" => 2, "k3" => 3}.
  3> maps:filtermap(Fun, Map).
  #{k1 => 2,"k2" => 2}
  ```
  """
  @spec filtermap(fun, mapOrIter) :: map when fun: (key, value1 -> (boolean() | {true, value2})), mapOrIter: (%{optional(key) => value1} | iterator(key, value1)), map: %{optional(key) => (value1 | value2)}
  def filtermap(fun, map) when is_map(map) and is_function(fun, 2), do: :maps.from_list(filtermap_1(fun, next(iterator(map)), :undefined))

  def filtermap(fun, iter) when is_function(fun, 2) do
    errorTag = make_ref()
    try do
      filtermap_1(fun, try_next(iter, errorTag), errorTag)
    catch
      {:error, ^errorTag, _} ->
        error_with_info({:badmap, iter}, [fun, iter])
    else
      result ->
        :maps.from_list(result)
    end
  end

  def filtermap(fun, map), do: badarg_with_info([fun, map])

  @doc ~S"""
  Returns a tuple `{ok, Value}`, where `Value` is the value associated with `Key`,
  or `error` if no value is associated with `Key` in `Map`.

  The call fails with a `{badmap,Map}` exception if `Map` is not a map.

  ## Examples

  ```erlang
  1> Map = #{"hi" => 42}.
  2> Key = "hi".
  3> maps:find(Key, Map).
  {ok,42}
  ```
  """
  @spec find(key, map) :: ({:ok, value} | :error) when map: %{optional(key) => value, optional(any()) => any()}
  def find(_, _), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Calls `Fun(Key, Value, AccIn)` for every `Key` to value `Value` association in
  `MapOrIter`, starting with `AccIn` bound to `Acc0`.

  The `Fun/3` fun must return a new accumulator, which is passed to the
  next call. The function returns the final value of the
  accumulator. The initial accumulator value `Init` is returned if the
  map is empty.

  Unless `MapOrIter` is an ordered iterator returned by `iterator/2`,
  the order of the `Fun(Key, Value, AccIn)` calls is not defined.

  The call fails with a `{badmap,Map}` exception if `MapOrIter` is not a
  map or valid iterator, or with `badarg` if `Fun` is not a function of
  arity 3.

  ## Examples

  ```erlang
  1> Fun = fun(K, V, AccIn) -> AccIn + V end.
  2> Map = #{k1 => 1, k2 => 2, k3 => 3}.
  3> maps:fold(Fun, 0, Map).
  6
  ```
  """
  @spec fold(fun, init, mapOrIter) :: acc when fun: (key, value, accIn -> accOut), init: term(), acc: accOut, accIn: (init | accOut), mapOrIter: (%{optional(key) => value} | iterator(key, value))
  def fold(fun, init, map) when is_map(map) and is_function(fun, 3), do: fold_1(fun, init, next(iterator(map)), :undefined)

  def fold(fun, init, iter) when is_function(fun, 3) do
    errorTag = make_ref()
    try do
      fold_1(fun, init, try_next(iter, errorTag), errorTag)
    catch
      {:error, ^errorTag, _} ->
        error_with_info({:badmap, iter}, [fun, init, iter])
    end
  end

  def fold(fun, init, map), do: badarg_with_info([fun, init, map])

  @doc ~S"""
  Calls `Fun(Key, Value)` for every `Key` to `Value` association in
  `MapOrIter`.

  Unless `MapOrIter` is an ordered iterator returned by `iterator/2`,
  the order of the `Fun(Key, Value)` calls is not defined.

  The call fails with a `{badmap,Map}` exception if `MapOrIter` is not a map or
  valid iterator, or with `badarg` if `Fun` is not a function of arity 2.

  ## Examples

  ```erlang
  1> Fun = fun(K, V) -> self() ! {K,V} end.
  2> Map = #{p => 1, q => 2,x => 10, y => 20, z => 30}.
  3> maps:foreach(Fun, maps:iterator(Map, ordered)).
  ok
  4> [receive X -> X end || _ <- [1,2,3,4,5]].
  [{p,1},{q,2},{x,10},{y,20},{z,30}]
  ```
  """
  @spec foreach(fun, mapOrIter) :: :ok when fun: (key, value -> term()), mapOrIter: (%{optional(key) => value} | iterator(key, value))
  def foreach(fun, map) when is_map(map) and is_function(fun, 2), do: foreach_1(fun, next(iterator(map)), :undefined)

  def foreach(fun, iter) when is_function(fun, 2) do
    errorTag = make_ref()
    try do
      foreach_1(fun, try_next(iter, errorTag), errorTag)
    catch
      {:error, ^errorTag, _} ->
        error_with_info({:badmap, iter}, [fun, iter])
    end
  end

  def foreach(fun, map), do: badarg_with_info([fun, map])

  @doc ~S"""
  Takes a list of keys and a value and builds a map where all keys are
  associated with the same value.

  ## Examples

  ```erlang
  1> Keys = ["a", "b", "c"].
  2> maps:from_keys(Keys, ok).
  #{"a" => ok,"b" => ok,"c" => ok}
  ```
  """
  @spec from_keys(keys, value) :: map when keys: [], value: term(), map: map()
  def from_keys(_, _), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Takes a list of key-value tuples and builds a map.

  If the same key appears more than once, the last (rightmost) value is
  used, and previous values are ignored.

  ## Examples

  ```erlang
  1> List = [{"a",ignored},{1337,"value two"},{42,value_three},{"a",1}].
  2> maps:from_list(List).
  #{42 => value_three,1337 => "value two","a" => 1}
  ```
  """
  @spec from_list(list) :: map when list: [{key, value}], key: term(), value: term(), map: map()
  def from_list(_), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Returns value `Value` associated with `Key` if `Map` contains `Key`.

  The call fails with a `{badmap,Map}` exception if `Map` is not a map, or with a
  `{badkey,Key}` exception if no value is associated with `Key`.

  ## Examples

  ```erlang
  1> Key = 1337.
  2> Map = #{42 => value_two,1337 => "value one","a" => 1}.
  3> maps:get(Key, Map).
  "value one"
  ```
  """
  @spec get(key, map) :: value when key: term(), map: map(), value: term()
  def get(_, _), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Returns the value associated with key `Key` in `Map`, or `Default` if
  `Key` is not present in the map.

  The call fails with a `{badmap,Map}` exception if `Map` is not a map.

  ## Examples

  ```erlang
  1> Map = #{key1 => val1, key2 => val2}.
  #{key1 => val1,key2 => val2}
  2> maps:get(key1, Map, "Default value").
  val1
  3> maps:get(key3, Map, "Default value").
  "Default value"
  ```
  """
  @spec get(key, map, default) :: (value | default) when map: %{optional(key) => value, optional(any()) => any()}
  def get(key, map, default) when is_map(map) do
    case map do
      %{key => value} ->
        value
      %{} ->
        default
    end
  end

  def get(key, map, default), do: error_with_info({:badmap, map}, [key, map, default])

  @doc ~S"""
  Partitions the given `List` into a map of groups.

  The result is a map where each key is given by `KeyFun` and each value is a list
  of elements from the given `List` for which `KeyFun` returned the same key.

  The order of elements within each group list is preserved from the original
  list.

  ## Examples

  ```erlang
  1> EvenOdd = fun(X) when X rem 2 =:= 0 -> even;
                  (_) -> odd
               end.
  2> maps:groups_from_list(EvenOdd, [1, 2, 3]).
  #{even => [2], odd => [1, 3]}
  3> maps:groups_from_list(fun length/1, ["ant", "buffalo", "cat", "dingo"]).
  #{3 => ["ant", "cat"], 5 => ["dingo"], 7 => ["buffalo"]}
  ```
  """
  @spec groups_from_list(keyFun, list) :: groupsMap when keyFun: (elem -> key), groupsMap: %{optional(key) => group}, key: term(), list: [elem], group: [elem], elem: term()
  def groups_from_list(fun, list0) when is_function(fun, 1) do
    try do
      :lists.reverse(list0)
    catch
      {:error, _, _} ->
        badarg_with_info([fun, list0])
    else
      list ->
        groups_from_list_1(fun, list, %{})
    end
  end

  def groups_from_list(fun, list), do: badarg_with_info([fun, list])

  @doc ~S"""
  Partitions the given `List` into a map of groups.

  The result is a map where each key is given by `KeyFun` and each value is a list
  of elements from the given `List`, mapped via `ValueFun`, for which `KeyFun`
  returned the same key.

  The order of elements within each group list is preserved from the original
  list.

  ## Examples

  ```erlang
  1> EvenOdd = fun(X) -> case X rem 2 of 0 -> even; 1 -> odd end end.
  2> Square = fun(X) -> X * X end.
  3> maps:groups_from_list(EvenOdd, Square, [1, 2, 3]).
  #{even => [4], odd => [1, 9]}
  4> maps:groups_from_list(
      fun length/1,
      fun lists:reverse/1,
      ["ant", "buffalo", "cat", "dingo"]).
  #{3 => ["tna", "tac"],5 => ["ognid"],7 => ["olaffub"]}
  ```
  """
  @spec groups_from_list(keyFun, valueFun, list) :: groupsMap when keyFun: (elem -> key), valueFun: (elem -> value), groupsMap: %{required(key) => group}, key: term(), value: term(), list: [elem], group: [value], elem: term()
  def groups_from_list(fun, valueFun, list0) when is_function(fun, 1) and is_function(valueFun, 1) do
    try do
      :lists.reverse(list0)
    catch
      {:error, _, _} ->
        badarg_with_info([fun, valueFun, list0])
    else
      list ->
        groups_from_list_2(fun, valueFun, list, %{})
    end
  end

  def groups_from_list(fun, valueFun, list), do: badarg_with_info([fun, valueFun, list])

  @doc ~S"""
  Computes the intersection of maps `Map1` and `Map2`, producing a
  single map `Map3`.

  If a key exists in both maps, the value in `Map1` is superseded by the
  value in `Map2`. Keys existing in only one of the maps are discarded
  along with their values.

  The call fails with a `{badmap,Map}` exception if `Map1` or `Map2` is not a map.

  ## Examples

  ```erlang
  1> Map1 = #{a => "one", b => "two"}.
  2> Map2 = #{a => 1, c => 3}.
  3> maps:intersect(Map1, Map2).
  #{a => 1}
  ```
  """
  @spec intersect(map1, map2) :: map3 when map1: %{optional(key) => term()}, map2: %{optional(term()) => value2}, map3: %{optional(key) => value2}
  def intersect(map1, map2) when is_map(map1) and is_map(map2) do
    case map_size(map1) <= map_size(map2) do
      true ->
        intersect_with_small_map_first(&intersect_combiner_v2/3, map1, map2)
      false ->
        intersect_with_small_map_first(&intersect_combiner_v1/3, map2, map1)
    end
  end

  def intersect(map1, map2), do: error_with_info(error_type_two_maps(map1, map2), [map1, map2])

  @doc ~S"""
  Computes the intersection of maps `Map1` and `Map2`, producing a
  single map `Map3`, where values having the same key are combined using
  the `Combiner` fun.

  When `Combiner` is applied, the key that exists in both maps is the
  first parameter, the value from `Map1` is the second parameter, and
  the value from `Map2` is the third parameter.

  The call fails with a `{badmap,Map}` exception if `Map1` or `Map2` is not a map.
  The call fails with a `badarg` exception if `Combiner` is not a fun that takes
  three arguments.

  ## Examples

  ```erlang
  1> Map1 = #{a => "one", b => "two"}.
  2> Map2 = #{a => 1, c => 3}.
  3> maps:intersect_with(fun(_Key, Val1, Val2) -> {Val1, Val2} end, Map1, Map2).
  #{a => {"one",1}}
  ```
  """
  @spec intersect_with(combiner, map1, map2) :: map3 when map1: %{optional(key) => value1}, map2: %{optional(term()) => value2}, combiner: (key, value1, value2 -> combineResult), map3: %{optional(key) => combineResult}
  def intersect_with(combiner, map1, map2) when is_map(map1) and is_map(map2) and is_function(combiner, 3) do
    case map_size(map1) <= map_size(map2) do
      true ->
        intersect_with_small_map_first(combiner, map1, map2)
      false ->
        rCombiner = fn k, v1, v2 ->
            combiner.(k, v2, v1)
        end
        intersect_with_small_map_first(rCombiner, map2, map1)
    end
  end

  def intersect_with(combiner, map1, map2), do: error_with_info(error_type_merge_intersect(map1, map2, combiner), [combiner, map1, map2])

  @doc false
  @spec is_iterator_valid(maybeIter) :: boolean() when maybeIter: (iterator() | term())
  def is_iterator_valid(iter) do
    try do
      is_iterator_valid_1(iter)
    catch
      {:error, :badarg, _} ->
        false
    end
  end

  @doc ~S"""
  Returns `true` if map `Map` contains `Key`; otherwise, returns `false`.

  The call fails with a `{badmap,Map}` exception if `Map` is not a map.

  ## Examples

  ```erlang
  1> Map = #{"42" => value}.
  #{"42" => value}
  2> maps:is_key("42", Map).
  true
  3> maps:is_key(value, Map).
  false
  ```
  """
  @spec is_key(key, map) :: boolean() when key: term(), map: map()
  def is_key(_, _), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Returns a map iterator `Iterator` that can be used by [`maps:next/1`](`next/1`)
  to traverse the key-value associations in a map.

  The order of iteration is undefined. When iterating over a map, the
  memory usage is guaranteed to be bounded no matter the size of the
  map.

  The call fails with a `{badmap,Map}` exception if `Map` is not a map.

  ## Examples

  ```erlang
  1> M = #{ "foo" => 1, "bar" => 2 }.
  #{"foo" => 1,"bar" => 2}
  2> I = maps:iterator(M).
  3> {K1, V1, I2} = maps:next(I), {K1, V1}.
  {"bar",2}
  4> {K2, V2, I3} = maps:next(I2),{K2, V2}.
  {"foo",1}
  5> maps:next(I3).
  none
  ```
  """
  @spec iterator(map) :: iterator when map: %{optional(key) => value}, iterator: iterator(key, value)
  def iterator(m) when is_map(m), do: iterator(m, :undefined)

  def iterator(m), do: error_with_info({:badmap, m}, [m])

  @doc ~S"""
  Returns a map iterator `Iterator` that can be used by [`maps:next/1`](`next/1`)
  to traverse the key-value associations in a map sorted by key using the given
  `Order`.

  The call fails with a `{badmap,Map}` exception if `Map` is not a map, or
  with a `badarg` exception if `Order` is invalid.

  ## Examples

  Ordered iterator:

  ```erlang
  1> M = #{a => 1, b => 2}.
  2> OrdI = maps:iterator(M, ordered).
  3> {K1, V1, OrdI2} = maps:next(OrdI), {K1, V1}.
  {a,1}
  4> {K2, V2, OrdI3} = maps:next(OrdI2),{K2, V2}.
  {b,2}
  5> maps:next(OrdI3).
  none
  ```

  Iterator ordered in reverse:

  ```erlang
  1> M = #{a => 1, b => 2}.
  2> RevI = maps:iterator(M, reversed).
  3> {K2, V2, RevI2} = maps:next(RevI), {K2, V2}.
  {b,2}
  4> {K1, V1, RevI3} = maps:next(RevI2),{K1, V1}.
  {a,1}
  5> maps:next(RevI3).
  none
  6> maps:to_list(RevI).
  [{b,2},{a,1}]
  ```

  Using a custom ordering function that orders binaries by size:

  ```erlang
  1> M = #{<<"abcde">> => d, <<"y">> => b, <<"x">> => a, <<"pqr">> => c}.
  2> SizeI = fun(A, B) when byte_size(A) < byte_size(B) -> true;
                (A, B) when byte_size(A) > byte_size(B) -> false;
                (A, B) -> A =< B
             end.
  3> SizeOrdI = maps:iterator(M, SizeI).
  4> maps:to_list(SizeOrdI).
  [{<<"x">>,a},{<<"y">>,b},{<<"pqr">>,c},{<<"abcde">>,d}]
  ```
  """
  @spec iterator(map, order) :: iterator when map: %{optional(key) => value}, order: iterator_order(key), iterator: iterator(key, value)
  def iterator(m, :undefined) when is_map(m), do: [0 | m]

  def iterator(m, :ordered) when is_map(m) do
    cmpFun = fn a, b ->
        :erts_internal.cmp_term(a, b) <= 0
    end
    keys = :lists.sort(cmpFun, :maps.keys(m))
    [keys | m]
  end

  def iterator(m, :reversed) when is_map(m) do
    cmpFun = fn a, b ->
        :erts_internal.cmp_term(b, a) <= 0
    end
    keys = :lists.sort(cmpFun, :maps.keys(m))
    [keys | m]
  end

  def iterator(m, cmpFun) when is_map(m) and is_function(cmpFun, 2) do
    keys = :lists.sort(cmpFun, :maps.keys(m))
    [keys | m]
  end

  def iterator(m, order), do: badarg_with_info([m, order])

  @doc ~S"""
  Returns a complete list of keys contained in `Map`, in any order.

  The call fails with a `{badmap,Map}` exception if `Map` is not a map.

  ## Examples

  ```erlang
  1> Map = #{42 => three,1337 => "two","a" => 1}.
  2> maps:keys(Map).
  [42,1337,"a"]
  ```
  """
  @spec keys(map) :: keys when map: %{optional(key) => any()}, keys: [key]
  def keys(_), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Produces a new map `Map` by calling function `Fun(Key, Value1)` for every
  `Key` to value `Value1` association in `MapOrIter`.

  The `Fun/2` fun must return value `Value2` to be associated with key
  `Key` for the new map `Map`.

  Unless `MapOrIter` is an ordered iterator returned by `iterator/2`,
  the order of the `Fun(Key, Value1)` calls is not defined.

  The call fails with a `{badmap,Map}` exception if `MapOrIter` is not a map or
  valid iterator, or with `badarg` if `Fun` is not a function of arity 2.

  ## Examples

  ```erlang
  1> Fun = fun(K,V1) when is_list(K) -> V1*2 end.
  2> Map = #{"k1" => 1, "k2" => 2, "k3" => 3}.
  3> maps:map(Fun, Map).
  #{"k1" => 2,"k2" => 4,"k3" => 6}
  ```
  """
  @spec map(fun, mapOrIter) :: map when fun: (key, value1 -> value2), mapOrIter: (%{optional(key) => value1} | iterator(key, value1)), map: %{optional(key) => value2}
  def map(fun, map) when is_map(map) and is_function(fun, 2), do: :maps.from_list(map_1(fun, next(iterator(map)), :undefined))

  def map(fun, iter) when is_function(fun, 2) do
    errorTag = make_ref()
    try do
      map_1(fun, try_next(iter, errorTag), errorTag)
    catch
      {:error, ^errorTag, _} ->
        error_with_info({:badmap, iter}, [fun, iter])
    else
      result ->
        :maps.from_list(result)
    end
  end

  def map(fun, map), do: badarg_with_info([fun, map])

  @doc ~S"""
  Merges maps `Map1` and `Map2` into a single map `Map3`, where values
  from `Map2` override those from `Map1` for duplicate keys.

  The call fails with a `{badmap,Map}` exception if `Map1` or `Map2` is not a map.

  ## Examples

  ```erlang
  1> Map1 = #{a => "one", b => "two"}.
  2> Map2 = #{a => 1, c => 3}.
  3> maps:merge(Map1, Map2).
  #{a => 1,b => "two",c => 3}
  ```
  """
  @spec merge(map1, map2) :: map3 when map1: map(), map2: map(), map3: map()
  def merge(_, _), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Merges maps `Map1` and `Map2` into a single map `Map3`, combining values for
  duplicate keys using the `Combiner` fun.

  When `Combiner` is applied, the key that exists in both maps is the
  first parameter, the value from `Map1` is the second parameter, and
  the value from `Map2` is the third parameter.

  The call fails with a `{badmap,Map}` exception if `Map1` or `Map2` is not a map.
  The call fails with a `badarg` exception if `Combiner` is not a fun that takes
  three arguments.

  ## Examples

  ```erlang
  1> Map1 = #{a => 3, b => 5}.
  2> Map2 = #{a => 4, c => 17}.
  3> maps:merge_with(fun(_Key, Val1, Val2) -> Val1 + Val2 end, Map1, Map2).
  #{a => 7,b => 5,c => 17}
  ```
  """
  @spec merge_with(combiner, map1, map2) :: map3 when map1: %{optional(key1) => value1}, map2: %{optional(key2) => value2}, combiner: (key1, value1, value2 -> combineResult), map3: %{optional(key1) => combineResult, optional(key1) => value1, optional(key2) => value2}
  def merge_with(combiner, map1, map2) when is_map(map1) and is_map(map2) and is_function(combiner, 3) do
    case map_size(map1) >= map_size(map2) do
      true ->
        iterator = :maps.iterator(map2)
        merge_with_1(:maps.next(iterator), map1, combiner)
      false ->
        iterator = :maps.iterator(map1)
        merge_with_1(:maps.next(iterator), map2, fn k, v1, v2 ->
            combiner.(k, v2, v1)
        end)
    end
  end

  def merge_with(combiner, map1, map2), do: error_with_info(error_type_merge_intersect(map1, map2, combiner), [combiner, map1, map2])

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @doc ~S"""
  Returns a new empty map.

  ## Examples

  ```text
  1> maps:new().
  #{}
  ```
  """
  @spec new() :: map when map: %{}
  def new(), do: %{}

  @doc ~S"""
  Returns the next key-value association in `Iterator` and a new iterator for the
  remaining associations in the iterator.

  If there are no more associations in the iterator, `none` is returned.

  ## Examples

  ```erlang
  1> Map = #{a => 1, b => 2, c => 3}.
  #{a => 1,b => 2,c => 3}
  2> I = maps:iterator(Map, ordered).
  3> {K1, V1, I1} = maps:next(I), {K1, V1}.
  {a,1}
  4> {K2, V2, I2} = maps:next(I1), {K2, V2}.
  {b,2}
  5> {K3, V3, I3} = maps:next(I2), {K3, V3}.
  {c,3}
  6> maps:next(I3).
  none
  ```
  """
  @spec next(iterator) :: ({key, value, nextIterator} | :none) when iterator: iterator(key, value), nextIterator: iterator(key, value)
  def next({k, v, i}), do: {k, v, i}

  def next([path | map] = iterator) when is_integer(path) or is_list(path) and is_map(map) do
    try do
      :erts_internal.map_next(path, map, :iterator)
    catch
      {:error, :badarg, _} ->
        badarg_with_info([iterator])
    else
      result ->
        result
    end
  end

  def next(:none), do: :none

  def next(iter), do: badarg_with_info([iter])

  @doc ~S"""
  Associates `Key` with `Value` in Map1, replacing any existing value, and
  returns a new map `Map2` with the updated association alongside the
  original entries from `Map1`.

  The call fails with a `{badmap,Map}` exception if `Map1` is not a map.

  ## Examples

  ```erlang
  1> Map = #{"a" => 1}.
  #{"a" => 1}
  2> maps:put("a", 42, Map).
  #{"a" => 42}
  3> maps:put("b", 1337, Map).
  #{"a" => 1,"b" => 1337}
  ```
  """
  @spec put(key, value, map1) :: map2 when key: term(), value: term(), map1: map(), map2: map()
  def put(_, _, _), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Removes `Key` and its associated value from `Map1`, if it exists, and
  returns a new map `Map2` without `Key`.

  The call fails with a `{badmap,Map}` exception if `Map1` is not a map.

  ## Examples

  ```erlang
  1> Map = #{"a" => 1}.
  #{"a" => 1}
  2> maps:remove("a", Map).
  #{}
  3> maps:remove("b", Map).
  #{"a" => 1}
  ```
  """
  @spec remove(key, map1) :: map2 when key: term(), map1: map(), map2: map()
  def remove(_, _), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Returns the number of key-value associations in `Map`.

  This operation occurs in constant time.

  ## Examples

  ```erlang
  1> Map = #{42 => value_two,1337 => "value one","a" => 1}.
  2> maps:size(Map).
  3
  ```
  """
  @spec size(map) :: non_neg_integer() when map: map()
  def size(map) do
    try do
      map_size(map)
    catch
      {_, _, _} ->
        error_with_info({:badmap, map}, [map])
    end
  end

  @doc ~S"""
  Removes `Key` and its associated value from `Map1`, if it exists,
  returning a tuple with the removed value `Value` and the new map
  `Map2`; otherwise, returns error.

  The call will fail with a `{badmap,Map}` exception if `Map1` is not a map.

  Example:

  ```erlang
  1> Map = #{"a" => "hello", "b" => "world"}.
  #{"a" => "hello", "b" => "world"}
  2> maps:take("a", Map).
  {"hello",#{"b" => "world"}}
  3> maps:take("does not exist", Map).
  error
  ```
  """
  @spec take(key, map1) :: ({value, map2} | :error) when map1: %{optional(key) => value, optional(any()) => any()}, map2: %{optional(any()) => any()}
  def take(_, _), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Returns a list of pairs representing the key-value associations of
  `MapOrIterator`.

  Unless `MapOrIter` is an ordered iterator returned by `iterator/2`,
  the order of the `{Key, Value}` tuples in the resulting list is not
  defined.

  The call fails with a `{badmap,Map}` exception if `MapOrIterator` is not a map
  or an iterator obtained by a call to `iterator/1` or `iterator/2`.

  ## Examples

  ```erlang
  1> Map = #{42 => value_three,1337 => "value two","a" => 1}.
  2> maps:to_list(Map).
  [{42,value_three},{1337,"value two"},{"a",1}]
  ```

  Using an ordered iterator to return an ordered list:

  ```erlang
  1> Map = #{z => 1, y => 2, x => 3}.
  2> maps:to_list(maps:iterator(Map, ordered)).
  [{x,3},{y,2},{z,1}]
  ```
  """
  @spec to_list(mapOrIterator) :: [{key, value}] when mapOrIterator: (%{optional(key) => value} | iterator(key, value))
  def to_list(map) when is_map(map), do: to_list_internal(:erts_internal.map_next(0, map, []))

  def to_list(iter) do
    try do
      to_list_from_iterator(next(iter))
    catch
      {:error, _, _} ->
        error_with_info({:badmap, iter}, [iter])
    end
  end

  @doc ~S"""
  If `Key` exists in `Map1`, its value is replaced with `Value`, and the
  function returns a new map `Map2` with the updated association.

  The call fails with a `{badmap,Map}` exception if `Map1` is not a map, or with a
  `{badkey,Key}` exception if no value is associated with `Key`.

  ## Examples

  ```erlang
  1> Map = #{"a" => 1}.
  #{"a" => 1}
  2> maps:update("a", 42, Map).
  #{"a" => 42}
  ```
  """
  @spec update(key, value, map1) :: map2 when map1: %{required(key) => any(), optional(any()) => any()}, map2: %{required(key) => value, optional(any()) => any()}
  def update(_, _, _), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Updates a value in a `Map1` associated with `Key` by calling `Fun` on the old
  value to produce a new value.

  The call fails with a `{badkey,Key}` exception if `Key` is not present
  in the map.

  ## Examples

  ```erlang
  1> Map = #{counter => 1}.
  2> Fun = fun(V) -> V + 1 end.
  3> maps:update_with(counter, Fun, Map).
  #{counter => 2}
  ```
  """
  @spec update_with(key, fun, map1) :: map2 when map1: %{required(key) => value1, optional(any()) => any()}, map2: %{required(key) => value2, optional(any()) => any()}, fun: (value1 -> value2)
  def update_with(key, fun, map) when is_function(fun, 1) and is_map(map) do
    case map do
      %{key => value} ->
        %{map | key => fun.(value)}
      %{} ->
        error({:badkey, key}, [key, fun, map])
    end
  end

  def update_with(key, fun, map), do: error_with_info(error_type(map), [key, fun, map])

  @doc ~S"""
  Updates the value in `Map1` for `Key` by applying `Fun` to the old value or
  using `Init` if `Key` is not present in the map.

  ## Examples

  ```erlang
  1> Map = #{"counter" => 1}.
  2> Fun = fun(V) -> V + 1 end.
  3> maps:update_with("counter", Fun, 42, Map).
  #{"counter" => 2}
  4> maps:update_with("new counter", Fun, 42, Map).
  #{"counter" => 1,"new counter" => 42}
  ```
  """
  @spec update_with(key, fun, init, map1) :: map2 when map1: %{optional(key) => value1, optional(any()) => any()}, map2: %{required(key) => (value2 | init), optional(any()) => any()}, fun: (value1 -> value2)
  def update_with(key, fun, init, map) when is_function(fun, 1) and is_map(map) do
    case map do
      %{key => value} ->
        %{map | key => fun.(value)}
      %{} ->
        %{map | key => init}
    end
  end

  def update_with(key, fun, init, map), do: error_with_info(error_type(map), [key, fun, init, map])

  @doc ~S"""
  Returns a complete list of values contained in map `Map`, in any order.

  The call fails with a `{badmap,Map}` exception if `Map` is not a map.

  ## Examples

  ```erlang
  1> Map = #{42 => value_three,1337 => "value two","a" => 1}.
  2> maps:values(Map).
  [value_three,"value two",1]
  ```
  """
  @spec values(map) :: values when map: %{optional(any()) => value}, values: [value]
  def values(_), do: :erlang.nif_error(:undef)

  @doc ~S"""
  Returns a new map `Map2` with the keys `K1` through `Kn` and their associated
  values from map `Map1`.

  Any key in `Ks` that does not exist in `Map1` is ignored.

  ## Examples

  ```erlang
  1> Map = #{42 => value_three,1337 => "value two","a" => 1}.
  2> Keys = ["a",42,"other key"].
  3> maps:with(Keys, Map).
  #{42 => value_three,"a" => 1}
  ```
  """
  @spec with(ks, map1) :: map2 when ks: [k], map1: %{optional(k) => v, optional(any()) => any()}, map2: %{optional(k) => v}
  def with(ks, map1) when is_list(ks) and is_map(map1), do: :maps.from_list(with_1(ks, map1))

  def with(ks, m), do: error_with_info(error_type(m), [ks, m])

  @doc ~S"""
  Returns a new map `Map2` without keys `K1` through `Kn` and their associated
  values from map `Map1`.

  Any key in `Ks` that does not exist in `Map1` is ignored.

  ## Examples

  ```erlang
  1> Map = #{42 => value_three, 1337 => "value two", "a" => 1}.
  2> Keys = ["a",42,"other key"].
  3> maps:without(Keys, Map).
  #{1337 => "value two"}
  ```
  """
  @spec without(ks, map1) :: map2 when ks: [k], map1: map(), map2: map(), k: term()
  def without(ks, m) when is_list(ks) and is_map(m), do: :lists.foldl(&:maps.remove/2, m, ks)

  def without(ks, m), do: error_with_info(error_type(m), [ks, m])

  # Private Functions

  defp unquote(:"-intersect_with/3-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-iterator/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-iterator/2-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-merge_with/3-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp error_type(m) when is_map(m), do: :badarg

  defp error_type(v), do: {:badmap, v}

  defp error_type_merge_intersect(m1, m2, combiner) when is_function(combiner, 3), do: error_type_two_maps(m1, m2)

  defp error_type_merge_intersect(_M1, _M2, _Combiner), do: :badarg

  defp error_type_two_maps(m1, m2) when is_map(m1), do: {:badmap, m2}

  defp error_type_two_maps(m1, _M2), do: {:badmap, m1}

  defp filter_1(pred, {k, v, iter}, errorTag) do
    case pred.(k, v) do
      true ->
        [{k, v} | filter_1(pred, try_next(iter, errorTag), errorTag)]
      false ->
        filter_1(pred, try_next(iter, errorTag), errorTag)
    end
  end

  defp filter_1(_Pred, :none, _ErrorTag), do: []

  defp filtermap_1(fun, {k, v, iter}, errorTag) do
    case fun.(k, v) do
      true ->
        [{k, v} | filtermap_1(fun, try_next(iter, errorTag), errorTag)]
      {true, newV} ->
        [{k, newV} | filtermap_1(fun, try_next(iter, errorTag), errorTag)]
      false ->
        filtermap_1(fun, try_next(iter, errorTag), errorTag)
    end
  end

  defp filtermap_1(_Fun, :none, _ErrorTag), do: []

  defp fold_1(fun, acc, {k, v, iter}, errorTag), do: fold_1(fun, fun.(k, v, acc), try_next(iter, errorTag), errorTag)

  defp fold_1(_Fun, acc, :none, _ErrorTag), do: acc

  defp foreach_1(fun, {k, v, iter}, errorTag) do
    fun.(k, v)
    foreach_1(fun, try_next(iter, errorTag), errorTag)
  end

  defp foreach_1(_Fun, :none, _ErrorTag), do: :ok

  defp groups_from_list_1(fun, [h | tail], acc) do
    k = fun.(h)
    newAcc = case acc do
      %{k => vs} ->
        %{acc | k => [h | vs]}
      %{} ->
        %{acc | k => [h]}
    end
    groups_from_list_1(fun, tail, newAcc)
  end

  defp groups_from_list_1(_Fun, [], acc), do: acc

  defp groups_from_list_2(fun, valueFun, [h | tail], acc) do
    k = fun.(h)
    v = valueFun.(h)
    newAcc = case acc do
      %{k => vs} ->
        %{acc | k => [v | vs]}
      %{} ->
        %{acc | k => [v]}
    end
    groups_from_list_2(fun, valueFun, tail, newAcc)
  end

  defp groups_from_list_2(_Fun, _ValueFun, [], acc), do: acc

  defp intersect_combiner_v1(_K, v1, _V2), do: v1

  defp intersect_combiner_v2(_K, _V1, v2), do: v2

  defp intersect_with_iterate({k, v1, iterator}, keep, bigMap, combiner) do
    next = :maps.next(iterator)
    case bigMap do
      %{k => v2} ->
        v = combiner.(k, v1, v2)
        intersect_with_iterate(next, [{k, v} | keep], bigMap, combiner)
      _ ->
        intersect_with_iterate(next, keep, bigMap, combiner)
    end
  end

  defp intersect_with_iterate(:none, keep, _BigMap2, _Combiner), do: :maps.from_list(keep)

  defp intersect_with_small_map_first(combiner, smallMap, bigMap) do
    next = :maps.next(:maps.iterator(smallMap))
    intersect_with_iterate(next, [], bigMap, combiner)
  end

  defp is_iterator_valid_1(:none), do: true

  defp is_iterator_valid_1({_, _, next}), do: is_iterator_valid_1(next(next))

  defp is_iterator_valid_1(iter) do
    _ = next(iter)
    true
  end

  defp map_1(fun, {k, v, iter}, errorTag), do: [{k, fun.(k, v)} | map_1(fun, try_next(iter, errorTag), errorTag)]

  defp map_1(_Fun, :none, _ErrorTag), do: []

  defp merge_with_1({k, v2, iterator}, map1, combiner) do
    case map1 do
      %{k => v1} ->
        newMap1 = %{map1 | k => combiner.(k, v1, v2)}
        merge_with_1(:maps.next(iterator), newMap1, combiner)
      %{} ->
        merge_with_1(:maps.next(iterator), :maps.put(k, v2, map1), combiner)
    end
  end

  defp merge_with_1(:none, result, _), do: result

  defp to_list_from_iterator({key, value, nextIter}), do: [{key, value} | to_list_from_iterator(next(nextIter))]

  defp to_list_from_iterator(:none), do: []

  defp to_list_internal([iter, map | acc]) when is_integer(iter), do: to_list_internal(:erts_internal.map_next(iter, map, acc))

  defp to_list_internal(acc), do: acc

  defp try_next({_, _, _} = kVI, _ErrorTag), do: kVI

  defp try_next(:none, _ErrorTag), do: :none

  defp try_next(iter, :undefined), do: next(iter)

  defp try_next(iter, errorTag) do
    try do
      next(iter)
    catch
      {:error, :badarg, _} ->
        error(errorTag)
    end
  end

  defp with_1([k | ks], map) do
    case map do
      %{k => v} ->
        [{k, v} | with_1(ks, map)]
      %{} ->
        with_1(ks, map)
    end
  end

  defp with_1([], _Map), do: []
end
