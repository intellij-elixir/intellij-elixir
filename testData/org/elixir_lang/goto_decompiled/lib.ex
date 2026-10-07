defmodule GotoRelated.Lib do
  def run(list), do: list

  defdelegate delegated(list), to: :erlang, as: :hd

  require EEx
  EEx.function_from_string(:def, :page, "<%= a %><%= b %>", [:a, :b])
  EEx.function_from_string(:def, :banner, "<%= name %>", [:name])

  require Mix.Generator
  Mix.Generator.embed_template(:greeting, "hello <%= @name %>")
  Mix.Generator.embed_text(:note, "note")

  # The compiler drops a private function nothing calls.
  def greet(assigns), do: greeting_template(assigns)
  def note, do: note_text()
end

defmodule GotoRelated.Other do
  def lonely(list), do: list
end

defmodule GotoRelated.Error do
  defexception [:message]
end

defmodule GotoRelated.Behaviour do
  @callback perform(term) :: term
end

defmodule GotoRelated.Struct do
  defstruct [:value]
end

defimpl String.Chars, for: GotoRelated.Struct do
  def to_string(struct), do: struct.value
end
