defmodule Worker do
  @callback helper(term) :: term
  @callback own(term) :: term
end

defmodule Helpers do
  def helper(x), do: x
end

defmodule Defaults do
  defmacro __using__(_options) do
    quote do
      @behaviour Worker

      import Helpers

      def own(x), do: x
    end
  end
end

defmodule UsesDefaults do
  use Defaults

  defoverridable helper: 1, own: 1

  @compile {:inline, helper: 1, own: 1}
end
