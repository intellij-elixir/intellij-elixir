defmodule NormalizedEExFunction do
  require EEx

  EEx.function_from_string(:def, :snoć, "<%= a %>", [:a])

  def caller(a), do: snoć<caret>(a)
end
