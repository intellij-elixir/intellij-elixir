# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :sets do
  @moduledoc ~S"""
  Sets are collections of elements with no duplicate elements.

  The data representing a set as used by this module is to be regarded as opaque
  by other modules. In abstract terms, the representation is a composite type of
  existing Erlang terms. See note on
  [data types](`e:system:data_types.md#no_user_types`). Any code assuming
  knowledge of the format is running on thin ice.

  This module provides the same interface as the `m:ordsets` module but
  with an undefined representation. One key difference is that this
  module considers two elements as different if they do not match
  (`=:=`), whereas `ordsets` considers them different if and only if
  they do not compare equal (`==`).

  Erlang/OTP 24.0 introduced a new more performant representation for sets which
  has become the default in Erlang/OTP 28. Developers can use the old representation
  by passing the `{version, 1}` flag to `new/1` and `from_list/2`. Functions that
  work on two sets, such as `union/2`, will work with sets of different
  versions. In such cases, there is no guarantee about the version of the returned set.
  Explicit conversion from the old version to the new one can be done with
  `sets:from_list(sets:to_list(Old), [{version,2}])`.

  ## Compatibility

  The following functions in this module also exist and provide the same
  functionality in the `m:gb_sets` and `m:ordsets` modules. That is, by only
  changing the module name for each call, you can try out different set
  representations.

  - `add_element/2`
  - `del_element/2`
  - `filter/2`
  - `filtermap/2`
  - `fold/3`
  - `from_list/1`
  - `intersection/1`
  - `intersection/2`
  - `is_disjoint/2`
  - `is_element/2`
  - `is_empty/1`
  - `is_equal/2`
  - `is_set/1`
  - `is_subset/2`
  - `map/2`
  - `new/0`
  - `size/1`
  - `subtract/2`
  - `to_list/1`
  - `union/1`
  - `union/2`

  > #### Note {: .info }
  >
  > While the three set implementations offer the same _functionality_ with
  > respect to the aforementioned functions, their overall _behavior_ may differ.
  > As mentioned, this module considers elements as different if and only if they
  > do not match (`=:=`), while both `m:ordsets` and `m:gb_sets` consider elements
  > as different if and only if they do not compare equal (`==`).
  >
  > ### Examples
  >
  > ```erlang
  > 1> sets:is_element(1.0, sets:from_list([1])).
  > false
  > 2> ordsets:is_element(1.0, ordsets:from_list([1])).
  > true
  > 3> gb_sets:is_element(1.0, gb_sets:from_list([1])).
  > true
  > ```

  ### See Also

  `m:gb_sets`, `m:ordsets`
  """

  # Types

  @type set :: set(any())

  @typedoc ~S"""
  As returned by `new/0`.
  """
  @opaque set(element) :: (set_v1(element) | set_v2(element))

  # Private Types

  @typep seg :: tuple()

  @typep segs(_Element) :: tuple()

  @typep set_v1(element) :: set(segs :: segs(element))

  @typep set_v2(element) :: %{optional(element) => []}

  # Functions

  @doc ~S"""
  Returns a new set formed from `Set1` with `Element` inserted.

  ## Examples

  ```erlang
  1> S0 = sets:new().
  2> S1 = sets:add_element(7, S0).
  3> sets:to_list(S1).
  [7]
  4> S2 = sets:add_element(42, S1).
  5> lists:sort(sets:to_list(S2)).
  [7,42]
  6> S2 = sets:add_element(42, S1).
  7> lists:sort(sets:to_list(S2)).
  [7,42]
  ```
  """
  @spec add_element(element, set1) :: set2 when set1: set(element), set2: set(element)
  def add_element(e, %{} = s), do: %{s | e => []}

  def add_element(e, set() = s0) do
    slot = get_slot(s0, e)
    bkt = get_bucket(s0, slot)
    case :lists.member(e, bkt) do
      true ->
        s0
      false ->
        s1 = update_bucket(s0, slot, [e | bkt])
        maybe_expand(s1)
    end
  end

  @doc ~S"""
  Returns a copy of `Set1` with `Element` removed.

  ## Examples

  ```erlang
  1> S = sets:from_list([a,b]).
  2> sets:to_list(sets:del_element(b, S)).
  [a]
  3> S = sets:del_element(x, S).
  4> lists:sort(sets:to_list(S)).
  [a,b]
  ```
  """
  @spec del_element(element, set1) :: set2 when set1: set(element), set2: set(element)
  def del_element(e, %{} = s), do: :maps.remove(e, s)

  def del_element(e, set() = s0) do
    slot = get_slot(s0, e)
    bkt = get_bucket(s0, slot)
    case :lists.member(e, bkt) do
      false ->
        s0
      true ->
        s1 = update_bucket(s0, slot, :lists.delete(e, bkt))
        maybe_contract(s1, 1)
    end
  end

  @doc ~S"""
  Filters elements in `Set1` using predicate function `Pred`.

  ## Examples

  ```erlang
  1> S = sets:from_list([1,2,3,4,5,6,7]).
  2> IsEven = fun(N) -> N rem 2 =:= 0 end.
  3> Filtered = sets:filter(IsEven, S).
  4> lists:sort(sets:to_list(Filtered)).
  [2,4,6]
  ```
  """
  @spec filter(pred, set1) :: set2 when pred: (element -> boolean()), set1: set(element), set2: set(element)
  def filter(f, %{} = d) when is_function(f, 1) do
    :maps.from_keys((for {k, _} <- d, f.(k) do
      k
    end), [])
  end

  def filter(f, set() = d) when is_function(f, 1), do: filter_set(f, d)

  @doc ~S"""
  Calls `Fun(Elem)` for each `Elem` of `Set1` to update or remove
  elements from `Set1`.

  `Fun/1` must return either a Boolean or a tuple `{true, Value}`. The
  function returns the set of elements for which `Fun` returns a new
  value, with `true` being equivalent to `{true, Elem}`.

  `sets:filtermap/2` behaves as if it were defined as follows:

  ```erlang
  filtermap(Fun, Set1) ->
      sets:from_list(lists:filtermap(Fun, sets:to_list(Set1))).
  ```

  ## Examples

  ```erlang
  1> S = sets:from_list([2,4,5,6,8,9]).
  2> F = fun(X) ->
             case X rem 2 of
                 0 -> {true, X div 2};
                 1 -> false
             end
          end.
  3> Set = sets:filtermap(F, S).
  4> lists:sort(sets:to_list(Set)).
  [1,2,3,4]
  ```
  """
  @spec filtermap(fun, set1) :: set2 when fun: (element1 -> (boolean() | {true, element2})), set1: set(element1), set2: set((element1 | element2))
  def filtermap(f, %{} = d) when is_function(f, 1), do: :maps.from_keys(:lists.filtermap(f, to_list(d)), [])

  def filtermap(f, set() = d) when is_function(f, 1) do
    fold(fn e0, acc ->
        case f.(e0) do
          true ->
            add_element(e0, acc)
          {true, e1} ->
            add_element(e1, acc)
          false ->
            acc
        end
    end, new([{:version, 1}]), d)
  end

  @doc ~S"""
  Folds `Function` over every element in `Set` and returns the final value of
  the accumulator.

  The evaluation order is undefined.

  ## Examples

  ```erlang
  1> S = sets:from_list([1,2,3,4]).
  2> Plus = fun erlang:'+'/2.
  3> sets:fold(Plus, 0, S).
  10
  ```
  """
  @spec fold(function, acc0, set) :: acc1 when function: (element, accIn -> accOut), set: set(element), acc0: acc, acc1: acc, accIn: acc, accOut: acc
  def fold(f, acc, %{} = d) when is_function(f, 2), do: fold_1(f, acc, :maps.iterator(d))

  def fold(f, acc, set() = d) when is_function(f, 2), do: fold_set(f, acc, d)

  @doc ~S"""
  Returns a set of the elements in `List`.

  ## Examples

  ```erlang
  1> S = sets:from_list([a,b,c]).
  2> lists:sort(sets:to_list(S)).
  [a,b,c]
  ```
  """
  @spec from_list(list) :: set when list: [element], set: set(element)
  def from_list(ls), do: :maps.from_keys(ls, [])

  @doc ~S"""
  Returns a set of the elements in `List` of the given version.

  ## Examples

  ```erlang
  1> S = sets:from_list([a,b,c], [{version, 1}]).
  2> lists:sort(sets:to_list(S)).
  [a,b,c]
  ```
  """
  @spec from_list(list, [{:version, 1..2}]) :: set when list: [element], set: set(element)
  def from_list(ls, [{:version, 2}]), do: from_list(ls)

  def from_list(ls, opts) do
    case :proplists.get_value(:version, opts, 2) do
      1 ->
        :lists.foldl(fn e, s ->
            add_element(e, s)
        end, new([{:version, 1}]), ls)
      2 ->
        from_list(ls)
    end
  end

  @doc ~S"""
  Returns the intersection of the non-empty list of sets.

  The intersection of multiple sets is a new set that contains only the
  elements that are present in all sets.

  ## Examples

  ```erlang
  1> S0 = sets:from_list([a,b,c,d]).
  2> S1 = sets:from_list([d,e,f]).
  3> S2 = sets:from_list([q,r]).
  4> Sets = [S0, S1, S2].
  5> sets:to_list(sets:intersection([S0, S1, S2])).
  []
  6> sets:to_list(sets:intersection([S0, S1])).
  [d]
  7> sets:intersection([]).
  ** exception error: no function clause matching sets:intersection([])
  ```
  """
  @spec intersection(setList) :: set when setList: [set(element), ...], set: set(element)
  def intersection([s1, s2 | ss]), do: intersection1(intersection(s1, s2), ss)

  def intersection([s]), do: s

  @doc ~S"""
  Returns the intersection of `Set1` and `Set2`.

  The intersection of two sets is a new set that contains only the
  elements that are present in both sets.

  ## Examples

  ```erlang
  1> S0 = sets:from_list([a,b,c,d]).
  2> S1 = sets:from_list([c,d,e,f]).
  3> S2 = sets:from_list([q,r]).
  4> Intersection = sets:intersection(S0, S1).
  5> lists:sort(sets:to_list(Intersection)).
  [c,d]
  6> sets:to_list(sets:intersection(S1, S2)).
  []
  ```
  """
  @spec intersection(set1, set2) :: set3 when set1: set(element), set2: set(element), set3: set(element)
  def intersection(%{} = s1, %{} = s2) do
    case map_size(s1) < map_size(s2) do
      true ->
        next = :maps.next(:maps.iterator(s1))
        intersection_heuristic(next, [], [], floor(map_size(s1) * 0.75), s1, s2)
      false ->
        next = :maps.next(:maps.iterator(s2))
        intersection_heuristic(next, [], [], floor(map_size(s2) * 0.75), s2, s1)
    end
  end

  def intersection(s1, s2) do
    case size(s1) < size(s2) do
      true ->
        filter(fn e ->
            is_element(e, s2)
        end, s1)
      false ->
        filter(fn e ->
            is_element(e, s1)
        end, s2)
    end
  end

  @doc ~S"""
  Returns `true` if `Set1` and `Set2` are disjoint; otherwise, returns
  `false`.

  Two sets are disjoint if they have no elements in common.

  This function is equivalent to `sets:is_empty(sets:intersection(Set1, Set2))`,
  but faster.

  ## Examples

  ```erlang
  1> S0 = sets:from_list([a,b,c,d]).
  2> S1 = sets:from_list([d,e,f]).
  3> S2 = sets:from_list([q,r]).
  4> sets:is_disjoint(S0, S1).
  false
  5> sets:is_disjoint(S1, S2).
  true
  ```
  """
  @spec is_disjoint(set1, set2) :: boolean() when set1: set(element), set2: set(element)
  def is_disjoint(%{} = s1, %{} = s2) do
    cond do
      map_size(s1) < map_size(s2) ->
        is_disjoint_1(s2, :maps.iterator(s1))
      true ->
        is_disjoint_1(s1, :maps.iterator(s2))
    end
  end

  def is_disjoint(s1, s2) do
    case size(s1) < size(s2) do
      true ->
        fold(fn _, false ->
            false
          e, true ->
            not is_element(e, s2)
        end, true, s1)
      false ->
        fold(fn _, false ->
            false
          e, true ->
            not is_element(e, s1)
        end, true, s2)
    end
  end

  @doc ~S"""
  Returns `true` if `Element` is an element of `Set`; otherwise, returns
  `false`.

  ## Examples

  ```erlang
  1> S = sets:from_list([a,b,c]).
  2> sets:is_element(42, S).
  false
  3> sets:is_element(b, S).
  true
  ```
  """
  @spec is_element(element, set) :: boolean() when set: set(element)
  def is_element(e, %{} = s) do
    case s do
      %{e => _} ->
        true
      _ ->
        false
    end
  end

  def is_element(e, set() = s) do
    slot = get_slot(s, e)
    bkt = get_bucket(s, slot)
    :lists.member(e, bkt)
  end

  @doc ~S"""
  Returns `true` if `Set` is an empty set; otherwise, returns `false`.

  ## Examples

  ```erlang
  1> sets:is_empty(sets:new()).
  true
  2> sets:is_empty(sets:from_list([1])).
  false
  ```
  """
  @spec is_empty(set) :: boolean() when set: set()
  def is_empty(%{} = s), do: map_size(s) === 0

  def is_empty(set(size: size)), do: size === 0

  @doc ~S"""
  Returns `true` if `Set1` and `Set2` are equal, that is, if every element
  of one set is also a member of the other set; otherwise, returns `false`.

  ## Examples

  ```erlang
  1> Empty = sets:new().
  2> S = sets:from_list([a,b]).
  3> sets:is_equal(S, S).
  true
  4> sets:is_equal(S, Empty).
  false
  5> OldSet = sets:from_list([a,b], [{version,1}]).
  6> sets:is_equal(S, OldSet).
  true
  7> S =:= OldSet.
  false
  ```
  """
  @spec is_equal(set1, set2) :: boolean() when set1: set(), set2: set()
  def is_equal(s1, s2) do
    case size(s1) === size(s2) do
      true when s1 === s2 ->
        true
      true ->
        canonicalize_v2(s1) === canonicalize_v2(s2)
      false ->
        false
    end
  end

  @doc ~S"""
  Returns `true` if `Set` appears to be a set of elements; otherwise,
  returns `false`.

  > #### Note {: .info }
  >
  > Note that the test is shallow and will return `true` for any term that
  > coincides with the possible representations of a set. See also note on
  > [data types](`e:system:data_types.md#no_user_types`).
  >
  > Furthermore, since sets are opaque, calling this function on terms
  > that are not sets could result in `m:dialyzer` warnings.

  ## Examples

  ```erlang
  1> sets:is_set(sets:new()).
  true
  2> sets:is_set(sets:new([{version,1}])).
  true
  3> sets:is_set(0).
  false
  ```
  """
  @spec is_set(set) :: boolean() when set: term()
  def is_set(%{}), do: true

  def is_set(set()), do: true

  def is_set(_), do: false

  @doc ~S"""
  Returns `true` when every element of `Set1` is also a member of `Set2`;
  otherwise, returns `false`.

  ## Examples

  ```erlang
  1> S0 = sets:from_list([a,b,c,d]).
  2> S1 = sets:from_list([c,d]).
  3> sets:is_subset(S1, S0).
  true
  4> sets:is_subset(S0, S1).
  false
  5> sets:is_subset(S0, S0).
  true
  ```
  """
  @spec is_subset(set1, set2) :: boolean() when set1: set(element), set2: set(element)
  def is_subset(%{} = s1, %{} = s2) do
    cond do
      map_size(s1) > map_size(s2) ->
        false
      true ->
        is_subset_1(s2, :maps.iterator(s1))
    end
  end

  def is_subset(s1, s2) do
    fold(fn e, sub ->
        sub and is_element(e, s2)
    end, true, s1)
  end

  @doc ~S"""
  Maps elements in `Set1` with mapping function `Fun`.

  ## Examples

  ```erlang
  1> S = sets:from_list([1,2,3,4,5,6,7]).
  2> F = fun(N) -> N div 2 end.
  3> Mapped = sets:map(F, S).
  4> lists:sort(sets:to_list(Mapped)).
  [0,1,2,3]
  ```
  """
  @spec map(fun, set1) :: set2 when fun: (element1 -> element2), set1: set(element1), set2: set(element2)
  def map(f, %{} = d) when is_function(f, 1) do
    :maps.from_keys((for {k, _} <- d do
      f.(k)
    end), [])
  end

  def map(f, set() = d) when is_function(f, 1) do
    fold(fn e, acc ->
        add_element(f.(e), acc)
    end, new([{:version, 1}]), d)
  end

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
  1> sets:to_list(sets:new()).
  []
  ```
  """
  @spec new() :: set(none())
  def new(), do: %{}

  @doc ~S"""
  Returns a new empty set of the given version.

  ## Examples

  ```erlang
  1> sets:to_list(sets:new([{version, 1}])).
  []
  2> sets:new() =:= sets:new([{version, 2}]).
  true
  ```
  """
  @spec new([{:version, 1..2}]) :: set(none())
  def new([{:version, 2}]), do: new()

  def new(opts) do
    case :proplists.get_value(:version, opts, 2) do
      1 ->
        empty = mk_seg(16)
        set(empty: empty, segs: {empty})
      2 ->
        new()
    end
  end

  @doc ~S"""
  Returns the number of elements in `Set`.

  ## Examples

  ```erlang
  1> sets:size(sets:new()).
  0
  2> sets:size(sets:from_list([4,5,6])).
  3
  ```
  """
  @spec size(set) :: non_neg_integer() when set: set()
  def size(%{} = s), do: map_size(s)

  def size(set(size: size)), do: size

  @doc ~S"""
  Returns a new set containing the elements of `Set1`
  that are not elements in `Set2`.

  ## Examples

  ```erlang
  1> S0 = sets:from_list([a,b,c,d]).
  2> S1 = sets:from_list([c,d,e,f]).
  3> lists:sort(sets:to_list(sets:subtract(S0, S1))).
  [a,b]
  4> lists:sort(sets:to_list(sets:subtract(S1, S0))).
  [e,f]
  ```
  """
  @spec subtract(set1, set2) :: set3 when set1: set(element), set2: set(element), set3: set(element)
  def subtract(%{} = lHS, %{} = rHS) do
    lSize = map_size(lHS)
    rSize = map_size(rHS)
    case rSize <= div(lSize, 4) do
      true ->
        next = :maps.next(:maps.iterator(rHS))
        subtract_decided(next, lHS, rHS)
      false ->
        keepThreshold = div(lSize * 3, 4)
        next = :maps.next(:maps.iterator(lHS))
        subtract_heuristic(next, [], [], keepThreshold, lHS, rHS)
    end
  end

  def subtract(lHS, rHS) do
    filter(fn e ->
        not is_element(e, rHS)
    end, lHS)
  end

  @doc ~S"""
  Returns the elements of `Set` as a list.

  The order of the returned elements is undefined.

  ## Examples

  ```erlang
  1> S = sets:from_list([1,2,3]).
  2> lists:sort(sets:to_list(S)).
  [1,2,3]
  ```
  """
  @spec to_list(set) :: list when set: set(element), list: [element]
  def to_list(%{} = s), do: :maps.keys(s)

  def to_list(set() = s) do
    fold(fn elem, list ->
        [elem | list]
    end, [], s)
  end

  @doc ~S"""
  Returns the union of a list of sets.

  The union of multiple sets is a new set that contains all the elements from
  all sets, without duplicates.

  ## Examples

  ```erlang
  1> S0 = sets:from_list([a,b,c,d]).
  2> S1 = sets:from_list([d,e,f]).
  3> S2 = sets:from_list([q,r]).
  4> Sets = [S0, S1, S2].
  5> Union = sets:union(Sets).
  6> lists:sort(sets:to_list(Union)).
  [a,b,c,d,e,f,q,r]
  ```
  """
  @spec union(setList) :: set when setList: [set(element)], set: set(element)
  def union([s1, s2 | ss]), do: union1(union(s1, s2), ss)

  def union([s]), do: s

  def union([]), do: new()

  @doc ~S"""
  Returns the union of `Set1` and `Set2`.

  The union of two sets is a new set that contains all the elements from
  both sets, without duplicates.

  ## Examples

  ```erlang
  1> S0 = sets:from_list([a,b,c,d]).
  2> S1 = sets:from_list([c,d,e,f]).
  3> Union = sets:union(S0, S1).
  4> lists:sort(sets:to_list(Union)).
  [a,b,c,d,e,f]
  ```
  """
  @spec union(set1, set2) :: set3 when set1: set(element), set2: set(element), set3: set(element)
  def union(%{} = s1, %{} = s2), do: :maps.merge(s1, s2)

  def union(s1, s2) do
    case size(s1) < size(s2) do
      true ->
        fold(fn e, s ->
            add_element(e, s)
        end, s2, s1)
      false ->
        fold(fn e, s ->
            add_element(e, s)
        end, s1, s2)
    end
  end

  # Private Functions

  defp unquote(:"-filter/2-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-filtermap/2-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-from_list/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-intersection/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-intersection/2-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-is_disjoint/2-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-is_disjoint/2-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-is_disjoint/2-inlined-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-is_disjoint/2-inlined-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-is_subset/2-fun-0-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-map/2-fun-1-")(arg1, arg2, arg3) do
    # body not decompiled
  end

  defp unquote(:"-map/2-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-subtract/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-to_list/1-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-union/2-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-union/2-fun-1-")(arg1, arg2) do
    # body not decompiled
  end

  defp canonicalize_v2(s), do: from_list(to_list(s))

  @spec contract_segs(segs(e)) :: segs(e)
  defp contract_segs({b1, _}), do: {b1}

  defp contract_segs({b1, b2, _, _}), do: {b1, b2}

  defp contract_segs({b1, b2, b3, b4, _, _, _, _}), do: {b1, b2, b3, b4}

  defp contract_segs({b1, b2, b3, b4, b5, b6, b7, b8, _, _, _, _, _, _, _, _}), do: {b1, b2, b3, b4, b5, b6, b7, b8}

  defp contract_segs({b1, b2, b3, b4, b5, b6, b7, b8, b9, b10, b11, b12, b13, b14, b15, b16, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _}), do: {b1, b2, b3, b4, b5, b6, b7, b8, b9, b10, b11, b12, b13, b14, b15, b16}

  defp contract_segs(segs) do
    ss = div(tuple_size(segs), 2)
    list_to_tuple(:lists.sublist(tuple_to_list(segs), 1, ss))
  end

  @spec expand_segs(segs(e), seg()) :: segs(e)
  defp expand_segs({b1}, empty), do: {b1, empty}

  defp expand_segs({b1, b2}, empty), do: {b1, b2, empty, empty}

  defp expand_segs({b1, b2, b3, b4}, empty), do: {b1, b2, b3, b4, empty, empty, empty, empty}

  defp expand_segs({b1, b2, b3, b4, b5, b6, b7, b8}, empty), do: {b1, b2, b3, b4, b5, b6, b7, b8, empty, empty, empty, empty, empty, empty, empty, empty}

  defp expand_segs({b1, b2, b3, b4, b5, b6, b7, b8, b9, b10, b11, b12, b13, b14, b15, b16}, empty), do: {b1, b2, b3, b4, b5, b6, b7, b8, b9, b10, b11, b12, b13, b14, b15, b16, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty, empty}

  defp expand_segs(segs, empty), do: list_to_tuple(tuple_to_list(segs) ++ :lists.duplicate(tuple_size(segs), empty))

  defp filter_bkt_list(f, [bkt0 | bkts], fbs, fc0) do
    {bkt1, fc1} = filter_bucket(f, bkt0, [], fc0)
    filter_bkt_list(f, bkts, [bkt1 | fbs], fc1)
  end

  defp filter_bkt_list(_, [], fbs, fc), do: {:lists.reverse(fbs), fc}

  defp filter_bucket(f, [e | bkt], fb, fc) do
    case f.(e) do
      true ->
        filter_bucket(f, bkt, [e | fb], fc)
      false ->
        filter_bucket(f, bkt, fb, fc + 1)
    end
  end

  defp filter_bucket(_, [], fb, fc), do: {fb, fc}

  defp filter_seg_list(f, [seg | segs], fss, fc0) do
    bkts0 = tuple_to_list(seg)
    {bkts1, fc1} = filter_bkt_list(f, bkts0, [], fc0)
    filter_seg_list(f, segs, [list_to_tuple(bkts1) | fss], fc1)
  end

  defp filter_seg_list(_, [], fss, fc), do: {:lists.reverse(fss, []), fc}

  defp filter_set(f, d) do
    segs0 = tuple_to_list(set(d, :segs))
    {segs1, fc} = filter_seg_list(f, segs0, [], 0)
    maybe_contract(set(d, segs: list_to_tuple(segs1)), fc)
  end

  defp fold_1(fun, acc, iter) do
    case :maps.next(iter) do
      {k, _, nextIter} ->
        fold_1(fun, fun.(k, acc), nextIter)
      :none ->
        acc
    end
  end

  defp fold_bucket(f, acc, [e | bkt]), do: fold_bucket(f, f.(e, acc), bkt)

  defp fold_bucket(_, acc, []), do: acc

  defp fold_seg(f, acc, seg, i) when i >= 1, do: fold_seg(f, fold_bucket(f, acc, element(i, seg)), seg, i - 1)

  defp fold_seg(_, acc, _, _), do: acc

  defp fold_segs(f, acc, segs, i) when i >= 1 do
    seg = element(i, segs)
    fold_segs(f, fold_seg(f, acc, seg, tuple_size(seg)), segs, i - 1)
  end

  defp fold_segs(_, acc, _, _), do: acc

  defp fold_set(f, acc, d) do
    segs = set(d, :segs)
    fold_segs(f, acc, segs, tuple_size(segs))
  end

  @spec get_bucket(set_v1(any()), non_neg_integer()) :: term()
  defp get_bucket(t, slot), do: get_bucket_s(set(t, :segs), slot)

  defp get_bucket_s(segs, slot) do
    segI = div(slot - 1, 16) + 1
    bktI = rem(slot - 1, 16) + 1
    element(bktI, element(segI, segs))
  end

  @spec get_slot(set_v1(e), e) :: non_neg_integer()
  defp get_slot(t, key) do
    h = :erlang.phash(key, set(t, :maxn))
    cond do
      h > set(t, :n) ->
        h - set(t, :bso)
      true ->
        h
    end
  end

  @spec intersection1(set(e), [set(e)]) :: set(e)
  defp intersection1(s1, [s2 | ss]), do: intersection1(intersection(s1, s2), ss)

  defp intersection1(s1, []), do: s1

  defp intersection_decided({key, _Value, iterator}, acc0, reference) do
    acc1 = case reference do
      %{key => _} ->
        acc0
      %{} ->
        :maps.remove(key, acc0)
    end
    intersection_decided(:maps.next(iterator), acc1, reference)
  end

  defp intersection_decided(:none, acc, _Reference), do: acc

  defp intersection_heuristic(next, _Keep, delete, 0, acc, reference), do: intersection_decided(next, remove_keys(delete, acc), reference)

  defp intersection_heuristic({key, _Value, iterator}, keep, delete, keepCount, acc, reference) do
    next = :maps.next(iterator)
    case reference do
      %{key => _} ->
        intersection_heuristic(next, [key | keep], delete, keepCount - 1, acc, reference)
      %{} ->
        intersection_heuristic(next, keep, [key | delete], keepCount, acc, reference)
    end
  end

  defp intersection_heuristic(:none, keep, _Delete, _Count, _Acc, _Reference), do: :maps.from_keys(keep, [])

  defp is_disjoint_1(set, iter) do
    case :maps.next(iter) do
      {k, _, nextIter} ->
        case set do
          %{k => _} ->
            false
          %{} ->
            is_disjoint_1(set, nextIter)
        end
      :none ->
        true
    end
  end

  defp is_subset_1(set, iter) do
    case :maps.next(iter) do
      {k, _, nextIter} ->
        case set do
          %{k => _} ->
            is_subset_1(set, nextIter)
          %{} ->
            false
        end
      :none ->
        true
    end
  end

  @spec maybe_contract(set_v1(e), non_neg_integer()) :: set(e)
  defp maybe_contract(t, dc) when set(t, :size) - dc < set(t, :con_size) and set(t, :n) > 16 do
    n = set(t, :n)
    slot1 = n - set(t, :bso)
    segs0 = set(t, :segs)
    b1 = get_bucket_s(segs0, slot1)
    slot2 = n
    b2 = get_bucket_s(segs0, slot2)
    segs1 = put_bucket_s(segs0, slot1, b1 ++ b2)
    segs2 = put_bucket_s(segs1, slot2, [])
    n1 = n - 1
    maybe_contract_segs(set(t, size: set(t, :size) - dc, n: n1, exp_size: n1 * 5, con_size: n1 * 3, segs: segs2))
  end

  defp maybe_contract(t, dc), do: set(t, size: set(t, :size) - dc)

  @spec maybe_contract_segs(set(e)) :: set(e)
  defp maybe_contract_segs(t) when set(t, :n) === set(t, :bso), do: set(t, maxn: div(set(t, :maxn), 2), bso: div(set(t, :bso), 2), segs: contract_segs(set(t, :segs)))

  defp maybe_contract_segs(t), do: t

  @spec maybe_expand(set_v1(e)) :: set_v1(e)
  defp maybe_expand(t0) when set(t0, :size) + 1 > set(t0, :exp_size) do
    t = maybe_expand_segs(t0)
    n = set(t, :n) + 1
    segs0 = set(t, :segs)
    slot1 = n - set(t, :bso)
    b = get_bucket_s(segs0, slot1)
    slot2 = n
    {b1, b2} = rehash(b, slot1, slot2, set(t, :maxn))
    segs1 = put_bucket_s(segs0, slot1, b1)
    segs2 = put_bucket_s(segs1, slot2, b2)
    set(t, size: set(t, :size) + 1, n: n, exp_size: n * 5, con_size: n * 3, segs: segs2)
  end

  defp maybe_expand(t), do: set(t, size: set(t, :size) + 1)

  @spec maybe_expand_segs(set_v1(e)) :: set_v1(e)
  defp maybe_expand_segs(t) when set(t, :n) === set(t, :maxn), do: set(t, maxn: 2 * set(t, :maxn), bso: 2 * set(t, :bso), segs: expand_segs(set(t, :segs), set(t, :empty)))

  defp maybe_expand_segs(t), do: t

  @spec mk_seg(16) :: seg()
  defp mk_seg(16), do: {[], [], [], [], [], [], [], [], [], [], [], [], [], [], [], []}

  defp put_bucket_s(segs, slot, bkt) do
    segI = div(slot - 1, 16) + 1
    bktI = rem(slot - 1, 16) + 1
    seg = setelement(bktI, element(segI, segs), bkt)
    setelement(segI, segs, seg)
  end

  @spec rehash([t], integer(), pos_integer(), pos_integer()) :: {[t], [t]}
  defp rehash([e | t], slot1, slot2, maxN) do
    {l1, l2} = rehash(t, slot1, slot2, maxN)
    case :erlang.phash(e, maxN) do
      slot1 ->
        {[e | l1], l2}
      slot2 ->
        {l1, [e | l2]}
    end
  end

  defp rehash([], _, _, _), do: {[], []}

  defp remove_keys([k | ks], map), do: remove_keys(ks, :maps.remove(k, map))

  defp remove_keys([], map), do: map

  defp subtract_decided({key, _Value, iterator}, acc, reference) do
    case reference do
      %{key => _} ->
        subtract_decided(:maps.next(iterator), :maps.remove(key, acc), reference)
      _ ->
        subtract_decided(:maps.next(iterator), acc, reference)
    end
  end

  defp subtract_decided(:none, acc, _Reference), do: acc

  defp subtract_heuristic(next, _Keep, delete, 0, acc, reference), do: subtract_decided(next, remove_keys(delete, acc), reference)

  defp subtract_heuristic({key, _Value, iterator}, keep, delete, keepCount, acc, reference) do
    next = :maps.next(iterator)
    case reference do
      %{key => _} ->
        subtract_heuristic(next, keep, [key | delete], keepCount, acc, reference)
      _ ->
        subtract_heuristic(next, [key | keep], delete, keepCount - 1, acc, reference)
    end
  end

  defp subtract_heuristic(:none, keep, _Delete, _Count, _Acc, _Reference), do: :maps.from_keys(keep, [])

  @spec union1(set(e), [set(e)]) :: set(e)
  defp union1(s1, [s2 | ss]), do: union1(union(s1, s2), ss)

  defp union1(s1, []), do: s1

  @spec update_bucket(set1, slot, bkt) :: set2 when set1: set_v1(element), set2: set_v1(element), slot: non_neg_integer(), bkt: [element]
  defp update_bucket(set, slot, newBucket) do
    segI = div(slot - 1, 16) + 1
    bktI = rem(slot - 1, 16) + 1
    segs = set(set, :segs)
    seg = element(segI, segs)
    set(set, segs: setelement(segI, segs, setelement(bktI, seg, newBucket)))
  end
end
