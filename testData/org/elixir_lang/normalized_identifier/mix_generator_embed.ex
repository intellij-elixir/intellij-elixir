defmodule NormalizedMixGeneratorEmbed do
  require Mix.Generator

  Mix.Generator.embed_template(:snoć, "<%= @a %>")

  def caller, do: snoć_template<caret>(a: 1)
end
