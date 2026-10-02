# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :gb_sets do
  @moduledoc ~S"""
  Sets represented by general balanced trees.

  This module provides ordered sets using Prof. Arne Andersson's General Balanced
  Trees. Ordered sets can be much more efficient than using ordered lists, for
  larger sets, but depends on the application.

  The data representing a set as used by this module is to be regarded as opaque
  by other modules. In abstract terms, the representation is a composite type of
  existing Erlang terms. See note on
  [data types](`e:system:data_types.md#no_user_types`). Any code assuming
  knowledge of the format is running on thin ice.

  This module considers two elements as different if and only if they do not
  compare equal (`==`).

  ## Complexity

  The complexity on set operations is bounded by either _O(|S|)_ or _O(|T| _
  log(|S|))\*, where S is the largest given set, depending on which is fastest for
  any particular function call. For operating on sets of almost equal size, this
  implementation is about 3 times slower than using ordered-list sets directly.
  For sets of very different sizes, however, this solution can be arbitrarily much
  faster; in practical cases, often 10-100 times. This implementation is
  particularly suited for accumulating elements a few at a time, building up a
  large set (> 100-200 elements), and repeatedly testing for membership in the
  current set.

  As with normal tree structures, lookup (membership testing), insertion, and
  deletion have logarithmic complexity.

  ## Compatibility

  See the [Compatibility Section in the `sets` module](`m:sets#module-compatibility`)
  for information about the compatibility of the different implementations of sets
  in the Standard Library.

  ### See Also

  `m:gb_trees`, `m:ordsets`, `m:sets`
  """

  # Types

  @type iter :: iter(any())

  @typedoc ~S"""
  A general balanced set iterator.
  """
  @opaque iter(element) :: {(:ordered | :reversed), [gb_set_node(element)]}

  @type set :: set(any())

  @typedoc ~S"""
  A general balanced set.
  """
  @opaque set(element) :: {non_neg_integer(), gb_set_node(element)}

  # Private Types

  @typep gb_set_node(element) :: (nil | {element, any(), any()})

  # Functions

  @spec add(element, set1) :: set2 when set1: set(element), set2: set(element)
  def add(x, s) do
    case is_member(x, s) do
      true ->
        s
      false ->
        insert(x, s)
    end
  end

  @doc ~S"""
  Returns a new set formed from `Set1` with `Element` inserted.

  If `Element` is already an element in `Set1`, nothing is changed.

  ## Examples

  ```erlang
  1> S0 = gb_sets:new().
  2> S1 = gb_sets:add_element(7, S0).
  3> gb_sets:to_list(S1).
  [7]
  4> S2 = gb_sets:add_element(42, S1).
  5> S2 = gb_sets:add_element(42, S1).
  6> gb_sets:to_list(S2).
  [7,42]
  ```
  """
  @spec add_element(element, set1) :: set2 when set1: set(element), set2: set(element)
  def add_element(x, s), do: add(x, s)

  @doc ~S"""
  Rebalances the tree representation of `Set1`.

  This is rarely necessary, but can be motivated when a large number of
  elements have been deleted from the tree without further
  insertions. Forcing rebalancing can minimize lookup times, as deletion
  does not rebalance the tree.

  ## Examples

  ```erlang
  1> S0 = gb_sets:from_list(lists:seq(1, 100)).
  2> Delete = fun(E, Set) -> gb_sets:delete(E, Set) end.
  3> S1 = lists:foldl(Delete, S0, lists:seq(1, 50)).
  4> gb_sets:size(S1).
  50
  5> S2 = gb_sets:balance(S1).
  ```
  """
  @spec balance(set1) :: set2 when set1: set(element), set2: set(element)
  def balance({s, t}) when is_integer(s) and s >= 0, do: {s, balance_unchecked(t, s)}

  @spec del_element(element, set1) :: set2 when set1: set(element), set2: set(element)
  def del_element(key, s), do: delete_any(key, s)

  @doc ~S"""
  Returns a new set formed from `Set1` with `Element` removed, assuming
  `Element` is present in `Set1`.

  Use `delete_any/2` when deleting from a set where `Element` is potentially
  missing.

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([a,b]).
  2> gb_sets:to_list(gb_sets:delete(b, S)).
  [a]
  ```
  """
  @spec delete(element, set1) :: set2 when set1: set(element), set2: set(element)
  def delete(key, {s, t}), do: {s - 1, delete_1(key, t)}

  @doc ~S"""
  Returns a new set formed from `Set1` with `Element` removed.

  If `Element` is not an element in `Set1`, nothing is changed.

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([a,b]).
  2> gb_sets:to_list(gb_sets:delete_any(b, S)).
  [a]
  3> S = gb_sets:delete_any(x, S).
  ```
  """
  @spec delete_any(element, set1) :: set2 when set1: set(element), set2: set(element)
  def delete_any(key, s) do
    case is_member(key, s) do
      true ->
        delete(key, s)
      false ->
        s
    end
  end

  @spec difference(set1, set2) :: set3 when set1: set(element), set2: set(element), set3: set(element)
  def difference({n1, t1}, {n2, t2}) when is_integer(n1) and n1 >= 0 and is_integer(n2) and n2 >= 0, do: difference(to_list_1(t1), n1, t2, n2)

  @doc ~S"""
  Returns a new empty set.

  ## Examples

  ```erlang
  1> gb_sets:to_list(gb_sets:empty()).
  []
  ```
  """
  @spec empty() :: set when set: set(none())
  def empty(), do: {0, nil}

  @doc ~S"""
  Filters elements in `Set1` using predicate function `Pred`.

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([1,2,3,4,5,6,7]).
  2> IsEven = fun(N) -> N rem 2 =:= 0 end.
  3> Filtered = gb_sets:filter(IsEven, S).
  4> gb_sets:to_list(Filtered).
  [2,4,6]
  ```
  """
  @spec filter(pred, set1) :: set2 when pred: (element -> boolean()), set1: set(element), set2: set(element)
  def filter(f, s) when is_function(f, 1) do
    from_ordset_unchecked((for x <- to_list(s), f.(x) do
      x
    end))
  end

  @doc ~S"""
  Calls `Fun(Elem)` for each `Elem` of `Set1` to update or remove
  elements from `Set1`.

  `Fun/1` must return either a Boolean or a tuple `{true, Value}`. The
  function returns the set of elements for which `Fun` returns a new
  value, with `true` being equivalent to `{true, Elem}`.

  `gb_sets:filtermap/2` behaves as if it were defined as follows:

  ```erlang
  filtermap(Fun, Set1) ->
      gb_sets:from_list(lists:filtermap(Fun, gb_sets:to_list(Set1))).
  ```

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([2,4,5,6,8,9]).
  2> F = fun(X) ->
             case X rem 2 of
                 0 -> {true, X div 2};
                 1 -> false
             end
          end.
  3> Set = gb_sets:filtermap(F, S).
  4> gb_sets:to_list(Set).
  [1,2,3,4]
  ```
  """
  @spec filtermap(fun, set1) :: set2 when fun: (element1 -> (boolean() | {true, element2})), set1: set(element1), set2: set((element1 | element2))
  def filtermap(f, {_, t}) when is_function(f, 1), do: from_list(filtermap_1(t, f, []))

  @doc ~S"""
  Folds `Function` over every element in `Set` and returns the final value of
  the accumulator.

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([1,2,3,4]).
  2> Plus = fun erlang:'+'/2.
  3> gb_sets:fold(Plus, 0, S).
  10
  ```
  """
  @spec fold(function, acc0, set) :: acc1 when function: (element, accIn -> accOut), acc0: acc, acc1: acc, accIn: acc, accOut: acc, set: set(element)
  def fold(f, a, {_, t}) when is_function(f, 2), do: fold_1(f, a, t)

  @doc ~S"""
  Returns a set of the elements in `List`, where `List` can be unordered and
  contain duplicates.

  ## Examples

  ```erlang
  1> Unordered = [x,y,a,x,y,b,b,z].
  2> gb_sets:to_list(gb_sets:from_list(Unordered)).
  [a,b,x,y,z]
  ```
  """
  @spec from_list(list) :: set when list: [element], set: set(element)
  def from_list(l), do: from_ordset_unchecked(:ordsets.from_list(l))

  @doc ~S"""
  Turns an ordered list without duplicates `List` into a set.

  See `from_list/1` for a function that accepts unordered lists with
  duplicates.

  ## Examples

  ```erlang
  1> Ordset = [1,2,3].
  2> gb_sets:to_list(gb_sets:from_ordset(Ordset)).
  [1,2,3]
  ```
  """
  @spec from_ordset(list) :: set when list: [element], set: set(element)
  def from_ordset(l) do
    s = length(l)
    {s, balance_list_checked(l, s)}
  end

  @doc ~S"""
  Returns a new set formed from `Set1` with `Element` inserted,
  assuming `Element` is not already present.

  Use `add/2` for inserting into a set where `Element` is potentially
  already present.

  ## Examples

  ```erlang
  1> S0 = gb_sets:new().
  2> S1 = gb_sets:insert(7, S0).
  3> gb_sets:to_list(S1).
  [7]
  4> S2 = gb_sets:insert(42, S1).
  5> gb_sets:to_list(S2).
  [7,42]
  ```
  """
  @spec insert(element, set1) :: set2 when set1: set(element), set2: set(element)
  def insert(key, {s, t}) when is_integer(s) and s >= 0 do
    s1 = s + 1
    {s1, insert_1(key, t, s1 * s1)}
  end

  @doc ~S"""
  Returns the intersection of the non-empty list of sets.

  The intersection of multiple sets is a new set that contains only the
  elements that are present in all sets.

  ## Examples

  ```erlang
  1> S0 = gb_sets:from_list([a,b,c,d]).
  2> S1 = gb_sets:from_list([d,e,f]).
  3> S2 = gb_sets:from_list([q,r]).
  4> Sets = [S0, S1, S2].
  5> gb_sets:to_list(gb_sets:intersection([S0, S1, S2])).
  []
  6> gb_sets:to_list(gb_sets:intersection([S0, S1])).
  [d]
  7> gb_sets:intersection([]).
  ** exception error: no function clause matching gb_sets:intersection([])
  ```
  """
  @spec intersection(setList) :: set when setList: [set(element), ...], set: set(element)
  def intersection([s | ss]), do: intersection_list(s, ss)

  @doc ~S"""
  Returns the intersection of `Set1` and `Set2`.

  The intersection of two sets is a new set that contains only the
  elements that are present in both sets.

  ## Examples

  ```erlang
  1> S0 = gb_sets:from_list([a,b,c,d]).
  2> S1 = gb_sets:from_list([c,d,e,f]).
  3> S2 = gb_sets:from_list([q,r]).
  4> gb_sets:to_list(gb_sets:intersection(S0, S1)).
  [c,d]
  5> gb_sets:to_list(gb_sets:intersection(S1, S2)).
  []
  ```
  """
  @spec intersection(set1, set2) :: set3 when set1: set(element), set2: set(element), set3: set(element)
  def intersection({n1, t1}, {n2, t2}) when is_integer(n1) and is_integer(n2) and n2 < n1, do: intersection(to_list_1(t2), n2, t1, n1)

  def intersection({n1, t1}, {n2, t2}) when is_integer(n1) and is_integer(n2), do: intersection(to_list_1(t1), n1, t2, n2)

  @doc ~S"""
  Returns `true` if `Set1` and `Set2` are disjoint; otherwise, returns
  `false`.

  Two sets are disjoint if they have no elements in common.

  This function is equivalent to `gb_sets:is_empty(gb_sets:intersection(Set1, Set2))`,
  but faster.

  ## Examples

  ```erlang
  1> S0 = gb_sets:from_list([a,b,c,d]).
  2> S1 = gb_sets:from_list([d,e,f]).
  3> S2 = gb_sets:from_list([q,r]).
  4> gb_sets:is_disjoint(S0, S1).
  false
  5> gb_sets:is_disjoint(S1, S2).
  true
  ```
  """
  @spec is_disjoint(set1, set2) :: boolean() when set1: set(element), set2: set(element)
  def is_disjoint({n1, t1}, {n2, t2}) when n1 < n2, do: is_disjoint_1(t1, t2)

  def is_disjoint({_, t1}, {_, t2}), do: is_disjoint_1(t2, t1)

  @spec is_element(element, set) :: boolean() when set: set(element)
  def is_element(key, s), do: is_member(key, s)

  @doc ~S"""
  Returns `true` if `Set` is an empty set; otherwise, returns `false`.

  ## Examples

  ```erlang
  1> gb_sets:is_empty(gb_sets:new()).
  true
  2> gb_sets:is_empty(gb_sets:singleton(1)).
  false
  ```
  """
  @spec is_empty(set) :: boolean() when set: set()
  def is_empty({0, nil}), do: true

  def is_empty(_), do: false

  @doc ~S"""
  Returns `true` if `Set1` and `Set2` are equal, that is, if every element
  of one set is also a member of the other set; otherwise, returns `false`.

  ## Examples

  ```erlang
  1> Empty = gb_sets:new().
  2> S = gb_sets:from_list([a,b]).
  3> gb_sets:is_equal(S, S).
  true
  4> gb_sets:is_equal(S, Empty).
  false
  ```
  """
  @spec is_equal(set1, set2) :: boolean() when set1: set(), set2: set()
  def is_equal({size, s1}, {^size, _} = s2) do
    try do
      is_equal_1(s1, to_list(s2))
    catch
      {:throw, :not_equal, _} ->
        false
    else
      [] ->
        true
    end
  end

  def is_equal({_, _}, {_, _}), do: false

  @doc ~S"""
  Returns `true` if `Element` is an element of `Set`; otherwise, returns
  `false`.

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([a,b,c]).
  2> gb_sets:is_member(42, S).
  false
  3> gb_sets:is_member(b, S).
  true
  ```
  """
  @spec is_member(element, set) :: boolean() when set: set(element)
  def is_member(key, {_, t}), do: is_member_1(key, t)

  @doc ~S"""
  Returns `true` if `Term` appears to be a set; otherwise, returns `false`.

  > #### Note {: .info }
  >
  > This function will return `true` for any term that coincides with the
  > representation of a `gb_set`, while not really being a `gb_set`, thus
  > it might return false positive results. See also note on [data
  > types](`e:system:data_types.md#no_user_types`).
  >
  > Furthermore, since gb_sets are opaque, calling this function on terms
  > that are not gb_sets could result in `m:dialyzer` warnings.

  ## Examples

  ```erlang
  1> gb_sets:is_set(gb_sets:new()).
  true
  2> gb_sets:is_set(gb_sets:singleton(42)).
  true
  3> gb_sets:is_set(0).
  false
  ```
  """
  @spec is_set(term) :: boolean() when term: term()
  def is_set({0, nil}), do: true

  def is_set({n, {_, _, _}}) when is_integer(n) and n >= 0, do: true

  def is_set(_), do: false

  @doc ~S"""
  Returns `true` when every element of `Set1` is also a member of `Set2`;
  otherwise, returns `false`.

  ## Examples

  ```erlang
  1> S0 = gb_sets:from_list([a,b,c,d]).
  2> S1 = gb_sets:from_list([c,d]).
  3> gb_sets:is_subset(S1, S0).
  true
  4> gb_sets:is_subset(S0, S1).
  false
  5> gb_sets:is_subset(S0, S0).
  true
  ```
  """
  @spec is_subset(set1, set2) :: boolean() when set1: set(element), set2: set(element)
  def is_subset({n1, t1}, {n2, t2}) when is_integer(n1) and n1 >= 0 and is_integer(n2) and n2 >= 0, do: is_subset(to_list_1(t1), n1, t2, n2)

  @spec iterator(set) :: iter when set: set(element), iter: iter(element)
  def iterator(set), do: iterator(set, :ordered)

  @doc ~S"""
  Returns an iterator that can be used for traversing the entries of `Set` in
  either `ordered` or `reversed` direction; see `next/1`.

  The implementation is very efficient; traversing the whole set using
  [`next/1`](`next/1`) is only slightly slower than getting the list of
  all elements using `to_list/1` and traversing that. The main advantage
  of the iterator approach is that it avoids building the complete list
  of all elements in memory at once.

  ```erlang
  1> S = gb_sets:from_list([1,2,3,4,5]).
  2> Iter0 = gb_sets:iterator(S, ordered).
  3> element(1, gb_sets:next(Iter0)).
  1
  4> Iter1 = gb_sets:iterator(S, reversed).
  5> element(1, gb_sets:next(Iter1)).
  5
  ```
  """
  @spec iterator(set, order) :: iter when set: set(element), iter: iter(element), order: (:ordered | :reversed)
  def iterator({_, t}, :ordered), do: {:ordered, iterator_1(t, [])}

  def iterator({_, t}, :reversed), do: {:reversed, iterator_r(t, [])}

  @spec iterator_from(element, set) :: iter when set: set(element), iter: iter(element)
  def iterator_from(element, set), do: iterator_from(element, set, :ordered)

  @doc ~S"""
  Returns an iterator over members of `Set` in the given `Order`, starting
  from `Element` or, if absent, the first member that follows in the
  iteration order, if any; see `next/1`.

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([10,20,30,40,50]).
  2> Iter1 = gb_sets:iterator_from(17, S, ordered).
  3> element(1, gb_sets:next(Iter1)).
  20
  4> Iter2 = gb_sets:iterator_from(17, S, reversed).
  5> element(1, gb_sets:next(Iter2)).
  10
  ```
  """
  @spec iterator_from(element, set, order) :: iter when set: set(element), iter: iter(element), order: (:ordered | :reversed)
  def iterator_from(s, {_, t}, :ordered), do: {:ordered, iterator_from_1(s, t, [])}

  def iterator_from(s, {_, t}, :reversed), do: {:reversed, iterator_from_r(s, t, [])}

  @doc ~S"""
  Returns `{found, Element2}`, where `Element2` is the least element strictly
  greater than `Element1`.

  Returns `none` if no such element exists.

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([10,20,30]).
  2> gb_sets:larger(1, S).
  {found,10}
  3> gb_sets:larger(10, S).
  {found,20}
  4> gb_sets:larger(19, S).
  {found,20}
  5> gb_sets:larger(30, S).
  none
  ```
  """
  @spec larger(element1, set) :: (:none | {:found, element2}) when element1: element, element2: element, set: set(element)
  def larger(key, {_, t}), do: larger_1(key, t)

  @doc ~S"""
  Returns the largest element in `Set`.

  Assumes that `Set` is not empty.

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([a,b,c]).
  2> gb_sets:largest(S).
  c
  ```
  """
  @spec largest(set) :: element when set: set(element)
  def largest({_, t}), do: largest_1(t)

  @doc ~S"""
  Maps elements in `Set1` with mapping function `Fun`.

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([1,2,3,4,5,6,7]).
  2> F = fun(N) -> N div 2 end.
  3> Mapped = gb_sets:map(F, S).
  4> gb_sets:to_list(Mapped).
  [0,1,2,3]
  ```
  """
  @spec map(fun, set1) :: set2 when fun: (element1 -> element2), set1: set(element1), set2: set(element2)
  def map(f, {_, t}) when is_function(f, 1), do: from_list(map_1(t, f, []))

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @doc ~S"""
  Returns a new empty set.

  ## Examples

  ```erlang
  1> gb_sets:to_list(gb_sets:new()).
  []
  ```
  """
  @spec new() :: set when set: set(none())
  def new(), do: empty()

  @doc ~S"""
  Returns `{Element, Iter2}`, where `Element` is the first element referred to
  by iterator `Iter1`, and `Iter2` is the new iterator to be used for traversing
  the remaining elements, or the atom `none` if no elements remain.

  ```erlang
  1> S = gb_sets:from_list([1,2,3,4,5]).
  2> Iter0 = gb_sets:iterator(S).
  3> {Element0, Iter1} = gb_sets:next(Iter0).
  4> Element0.
  1
  5> {Element1, Iter2} = gb_sets:next(Iter1).
  6> Element1.
  2
  ```
  """
  @spec next(iter1) :: ({element, iter2} | :none) when iter1: iter(element), iter2: iter(element)
  def next({:ordered, [{x, _, t} | as]}), do: {x, {:ordered, iterator_1(t, as)}}

  def next({:reversed, [{x, t, _} | as]}), do: {x, {:reversed, iterator_r(t, as)}}

  def next({_, []}), do: :none

  @doc ~S"""
  Returns a set containing only element `Element`.

  ## Examples

  ```erlang
  1> S = gb_sets:singleton(42).
  2> gb_sets:to_list(S).
  [42]
  ```
  """
  @spec singleton(element) :: set(element)
  def singleton(key), do: {1, {key, nil, nil}}

  @doc ~S"""
  Returns the number of elements in `Set`.

  ## Examples

  ```erlang
  1> gb_sets:size(gb_sets:new()).
  0
  2> gb_sets:size(gb_sets:from_list([4,5,6])).
  3
  ```
  """
  @spec size(set) :: non_neg_integer() when set: set()
  def size({size, _}), do: size

  @doc ~S"""
  Returns `{found, Element2}`, where `Element2` is the greatest element strictly
  less than `Element1`.

  Returns `none` if no such element exists.

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([a,b,c]).
  2> gb_sets:smaller(b, S).
  {found,a}
  3> gb_sets:smaller(z, S).
  {found,c}
  4> gb_sets:smaller(a, S).
  none
  ```
  """
  @spec smaller(element1, set) :: (:none | {:found, element2}) when element1: element, element2: element, set: set(element)
  def smaller(key, {_, t}), do: smaller_1(key, t)

  @doc ~S"""
  Returns the smallest element in `Set`.

  Assumes that `Set` is not empty.

  ## Examples

  ```erlang
  1> S = gb_sets:from_list([a,b,c]).
  2> gb_sets:smallest(S).
  a
  ```
  """
  @spec smallest(set) :: element when set: set(element)
  def smallest({_, t}), do: smallest_1(t)

  @doc ~S"""
  Returns a new set containing the elements of `Set1`
  that are not elements in `Set2`.

  ## Examples

  ```erlang
  1> S0 = gb_sets:from_list([a,b,c,d]).
  2> S1 = gb_sets:from_list([c,d,e,f]).
  3> gb_sets:to_list(gb_sets:subtract(S0, S1)).
  [a,b]
  4> gb_sets:to_list(gb_sets:subtract(S1, S0)).
  [e,f]
  ```
  """
  @spec subtract(set1, set2) :: set3 when set1: set(element), set2: set(element), set3: set(element)
  def subtract(s1, s2), do: difference(s1, s2)

  @doc ~S"""
  Returns `{Element, Set2}`, where `Element` is the largest element in
  `Set1`, and `Set2` is this set with `Element` deleted.

  Assumes that `Set1` is not empty.

  ## Examples

  ```erlang
  1> S0 = gb_sets:from_list([a,b,c]).
  2> {Largest,S1} = gb_sets:take_largest(S0).
  3> Largest.
  c
  4> gb_sets:to_list(S1).
  [a,b]
  ```
  """
  @spec take_largest(set1) :: {element, set2} when set1: set(element), set2: set(element)
  def take_largest({s, t}) do
    {key, smaller} = take_largest1(t)
    {key, {s - 1, smaller}}
  end

  @doc ~S"""
  Returns `{Element, Set2}`, where `Element` is the smallest element in
  `Set1`, and `Set2` is this set with `Element` deleted.

  Assumes that `Set1` is not empty.

  ## Examples

  ```erlang
  1> S0 = gb_sets:from_list([a,b,c]).
  2> {Smallest,S1} = gb_sets:take_smallest(S0).
  3> Smallest.
  a
  4> gb_sets:to_list(S1).
  [b,c]
  ```
  """
  @spec take_smallest(set1) :: {element, set2} when set1: set(element), set2: set(element)
  def take_smallest({s, t}) do
    {key, larger} = take_smallest1(t)
    {key, {s - 1, larger}}
  end

  @doc ~S"""
  Returns the elements of `Set` as an ordered list.

  ```erlang
  1> gb_sets:to_list(gb_sets:from_list([4,3,5,1,2])).
  [1,2,3,4,5]
  ```
  """
  @spec to_list(set) :: list when set: set(element), list: [element]
  def to_list({_, t}), do: to_list(t, [])

  @doc ~S"""
  Returns the union of a list of sets.

  The union of multiple sets is a new set that contains all the elements from
  all sets, without duplicates.

  ## Examples

  ```erlang
  1> S0 = gb_sets:from_list([a,b,c,d]).
  2> S1 = gb_sets:from_list([d,e,f]).
  3> S2 = gb_sets:from_list([q,r]).
  4> Sets = [S0, S1, S2].
  5> Union = gb_sets:union(Sets).
  6> gb_sets:to_list(Union).
  [a,b,c,d,e,f,q,r]
  ```
  """
  @spec union(setList) :: set when setList: [set(element)], set: set(element)
  def union([s | ss]), do: union_list(s, ss)

  def union([]), do: empty()

  @doc ~S"""
  Returns the union of `Set1` and `Set2`.

  The union of two sets is a new set that contains all the elements from
  both sets, without duplicates.

  ## Examples

  ```erlang
  1> S0 = gb_sets:from_list([a,b,c,d]).
  2> S1 = gb_sets:from_list([c,d,e,f]).
  3> Union = gb_sets:union(S0, S1).
  4> gb_sets:to_list(Union).
  [a,b,c,d,e,f]
  ```
  """
  @spec union(set1, set2) :: set3 when set1: set(element), set2: set(element), set3: set(element)
  def union({n1, t1}, {n2, t2}) when is_integer(n1) and is_integer(n2) and n2 < n1, do: union(to_list_1(t2), n2, t1, n1)

  def union({n1, t1}, {n2, t2}) when is_integer(n1) and is_integer(n2), do: union(to_list_1(t1), n1, t2, n2)

  # Private Functions

  defp unquote(:"-filter/2-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp balance_list_checked(l, s) do
    {t, _} = balance_list_checked_1(l, s)
    t
  end

  defp balance_list_checked_1(l, s) when s > 1 do
    {s1, s2} = split_list_size(s)
    {t1, [k | l1]} = balance_list_checked_1(l, s1)
    case l1 do
      [k1 | _] when k >= k1 ->
        :erlang.error({:badarg, :not_ordset})
      _ ->
        {t2, l2} = balance_list_checked_1(l1, s2)
        t = {k, t1, t2}
        {t, l2}
    end
  end

  defp balance_list_checked_1([e1, e2 | _], 1) when e1 >= e2, do: :erlang.error({:badarg, :not_ordset})

  defp balance_list_checked_1([key | l], 1), do: {{key, nil, nil}, l}

  defp balance_list_checked_1(l, 0), do: {nil, l}

  defp balance_list_unchecked(l, s) do
    {t, _} = balance_list_unchecked_1(l, s)
    t
  end

  defp balance_list_unchecked_1(l, s) when s > 1 do
    {s1, s2} = split_list_size(s)
    {t1, [k | l1]} = balance_list_unchecked_1(l, s1)
    {t2, l2} = balance_list_unchecked_1(l1, s2)
    t = {k, t1, t2}
    {t, l2}
  end

  defp balance_list_unchecked_1([key | l], 1), do: {{key, nil, nil}, l}

  defp balance_list_unchecked_1(l, 0), do: {nil, l}

  defp balance_revlist(l, s) when is_integer(s) do
    {t, _} = balance_revlist_1(l, s)
    t
  end

  defp balance_revlist_1(l, s) when s > 1 do
    {s1, s2} = split_list_size(s)
    {t2, [k | l1]} = balance_revlist_1(l, s1)
    {t1, l2} = balance_revlist_1(l1, s2)
    t = {k, t1, t2}
    {t, l2}
  end

  defp balance_revlist_1([key | l], 1), do: {{key, nil, nil}, l}

  defp balance_revlist_1(l, 0), do: {nil, l}

  defp balance_unchecked(t, s), do: balance_list_unchecked(to_list_1(t), s)

  defp count({_, nil, nil}), do: {1, 1}

  defp count({_, sm, bi}) do
    {h1, s1} = count(sm)
    {h2, s2} = count(bi)
    {:erlang.max(h1, h2) <<< 1, s1 + s2 + 1}
  end

  defp count(nil), do: {1, 0}

  defp delete_1(key, {key1, smaller, larger}) when key < key1 do
    smaller1 = delete_1(key, smaller)
    {key1, smaller1, larger}
  end

  defp delete_1(key, {key1, smaller, bigger}) when key > key1 do
    bigger1 = delete_1(key, bigger)
    {key1, smaller, bigger1}
  end

  defp delete_1(_, {_, smaller, larger}), do: merge(smaller, larger)

  defp difference(l, n1, t2, n2) when n2 < 10, do: difference_2(l, to_list_1(t2), n1)

  defp difference(l, n1, t2, n2) do
    x = n1 * round(1.46 * :math.log(n2))
    cond do
      n2 < x ->
        difference_2(l, to_list_1(t2), n1)
      true ->
        difference_1(l, t2)
    end
  end

  defp difference_1(xs, t), do: difference_1(xs, t, [], 0)

  defp difference_1([x | xs], t, as, n) do
    case is_member_1(x, t) do
      true ->
        difference_1(xs, t, as, n)
      false ->
        difference_1(xs, t, [x | as], n + 1)
    end
  end

  defp difference_1([], _, as, n), do: {n, balance_revlist(as, n)}

  defp difference_2(xs, ys, s), do: difference_2(xs, ys, [], s)

  defp difference_2([x | xs1], [y | _] = ys, as, s) when x < y, do: difference_2(xs1, ys, [x | as], s)

  defp difference_2([x | _] = xs, [y | ys1], as, s) when x > y, do: difference_2(xs, ys1, as, s)

  defp difference_2([_X | xs1], [_Y | ys1], as, s), do: difference_2(xs1, ys1, as, s - 1)

  defp difference_2([], _Ys, as, s), do: {s, balance_revlist(as, s)}

  defp difference_2(xs, [], as, s), do: {s, balance_revlist(push(xs, as), s)}

  defp filtermap_1({key, small, big}, f, l) do
    case f.(key) do
      true ->
        filtermap_1(small, f, [key | filtermap_1(big, f, l)])
      {true, val} ->
        filtermap_1(small, f, [val | filtermap_1(big, f, l)])
      false ->
        filtermap_1(small, f, filtermap_1(big, f, l))
    end
  end

  defp filtermap_1(nil, _F, l), do: l

  defp fold_1(f, acc0, {key, small, big}) do
    acc1 = fold_1(f, acc0, small)
    acc = f.(key, acc1)
    fold_1(f, acc, big)
  end

  defp fold_1(_, acc, _), do: acc

  defp from_ordset_unchecked(l) do
    s = length(l)
    {s, balance_list_unchecked(l, s)}
  end

  defp insert_1(key, {key1, smaller, bigger}, s) when key < key1 do
    case insert_1(key, smaller, s >>> 1) do
      {t1, h1, s1} when is_integer(h1) and is_integer(s1) ->
        t = {key1, t1, bigger}
        {h2, s2} = count(bigger)
        h = :erlang.max(h1, h2) <<< 1
        sS = s1 + s2 + 1
        p = sS * sS
        cond do
          h > p ->
            balance_unchecked(t, sS)
          true ->
            {t, h, sS}
        end
      t1 ->
        {key1, t1, bigger}
    end
  end

  defp insert_1(key, {key1, smaller, bigger}, s) when key > key1 do
    case insert_1(key, bigger, s >>> 1) do
      {t1, h1, s1} when is_integer(h1) and is_integer(s1) ->
        t = {key1, smaller, t1}
        {h2, s2} = count(smaller)
        h = :erlang.max(h1, h2) <<< 1
        sS = s1 + s2 + 1
        p = sS * sS
        cond do
          h > p ->
            balance_unchecked(t, sS)
          true ->
            {t, h, sS}
        end
      t1 ->
        {key1, smaller, t1}
    end
  end

  defp insert_1(key, nil, 0), do: {{key, nil, nil}, 1, 1}

  defp insert_1(key, nil, _), do: {key, nil, nil}

  defp insert_1(key, _, _), do: :erlang.error({:key_exists, key})

  defp intersection(l, _N1, t2, n2) when n2 < 10, do: intersection_2(l, to_list_1(t2))

  defp intersection(l, n1, t2, n2) do
    x = n1 * round(1.46 * :math.log(n2))
    cond do
      n2 < x ->
        intersection_2(l, to_list_1(t2))
      true ->
        intersection_1(l, t2)
    end
  end

  defp intersection_1(xs, t), do: intersection_1(xs, t, [], 0)

  defp intersection_1([x | xs], t, as, n) do
    case is_member_1(x, t) do
      true ->
        intersection_1(xs, t, [x | as], n + 1)
      false ->
        intersection_1(xs, t, as, n)
    end
  end

  defp intersection_1([], _, as, n), do: {n, balance_revlist(as, n)}

  defp intersection_2(xs, ys), do: intersection_2(xs, ys, [], 0)

  defp intersection_2([x | xs1], [y | _] = ys, as, s) when x < y, do: intersection_2(xs1, ys, as, s)

  defp intersection_2([x | _] = xs, [y | ys1], as, s) when x > y, do: intersection_2(ys1, xs, as, s)

  defp intersection_2([x | xs1], [_ | ys1], as, s), do: intersection_2(xs1, ys1, [x | as], s + 1)

  defp intersection_2([], _, as, s), do: {s, balance_revlist(as, s)}

  defp intersection_2(_, [], as, s), do: {s, balance_revlist(as, s)}

  defp intersection_list(s, [s1 | ss]), do: intersection_list(intersection(s, s1), ss)

  defp intersection_list(s, []), do: s

  defp is_disjoint_1({k1, smaller1, bigger}, {k2, smaller2, _} = tree) when k1 < k2, do: not is_member_1(k1, smaller2) and is_disjoint_1(smaller1, smaller2) and is_disjoint_1(bigger, tree)

  defp is_disjoint_1({k1, smaller, bigger1}, {k2, _, bigger2} = tree) when k1 > k2, do: not is_member_1(k1, bigger2) and is_disjoint_1(bigger1, bigger2) and is_disjoint_1(smaller, tree)

  defp is_disjoint_1({_K1, _, _}, {_K2, _, _}), do: false

  defp is_disjoint_1(nil, _), do: true

  defp is_disjoint_1(_, nil), do: true

  defp is_equal_1(nil, keys), do: keys

  defp is_equal_1({key1, smaller, bigger}, keys0) do
    [key2 | keys] = is_equal_1(smaller, keys0)
    cond do
      key1 == key2 ->
        is_equal_1(bigger, keys)
      true ->
        throw(:not_equal)
    end
  end

  defp is_member_1(key, {key1, smaller, _}) when key < key1, do: is_member_1(key, smaller)

  defp is_member_1(key, {key1, _, bigger}) when key > key1, do: is_member_1(key, bigger)

  defp is_member_1(_, {_, _, _}), do: true

  defp is_member_1(_, nil), do: false

  defp is_subset(l, _N1, t2, n2) when n2 < 10, do: is_subset_2(l, to_list_1(t2))

  defp is_subset(l, n1, t2, n2) do
    x = n1 * round(1.46 * :math.log(n2))
    cond do
      n2 < x ->
        is_subset_2(l, to_list_1(t2))
      true ->
        is_subset_1(l, t2)
    end
  end

  defp is_subset_1([x | xs], t) do
    case is_member_1(x, t) do
      true ->
        is_subset_1(xs, t)
      false ->
        false
    end
  end

  defp is_subset_1([], _), do: true

  defp is_subset_2([x | _], [y | _]) when x < y, do: false

  defp is_subset_2([x | _] = xs, [y | ys1]) when x > y, do: is_subset_2(xs, ys1)

  defp is_subset_2([_ | xs1], [_ | ys1]), do: is_subset_2(xs1, ys1)

  defp is_subset_2([], _), do: true

  defp is_subset_2(_, []), do: false

  defp iterator_1({_, nil, _} = t, as), do: [t | as]

  defp iterator_1({_, l, _} = t, as), do: iterator_1(l, [t | as])

  defp iterator_1(nil, as), do: as

  defp iterator_from_1(s, {k, _, t}, as) when k < s, do: iterator_from_1(s, t, as)

  defp iterator_from_1(_, {_, nil, _} = t, as), do: [t | as]

  defp iterator_from_1(s, {_, l, _} = t, as), do: iterator_from_1(s, l, [t | as])

  defp iterator_from_1(_, nil, as), do: as

  defp iterator_from_r(s, {k, t, _}, as) when k > s, do: iterator_from_r(s, t, as)

  defp iterator_from_r(_, {_, _, nil} = t, as), do: [t | as]

  defp iterator_from_r(s, {_, _, r} = t, as), do: iterator_from_r(s, r, [t | as])

  defp iterator_from_r(_, nil, as), do: as

  defp iterator_r({_, _, nil} = t, as), do: [t | as]

  defp iterator_r({_, _, r} = t, as), do: iterator_r(r, [t | as])

  defp iterator_r(nil, as), do: as

  defp larger_1(_Key, nil), do: :none

  defp larger_1(key, {key1, smaller, _Larger}) when key < key1 do
    case larger_1(key, smaller) do
      :none ->
        {:found, key1}
      found ->
        found
    end
  end

  defp larger_1(key, {_Key, _Smaller, larger}), do: larger_1(key, larger)

  defp largest_1({key, _Smaller, nil}), do: key

  defp largest_1({_Key, _Smaller, larger}), do: largest_1(larger)

  defp map_1({key, small, big}, f, l), do: map_1(small, f, [f.(key) | map_1(big, f, l)])

  defp map_1(nil, _F, l), do: l

  defp merge(smaller, nil), do: smaller

  defp merge(nil, larger), do: larger

  defp merge(smaller, larger) do
    {key, larger1} = take_smallest1(larger)
    {key, smaller, larger1}
  end

  @spec mk_set(non_neg_integer(), gb_set_node(t)) :: set(t)
  defp mk_set(n, t), do: {n, t}

  defp push([x | xs], as), do: push(xs, [x | as])

  defp push([], as), do: as

  defp smaller_1(_Key, nil), do: :none

  defp smaller_1(key, {key1, _Smaller, larger}) when key > key1 do
    case smaller_1(key, larger) do
      :none ->
        {:found, key1}
      found ->
        found
    end
  end

  defp smaller_1(key, {_Key, smaller, _Larger}), do: smaller_1(key, smaller)

  defp smallest_1({key, nil, _Larger}), do: key

  defp smallest_1({_Key, smaller, _Larger}), do: smallest_1(smaller)

  defp take_largest1({key, smaller, nil}), do: {key, smaller}

  defp take_largest1({key, smaller, larger}) do
    {key1, larger1} = take_largest1(larger)
    {key1, {key, smaller, larger1}}
  end

  defp take_smallest1({key, nil, larger}), do: {key, larger}

  defp take_smallest1({key, smaller, larger}) do
    {key1, smaller1} = take_smallest1(smaller)
    {key1, {key, smaller1, larger}}
  end

  defp to_list({key, small, big}, l), do: to_list(small, [key | to_list(big, l)])

  defp to_list(nil, l), do: l

  defp to_list_1(t), do: to_list(t, [])

  defp union(l, n1, t2, n2) when n2 < 10, do: union_2(l, to_list_1(t2), n1 + n2)

  defp union(l, n1, t2, n2) do
    x = n1 * round(1.46 * :math.log(n2))
    cond do
      n2 < x ->
        union_2(l, to_list_1(t2), n1 + n2)
      true ->
        union_1(l, mk_set(n2, t2))
    end
  end

  defp union_1([x | xs], s), do: union_1(xs, add(x, s))

  defp union_1([], s), do: s

  defp union_2(xs, ys, s), do: union_2(xs, ys, [], s)

  defp union_2([x | xs1], [y | _] = ys, as, s) when x < y, do: union_2(xs1, ys, [x | as], s)

  defp union_2([x | _] = xs, [y | ys1], as, s) when x > y, do: union_2(ys1, xs, [y | as], s)

  defp union_2([x | xs1], [_ | ys1], as, s), do: union_2(xs1, ys1, [x | as], s - 1)

  defp union_2([], ys, as, s), do: {s, balance_revlist(push(ys, as), s)}

  defp union_2(xs, [], as, s), do: {s, balance_revlist(push(xs, as), s)}

  defp union_list(s, [s1 | ss]), do: union_list(union(s, s1), ss)

  defp union_list(s, []), do: s
end
