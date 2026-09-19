# Regenerates resources/org/elixir_lang/annotator/unicode_security/identifiers-<unicode version>.tsv: which code points
# above 127 Elixir's tokenizer accepts in an identifier, and their augmented script sets, for every Unicode version
# the elixir-lang/elixir corpora in .github/ci-versions.json were built with. Run it from the repository root:
#
#     mise exec -- elixir src/org/elixir_lang/annotator/unicode_security/generate.exs
#
# The tables are built the way lib/elixir/unicode/tokenizer.ex builds them, from that release's own Unicode files.
# verify.exs then checks each table against String.Tokenizer.tokenize/1 of every release that uses it, through
# `mise exec`, so every pair in the declaration must be installed in mise.
#
# It also writes gen/org/elixir_lang/annotator/unicode_security/IdentifierTables.kt, mapping each of those releases'
# major.minor to the Unicode version of its table, which is how the plugin picks the table for a language level.
#
# The table-building code is ported from lib/elixir/unicode/tokenizer.ex of elixir-lang/elixir, licensed under the
# Apache License, Version 2.0:
#
#     SPDX-FileCopyrightText: 2021 The Elixir Team
#     SPDX-FileCopyrightText: 2012 Plataformatec
unless Version.match?(System.version(), ">= 1.18.0") do
  raise "generate.exs needs Elixir 1.18 or later for JSON; this is #{System.version()}"
end

here = __DIR__
root = Path.expand("../../../../..", here)
declaration_path = List.first(System.argv()) || Path.join(root, ".github/ci-versions.json")
output_directory = Path.join(root, "resources/org/elixir_lang/annotator/unicode_security")
generated_directory = Path.join(root, "gen/org/elixir_lang/annotator/unicode_security")
verifier = Path.join(here, "verify.exs")
elixir_git = "https://github.com/elixir-lang/elixir"
files = ~w(UnicodeData.txt PropList.txt IdentifierType.txt Scripts.txt ScriptExtensions.txt PropertyValueAliases.txt)

beam = declaration_path |> File.read!() |> JSON.decode!() |> Map.fetch!("beam")

releases =
  [beam["baseline"] | beam["additional"] || []]
  |> Enum.flat_map(fn pair ->
    for %{"git" => ^elixir_git, "sha" => sha} <- pair["corpus"] || [], do: {pair["elixir"], pair["otp"], sha}
  end)
  |> Enum.uniq_by(fn {elixir, _, _} -> elixir end)
  |> Enum.filter(fn {elixir, _, _} -> Version.match?(elixir, ">= 1.14.0") end)
  |> Enum.sort_by(fn {elixir, _, _} -> elixir end, &(Version.compare(&1, &2) != :gt))

{:ok, _} = Application.ensure_all_started(:inets)
{:ok, _} = Application.ensure_all_started(:ssl)

download = fn sha, file ->
  url = ~c"https://raw.githubusercontent.com/elixir-lang/elixir/#{sha}/lib/elixir/unicode/#{file}"

  ssl = [
    verify: :verify_peer,
    cacerts: :public_key.cacerts_get(),
    customize_hostname_check: [match_fun: :public_key.pkix_verify_hostname_match_fun(:https)]
  ]

  case :httpc.request(:get, {url, []}, [ssl: ssl, timeout: 300_000], body_format: :binary) do
    {:ok, {{_, 200, _}, _, body}} -> body
    other -> raise "could not download #{url}: #{inspect(other)}"
  end
end

lines = fn content -> String.split(content, ["\r\n", "\n"], trim: true) end

range_to_codepoints = fn range ->
  case :binary.split(String.trim(range), "..") do
    [a] -> [String.to_integer(a, 16)]
    [a, b] -> Enum.to_list(String.to_integer(a, 16)..String.to_integer(b, 16))
  end
end

# Mirrors the table-building half of lib/elixir/unicode/tokenizer.ex, which is the same from 1.14 to 1.20.
build = fn sources ->
  {letter_uptitlecase, start, continue, _} =
    sources["UnicodeData.txt"]
    |> lines.()
    |> Enum.reduce({[], [], [], nil}, fn line, {letter_uptitlecase, start, continue, first} ->
      [codepoint, line] = :binary.split(line, ";")
      [name, line] = :binary.split(line, ";")
      [category, _] = :binary.split(line, ";")

      {codepoints, first} =
        case name do
          "<" <> _ when is_integer(first) ->
            last = String.to_integer(codepoint, 16)
            {Enum.to_list(last..first//-1), nil}

          "<" <> _ ->
            first = String.to_integer(codepoint, 16)
            {[first], first + 1}

          _ ->
            {[String.to_integer(codepoint, 16)], nil}
        end

      cond do
        category in ~w(Lu Lt) -> {codepoints ++ letter_uptitlecase, start, continue, first}
        category in ~w(Ll Lm Lo Nl) -> {letter_uptitlecase, codepoints ++ start, continue, first}
        category in ~w(Mn Mc Nd Pc) -> {letter_uptitlecase, start, codepoints ++ continue, first}
        true -> {letter_uptitlecase, start, continue, first}
      end
    end)

  {start, continue, patterns} =
    sources["PropList.txt"]
    |> lines.()
    |> Enum.reduce({start, continue, []}, fn line, acc ->
      [range | category] = :binary.split(line, ";")

      pos =
        case category do
          [" Other_ID_Start" <> _] -> 0
          [" Other_ID_Continue" <> _] -> 1
          [" Pattern_White_Space" <> _] -> 2
          [" Pattern_Syntax" <> _] -> 2
          _ -> -1
        end

      if pos >= 0, do: put_elem(acc, pos, range_to_codepoints.(range) ++ elem(acc, pos)), else: acc
    end)

  restricted =
    sources["IdentifierType.txt"]
    |> lines.()
    |> Enum.flat_map(fn line ->
      with [range, type_with_comments] <- :binary.split(line, ";"),
           [types, _comments] <- :binary.split(type_with_comments, "#"),
           types = String.split(types, " ", trim: true),
           false <- "Inclusion" in types or "Recommended" in types do
        range_to_codepoints.(range)
      else
        _ -> []
      end
    end)
    |> MapSet.new()

  keep = fn codepoints ->
    patterns = MapSet.new(patterns)
    codepoints |> Enum.reject(&(&1 <= 127 or MapSet.member?(patterns, &1) or MapSet.member?(restricted, &1))) |> MapSet.new()
  end

  upper = keep.(letter_uptitlecase)
  start = keep.(start)
  continue = keep.(continue)
  all = upper |> MapSet.union(start) |> MapSet.union(continue)

  script_aliases =
    sources["PropertyValueAliases.txt"]
    |> lines.()
    |> Enum.flat_map(fn line ->
      case String.split(line, [";", " "], trim: true) do
        ["sc", short, long | _] -> [{short, long}]
        _ -> []
      end
    end)
    |> Map.new()

  codepoints_to_scripts = fn file, aliases ->
    sources[file]
    |> lines.()
    |> Enum.flat_map(fn line ->
      with [range, scripts_with_comments] <- :binary.split(line, ";"),
           [scripts, _comments] <- :binary.split(scripts_with_comments, "#"),
           scripts = scripts |> String.split(" ", trim: true) |> Enum.map(&Map.get(aliases, &1, &1)) do
        for codepoint <- range_to_codepoints.(range),
            MapSet.member?(all, codepoint) and "Common" not in scripts and "Inherited" not in scripts,
            do: {codepoint, scripts}
      else
        _ -> []
      end
    end)
  end

  all_codepoints_to_scripts =
    codepoints_to_scripts.("Scripts.txt", %{}) ++ codepoints_to_scripts.("ScriptExtensions.txt", script_aliases)

  all_scripts =
    all_codepoints_to_scripts
    |> Enum.flat_map(&elem(&1, 1))
    |> Enum.uniq()
    |> then(&(["Han with Bopomofo", "Japanese", "Korean"] ++ &1))

  sorted_scripts = ["Latin" | all_scripts |> List.delete("Latin") |> Enum.sort()]
  index = sorted_scripts |> Enum.with_index() |> Map.new()
  script_mask = fn script -> MapSet.new([Map.fetch!(index, script)]) end

  augmentation_rules = %{
    "Han" => ["Han with Bopomofo", "Japanese", "Korean"],
    "Hiragana" => ["Japanese"],
    "Katakana" => ["Japanese"],
    "Hangul" => ["Korean"],
    "Bopomofo" => ["Han with Bopomofo"]
  }

  masks =
    Map.new(sorted_scripts, fn script ->
      additions = Map.get(augmentation_rules, script, [])
      {script, Enum.reduce(additions, script_mask.(script), &MapSet.union(script_mask.(&1), &2))}
    end)

  top = :top

  codepoint_masks =
    for {codepoint, scripts} <- all_codepoints_to_scripts, into: %{} do
      {codepoint, scripts |> Enum.map(&Map.fetch!(masks, &1)) |> Enum.reduce(MapSet.new(), &MapSet.union/2)}
    end

  # The µ => μ normalization: the union with Common µ makes μ's script set the top.
  union = fn
    :top, _ -> top
    _, :top -> top
    left, right -> MapSet.union(left, right)
  end

  codepoint_masks = Map.put(codepoint_masks, ?μ, union.(Map.get(codepoint_masks, ?µ, top), Map.get(codepoint_masks, ?μ, top)))

  rows =
    all
    |> Enum.sort()
    |> Enum.map(fn codepoint ->
      classes =
        [{upper, "u"}, {start, "s"}, {continue, "c"}]
        |> Enum.filter(fn {set, _} -> MapSet.member?(set, codepoint) end)
        |> Enum.map_join(fn {_, letter} -> letter end)

      scriptset =
        case Map.get(codepoint_masks, codepoint, top) do
          :top -> "*"
          set -> set |> Enum.sort() |> Enum.join(",")
        end

      {codepoint, classes, scriptset}
    end)
    |> Enum.chunk_while(
      nil,
      fn
        {codepoint, classes, scriptset}, {first, last, classes, scriptset} when codepoint == last + 1 ->
          {:cont, {first, codepoint, classes, scriptset}}

        {codepoint, classes, scriptset}, nil ->
          {:cont, {codepoint, codepoint, classes, scriptset}}

        {codepoint, classes, scriptset}, range ->
          {:cont, range, {codepoint, codepoint, classes, scriptset}}
      end,
      fn
        nil -> {:cont, nil}
        range -> {:cont, range, nil}
      end
    )

  {sorted_scripts, rows}
end

tables =
  releases
  |> Enum.map(fn {elixir, otp, sha} ->
    sources = Map.new(files, &{&1, download.(sha, &1)})
    [_, unicode] = Regex.run(~r/^# Scripts-(\d+\.\d+)\.\d+\.txt/, sources["Scripts.txt"])
    {unicode, {elixir, otp, sha, sources}}
  end)
  |> Enum.group_by(&elem(&1, 0), &elem(&1, 1))
  |> Enum.sort_by(fn {unicode, _} -> Version.parse!(unicode <> ".0") end, &(Version.compare(&1, &2) != :gt))

File.mkdir_p!(output_directory)

for {unicode, [{_elixir, _otp, sha, sources} | _] = users} <- tables do
  if users |> Enum.map(fn {_, _, _, sources} -> sources end) |> Enum.uniq() |> length() > 1 do
    raise "releases built with Unicode #{unicode} ship different Unicode files: #{Enum.map_join(users, ", ", &elem(&1, 0))}"
  end

  {scripts, rows} = build.(sources)
  path = Path.join(output_directory, "identifiers-#{unicode}.tsv")

  File.write!(path, [
    "# Generated by src/org/elixir_lang/annotator/unicode_security/generate.exs from #{elixir_git}/tree/#{sha}/lib/elixir/unicode\n",
    "# for Elixir #{Enum.map_join(users, ", ", &elem(&1, 0))}. Do not edit.\n",
    "#\n",
    "#     <first code point>\\t<last code point>\\t<u upper, s start, c continue>\\t<script set: indexes into scripts, or * for all>\n",
    Enum.join(["scripts" | scripts], "\t"),
    "\n",
    Enum.map(rows, fn {first, last, classes, scriptset} ->
      [Integer.to_string(first, 16), ?\t, Integer.to_string(last, 16), ?\t, classes, ?\t, scriptset, ?\n]
    end)
  ])

  IO.puts("Unicode #{unicode}: #{length(rows)} ranges, #{length(scripts)} scripts -> #{Path.relative_to(path, root)}")

  for {elixir, otp, _sha, _sources} <- users do
    case System.cmd("mise", ["exec", "elixir@#{elixir}", "erlang@#{otp}", "--", "elixir", verifier, path], stderr_to_stdout: true) do
      {log, 0} -> IO.write("  Elixir #{elixir}: " <> log)
      {log, status} -> raise "verify.exs under Elixir #{elixir} / OTP #{otp} exited #{status}:\n#{log}"
    end
  end
end

releases =
  for {unicode, users} <- tables, {elixir, _otp, _sha, _sources} <- users do
    version = Version.parse!(elixir)
    {{version.major, version.minor}, unicode}
  end
  |> Enum.uniq()
  |> Enum.sort()

File.mkdir_p!(generated_directory)

File.write!(Path.join(generated_directory, "IdentifierTables.kt"), [
  "// Generated by src/org/elixir_lang/annotator/unicode_security/generate.exs. Do not edit.\n",
  "package org.elixir_lang.annotator.unicode_security\n",
  "\n",
  "/** Each Elixir major and minor that checks identifiers, oldest first, with the Unicode version of its table. */\n",
  "internal val IDENTIFIER_TABLE_UNICODE_VERSIONS: List<Pair<Pair<Int, Int>, String>> = listOf(\n",
  for {{major, minor}, unicode} <- releases do
    "    (#{major} to #{minor}) to \"#{unicode}\",\n"
  end,
  ")\n"
])

copyrights = fn sources ->
  sources
  |> Map.values()
  |> Enum.flat_map(&Regex.scan(~r/^# (© \d{4} Unicode®, Inc\.)\r?$/mu, &1, capture: :all_but_first))
  |> List.flatten()
  |> Enum.uniq()
  |> Enum.join(", ")
end

unicode_license = """
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2026 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.
"""

File.write!(Path.join(output_directory, "NOTICE.md"), [
  """
  # Unicode data for Elixir's identifier rules

  Each `identifiers-<Unicode version>.tsv` beside this file lists the code points above 127 that Elixir's tokenizer
  accepts in an identifier, with their script sets, for one Unicode version. They were derived by
  `src/org/elixir_lang/annotator/unicode_security/generate.exs`, which also writes this file, from the Unicode
  Character Database files in `lib/elixir/unicode/` of
  #{elixir_git},
  with a port of the table-building part of that repository's `lib/elixir/unicode/tokenizer.ex`. That file is
  copyright 2021 The Elixir Team and 2012 Plataformatec, and licensed under the Apache License, Version 2.0, the
  licence of this repository (`LICENSE`). The Unicode data is licensed under the Unicode License v3 below.

  ## Sources

  | Unicode | Elixir | Commit | Copyright |
  |---|---|---|---|
  """,
  for {unicode, [{_, _, sha, sources} | _] = users} <- tables do
    "| #{unicode} | #{Enum.map_join(users, ", ", &elem(&1, 0))} | [`#{sha}`](#{elixir_git}/tree/#{sha}) | #{copyrights.(sources)} |\n"
  end,
  "\n## Unicode License v3\n\n",
  unicode_license |> String.trim_trailing() |> String.split("\n") |> Enum.map_join("\n", &String.trim_trailing("    " <> &1)),
  "\n"
])
