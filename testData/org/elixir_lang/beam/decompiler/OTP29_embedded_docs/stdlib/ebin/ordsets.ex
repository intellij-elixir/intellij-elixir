# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :ordsets do
  @moduledoc ~S"""
  Functions for manipulating sets as ordered lists.

  Sets are collections of elements with no duplicate elements. An `ordset` is a
  representation of a set, where an ordered list is used to store the elements of
  the set. An ordered list is more efficient than an unordered list. Elements are
  ordered according to the _Erlang term order_.

  This module provides the same interface as the `m:sets` module but with a
  defined representation. One difference is that while `sets` considers two
  elements as different if they do not match (`=:=`), this module considers two
  elements as different if and only if they do not compare equal (`==`).

  See the [Compatibility Section in the `sets` module](`m:sets#module-compatibility`)
  for more information about the compatibility of the different implementations of
  sets in the Standard Library.

  ### See Also

  `m:gb_sets`, `m:sets`
  """

  # Types

  @typedoc ~S"""
  As returned by `new/0`.
  """
  @type ordset(t) :: [t]

  # Functions

  @doc ~S"""
  Returns a new ordered set formed from `Ordset1` with `Element` inserted.

  ## Examples

  ```erlang
  1> S0 = ordsets:new().
  []
  2> S1 = ordsets:add_element(7, S0).
  [7]
  3> S2 = ordsets:add_element(42, S1).
  [7,42]
  4> ordsets:add_element(42, S2).
  [7,42]
  ```
  """
  @spec add_element(element, ordset1) :: ordset2 when element: e, ordset1: ordset(t), ordset2: ordset((t | e))
  def add_element(e, [h | es]) when e > h, do: [h | add_element(e, es)]

  def add_element(e, [h | _] = set) when e < h, do: [e | set]

  def add_element(_E, [_H | _] = set), do: set

  def add_element(e, []), do: [e]

  @doc ~S"""
  Returns a copy of `Ordset1` with `Element` removed.

  ## Examples

  ```erlang
  1> S = ordsets:from_list([a,b,c]).
  2> ordsets:del_element(c, S).
  [a,b]
  3> ordsets:del_element(x, S).
  [a,b,c]
  ```
  """
  @spec del_element(element, ordset1) :: ordset2 when element: term(), ordset1: ordset(t), ordset2: ordset(t)
  def del_element(e, [h | es]) when e > h, do: [h | del_element(e, es)]

  def del_element(e, [h | _] = set) when e < h, do: set

  def del_element(_E, [_H | es]), do: es

  def del_element(_, []), do: []

  @doc ~S"""
  Filters elements in `Ordset1` using predicate function `Pred`.

  ## Examples

  ```erlang
  1> S = ordsets:from_list([1,2,3,4,5,6,7]).
  2> IsEven = fun(N) -> N rem 2 =:= 0 end.
  3> ordsets:filter(IsEven, S).
  [2,4,6]
  ```
  """
  @spec filter(pred, ordset1) :: ordset2 when pred: (element :: t -> boolean()), ordset1: ordset(t), ordset2: ordset(t)
  def filter(f, set), do: :lists.filter(f, set)

  @doc ~S"""
  Calls `Fun(Elem)` for each `Elem` of `Ordset1` to update or remove
  elements from `Ordset1`.

  `Fun/1` must return either a Boolean or a tuple `{true, Value}`. The
  function returns the set of elements for which `Fun` returns a new
  value, with `true` being equivalent to `{true, Elem}`.

  `ordsets:filtermap/2` behaves as if it were defined as follows:

  ```erlang
  filtermap(Fun, Ordset1) ->
      ordsets:from_list(lists:filtermap(Fun, ordsets:to_list(Ordset1))).
  ```

  ## Examples

  ```erlang
  1> S = ordsets:from_list([2,4,5,6,8,9]).
  2> F = fun(X) ->
             case X rem 2 of
                 0 -> {true, X div 2};
                 1 -> false
             end
          end.
  3> ordsets:filtermap(F, S).
  [1,2,3,4]
  ```
  """
  @spec filtermap(fun, ordset1) :: ordset2 when fun: (element1 :: t1 -> (boolean() | {true, element2 :: t2})), ordset1: ordset(t1), ordset2: ordset((t1 | t2))
  def filtermap(f, set), do: from_list(:lists.filtermap(f, set))

  @doc ~S"""
  Folds `Function` over every element in `Ordset` and returns the final value of
  the accumulator.

  ## Examples

  ```erlang
  1> S = ordsets:from_list([1,2,3,4]).
  2> Plus = fun erlang:'+'/2.
  3> ordsets:fold(Plus, 0, S).
  10
  ```
  """
  @spec fold(function, acc0, ordset) :: acc1 when function: (element :: t, accIn :: term() -> accOut :: term()), ordset: ordset(t), acc0: term(), acc1: term()
  def fold(f, acc, set), do: :lists.foldl(f, acc, set)

  @doc ~S"""
  Returns an ordered set of the elements in `List`.

  ## Examples

  ```erlang
  1> ordsets:from_list([a,b,a,b,b,c]).
  [a,b,c]
  ```
  """
  @spec from_list(list) :: ordset when list: [t], ordset: ordset(t)
  def from_list(l), do: :lists.usort(l)

  @doc ~S"""
  Returns the intersection of the non-empty list of sets.

  The intersection of multiple sets is a new set that contains only the
  elements that are present in all sets.

  ## Examples

  ```erlang
  1> S0 = ordsets:from_list([a,b,c,d]).
  2> S1 = ordsets:from_list([d,e,f]).
  3> S2 = ordsets:from_list([q,r]).
  4> Sets = [S0, S1, S2].
  5> ordsets:intersection([S0, S1, S2]).
  []
  6> ordsets:intersection([S0, S1]).
  [d]
  7> ordsets:intersection([]).
  ** exception error: no function clause matching ordsets:intersection([])
  ```
  """
  @spec intersection(ordsetList) :: ordset when ordsetList: [ordset(any()), ...], ordset: ordset(any())
  def intersection([s1, s2 | ss]), do: intersection1(intersection(s1, s2), ss)

  def intersection([s]), do: s

  @doc ~S"""
  Returns the intersection of `Ordset1` and `Ordset2`.

  The intersection of two sets is a new set that contains only the
  elements that are present in both sets.

  ## Examples

  ```erlang
  1> S0 = ordsets:from_list([a,b,c,d]).
  2> S1 = ordsets:from_list([c,d,e,f]).
  3> S2 = ordsets:from_list([q,r]).
  4> ordsets:intersection(S0, S1).
  [c,d]
  5> ordsets:intersection(S1, S2).
  []
  ```
  """
  @spec intersection(ordset1, ordset2) :: ordset3 when ordset1: ordset(any()), ordset2: ordset(any()), ordset3: ordset(any())
  def intersection([e1 | es1], [e2 | _] = set2) when e1 < e2, do: intersection(es1, set2)

  def intersection([e1 | _] = set1, [e2 | es2]) when e1 > e2, do: intersection(es2, set1)

  def intersection([e1 | es1], [_E2 | es2]), do: [e1 | intersection(es1, es2)]

  def intersection([], _), do: []

  def intersection(_, []), do: []

  @doc ~S"""
  Returns `true` if `Ordset1` and `Ordset2` are disjoint; otherwise,
  returns `false`.

  Two sets are disjoint if they have no elements in common.

  This function is equivalent to `ordsets:is_empty(ordsets:intersection(Ordset1, Ordset2))`, but faster.

  ## Examples

  ```erlang
  1> S0 = ordsets:from_list([a,b,c,d]).
  2> S1 = ordsets:from_list([d,e,f]).
  3> S2 = ordsets:from_list([q,r]).
  4> ordsets:is_disjoint(S0, S1).
  false
  5> ordsets:is_disjoint(S1, S2).
  true
  ```
  """
  @spec is_disjoint(ordset1, ordset2) :: boolean() when ordset1: ordset(any()), ordset2: ordset(any())
  def is_disjoint([e1 | es1], [e2 | _] = set2) when e1 < e2, do: is_disjoint(es1, set2)

  def is_disjoint([e1 | _] = set1, [e2 | es2]) when e1 > e2, do: is_disjoint(es2, set1)

  def is_disjoint([_E1 | _Es1], [_E2 | _Es2]), do: false

  def is_disjoint([], _), do: true

  def is_disjoint(_, []), do: true

  @doc ~S"""
  Returns `true` if `Element` is an element of `Ordset`; otherwise, returns `false`.

  ## Examples

  ```erlang
  1> S = ordsets:from_list([a,b,c]).
  2> ordsets:is_element(42, S).
  false
  3> ordsets:is_element(b, S).
  true
  ```
  """
  @spec is_element(element, ordset) :: boolean() when element: term(), ordset: ordset(any())
  def is_element(e, [h | es]) when e > h, do: is_element(e, es)

  def is_element(e, [h | _]) when e < h, do: false

  def is_element(_E, [_H | _]), do: true

  def is_element(_, []), do: false

  @doc ~S"""
  Returns `true` if `Ordset` is an empty set; otherwise, returns `false`.

  ## Examples

  ```erlang
  1> ordsets:is_empty(ordsets:new()).
  true
  2> ordsets:is_empty(ordsets:from_list([1])).
  false
  ```
  """
  @spec is_empty(ordset) :: boolean() when ordset: ordset(any())
  def is_empty(s), do: s === []

  @doc ~S"""
  Returns `true` if `Ordset1` and `Ordset2` are equal, that is, if every element
  of one set is also a member of the other set; otherwise, returns `false`.

  ## Examples

  ```erlang
  1> Empty = ordsets:new().
  2> S = ordsets:from_list([a,b]).
  3> ordsets:is_equal(S, S).
  true
  4> ordsets:is_equal(S, Empty).
  false
  ```
  """
  @spec is_equal(ordset1, ordset2) :: boolean() when ordset1: ordset(any()), ordset2: ordset(any())
  def is_equal(s1, s2) when is_list(s1) and is_list(s2), do: s1 == s2

  @doc ~S"""
  Returns `true` if `Ordset` is an ordered set of elements; otherwise,
  returns `false`.

  > #### Note {: .info }
  >
  > This function returns true for any ordered list, even if it was not
  > constructed using the functions in this module.

  ## Examples

  ```erlang
  1> ordsets:is_set(ordsets:from_list([a,x,13,{p,q}])).
  true
  2> ordsets:is_set([a,b,c]).
  true
  3> ordsets:is_set([z,a]).
  false
  4> ordsets:is_set({a,b}).
  false
  ```
  """
  @spec is_set(ordset) :: boolean() when ordset: term()
  def is_set([e | es]), do: is_set(es, e)

  def is_set([]), do: true

  def is_set(_), do: false

  @doc ~S"""
  Returns `true` when every element of `Ordset1` is also a member of `Ordset2`;
  otherwise, returns `false`.

  ## Examples

  ```erlang
  1> S0 = ordsets:from_list([a,b,c,d]).
  2> S1 = ordsets:from_list([c,d]).
  3> ordsets:is_subset(S1, S0).
  true
  4> ordsets:is_subset(S0, S1).
  false
  5> ordsets:is_subset(S0, S0).
  true
  ```
  """
  @spec is_subset(ordset1, ordset2) :: boolean() when ordset1: ordset(any()), ordset2: ordset(any())
  def is_subset([e1 | _], [e2 | _]) when e1 < e2, do: false

  def is_subset([e1 | _] = set1, [e2 | es2]) when e1 > e2, do: is_subset(set1, es2)

  def is_subset([_E1 | es1], [_E2 | es2]), do: is_subset(es1, es2)

  def is_subset([], _), do: true

  def is_subset(_, []), do: false

  @doc ~S"""
  Maps elements in `Ordset1` with mapping function `Fun`.

  ## Examples

  ```erlang
  1> S = ordsets:from_list([1,2,3,4,5,6,7]).
  2> F = fun(N) -> N div 2 end.
  3> ordsets:map(F, S).
  [0,1,2,3]
  ```
  """
  @spec map(fun, ordset1) :: ordset2 when fun: (element1 :: t1 -> element2 :: t2), ordset1: ordset(t1), ordset2: ordset(t2)
  def map(f, set), do: from_list(:lists.map(f, set))

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @doc ~S"""
  Returns a new empty ordered set.

  ## Examples

  ```erlang
  1> ordsets:new().
  []
  ```
  """
  @spec new() :: ordset(none())
  def new(), do: []

  @doc ~S"""
  Returns the number of elements in `Ordset`.

  ## Examples

  ```erlang
  1> ordsets:size(ordsets:new()).
  0
  2> ordsets:size(ordsets:from_list([4,5,6])).
  3
  ```
  """
  @spec size(ordset) :: non_neg_integer() when ordset: ordset(any())
  def size(s), do: length(s)

  @doc ~S"""
  Returns a new ordered set containing the elements of `Ordset1`
  that are not elements in `Ordset2`.

  ## Examples

  ```erlang
  1> S0 = ordsets:from_list([a,b,c,d]).
  2> S1 = ordsets:from_list([c,d,e,f]).
  3> ordsets:subtract(S0, S1).
  [a,b]
  4> ordsets:subtract(S1, S0).
  [e,f]
  ```
  """
  @spec subtract(ordset1, ordset2) :: ordset3 when ordset1: ordset(any()), ordset2: ordset(any()), ordset3: ordset(any())
  def subtract([e1 | es1], [e2 | _] = set2) when e1 < e2, do: [e1 | subtract(es1, set2)]

  def subtract([e1 | _] = set1, [e2 | es2]) when e1 > e2, do: subtract(set1, es2)

  def subtract([_E1 | es1], [_E2 | es2]), do: subtract(es1, es2)

  def subtract([], _), do: []

  def subtract(es1, []), do: es1

  @doc ~S"""
  Returns the elements of `Ordset` as a list.

  ## Examples

  ```erlang
  1> S = ordsets:from_list([a,b]).
  2> ordsets:to_list(S).
  [a,b]
  ```
  """
  @spec to_list(ordset) :: list when ordset: ordset(t), list: [t]
  def to_list(s), do: s

  @doc ~S"""
  Returns the union of a list of sets.

  The union of multiple sets is a new set that contains all the elements from
  all sets, without duplicates.

  ## Examples

  ```erlang
  1> S0 = ordsets:from_list([a,b,c,d]).
  2> S1 = ordsets:from_list([d,e,f]).
  3> S2 = ordsets:from_list([q,r]).
  4> Sets = [S0, S1, S2].
  5> ordsets:union(Sets).
  [a,b,c,d,e,f,q,r]
  ```
  """
  @spec union(ordsetList) :: ordset when ordsetList: [ordset(t)], ordset: ordset(t)
  def union(ordsetList), do: :lists.umerge(ordsetList)

  @doc ~S"""
  Returns the union of `Ordset1` and `Ordset2`.

  The union of two sets is a new set that contains all the elements from
  both sets, without duplicates.

  ## Examples

  ```erlang
  1> S0 = ordsets:from_list([a,b,c,d]).
  2> S1 = ordsets:from_list([c,d,e,f]).
  3> ordsets:union(S0, S1).
  [a,b,c,d,e,f]
  ```
  """
  @spec union(ordset1, ordset2) :: ordset3 when ordset1: ordset(t1), ordset2: ordset(t2), ordset3: ordset((t1 | t2))
  def union([e1 | es1], [e2 | _] = set2) when e1 < e2, do: [e1 | union(es1, set2)]

  def union([e1 | _] = set1, [e2 | es2]) when e1 > e2, do: [e2 | union(es2, set1)]

  def union([e1 | es1], [_E2 | es2]), do: [e1 | union(es1, es2)]

  def union([], es2), do: es2

  def union(es1, []), do: es1

  # Private Functions

  defp intersection1(s1, [s2 | ss]), do: intersection1(intersection(s1, s2), ss)

  defp intersection1(s1, []), do: s1

  defp is_set([e2 | es], e1) when e1 < e2, do: is_set(es, e2)

  defp is_set([_ | _], _), do: false

  defp is_set([], _), do: true
end
