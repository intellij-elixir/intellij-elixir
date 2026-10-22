defmodule EEx do
  defmacro function_from_string(kind, name, source, args \\ [], options \\ []) do
    quote do
      unquote(kind)
      unquote(name)
    end
  end
end
