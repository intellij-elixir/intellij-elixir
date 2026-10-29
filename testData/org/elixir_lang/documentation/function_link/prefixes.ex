defmodule Prefixed do
  def foo(a), do: a
  def foo_bar(a), do: a
end

defmodule PrefixOnly do
  def foo_bar(a), do: a
end

defmodule LongerFirst do
  def foo_bar(a), do: a
  def foo(a, b), do: {a, b}
end

defmodule ShorterFirst do
  def foo(a, b), do: {a, b}
  def foo_bar(a), do: a
end

defmodule LongerCallback do
  @callback foo_bar(integer) :: term
end

defmodule LongerCallbackFirst do
  @callback foo_bar(integer) :: term
  @callback foo(integer, integer) :: term
end
