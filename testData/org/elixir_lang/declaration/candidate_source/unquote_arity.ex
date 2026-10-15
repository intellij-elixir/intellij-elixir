defmodule Arities do
  def zero, do: 0
  def one(a), do: a
  def two(a, b), do: {a, b}

  defmacro dynamic(name) do
    quote do
      Arities.unquote(name)
      Arities.unquote(name)()
      Arities.unquote(name)(1, 2)
    end
  end

  defmacro piped(piped) do
    quote do
      :x |> Arities.unquote(piped)
      :x |> Arities.unquote(piped)(1)
    end
  end

  defmacro blocks(block) do
    quote do
      Arities.unquote(block) do
        :ok
      end

      Arities.unquote(block)(1) do
        :ok
      end
    end
  end
end
