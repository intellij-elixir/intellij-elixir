defmodule MyApp.FakeReads do
  def fake_read, do: MyApp.Fake.get_env(:my_app, :fake_read)
  def fake_app, do: MyApp.Fake.get_env(:app_fake, :anything)
end
