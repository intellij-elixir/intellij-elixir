defmodule L do
  defmacro m do
    quote do
      def run, do: __MODULE__.Sub.helper(1)
    end
  end
end

defmodule U.Sub do
  def helper(x), do: x
end

defmodule U do
  require L
  L.m()
end
