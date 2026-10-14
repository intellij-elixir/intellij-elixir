defmodule Worker do
  @callback id(term) :: term
  @callback hidden() :: term
  @callback multi(term) :: term
  @callback opt() :: term
  @callback opt(term) :: term
  @callback missing() :: term
  @macrocallback perform(term) :: Macro.t()
  @macrocallback expand(term) :: Macro.t()
end

defmodule Ids do
  def id(socket), do: socket
end

defmodule UsesWorker do
  @behaviour Worker

  defdelegate id(socket), to: Ids
  defoverridable id: 1

  defp hidden, do: :hidden
  defoverridable hidden: 0

  def perform(x), do: x
  defoverridable perform: 1

  defmacro expand(x), do: x
  defoverridable expand: 1

  def opt(x \\ nil), do: x
  defoverridable opt: 1

  def multi(:a), do: 1
  def multi(:b), do: 2
  defoverridable multi: 1

  defoverridable missing: 0
end
