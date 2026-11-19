defmodule M do
  defmacro m(x) do
    if System.get_env("X") do
      Macro.expand(quote(do: unless(true, do: 1)), __CALLER__)
    end

    x
  end
end
