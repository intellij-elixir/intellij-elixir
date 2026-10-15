defmodule Progress.Outer do
  defmodule Inner do
    require Progress.Macros
    Progress.Macros.wrap(1)
  end
end
