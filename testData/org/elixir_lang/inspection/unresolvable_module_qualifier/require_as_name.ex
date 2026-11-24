defmodule RequireAsTest do
  require MyNamespace.Referenced, as: M

  def run, do: M.changeset(%{})
end
