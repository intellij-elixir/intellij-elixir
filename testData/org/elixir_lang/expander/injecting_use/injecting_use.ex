# The `.beam` beside this is `elixirc injecting_use.ex -o .` of it.
defmodule InjectingUse do
  defmacro __using__(_) do
    quote do
      def foo, do: 1
      def bar, do: 2
    end
  end
end
