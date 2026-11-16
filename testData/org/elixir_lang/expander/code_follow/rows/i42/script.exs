defmodule R do
  {{c, read}, _} = Code.eval_string(~S|c = :counters.new(1, []); {c, fn -> :counters.get(c, 1) end}|)
  :counters.put(c, 1, 7)
  @v read.()
  def r, do: @v
end
