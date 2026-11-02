defmodule EctoFixture.CompileTime do
  require EctoFixture.Probe

  @can_await EctoFixture.Probe.can_await()

  def can_await, do: @can_await
end
