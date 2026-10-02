defmodule Outer do
  defmodule Renamee do
    def hello, do: :world
  end

  def call, do: Renamee.hello()
end

defmodule Client do
  def call, do: Outer.Renamee.hello()
end
