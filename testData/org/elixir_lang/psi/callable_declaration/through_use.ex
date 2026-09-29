defmodule Using do
  defmacro __using__(_) do
    quote do
      require EEx
      require Mix.Generator

      def clause(a), do: a
      defdelegate delegated(a, b), to: Unresolvable
      defexception [:message]
      EEx.function_from_string(:def, :from_string, "<%= a %>", [:a])
      Mix.Generator.embed_template(:log, "Log")
      Mix.Generator.embed_text(:error, "Error")
    end
  end
end

defmodule ThroughUse do
  use Using

  def usage do
    {
      clause(1),
      delegated(1, 2),
      exception(message: "x"),
      message(%{}),
      from_string(1),
      log_template(a: 1),
      error_text()
    }
  end
end
