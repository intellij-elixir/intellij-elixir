defmodule QuotedNames do
  require EEx
  require Mix.Generator

  EEx.function_from_string(:def, :"quoted_from_string", "<%= a %>", [:a])
  Mix.Generator.embed_text(:"quoted", "Quoted")
  defdelegate count(x), to: QuotedNamesTarget, as: :"size"
  defdelegate dynamic_count(x), to: QuotedNamesTarget, as: :"#{:size}"

  def usage do
    {
      quoted_from_string(1),
      quoted_text(),
      count(1),
      dynamic_count(1)
    }
  end
end

defmodule QuotedNamesTarget do
  def size(x), do: x
  def count(x), do: x
  def dynamic_count(x), do: x
end
