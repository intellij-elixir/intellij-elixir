defmodule UsagesDefaultWithoutBehaviour do
  @callback per<caret>form() :: any

  defmacro __using__(_) do
    quote do
      def perform, do: :default
    end
  end
end
