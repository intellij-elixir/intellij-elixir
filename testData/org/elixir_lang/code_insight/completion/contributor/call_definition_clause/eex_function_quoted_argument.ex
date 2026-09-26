defmodule EExFunctionQuotedArgument do
  require EEx

  EEx.function_from_string(:def, :quoted_argument_sample, "<%= a %><%= b %>", [:a, :"b"])

  def usage do
    quoted<caret>
  end
end
