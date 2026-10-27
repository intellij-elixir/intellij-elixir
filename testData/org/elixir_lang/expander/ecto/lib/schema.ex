defmodule EctoFixture.Comment do
  use Ecto.Schema

  schema "comments" do
    field :body, :string
    field :post_id, :integer
  end
end

defmodule EctoFixture.Post do
  use Ecto.Schema

  schema "posts" do
    field :title, :string
    field :price, :decimal
    field :category_id, :integer
    has_many :comments, EctoFixture.Comment
  end
end
