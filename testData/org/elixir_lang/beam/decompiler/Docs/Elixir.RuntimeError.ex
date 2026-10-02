# Source code recreated from a .beam file by IntelliJ Elixir
defmodule RuntimeError do

  # Functions

  def __info__(arg1) do
    # body not decompiled
  end

  def __struct__() do
    %RuntimeError{__exception__: true, message: "runtime error"}
  end

  def __struct__(kv) do
    Enum.reduce(kv, %RuntimeError{__exception__: true, message: "runtime error"}, fn {key, val}, map -> :maps.update(key, val, map) end)
  end

  @doc false
  def exception(msg) when is_binary(msg) do
    exception(message: msg)
  end

  def exception(args) when is_list(args) do
    (
      struct = __struct__()
      {valid, invalid} = Enum.split_with(args, fn {k, _} -> Map.has_key?(struct, k) end)
      case(invalid) do
        [] ->
          :ok
        _ ->
          IO.warn(<<"the following fields are unknown when raising "::binary(), Kernel.inspect(RuntimeError)::binary(), ": "::binary(), Kernel.inspect(invalid)::binary(), ". "::binary(), "Please make sure to only give known fields when raising "::binary(), "or redefine "::binary(), Kernel.inspect(RuntimeError)::binary(), ".exception/1 to "::binary(), "discard unknown fields. Future Elixir versions will raise on "::binary(), "unknown fields given to raise/2"::binary()>>)
      end
      Kernel.struct!(struct, valid)
    )
  end

  @doc false
  def message(exception) do
    exception.message()
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  # Private Functions

  defp unquote(:"-__struct__/1-fun-0-")(arg1, arg2) do
    # body not decompiled
  end

  defp unquote(:"-exception/1-fun-0-")(arg1, arg2) do
    # body not decompiled
  end
end
