defmodule OpenTarget do
  def open(a, b), do: {a, b}
  def open(a, b, c), do: {a, b, c}
end

defmodule OpenDelegator do
  defdelegate open(a, unquote_splicing(rest)), to: OpenTarget
end
