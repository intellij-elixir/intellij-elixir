if System.get_env("NOPE"), do: Code.require_file("h.ex", __DIR__)

defmodule R do
  {v, _} = Code.eval_string(~S|is_list(Code.get_compiler_option(:parser_options))|, [], __ENV__)
  @v v
  def r, do: @v
end
