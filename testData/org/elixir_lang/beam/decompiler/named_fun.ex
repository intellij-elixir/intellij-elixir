# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :named_fun do

  # Functions

  def countdown(n) do
    f = (loop = fn
      # Decompiled from an Erlang named fun. Elixir has no named anonymous functions, so
      # `loop` is bound to the fn here to keep self-references readable; it will still
      # NOT compile if the body recurses, as Elixir cannot see `loop` inside its own
      # definition (recursion must go through a module function).
      0 ->
        :done
      k ->
        loop.(k - 1)
    end)
    f.(n)
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  def sum_to(n) do
    (loop = fn
      # Decompiled from an Erlang named fun. Elixir has no named anonymous functions, so
      # `loop` is bound to the fn here to keep self-references readable; it will still
      # NOT compile if the body recurses, as Elixir cannot see `loop` inside its own
      # definition (recursion must go through a module function).
      0, acc ->
        acc
      k, acc ->
        loop.(k - 1, acc + k)
    end).(n, 0)
  end

  # Private Functions

  defp unquote(:"-countdown/1-Loop/1-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-sum_to/1-Loop/2-0-")(arg1, arg2) do
    # body not decompiled
  end
end
