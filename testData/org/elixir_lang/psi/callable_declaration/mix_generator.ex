defmodule Mix.Generator do
  defmacro embed_template(name, contents) do
    {name, contents}
  end

  defmacro embed_text(name, contents) do
    {name, contents}
  end
end
