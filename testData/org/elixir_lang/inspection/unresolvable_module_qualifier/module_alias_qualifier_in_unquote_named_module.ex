defmodule L do
  defmacro m(name) do
    quote do
      defmodule unquote(name) do
        def run, do: __MODULE__.Sub.helper(1)
      end
    end
  end
end

defmodule N do
end

defmodule N.Sub do
  def helper(x), do: x
end

defmodule U do
  require L
  L.m(N)
end
