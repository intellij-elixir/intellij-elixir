defmodule RouterFixture.Asker do
  alias RouterFixture.HelpersRouter.Helpers, as: Routes

  def users(conn), do: Routes.user_path(conn, :index)
  def stopped(conn), do: RouterFixture.HelpersOn50.Helpers.p0_path(conn, :index, 1)
  def off(conn), do: RouterFixture.HelpersOff50.Helpers.p0_path(conn, :index, 1)
end
