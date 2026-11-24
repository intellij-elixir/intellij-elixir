defmodule M do
  def run, do: <error descr="Module '__MODULE__.Nope' is not defined or aliased in this scope">__MODULE__.Nope</error>.f()
end
