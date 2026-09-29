defmodule Errors do
  defexception [:message]
  def pad(a, b \\ 1), do: {a, b}
end

defmodule OnlyUser do
  import Errors, only: [exception: 1, pad: 1]

  def usage do
    {exception(message: "x"), message(%{}), pad(1), pad(1, 2)}
  end
end
