# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :gb_sets do

  # Types

  @type iter :: iter(any())

  @opaque iter(element) :: [gb_set_node(element)]

  @type set :: set(any())

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

  @spec add_element(element, set1) :: set2 when set1: set(element), set2: set(element)
  def add_element(x, s), do: add(x, s)

  @spec balance(set1) :: set2 when set1: set(element), set2: set(element)
  def balance({s, t}), do: {s, balance(t, s)}

  @spec del_element(element, set1) :: set2 when set1: set(element), set2: set(element)
  def del_element(key, s), do: delete_any(key, s)

  @spec delete(element, set1) :: set2 when set1: set(element), set2: set(element)
  def delete(key, {s, t}), do: {s - 1, delete_1(key, t)}

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
  def difference({n1, t1}, {n2, t2}), do: difference(to_list_1(t1), n1, t2, n2)

  @spec empty() :: set when set: set()
  def empty(), do: {0, nil}

  @spec filter(pred, set1) :: set2 when pred: (element -> boolean()), set1: set(element), set2: set(element)
  def filter(f, s) do
    from_ordset((for x <- to_list(s), f.(x) do
      x
    end))
  end

  @spec fold(function, acc0, set) :: acc1 when function: (element, accIn -> accOut), acc0: acc, acc1: acc, accIn: acc, accOut: acc, set: set(element)
  def fold(f, a, {_, t}) when is_function(f, 2), do: fold_1(f, a, t)

  @spec from_list(list) :: set when list: [element], set: set(element)
  def from_list(l), do: from_ordset(:ordsets.from_list(l))

  @spec from_ordset(list) :: set when list: [element], set: set(element)
  def from_ordset(l) do
    s = length(l)
    {s, balance_list(l, s)}
  end

  @spec insert(element, set1) :: set2 when set1: set(element), set2: set(element)
  def insert(key, {s, t}) do
    s1 = s + 1
    {s1, insert_1(key, t, s1 * s1)}
  end

  @spec intersection(setList) :: set when setList: [set(element), ...], set: set(element)
  def intersection([s | ss]), do: intersection_list(s, ss)

  @spec intersection(set1, set2) :: set3 when set1: set(element), set2: set(element), set3: set(element)
  def intersection({n1, t1}, {n2, t2}) when n2 < n1, do: intersection(to_list_1(t2), n2, t1, n1)

  def intersection({n1, t1}, {n2, t2}), do: intersection(to_list_1(t1), n1, t2, n2)

  @spec is_disjoint(set1, set2) :: boolean() when set1: set(element), set2: set(element)
  def is_disjoint({n1, t1}, {n2, t2}) when n1 < n2, do: is_disjoint_1(t1, t2)

  def is_disjoint({_, t1}, {_, t2}), do: is_disjoint_1(t2, t1)

  @spec is_element(element, set) :: boolean() when set: set(element)
  def is_element(key, s), do: is_member(key, s)

  @spec is_empty(set) :: boolean() when set: set()
  def is_empty({0, nil}), do: true

  def is_empty(_), do: false

  @spec is_member(element, set) :: boolean() when set: set(element)
  def is_member(key, {_, t}), do: is_member_1(key, t)

  @spec is_set(term) :: boolean() when term: term()
  def is_set({0, nil}), do: true

  def is_set({n, {_, _, _}}) when is_integer(n) and n >= 0, do: true

  def is_set(_), do: false

  @spec is_subset(set1, set2) :: boolean() when set1: set(element), set2: set(element)
  def is_subset({n1, t1}, {n2, t2}), do: is_subset(to_list_1(t1), n1, t2, n2)

  @spec iterator(set) :: iter when set: set(element), iter: iter(element)
  def iterator({_, t}), do: iterator(t, [])

  @spec iterator_from(element, set) :: iter when set: set(element), iter: iter(element)
  def iterator_from(s, {_, t}), do: iterator_from(s, t, [])

  @spec largest(set) :: element when set: set(element)
  def largest({_, t}), do: largest_1(t)

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @spec new() :: set when set: set()
  def new(), do: empty()

  @spec next(iter1) :: ({element, iter2} | :none) when iter1: iter(element), iter2: iter(element)
  def next([{x, _, t} | as]), do: {x, iterator(t, as)}

  def next([]), do: :none

  @spec singleton(element) :: set(element)
  def singleton(key), do: {1, {key, nil, nil}}

  @spec size(set) :: non_neg_integer() when set: set()
  def size({size, _}), do: size

  @spec smallest(set) :: element when set: set(element)
  def smallest({_, t}), do: smallest_1(t)

  @spec subtract(set1, set2) :: set3 when set1: set(element), set2: set(element), set3: set(element)
  def subtract(s1, s2), do: difference(s1, s2)

  @spec take_largest(set1) :: {element, set2} when set1: set(element), set2: set(element)
  def take_largest({s, t}) do
    {key, smaller} = take_largest1(t)
    {key, {s - 1, smaller}}
  end

  @spec take_smallest(set1) :: {element, set2} when set1: set(element), set2: set(element)
  def take_smallest({s, t}) do
    {key, larger} = take_smallest1(t)
    {key, {s - 1, larger}}
  end

  @spec to_list(set) :: list when set: set(element), list: [element]
  def to_list({_, t}), do: to_list(t, [])

  @spec union(setList) :: set when setList: [set(element), ...], set: set(element)
  def union([s | ss]), do: union_list(s, ss)

  def union([]), do: empty()

  @spec union(set1, set2) :: set3 when set1: set(element), set2: set(element), set3: set(element)
  def union({n1, t1}, {n2, t2}) when n2 < n1, do: union(to_list_1(t2), n2, t1, n1)

  def union({n1, t1}, {n2, t2}), do: union(to_list_1(t1), n1, t2, n2)

  # Private Functions

  defp unquote(:"-filter/2-lc$^0/1-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp balance(t, s), do: balance_list(to_list_1(t), s)

  defp balance_list(l, s) do
    {t, _} = balance_list_1(l, s)
    t
  end

  defp balance_list_1(l, s) when s > 1 do
    sm = s - 1
    s2 = div(sm, 2)
    s1 = sm - s2
    {t1, [k | l1]} = balance_list_1(l, s1)
    {t2, l2} = balance_list_1(l1, s2)
    t = {k, t1, t2}
    {t, l2}
  end

  defp balance_list_1([key | l], 1), do: {{key, nil, nil}, l}

  defp balance_list_1(l, 0), do: {nil, l}

  defp balance_revlist(l, s) do
    {t, _} = balance_revlist_1(l, s)
    t
  end

  defp balance_revlist_1(l, s) when s > 1 do
    sm = s - 1
    s2 = div(sm, 2)
    s1 = sm - s2
    {t2, [k | l1]} = balance_revlist_1(l, s1)
    {t1, l2} = balance_revlist_1(l1, s2)
    t = {k, t1, t2}
    {t, l2}
  end

  defp balance_revlist_1([key | l], 1), do: {{key, nil, nil}, l}

  defp balance_revlist_1(l, 0), do: {nil, l}

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

  defp fold_1(f, acc0, {key, small, big}) do
    acc1 = fold_1(f, acc0, small)
    acc = f.(key, acc1)
    fold_1(f, acc, big)
  end

  defp fold_1(_, acc, _), do: acc

  defp insert_1(key, {key1, smaller, bigger}, s) when key < key1 do
    case insert_1(key, smaller, s >>> 1) do
      {t1, h1, s1} when is_integer(h1) ->
        t = {key1, t1, bigger}
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
        {key1, t1, bigger}
    end
  end

  defp insert_1(key, {key1, smaller, bigger}, s) when key > key1 do
    case insert_1(key, bigger, s >>> 1) do
      {t1, h1, s1} when is_integer(h1) ->
        t = {key1, smaller, t1}
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

  defp iterator({_, nil, _} = t, as), do: [t | as]

  defp iterator({_, l, _} = t, as), do: iterator(l, [t | as])

  defp iterator(nil, as), do: as

  defp iterator_from(s, {k, _, t}, as) when k < s, do: iterator_from(s, t, as)

  defp iterator_from(_, {_, nil, _} = t, as), do: [t | as]

  defp iterator_from(s, {_, l, _} = t, as), do: iterator_from(s, l, [t | as])

  defp iterator_from(_, nil, as), do: as

  defp largest_1({key, _Smaller, nil}), do: key

  defp largest_1({_Key, _Smaller, larger}), do: largest_1(larger)

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
