defmodule D do
  {v, _} = Code.eval_file("x.exs")
  @r {v, Path.basename(File.cwd!())}
  def r, do: @r
end
