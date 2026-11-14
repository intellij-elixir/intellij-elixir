# Usage: elixir judge.exs <rows dir> <work dir>: each row read by Config.Reader.read!/2 under X unset, "y" and "".
# Elixir as the test oracle only: the reads BooleanRowsHeavyTest's Elixir answers come from.
[rows, work] = System.argv()
Code.compiler_options(ignore_module_conflict: true)
config = Path.join([work, "config", "config.exs"])
File.mkdir_p!(Path.dirname(config))

for file <- Enum.sort(File.ls!(rows)) do
  File.write!(config, "import Config\n\n" <> File.read!(Path.join(rows, file)))

  for {label, x} <- [{"unset", nil}, {"y", "y"}, {"empty", ""}] do
    if x, do: System.put_env("X", x), else: System.delete_env("X")
    System.delete_env("Y")

    result =
      try do
        inspect(Keyword.get(Config.Reader.read!(config, env: :dev), :app), limit: :infinity, charlists: :as_lists)
      rescue
        e -> "raised " <> inspect(e.__struct__)
      catch
        kind, reason -> "caught #{inspect(kind)} #{inspect(reason)}"
      end

    IO.puts("#{Path.rootname(file)}\t#{label}\t#{result}")
  end
end
