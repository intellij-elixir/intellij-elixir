# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :gb_trees do
  @moduledoc ~S"""
  General balanced trees.

  This module provides Prof. Arne Andersson's General Balanced Trees. These have
  no storage overhead compared to unbalanced binary trees, and their performance
  is better than AVL trees.

  This module considers two keys as different if and only if they do not compare
  equal (`==`).

  ## Data Structure

  Trees and iterators are built using opaque data structures that should not be
  pattern-matched from outside this module.

  There is no attempt to balance trees after deletions. As deletions do not
  increase the height of a tree, this should be OK.

  The original balance condition `h(T) <= ceil(c * log(|T|))` has been changed to
  the similar (but not quite equivalent) condition `2 ^ h(T) <= |T| ^ c`. This
  should also be OK.

  ### See Also

  `m:dict`, `m:gb_sets`, `m:maps`
  """

  # Types

  @type iter :: iter(any(), any())

  @typedoc ~S"""
  A general balanced tree iterator.
  """
  @opaque iter(key, value) :: {(:ordered | :reversed), [gb_tree_node(key, value)]}

  @type tree :: tree(any(), any())

  @typedoc ~S"""
  A general balanced tree.
  """
  @opaque tree(key, value) :: {non_neg_integer(), gb_tree_node(key, value)}

  # Private Types

  @typep gb_tree_node(k, v) :: (nil | {k, v, gb_tree_node(k, v), gb_tree_node(k, v)})

  # Functions

  @doc ~S"""
  Rebalances `Tree1`.

  Note that this is rarely necessary, but can be motivated
  when many nodes have been deleted from the tree without further insertions.
  Rebalancing can then be forced to minimize lookup times, as deletion does not
  rebalance the tree.

  ## Examples

  ```erlang
  1> Tree1 = gb_trees:from_list([{I,2*I} || I <- lists:seq(1, 100)]).
  2> Delete = fun gb_trees:delete/2.
  3> Tree2 = lists:foldl(Delete, Tree1, lists:seq(1, 50)).
  4> gb_trees:size(Tree2).
  50
  5> Tree3 = gb_trees:balance(Tree2).
  ```
  """
  @spec balance(tree1) :: tree2 when tree1: tree(key, value), tree2: tree(key, value)
  def balance({s, t}) when is_integer(s) and s >= 0, do: {s, balance(t, s)}

  @doc ~S"""
  Removes the node with key `Key` from `Tree1`, returning the new tree;
  raises an exception if `Key` is not present.

  ## Examples

  ```erlang
  1> Tree1 = gb_trees:from_list([{a,1},{b,2}]).
  2> Tree2 = gb_trees:delete(a, Tree1).
  3> gb_trees:to_list(Tree2).
  [{b,2}]
  ```
  """
  @spec delete(key, tree1) :: tree2 when tree1: tree(key, value), tree2: tree(key, value)
  def delete(key, {s, t}) when is_integer(s) and s >= 0, do: {s - 1, delete_1(key, t)}

  @doc ~S"""
  Removes the node with key `Key` from `Tree1` if present and returns the
  resulting tree; otherwise, returns `Tree1` unchanged.

  ## Examples

  ```erlang
  1> Tree1 = gb_trees:from_list([{a,1},{b,2}]).
  2> Tree2 = gb_trees:delete_any(a, Tree1).
  3> gb_trees:to_list(Tree2).
  [{b,2}]
  4> Tree3 = gb_trees:delete_any(z, Tree2).
  5> Tree2 == Tree3.
  true
  ```
  """
  @spec delete_any(key, tree1) :: tree2 when tree1: tree(key, value), tree2: tree(key, value)
  def delete_any(key, t) do
    case is_defined(key, t) do
      true ->
        delete(key, t)
      false ->
        t
    end
  end

  @doc ~S"""
  Returns a new empty tree.

  ## Examples

  ```erlang
  1> gb_trees:to_list(gb_trees:empty()).
  []
  ```
  """
  @spec empty() :: tree(none(), none())
  def empty(), do: {0, nil}

  @doc ~S"""
  Inserts `Key` with value `Value` into `Tree1` if not present, or
  updates the value for `Key` to `Value` if present; returns the new
  tree.

  ## Examples

  ```erlang
  1> Tree1 = gb_trees:from_list([{a,1},{b,2}]).
  2> Tree2 = gb_trees:enter(c, 10, Tree1).
  3> Tree3 = gb_trees:enter(a, 100, Tree2).
  4> gb_trees:to_list(Tree3).
  [{a,100},{b,2},{c,10}]
  ```
  """
  @spec enter(key, value, tree1) :: tree2 when tree1: tree(key, value), tree2: tree(key, value)
  def enter(key, val, t) do
    case is_defined(key, t) do
      true ->
        update(key, val, t)
      false ->
        insert(key, val, t)
    end
  end

  @doc ~S"""
  Returns a tree of the key-value tuples in `List`,
  where `List` can be unordered and contain duplicate keys.

  ## Examples

  ```erlang
  1> Unordered = [{x, 1}, {y, 2}, {a, 3}, {x, 4}, {y, 5}, {b, 6}].
  2> gb_trees:to_list(gb_trees:from_list(Unordered)).
  [{a,3},{b,6},{x,4},{y,5}]
  ```
  """
  @spec from_list(list) :: tree when list: [{key, value}], tree: tree(key, value)
  def from_list(l), do: from_orddict_unchecked(:orddict.from_list(l))

  @doc ~S"""
  Turns an ordered list `List` of key-value tuples into a tree.

  The list must not contain duplicate keys.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_orddict([{a,1},{b,2}]).
  2> gb_trees:to_list(Tree).
  [{a,1},{b,2}]
  ```
  """
  @spec from_orddict(list) :: tree when list: [{key, value}], tree: tree(key, value)
  def from_orddict(l) do
    s = length(l)
    {s, balance_list_checked(l, s)}
  end

  @doc ~S"""
  Retrieves the value stored with `Key` in `Tree`; raises an exception
  if `Key` is not present.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2}]).
  2> gb_trees:get(b, Tree).
  2
  ```
  """
  @spec get(key, tree) :: value when tree: tree(key, value)
  def get(key, {_, t}), do: get_1(key, t)

  @doc ~S"""
  Inserts `Key` with value `Value` into `Tree1`, returning the new
  tree; raises an exception if `Key` is already present.

  ## Examples

  ```erlang
  1> Tree1 = gb_trees:from_list([{a,1},{b,2}]).
  2> Tree2 = gb_trees:insert(c, 10, Tree1).
  3> gb_trees:to_list(Tree2).
  [{a,1},{b,2},{c,10}]
  ```
  """
  @spec insert(key, value, tree1) :: tree2 when tree1: tree(key, value), tree2: tree(key, value)
  def insert(key, val, {s, t}) when is_integer(s) do
    s1 = s + 1
    {s1, insert_1(key, val, t, s1 * s1)}
  end

  @doc ~S"""
  Returns `true` if `Key` is present in `Tree`; otherwise, returns
  `false`.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> gb_trees:is_defined(a, Tree).
  true
  3> gb_trees:is_defined(x, Tree).
  false
  ```
  """
  @spec is_defined(key, tree) :: boolean() when tree: tree(key, value :: term())
  def is_defined(key, {_, t}), do: is_defined_1(key, t)

  @doc ~S"""
  Returns `true` if `Tree` is an empty tree; otherwise, returns `false`.

  ## Examples

  ```erlang
  1> gb_trees:is_empty(gb_trees:empty()).
  true
  2> gb_trees:is_empty(gb_trees:from_list([{a,99}])).
  false
  ```
  """
  @spec is_empty(tree) :: boolean() when tree: tree()
  def is_empty({0, nil}), do: true

  def is_empty(_), do: false

  @spec iterator(tree) :: iter when tree: tree(key, value), iter: iter(key, value)
  def iterator(tree), do: iterator(tree, :ordered)

  @doc ~S"""
  Returns an iterator that can be used for traversing the entries of `Tree` in
  either `ordered` or `reversed` direction; see `next/1`.

  The implementation is very efficient; traversing the whole tree using
  [`next/1`](`next/1`) is only slightly slower than getting the list of all
  elements using `to_list/1` and traversing that. The main advantage of the
  iterator approach is that it avoids building the complete list of all elements
  in memory at once.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> Iter0 = gb_trees:iterator(Tree, ordered).
  3> {a,1,Iter1} = gb_trees:next(Iter0).
  4> RevIter0 = gb_trees:iterator(Tree, reversed).
  5> {c,3,RevIter1} = gb_trees:next(RevIter0).
  ```
  """
  @spec iterator(tree, order) :: iter when tree: tree(key, value), iter: iter(key, value), order: (:ordered | :reversed)
  def iterator({_, t}, :ordered), do: {:ordered, iterator_1(t, [])}

  def iterator({_, t}, :reversed), do: {:reversed, iterator_r(t, [])}

  @spec iterator_from(key, tree) :: iter when tree: tree(key, value), iter: iter(key, value)
  def iterator_from(key, tree), do: iterator_from(key, tree, :ordered)

  @doc ~S"""
  Returns an iterator over the entries of `Tree` in the given `Order`, starting
  from `Key` or, if absent, the first key that follows in the iteration order,
  if any; see `next/1`.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3},{d,4}]).
  2> Iter0 = gb_trees:iterator_from(aa, Tree, ordered).
  3> {b,2,Iter1} = gb_trees:next(Iter0).
  4> RevIter0 = gb_trees:iterator_from(c, Tree, reversed).
  5> {c,3,RevIter1} = gb_trees:next(RevIter0).
  6> {b,2,RevIter2} = gb_trees:next(RevIter1).
  ```
  """
  @spec iterator_from(key, tree, order) :: iter when tree: tree(key, value), iter: iter(key, value), order: (:ordered | :reversed)
  def iterator_from(s, {_, t}, :ordered), do: {:ordered, iterator_from_1(s, t, [])}

  def iterator_from(s, {_, t}, :reversed), do: {:reversed, iterator_from_r(s, t, [])}

  @doc ~S"""
  Returns the keys in `Tree` as an ordered list.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> gb_trees:keys(Tree).
  [a,b,c]
  3> gb_trees:keys(gb_trees:empty()).
  []
  ```
  """
  @spec keys(tree) :: [key] when tree: tree(key, value :: term())
  def keys({_, t}), do: keys(t, [])

  @doc ~S"""
  Returns `{Key2, Value}`, where `Key2` is the least key strictly greater than
  `Key1`, and `Value` is the value associated with this key.

  Returns `none` if no such pair exists.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> gb_trees:larger(c, Tree).
  none
  3> gb_trees:larger(bb, Tree).
  {c,3}
  4> gb_trees:larger(a, Tree).
  {b,2}
  ```
  """
  @spec larger(key1, tree) :: (:none | {key2, value}) when key1: key, key2: key, tree: tree(key, value)
  def larger(key, {_, treeNode}), do: larger_1(key, treeNode)

  @doc ~S"""
  Returns `{Key, Value}`, where `Key` is the largest key in `Tree`, and `Value` is
  the value associated with this key.

  Assumes that the tree is not empty.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> gb_trees:largest(Tree).
  {c,3}
  ```
  """
  @spec largest(tree) :: {key, value} when tree: tree(key, value)
  def largest({_, tree}), do: largest_1(tree)

  @doc ~S"""
  Looks up `Key` in `Tree` and returns `{value, Value}` if found, or `none` if not present.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> gb_trees:lookup(a, Tree).
  {value,1}
  3> gb_trees:lookup(z, Tree).
  none
  ```
  """
  @spec lookup(key, tree) :: (:none | {:value, value}) when tree: tree(key, value)
  def lookup(key, {_, t}), do: lookup_1(key, t)

  @doc ~S"""
  Maps `Function(K, V1) -> V2` to all key-value pairs of tree `Tree1`,
  returning a new tree `Tree2` with the same set of keys as
  `Tree1` and the new set of values `V2`.

  ## Examples

  ```erlang
  1> Tree0 = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> Tree1 = gb_trees:map(fun(_, V) -> 2 * V end, Tree0).
  3> gb_trees:to_list(Tree1).
  [{a,2},{b,4},{c,6}]
  ```
  """
  @spec map(function, tree1) :: tree2 when function: (k :: key, v1 :: value1 -> v2 :: value2), tree1: tree(key, value1), tree2: tree(key, value2)
  def map(f, {size, tree}) when is_function(f, 2), do: {size, map_1(f, tree)}

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @doc ~S"""
  Returns `{Key, Value, Iter2}`, where `Key` is the next key referred to by
  iterator `Iter1`, and `Iter2` is the new iterator to be used for traversing the
  remaining nodes, or the atom `none` if no nodes remain.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> Iter0 = gb_trees:iterator(Tree).
  3> {a,1,Iter1} = gb_trees:next(Iter0).
  4> {b,2,Iter2} = gb_trees:next(Iter1).
  5> {c,3,Iter3} = gb_trees:next(Iter2).
  6> none = gb_trees:next(Iter3).
  ```
  """
  @spec next(iter1) :: (:none | {key, value, iter2}) when iter1: iter(key, value), iter2: iter(key, value)
  def next({:ordered, [{x, v, _, t} | as]}), do: {x, v, {:ordered, iterator_1(t, as)}}

  def next({:reversed, [{x, v, t, _} | as]}), do: {x, v, {:reversed, iterator_r(t, as)}}

  def next({_, []}), do: :none

  @doc ~S"""
  Returns the number of nodes in `Tree`.

  ## Examples

  ```erlang
  1> gb_trees:size(gb_trees:empty()).
  0
  2> gb_trees:size(gb_trees:from_list([{a,1},{b,2}])).
  2
  ```
  """
  @spec size(tree) :: non_neg_integer() when tree: tree()
  def size({size, _}) when is_integer(size) and size >= 0, do: size

  @doc ~S"""
  Returns `{Key2, Value}`, where `Key2` is the greatest key strictly less than
  `Key1`, and `Value` is the value associated with this key.

  Returns `none` if no such pair exists.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> gb_trees:smaller(c, Tree).
  {b,2}
  3> gb_trees:smaller(bb, Tree).
  {b,2}
  4> gb_trees:smaller(a, Tree).
  none
  ```
  """
  @spec smaller(key1, tree) :: (:none | {key2, value}) when key1: key, key2: key, tree: tree(key, value)
  def smaller(key, {_, treeNode}), do: smaller_1(key, treeNode)

  @doc ~S"""
  Returns `{Key, Value}`, where `Key` is the smallest key in `Tree`, and `Value`
  is the value associated with this key.

  Assumes that the tree is not empty.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> gb_trees:smallest(Tree).
  {a,1}
  ```
  """
  @spec smallest(tree) :: {key, value} when tree: tree(key, value)
  def smallest({_, tree}), do: smallest_1(tree)

  @doc ~S"""
  Returns a value `Value` from the node with key `Key` and a new tree `Tree2`
  with that node removed.

  Assumes that `Key` is present in the tree.

  ## Examples

  ```erlang
  1> Tree0 = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> {Value,Tree1} = gb_trees:take(b, Tree0).
  3> Value.
  2
  4> gb_trees:to_list(Tree1).
  [{a,1},{c,3}]
  ```
  """
  @spec take(key, tree1) :: {value, tree2} when tree1: tree(key, any()), tree2: tree(key, any()), key: term(), value: term()
  def take(key, {s, t}) when is_integer(s) and s >= 0 do
    {value, res} = take_1(key, t)
    {value, {s - 1, res}}
  end

  @doc ~S"""
  Returns a value `Value` from the node with key `Key` and a new tree `Tree2`
  with that node removed; returns `error` if `Key` is not present in `Tree1`.

  ## Examples

  ```erlang
  1> Tree0 = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> {Value,Tree1} = gb_trees:take_any(b, Tree0).
  3> Value.
  2
  4> gb_trees:to_list(Tree1).
  [{a,1},{c,3}]
  5> gb_trees:take_any(x, Tree0).
  error
  ```
  """
  @spec take_any(key, tree1) :: ({value, tree2} | :error) when tree1: tree(key, any()), tree2: tree(key, any()), key: term(), value: term()
  def take_any(key, tree) do
    case is_defined(key, tree) do
      true ->
        take(key, tree)
      false ->
        :error
    end
  end

  @doc ~S"""
  Returns `{Key, Value, Tree2}`, where `Key` is the largest key in
  `Tree1`, `Value` is the value associated with this key, and `Tree2` is
  this tree with the corresponding node deleted.

  Assumes that the tree is not empty.

  ## Examples

  ```erlang
  1> Tree0 = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> {Key,Value,Tree1} = gb_trees:take_largest(Tree0).
  3> Key.
  c
  4> Value.
  3
  5> gb_trees:to_list(Tree1).
  [{a,1},{b,2}]
  ```
  """
  @spec take_largest(tree1) :: {key, value, tree2} when tree1: tree(key, value), tree2: tree(key, value)
  def take_largest({size, tree}) when is_integer(size) and size >= 0 do
    {key, value, smaller} = take_largest1(tree)
    {key, value, {size - 1, smaller}}
  end

  @doc ~S"""
  Returns `{Key, Value, Tree2}`, where `Key` is the smallest key in `Tree1`,
  `Value` is the value associated with that key, and `Tree2` is the tree
  with the corresponding node removed.

  Assumes that the tree is not empty.

  ## Examples

  ```erlang
  1> Tree0 = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> {Key,Value,Tree1} = gb_trees:take_smallest(Tree0).
  3> Key.
  a
  4> Value.
  1
  5> gb_trees:to_list(Tree1).
  [{b,2},{c,3}]
  ```
  """
  @spec take_smallest(tree1) :: {key, value, tree2} when tree1: tree(key, value), tree2: tree(key, value)
  def take_smallest({size, tree}) when is_integer(size) and size >= 0 do
    {key, value, larger} = take_smallest1(tree)
    {key, value, {size - 1, larger}}
  end

  @doc ~S"""
  Converts a tree into an ordered list of key-value tuples.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3}]).
  2> gb_trees:to_list(Tree).
  [{a,1},{b,2},{c,3}]
  3> gb_trees:to_list(gb_trees:empty()).
  []
  ```
  """
  @spec to_list(tree) :: [{key, value}] when tree: tree(key, value)
  def to_list({_, t}), do: to_list(t, [])

  @doc ~S"""
  Updates `Key` to value `Value` in `Tree1` and returns the new tree.

  Assumes that the key is present in the tree.

  ## Examples

  ```erlang
  1> Tree1 = gb_trees:from_list([{a,1},{b,2}]).
  2> Tree2 = gb_trees:update(a, 99, Tree1).
  3> gb_trees:to_list(Tree2).
  [{a,99},{b,2}]
  ```
  """
  @spec update(key, value, tree1) :: tree2 when tree1: tree(key, value), tree2: tree(key, value)
  def update(key, val, {s, t}) do
    t1 = update_1(key, val, t)
    {s, t1}
  end

  @doc ~S"""
  Returns the values in `Tree` as an ordered list, sorted by their
  corresponding keys.

  Duplicates are not removed.

  ## Examples

  ```erlang
  1> Tree = gb_trees:from_list([{a,1},{b,2},{c,3},{d,1}]).
  2> gb_trees:values(Tree).
  [1,2,3,1]
  ```
  """
  @spec values(tree) :: [value] when tree: tree(key :: term(), value)
  def values({_, t}), do: values(t, [])

  # Private Functions

  defp balance(t, s), do: balance_list_unchecked(to_list_1(t), s)

  defp balance_list_checked(l, s) do
    {t, []} = balance_list_checked_1(l, s)
    t
  end

  defp balance_list_checked_1(l, s) when s > 1 do
    {s1, s2} = split_list_size(s)
    {t1, [{k, v} | l1]} = balance_list_checked_1(l, s1)
    case l1 do
      [{k1, _} | _] when k >= k1 ->
        :erlang.error({:badarg, :not_orddict})
      _ ->
        {t2, l2} = balance_list_checked_1(l1, s2)
        t = {k, v, t1, t2}
        {t, l2}
    end
  end

  defp balance_list_checked_1([{k1, _}, {k2, _} | _], 1) when k1 >= k2, do: :erlang.error({:badarg, :not_orddict})

  defp balance_list_checked_1([{key, val} | l], 1), do: {{key, val, nil, nil}, l}

  defp balance_list_checked_1(l, 0), do: {nil, l}

  defp balance_list_unchecked(l, s) do
    {t, []} = balance_list_unchecked_1(l, s)
    t
  end

  defp balance_list_unchecked_1(l, s) when s > 1 do
    {s1, s2} = split_list_size(s)
    {t1, [{k, v} | l1]} = balance_list_unchecked_1(l, s1)
    {t2, l2} = balance_list_unchecked_1(l1, s2)
    t = {k, v, t1, t2}
    {t, l2}
  end

  defp balance_list_unchecked_1([{key, val} | l], 1), do: {{key, val, nil, nil}, l}

  defp balance_list_unchecked_1(l, 0), do: {nil, l}

  defp count({_, _, nil, nil}), do: {1, 1}

  defp count({_, _, sm, bi}) do
    {h1, s1} = count(sm)
    {h2, s2} = count(bi)
    {:erlang.max(h1, h2) <<< 1, s1 + s2 + 1}
  end

  defp count(nil), do: {1, 0}

  defp delete_1(key, {key1, value, smaller, larger}) when key < key1 do
    smaller1 = delete_1(key, smaller)
    {key1, value, smaller1, larger}
  end

  defp delete_1(key, {key1, value, smaller, bigger}) when key > key1 do
    bigger1 = delete_1(key, bigger)
    {key1, value, smaller, bigger1}
  end

  defp delete_1(_, {_, _, smaller, larger}), do: merge(smaller, larger)

  defp from_orddict_unchecked(l) do
    s = length(l)
    {s, balance_list_unchecked(l, s)}
  end

  defp get_1(key, {key1, _, smaller, _}) when key < key1, do: get_1(key, smaller)

  defp get_1(key, {key1, _, _, bigger}) when key > key1, do: get_1(key, bigger)

  defp get_1(_, {_, value, _, _}), do: value

  defp insert_1(key, value, {key1, v, smaller, bigger}, s) when key < key1 do
    case insert_1(key, value, smaller, s >>> 1) do
      {t1, h1, s1} when is_integer(h1) and is_integer(s1) ->
        t = {key1, v, t1, bigger}
        {h2, s2} = count(bigger)
        h = :erlang.max(h1, h2) <<< 1
        sS = s1 + s2 + 1
        p = sS * sS
        cond do
          h > p ->
            balance(t, sS)
          true ->
            {t, h, sS}
        end
      t1 ->
        {key1, v, t1, bigger}
    end
  end

  defp insert_1(key, value, {key1, v, smaller, bigger}, s) when key > key1 do
    case insert_1(key, value, bigger, s >>> 1) do
      {t1, h1, s1} when is_integer(h1) and is_integer(s1) ->
        t = {key1, v, smaller, t1}
        {h2, s2} = count(smaller)
        h = :erlang.max(h1, h2) <<< 1
        sS = s1 + s2 + 1
        p = sS * sS
        cond do
          h > p ->
            balance(t, sS)
          true ->
            {t, h, sS}
        end
      t1 ->
        {key1, v, smaller, t1}
    end
  end

  defp insert_1(key, value, nil, s) when s === 0, do: {{key, value, nil, nil}, 1, 1}

  defp insert_1(key, value, nil, _S), do: {key, value, nil, nil}

  defp insert_1(key, _, _, _), do: :erlang.error({:key_exists, key})

  defp is_defined_1(key, {key1, _, smaller, _}) when key < key1, do: is_defined_1(key, smaller)

  defp is_defined_1(key, {key1, _, _, bigger}) when key > key1, do: is_defined_1(key, bigger)

  defp is_defined_1(_, {_, _, _, _}), do: true

  defp is_defined_1(_, nil), do: false

  defp iterator_1({_, _, nil, _} = t, as), do: [t | as]

  defp iterator_1({_, _, l, _} = t, as), do: iterator_1(l, [t | as])

  defp iterator_1(nil, as), do: as

  defp iterator_from_1(s, {k, _, _, t}, as) when k < s, do: iterator_from_1(s, t, as)

  defp iterator_from_1(_, {_, _, nil, _} = t, as), do: [t | as]

  defp iterator_from_1(s, {_, _, l, _} = t, as), do: iterator_from_1(s, l, [t | as])

  defp iterator_from_1(_, nil, as), do: as

  defp iterator_from_r(s, {k, _, t, _}, as) when k > s, do: iterator_from_r(s, t, as)

  defp iterator_from_r(_, {_, _, _, nil} = t, as), do: [t | as]

  defp iterator_from_r(s, {_, _, _, r} = t, as), do: iterator_from_r(s, r, [t | as])

  defp iterator_from_r(_, nil, as), do: as

  defp iterator_r({_, _, _, nil} = t, as), do: [t | as]

  defp iterator_r({_, _, _, r} = t, as), do: iterator_r(r, [t | as])

  defp iterator_r(nil, as), do: as

  defp keys({key, _Value, small, big}, l), do: keys(small, [key | keys(big, l)])

  defp keys(nil, l), do: l

  defp larger_1(_Key, nil), do: :none

  defp larger_1(key, {key1, value, smaller, _Larger}) when key < key1 do
    case larger_1(key, smaller) do
      :none ->
        {key1, value}
      entry ->
        entry
    end
  end

  defp larger_1(key, {_Key, _Value, _Smaller, larger}), do: larger_1(key, larger)

  defp largest_1({key, value, _Smaller, nil}), do: {key, value}

  defp largest_1({_Key, _Value, _Smaller, larger}), do: largest_1(larger)

  defp lookup_1(key, {key1, _, smaller, _}) when key < key1, do: lookup_1(key, smaller)

  defp lookup_1(key, {key1, _, _, bigger}) when key > key1, do: lookup_1(key, bigger)

  defp lookup_1(_, {_, value, _, _}), do: {:value, value}

  defp lookup_1(_, nil), do: :none

  defp map_1(_, nil), do: nil

  defp map_1(f, {k, v, smaller, larger}), do: {k, f.(k, v), map_1(f, smaller), map_1(f, larger)}

  defp merge(smaller, nil), do: smaller

  defp merge(nil, larger), do: larger

  defp merge(smaller, larger) do
    {key, value, larger1} = take_smallest1(larger)
    {key, value, smaller, larger1}
  end

  defp smaller_1(_Key, nil), do: :none

  defp smaller_1(key, {key1, value, _Smaller, larger}) when key > key1 do
    case smaller_1(key, larger) do
      :none ->
        {key1, value}
      entry ->
        entry
    end
  end

  defp smaller_1(key, {_Key, _Value, smaller, _Larger}), do: smaller_1(key, smaller)

  defp smallest_1({key, value, nil, _Larger}), do: {key, value}

  defp smallest_1({_Key, _Value, smaller, _Larger}), do: smallest_1(smaller)

  defp take_1(key, {key1, value, smaller, larger}) when key < key1 do
    {value2, smaller1} = take_1(key, smaller)
    {value2, {key1, value, smaller1, larger}}
  end

  defp take_1(key, {key1, value, smaller, bigger}) when key > key1 do
    {value2, bigger1} = take_1(key, bigger)
    {value2, {key1, value, smaller, bigger1}}
  end

  defp take_1(_, {_Key, value, smaller, larger}), do: {value, merge(smaller, larger)}

  defp take_largest1({key, value, smaller, nil}), do: {key, value, smaller}

  defp take_largest1({key, value, smaller, larger}) do
    {key1, value1, larger1} = take_largest1(larger)
    {key1, value1, {key, value, smaller, larger1}}
  end

  defp take_smallest1({key, value, nil, larger}), do: {key, value, larger}

  defp take_smallest1({key, value, smaller, larger}) do
    {key1, value1, smaller1} = take_smallest1(smaller)
    {key1, value1, {key, value, smaller1, larger}}
  end

  defp to_list({key, value, small, big}, l), do: to_list(small, [{key, value} | to_list(big, l)])

  defp to_list(nil, l), do: l

  defp to_list_1(t), do: to_list(t, [])

  defp update_1(key, value, {key1, v, smaller, bigger}) when key < key1, do: {key1, v, update_1(key, value, smaller), bigger}

  defp update_1(key, value, {key1, v, smaller, bigger}) when key > key1, do: {key1, v, smaller, update_1(key, value, bigger)}

  defp update_1(key, value, {_, _, smaller, bigger}), do: {key, value, smaller, bigger}

  defp values({_Key, value, small, big}, l), do: values(small, [value | values(big, l)])

  defp values(nil, l), do: l
end
