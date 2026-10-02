# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :strict_generators do

  # Functions

  def bitstring_strict(bin) do
    for <<b <- bin>> do
      b
    end
  end

  def list_strict(list) do
    for x <- list do
      x
    end
  end

  def map_strict(map) do
    for {k, v} <- map do
      {k, v}
    end
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  # Private Functions

  defp unquote(:"-bitstring_strict/1-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-list_strict/1-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-map_strict/1-lc$^0/1-0-")(arg1) do
    # body not decompiled
  end
end
