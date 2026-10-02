# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :array do
  @moduledoc ~S"""
  Functional, extendible arrays.

  Arrays can have fixed size, or can grow automatically as needed. A default value
  is used for entries that have not been explicitly set.

  Arrays uses _zero_-based indexing. This is a deliberate design choice and
  differs from other Erlang data structures, for example, tuples.

  Unless specified by the user when the array is created, the default value is the
  atom `undefined`. There is no difference between an unset entry and an entry
  that has been explicitly set to the same value as the default one (compare
  `reset/2`). If you need to differentiate between unset and set entries, ensure
  that the default value cannot be confused with the values of set entries.

  The array never shrinks automatically. If an index `I` has been used to set an
  entry successfully, all indices in the range `[0,I]` stay accessible unless the
  array size is explicitly changed by calling `resize/2`.

  ## Examples

  Create a fixed-size array with entries 0-9 set to `undefined`:

  ```
  A0 = array:new(10).
  10 = array:size(A0).
  ```

  Create an extendible array and set entry 17 to `true`, causing the array to grow
  automatically:

  ```
  A1 = array:set(17, true, array:new()).
  18 = array:size(A1).
  ```

  Read back a stored value:

  ```
  true = array:get(17, A1).
  ```

  Accessing an unset entry returns the default value:

  ```
  undefined = array:get(3, A1)
  ```

  Accessing an entry beyond the last set entry also returns the default value, if
  the array does not have fixed size:

  ```
  undefined = array:get(18, A1).
  ```

  "Sparse" functions ignore default-valued entries:

  ```
  A2 = array:set(4, false, A1).
  [{4, false}, {17, true}] = array:sparse_to_orddict(A2).
  ```

  An extendible array can be made fixed-size later:

  ```
  A3 = array:fix(A2).
  ```

  A fixed-size array does not grow automatically and does not allow accesses
  beyond the last set entry:

  ```
  {'EXIT',{badarg,_}} = (catch array:set(18, true, A3)).
  {'EXIT',{badarg,_}} = (catch array:get(18, A3)).
  ```
  """

  # Types

  @type array :: array(dynamic())

  @typedoc ~S"""
  A functional, extendible array.

  The representation is not documented and is subject to change without
  notice. Notice that arrays cannot be directly compared for equality.
  """
  @opaque array(type) :: array(default :: type, elements :: elements(type))

  # Private Types

  @typep array_indx :: non_neg_integer()

  @typep array_opt :: ({:fixed, boolean()} | :fixed | {:default, type :: dynamic()} | {:size, n :: non_neg_integer()} | n :: non_neg_integer())

  @typep array_opts :: (array_opt() | [array_opt()])

  @typep cache :: leaf_tuple(dynamic())

  @typep element_tuple(t) :: (leaf_tuple(t) | [] | {element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t), element_tuple(t)})

  @typep elements(t) :: ([] | element_tuple(t))

  @typep indx_pair(type) :: {index :: array_indx(), type}

  @typep indx_pairs(type) :: [indx_pair(type)]

  @typep leaf_tuple(t) :: {t, t, t, t, t, t, t, t, t, t, t, t, t, t, t, t}

  # Functions

  @doc ~S"""
  Append a single value to the right side of the array.

  The operation is always allowed even if the array is fixed.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,9)).
  2> array:get(array:size(A), array:append(last, A)).
  last
  ```

  See also `prepend/2`, `concat/2`.
  """
  @spec append(value :: any(), array :: array(type)) :: array(type)
  def append(value, array(size: n, zero: z, cache: c, cache_index: cI, default: d, elements: e, bits: s) = a) when is_integer(n) and is_integer(cI) and is_integer(s) and is_integer(z) do
    i = n + z
    n1 = n + 1
    cond do
      i < cI + 1 <<< 4 ->
        array(a, size: n1, cache: setelement(1 + i - cI, c, value))
      i < 1 <<< s + 4 ->
        r = i &&& 1 <<< 4 - 1
        cI1 = i - r
        e1 = set_leaf(cI, s, e, c)
        c1 = get_leaf(cI1, s, e1, d)
        c2 = setelement(1 + r, c1, value)
        array(a, size: n1, elements: e1, cache: c2, cache_index: cI1)
      true ->
        r = i &&& 1 <<< 4 - 1
        cI1 = i - r
        {e1, s1} = grow(i, e, s)
        e2 = set_leaf(cI, s1, e1, c)
        c1 = get_leaf(cI1, s1, e2, d)
        c2 = setelement(1 + r, c1, value)
        array(a, size: n1, elements: e2, cache: c2, cache_index: cI1, bits: s1)
    end
  end

  def append(_V, _A), do: :erlang.error(:badarg)

  @doc ~S"""
  Concatenates a nonempty list of arrays.

  ## Examples

  ```erlang
  1> A = array:from_list([a]).
  2> B = array:from_list([b]).
  3> array:to_list(array:concat([A,B])).
  [a,b]
  ```

  See also `concat/2`.
  """
  @spec concat(arrays :: [array(type)]) :: array(type)
  def concat([a0 | as]) do
    :lists.foldl(fn a, acc ->
        concat(acc, a)
    end, a0, as)
  end

  def concat(_), do: :erlang.error(:badarg)

  @doc ~S"""
  Concatenates two arrays.

  Adds the elements of `B` onto `A`.

  ## Examples

  ```erlang
  1> A = array:set(1, a, array:new([{default, xa}, {size,3}, {fixed, true}])).
  2> B = array:set(2, b, array:new([{default, xb}, {size,4}, {fixed, false}])).
  3> AB = array:concat(A,B).
  4> array:to_list(AB).
  [xa,a,xa,xb,xb,b,xb]
  ```

  See also `concat/1`, `append/2`, `prepend/2`.
  """
  @spec concat(a :: array(type), b :: array(type)) :: aB :: array(type)
  def concat(array(size: leftN, fix: fix, default: defA) = left, array(size: rightN, default: defB) = right) do
    cond do
      rightN > leftN and defA === defB ->
        foldr(fn _I, v, acc ->
            prepend(v, acc)
        end, array(right, fix: fix), left)
      true ->
        foldl(fn _I, v, acc ->
            append(v, acc)
        end, left, right)
    end
  end

  def concat(_, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Gets the value used for uninitialized entries.

  ## Examples

  ```erlang
  1> array:default(array:new()).
  undefined
  2> array:get(52, array:new()).
  undefined
  3> array:default(array:new([{default, 0}])).
  0
  4> array:get(52, array:new([{default, 0}])).
  0
  ```

  See also `new/2`.
  """
  @spec default(array :: array(type)) :: value :: type
  def default(array(default: d)), do: d

  def default(_), do: :erlang.error(:badarg)

  @doc ~S"""
  Fixes the array size to prevent it from growing automatically upon
  insertion.

  Note that operations which explicitly increase the array size, such as
  `append/2`, may still be used on a fixed size array.

  ## Examples

  ```erlang
  1> array:get(1, array:from_list([a,b,c])).
  b
  2> array:get(10, array:from_list([a,b,c])).
  undefined
  3> array:get(10, array:fix(array:from_list([a,b,c]))).
  ** exception error: bad argument
       in function  array:get/2
  ```

  See also `relax/1`, `set/3`.
  """
  @spec fix(array :: array(type)) :: array(type)
  def fix(array() = a), do: array(a, fix: true)

  @doc ~S"""
  Folds the array elements using the specified function and initial accumulator
  value.

  The elements are visited in order from the lowest index to the highest.

  If `Function` is not a function, the call fails with reason `badarg`.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,3)).
  2> array:foldl(fun(_K, V, Acc) -> V+Acc end, 0, A).
  6
  ```

  See also `foldl/5`, `foldr/3`, `sparse_foldl/3`.
  """
  @spec foldl(function, initialAcc :: a, array :: array(type)) :: a when function: (index :: array_indx(), value :: type, acc :: a -> a)
  def foldl(function, acc, array(size: n) = array) when is_integer(n), do: foldl(0, n - 1, function, acc, array)

  def foldl(_, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Folds the array elements from `Low` to `High` using the specified function and
  initial accumulator value.

  The elements are visited in order from the lowest index to the
  highest.

  If `Function` is not a function, the call fails with reason `badarg`.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,100)).
  2> array:foldl(50, 59, fun(_K, V, Acc) -> V+Acc end, 0, A).
  545
  ```

  See also `foldl/3`, `sparse_foldl/5`.
  """
  @spec foldl(low, high, function, initialAcc :: a, array) :: a when low: array_indx(), high: array_indx(), function: (index :: array_indx(), value :: type, acc :: a -> a), array: array(type)
  def foldl(low, high, function, acc, array(size: n, zero: z, cache: c, cache_index: cI, elements: e, default: d, bits: s)) when is_integer(low) and low >= 0 and is_integer(high) and is_integer(n) and high < n and is_function(function, 3) and is_integer(z) and is_integer(cI) and is_integer(s) do
    cond do
      low <= high ->
        e1 = set_leaf(cI, s, e, c)
        foldl_1(low + z, high + z, low, s, e1, d, function, acc)
      true ->
        acc
    end
  end

  def foldl(_, _, _, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Folds the array elements right-to-left using the specified function and initial
  accumulator value.

  The elements are visited in order from the highest index to the
  lowest.

  If `Function` is not a function, the call fails with reason `badarg`.

  See also `foldr/5`, `foldl/3`, `sparse_foldr/3`.
  """
  @spec foldr(function, initialAcc :: a, array :: array(type)) :: a when function: (index :: array_indx(), value :: type, acc :: a -> a)
  def foldr(function, acc, array(size: n) = array) when is_integer(n), do: foldr(0, n - 1, function, acc, array)

  def foldr(_, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Folds the array elements from `High` to `Low` using the specified function and
  initial accumulator value.

  The elements are visited in order from the highest index to the
  lowest.

  If `Function` is not a function, the call fails with reason `badarg`.

  See also `foldr/3`, `foldl/5`.
  """
  @spec foldr(low, high, function, initialAcc :: a, array :: array(type)) :: a when low: array_indx(), high: array_indx(), function: (index :: array_indx(), value :: type, acc :: a -> a)
  def foldr(low, high, function, acc, array(size: n, zero: z, cache: c, cache_index: cI, elements: e, default: d, bits: s)) when is_integer(low) and low >= 0 and is_integer(high) and is_function(function, 3) and is_integer(n) and high < n and is_integer(z) and is_integer(cI) and is_integer(s) do
    cond do
      low <= high ->
        e1 = set_leaf(cI, s, e, c)
        foldr_1(low + z, high + z, high, s, e1, d, function, acc)
      true ->
        acc
    end
  end

  def foldr(_, _, _, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Equivalent to [`from(Fun, State, undefined)`](`from/3`).
  """
  @spec from(function, state :: term()) :: array(type) when function: (state0 :: term() -> ({type, state1 :: term()} | :done))
  def from(fun, state), do: from(fun, state, :undefined)

  @doc ~S"""
  Creates an extendible array with values obtained with `Function(State)`.

  The 'Function(State)' shall return `{Value, NewState}` or `done`, and is invoked
  until `done` is returned, otherwise the call fails with reason `badarg`.

  `Default` is used as the value for uninitialized entries of the array.

  Note: Use `fix/1` on the resulting array if you want to prevent accesses
  outside the size range.

  ## Examples

  ```erlang
  1> Floats = << <<N:32/float-native>> || N <- lists:seq(0, 2047)>>.
  2> BinToVal = fun(I) ->
       case Floats of
           <<_:I/binary, N:32/float-native, _/binary>> ->
               {N, I+4};
           _ ->
               done
       end
     end.
  3> A = array:from(BinToVal, 0).
  4> array:get(10, A).
  10.0
  5> array:size(A).
  2048
  6> ValToBin = fun(_K, V, Acc) -> <<Acc/binary, V:32/float-native>> end.
  7> Floats == array:foldl(ValToBin, <<>>, A).
  true
  ```

  See also `new/2`, `from_list/1`, `foldl/3`.
  """
  @spec from(function, state :: term(), default :: term()) :: array(type) when function: (state0 :: term() -> ({type, state1 :: term()} | :done))
  def from(fun, st0, default) when is_function(fun, 1) do
    vS = fun.(st0)
    {e, n, s0} = from_fun_1(1 <<< 4, default, fun, vS, 0, [], [])
    cI = 0
    s = s0 - 4
    c = get_leaf(cI, s, e, default)
    array(size: n, zero: 0, fix: false, cache: c, cache_index: cI, default: default, elements: e, bits: s)
  end

  def from(_, _, _), do: error(:badarg)

  @doc ~S"""
  Equivalent to [`from_list(List, undefined)`](`from_list/2`).
  """
  @spec from_list(list :: [value :: type]) :: array(type)
  def from_list(list), do: from_list(list, :undefined)

  @doc ~S"""
  Converts a list to an extendible array.

  `Default` is used as the value for uninitialized entries of the array.

  If `List` is not a proper list, the call fails with reason `badarg`.

  Note: Use `fix/1` on the resulting array if you want to prevent accesses
  outside the size range.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,2), default).
  2> array:to_list(array:reset(1, A)).
  [0,default,2]
  ```

  See also `new/2`, `to_list/1`.
  """
  @spec from_list(list :: [value :: type], default :: term()) :: array(type)
  def from_list([], default), do: new({:default, default})

  def from_list(list, default) when is_list(list) do
    {e, n, s0} = from_list_1(1 <<< 4, list, default, 0, [], [])
    cI = 0
    s = s0 - 4
    c = get_leaf(cI, s, e, default)
    array(size: n, zero: 0, fix: false, cache: c, cache_index: cI, default: default, elements: e, bits: s)
  end

  def from_list(_, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Equivalent to [`from_orddict(Orddict, undefined)`](`from_orddict/2`).
  """
  @spec from_orddict(orddict :: indx_pairs(value :: type)) :: array(type)
  def from_orddict(orddict), do: from_orddict(orddict, :undefined)

  @doc ~S"""
  Converts an ordered list of pairs `{Index, Value}` to a corresponding extendible
  array.

  `Default` is used as the value for uninitialized entries of the array.

  If `Orddict` is not a proper, ordered list of pairs whose first elements are
  non-negative integers, the call fails with reason `badarg`.

  Note: Use `fix/1` on the resulting array if you want to prevent accesses
  outside the size range.

  ## Examples

  ```erlang
  1> A = array:from_orddict([{K,V} || K <:- lists:seq(2,4) && V <- [v1,v2,v3]], vx).
  2> array:to_orddict(A).
  [{0,vx},{1,vx},{2,v1},{3,v2},{4,v3}]
  ```

  See also `new/2`, `to_orddict/1`.
  """
  @spec from_orddict(orddict :: indx_pairs(value :: type), default :: dynamic()) :: array(type)
  def from_orddict([], default), do: new({:default, default})

  def from_orddict(list, default) when is_list(list) do
    {e, n, s0} = from_orddict_0(list, 0, 1 <<< 4, default, [])
    cI = 0
    s = s0 - 4
    c = get_leaf(cI, s, e, default)
    array(size: n, zero: 0, fix: false, cache: c, cache_index: cI, default: default, elements: e, bits: s)
  end

  def from_orddict(_, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Gets the value of entry `I`.

  If `I` is not a non-negative integer, or if the array has fixed size and `I` is
  larger than the maximum index, the call fails with reason `badarg`.

  If the array does not have fixed size, the default value for any index `I`
  greater than `size(Array)-1` is returned.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,9)).
  2> array:get(4,A).
  4
  3> array:get(10, A).
  undefined
  ```

  See also `set/3`.
  """
  @spec get(i :: array_indx(), array :: array(type)) :: value :: type
  def get(i0, array(size: n, zero: z, fix: fix, cache: c, cache_index: cI, elements: e, default: d, bits: s)) when is_integer(i0) and i0 >= 0 and is_integer(n) and is_integer(cI) and is_integer(s) and is_integer(z) do
    cond do
      i0 < n ->
        i = i0 + z
        cond do
          i >= cI and i < cI + 1 <<< 4 ->
            element(1 + i - cI, c)
          true ->
            get_1(i, s, e, d)
        end
      fix ->
        :erlang.error(:badarg)
      true ->
        d
    end
  end

  def get(_I, _A), do: :erlang.error(:badarg)

  @doc ~S"""
  Returns `true` if `X` is an array, otherwise `false`.

  Notice that the check is only shallow, as there is no guarantee that `X` is a
  well-formed array representation even if this function returns `true`.

  ## Examples

  ```erlang
  1> array:is_array(array:new(4, [])).
  true
  ```
  """
  @spec is_array(x :: term()) :: boolean()
  def is_array(array(size: size)) when is_integer(size), do: true

  def is_array(_), do: false

  @doc ~S"""
  Checks if the array has fixed size.

  Returns `true` if the array is fixed, otherwise `false`.

  ## Examples

  ```erlang
  1> array:is_fix(array:new()).
  false
  2> array:is_fix(array:new({fixed, true})).
  true
  ```

  See also `fix/1`.
  """
  @spec is_fix(array :: array()) :: boolean()
  def is_fix(array(fix: true)), do: true

  def is_fix(array()), do: false

  @doc ~S"""
  Maps the specified function onto each array element.

  The elements are visited in order from the lowest index to the
  highest.

  If `Function` is not a function, the call fails with reason `badarg`.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,3)).
  2> B = array:map(fun(K, V) -> K*V end, A).
  3> array:to_orddict(B).
  [{0,0},{1,1},{2,4},{3,9}]
  ```

  See also `mapfoldl/3`, `sparse_map/2`.
  """
  @spec map(function, array :: array(type1)) :: array((type1 | type2)) when function: (index :: array_indx(), type1 -> type2)
  def map(function, array) when is_function(function, 2) do
    {array1, _} = mapfoldl(fn i, v, _ ->
        {function.(i, v), []}
    end, [], array)
    array1
  end

  def map(_, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Combined map and fold over the array elements using the specified
  function and initial accumulator value.

  The elements are visited in order from the lowest index to the
  highest.

  If `Function` is not a function, the call fails with reason `badarg`.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,3)).
  2> {B, Acc} = array:mapfoldl(fun(K, V, Sum) -> {K*V, V+Sum} end, 0, A).
  3> Acc.
  6
  4> array:to_orddict(B).
  [{0,0}, {1,1}, {2,4}, {3,9}]
  ```

  See also `mapfoldl/5`, `foldl/3`, `map/2`, `sparse_mapfoldl/3`.
  """
  @spec mapfoldl(function, initialAcc :: a, array :: array(type)) :: {array(type), a} when function: (index :: array_indx(), value :: type, acc :: a -> {type, a})
  def mapfoldl(function, acc, array(size: n) = array) when is_integer(n), do: mapfoldl(0, n - 1, function, acc, array)

  def mapfoldl(_, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Combined map and fold over the array elements from `Low` to `High` using
  the specified function and initial accumulator value.

  The elements are visited in order from the lowest index to the
  highest.

  If `Function` is not a function, the call fails with reason `badarg`.

  See also `mapfoldl/3`, `sparse_mapfoldl/5`.
  """
  @spec mapfoldl(low, high, function, initialAcc :: a, array :: array(type)) :: {array(type), a} when low: array_indx(), high: array_indx(), function: (index :: array_indx(), value :: type, acc :: a -> {type, a})
  def mapfoldl(low, high, function, acc, array(size: n, zero: z, cache: c, cache_index: cI, elements: e, default: d, bits: s) = array) when is_integer(low) and low >= 0 and is_integer(high) and is_function(function, 3) and is_integer(n) and high < n and is_integer(z) and is_integer(cI) and is_integer(s) do
    cond do
      low <= high ->
        e0 = set_leaf(cI, s, e, c)
        {e1, acc1} = mapfoldl_1(low + z, high + z, low, s, e0, d, function, acc)
        c1 = get_leaf(cI, s, e1, d)
        {array(array, elements: e1, cache: c1), acc1}
      true ->
        {array, acc}
    end
  end

  def mapfoldl(_, _, _, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Combined map and fold over the array elements using the specified
  function and initial accumulator value.

  The elements are visited in order from the highest index to the
  lowest.

  If `Function` is not a function, the call fails with reason `badarg`.

  See also `mapfoldr/5`, `foldr/3`, `map/2`, `sparse_mapfoldr/3`.
  """
  @spec mapfoldr(function, initialAcc :: a, array :: array(type)) :: {array(type), a} when function: (index :: array_indx(), value :: type, acc :: a -> {type, a})
  def mapfoldr(function, acc, array(size: n) = array) when is_integer(n), do: mapfoldr(0, n - 1, function, acc, array)

  def mapfoldr(_, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Combined map and fold over the array elements from `Low` to `High` using
  the specified function and initial accumulator value.

  The elements are visited in order from the highest index to the lowest.

  If `Function` is not a function, the call fails with reason `badarg`.

  See also `mapfoldr/3`, `mapfoldl/5`, `sparse_mapfoldr/5`.
  """
  @spec mapfoldr(low, high, function, initialAcc :: a, array :: array(type)) :: {array(type), a} when low: array_indx(), high: array_indx(), function: (index :: array_indx(), value :: type, acc :: a -> {type, a})
  def mapfoldr(low, high, function, acc, array(size: n, zero: z, cache: c, cache_index: cI, elements: e, default: d, bits: s) = array) when is_integer(low) and low >= 0 and is_integer(high) and is_function(function, 3) and is_integer(n) and high < n and is_integer(z) and is_integer(cI) and is_integer(s) do
    cond do
      low <= high ->
        e0 = set_leaf(cI, s, e, c)
        {e1, acc1} = mapfoldr_1(low + z, high + z, high, s, e0, d, function, acc)
        c1 = get_leaf(cI, s, e1, d)
        {array(array, elements: e1, cache: c1), acc1}
      true ->
        {array, acc}
    end
  end

  def mapfoldr(_, _, _, _, _), do: :erlang.error(:badarg)

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @doc ~S"""
  Creates a new, extendible array with initial size zero.
  """
  @spec new() :: array()
  def new(), do: new([])

  @doc ~S"""
  Creates a new array according to the specified options.

  By default, the array is extendible and has initial size zero. Array
  indices start at `0`.

  `Options` is a single term or a list of terms, selected from the following:

  - **`N::integer() >= 0` or `{size, N::integer() >= 0}`** - Specifies the initial
    array size; this also implies `{fixed, true}`. If `N` is not a non-negative
    integer, the call fails with reason `badarg`.

  - **`fixed` or `{fixed, true}`** - Creates a fixed-size array. See also `fix/1`.

  - **`{fixed, false}`** - Creates an extendible (non-fixed-size) array.

  - **`{default, Value}`** - Sets the default value for the array to `Value`.

  Options are processed in the order they occur in the list, that is, later
  options have higher precedence.

  The default value is used as the value of uninitialized entries, and cannot be
  changed once the array has been created.

  ## Examples

  ```erlang
  1> array:new(100).
  ```

  creates a fixed-size array of size 100.

  ```
  1> array:new({default,0}).
  ```

  creates an empty, extendible array whose default value is `0`.

  ```
  1> array:new([{size,10},{fixed,false},{default,-1}]).
  ```

  creates an extendible array with initial size 10 whose default value is `-1`.

  See also `fix/1`, `from_list/2`, `get/2`, `new/0`, `new/2`, `set/3`.
  """
  @spec new(options :: array_opts()) :: array()
  def new(options), do: new_0(options, 0, false)

  @doc ~S"""
  Creates a new array according to the specified size and options.

  If `Size` is not a non-negative integer, the call fails with reason `badarg`.
  By default, the array has fixed size. Notice that any size specifications in
  `Options` override parameter `Size`.

  If `Options` is a list, this is equivalent to
  [`new([{size, Size} | Options])`](`new/1`), otherwise it is equivalent to
  [`new([{size, Size} | [Options]])`](`new/1`). However, using this function
  directly is more efficient.

  ## Examples

  ```erlang
  1> array:new(100, {default,0}).
  ```

  Creates a fixed-size array of size 100, whose default value is `0`.
  """
  @spec new(size :: non_neg_integer(), options :: array_opts()) :: array()
  def new(size, options) when is_integer(size) and size >= 0, do: new_0(options, size, true)

  def new(_, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Prepend a single value to the left side of the array.

  The operation is always allowed even if the array is fixed.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,9)).
  2> array:get(0, array:prepend(first, A)).
  first
  ```

  See also `append/2`, `concat/2`.
  """
  @spec prepend(value :: type, array :: array(type)) :: array(type)
  def prepend(value, array() = a), do: set(0, value, shift(-1, a))

  @doc ~S"""
  Makes the array extendible, reversing the effects of `fix/1`.

  ## Examples

  ```erlang
  1> array:get(10, array:new({fixed, true})).
  ** exception error: bad argument
       in function  array:get/2
  2> array:get(10, array:relax(array:new())).
  undefined
  ```

  See also `fix/1`.
  """
  @spec relax(array :: array(type)) :: array(type)
  def relax(array(size: n) = a) when is_integer(n) and n >= 0, do: array(a, fix: false)

  @doc ~S"""
  Resets entry `I` to the default value for the array.

  If the value of entry `I` is the default value, the array is returned
  unchanged.

  Reset never changes the array size. Shrinking can be done explicitly by calling
  `resize/2`.

  If `I` is not a non-negative integer, or if the array has fixed size and `I` is
  larger than the maximum index, the call fails with reason `badarg`; compare
  `set/3`.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,9)).
  2> array:get(5, array:reset(5, A)).
  undefined
  ```

  See also `new/2`, `set/3`.
  """
  @spec reset(i :: array_indx(), array :: array(type)) :: array(type)
  def reset(i0, array(size: n, zero: z, fix: fix, cache: c, cache_index: cI, default: d, elements: e, bits: s) = a) when is_integer(i0) and i0 >= 0 and is_integer(n) and is_integer(cI) and is_integer(s) and is_integer(z) do
    cond do
      i0 < n ->
        i = i0 + z
        cond do
          i >= cI and i < cI + 1 <<< 4 ->
            array(a, cache: setelement(1 + i - cI, c, d))
          true ->
            try do
              array(a, elements: reset_1(i, s, e, d))
            catch
              {:throw, :default, _} ->
                a
            end
        end
      fix ->
        :erlang.error(:badarg)
      true ->
        a
    end
  end

  def reset(_I, _A), do: :erlang.error(:badarg)

  @doc ~S"""
  Changes the array size to that reported by `sparse_size/1`.

  If the specified array has fixed size, the resulting array also has
  fixed size.

  ## Examples

  ```erlang
  1> A = array:set(1, x, array:new(4, [])).
  2> array:size(A).
  4
  3> array:size(array:resize(A)).
  2
  ```

  See also `resize/2`, `sparse_size/1`.
  """
  @spec resize(array :: array(type)) :: array(type)
  def resize(array), do: resize(sparse_size(array), array)

  @doc ~S"""
  Change the array size.

  If `Size` is not a non-negative integer, the call fails with reason `badarg`. If
  the specified array has fixed size, also the resulting array has fixed size.

  Note: As of OTP 29, resizing ensures that entries outside the new range are
  pruned so that garbage collection can recover the memory.

  ## Examples

  ```erlang
  1> array:get(10, array:new({fixed, true})).
  ** exception error: bad argument
       in function  array:get/2
  2> array:get(10, array:resize(20, array:new({fixed, true}))).
  undefined
  ```

  See also `shift/2`.
  """
  @spec resize(size :: non_neg_integer(), array :: array(type)) :: array(type)
  def resize(size, array(size: n, zero: z, cache: c, cache_index: cI, elements: e, default: d, bits: s) = a) when is_integer(size) and size >= 0 and is_integer(n) and n >= 0 and is_integer(cI) and is_integer(s) do
    cond do
      size > n ->
        case z > 0 do
          true ->
            e1 = set_leaf(cI, s, e, c)
            {e2, z2, s2} = shrink(z, n, s, e1, d)
            {e3, s3} = grow(z2 + size - 1, e2, s2)
            cI1 = 0
            c1 = get_leaf(cI1, s3, e3, d)
            array(a, size: size, zero: z2, elements: e3, cache: c1, cache_index: cI1, bits: s3)
          false ->
            {e1, s1} = grow(z + size - 1, e, s)
            array(a, size: size, elements: e1, bits: s1)
        end
      size < n or z > 0 ->
        e1 = set_leaf(cI, s, e, c)
        {e2, z2, s1} = shrink(z, size, s, e1, d)
        cI1 = 0
        c1 = get_leaf(cI1, s1, e2, d)
        array(a, size: size, zero: z2, elements: e2, cache: c1, cache_index: cI1, bits: s1)
      true ->
        a
    end
  end

  def resize(_Size, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Sets entry `I` of the array to `Value`.

  If `I` is not a non-negative integer, or if the array has fixed size and `I` is
  larger than the maximum index, the call fails with reason `badarg`.

  If the array does not have fixed size, and `I` is greater than `size(Array)-1`,
  the array grows to size `I+1`.

  ## Examples

  ```erlang
  1> A = array:new(4, [{fixed,true}]).
  2> array:set(1, x, A).
  3> array:set(5, x, A).
  ** exception error: bad argument
       in function  array:set/3
  ```

  See also `get/2`, `reset/2`.
  """
  @spec set(i :: array_indx(), value :: type, array :: array(type)) :: array(type)
  def set(i0, value, array(size: n, zero: z, fix: fix, cache: c, cache_index: cI, default: d, elements: e, bits: s) = a) when is_integer(i0) and i0 >= 0 and is_integer(n) and is_integer(cI) and is_integer(s) and is_integer(z) do
    i = i0 + z
    cond do
      i0 < n ->
        cond do
          i >= cI and i < cI + 1 <<< 4 ->
            array(a, cache: setelement(1 + i - cI, c, value))
          true ->
            r = i &&& 1 <<< 4 - 1
            cI1 = i - r
            e1 = set_leaf(cI, s, e, c)
            c1 = get_leaf(cI1, s, e1, d)
            c2 = setelement(1 + r, c1, value)
            array(a, elements: e1, cache: c2, cache_index: cI1)
        end
      fix ->
        :erlang.error(:badarg)
      true ->
        n1 = i0 + 1
        cond do
          i < 1 <<< s + 4 ->
            r = i &&& 1 <<< 4 - 1
            cI1 = i - r
            cond do
              cI1 !== cI ->
                e1 = set_leaf(cI, s, e, c)
                c1 = get_leaf(cI1, s, e1, d)
                c2 = setelement(1 + r, c1, value)
                array(a, size: n1, elements: e1, cache: c2, cache_index: cI1)
              true ->
                c1 = setelement(1 + r, c, value)
                array(a, size: n1, cache: c1, cache_index: cI1)
            end
          true ->
            r = i &&& 1 <<< 4 - 1
            cI1 = i - r
            {e1, s1} = grow(i, e, s)
            e2 = set_leaf(cI, s1, e1, c)
            c1 = get_leaf(cI1, s1, e2, d)
            c2 = setelement(1 + r, c1, value)
            array(a, size: n1, elements: e2, cache: c2, cache_index: cI1, bits: s1)
        end
    end
  end

  def set(_I, _V, _A), do: :erlang.error(:badarg)

  @doc ~S"""
  Shift the array a number of steps to the left, or to the right if the
  number is negative.

  Shifting left drops elements from the left side, reducing the array
  size, and shifting right adds space on the left, increasing the array
  size.

  The fixed option does not affect the result of shift.

  Note: For efficiency, this does not prune the representation, which means
  that a subsequent shift or similar operation can bring back the values that
  were shifted out. Use `resize/2` or `resize/1` if you want to ensure that
  values outside the range get pruned.

  ## Examples

  ```erlang
  1> A = array:new(10, [{fixed, true}]).
  2> array:size(A).
  10
  3> array:size(array:shift(-5, A)).
  15
  4> array:size(array:shift(5, A)).
  5
  ```
  """
  @spec shift(steps :: integer(), array :: array(type)) :: array(type)
  def shift(0, a = array()), do: a

  def shift(steps, array(size: n, zero: z) = a) when is_integer(steps) and is_integer(n) and steps <= n and is_integer(z) do
    z1 = z + steps
    n1 = n - steps
    cond do
      z1 >= 0 ->
        array(a, size: n1, zero: z1)
      true ->
        array(cache_index: cI, elements: e, bits: s) = a
        {e1, s1, z2} = grow_left(z1, e, s)
        cI1 = ^cI + ^z2 - ^z1
        array(a, size: ^n1, zero: ^z2, cache_index: ^cI1, elements: ^e1, bits: ^s1)
    end
  end

  def shift(_Steps, _A), do: :erlang.error(:badarg)

  @doc ~S"""
  Gets the number of entries in the array.

  Entries are numbered from `0` to `size(Array)-1`. Hence, this is also
  the index of the first entry that is guaranteed to not have been
  previously set.

  ## Examples

  ```erlang
  1> array:size(array:new(4, [])).
  4
  2> array:size(array:set(5, value, array:new())).
  6
  ```
  """
  @spec size(array :: array()) :: non_neg_integer()
  def size(array(size: n)), do: n

  def size(_), do: :erlang.error(:badarg)

  @doc ~S"""
  Extract a slice of the array.

  This drops elements before `I` as with `shift/2`, and takes the following
  `Length` elements starting from `I`.

  If `N` is less than or equal to zero, the resulting array is empty. To extract
  a slice from `Start` to `End` inclusive, use `slice(Start, End-Start+1,
  Array)`.

  Note: For efficiency, this does not prune the representation, which means
  that a subsequent shift or similar operation can bring back the values that
  were shifted out. Use `resize/2` or `resize/1` if you want to ensure that
  values outside the range get pruned.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,9)).
  2> array:to_list(array:slice(2,3,A)).
  [2,3,4]
  ```
  """
  @spec slice(i :: array_indx(), length :: non_neg_integer(), array :: array(type)) :: array(type)
  def slice(i, length, array(size: n) = a) when is_integer(i) and i >= 0 and is_integer(n) and n >= 0 and i + length <= n do
    a1 = shift(i, a)
    array(a1, size: length)
  end

  def slice(_I, _N, _A), do: :erlang.error(:badarg)

  @doc ~S"""
  Folds the array elements using the specified function and initial accumulator
  value, skipping default-valued entries.

  The elements are visited in order from the lowest index to the
  highest.

  If `Function` is not a function, the call fails with reason `badarg`.

  See also `sparse_foldl/5`, `foldl/3`.
  """
  @spec sparse_foldl(function, initialAcc :: a, array :: array(type)) :: a when function: (index :: array_indx(), value :: type, acc :: a -> a)
  def sparse_foldl(function, acc, array(size: n) = array) when is_integer(n), do: sparse_foldl(0, n - 1, function, acc, array)

  def sparse_foldl(_, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Folds the array elements from `Low` to `High` using the specified
  function and initial accumulator value, skipping default-valued entries.

  The elements are visited in order from the lowest index to the highest.

  If `Function` is not a function, the call fails with reason `badarg`.

  See also `sparse_foldl/3`, `foldl/5`.
  """
  @spec sparse_foldl(low :: array_indx(), high :: array_indx(), function, initialAcc :: a, array :: array(type)) :: a when function: (index :: array_indx(), value :: type, acc :: a -> a)
  def sparse_foldl(low, high, function, initialAcc, array(default: d) = array) when is_function(function, 3) do
    skip = fn _I, v, a when v === d ->
        a
      i, v, a ->
        function.(i, v, a)
    end
    foldl(low, high, skip, initialAcc, array)
  end

  def sparse_foldl(_, _, _, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Folds the array elements right-to-left using the specified function and initial
  accumulator value, skipping default-valued entries.

  The elements are visited in order from the highest index to the
  lowest.

  If `Function` is not a function, the call fails with reason `badarg`.

  See also `sparse_foldr/5`, `foldr/3`, `sparse_foldl/3`.
  """
  @spec sparse_foldr(function, initialAcc :: a, array :: array(type)) :: a when function: (index :: array_indx(), value :: type, acc :: a -> a)
  def sparse_foldr(function, acc, array(size: n) = array) when is_integer(n), do: sparse_foldr(0, n - 1, function, acc, array)

  def sparse_foldr(_, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Folds the array elements from `High` to `Low` using the specified
  function and initial accumulator value, skipping default-valued entries.

  The elements are visited in order from the highest index to the lowest.

  If `Function` is not a function, the call fails with reason `badarg`.

  See also `sparse_foldr/3`, `foldr/5`.
  """
  @spec sparse_foldr(low :: array_indx(), high :: array_indx(), function, initialAcc :: a, array :: array(type)) :: a when function: (index :: array_indx(), value :: type, acc :: a -> a)
  def sparse_foldr(low, high, function, initialAcc, array(default: d) = array) when is_function(function, 3) do
    skip = fn _I, v, a when v === d ->
        a
      i, v, a ->
        function.(i, v, a)
    end
    foldr(low, high, skip, initialAcc, array)
  end

  def sparse_foldr(_, _, _, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Maps the specified function onto each array element, skipping default-valued
  entries.

  The elements are visited in order from the lowest index to the highest.

  If `Function` is not a function, the call fails with reason `badarg`.

  See also `map/2`.
  """
  @spec sparse_map(function, array :: array(type1)) :: array((type1 | type2)) when function: (index :: array_indx(), type1 -> type2)
  def sparse_map(function, array) when is_function(function, 2) do
    {array1, _} = sparse_mapfoldl(fn i, v, _ ->
        {function.(i, v), []}
    end, [], array)
    array1
  end

  def sparse_map(_, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Like `mapfoldl/3` but skips default-valued entries.

  See also `sparse_mapfoldl/5`, `sparse_mapfoldr/3`.
  """
  @spec sparse_mapfoldl(function, initialAcc :: a, array) :: {arrayRes, a} when array: array(type1), function: (index :: array_indx(), value :: type1, acc :: a -> {type2, a}), arrayRes: array((type1 | type2))
  def sparse_mapfoldl(function, acc, array(size: n) = array) when is_integer(n), do: sparse_mapfoldl(0, n - 1, function, acc, array)

  def sparse_mapfoldl(_, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Like `mapfoldl/5` but skips default-valued entries.

  See also `sparse_mapfoldl/3`, `sparse_mapfoldr/5`.
  """
  @spec sparse_mapfoldl(low, high, function, initialAcc :: a, array) :: {arrayRes, a} when low: array_indx(), high: array_indx(), function: (index :: array_indx(), value :: type1, acc :: a -> {type2, a}), array: array(type1), arrayRes: array((type1 | type2))
  def sparse_mapfoldl(low, high, function, acc, array(size: n, zero: z, cache: c, cache_index: cI, elements: e, default: d, bits: s) = array) when is_integer(low) and low >= 0 and is_integer(high) and is_function(function, 3) and is_integer(n) and high < n and is_integer(z) and is_integer(cI) and is_integer(s) do
    cond do
      low <= high ->
        e0 = set_leaf(cI, s, e, c)
        {e1, acc1} = sparse_mapfoldl_1(low + z, high + z, low, s, e0, d, function, acc)
        c1 = get_leaf(cI, s, e1, d)
        {array(array, elements: e1, cache: c1), acc1}
      true ->
        {array, acc}
    end
  end

  def sparse_mapfoldl(_, _, _, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Like `mapfoldr/3` but skips default-valued entries.

  See also `sparse_mapfoldr/5`, `sparse_mapfoldl/3`.
  """
  @spec sparse_mapfoldr(function, initialAcc :: a, array) :: {arrayRes, a} when array: array(type1), function: (index :: array_indx(), value :: type1, acc :: a -> {type2, a}), arrayRes: array((type1 | type2))
  def sparse_mapfoldr(function, acc, array(size: n) = array) when is_integer(n), do: sparse_mapfoldr(0, n - 1, function, acc, array)

  def sparse_mapfoldr(_, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Like `mapfoldr/5` but skips default-valued entries.

  See also `sparse_mapfoldr/3`, `sparse_mapfoldl/5`.
  """
  @spec sparse_mapfoldr(low, high, function, initialAcc :: a, array) :: {arrayRes, a} when low: array_indx(), high: array_indx(), function: (index :: array_indx(), value :: type1, acc :: a -> {type2, a}), array: array(type1), arrayRes: array((type1 | type2))
  def sparse_mapfoldr(low, high, function, acc, array(size: n, zero: z, cache: c, cache_index: cI, elements: e, default: d, bits: s) = array) when is_integer(low) and low >= 0 and is_integer(high) and is_function(function, 3) and is_integer(n) and high < n and is_integer(z) and is_integer(cI) and is_integer(s) do
    cond do
      low <= high ->
        e0 = set_leaf(cI, s, e, c)
        {e1, acc1} = sparse_mapfoldr_1(low + z, high + z, high, s, e0, d, function, acc)
        c1 = get_leaf(cI, s, e1, d)
        {array(array, elements: e1, cache: c1), acc1}
      true ->
        {array, acc}
    end
  end

  def sparse_mapfoldr(_, _, _, _, _), do: :erlang.error(:badarg)

  @doc ~S"""
  Gets the number of entries in the array up until the last non-default-valued
  entry.

  That is, returns `I+1` if `I` is the last non-default-valued entry in
  the array, or zero if no such entry exists.

  ## Examples

  ```erlang
  1> A = array:set(3, 42, array:new(10)).
  2> array:size(A).
  10
  3> array:sparse_size(A).
  4
  ```

  See also `resize/1`, `size/1`.
  """
  @spec sparse_size(array :: array()) :: non_neg_integer()
  def sparse_size(a) do
    f = fn i, _V, _A ->
        throw({:value, i})
    end
    try do
      sparse_foldr(f, [], a)
    catch
      {:throw, {:value, i}, _} when is_integer(i) ->
        i + 1
    else
      [] ->
        0
    end
  end

  @doc ~S"""
  Converts the array to a list, skipping default-valued entries.

  ## Examples

  ```erlang
  1> A = array:set(2, x, array:new()).
  2> array:to_list(A).
  [undefined,undefined,x]
  3> array:sparse_to_list(A).
  [x]
  ```

  See also `to_list/1`  and `to_orddict/1`.
  """
  @spec sparse_to_list(array :: array(type)) :: [value :: type]
  def sparse_to_list(array) do
    sparse_foldr(fn _I, v, a ->
        [v | a]
    end, [], array)
  end

  @doc ~S"""
  Converts the array to an ordered list of pairs `{Index, Value}`, skipping
  default-valued entries.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,2), default).
  2> array:to_orddict(array:reset(1, A)).
  [{0,0},{1,default},{2,2}]
  3> array:sparse_to_orddict(array:reset(1, A)).
  [{0,0},{2,2}]
  ```

  See also `to_orddict/1`.
  """
  @spec sparse_to_orddict(array :: array(type)) :: indx_pairs(value :: type)
  def sparse_to_orddict(array) do
    sparse_foldr(fn i, v, a ->
        [{i, v} | a]
    end, [], array)
  end

  @doc ~S"""
  Converts the array to a list.

  ## Examples

  ```erlang
  1> A = array:set(2, x, array:new()).
  2> array:to_list(A).
  [undefined,undefined,x]
  ```

  See also `from_list/2`, `sparse_to_list/1` and `to_orddict/1`.
  """
  @spec to_list(array :: array(type)) :: [value :: type]
  def to_list(array) do
    foldr(fn _I, v, a ->
        [v | a]
    end, [], array)
  end

  @doc ~S"""
  Converts the array to an ordered list of pairs `{Index, Value}`.

  ## Examples

  ```erlang
  1> A = array:from_list(lists:seq(0,2), default).
  2> array:to_orddict(array:reset(1, A)).
  [{0,0},{1,default},{2,2}]
  ```

  See also `from_orddict/2`, `sparse_to_orddict/1` and `to_list/1`.
  """
  @spec to_orddict(array :: array(type)) :: indx_pairs(value :: type)
  def to_orddict(array) do
    foldr(fn i, v, a ->
        [{i, v} | a]
    end, [], array)
  end

  @doc false
  @spec upgrade(oldArray :: (tuple() | array())) :: array()
  def upgrade(a = array()), do: a

  def upgrade({:array, size, max, def, es}) do
    a = old_sparse_foldl_1(size - 1, es, new({:default, def}), 0, &set/3, def)
    case max == 0 do
      true ->
        fix(a)
      false ->
        a
    end
  end

  def upgrade(a), do: error({:badarg, a})

  # Private Functions

  defp unquote(:"-concat/1-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-concat/2-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-concat/2-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-map/2-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-sparse_foldl/5-fun-0-")(arg1, arg2, arg3, arg4, arg5) do
    # body not decompiled
  end

  defp unquote(:"-sparse_foldr/5-fun-0-")(arg1, arg2, arg3, arg4, arg5) do
    # body not decompiled
  end

  defp unquote(:"-sparse_map/2-fun-0-")(arg1, arg2, arg3, arg4) do
    # body not decompiled
  end

  defp unquote(:"-sparse_size/1-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-sparse_to_list/1-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-sparse_to_orddict/1-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-to_list/1-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-to_orddict/1-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp collect_leafs(n, es, s) do
    i = n - 1 >>> s + 1
    pad = rem(1 <<< 4 - rem(i, 1 <<< 4), 1 <<< 4) * 1 <<< s
    case pad do
      0 ->
        collect_leafs(1 <<< 4, es, s, n, [], [])
      _ ->
        collect_leafs(1 <<< 4, [pad | es], s, n, [], [])
    end
  end

  defp collect_leafs(0, xs, s, n, as, es) do
    e = list_to_tuple(as)
    case xs do
      [] ->
        case es do
          [] ->
            {e, n, s + 4}
          _ ->
            collect_leafs(n, :lists.reverse([e | es]), s + 4)
        end
      _ ->
        collect_leafs(1 <<< 4, xs, s, n, [], [e | es])
    end
  end

  defp collect_leafs(i, [x | xs], s, n, as0, es0) when is_integer(x) do
    step0 = x >>> s
    cond do
      step0 < i ->
        as = push_n(step0, [], as0)
        collect_leafs(i - step0, xs, s, n, as, es0)
      i === 1 <<< 4 ->
        step = rem(step0, 1 <<< 4)
        as = push_n(step, [], as0)
        collect_leafs(i - step, xs, s, n, as, [x | es0])
      i === step0 ->
        as = push_n(i, [], as0)
        collect_leafs(0, xs, s, n, as, es0)
      true ->
        as = push_n(i, [], as0)
        step = step0 - i
        collect_leafs(0, [step <<< s | xs], s, n, as, es0)
    end
  end

  defp collect_leafs(i, [x | xs], s, n, as, es), do: collect_leafs(i - 1, xs, s, n, [x | as], es)

  defp collect_leafs(1 <<< 4, [], s, n, [], es), do: collect_leafs(n, :lists.reverse(es), s + 4)

  @spec find_bits(integer(), non_neg_integer()) :: non_neg_integer()
  defp find_bits(i, s) when i < 1 <<< s + 4, do: s

  defp find_bits(i, s), do: find_bits(i, s + 4)

  defp foldl_1(low, high, ix, s, [], d, f, a), do: foldl_4(low, high, ix, s, d, f, a)

  defp foldl_1(low, high, ix, 0, e, _D, f, a), do: foldl_3(low, high, ix, e, f, a)

  defp foldl_1(low, high, ix, s, e, d, f, a) do
    lDiv = low >>> s
    hDiv = high >>> s
    lRem = low &&& 1 <<< s - 1
    hRem = high &&& 1 <<< s - 1
    cond do
      lDiv === hDiv ->
        foldl_1(lRem, hRem, ix, s - 4, element(lDiv + 1, e), d, f, a)
      true ->
        a1 = foldl_1(lRem, 1 <<< s - 1, ix, s - 4, element(lDiv + 1, e), d, f, a)
        foldl_2(lDiv + 1, hDiv, ix + 1 <<< s - lRem, s, e, d, f, a1, hRem)
    end
  end

  defp foldl_2(low, high, ix, s, e, d, f, a, hRem) when low < high do
    a1 = foldl_1(0, 1 <<< s - 1, ix, s - 4, element(low + 1, e), d, f, a)
    foldl_2(low + 1, high, ix + 1 <<< s, s, e, d, f, a1, hRem)
  end

  defp foldl_2(low, _High, ix, s, e, d, f, a, hRem), do: foldl_1(0, hRem, ix, s - 4, element(low + 1, e), d, f, a)

  @spec foldl_3(array_indx(), array_indx(), array_indx(), tuple(), (array_indx(), any(), a -> a), a) :: a
  defp foldl_3(low, high, ix, e, f, a) when low <= high, do: foldl_3(low + 1, high, ix + 1, e, f, f.(ix, element(low + 1, e), a))

  defp foldl_3(_Low, _High, _Ix, _E, _F, a), do: a

  defp foldl_4(low, high, ix, 0, d, f, a), do: foldl_6(low, high, ix, d, f, a)

  defp foldl_4(low, high, ix, s, d, f, a) do
    lDiv = low >>> s
    hDiv = high >>> s
    lRem = low &&& 1 <<< s - 1
    hRem = high &&& 1 <<< s - 1
    cond do
      lDiv === hDiv ->
        foldl_4(lRem, hRem, ix, s - 4, d, f, a)
      true ->
        a1 = foldl_4(lRem, 1 <<< s - 1, ix, s - 4, d, f, a)
        foldl_5(lDiv + 1, hDiv, ix + 1 <<< s - lRem, s, d, f, a1, hRem)
    end
  end

  defp foldl_5(low, high, ix, s, d, f, a, hRem) when low < high do
    a1 = foldl_4(0, 1 <<< s - 1, ix, s - 4, d, f, a)
    foldl_5(low + 1, high, ix + 1 <<< s, s, d, f, a1, hRem)
  end

  defp foldl_5(_Low, _High, ix, s, d, f, a, hRem), do: foldl_4(0, hRem, ix, s - 4, d, f, a)

  defp foldl_6(low, high, ix, d, f, a) when low <= high, do: foldl_6(low + 1, high, ix + 1, d, f, f.(ix, d, a))

  defp foldl_6(_Low, _High, _Ix, _D, _F, a), do: a

  defp foldr_1(low, high, ix, s, [], d, f, a), do: foldr_4(low, high, ix, s, d, f, a)

  defp foldr_1(low, high, ix, 0, e, _D, f, a), do: foldr_3(low, high, ix, e, f, a)

  defp foldr_1(low, high, ix, s, e, d, f, a) do
    lDiv = low >>> s
    hDiv = high >>> s
    lRem = low &&& 1 <<< s - 1
    hRem = high &&& 1 <<< s - 1
    cond do
      lDiv === hDiv ->
        foldr_1(lRem, hRem, ix, s - 4, element(hDiv + 1, e), d, f, a)
      true ->
        a1 = foldr_1(0, hRem, ix, s - 4, element(hDiv + 1, e), d, f, a)
        foldr_2(lDiv, hDiv - 1, ix - hRem - 1, s, e, d, f, a1, lRem)
    end
  end

  defp foldr_2(low, high, ix, s, e, d, f, a, lRem) when low < high do
    a1 = foldr_1(0, 1 <<< s - 1, ix, s - 4, element(high + 1, e), d, f, a)
    foldr_2(low, high - 1, ix - 1 <<< s, s, e, d, f, a1, lRem)
  end

  defp foldr_2(_Low, high, ix, s, e, d, f, a, lRem), do: foldr_1(lRem, 1 <<< s - 1, ix, s - 4, element(high + 1, e), d, f, a)

  defp foldr_3(low, high, ix, e, f, a) when low <= high, do: foldr_3(low, high - 1, ix - 1, e, f, f.(ix, element(high + 1, e), a))

  defp foldr_3(_Low, _High, _Ix, _D, _F, a), do: a

  defp foldr_4(low, high, ix, 0, d, f, a), do: foldr_6(low, high, ix, d, f, a)

  defp foldr_4(low, high, ix, s, d, f, a) do
    lDiv = low >>> s
    hDiv = high >>> s
    lRem = low &&& 1 <<< s - 1
    hRem = high &&& 1 <<< s - 1
    cond do
      lDiv === hDiv ->
        foldr_4(lRem, hRem, ix, s - 4, d, f, a)
      true ->
        a1 = foldr_4(0, hRem, ix, s - 4, d, f, a)
        foldr_5(lDiv, hDiv - 1, ix - hRem - 1, s, d, f, a1, lRem)
    end
  end

  defp foldr_5(low, high, ix, s, d, f, a, lRem) when low < high do
    a1 = foldr_4(0, 1 <<< s - 1, ix, s - 4, d, f, a)
    foldr_5(low, high - 1, ix - 1 <<< s, s, d, f, a1, lRem)
  end

  defp foldr_5(_Low, _High, ix, s, d, f, a, lRem), do: foldr_4(lRem, 1 <<< s - 1, ix, s - 4, d, f, a)

  defp foldr_6(low, high, ix, d, f, a) when low <= high, do: foldr_6(low, high - 1, ix - 1, d, f, f.(ix, d, a))

  defp foldr_6(_Low, _High, _Ix, _D, _F, a), do: a

  defp from_fun_1(0, d, fun, vS, n, as, es) do
    e = list_to_tuple(:lists.reverse(as))
    case vS do
      :done ->
        case es do
          [] ->
            {e, n, 4}
          _ ->
            from_list_2_0(n, [e | es], 4)
        end
      _ ->
        from_fun_1(1 <<< 4, d, fun, vS, n, [], [e | es])
    end
  end

  defp from_fun_1(i, d, fun, :done, n, as, es), do: from_fun_1(i - 1, d, fun, :done, n, [d | as], es)

  defp from_fun_1(i, d, fun, {x, s}, n, as, es), do: from_fun_1(i - 1, d, fun, fun.(s), n + 1, [x | as], es)

  defp from_fun_1(_I, _D, _Fun, _VS, _N, _As, _Es), do: :erlang.error(:badarg)

  defp from_list_1(0, xs, d, n, as, es) do
    e = list_to_tuple(:lists.reverse(as))
    case xs do
      [] ->
        case es do
          [] ->
            {e, n, 4}
          _ ->
            from_list_2_0(n, [e | es], 4)
        end
      [_ | _] ->
        from_list_1(1 <<< 4, xs, d, n, [], [e | es])
      _ ->
        :erlang.error(:badarg)
    end
  end

  defp from_list_1(i, xs, d, n, as, es) do
    case xs do
      [x | xs1] ->
        from_list_1(i - 1, xs1, d, n + 1, [x | as], es)
      _ ->
        from_list_1(i - 1, xs, d, n, [d | as], es)
    end
  end

  defp from_list_2(0, xs, s, n, as, es) do
    e = list_to_tuple(as)
    case xs do
      [] ->
        case es do
          [] ->
            {e, n, s + 4}
          _ ->
            from_list_2_0(n, :lists.reverse([e | es]), s + 4)
        end
      _ ->
        from_list_2(1 <<< 4, xs, s, n, [], [e | es])
    end
  end

  defp from_list_2(i, [x | xs], s, n, as, es), do: from_list_2(i - 1, xs, s, n, [x | as], es)

  defp from_list_2_0(n, es, s), do: from_list_2(1 <<< 4, pad(n - 1 >>> s + 1, 1 <<< 4, [], es), s, n, [], [])

  defp from_orddict_0([], n, _Bits, _D, es) do
    case es do
      [e] ->
        {e, n, 4}
      _ ->
        collect_leafs(n, es, 4)
    end
  end

  defp from_orddict_0(xs = [{ix1, _} | _], ix, s0, d, es0) when is_integer(ix1) and ix1 > s0 do
    hole = ix1 - ix
    step = hole - hole &&& 1 <<< 4 - 1
    next = ix + step
    from_orddict_0(xs, next, next + 1 <<< 4, d, [step | es0])
  end

  defp from_orddict_0(xs0 = [{_, _} | _], ix0, s, d, es) do
    {xs, e, ix} = from_orddict_1(ix0, s, xs0, ix0, d, [])
    from_orddict_0(xs, ix, ix + 1 <<< 4, d, [e | es])
  end

  defp from_orddict_0(xs, _, _, _, _), do: :erlang.error({:badarg, xs})

  defp from_orddict_1(ix, ^ix, xs, n, _D, as) do
    e = list_to_tuple(:lists.reverse(as))
    {xs, e, n}
  end

  defp from_orddict_1(ix, s, xs, n0, d, as) do
    case xs do
      [{ix, val} | xs1] ->
        n = ix + 1
        from_orddict_1(n, s, xs1, n, d, [val | as])
      [{ix1, _} | _] when is_integer(ix1) and ix1 > ix ->
        n = ix + 1
        from_orddict_1(n, s, xs, n, d, [d | as])
      [_ | _] ->
        :erlang.error({:badarg, xs})
      _ ->
        from_orddict_1(ix + 1, s, xs, n0, d, [d | as])
    end
  end

  defp get_1(_I, _S, [], d), do: d

  defp get_1(i, 0, e, _D), do: element(i &&& 1 <<< 4 - 1 + 1, e)

  defp get_1(i, s, e, d) do
    iDiv = i >>> s &&& 1 <<< 4 - 1
    get_1(i, s - 4, element(iDiv + 1, e), d)
  end

  defp get_leaf(_I, _, [], d), do: :erlang.make_tuple(1 <<< 4, d)

  defp get_leaf(_I, 0, e, _D), do: e

  defp get_leaf(i, s, e, d) do
    iDiv = i >>> s &&& 1 <<< 4 - 1
    get_leaf(i, s - 4, element(iDiv + 1, e), d)
  end

  defp grow(i, [], s) when is_integer(i) do
    s1 = find_bits(i, s)
    {[], s1}
  end

  defp grow(i, e, 0), do: grow_1(i, e, 0)

  defp grow(i, e, s), do: grow_1(i, e, s)

  defp grow_1(i, e, s) do
    s1 = s + 4
    cond do
      i < 1 <<< s1 ->
        {e, s}
      true ->
        grow_1(i, setelement(1, {[], [], [], [], [], [], [], [], [], [], [], [], [], [], [], []}, e), s1)
    end
  end

  defp grow_left(z, [], s), do: grow_left_2(z, s)

  defp grow_left(z, e, s), do: grow_left_1(z, e, s)

  defp grow_left_1(z, e, s) when z >= 0, do: {e, s, z}

  defp grow_left_1(z, e, s) do
    s1 = s + 4
    i = div(1 <<< 4, 2)
    grow_left_1(z + i * 1 <<< s1, setelement(i + 1, {[], [], [], [], [], [], [], [], [], [], [], [], [], [], [], []}, e), s1)
  end

  defp grow_left_2(z, s) when z >= 0, do: {[], s, z}

  defp grow_left_2(z, s) do
    s1 = s + 4
    i = div(1 <<< 4, 2)
    grow_left_2(z + i * 1 <<< s1, s1)
  end

  defp mapfoldl_1(low, high, ix, s, [], d, f, a), do: mapfoldl_1(low, high, ix, s, unfold(s, d), d, f, a)

  defp mapfoldl_1(low, high, ix, 0, e, _D, f, a), do: mapfoldl_3(low, high, ix, tuple_to_list(e), f, a, [], 0)

  defp mapfoldl_1(low, high, ix, s, e, d, f, a) do
    lDiv = low >>> s
    hDiv = high >>> s
    lRem = low &&& 1 <<< s - 1
    hRem = high &&& 1 <<< s - 1
    cond do
      lDiv === hDiv ->
        {e1, a1} = mapfoldl_1(lRem, hRem, ix, s - 4, element(lDiv + 1, e), d, f, a)
        {setelement(lDiv + 1, e, e1), a1}
      true ->
        es = tuple_to_list(e)
        {es1, a1} = mapfoldl_2(lDiv, hDiv, ix, s, es, d, f, a, hRem, [], lRem, 0)
        {list_to_tuple(es1), a1}
    end
  end

  defp mapfoldl_2(low, high, ix, s, [e | es], d, f, a, hRem, es1, lRem, i) when i < low, do: mapfoldl_2(low, high, ix, s, es, d, f, a, hRem, [e | es1], lRem, i + 1)

  defp mapfoldl_2(low, high, ix, s, [e | es], d, f, a, hRem, es1, lRem, _I) do
    {e1, a1} = mapfoldl_1(lRem, 1 <<< s - 1, ix, s - 4, e, d, f, a)
    mapfoldl_2_1(low + 1, high, ix + 1 <<< s - lRem, s, es, d, f, a1, hRem, [e1 | es1])
  end

  defp mapfoldl_2_1(low, high, ix, s, [e | es], d, f, a, hRem, es1) when low < high do
    {e1, a1} = mapfoldl_1(0, 1 <<< s - 1, ix, s - 4, e, d, f, a)
    mapfoldl_2_1(low + 1, high, ix + 1 <<< s, s, es, d, f, a1, hRem, [e1 | es1])
  end

  defp mapfoldl_2_1(_Low, _High, ix, s, [e | es], d, f, a, hRem, es1) do
    {e1, a1} = mapfoldl_1(0, hRem, ix, s - 4, e, d, f, a)
    {:lists.reverse(:lists.reverse(es, [e1 | es1])), a1}
  end

  defp mapfoldl_3(low, high, ix, [e | es], f, a, es1, i) when i < low, do: mapfoldl_3(low, high, ix, es, f, a, [e | es1], i + 1)

  defp mapfoldl_3(low, high, ix, es, f, a, es1, _I), do: mapfoldl_3_1(low, high, ix, es, f, a, es1)

  defp mapfoldl_3_1(low, high, ix, [e | es], f, a, es1) when low <= high do
    {e1, a1} = f.(ix, e, a)
    mapfoldl_3_1(low + 1, high, ix + 1, es, f, a1, [e1 | es1])
  end

  defp mapfoldl_3_1(_Low, _High, _Ix, es, _F, a, es1), do: {list_to_tuple(:lists.reverse(:lists.reverse(es, es1))), a}

  defp mapfoldr_1(low, high, ix, s, [], d, f, a), do: mapfoldr_1(low, high, ix, s, unfold(s, d), d, f, a)

  defp mapfoldr_1(low, high, ix, 0, e, _D, f, a), do: mapfoldr_3(low, high, ix, :lists.reverse(tuple_to_list(e)), f, a, [], 1 <<< 4 - 1)

  defp mapfoldr_1(low, high, ix, s, e, d, f, a) do
    lDiv = low >>> s
    hDiv = high >>> s
    lRem = low &&& 1 <<< s - 1
    hRem = high &&& 1 <<< s - 1
    cond do
      lDiv === hDiv ->
        {e1, a1} = mapfoldr_1(lRem, hRem, ix, s - 4, element(hDiv + 1, e), d, f, a)
        {setelement(hDiv + 1, e, e1), a1}
      true ->
        es = :lists.reverse(tuple_to_list(e))
        {es1, a1} = mapfoldr_2(lDiv, hDiv, ix, s, es, d, f, a, lRem, [], hRem, 1 <<< 4 - 1)
        {list_to_tuple(es1), a1}
    end
  end

  defp mapfoldr_2(low, high, ix, s, [e | es], d, f, a, lRem, es1, hRem, i) when high < i, do: mapfoldr_2(low, high, ix, s, es, d, f, a, lRem, [e | es1], hRem, i - 1)

  defp mapfoldr_2(low, high, ix, s, [e | es], d, f, a, lRem, es1, hRem, _I) do
    {e1, a1} = mapfoldr_1(0, hRem, ix, s - 4, e, d, f, a)
    mapfoldr_2_1(low, high - 1, ix - hRem - 1, s, es, d, f, a1, lRem, [e1 | es1])
  end

  defp mapfoldr_2_1(low, high, ix, s, [e | es], d, f, a, lRem, es1) when low < high do
    {e1, a1} = mapfoldr_1(0, 1 <<< s - 1, ix, s - 4, e, d, f, a)
    mapfoldr_2_1(low, high - 1, ix - 1 <<< s, s, es, d, f, a1, lRem, [e1 | es1])
  end

  defp mapfoldr_2_1(_Low, _High, ix, s, [e | es], d, f, a, lRem, es1) do
    {e1, a1} = mapfoldr_1(lRem, 1 <<< s - 1, ix, s - 4, e, d, f, a)
    {:lists.reverse(es, [e1 | es1]), a1}
  end

  defp mapfoldr_3(low, high, ix, [e | es], f, a, es1, i) when high < i, do: mapfoldr_3(low, high, ix, es, f, a, [e | es1], i - 1)

  defp mapfoldr_3(low, high, ix, es, f, a, es1, _I), do: mapfoldr_3_1(low, high, ix, es, f, a, es1)

  defp mapfoldr_3_1(low, high, ix, [e | es], f, a, es1) when low <= high do
    {e1, a1} = f.(ix, e, a)
    mapfoldr_3_1(low, high - 1, ix - 1, es, f, a1, [e1 | es1])
  end

  defp mapfoldr_3_1(_Low, _High, _Ix, es, _F, a, es1), do: {list_to_tuple(:lists.reverse(es, es1)), a}

  defp new(size, fixed, default) do
    s = find_bits(size - 1, 0)
    c = :erlang.make_tuple(1 <<< 4, default)
    array(size: size, zero: 0, fix: fixed, cache: c, cache_index: 0, default: default, elements: [], bits: s)
  end

  defp new_0(options, size, fixed) when is_list(options), do: new_1(options, size, fixed, :undefined)

  defp new_0(options, size, fixed), do: new_1([options], size, fixed, :undefined)

  defp new_1([:fixed | options], size, _, default), do: new_1(options, size, true, default)

  defp new_1([{:fixed, fixed} | options], size, _, default) when is_boolean(fixed), do: new_1(options, size, fixed, default)

  defp new_1([{:default, default} | options], size, fixed, _), do: new_1(options, size, fixed, default)

  defp new_1([{:size, size} | options], _, _, default) when is_integer(size) and size >= 0, do: new_1(options, size, true, default)

  defp new_1([size | options], _, _, default) when is_integer(size) and size >= 0, do: new_1(options, size, true, default)

  defp new_1([], size, fixed, default), do: new(size, fixed, default)

  defp new_1(_Options, _Size, _Fixed, _Default), do: :erlang.error(:badarg)

  defp old_sparse_foldl_1(n, e = {_, _, _, _, _, _, _, _, _, _, s}, a, ix, f, d), do: old_sparse_foldl_2(1, e, a, ix, f, d, div(n, s) + 1, rem(n, s), s)

  defp old_sparse_foldl_1(_N, e, a, _Ix, _F, _D) when is_integer(e), do: a

  defp old_sparse_foldl_1(n, e, a, ix, f, d), do: old_sparse_foldl_3(1, e, a, ix, f, d, n + 1)

  defp old_sparse_foldl_2(i, e, a, ix, f, d, ^i, r, _S), do: old_sparse_foldl_1(r, element(i, e), a, ix, f, d)

  defp old_sparse_foldl_2(i, e, a, ix, f, d, n, r, s), do: old_sparse_foldl_2(i + 1, e, old_sparse_foldl_1(s - 1, element(i, e), a, ix, f, d), ix + s, f, d, n, r, s)

  defp old_sparse_foldl_3(i, t, a, ix, f, d, n) when i <= n do
    case element(i, t) do
      d ->
        old_sparse_foldl_3(i + 1, t, a, ix + 1, f, d, n)
      e ->
        old_sparse_foldl_3(i + 1, t, f.(ix, e, a), ix + 1, f, d, n)
    end
  end

  defp old_sparse_foldl_3(_I, _T, a, _Ix, _F, _D, _N), do: a

  defp pad(n, k, p, es), do: push_n(rem(k - rem(n, k), k), p, es)

  defp prune_left(e, n, d) when is_tuple(e) do
    cond do
      0 < n ->
        list_to_tuple(prune_left(0, n, d, tuple_to_list(e)))
      true ->
        e
    end
  end

  defp prune_left(i, n, d, [_ | es]) when i < n, do: [d | prune_left(i + 1, n, d, es)]

  defp prune_left(i, n, d, [e | es]), do: [e | prune_left(i + 1, n, d, es)]

  defp prune_left(_I, _N, _D, []), do: []

  defp prune_right(e, n, d) when is_tuple(e) do
    cond do
      n < tuple_size(e) - 1 ->
        list_to_tuple(prune_right(0, n, d, tuple_to_list(e)))
      true ->
        e
    end
  end

  defp prune_right(i, n, d, [e | es]) when i <= n, do: [e | prune_right(i + 1, n, d, es)]

  defp prune_right(i, n, d, [_ | es]), do: [d | prune_right(i + 1, n, d, es)]

  defp prune_right(_I, _N, _D, []), do: []

  defp push_n(0, _E, l), do: l

  defp push_n(n, e, l), do: push_n(n - 1, e, [e | l])

  defp reset_1(_I, _, [], _D), do: throw(:default)

  defp reset_1(i, 0, e, d) do
    indx = i &&& 1 <<< 4 - 1 + 1
    case element(indx, e) do
      d ->
        throw(:default)
      _ ->
        setelement(indx, e, d)
    end
  end

  defp reset_1(i, s, e, d) do
    iDiv = i >>> s &&& 1 <<< 4 - 1
    i1 = iDiv + 1
    setelement(i1, e, reset_1(i, s - 4, element(i1, e), d))
  end

  defp set_leaf(_I, 0, _E, c), do: c

  defp set_leaf(i, s, [], c), do: set_leaf_1(i, s, c)

  defp set_leaf(i, s, e, c) when s > 0 do
    iDiv = i >>> s &&& 1 <<< 4 - 1
    i1 = iDiv + 1
    setelement(i1, e, set_leaf(i, s - 4, element(i1, e), c))
  end

  defp set_leaf_1(i, s, c) when s > 0 do
    iDiv = i >>> s &&& 1 <<< 4 - 1
    setelement(iDiv + 1, {[], [], [], [], [], [], [], [], [], [], [], [], [], [], [], []}, set_leaf_1(i, s - 4, c))
  end

  defp set_leaf_1(_I, _S, c), do: c

  defp shrink(0, 0, _S, _E, _D), do: {[], 0, 0}

  defp shrink(z, size, s, e, d) when z > 0, do: shrink_left(z, size - 1, s, e, d)

  defp shrink(0, size, s, e, d), do: shrink_right(size - 1, s, e, d, 0)

  defp shrink_left(_Z, max, _S, [], _D) do
    s = find_bits(max, 0)
    {[], 0, s}
  end

  defp shrink_left(z, max, 0, e, d), do: shrink_right(z + max, 0, prune_left(e, z, d), d, z)

  defp shrink_left(z0, max, s, e, d) when z0 >>> s === z0 + max >>> s do
    i1 = z0 >>> s + 1
    z = z0 &&& 1 <<< s - 1
    shrink_left(z, max, s - 4, element(i1, e), d)
  end

  defp shrink_left(z0, max, s, e, d) do
    {e1, s1} = shrink_left_2(z0, s, e, d)
    shrink_right(z0 + max, s1, e1, d, z0)
  end

  defp shrink_left_2(_Z, s, [], _D), do: {[], s}

  defp shrink_left_2(z, 0, e, d), do: {prune_left(e, z, d), 0}

  defp shrink_left_2(z0, s, e, d) do
    iDiv = z0 >>> s
    iRem = z0 &&& 1 <<< s - 1
    e1 = prune_left(e, iDiv, [])
    i = iDiv + 1
    {e2, _} = shrink_left_2(iRem, s - 4, element(i, e1), d)
    {setelement(i, e1, e2), s}
  end

  defp shrink_right(i, _S, [], _D, z) do
    s = find_bits(i, 0)
    {[], z, s}
  end

  defp shrink_right(i, 0, e, d, z), do: {prune_right(e, i, d), z, 0}

  defp shrink_right(i, s, e, d, z) when i < 1 <<< s, do: shrink_right(i, s - 4, element(1, e), d, z)

  defp shrink_right(i, s, e, d, z), do: shrink_right_2(i, s, e, d, z)

  defp shrink_right_2(_I, s, [], _D, z), do: {[], z, s}

  defp shrink_right_2(i, 0, e, d, z), do: {prune_right(e, i, d), z, 0}

  defp shrink_right_2(i, s, e, d, z) do
    iDiv = i >>> s
    iRem = i &&& 1 <<< s - 1
    e1 = prune_right(e, iDiv, [])
    i1 = iDiv + 1
    {e2, _, _} = shrink_right_2(iRem, s - 4, element(i1, e1), d, z)
    {setelement(i1, e1, e2), z, s}
  end

  defp sparse_mapfoldl_1(_Low, _High, _Ix, _S, [], _D, _F, a), do: {[], a}

  defp sparse_mapfoldl_1(low, high, ix, 0, e, d, f, a), do: sparse_mapfoldl_3(low, high, ix, tuple_to_list(e), d, f, a, [], 0)

  defp sparse_mapfoldl_1(low, high, ix, s, e, d, f, a) do
    lDiv = low >>> s
    hDiv = high >>> s
    lRem = low &&& 1 <<< s - 1
    hRem = high &&& 1 <<< s - 1
    cond do
      lDiv === hDiv ->
        {e1, a1} = sparse_mapfoldl_1(lRem, hRem, ix, s - 4, element(lDiv + 1, e), d, f, a)
        {setelement(lDiv + 1, e, e1), a1}
      true ->
        es = tuple_to_list(e)
        {es1, a1} = sparse_mapfoldl_2(lDiv, hDiv, ix, s, es, d, f, a, hRem, [], lRem, 0)
        {list_to_tuple(es1), a1}
    end
  end

  defp sparse_mapfoldl_2(low, high, ix, s, [e | es], d, f, a, hRem, es1, lRem, i) when i < low, do: sparse_mapfoldl_2(low, high, ix, s, es, d, f, a, hRem, [e | es1], lRem, i + 1)

  defp sparse_mapfoldl_2(low, high, ix, s, [e | es], d, f, a, hRem, es1, lRem, _I) do
    {e1, a1} = sparse_mapfoldl_1(lRem, 1 <<< s - 1, ix, s - 4, e, d, f, a)
    sparse_mapfoldl_2_1(low + 1, high, ix + 1 <<< s - lRem, s, es, d, f, a1, hRem, [e1 | es1])
  end

  defp sparse_mapfoldl_2_1(low, high, ix, s, [e | es], d, f, a, hRem, es1) when low < high do
    {e1, a1} = sparse_mapfoldl_1(0, 1 <<< s - 1, ix, s - 4, e, d, f, a)
    sparse_mapfoldl_2_1(low + 1, high, ix + 1 <<< s, s, es, d, f, a1, hRem, [e1 | es1])
  end

  defp sparse_mapfoldl_2_1(_Low, _High, ix, s, [e | es], d, f, a, hRem, es1) do
    {e1, a1} = sparse_mapfoldl_1(0, hRem, ix, s - 4, e, d, f, a)
    {:lists.reverse(:lists.reverse(es, [e1 | es1])), a1}
  end

  defp sparse_mapfoldl_3(low, high, ix, [e | es], d, f, a, es1, i) when i < low, do: sparse_mapfoldl_3(low, high, ix, es, d, f, a, [e | es1], i + 1)

  defp sparse_mapfoldl_3(low, high, ix, es, d, f, a, es1, _I), do: sparse_mapfoldl_3_1(low, high, ix, es, d, f, a, es1)

  defp sparse_mapfoldl_3_1(low, high, ix, [e | es], d, f, a, es1) when low <= high do
    cond do
      e === d ->
        sparse_mapfoldl_3_1(low + 1, high, ix + 1, es, d, f, a, [e | es1])
      true ->
        {e1, a1} = f.(ix, e, a)
        sparse_mapfoldl_3_1(low + 1, high, ix + 1, es, d, f, a1, [e1 | es1])
    end
  end

  defp sparse_mapfoldl_3_1(_Low, _High, _Ix, es, _D, _F, a, es1), do: {list_to_tuple(:lists.reverse(:lists.reverse(es, es1))), a}

  defp sparse_mapfoldr_1(_Low, _High, _Ix, _S, [], _D, _F, a), do: {[], a}

  defp sparse_mapfoldr_1(low, high, ix, 0, e, d, f, a), do: sparse_mapfoldr_3(low, high, ix, :lists.reverse(tuple_to_list(e)), d, f, a, [], 1 <<< 4 - 1)

  defp sparse_mapfoldr_1(low, high, ix, s, e, d, f, a) do
    lDiv = low >>> s
    hDiv = high >>> s
    lRem = low &&& 1 <<< s - 1
    hRem = high &&& 1 <<< s - 1
    cond do
      lDiv === hDiv ->
        {e1, a1} = sparse_mapfoldr_1(lRem, hRem, ix, s - 4, element(hDiv + 1, e), d, f, a)
        {setelement(hDiv + 1, e, e1), a1}
      true ->
        es = :lists.reverse(tuple_to_list(e))
        {es1, a1} = sparse_mapfoldr_2(lDiv, hDiv, ix, s, es, d, f, a, lRem, [], hRem, 1 <<< 4 - 1)
        {list_to_tuple(es1), a1}
    end
  end

  defp sparse_mapfoldr_2(low, high, ix, s, [e | es], d, f, a, lRem, es1, hRem, i) when high < i, do: sparse_mapfoldr_2(low, high, ix, s, es, d, f, a, lRem, [e | es1], hRem, i - 1)

  defp sparse_mapfoldr_2(low, high, ix, s, [e | es], d, f, a, lRem, es1, hRem, _I) do
    {e1, a1} = sparse_mapfoldr_1(0, hRem, ix, s - 4, e, d, f, a)
    sparse_mapfoldr_2_1(low, high - 1, ix - hRem - 1, s, es, d, f, a1, lRem, [e1 | es1])
  end

  defp sparse_mapfoldr_2_1(low, high, ix, s, [e | es], d, f, a, lRem, es1) when low < high do
    {e1, a1} = sparse_mapfoldr_1(0, 1 <<< s - 1, ix, s - 4, e, d, f, a)
    sparse_mapfoldr_2_1(low, high - 1, ix - 1 <<< s, s, es, d, f, a1, lRem, [e1 | es1])
  end

  defp sparse_mapfoldr_2_1(_Low, _High, ix, s, [e | es], d, f, a, lRem, es1) do
    {e1, a1} = sparse_mapfoldr_1(lRem, 1 <<< s - 1, ix, s - 4, e, d, f, a)
    {:lists.reverse(es, [e1 | es1]), a1}
  end

  defp sparse_mapfoldr_3(low, high, ix, [e | es], d, f, a, es1, i) when high < i, do: sparse_mapfoldr_3(low, high, ix, es, d, f, a, [e | es1], i - 1)

  defp sparse_mapfoldr_3(low, high, ix, es, d, f, a, es1, _I), do: sparse_mapfoldr_3_1(low, high, ix, es, d, f, a, es1)

  defp sparse_mapfoldr_3_1(low, high, ix, [e | es], d, f, a, es1) when low <= high do
    cond do
      e === d ->
        sparse_mapfoldr_3_1(low, high - 1, ix - 1, es, d, f, a, [e | es1])
      true ->
        {e1, a1} = f.(ix, e, a)
        sparse_mapfoldr_3_1(low, high - 1, ix - 1, es, d, f, a1, [e1 | es1])
    end
  end

  defp sparse_mapfoldr_3_1(_Low, _High, _Ix, es, _D, _F, a, es1), do: {list_to_tuple(:lists.reverse(es, es1)), a}

  defp unfold(s, _D) when s > 0, do: {[], [], [], [], [], [], [], [], [], [], [], [], [], [], [], []}

  defp unfold(_S, d), do: :erlang.make_tuple(1 <<< 4, d)
end
