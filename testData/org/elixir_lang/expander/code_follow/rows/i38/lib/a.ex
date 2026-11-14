defmodule A do
  @v (try do
        {v, _} = Code.eval_string(~s|String.to_integer(System.get_env("NO_SUCH_VAR_I38")); :ok|)
        v
      rescue
        _ -> :rescued
      end)
  def r, do: @v
end
