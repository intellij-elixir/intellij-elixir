defmodule CensusDep do
  defmacro __using__(_opts) do
    quote do
      def built, do: :built
    end
  end

  defmacro wrap(expr) do
    quote do
      if unquote(expr), do: :yes, else: :no
    end
  end
end
