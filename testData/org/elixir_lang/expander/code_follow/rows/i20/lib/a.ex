defmodule A do
  Code.eval_quoted(
    quote do
      cond do
        (receive do
           after
             0 ->
               Module.create(Made, quote(do: def(f, do: :ok)), file: "made.ex")
               :x
         end) == :y ->
          :erlang.error(:no)

        true ->
          :ok
      end
    end,
    [],
    __ENV__
  )

  @v {Code.ensure_loaded?(Made), File.cwd!() == Path.expand("..", __DIR__)}
  def r, do: @v
end
