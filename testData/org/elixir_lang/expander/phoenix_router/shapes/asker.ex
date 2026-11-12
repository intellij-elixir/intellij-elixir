defmodule RouterFixture.Asker do
  alias RouterFixture.HelpersRouter.Helpers, as: Routes

  def users(conn), do: Routes.user_path(conn, :index)
end
