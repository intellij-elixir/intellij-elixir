# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :reserved_type do

  # Types

  # `fn` is a reserved Elixir word and cannot name an Elixir type (`@type` takes no `unquote` fragment); the Erlang type is preserved as a comment:
  #   @type unquote(:fn) :: function()

  # `in` is a reserved Elixir word and cannot name an Elixir type (`@type` takes no `unquote` fragment); the Erlang type is preserved as a comment:
  #   @type unquote(:in)(t) :: [t]

  # `nil` is a reserved Elixir word and cannot name an Elixir type (`@type` takes no `unquote` fragment); the Erlang type is preserved as a comment:
  #   @type unquote(:nil) :: []

  # Functions

  @spec make() :: {[], unquote(:fn)(), unquote(:in)(integer())}
  def make() do
    {[], fn () ->
        :ok
    end, []}
  end

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  # Private Functions

  defp unquote(:"-make/0-fun-0-")() do
    # body not decompiled
  end
end
