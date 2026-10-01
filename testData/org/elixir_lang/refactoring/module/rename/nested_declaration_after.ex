defmodule Outer do
  defmodule Renamed do
    def run, do: :ok
  end

  def call, do: Renamed.run()
end

defmodule Consumer do
  def call, do: Outer.Renamed.run()
end
