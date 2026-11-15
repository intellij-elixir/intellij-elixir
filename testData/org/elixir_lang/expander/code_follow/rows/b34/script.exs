try do
  Code.require_file("r.exs", __DIR__)
rescue
  _ -> :ok
end

x =
  try do
    Code.require_file("r.exs", __DIR__)
    :ran
  rescue
    _ -> :raised
  end

defmodule R do
  @v x
  def r, do: @v
end
