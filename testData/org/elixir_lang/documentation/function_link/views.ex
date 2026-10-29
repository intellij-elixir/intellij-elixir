defmodule Views do
  require EEx

  EEx.function_from_string(:def, :listed, "<%= a %>", [:a])
  EEx.function_from_string(:def, :opaque, "<%= a %>", bindings)
  EEx.function_from_string(:def, :open_tail, "<%= a %>", [:a | rest])
  EEx.function_from_string(:def, :"quoted_eex", "<%= a %>", [:a])
end
