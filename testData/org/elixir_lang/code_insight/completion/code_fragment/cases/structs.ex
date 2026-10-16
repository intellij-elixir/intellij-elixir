defmodule S do
  defstruct [:a]
  @s S
  @type t :: %S{}
  @type u ::%S{}
  def f(s, x) do
    [%S{}, %S{a: 1}, %@s{}, %s{}, %__MODULE__{}, %x.S{}, %Hello.World{}, %__MODULE__.S{}]
  end
end
