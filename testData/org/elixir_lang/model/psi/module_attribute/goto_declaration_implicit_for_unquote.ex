defprotocol ImplicitForProtocol do
  def describe(term)
end

defmodule ImplicitForTarget do
end

defimpl ImplicitForProtocol, for: ImplicitForTarget do
  defmacro __using__(_) do
    quote do
      def describe(_), do: unquote(@f<caret>or)
    end
  end
end
