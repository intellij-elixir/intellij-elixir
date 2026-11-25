defmodule Foo do
  require __MODULE__.Sub, as: M

  def run, do: M.f()
end
