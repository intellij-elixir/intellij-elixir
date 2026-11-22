# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :erlang_signature do

  # Functions

  @spec annotated(q :: atom()) :: :ok
  def annotated(_), do: :ok

  @spec anonymous_spec(any()) :: :ok
  def anonymous_spec(_Value), do: :ok

  def args(first, second), do: {first, second}

  def behaviour_info(arg1) do
    # body not decompiled
  end

  @spec bounded(tuple) :: term() when tuple: tuple()
  def bounded({x}), do: x

  @spec callback_named({atom(), atom()}) :: {atom(), atom()}
  def callback_named({a, b}), do: {a, b}

  def calls_private(x), do: {private_spec({x}), private_trimmed(x)}

  @doc ~S"""
  Names its parameters on its first line.
  """
  def doc_line(a, b), do: {a, b}

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  def multi_clause(first, :a), do: first

  def multi_clause(_, second), do: second

  def pattern({x}, y), do: {x, y}

  @spec remote(tuple) :: tuple
  def remote({x}), do: x

  @spec rendered(erlangVariableIn, q1) :: {erlangVariableIn, q1}
  def rendered({x}, y), do: {x, y}

  @spec spec_after(tuple) :: tuple
  def spec_after({x}), do: x

  @spec spec_names(key, value) :: {key, value}
  def spec_names(:x, {y}), do: {:x, y}

  @spec spec_over_clauses(key, list) :: {key, list}
  def spec_over_clauses(:k, []), do: :ok

  def spec_over_clauses(_, [_ | _]), do: :ok

  @spec spec_then_callback(own) :: own
  def spec_then_callback({x}), do: x

  def trimmed(_Acc, value), do: value

  @spec two_clause_spec(atom :: atom()) :: atom()
  @spec two_clause_spec(int :: integer()) :: integer()
  def two_clause_spec(x), do: x

  def underscore(_, value), do: value

  @spec untyped_spec(atom()) :: atom()
  def untyped_spec(value), do: value

  # Private Functions

  defp unquote(:"-inlined-behaviour_info/1-")(arg1) do
    # body not decompiled
  end

  @spec private_spec(name) :: name
  defp private_spec({n}), do: n

  defp private_trimmed(_Acc), do: :ok
end
