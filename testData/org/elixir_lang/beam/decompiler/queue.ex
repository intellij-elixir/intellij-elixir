# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :queue do

  # Types

  @type queue :: queue(any())

  @opaque queue(item) :: {[item], [item]}

  # Functions

  @spec cons(item, q1 :: queue(item)) :: q2 :: queue(item)
  def cons(x, q), do: in_r(x, q)

  @spec daeh(q :: queue(item)) :: item
  def daeh(q), do: get_r(q)

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

  @spec from_list(l :: [item]) :: queue(item)
  def from_list(l) when is_list(l), do: f2r(l)

  def from_list(l), do: :erlang.error(:badarg, [l])

  @spec get(q :: queue(item)) :: item
  def get({[], []} = q), do: :erlang.error(:empty, [q])

  def get({r, f}) when is_list(r) and is_list(f), do: get(r, f)

  def get(q), do: :erlang.error(:badarg, [q])

  @spec get_r(q :: queue(item)) :: item
  def get_r({[], []} = q), do: :erlang.error(:empty, [q])

  def get_r({[h | _], f}) when is_list(f), do: h

  def get_r({[], [h]}), do: h

  def get_r({[], [_ | f]}), do: :lists.last(f)

  def get_r(q), do: :erlang.error(:badarg, [q])

  @spec head(q :: queue(item)) :: item
  def head({[], []} = q), do: :erlang.error(:empty, [q])

  def head({r, f}) when is_list(r) and is_list(f), do: get(r, f)

  def head(q), do: :erlang.error(:badarg, [q])

  @spec unquote(:in)(item, q1 :: queue(item)) :: q2 :: queue(item)
  def unquote(:in)(x, {[_] = erlangVariableIn, []}), do: {[x], erlangVariableIn}

  def unquote(:in)(x, {erlangVariableIn, out}) when is_list(erlangVariableIn) and is_list(out), do: {[x | erlangVariableIn], out}

  def unquote(:in)(x, q), do: :erlang.error(:badarg, [x, q])

  @spec in_r(item, q1 :: queue(item)) :: q2 :: queue(item)
  def in_r(x, {[], [_] = f}), do: {f, [x]}

  def in_r(x, {r, f}) when is_list(r) and is_list(f), do: {r, [x | f]}

  def in_r(x, q), do: :erlang.error(:badarg, [x, q])

  @spec init(q1 :: queue(item)) :: q2 :: queue(item)
  def init(q), do: drop_r(q)

  @spec is_empty(q :: queue()) :: boolean()
  def is_empty({[], []}), do: true

  def is_empty({erlangVariableIn, out}) when is_list(erlangVariableIn) and is_list(out), do: false

  def is_empty(q), do: :erlang.error(:badarg, [q])

  @spec is_queue(term :: term()) :: boolean()
  def is_queue({r, f}) when is_list(r) and is_list(f), do: true

  def is_queue(_), do: false

  @spec join(q1 :: queue(item), q2 :: queue(item)) :: q3 :: queue(item)
  def join({r, f} = q, {[], []}) when is_list(r) and is_list(f), do: q

  def join({[], []}, {r, f} = q) when is_list(r) and is_list(f), do: q

  def join({r1, f1}, {r2, f2}) when is_list(r1) and is_list(f1) and is_list(r2) and is_list(f2), do: {r2, f1 ++ :lists.reverse(r1, f2)}

  def join(q1, q2), do: :erlang.error(:badarg, [q1, q2])

  @spec lait(q1 :: queue(item)) :: q2 :: queue(item)
  def lait(q), do: drop_r(q)

  @spec last(q :: queue(item)) :: item
  def last(q), do: get_r(q)

  @spec len(q :: queue()) :: non_neg_integer()
  def len({r, f}) when is_list(r) and is_list(f), do: length(r) + length(f)

  def len(q), do: :erlang.error(:badarg, [q])

  @spec liat(q1 :: queue(item)) :: q2 :: queue(item)
  def liat(q), do: drop_r(q)

  @spec member(item, q :: queue(item)) :: boolean()
  def member(x, {r, f}) when is_list(r) and is_list(f), do: :lists.member(x, r) or :lists.member(x, f)

  def member(x, q), do: :erlang.error(:badarg, [x, q])

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @spec new() :: queue()
  def new(), do: {[], []}

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

  @spec peek(q :: queue(item)) :: (:empty | {:value, item})
  def peek({[], []}), do: :empty

  def peek({r, [h | _]}) when is_list(r), do: {:value, h}

  def peek({[h], []}), do: {:value, h}

  def peek({[_ | r], []}), do: {:value, :lists.last(r)}

  def peek(q), do: :erlang.error(:badarg, [q])

  @spec peek_r(q :: queue(item)) :: (:empty | {:value, item})
  def peek_r({[], []}), do: :empty

  def peek_r({[h | _], f}) when is_list(f), do: {:value, h}

  def peek_r({[], [h]}), do: {:value, h}

  def peek_r({[], [_ | r]}), do: {:value, :lists.last(r)}

  def peek_r(q), do: :erlang.error(:badarg, [q])

  @spec reverse(q1 :: queue(item)) :: q2 :: queue(item)
  def reverse({r, f}) when is_list(r) and is_list(f), do: {f, r}

  def reverse(q), do: :erlang.error(:badarg, [q])

  @spec snoc(q1 :: queue(item), item) :: q2 :: queue(item)
  def snoc(q, x), do: apply(__MODULE__, :in, [x, q])

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

  @spec tail(q1 :: queue(item)) :: q2 :: queue(item)
  def tail(q), do: drop(q)

  @spec to_list(q :: queue(item)) :: [item]
  def to_list({erlangVariableIn, out}) when is_list(erlangVariableIn) and is_list(out), do: out ++ :lists.reverse(erlangVariableIn, [])

  def to_list(q), do: :erlang.error(:badarg, [q])

  # Private Functions

  defp filter_f(_, []), do: []

  defp filter_f(fun, [x | f]) do
    case fun.(x) do
      true ->
        [x | filter_f(fun, f)]
      false ->
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
      false ->
        r
      l when is_list(l) ->
        :lists.reverse(l, r)
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
