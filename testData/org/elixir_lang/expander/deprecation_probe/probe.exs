# Compiles the source file it is given with the dispatch-time checks only: `:elixir_compiler.string/3` runs no
# module checker, and with the checker's cache absent the type checker traverses without checking deprecations,
# so what is written to standard error is what the compiler warns of while it expands. A source that raises at
# compile time has still warned of what came before.
[path] = System.argv()

# Elixir colours "warning:" when standard output is a terminal, and Windows takes the null device the test
# discards it to for one, which hides the prefix the test reads.
Application.put_env(:elixir, :ansi_enabled, false)

:erlang.erase(:elixir_checker_info)

try do
  :elixir_compiler.string(path |> File.read!() |> String.to_charlist(), "probe.ex", fn _, _ -> :ok end)
rescue
  _ -> :ok
end

# From 1.14, `Code.require_file/2` verifies the script's own compile with the cache erased above and raises when the
# script ends, so the script stops here and a nonzero exit means the probe itself failed.
System.halt(0)
