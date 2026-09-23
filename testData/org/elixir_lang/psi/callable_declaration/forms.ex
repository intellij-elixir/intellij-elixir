defmodule Forms do
  require EEx
  require Mix.Generator

  def public_function(a), do: a
  defp private_function(a, b \\ 1), do: {a, b}
  defmacro public_macro(a), do: a
  defmacrop private_macro(a), do: a
  defguard is_small(a) when a < 10
  defguardp is_large(a) when a > 10
  @callback function_callback(integer) :: atom
  @macrocallback macro_callback(term) :: Macro.t()
  defdelegate delegated(a, b), to: Target
  defexception [:message]
  EEx.function_from_string(:def, :from_string, "<%= a %>", [:a])
  EEx.function_from_file(:defp, :from_file, "sample.eex")
  Mix.Generator.embed_template(:log, "Log")
  Mix.Generator.embed_text(:error, "Error")
  EEx.function_from_string(:def, :"quoted_from_string", "")
  EEx.function_from_string(:def, :"#{:interpolated}_from_string", "")
  Mix.Generator.embed_text(:"quoted", "Quoted")

  import Enum
  alias Target, as: T
  use Target
  for x <- [1], do: x
  if true, do: :ok
  quote do: 1
  @moduledoc false
end
