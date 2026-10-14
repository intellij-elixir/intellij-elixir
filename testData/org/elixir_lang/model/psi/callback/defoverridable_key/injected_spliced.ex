defmodule Worker do
  @callback id(term) :: term
end

defmodule Defaults do
  defmacro __using__(_options) do
    definitions =
      quote do
        def id(x), do: x
      end

    quote do
      @behaviour Worker

      unquote(definitions)

      defoverridable id: 1
    end
  end
end
