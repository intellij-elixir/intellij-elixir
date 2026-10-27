defmodule EctoFixture.Queries do
  import Ecto.Query
  alias EctoFixture.{Comment, Post}

  def q_from(id), do: from(p in Post, where: p.id == ^id, select: p)
  def q_pipe(id), do: Post |> where([p], p.id == ^id)
  def q_join(q), do: join(q, :inner, [p], c in assoc(p, :comments), as: :comments)
  def q_named(q), do: where(q, [comments: c], c.id > 0)
  def q_rest(q), do: where(q, [p, ...], p.id > 0)
  def q_fragment, do: from(p in Post, where: fragment("lower(?)", p.title) == "x")
  def q_window, do: from(p in Post, windows: [w: [partition_by: p.category_id]], select: over(avg(p.price), :w))
  def q_parens(t), do: from(p in Post, where: p.title() == ^t)
  def q_no_on(q), do: join(q, :inner, [p], c in Comment)
end
