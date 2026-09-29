defmodule EEx do
  defmacro function_from_file(kind, name, file, args \\ [], options \\ []) do
    {kind, name, file, args, options}
  end

  defmacro function_from_string(kind, name, source, args \\ [], options \\ []) do
    {kind, name, source, args, options}
  end
end
