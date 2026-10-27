defmodule EctoFixture.Repo do
  use Ecto.Repo, otp_app: :ecto_fixture, adapter: Ecto.Adapters.Postgres
end
