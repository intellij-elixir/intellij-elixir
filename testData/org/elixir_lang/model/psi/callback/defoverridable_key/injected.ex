defmodule Worker do
  @callback id(term) :: term
end

defmodule Defaults do
  defmacro __using__(_options) do
    quote do
      @behaviour Worker

      def id(x), do: x
    end
  end
end

defmodule UsesDefaults do
  use Defaults

  defoverridable id: 1

  @compile {:inline, id: 1}
  @dialyzer {:nowarn_function, id: 1}
end
