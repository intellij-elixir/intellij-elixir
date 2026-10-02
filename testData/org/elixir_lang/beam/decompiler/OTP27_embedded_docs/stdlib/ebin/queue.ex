# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :queue do
  @moduledoc ~S"""
  Abstract data type for FIFO queues.

  This module provides (double-ended) FIFO queues in an efficient manner.

  All functions fail with reason `badarg` if arguments are of wrong type, for
  example, queue arguments are not queues, indexes are not integers, and list
  arguments are not lists. Improper lists cause internal crashes. An index out of
  range for a queue also causes a failure with reason `badarg`.

  Some functions, where noted, fail with reason `empty` for an empty queue.

  The data representing a queue as used by this module is to be regarded as opaque
  by other modules. In abstract terms, the representation is a composite type of
  existing Erlang terms. See note on
  [data types](`e:system:data_types.md#no_user_types`). Any code assuming
  knowledge of the format is running on thin ice.

  All operations have an amortized O(1) running time, except `all/2`, `any/2`,
  `delete/2`, `delete_r/2`, `delete_with/2`, `delete_with_r/2`, `filter/2`,
  `filtermap/2`, `fold/3`, `join/2`, `len/1`, `member/2`, `split/2` that have
  O(n). To minimize the size of a queue minimizing the amount of garbage built by
  queue operations, the queues do not contain explicit length information, and
  that is why [`len/1`](`len/1`) is O(n). If better performance for this
  particular operation is essential, it is easy for the caller to keep track of
  the length.

  Queues are double-ended. The mental picture of a queue is a line of people
  (items) waiting for their turn. The queue front is the end with the item that
  has waited the longest. The queue rear is the end an item enters when it starts
  to wait. If instead using the mental picture of a list, the front is called head
  and the rear is called tail.

  Entering at the front and exiting at the rear are reverse operations on the
  queue.

  This module has three sets of interface functions: the _"Original API"_, the
  _"Extended API"_, and the _"Okasaki API"_.

  The "Original API" and the "Extended API" both use the mental picture of a
  waiting line of items. Both have reverse operations suffixed "\_r".

  The "Original API" item removal functions return compound terms with both the
  removed item and the resulting queue. The "Extended API" contains alternative
  functions that build less garbage and functions for just inspecting the queue
  ends. Also the "Okasaki API" functions build less garbage.

  The "Okasaki API" is inspired by "Purely Functional Data Structures" by Chris
  Okasaki. It regards queues as lists. This API is by many regarded as strange and
  avoidable. For example, many reverse operations have lexically reversed names,
  some with more readable but perhaps less understandable aliases.
  """

  # Types

  @type queue :: queue(any())

  @typedoc ~S"""
  As returned by `new/0`.
  """
  @opaque queue(item) :: {[item], [item]}

  # Functions

  @doc ~S"""
  Returns `true` if `Pred(Item)` returns `true` for all items `Item` in `Q`,
  otherwise `false`.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  2> queue:all(fun (E) -> E > 3 end, Queue).
  false
  3> queue:all(fun (E) -> E > 0 end, Queue).
  true
  ```
  """
  @spec all(pred, q :: queue(item)) :: boolean() when pred: (item -> boolean())
  def all(pred, {r, f}) when is_function(pred, 1) and is_list(r) and is_list(f), do: :lists.all(pred, f) and :lists.all(pred, r)

  def all(pred, q), do: :erlang.error(:badarg, [pred, q])

  @doc ~S"""
  Returns `true` if `Pred(Item)` returns `true` for at least one item `Item` in
  `Q`, otherwise `false`.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  2> queue:any(fun (E) -> E > 10 end, Queue).
  false
  3> queue:any(fun (E) -> E > 3 end, Queue).
  true
  ```
  """
  @spec any(pred, q :: queue(item)) :: boolean() when pred: (item -> boolean())
  def any(pred, {r, f}) when is_function(pred, 1) and is_list(r) and is_list(f), do: :lists.any(pred, f) or :lists.any(pred, r)

  def any(pred, q), do: :erlang.error(:badarg, [pred, q])

  @doc ~S"""
  Inserts `Item` at the head of queue `Q1`. Returns the new queue `Q2`.

  _Example:_

  ```erlang
  1> Queue = queue:cons(0, queue:from_list([1,2,3])).
  {[3,2],[0,1]}
  2> queue:to_list(Queue).
  [0,1,2,3]
  ```
  """
  @spec cons(item, q1 :: queue(item)) :: q2 :: queue(item)
  def cons(x, q), do: in_r(x, q)

  @doc ~S"""
  Returns the tail item of queue `Q`.

  Fails with reason `empty` if `Q` is empty.

  _Example 1:_

  ```erlang
  1> queue:daeh(queue:from_list([1,2,3])).
  3
  ```
  """
  @spec daeh(q :: queue(item)) :: item
  def daeh(q), do: get_r(q)

  @doc ~S"""
  Returns a copy of `Q1` where the first item matching `Item` is deleted, if there
  is such an item.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  2> Queue1 = queue:delete(3, Queue).
  3> queue:member(3, Queue1).
  false
  ```
  """
  @spec delete(item, q1) :: q2 when item: t, q1: queue(t), q2: queue(t), t: term()
  def delete(item, {r0, f0} = q) when is_list(r0) and is_list(f0) do
    case delete_front(item, f0) do
      false ->
        case delete_rear(item, r0) do
          false ->
            q
          [] ->
            f2r(f0)
          r1 ->
            {r1, f0}
        end
      [] ->
        r2f(r0)
      f1 ->
        {r0, f1}
    end
  end

  def delete(item, q), do: :erlang.error(:badarg, [item, q])

  @doc ~S"""
  Returns a copy of `Q1` where the last item matching `Item` is deleted, if there
  is such an item.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,3,5]).
  2> Queue1 = queue:delete_r(3, Queue).
  3> queue:to_list(Queue1).
  [1,2,3,4,5]
  ```
  """
  @spec delete_r(item, q1) :: q2 when item: t, q1: queue(t), q2: queue(t), t: term()
  def delete_r(item, {r0, f0}) when is_list(r0) and is_list(f0) do
    {f1, r1} = delete(item, {f0, r0})
    {r1, f1}
  end

  def delete_r(item, q), do: :erlang.error(:badarg, [item, q])

  @doc ~S"""
  Returns a copy of `Q1` where the first item for which `Pred` returns `true` is
  deleted, if there is such an item.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([100,1,2,3,4,5]).
  2> Queue1 = queue:delete_with(fun (E) -> E > 0, Queue).
  3> queue:to_list(Queue1).
  [1,2,3,4,5]
  ```
  """
  @spec delete_with(pred, q1) :: q2 when pred: (item -> boolean()), q1: queue(item), q2: queue(item), item: term()
  def delete_with(pred, {r0, f0} = q) when is_function(pred, 1) and is_list(r0) and is_list(f0) do
    case delete_with_front(pred, f0) do
      false ->
        case delete_with_rear(pred, r0) do
          false ->
            q
          [] ->
            f2r(f0)
          r1 ->
            {r1, f0}
        end
      [] ->
        r2f(r0)
      f1 ->
        {r0, f1}
    end
  end

  def delete_with(pred, q), do: :erlang.error(:badarg, [pred, q])

  @doc ~S"""
  Returns a copy of `Q1` where the last item for which `Pred` returns `true` is
  deleted, if there is such an item.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5,100]).
  2> Queue1 = queue:delete_with(fun (E) -> E > 10, Queue).
  3> queue:to_list(Queue1).
  [1,2,3,4,5]
  ```
  """
  @spec delete_with_r(pred, q1) :: q2 when pred: (item -> boolean()), q1: queue(item), q2: queue(item), item: term()
  def delete_with_r(pred, {r0, f0}) when is_function(pred, 1) and is_list(r0) and is_list(f0) do
    {f1, r1} = delete_with(pred, {f0, r0})
    {r1, f1}
  end

  def delete_with_r(pred, q), do: :erlang.error(:badarg, [pred, q])

  @doc ~S"""
  Returns a queue `Q2` that is the result of removing the front item from `Q1`.

  Fails with reason `empty` if `Q1` is empty.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> Queue = queue:drop(Queue).
  {[5,4,3],[2]}
  3> queue:to_list(Queue1).
  [2,3,4,5]
  ```
  """
  @spec drop(q1 :: queue(item)) :: q2 :: queue(item)
  def drop({[], []} = q), do: :erlang.error(:empty, [q])

  def drop({[_], []}), do: {[], []}

  def drop({[y | r], []}) do
    [_ | f] = :lists.reverse(r, [])
    {[y], f}
  end

  def drop({r, [_]}) when is_list(r), do: r2f(r)

  def drop({r, [_ | f]}) when is_list(r), do: {r, f}

  def drop(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Returns a queue `Q2` that is the result of removing the rear item from `Q1`.

  Fails with reason `empty` if `Q1` is empty.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> Queue = queue:drop_r(Queue).
  {[4,3],[1,2]}
  3> queue:to_list(Queue1).
  [1,2,3,4]
  ```
  """
  @spec drop_r(q1 :: queue(item)) :: q2 :: queue(item)
  def drop_r({[], []} = q), do: :erlang.error(:empty, [q])

  def drop_r({[], [_]}), do: {[], []}

  def drop_r({[], [y | f]}) do
    [_ | r] = :lists.reverse(f, [])
    {r, [y]}
  end

  def drop_r({[_], f}) when is_list(f), do: f2r(f)

  def drop_r({[_ | r], f}) when is_list(f), do: {r, f}

  def drop_r(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Returns a queue `Q2` that is the result of calling `Fun(Item)` on all items in
  `Q1`.

  If `Fun(Item)` returns `true`, `Item` is copied to the result queue. If it
  returns `false`, `Item` is not copied. If it returns a list, the list elements
  are inserted instead of `Item` in the result queue.

  _Example 1:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> Queue1 = queue:filter(fun (E) -> E > 2 end, Queue).
  {[5],[3,4]}
  3> queue:to_list(Queue1).
  [3,4,5]
  ```

  So, `Fun(Item)` returning `[Item]` is thereby semantically equivalent to
  returning `true`, just as returning `[]` is semantically equivalent to returning
  `false`. But returning a list builds more garbage than returning an atom.

  _Example 2:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> Queue1 = queue:filter(fun (E) -> [E, E+1] end, Queue).
  {[6,5,5,4,4,3],[1,2,2,3]}
  3> queue:to_list(Queue1).
  [1,2,2,3,3,4,4,5,5,6]
  ```
  """
  @spec filter(fun, q1 :: queue(item)) :: q2 :: queue(item) when fun: (item -> (boolean() | [item]))
  def filter(fun, {r0, f0}) when is_function(fun, 1) and is_list(r0) and is_list(f0) do
    f = filter_f(fun, f0)
    r = filter_r(fun, r0)
    cond do
      r === [] ->
        f2r(f)
      f === [] ->
        r2f(r)
      true ->
        {r, f}
    end
  end

  def filter(fun, q), do: :erlang.error(:badarg, [fun, q])

  @doc ~S"""
  Returns a queue `Q2` that is the result of calling `Fun(Item)` on all items in
  `Q1`.

  If `Fun(Item)` returns `true`, `Item` is copied to the result queue. If it
  returns `false`, `Item` is not copied. If it returns `{true, NewItem}`, the
  queue element at this position is replaced with `NewItem` in the result queue.

  _Example 1:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> Queue1 = queue:filtermap(fun (E) -> E > 2 end, Queue).
  {[5],[3,4]}
  3> queue:to_list(Queue1).
  [3,4,5]
  4> Queue1 = queue:filtermap(fun (E) -> {true, E+100} end, Queue).
  {"ihg","ef"}
  5> queue:to_list(Queue1).
  "efghi
  ```
  """
  @spec filtermap(fun, q1) :: q2 when fun: (item -> (boolean() | {true, value})), q1: queue(item), q2: queue((item | value)), item: term(), value: term()
  def filtermap(fun, {r0, f0}) when is_function(fun, 1) and is_list(r0) and is_list(f0) do
    f = :lists.filtermap(fun, f0)
    r = filtermap_r(fun, r0)
    cond do
      r === [] ->
        f2r(f)
      f === [] ->
        r2f(r)
      true ->
        {r, f}
    end
  end

  def filtermap(fun, q), do: :erlang.error(:badarg, [fun, q])

  @doc ~S"""
  Calls `Fun(Item, AccIn)` on successive items `Item` of `Queue`, starting with
  `AccIn == Acc0`. The queue is traversed in queue order, that is, from front to
  rear. `Fun/2` must return a new accumulator, which is passed to the next call.
  The function returns the final value of the accumulator. `Acc0` is returned if
  the queue is empty.

  _Example:_

  ```erlang
  1> queue:fold(fun(X, Sum) -> X + Sum end, 0, queue:from_list([1,2,3,4,5])).
  15
  2> queue:fold(fun(X, Prod) -> X * Prod end, 1, queue:from_list([1,2,3,4,5])).
  120
  ```
  """
  @spec fold(fun, acc0, q :: queue(item)) :: acc1 when fun: (item, accIn -> accOut), acc0: term(), acc1: term(), accIn: term(), accOut: term()
  def fold(fun, acc0, {r, f}) when is_function(fun, 2) and is_list(r) and is_list(f) do
    acc1 = :lists.foldl(fun, acc0, f)
    :lists.foldr(fun, acc1, r)
  end

  def fold(fun, acc0, q), do: :erlang.error(:badarg, [fun, acc0, q])

  @doc ~S"""
  Returns a queue containing the items in `L` in the same order; the head item of
  the list becomes the front item of the queue.
  """
  @spec from_list(l :: [item]) :: queue(item)
  def from_list(l) when is_list(l), do: f2r(l)

  def from_list(l), do: :erlang.error(:badarg, [l])

  @doc ~S"""
  Returns `Item` at the front of queue `Q`.

  Fails with reason `empty` if `Q` is empty.

  _Example 1:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> 1 == queue:get(Queue).
  true
  ```
  """
  @spec get(q :: queue(item)) :: item
  def get({[], []} = q), do: :erlang.error(:empty, [q])

  def get({r, f}) when is_list(r) and is_list(f), do: get(r, f)

  def get(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Returns `Item` at the rear of queue `Q`.

  Fails with reason `empty` if `Q` is empty.

  _Example 1:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> 5 == queue:get_r(Queue).
  true
  ```
  """
  @spec get_r(q :: queue(item)) :: item
  def get_r({[], []} = q), do: :erlang.error(:empty, [q])

  def get_r({[h | _], f}) when is_list(f), do: h

  def get_r({[], [h]}), do: h

  def get_r({[], [_ | f]}), do: :lists.last(f)

  def get_r(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Returns `Item` from the head of queue `Q`.

  Fails with reason `empty` if `Q` is empty.

  _Example 1:_

  ```erlang
  1> queue:head(queue:from_list([1,2,3])).
  1
  ```
  """
  @spec head(q :: queue(item)) :: item
  def head({[], []} = q), do: :erlang.error(:empty, [q])

  def head({r, f}) when is_list(r) and is_list(f), do: get(r, f)

  def head(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Inserts `Item` at the rear of queue `Q1`. Returns the resulting queue `Q2`.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> Queue1 = queue:in(100, Queue).
  {[100,5,4,3],[1,2]}
  3> queue:to_list(Queue1).
  [1,2,3,4,5,100]
  ```
  """
  @spec unquote(:in)(item, q1 :: queue(item)) :: q2 :: queue(item)
  def unquote(:in)(x, {[_] = erlangVariableIn, []}), do: {[x], erlangVariableIn}

  def unquote(:in)(x, {erlangVariableIn, out}) when is_list(erlangVariableIn) and is_list(out), do: {[x | erlangVariableIn], out}

  def unquote(:in)(x, q), do: :erlang.error(:badarg, [x, q])

  @doc ~S"""
  Inserts `Item` at the front of queue `Q1`. Returns the resulting queue `Q2`.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> Queue1 = queue:in_r(100, Queue).
  {[5,4,3],[100,1,2]}
  3> queue:to_list(Queue1).
  [100,1,2,3,4,5]
  ```
  """
  @spec in_r(item, q1 :: queue(item)) :: q2 :: queue(item)
  def in_r(x, {[], [_] = f}), do: {f, [x]}

  def in_r(x, {r, f}) when is_list(r) and is_list(f), do: {r, [x | f]}

  def in_r(x, q), do: :erlang.error(:badarg, [x, q])

  @doc ~S"""
  Returns a queue `Q2` that is the result of removing the tail item from `Q1`.

  Fails with reason `empty` if `Q1` is empty.

  _Example:_

  ```erlang
  1> Queue = queue:init(queue:from_list([1,2,3])).
  {[2],[1]}
  2> queue:to_list(Queue).
  [1,2]
  ```
  """
  @spec init(q1 :: queue(item)) :: q2 :: queue(item)
  def init(q), do: drop_r(q)

  @doc ~S"""
  Tests if `Q` is empty and returns `true` if so, otherwise `false`.
  """
  @spec is_empty(q :: queue()) :: boolean()
  def is_empty({[], []}), do: true

  def is_empty({erlangVariableIn, out}) when is_list(erlangVariableIn) and is_list(out), do: false

  def is_empty(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Tests if `Term` is a queue and returns `true` if so, otherwise `false`. Note
  that the test will return `true` for a term coinciding with the representation
  of a queue, even when not constructed by thus module. See also note on
  [data types](`e:system:data_types.md#no_user_types`).
  """
  @spec is_queue(term :: term()) :: boolean()
  def is_queue({r, f}) when is_list(r) and is_list(f), do: true

  def is_queue(_), do: false

  @doc ~S"""
  Returns a queue `Q3` that is the result of joining `Q1` and `Q2` with `Q1` in
  front of `Q2`.

  _Example:_

  ```erlang
  1> Queue1 = queue:from_list([1,3]).
  {[3],[1]}
  2> Queue2 = queue:from_list([2,4]).
  {[4],[2]}
  3> queue:to_list(queue:join(Queue1, Queue2)).
  [1,3,2,4]
  ```
  """
  @spec join(q1 :: queue(item), q2 :: queue(item)) :: q3 :: queue(item)
  def join({r, f} = q, {[], []}) when is_list(r) and is_list(f), do: q

  def join({[], []}, {r, f} = q) when is_list(r) and is_list(f), do: q

  def join({r1, f1}, {r2, f2}) when is_list(r1) and is_list(f1) and is_list(r2) and is_list(f2), do: {r2, f1 ++ :lists.reverse(r1, f2)}

  def join(q1, q2), do: :erlang.error(:badarg, [q1, q2])


  @deprecated """
  queue:lait/1 is deprecated; use queue:liat/1 instead
  """

  @doc ~S"""
  Returns a queue `Q2` that is the result of removing the tail item from `Q1`.

  Fails with reason `empty` if `Q1` is empty.

  The name [`lait/1`](`lait/1`) is a misspelling - do not use it anymore.
  """
  @spec lait(q1 :: queue(item)) :: q2 :: queue(item)
  def lait(q), do: drop_r(q)

  @doc ~S"""
  Returns the tail item of queue `Q`.

  Fails with reason `empty` if `Q` is empty.

  _Example:_

  ```erlang
  1> queue:last(queue:from_list([1,2,3])).
  3
  ```
  """
  @spec last(q :: queue(item)) :: item
  def last(q), do: get_r(q)

  @doc ~S"""
  Calculates and returns the length of queue `Q`.
  """
  @spec len(q :: queue()) :: non_neg_integer()
  def len({r, f}) when is_list(r) and is_list(f), do: length(r) + length(f)

  def len(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Returns a queue `Q2` that is the result of removing the tail item from `Q1`.

  Fails with reason `empty` if `Q1` is empty.

  _Example:_

  ```erlang
  1> Queue = queue:liat(queue:from_list([1,2,3])).
  {[2],[1]}
  2> queue:to_list(Queue).
  [1,2]
  ```
  """
  @spec liat(q1 :: queue(item)) :: q2 :: queue(item)
  def liat(q), do: drop_r(q)

  @doc ~S"""
  Returns `true` if `Item` matches some element in `Q`, otherwise `false`.
  """
  @spec member(item, q :: queue(item)) :: boolean()
  def member(x, {r, f}) when is_list(r) and is_list(f), do: :lists.member(x, r) or :lists.member(x, f)

  def member(x, q), do: :erlang.error(:badarg, [x, q])

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @doc ~S"""
  Returns an empty queue.
  """
  @spec new() :: queue(none())
  def new(), do: {[], []}

  @doc ~S"""
  Removes the item at the front of queue `Q1`. Returns tuple
  `{{value, Item}, Q2}`, where `Item` is the item removed and `Q2` is the
  resulting queue. If `Q1` is empty, tuple `{empty, Q1}` is returned.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> {{value, 1=Item}, Queue1} = queue:out(Queue).
  {{value,1},{[5,4,3],[2]}}
  3> queue:to_list(Queue1).
  [2,3,4,5]
  ```
  """
  @spec out(q1 :: queue(item)) :: ({{:value, item}, q2 :: queue(item)} | {:empty, q1 :: queue(item)})
  def out({[], []} = q), do: {:empty, q}

  def out({[v], []}), do: {{:value, v}, {[], []}}

  def out({[y | erlangVariableIn], []}) do
    [v | out] = :lists.reverse(erlangVariableIn, [])
    {{:value, v}, {[y], out}}
  end

  def out({erlangVariableIn, [v]}) when is_list(erlangVariableIn), do: {{:value, v}, r2f(erlangVariableIn)}

  def out({erlangVariableIn, [v | out]}) when is_list(erlangVariableIn), do: {{:value, v}, {erlangVariableIn, out}}

  def out(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Removes the item at the rear of queue `Q1`. Returns tuple `{{value, Item}, Q2}`,
  where `Item` is the item removed and `Q2` is the new queue. If `Q1` is empty,
  tuple `{empty, Q1}` is returned.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> {{value, 5=Item}, Queue1} = queue:out_r(Queue).
  {{value,5},{[4,3],[1,2]}}
  3> queue:to_list(Queue1).
  [1,2,3,4]
  ```
  """
  @spec out_r(q1 :: queue(item)) :: ({{:value, item}, q2 :: queue(item)} | {:empty, q1 :: queue(item)})
  def out_r({[], []} = q), do: {:empty, q}

  def out_r({[], [v]}), do: {{:value, v}, {[], []}}

  def out_r({[], [y | out]}) do
    [v | erlangVariableIn] = :lists.reverse(out, [])
    {{:value, v}, {erlangVariableIn, [y]}}
  end

  def out_r({[v], out}) when is_list(out), do: {{:value, v}, f2r(out)}

  def out_r({[v | erlangVariableIn], out}) when is_list(out), do: {{:value, v}, {erlangVariableIn, out}}

  def out_r(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Returns tuple `{value, Item}`, where `Item` is the front item of `Q`, or `empty`
  if `Q` is empty.

  _Example 1:_

  ```erlang
  1> queue:peek(queue:new()).
  empty
  2> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  3> queue:peek(Queue).
  {value, 1}
  ```
  """
  @spec peek(q :: queue(item)) :: (:empty | {:value, item})
  def peek({[], []}), do: :empty

  def peek({r, [h | _]}) when is_list(r), do: {:value, h}

  def peek({[h], []}), do: {:value, h}

  def peek({[_ | r], []}), do: {:value, :lists.last(r)}

  def peek(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Returns tuple `{value, Item}`, where `Item` is the rear item of `Q`, or `empty`
  if `Q` is empty.

  _Example 1:_

  ```erlang
  1> queue:peek_r(queue:new()).
  empty
  2> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  3> queue:peek_r(Queue).
  {value, 5}
  ```
  """
  @spec peek_r(q :: queue(item)) :: (:empty | {:value, item})
  def peek_r({[], []}), do: :empty

  def peek_r({[h | _], f}) when is_list(f), do: {:value, h}

  def peek_r({[], [h]}), do: {:value, h}

  def peek_r({[], [_ | r]}), do: {:value, :lists.last(r)}

  def peek_r(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Returns a queue `Q2` containing the items of `Q1` in the reverse order.
  """
  @spec reverse(q1 :: queue(item)) :: q2 :: queue(item)
  def reverse({r, f}) when is_list(r) and is_list(f), do: {f, r}

  def reverse(q), do: :erlang.error(:badarg, [q])

  @doc ~S"""
  Inserts `Item` as the tail item of queue `Q1`. Returns the new queue `Q2`.

  _Example:_

  ```erlang
  1> Queue = queue:snoc(queue:from_list([1,2,3]), 4).
  {[4,3,2],[1]}
  2> queue:to_list(Queue).
  [1,2,3,4]
  ```
  """
  @spec snoc(q1 :: queue(item), item) :: q2 :: queue(item)
  def snoc(q, x), do: apply(__MODULE__, :in, [x, q])

  @doc ~S"""
  Splits `Q1` in two. The `N` front items are put in `Q2` and the rest in `Q3`.
  """
  @spec split(n :: non_neg_integer(), q1 :: queue(item)) :: {q2 :: queue(item), q3 :: queue(item)}
  def split(0, {r, f} = q) when is_list(r) and is_list(f), do: {{[], []}, q}

  def split(n, {r, f} = q) when is_integer(n) and n >= 1 and is_list(r) and is_list(f) do
    lf = :erlang.length(f)
    cond do
      n < lf ->
        [x | f1] = f
        split_f1_to_r2(n - 1, r, f1, [], [x])
      n > lf ->
        lr = length(r)
        m = lr - n - lf
        cond do
          m < 0 ->
            :erlang.error(:badarg, [n, q])
          m > 0 ->
            [x | r1] = r
            split_r1_to_f2(m - 1, r1, f, [x], [])
          true ->
            {q, {[], []}}
        end
      true ->
        {f2r(f), r2f(r)}
    end
  end

  def split(n, q), do: :erlang.error(:badarg, [n, q])

  @doc ~S"""
  Returns a queue `Q2` that is the result of removing the head item from `Q1`.

  Fails with reason `empty` if `Q1` is empty.
  """
  @spec tail(q1 :: queue(item)) :: q2 :: queue(item)
  def tail(q), do: drop(q)

  @doc ~S"""
  Returns a list of the items in the queue in the same order; the front item of
  the queue becomes the head of the list.

  _Example:_

  ```erlang
  1> Queue = queue:from_list([1,2,3,4,5]).
  {[5,4,3],[1,2]}
  2> List == queue:to_list(Queue).
  true
  ```
  """
  @spec to_list(q :: queue(item)) :: [item]
  def to_list({erlangVariableIn, out}) when is_list(erlangVariableIn) and is_list(out), do: out ++ :lists.reverse(erlangVariableIn, [])

  def to_list(q), do: :erlang.error(:badarg, [q])

  # Private Functions

  defp delete_front(item, [^item | rest]), do: rest

  defp delete_front(item, [x | rest]) do
    case delete_front(item, rest) do
      false ->
        false
      f ->
        [x | f]
    end
  end

  defp delete_front(_, []), do: false

  defp delete_rear(item, [x | rest]) do
    case delete_rear(item, rest) do
      false when x === item ->
        rest
      false ->
        false
      r ->
        [x | r]
    end
  end

  defp delete_rear(_, []), do: false

  defp delete_with_front(pred, [x | rest]) do
    case pred.(x) do
      true ->
        rest
      false ->
        case delete_with_front(pred, rest) do
          false ->
            false
          f ->
            [x | f]
        end
    end
  end

  defp delete_with_front(_, []), do: false

  defp delete_with_rear(pred, [x | rest]) do
    case delete_with_rear(pred, rest) do
      false ->
        case pred.(x) do
          true ->
            rest
          false ->
            false
        end
      r ->
        [x | r]
    end
  end

  defp delete_with_rear(_, []), do: false

  defp filter_f(_, []), do: []

  defp filter_f(fun, [x | f]) do
    case fun.(x) do
      true ->
        [x | filter_f(fun, f)]
      [y] ->
        [y | filter_f(fun, f)]
      false ->
        filter_f(fun, f)
      [] ->
        filter_f(fun, f)
      l when is_list(l) ->
        l ++ filter_f(fun, f)
    end
  end

  defp filter_r(_, []), do: []

  defp filter_r(fun, [x | r0]) do
    r = filter_r(fun, r0)
    case fun.(x) do
      true ->
        [x | r]
      [y] ->
        [y | r]
      false ->
        r
      [] ->
        r
      l when is_list(l) ->
        :lists.reverse(l, r)
    end
  end

  defp filtermap_r(_, []), do: []

  defp filtermap_r(fun, [x | r0]) do
    r = filtermap_r(fun, r0)
    case fun.(x) do
      true ->
        [x | r]
      {true, y} ->
        [y | r]
      false ->
        r
    end
  end

  @spec get([], []) :: term()
  defp get(r, [h | _]) when is_list(r), do: h

  defp get([h], []), do: h

  defp get([_ | r], []), do: :lists.last(r)

  defp split_f1_to_r2(0, r1, f1, r2, f2), do: {{r2, f2}, {r1, f1}}

  defp split_f1_to_r2(n, r1, [x | f1], r2, f2), do: split_f1_to_r2(n - 1, r1, f1, [x | r2], f2)

  defp split_r1_to_f2(0, r1, f1, r2, f2), do: {{r1, f1}, {r2, f2}}

  defp split_r1_to_f2(n, [x | r1], f1, r2, f2), do: split_r1_to_f2(n - 1, r1, f1, r2, [x | f2])
end
