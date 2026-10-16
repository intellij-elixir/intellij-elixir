defmodule Unquotes do
  require EEx
  require Mix.Generator

  def plain(x), do: x
  def plain_guarded(x) when is_integer(x), do: x
  def unquote(:literal)(x), do: x
  def unquote(:"quoted literal")(x), do: x
  def unquote(name)(x), do: x
  def left <~> right, do: {left, right}
  def café(x), do: x
  def plain?(x), do: x
  def plain!(x), do: x
  @callback cb(term) :: term
  @macrocallback mcb(term) :: term
  @callback unquote(:cb_literal)(term) :: term
  defdelegate del(x), to: Target
  defdelegate unquote(:del_literal)(x), to: Target
  defdelegate del_as(x), to: Target, as: :"target name"
  defexception [:message]
  EEx.function_from_string(:def, :eex_plain, "")
  EEx.function_from_string(:def, :"eex quoted", "")
  EEx.function_from_string(:def, :'eex single', "")
  EEx.function_from_string(:def, :"eex_#{:interp}", "")
  Mix.Generator.embed_text(:embed_plain, "")
  Mix.Generator.embed_text(:"embed quoted", "")
  def use_literal, do: literal(1)
  def use_quoted_literal, do: unquote(:"quoted literal")(1)
  def use_del_literal, do: unquote(:del_literal)(1)
end
