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

defmodule EctoFixture.Money do
  use Ecto.Type

  def type, do: :integer
  def cast(value), do: {:ok, value}
  def load(value), do: {:ok, value}
  def dump(value), do: {:ok, value}
end

defmodule EctoFixture.Tag do
  use Ecto.Schema

  @primary_key false
  @derive Inspect

  embedded_schema do
    field :name, :string
  end
end

defmodule EctoFixture.Author do
  use Ecto.Schema

  @primary_key {:id, :binary_id, autogenerate: true}
  @foreign_key_type :binary_id
  @derive {Inspect, only: [:id]}

  schema "authors" do
    field :status, Ecto.Enum, values: [:active, :retired]
    field :aliases, {:array, :string}
    field :draft, :string, virtual: true
    field :display_name, :string, source: :name
    field :royalties, EctoFixture.Money
    has_one :pinned, EctoFixture.Comment, foreign_key: :post_id
    has_many :posts, EctoFixture.Post, foreign_key: :category_id
    has_many :comments, through: [:posts, :comments]
    many_to_many :reviews, EctoFixture.Comment, join_through: "author_reviews"
    embeds_one :label, EctoFixture.Tag
    embeds_many :labels, EctoFixture.Tag
    timestamps()
  end
end

defmodule EctoFixture.Book do
  use Ecto.Schema

  schema "books" do
    field :title, :string
    field :isbn, :string, redact: true
    belongs_to :author, EctoFixture.Author
  end
end
