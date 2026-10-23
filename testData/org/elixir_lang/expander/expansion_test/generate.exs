# Extracts the programs Elixir's own `lib/elixir/test/elixir/kernel/expansion_test.exs` expands, at each Elixir version
# the plugin tests on, into `cases/`, one file per distinct program, and records each call site it leaves out and why in
# `skipped.txt`. The programs are inputs only: the differential test compiles each one with the leg's Elixir and compares
# the expander's answer with that compile's, never with what the Elixir test asserts of it.
#
# Run on the baseline leg, from anywhere, with a clone of github.com/elixir-lang/elixir that has every leg's tag:
# `mise exec -- elixir generate.exs <elixir clone>`. Elixir is needed only here, never by the tests.
#
# A site is a call in a `test` body to `expand/1,2`, `expand_with_version` or `expand_env(_, env)`, on a literal `quote`
# or on a variable whose latest `= quote` binding before the call, by source position, is one. A site is skipped, for
# the first of these that applies: its quote has an `unquote`; its env is not bare `__ENV__`; it is not a literal quote;
# it names `Kernel.ExpansionTarget`, the test file's own module, or calls one of that module's macros unqualified after
# importing it; or it is `expand_with_version`.
#
# A program is the quote body's text as the file has it, de-indented, wrapped in a function of a module. Only an
# `expand_env(_, __ENV__)` site expands in the test's own env, so only it keeps the test body's `alias`, `import` and
# `require` statements before it. A one-argument `expand` outside `assert_raise` and `assert_compile_error` leaves its
# unbound variables to the test's `if_undefined: :apply`, which a compile does not do, so each becomes a parameter of
# the function; any other site is compiled as written, and Elixir's error is what is compared.

defmodule ExpansionTestPrograms do
  @test_file "lib/elixir/test/elixir/kernel/expansion_test.exs"
  @call_names [:expand, :expand_env, :expand_with_version]
  @directives [:alias, :import, :require]
  @compile_timeout 60_000
  @max_param_rounds 5
  @slug_length 50

  @here __DIR__
  @cases Path.join(@here, "cases")
  @skipped Path.join(@here, "skipped.txt")
  @ci_versions Path.expand("../../../../../.github/ci-versions.json", @here)

  def main([clone]) do
    %{"baseline" => baseline, "additional" => additional} = @ci_versions |> File.read!() |> JSON.decode!() |> Map.fetch!("beam")
    legs = [baseline | additional] |> Enum.map(& &1["elixir"]) |> Enum.sort(&(Version.compare(&1, &2) != :gt))

    if System.version() != baseline["elixir"] do
      raise "run on the baseline leg, #{baseline["elixir"]}, not #{System.version()}"
    end

    per_leg = Map.new(legs, fn leg -> {leg, sites(clone, leg)} end)

    {found, _} =
      Enum.flat_map_reduce(legs, %{}, fn leg, parameters -> programs(leg, per_leg[leg], parameters) end)

    programs = collect(found, legs)

    write_cases(programs)
    write_skipped(per_leg, legs)
    report(per_leg, programs, legs)
  end

  # ---- the sites of one leg's copy of the file ----

  defp sites(clone, leg) do
    {source, 0} = System.cmd("git", ["-C", clone, "show", "v#{leg}:#{@test_file}"])
    lines = source |> String.split("\n") |> Enum.map(&String.to_charlist/1)
    {:ok, ast} = Code.string_to_quoted(source, columns: true, token_metadata: true, emit_warnings: false)
    macros = target_macros(ast)

    for {name, body} <- tests(ast), site <- test_sites(name, body, lines, macros), do: site
  end

  defp tests(ast) do
    {_, found} =
      Macro.prewalk(ast, [], fn
        {:test, _, [name | _] = args} = node, acc when is_binary(name) ->
          options = List.last(args)

          if is_list(options) and Keyword.keyword?(options) and Keyword.has_key?(options, :do),
            do: {node, [{name, Keyword.fetch!(options, :do)} | acc]},
            else: {node, acc}

        node, acc ->
          {node, acc}
      end)

    Enum.reverse(found)
  end

  defp test_sites(test, body, lines, macros) do
    directives = for {kind, meta, [_ | _]} = statement <- statements(body), kind in @directives, do: {position(meta), statement}

    quote_bindings =
      traverse(body, [], fn
        {:=, meta, [{name, _, context}, {:quote, _, _} = quoted]}, acc when is_atom(name) and is_atom(context) ->
          [{name, position(meta), quoted} | acc]

        _, acc ->
          acc
      end)

    in_assert_raise = positions_inside_assert_raise(body)
    imports_of_target = for {at, {:import, _, [module | _]}} <- directives, names_target?(module), do: at

    for site <- call_sites(body) do
      binding = latest_binding(quote_bindings, site)
      quoted = if match?({:quote, _, _}, site.quoted), do: site.quoted, else: binding
      kept_directives = if site.kind == :expand_env, do: for({at, d} <- directives, at < site.at, do: d), else: []

      reason =
        cond do
          quoted && unquotes?(quoted) -> "unquote"
          site.env != :default and not bare_env?(site.env) -> "env"
          quoted == nil -> "not-a-quote"
          names_target?(quoted) or Enum.any?(kept_directives, &names_target?/1) -> "expansion-target"
          Enum.any?(imports_of_target, &(&1 < site.at)) and macro_calls(quoted, macros) != [] -> "expansion-target"
          site.kind == :expand_with_version -> "expand-with-version"
          true -> nil
        end

      line = elem(site.at, 0)

      if reason do
        %{test: test, line: line, skipped: reason}
      else
        %{
          test: test,
          line: line,
          skipped: nil,
          program: quote_text(lines, quoted, site.closing),
          directives: Enum.map(kept_directives, &directive_text(lines, &1)),
          params?: site.kind == :expand and site.arguments == 0 and not MapSet.member?(in_assert_raise, site.at)
        }
      end
    end
  end

  defp statements({:__block__, _, statements}), do: statements
  defp statements(statement), do: [statement]

  # Every call to a name in @call_names, with the quote it is given, its env argument, and where it closes.
  defp call_sites(body) do
    traverse(body, [], fn
      {name, meta, [quoted | rest]}, acc when name in @call_names ->
        env = if name == :expand_env, do: List.first(rest, :missing), else: :default
        [%{kind: name, quoted: quoted, env: env, arguments: length(rest), at: position(meta), closing: closing(meta)} | acc]

      {name, _, []}, _ when name in @call_names ->
        raise "#{name}/0 has no quote argument, as when it is piped to, which this generator does not follow"

      _, acc ->
        acc
    end)
    |> Enum.sort_by(& &1.at)
  end

  # A binding of the quoted variable before the call, the latest by position.
  defp latest_binding(bindings, %{quoted: {name, _, context}, at: at}) when is_atom(name) and is_atom(context) do
    case bindings |> Enum.filter(fn {variable, bound, _} -> variable == name and bound < at end) |> Enum.sort() do
      [] -> nil
      sorted -> sorted |> List.last() |> elem(2)
    end
  end

  defp latest_binding(_, _), do: nil

  # The positions of the calls in the function an `assert_raise` or `assert_compile_error` is given: the test checks an
  # error there, so the program is compiled as written.
  defp positions_inside_assert_raise(body) do
    traverse(body, MapSet.new(), fn
      {name, _, arguments}, acc when name in [:assert_raise, :assert_compile_error] and is_list(arguments) ->
        for {:fn, _, _} = function <- arguments, site <- call_sites(function), reduce: acc do
          set -> MapSet.put(set, site.at)
        end

      _, acc ->
        acc
    end)
  end

  defp bare_env?({:__ENV__, _, context}) when is_atom(context), do: true
  defp bare_env?(_), do: false

  defp unquotes?(ast), do: any?(ast, fn {name, _, arguments} -> name in [:unquote, :unquote_splicing] and is_list(arguments); _ -> false end)

  defp names_target?(ast) do
    any?(ast, fn
      {:__aliases__, _, segments} -> List.last(segments) == :ExpansionTarget
      :"Elixir.Kernel.ExpansionTarget" -> true
      _ -> false
    end)
  end

  # `{name, arity}` of every macro the file's own `defmodule Kernel.ExpansionTarget` defines, a default arity included.
  defp target_macros(ast) do
    {_, heads} =
      Macro.prewalk(ast, [], fn
        {:defmodule, _, [{:__aliases__, _, [:Kernel, :ExpansionTarget]}, [do: body]]} = node, acc ->
          {node, acc ++ for({:defmacro, _, [head | _]} <- statements(body), do: head)}

        node, acc ->
          {node, acc}
      end)

    heads
    |> Enum.flat_map(fn head ->
      {name, _, arguments} = case head do {:when, _, [inner | _]} -> inner; inner -> inner end
      arguments = if is_list(arguments), do: arguments, else: []
      required = Enum.count(arguments, &(not match?({:\\, _, _}, &1)))

      for arity <- required..length(arguments), do: {name, arity}
    end)
    |> Enum.uniq()
  end

  defp macro_calls(ast, macros) do
    {_, found} =
      Macro.prewalk(ast, [], fn
        {name, _, arguments} = node, acc when is_atom(name) and is_list(arguments) ->
          {node, if({name, length(arguments)} in macros, do: [name | acc], else: acc)}

        {name, _, context} = node, acc when is_atom(name) and is_atom(context) ->
          {node, if({name, 0} in macros, do: [name | acc], else: acc)}

        node, acc ->
          {node, acc}
      end)

    found
  end

  # ---- the text of a quote and of a directive, as the file has it ----

  # The text of the quote's body. `quote do ... end` and `quote(do: x)` are read from the token metadata; a paren-less
  # `quote do: x` ends where the call it is an argument of closes.
  defp quote_text(lines, {:quote, meta, [[do: body]]}, closing) do
    text =
      cond do
        Keyword.has_key?(meta, :do) ->
          from = meta[:do][:line]
          to = meta[:end][:line]

          lines |> Enum.slice(from, max(to - from - 1, 0)) |> Enum.map_join("\n", &List.to_string/1) |> dedent()

        Keyword.has_key?(meta, :closing) ->
          open = advance(position(meta), "quote")
          "(" <> rest = slice(lines, open, position(meta[:closing]))
          keyword_value(open, "(", rest)

        closing != nil ->
          open = advance(position(meta), "quote")
          keyword_value(open, "", slice(lines, open, closing))

        true ->
          raise "no end to the quote at #{inspect(position(meta))}"
      end

    unless same_ast?(text, body), do: raise("the text sliced for the quote at #{inspect(position(meta))} is not its body:\n#{text}")

    text
  end

  defp quote_text(_, {:quote, meta, _}, _), do: raise("a quote with options at #{inspect(position(meta))}")

  # [open] is where [rest] starts less [prefix]; the body is the text after `do:`.
  defp keyword_value({line, column}, prefix, rest) do
    [lead] = Regex.run(~r/\A\s*do:\s*/, rest)
    start = advance({line, column}, prefix <> lead)
    text = rest |> binary_part(byte_size(lead), byte_size(rest) - byte_size(lead)) |> String.trim_trailing()

    dedent(String.duplicate(" ", elem(start, 1) - 1) <> text)
  end

  # A directive's text, from its first character to the end of its last line.
  defp directive_text(lines, {_, meta, _} = directive) do
    {line, column} = position(meta)
    last = last_line(directive)
    text = slice(lines, {line, column}, {last, length(Enum.at(lines, last - 1)) + 1})
    text = dedent(String.duplicate(" ", column - 1) <> text)

    unless same_ast?(text, directive), do: raise("the text sliced for the directive at #{line} is not it:\n#{text}")

    text
  end

  # [from, to) as 1-based lines and columns counted in code points.
  defp slice(lines, {first_line, first_column}, {last_line, last_column}) do
    Enum.map_join(first_line..last_line, "\n", fn line ->
      text = Enum.at(lines, line - 1)
      from = if line == first_line, do: first_column - 1, else: 0
      to = if line == last_line, do: last_column - 1, else: length(text)

      text |> Enum.slice(from, max(to - from, 0)) |> List.to_string()
    end)
  end

  defp advance({line, column}, text) do
    case String.split(text, "\n") do
      [one] -> {line, column + String.length(one)}
      parts -> {line + length(parts) - 1, String.length(List.last(parts)) + 1}
    end
  end

  defp dedent(text) do
    lines = String.split(text, "\n")

    indent =
      lines
      |> Enum.reject(&(String.trim(&1) == ""))
      |> Enum.map(&(byte_size(&1) - byte_size(String.trim_leading(&1, " "))))
      |> Enum.min(fn -> 0 end)

    Enum.map_join(lines, "\n", fn line ->
      if String.trim(line) == "", do: "", else: binary_part(line, indent, byte_size(line) - indent)
    end)
  end

  defp same_ast?(text, ast) do
    case Code.string_to_quoted(text) do
      {:ok, parsed} -> without_meta(parsed) == without_meta(ast)
      _ -> false
    end
  end

  defp without_meta(ast), do: Macro.prewalk(ast, fn {name, _, arguments} -> {name, [], arguments}; other -> other end)

  defp position(meta), do: {meta[:line] || 0, meta[:column] || 0}
  defp closing(meta), do: if(meta[:closing], do: position(meta[:closing]))

  defp last_line(ast) do
    {_, last} =
      Macro.prewalk(ast, 0, fn
        {_, meta, _} = node, acc when is_list(meta) ->
          lines = for {key, value} <- meta, key in [:line, :closing, :end, :end_of_expression], do: if(is_list(value), do: value[:line], else: value)
          {node, Enum.max([acc | lines])}

        node, acc ->
          {node, acc}
      end)

    last
  end

  # ---- traversal ----

  # Folds [fun] over [node] and everything under it, never into a `quote`'s body.
  defp traverse(node, acc, fun) do
    acc = fun.(node, acc)

    case node do
      {:quote, _, _} -> acc
      {form, _, arguments} when is_list(arguments) ->
        acc = if is_tuple(form), do: traverse(form, acc, fun), else: acc
        Enum.reduce(arguments, acc, &traverse(&1, &2, fun))

      {left, right} -> traverse(right, traverse(left, acc, fun), fun)
      list when is_list(list) -> Enum.reduce(list, acc, &traverse(&1, &2, fun))
      _ -> acc
    end
  end

  # Whether [fun] holds for any node of [ast], quote bodies included.
  defp any?(ast, fun) do
    {_, found} = Macro.prewalk(ast, false, fn node, acc -> {node, acc or fun.(node)} end)
    found
  end

  # ---- programs ----

  # The programs of [leg]'s kept sites, and [parameters], the parameters found so far by directives, program and kind of site.
  defp programs(leg, sites, parameters) do
    sites
    |> Enum.filter(&(&1.skipped == nil))
    |> Enum.map_reduce(parameters, fn site, parameters ->
      key = {site.directives, site.program, site.params?}
      params = Map.get_lazy(parameters, key, fn -> parameters(site) end)

      program = %{leg: leg, test: site.test, line: site.line, wrapped: wrap(site.directives, site.program, params), params: params}

      {program, Map.put(parameters, key, params)}
    end)
  end

  defp parameters(%{params?: false}), do: []

  defp parameters(site) do
    Enum.reduce_while(1..@max_param_rounds, [], fn _, params ->
      new = site.directives |> wrap(site.program, params) |> compile() |> undefined_variables() |> Kernel.--(params)
      if new == [], do: {:halt, params}, else: {:cont, params ++ new}
    end)
  end

  defp wrap(directives, program, params) do
    body = Enum.join(directives ++ [program], "\n")

    "defmodule Fixture do\n  def run(#{Enum.join(params, ", ")}) do\n#{indent(body, 4)}\n  end\nend\n"
  end

  defp indent(text, width) do
    padding = String.duplicate(" ", width)

    text |> String.split("\n") |> Enum.map_join("\n", fn "" -> ""; line -> padding <> line end)
  end

  # The error messages of compiling [wrapped] under a module name of its own, which a crash or a hang gives as one.
  defp compile(wrapped) do
    source = String.replace(wrapped, "defmodule Fixture do", "defmodule Fixture#{System.unique_integer([:positive])} do", global: false)
    parent = self()
    reference = make_ref()
    {pid, monitor} = spawn_monitor(fn -> send(parent, {reference, errors(source)}) end)

    receive do
      {^reference, errors} ->
        Process.demonitor(monitor, [:flush])
        errors

      {:DOWN, ^monitor, :process, ^pid, reason} ->
        ["the compiler crashed: #{inspect(reason)}"]
    after
      @compile_timeout ->
        Process.exit(pid, :kill)
        ["the compile timed out"]
    end
  end

  defp errors(source) do
    {result, diagnostics} =
      Code.with_diagnostics(fn ->
        try do
          for {module, _} <- Code.compile_string(source, "nofile"), do: purge(module)
          :ok
        rescue
          exception -> {:raised, Exception.message(exception)}
        end
      end)

    case {for(%{severity: :error, message: message} <- diagnostics, do: IO.chardata_to_string(message)), result} do
      {[], {:raised, message}} -> [message]
      {messages, _} -> messages
    end
  end

  defp purge(module) do
    :code.purge(module)
    :code.delete(module)
    :code.purge(module)
  end

  # The names of the variables the compile found undefined, in order of first use.
  defp undefined_variables(errors) do
    for message <- errors,
        [_, name] <- Regex.scan(~r/undefined variable "([^"]+)"/, message),
        Regex.match?(~r/\A[a-z_][a-zA-Z0-9_]*[?!]?\z/, name),
        uniq: true,
        do: name
  end

  # One entry per distinct wrapped program, with the legs it is on and where each leg has it.
  defp collect(programs, legs) do
    programs
    |> Enum.group_by(& &1.wrapped)
    |> Map.new(fn {wrapped, found} ->
      ordered = Enum.sort_by(found, &{Enum.find_index(legs, fn leg -> leg == &1.leg end), &1.line})
      first = hd(ordered)

      {wrapped,
       %{
         legs: ordered |> Enum.map(& &1.leg) |> Enum.uniq(),
         from: Enum.map(ordered, &"v#{&1.leg}:#{&1.line}"),
         test: first.test,
         params: first.params
       }}
    end)
  end

  # ---- output ----

  defp write_cases(programs) do
    File.rm_rf!(@cases)
    File.mkdir_p!(@cases)

    names =
      for {wrapped, program} <- programs do
        hash = :crypto.hash(:sha256, wrapped) |> Base.encode16(case: :lower) |> binary_part(0, 8)
        name = "#{slug(program.test)}__#{hash}.ex"
        header = header(program)

        {name, wrapped, header}
      end

    duplicates = names |> Enum.frequencies_by(&elem(&1, 0)) |> Enum.filter(fn {_, count} -> count > 1 end)
    if duplicates != [], do: raise("two programs share a file name: #{inspect(duplicates)}")

    for {name, wrapped, header} <- names, do: File.write!(Path.join(@cases, name), header <> wrapped)
  end

  defp header(program) do
    """
    # Generated by generate.exs beside this directory; do not edit.
    # Regenerate with `mise exec -- elixir generate.exs <elixir clone>` in testData/org/elixir_lang/expander/expansion_test.
    # legs: #{Enum.join(program.legs, " ")}
    # test: #{String.replace(program.test, ~r/\s+/, " ")}
    # from: #{Enum.join(program.from, " ")}
    """
  end

  defp slug(test) do
    test
    |> String.downcase()
    |> String.replace(~r/[^a-z0-9]+/, "_")
    |> String.slice(0, @slug_length)
    |> String.trim("_")
  end

  defp write_skipped(per_leg, legs) do
    lines =
      for leg <- legs, %{skipped: reason} = site <- per_leg[leg], reason != nil do
        "v#{leg}:#{site.line} #{reason} #{String.replace(site.test, ~r/\s+/, " ")}\n"
      end

    File.write!(@skipped, lines)
  end

  defp report(per_leg, programs, legs) do
    IO.puts("leg sites kept unique skipped params")

    for leg <- legs do
      sites = per_leg[leg]
      kept = Enum.count(sites, &(&1.skipped == nil))
      unique = Enum.count(programs, fn {_, program} -> leg in program.legs end)
      skipped = sites |> Enum.filter(& &1.skipped) |> Enum.frequencies_by(& &1.skipped) |> Enum.sort()
      params = Enum.count(programs, fn {_, program} -> leg in program.legs and program.params != [] end)

      IO.puts("#{leg} #{length(sites)} #{kept} #{unique} #{inspect(skipped)} #{params}")
    end

    IO.puts("union #{map_size(programs)}, on every leg #{Enum.count(programs, fn {_, p} -> length(p.legs) == length(legs) end)}")
  end
end

ExpansionTestPrograms.main(System.argv())
