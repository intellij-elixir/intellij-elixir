defmodule Heads do
  require EEx
  require Mix.Generator

  def plain(x), do: x
  def plain_guarded(x) when is_integer(x), do: x
  def unquote(:literal)(x), do: x
  def unquote(:"quoted literal")(x), do: x
  def unquote(name)(x), do: x
  def unquote(true)(x), do: x
  def left <~> right, do: {left, right}
  def café(x), do: x
  def plain?(x), do: x
  def plain!(x), do: x
  defp private(x, y \\ 1), do: {x, y}
  defmacro macro(x), do: x
  @callback cb(term) :: term
  @macrocallback mcb(term) :: term
  @callback unquote(:cb_literal)(term) :: term
  defdelegate del(x, y \\ 1), to: Target
  defdelegate unquote(:del_literal)(x), to: Target
  defdelegate [del_list(x), del_list(x, y)], to: Target
  defexception [:message]
  EEx.function_from_string(:def, :eex_plain, "")
  EEx.function_from_string(:defp, :eex_private, "<%= a %>", [:a, :b])
  EEx.function_from_string(:def, :eex_unknown_args, "", @args)
  EEx.function_from_string(:def, :"eex quoted", "")
  EEx.function_from_string(:def, :'eex single', "")
  EEx.function_from_string(:def, :"eex_#{:interp}", "")
  EEx.function_from_string(@kind, :eex_kind, "<%= a %>", [:a])
  Mix.Generator.embed_template(:embed_plain, "")
  Mix.Generator.embed_text(:embed_plain, "")
  Mix.Generator.embed_text(:"embed quoted", "")
end
