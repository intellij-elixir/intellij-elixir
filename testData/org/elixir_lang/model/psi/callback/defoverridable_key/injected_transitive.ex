defmodule Worker do
  @callback id(term) :: term
end

defmodule Inner do
  defmacro __using__(_options) do
    quote do
      @behaviour Worker

      def id(x), do: x
    end
  end
end

defmodule Outer do
  defmacro __using__(_options) do
    quote do
      use Inner
    end
  end
end

defmodule UsesOuter do
  use Outer

  defoverridable id: 1
end
