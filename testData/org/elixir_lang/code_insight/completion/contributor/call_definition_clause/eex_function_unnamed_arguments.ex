defmodule EExFunctionUnnamedArguments do
  require EEx

  @extra :b
  EEx.function_from_string(:def, :unnamed_arguments_sample, "<%= a %>", [:a, @extra, :"#{:c}"])

  def usage do
    unnamed<caret>
  end
end
