# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :maybe_expr do

  # Functions

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  def with_else(x) do
    with {:ok, v} <- x,
         (doubled = case v do
      n when n > 0 ->
        n * 2
      _ ->
        0
    end) do
      {:ok, doubled}
    else
      :error ->
        {:error, :no_value}
      {:error, reason} ->
        {:error, reason}
    end
  end

  def without_else(x) do
    with {:ok, v} <- x do
      v
    end
  end
end
