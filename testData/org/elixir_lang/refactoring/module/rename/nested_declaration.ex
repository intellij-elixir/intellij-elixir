defmodule Outer do
  defmodule Inn<caret>er do
    def run, do: :ok
  end

  def call, do: Inner.run()
end

defmodule Consumer do
  def call, do: Outer.Inner.run()
end
