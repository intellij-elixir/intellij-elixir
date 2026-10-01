defmodule Outer do
  defmodule Fresh do
    def hello, do: :world
  end

  def call, do: Fresh.hello()
end

defmodule Client do
  def call, do: Outer.Fresh.hello()
end
