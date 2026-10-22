defmodule MyAppWeb.CoreComponents do
  require EEx

  @args [:assigns]
  EEx.function_from_string(:def, :button, "<%= @x %>", @args)
end
