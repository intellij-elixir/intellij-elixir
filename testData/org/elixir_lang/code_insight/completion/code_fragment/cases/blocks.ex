defmodule B do
  def f(x) when x > 1 do
    case x do
      1 -> :one
      _ -> :other
    end
  rescue
    e in RuntimeError -> e
  catch
    :exit, _ -> :exit
  after
    :ok
  end

  def g(x) do
    if x, do: "a" else "b"
    foo(1) do
      [1] ++ [2]
    end
    {x} = {1}
    %{a: 1} = %{a: 1}
    'chars' <> "bin"
    true and nil or false
    123 + 1.5
    Mod.f() |> g()
  end
end
