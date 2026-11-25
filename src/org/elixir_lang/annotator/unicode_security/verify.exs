# Checks one identifiers-<unicode version>.tsv against the running Elixir's own String.Tokenizer.tokenize/1: every code
# point's start and continue classes, and, from 1.14, for one start code point per script set, whether each pair of
# them may form an identifier. generate.exs runs it under every release that uses the table; it takes the table's path.
[path] = System.argv()

[scripts_line | rows] =
  path |> File.read!() |> String.split("\n", trim: true) |> Enum.reject(&String.starts_with?(&1, "#"))

["scripts" | scripts] = String.split(scripts_line, "\t")
index = scripts |> Enum.with_index() |> Map.new()
all = MapSet.new(0..(length(scripts) - 1))

entries =
  for row <- rows,
      [first, last, classes, scriptset] = String.split(row, "\t"),
      codepoint <- String.to_integer(first, 16)..String.to_integer(last, 16),
      into: %{} do
    set = if scriptset == "*", do: all, else: scriptset |> String.split(",") |> Enum.map(&String.to_integer/1) |> MapSet.new()
    {codepoint, {classes, set}}
  end

accepted? = fn codepoints ->
  case String.Tokenizer.tokenize(codepoints) do
    # 1.13 leaves over a code point that continues no identifier.
    {kind, _, rest, _, _, _} when kind in [:identifier, :atom, :alias] -> rest == []
    {:error, _} -> false
    other -> raise "unexpected #{inspect(other)} for #{inspect(codepoints)}"
  end
end

before_1_14? = Version.match?(System.version(), "< 1.14.0")

# Before 1.14 an identifier not in NFC is rejected, where later releases normalize it.
nfc? = &(not before_1_14? or :unicode.characters_to_nfc_list(&1) == &1)

mismatches =
  Enum.filter(Enum.concat(128..0xD7FF, 0xE000..0x10FFFF), fn codepoint ->
    {classes, _} = Map.get(entries, codepoint, {"", nil})
    start = (String.contains?(classes, ["u", "s"]) or codepoint == ?µ) and nfc?.([codepoint])
    anywhere = (classes != "" or codepoint == ?µ) and nfc?.([?_, codepoint])
    start != accepted?.([codepoint]) or anywhere != accepted?.([?_, codepoint])
  end)

unless mismatches == [] do
  raise "#{length(mismatches)} code points classified differently, first: #{mismatches |> Enum.take(10) |> Enum.map_join(", ", &"U+#{Integer.to_string(&1, 16)}")}"
end

# Before 1.14 no identifier's scripts are checked, so the classes are all there is to check.
if before_1_14? do
  IO.puts("#{map_size(entries)} code points agree")
  System.halt(0)
end

# Below 1.18 an identifier whose script sets do not intersect is still accepted when every character shares a script
# with Latin plus one of Japanese, Han with Bopomofo or Korean.
highly_restrictive =
  if Version.match?(System.version(), "< 1.18.0") do
    for script <- ["Japanese", "Han with Bopomofo", "Korean"], do: MapSet.new([index["Latin"], index[script]])
  else
    []
  end

representatives =
  entries
  |> Enum.filter(fn {codepoint, {classes, _}} -> String.contains?(classes, ["u", "s"]) and codepoint != ?μ end)
  |> Enum.sort()
  |> Enum.uniq_by(fn {_, {_, set}} -> set end)

pairs =
  for {left, {_, left_set}} <- representatives,
      {right, {_, right_set}} <- Enum.sort(entries),
      right != ?μ,
      :unicode.characters_to_nfc_list([left, right]) == [left, right],
      do: {left, left_set, right, right_set}

pair_mismatches =
  Enum.filter(pairs, fn {left, left_set, right, right_set} ->
    expected =
      not MapSet.disjoint?(left_set, right_set) or
        Enum.any?(highly_restrictive, &(not MapSet.disjoint?(&1, left_set) and not MapSet.disjoint?(&1, right_set)))

    expected != accepted?.([left, right])
  end)

unless pair_mismatches == [] do
  raise "#{length(pair_mismatches)} script set pairs judged differently, first: #{pair_mismatches |> Enum.take(5) |> Enum.map_join(", ", fn {l, _, r, _} -> "U+#{Integer.to_string(l, 16)} U+#{Integer.to_string(r, 16)}" end)}"
end

disjoint = Enum.count(pairs, fn {_, left_set, _, right_set} -> MapSet.disjoint?(left_set, right_set) end)
IO.puts("#{map_size(entries)} code points and #{length(pairs)} script set pairs (#{disjoint} disjoint) agree")
