defmodule NamelessQuery do
  require EEx
  require Mix.Generator

  EEx.function_from_string(:def, :from_string, "<%= 1 %>", [])
  Mix.Generator.embed_text(:error, "Error")
end
