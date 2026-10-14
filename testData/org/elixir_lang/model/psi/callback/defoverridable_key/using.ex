defmodule Socket do
  @callback id(term) :: term
end

defmodule LiveSocket do
  defmacro __using__(_options) do
    quote do
      @behaviour Socket

      defdelegate id(socket), to: unquote(__MODULE__)
      defoverridable id: 1
    end
  end
end
