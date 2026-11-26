defmodule Foo.Bar do
  @type t :: integer()
end

defmodule Aliased do
  alias Foo.Bar
  @type t :: Bar.t()
  @type nope :: Bar.nope()
end
