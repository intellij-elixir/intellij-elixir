defmodule L do
  defmacro m do
    quote do
      defmodule Inner do
        def run, do: __MODULE__.Sub.helper(1)
      end
    end
  end
end

defmodule U.Inner.Sub do
  def helper(x), do: x
end

defmodule U do
  require L
  L.m()
end
