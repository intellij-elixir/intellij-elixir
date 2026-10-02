# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :idna do

  # Private Types

  @typep idna_flags :: [({:uts46, boolean()} | {:std3_rules, boolean()} | {:transitional, boolean()})]

  # Functions

  def alabel(label0) do
    label = case :lists.all(fn c ->
        :idna_ucs.is_ascii(c)
    end, label0) do
      true ->
        _ = try do
          ulabel(label0)
        catch
          {_, error, _} ->
            errorMsg = error_msg('The label ~p  is not a valid A-label: ulabel error=~p', [label0, error])
            :erlang.exit({:bad_label, {:alabel, errorMsg}})
        end
        :ok = check_label_length(label0)
        label0
      false ->
        :ok = check_label(label0)
        'xn--' ++ :punycode.encode(label0)
    end
    :ok = check_label_length(label)
    label
  end

  def check_context(label), do: check_context(label, label, true, 0)

  def check_hyphen(label), do: check_hyphen(label, true)

  def check_initial_combiner([cP | _]) do
    case :idna_data.lookup(cP) do
      {[?M | _], _} ->
        :erlang.exit({:bad_label, {:initial_combiner, 'Label begins with an illegal combining character'}})
      _ ->
        :ok
    end
  end

  @spec check_label(charlist()) :: :ok
  def check_label(label), do: check_label(label, true, true, true)

  @spec check_label(label, checkHyphens, checkJoiners, checkBidi) :: result when label: charlist(), checkHyphens: boolean(), checkJoiners: boolean(), checkBidi: boolean(), result: :ok
  def check_label(label, checkHyphens, checkJoiners, checkBidi) do
    :ok = check_nfc(label)
    :ok = check_hyphen(label, checkHyphens)
    :ok = check_initial_combiner(label)
    :ok = check_context(label, checkJoiners)
    :ok = check_bidi(label, checkBidi)
    :ok
  end

  def check_label_length(label) when length(label) > 63 do
    errorMsg = error_msg('The label ~p  is too long', [label])
    :erlang.exit({:bad_label, {:too_long, errorMsg}})
  end

  def check_label_length(_), do: :ok

  def check_nfc(label) do
    case characters_to_nfc_list(label) do
      label ->
        :ok
      _ ->
        :erlang.exit({:bad_label, {:nfc, 'Label must be in Normalization Form C'}})
    end
  end

  @spec decode(charlist()) :: charlist()
  def decode(domain), do: decode(domain, [])

  @spec decode(charlist(), idna_flags()) :: charlist()
  def decode(domain0, options) do
    :ok = validate_options(options)
    domain = case :proplists.get_value(:uts46, options, false) do
      true ->
        sTD3Rules = :proplists.get_value(:std3_rules, options, false)
        transitional = :proplists.get_value(:transitional, options, false)
        uts46_remap(domain0, sTD3Rules, transitional)
      false ->
        domain0
    end
    labels = case :proplists.get_value(:strict, options, false) do
      false ->
        :re.split(lowercase(domain), "[.。．｡]", [{:return, :list}, :unicode])
      true ->
        :string.tokens(lowercase(domain), '.')
    end
    case labels do
      [] ->
        exit(:empty_domain)
      _ ->
        decode_1(labels, [])
    end
  end

  @spec encode(charlist()) :: charlist()
  def encode(domain), do: encode(domain, [])

  @spec encode(charlist(), idna_flags()) :: charlist()
  def encode(domain0, options) do
    :ok = validate_options(options)
    domain = case :proplists.get_value(:uts46, options, false) do
      true ->
        sTD3Rules = :proplists.get_value(:std3_rules, options, false)
        transitional = :proplists.get_value(:transitional, options, false)
        uts46_remap(domain0, sTD3Rules, transitional)
      false ->
        domain0
    end
    labels = case :proplists.get_value(:strict, options, false) do
      false ->
        :re.split(domain, "[.。．｡]", [{:return, :list}, :unicode])
      true ->
        :string.tokens(domain, '.')
    end
    case labels do
      [] ->
        exit(:empty_domain)
      _ ->
        encode_1(labels, [])
    end
  end

  @spec from_ascii([char(), ...]) :: [char(), ...]
  def from_ascii(domain), do: decode(domain)

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  @spec to_ascii(charlist()) :: charlist()
  def to_ascii(domain), do: encode(domain)

  @spec to_unicode(charlist()) :: charlist()
  def to_unicode(domain), do: decode(domain)

  def ulabel([]), do: []

  def ulabel(label0) do
    label = case :lists.all(fn c ->
        :idna_ucs.is_ascii(c)
    end, label0) do
      true ->
        case label0 do
          [?x, ?n, ?-, ?- | label1] ->
            :punycode.decode(lowercase(label1))
          _ ->
            lowercase(label0)
        end
      false ->
        lowercase(label0)
    end
    :ok = check_label(label)
    label
  end

  def utf8_to_ascii(domain), do: to_ascii(:idna_ucs.from_utf8(domain))

  # Private Functions

  defp unquote(:"-alabel/1-fun-0-")(arg1) do
    # body not decompiled
  end

  defp unquote(:"-ulabel/1-fun-0-")(arg1) do
    # body not decompiled
  end

  defp append(char, <<>>) when is_integer(char), do: [char]

  defp append(char, <<>>) when is_list(char), do: char

  defp append(char, bin) when is_binary(bin), do: [char, bin]

  defp append(char, str) when is_integer(char), do: [char | str]

  defp append(gC, str) when is_list(gC), do: gC ++ str

  defp characters_to_nfc_list(cD) do
    case :unicode_util_compat.nfc(cD) do
      [cPs | str] when is_list(cPs) ->
        cPs ++ characters_to_nfc_list(str)
      [cP | str] ->
        [cP | characters_to_nfc_list(str)]
      [] ->
        []
    end
  end

  defp check_bidi(label, true), do: :idna_bidi.check_bidi(label)

  defp check_bidi(_, false), do: :ok

  defp check_context(label, checkJoiners), do: check_context(label, label, checkJoiners, 0)

  defp check_context([cP | rest], label, checkJoiners, pos) do
    case :idna_table.lookup(cP) do
      :PVALID ->
        check_context(rest, label, checkJoiners, pos + 1)
      :CONTEXTJ ->
        :ok = valid_contextj(cP, label, pos, checkJoiners)
        check_context(rest, label, checkJoiners, pos + 1)
      :CONTEXTO ->
        :ok = valid_contexto(cP, label, pos, checkJoiners)
        check_context(rest, label, checkJoiners, pos + 1)
      _Status ->
        errorMsg = error_msg('Codepoint ~p not allowed (~p) at position ~p in ~p', [cP, _Status, pos, label])
        :erlang.exit({:bad_label, {:context, errorMsg}})
    end
  end

  defp check_context([], _, _, _), do: :ok

  defp check_hyphen(label, true) when length(label) >= 3 do
    case :lists.nthtail(2, label) do
      [?-, ?- | _] ->
        errorMsg = error_msg('Label ~p has disallowed hyphens in 3rd and 4th position', [label])
        :erlang.exit({:bad_label, {:hyphen, errorMsg}})
      _ ->
        case :lists.nth(1, label) == ?- or :lists.last(label) == ?- do
          true ->
            errorMsg = error_msg('Label ~p must not start or end with a hyphen', [label])
            :erlang.exit({:bad_label, {:hyphen, errorMsg}})
          false ->
            :ok
        end
    end
  end

  defp check_hyphen(label, true) do
    case :lists.nth(1, label) == ?- or :lists.last(label) == ?- do
      true ->
        errorMsg = error_msg('Label ~p must not start or end with a hyphen', [label])
        :erlang.exit({:bad_label, {:hyphen, errorMsg}})
      false ->
        :ok
    end
  end

  defp check_hyphen(_Label, false), do: :ok

  defp decode_1([], acc), do: :lists.reverse(acc)

  defp decode_1([label | labels], []), do: decode_1(labels, :lists.reverse(ulabel(label)))

  defp decode_1([label | labels], acc), do: decode_1(labels, :lists.reverse(ulabel(label), [?. | acc]))

  defp encode_1([], acc), do: :lists.reverse(acc)

  defp encode_1([label | labels], []), do: encode_1(labels, :lists.reverse(alabel(label)))

  defp encode_1([label | labels], acc), do: encode_1(labels, :lists.reverse(alabel(label), [?. | acc]))

  defp error_msg(msg, fmt), do: :lists.flatten(:io_lib.format(msg, fmt))

  @spec lowercase(string :: :unicode.chardata()) :: :unicode.chardata()
  defp lowercase(cD) when is_list(cD) do
    try do
      lowercase_list(cD, false)
    catch
      {:throw, :unchanged, _} ->
        cD
    end
  end

  defp lowercase(<<cP1 :: utf8, rest :: binary>> = orig) do
    try do
      lowercase_bin(cP1, rest, false)
    catch
      {:throw, :unchanged, _} ->
        orig
    else
      list ->
        :unicode.characters_to_binary(list)
    end
  end

  defp lowercase(<<>>), do: <<>>

  defp lowercase_bin(cP1, <<cP2 :: utf8, bin :: binary>>, _Changed) when ?A <= cP1 and cP1 <= ?Z and cP2 < 256, do: [cP1 + 32 | lowercase_bin(cP2, bin, true)]

  defp lowercase_bin(cP1, <<cP2 :: utf8, bin :: binary>>, changed) when cP1 < 128 and cP2 < 256, do: [cP1 | lowercase_bin(cP2, bin, changed)]

  defp lowercase_bin(cP1, bin, changed) do
    case :unicode_util_compat.lowercase([cP1 | bin]) do
      [cP1 | cPs] ->
        case :unicode_util_compat.cp(cPs) do
          [next | rest] ->
            [cP1 | lowercase_bin(next, rest, changed)]
          [] when changed ->
            [cP1]
          [] ->
            throw(:unchanged)
        end
      [char | cPs] ->
        case :unicode_util_compat.cp(cPs) do
          [next | rest] ->
            [char | lowercase_bin(next, rest, true)]
          [] ->
            [char]
        end
    end
  end

  defp lowercase_list([cP1 | [cP2 | _] = cont], _Changed) when ?A <= cP1 and cP1 <= ?Z and cP2 < 256, do: [cP1 + 32 | lowercase_list(cont, true)]

  defp lowercase_list([cP1 | [cP2 | _] = cont], changed) when cP1 < 128 and cP2 < 256, do: [cP1 | lowercase_list(cont, changed)]

  defp lowercase_list([], true), do: []

  defp lowercase_list([], false), do: throw(:unchanged)

  defp lowercase_list(cPs0, changed) do
    case :unicode_util_compat.lowercase(cPs0) do
      [char | cPs] when char === hd(cPs0) ->
        [char | lowercase_list(cPs, changed)]
      [char | cPs] ->
        append(char, lowercase_list(cPs, true))
      [] ->
        lowercase_list([], changed)
    end
  end

  defp uts46_remap(str, std3Rules, transitional), do: characters_to_nfc_list(uts46_remap_1(str, std3Rules, transitional))

  defp uts46_remap_1([cp | rs], std3Rules, transitional) do
    row = try do
      :idna_mapping.uts46_map(cp)
    catch
      {:error, :badarg, _} ->
        case :logger.allow(:error, :idna) do
          true ->
            :erlang.apply(:logger, :macro_log, [%{:mfa => {:idna, :uts46_remap_1, 3}, :line => 395, :file => '/Users/kronic.deth/github/Frameio/massdriver/deps/idna/src/idna.erl'}, :error, 'codepoint ~p not found in mapping list~n', [cp]])
          false ->
            :ok
        end
        :erlang.exit({:invalid_codepoint, cp})
    end
    {status, replacement} = case row do
      {_, _} ->
        row
      s ->
        {s, :undefined}
    end
    cond do
      status === :V or status === :D and transitional === false or status === :"3" and std3Rules === true and replacement === :undefined ->
        [cp] ++ uts46_remap_1(rs, std3Rules, transitional)
      replacement !== :undefined and status === :M or status === :"3" and std3Rules === false or status === :D and transitional === true ->
        replacement ++ uts46_remap_1(rs, std3Rules, transitional)
      status === :I ->
        uts46_remap_1(rs, std3Rules, transitional)
      true ->
        :erlang.exit({:invalid_codepoint, cp})
    end
  end

  defp uts46_remap_1([], _, _), do: []

  defp valid_contextj(cP, label, pos, true) do
    case :idna_context.valid_contextj(cP, label, pos) do
      true ->
        :ok
      false ->
        errorMsg = error_msg('Joiner ~p not allowed at position ~p in ~p', [cP, pos, label])
        :erlang.exit({:bad_label, {:contextj, errorMsg}})
    end
  end

  defp valid_contextj(_CP, _Label, _Pos, false), do: :ok

  defp valid_contexto(cP, label, pos, true) do
    case :idna_context.valid_contexto(cP, label, pos) do
      true ->
        :ok
      false ->
        errorMsg = error_msg('Joiner ~p not allowed at position ~p in ~p', [cP, pos, label])
        :erlang.exit({:bad_label, {:contexto, errorMsg}})
    end
  end

  defp valid_contexto(_CP, _Label, _Pos, false), do: :ok

  defp validate_options([]), do: :ok

  defp validate_options([:uts46 | rs]), do: validate_options(rs)

  defp validate_options([{:uts46, b} | rs]) when is_boolean(b), do: validate_options(rs)

  defp validate_options([:strict | rs]), do: validate_options(rs)

  defp validate_options([{:strict, b} | rs]) when is_boolean(b), do: validate_options(rs)

  defp validate_options([:std3_rules | rs]), do: validate_options(rs)

  defp validate_options([{:std3_rules, b} | rs]) when is_boolean(b), do: validate_options(rs)

  defp validate_options([:transitional | rs]), do: validate_options(rs)

  defp validate_options([{:transitional, b} | rs]) when is_boolean(b), do: validate_options(rs)

  defp validate_options([_]), do: :erlang.error(:badarg)
end
