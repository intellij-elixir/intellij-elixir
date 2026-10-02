# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :zip_map_generator do

  # Functions

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  def zip_map(map, list) do
    for {{k, v}, x} <- Enum.zip([map, list]) do
      {k, v, x}
    end
  end

  def zip_map_strict(map, list) do
    for {{k, v}, x} <- Enum.zip([map, list]) do
      {k, v, x}
    end
  end

  # Private Functions

  defp unquote(:"-zip_map/2-zlc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-zip_map_strict/2-zlc$^0/2-0-")(arg1, arg2) do
    # body not decompiled
  end
end
