# Compiled to ebin/Elixir.foo.beam with Elixir 1.20.4 on OTP 29:
#
#     mise exec elixir@1.20.4-otp-29 erlang@29 -- elixirc -o ebin elixir_foo.ex
#
# `Elixir.foo` starts like an alias but is not one, so Elixir writes it `:"Elixir.foo"`.
defmodule :"Elixir.foo" do
  @doc "Says hello."
  def hello, do: :world
end
