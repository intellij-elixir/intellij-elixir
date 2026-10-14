defmodule Worker do
  @callback id(term) :: term
end

defmodule Defaults do
  defmacro __using__(_options) do
    _data =
      quote do
        def id(x), do: x
      end

    quote do
      @behaviour Worker
    end
  end
end

defmodule UsesDefaults do
  use Defaults

  defoverridable id: 1
end
