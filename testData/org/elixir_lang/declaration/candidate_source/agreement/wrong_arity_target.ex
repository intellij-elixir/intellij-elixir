defmodule WrongArityTarget.Other do
  def d(a, b), do: {a, b}
end

defmodule WrongArityTarget do
  defdelegate d(a), to: WrongArityTarget.Other

  def run(a), do: d(a)
end
