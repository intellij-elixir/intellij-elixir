defmodule Documented do
  @moduledoc """
  Specifies a conversion.

      iex> @spec convert(Other.missing()) :: :ok
  """

  def convert(_value), do: :ok
end
