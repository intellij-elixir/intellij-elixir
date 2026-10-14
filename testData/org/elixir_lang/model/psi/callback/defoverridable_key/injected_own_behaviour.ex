defmodule Worker do
  @callback id(term) :: term
end

defmodule Plain do
  defmacro __using__(_options) do
    quote do
      def id(x), do: x
    end
  end
end

defmodule UsesPlain do
  use Plain
  @behaviour Worker

  defoverridable id: 1
end
