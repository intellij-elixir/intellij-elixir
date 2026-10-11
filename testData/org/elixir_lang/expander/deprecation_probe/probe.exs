# Compiles the source files it is given, in order, with the dispatch-time checks only: `:elixir_compiler.string/3`
# runs no module checker, and with the checker's cache absent the type checker traverses without checking
# deprecations, so what is written to standard error is what the compiler warns of while it expands. A file's modules
# are loaded once it compiles, so a later file reads them. A source that raises at compile time has still warned of
# what came before.
paths = System.argv()

# Elixir colours "warning:" when standard output is a terminal, and Windows takes the null device the test
# discards it to for one, which hides the prefix the test reads.
Application.put_env(:elixir, :ansi_enabled, false)

:erlang.erase(:elixir_checker_info)

for path <- paths do
  try do
    :elixir_compiler.string(path |> File.read!() |> String.to_charlist(), Path.basename(path), fn _, _ -> :ok end)
  rescue
    _ -> :ok
  end
end

# From 1.14, `Code.require_file/2` verifies the script's own compile with the cache erased above and raises when the
# script ends, so the script stops here and a nonzero exit means the probe itself failed.
System.halt(0)
