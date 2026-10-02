# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :base64 do
  @moduledoc ~S"""
  Provides base64 encode and decode, see
  [RFC 2045](https://www.ietf.org/rfc/rfc2045.txt).
  """

  # Private Types

  @typedoc ~S"""
  Base 64 Encoding alphabet, see
  [RFC 4648](https://datatracker.ietf.org/doc/html/rfc4648).
  """
  @typep base64_alphabet :: (?A..?Z | ?a..?z | ?0..?9 | ?+ | ?/ | ?- | ?_ | ?=)

  @typedoc ~S"""
  Base 64 encoded binary.
  """
  @typep base64_binary :: binary()

  @typedoc ~S"""
  Selector for the Base 64 Encoding alphabet used for [encoding](`encode/2`) and
  [decoding](`decode/2`). See
  [RFC 4648](https://datatracker.ietf.org/doc/html/rfc4648) Sections
  [4](https://datatracker.ietf.org/doc/html/rfc4648#section-4) and
  [5](https://datatracker.ietf.org/doc/html/rfc4648#section-5).
  """
  @typep base64_mode :: (:standard | :urlsafe)

  @typedoc ~S"""
  Base 64 encoded string.
  """
  @typep base64_string :: [base64_alphabet()]

  @typedoc ~S"""
  Arbitrary sequences of octets.
  """
  @typep byte_string :: [byte()]

  @typedoc ~S"""
  Customizes the behaviour of the decode functions.

  Default value if omitted entirely or partially is `#{mode => standard, padding => true}`.

  The `mode` option can be one of the following:

  - **`standard`** - Default. Decode the given string using the standard base64
    alphabet according to
    [RFC 4648 Section 4](https://datatracker.ietf.org/doc/html/rfc4648#section-4),
    that is `"+"` and `"/"` are representing bytes `62` and `63` respectively,
    while `"-"` and `"_"` are illegal characters.

  - **`urlsafe`** - Decode the given string using the alternative "URL and
    Filename safe" base64 alphabet according to
    [RFC 4648 Section 5](https://datatracker.ietf.org/doc/html/rfc4648#section-5),
    that is `"-"` and `"_"` are representing bytes `62` and `63` respectively,
    while `"+"` and `"/"` are illegal characters.

  The `padding` option can be one of the following:

  - **`true`** - Default. Checks the correct number of `=` padding characters at
    the end of the encoded string.

  - **`false`** - Accepts an encoded string with missing `=` padding characters at
    the end.
  """
  @typep decode_options :: %{optional(:padding) => boolean(), optional(:mode) => base64_mode()}

  @typedoc ~S"""
  Customizes the behaviour of the decode functions.

  Default value if omitted entirely or partially is `#{mode => standard, padding => true}`.

  The `mode` option can be one of the following:

  - **`standard`** - Default. Encode the given string using the standard base64
    alphabet according to
    [RFC 4648 Section 4](https://datatracker.ietf.org/doc/html/rfc4648#section-4).

  - **`urlsafe`** - Encode the given string using the alternative "URL and
    Filename safe" base64 alphabet according to
    [RFC 4648 Section 5](https://datatracker.ietf.org/doc/html/rfc4648#section-5).

  The `padding` option can be one of the following:

  - **`true`** - Default. Appends correct number of `=` padding characters to the
    encoded string.

  - **`false`** - Skips appending `=` padding characters to the encoded string.
  """
  @typep encode_options :: %{optional(:padding) => boolean(), optional(:mode) => base64_mode()}

  # Functions

  @spec decode(base64) :: data when base64: (base64_string() | base64_binary()), data: binary()
  def decode(base64), do: decode(base64, %{})

  @doc ~S"""
  Decodes a base64 string encoded using the standard alphabet according to
  [RFC 4648 Section 4](https://datatracker.ietf.org/doc/html/rfc4648#section-4) to
  plain ASCII.

  The function will strips away any whitespace characters and check for the
  the correct number of `=` padding characters at the end of the encoded string.

  See `t:decode_options/0` for details on which options can be passed.

  _Example_:
  ```erlang
  1> base64:decode("AQIDBA==").
  <<1,2,3,4>>
  2> base64:decode("AQ ID BA==").
  <<1,2,3,4>>
  3> base64:decode("AQIDBA=").
  ** exception error: missing_padding
       in function  base64:decode_list/7 (base64.erl, line 734)
          *** data to decode is missing final = padding characters, if this is intended, use the `padding => false` option
  4> base64:decode("AQIDBA=", #{ padding => false }).
  <<1,2,3,4>>
  ```
  """
  @spec decode(base64, options) :: data when base64: (base64_string() | base64_binary()), options: decode_options(), data: binary()
  def decode(bin, options) when is_binary(bin), do: decode_binary(get_decoding_offset(options), get_padding(options), bin, <<>>)

  def decode(list, options) when is_list(list), do: decode_list(get_decoding_offset(options), get_padding(options), list, <<>>)

  @doc ~S"""
  Equivalent to [`decode(Base64)`](`decode/1`), but returns a `t:byte_string/0`.
  """
  @spec decode_to_string(base64) :: dataString when base64: (base64_string() | base64_binary()), dataString: byte_string()
  def decode_to_string(base64), do: decode_to_string(base64, %{})

  @doc ~S"""
  Equivalent to [`decode(Base64, Options)`](`decode/2`), but returns a `t:byte_string/0`.
  """
  @spec decode_to_string(base64, options) :: dataString when base64: (base64_string() | base64_binary()), options: decode_options(), dataString: byte_string()
  def decode_to_string(bin, options) when is_binary(bin), do: decode_to_string(binary_to_list(bin), options)

  def decode_to_string(list, options) when is_list(list), do: decode_list_to_string(get_decoding_offset(options), get_padding(options), list)

  @spec encode(data) :: base64 when data: (byte_string() | binary()), base64: base64_binary()
  def encode(data), do: encode(data, %{})

  @doc ~S"""
  Encodes a plain ASCII string into base64 using the alphabet indicated by the
  `mode` option. The result is 33% larger than the data.

  See `t:encode_options/0` for details on which options can be passed.
  """
  @spec encode(data, options) :: base64 when data: (byte_string() | binary()), options: encode_options(), base64: base64_binary()
  def encode(bin, options) when is_binary(bin) and is_map(options), do: encode_binary(get_encoding_offset(options), get_padding(options), bin, <<>>)

  def encode(list, options) when is_list(list), do: encode_list(get_encoding_offset(options), get_padding(options), list, <<>>)

  @doc ~S"""
  Equivalent to [`encode(Data)`](`encode/1`), but returns a `t:byte_string/0`.
  """
  @spec encode_to_string(data) :: base64String when data: (byte_string() | binary()), base64String: base64_string()
  def encode_to_string(data), do: encode_to_string(data, %{})

  @doc ~S"""
  Equivalent to [`encode(Data, Options)`](`encode/2`), but returns a `t:byte_string/0`.
  """
  @spec encode_to_string(data, options) :: base64String when data: (byte_string() | binary()), options: encode_options(), base64String: base64_string()
  def encode_to_string(bin, options) when is_binary(bin) and is_map(options), do: encode_to_string(binary_to_list(bin), options)

  def encode_to_string(list, options) when is_list(list) and is_map(options), do: encode_list_to_string(get_encoding_offset(options), get_padding(options), list)

  @doc false
  def format_error(:missing_padding, _), do: %{:general => 'data to decode is missing final = padding characters, if this is intended, use the `padding => false` option'}

  def format_error(_, _), do: %{}

  @spec mime_decode(base64) :: data when base64: (base64_string() | base64_binary()), data: binary()
  def mime_decode(base64), do: mime_decode(base64, %{})

  @doc ~S"""
  Decodes a base64 "mime" string encoded using the standard alphabet according to
  [RFC 4648 Section 4](https://datatracker.ietf.org/doc/html/rfc4648#section-4) to
  plain ASCII.

  The function will strips away any illegal characters. It does *not* check for the
  the correct number of `=` padding characters at the end of the encoded string.

  See `t:decode_options/0` for details on which options can be passed.

  _Example_:
  ```erlang
  1> base64:mime_decode("AQIDBA==").
  <<1,2,3,4>>
  2> base64:mime_decode("AQIDB=A=").
  <<1,2,3,4>>
  ```
  """
  @spec mime_decode(base64, options) :: data when base64: (base64_string() | base64_binary()), options: decode_options(), data: binary()
  def mime_decode(bin, options) when is_binary(bin), do: mime_decode_binary(get_decoding_offset(options), get_padding(options), bin, <<>>)

  def mime_decode(list, options) when is_list(list), do: mime_decode_list(get_decoding_offset(options), get_padding(options), list, <<>>)

  @doc ~S"""
  Equivalent to [`mime_decode(Base64)`](`mime_decode/1`),
  but returns a `t:byte_string/0`.
  """
  @spec mime_decode_to_string(base64) :: dataString when base64: (base64_string() | base64_binary()), dataString: byte_string()
  def mime_decode_to_string(base64), do: mime_decode_to_string(base64, %{})

  @doc ~S"""
  Equivalent to [`mime_decode(Base64, Options)`](`mime_decode/2`),
  but returns a `t:byte_string/0`.
  """
  @spec mime_decode_to_string(base64, options) :: dataString when base64: (base64_string() | base64_binary()), options: decode_options(), dataString: byte_string()
  def mime_decode_to_string(bin, options) when is_binary(bin), do: mime_decode_to_string(binary_to_list(bin), options)

  def mime_decode_to_string(list, options) when is_list(list), do: mime_decode_list_to_string(get_decoding_offset(options), get_padding(options), list)

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  # Private Functions

  defp dec_bin(modeOffset, padding, cs, :ws, a), do: decode_binary(modeOffset, padding, cs, a)

  defp dec_bin(modeOffset, padding, cs, b1, a), do: decode_binary(modeOffset, padding, cs, a, b1)

  defp dec_bin(modeOffset, padding, cs, :ws, b2, a), do: dec_bin(modeOffset, padding, cs, b2, a)

  defp dec_bin(modeOffset, padding, cs, b1, :ws, a), do: dec_bin(modeOffset, padding, cs, b1, a)

  defp dec_bin(modeOffset, padding, cs, b1, b2, a), do: decode_binary(modeOffset, padding, cs, a, b1, b2)

  defp dec_bin(modeOffset, padding, cs, :ws, b2, b3, a), do: dec_bin(modeOffset, padding, cs, b2, b3, a)

  defp dec_bin(modeOffset, padding, cs, b1, :ws, b3, a), do: dec_bin(modeOffset, padding, cs, b1, b3, a)

  defp dec_bin(modeOffset, padding, cs, b1, b2, :ws, a), do: dec_bin(modeOffset, padding, cs, b1, b2, a)

  defp dec_bin(modeOffset, padding, cs, b1, b2, b3, a), do: decode_binary(modeOffset, padding, cs, a, b1, b2, b3)

  defp dec_bin(modeOffset, padding, cs, :ws, b2, b3, b4, a), do: dec_bin(modeOffset, padding, cs, b2, b3, b4, a)

  defp dec_bin(modeOffset, padding, cs, b1, :ws, b3, b4, a), do: dec_bin(modeOffset, padding, cs, b1, b3, b4, a)

  defp dec_bin(modeOffset, padding, cs, b1, b2, :ws, b4, a), do: dec_bin(modeOffset, padding, cs, b1, b2, b4, a)

  defp dec_bin(modeOffset, padding, cs, b1, b2, b3, b4, a) do
    case b4 do
      :ws ->
        decode_binary(modeOffset, padding, cs, a, b1, b2, b3)
      :eq when b3 === :eq ->
        only_ws_binary(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 >>> 4 :: 2>>)
      :eq ->
        only_ws_binary(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>)
      b4 ->
        decode_binary(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6>>)
    end
  end

  defp decode_binary(modeOffset, padding, <<c1 :: 8, c2 :: 8, c3 :: 8, c4 :: 8, cs :: bits>>, a) do
    case {b64d(c1, modeOffset), b64d(c2, modeOffset), b64d(c3, modeOffset), b64d(c4, modeOffset)} do
      {b1, b2, b3, b4} when is_integer(b1) and is_integer(b2) and is_integer(b3) and is_integer(b4) ->
        decode_binary(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6>>)
      {b1, b2, b3, b4} ->
        dec_bin(modeOffset, padding, cs, b1, b2, b3, b4, a)
    end
  end

  defp decode_binary(_ModeOffset, _Padding, <<>>, a), do: a

  defp decode_binary(modeOffset, padding, <<c1 :: 8, cs :: bits>>, a) do
    case b64d(c1, modeOffset) do
      :ws ->
        decode_binary(modeOffset, padding, cs, a)
      b1 ->
        decode_binary(modeOffset, padding, cs, a, b1)
    end
  end

  defp decode_binary(modeOffset, padding, <<c2 :: 8, cs :: bits>>, a, b1) do
    case b64d(c2, modeOffset) do
      :ws ->
        decode_binary(modeOffset, padding, cs, a, b1)
      b2 ->
        decode_binary(modeOffset, padding, cs, a, b1, b2)
    end
  end

  defp decode_binary(modeOffset, padding, <<c3 :: 8, cs :: bits>>, a, b1, b2) do
    case b64d(c3, modeOffset) do
      :ws ->
        decode_binary(modeOffset, padding, cs, a, b1, b2)
      b3 ->
        decode_binary(modeOffset, padding, cs, a, b1, b2, b3)
    end
  end

  defp decode_binary(modeOffset, padding, <<cs :: bits>>, a, b1, b2) do
    case padding do
      true ->
        missing_padding_error()
      false ->
        decode_binary(modeOffset, padding, cs, a, b1, b2, :eq)
    end
  end

  defp decode_binary(modeOffset, padding, <<c4 :: 8, cs :: bits>>, a, b1, b2, b3) do
    case b64d(c4, modeOffset) do
      :ws ->
        decode_binary(modeOffset, padding, cs, a, b1, b2, b3)
      :eq when b3 === :eq ->
        only_ws_binary(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 >>> 4 :: 2>>)
      :eq ->
        only_ws_binary(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>)
      b4 ->
        decode_binary(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6>>)
    end
  end

  defp decode_binary(_ModeOffset, padding, <<>>, a, b1, b2, b3) do
    case padding do
      true ->
        missing_padding_error()
      false when b3 === :eq ->
        <<a :: bits, b1 :: 6, b2 >>> 4 :: 2>>
      false ->
        <<a :: bits, b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>
    end
  end

  defp decode_list(modeOffset, padding, [c1 | cs], a) do
    case b64d(c1, modeOffset) do
      :ws ->
        decode_list(modeOffset, padding, cs, a)
      b1 ->
        decode_list(modeOffset, padding, cs, a, b1)
    end
  end

  defp decode_list(_ModeOffset, _Padding, [], a), do: a

  defp decode_list(modeOffset, padding, [c2 | cs], a, b1) do
    case b64d(c2, modeOffset) do
      :ws ->
        decode_list(modeOffset, padding, cs, a, b1)
      b2 ->
        decode_list(modeOffset, padding, cs, a, b1, b2)
    end
  end

  defp decode_list(modeOffset, padding, [c3 | cs], a, b1, b2) do
    case b64d(c3, modeOffset) do
      :ws ->
        decode_list(modeOffset, padding, cs, a, b1, b2)
      b3 ->
        decode_list(modeOffset, padding, cs, a, b1, b2, b3)
    end
  end

  defp decode_list(modeOffset, padding, [], a, b1, b2) do
    case padding do
      true ->
        missing_padding_error()
      false ->
        decode_list(modeOffset, padding, [], a, b1, b2, :eq)
    end
  end

  defp decode_list(modeOffset, padding, [c4 | cs], a, b1, b2, b3) do
    case b64d(c4, modeOffset) do
      :ws ->
        decode_list(modeOffset, padding, cs, a, b1, b2, b3)
      :eq when b3 === :eq ->
        only_ws(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 >>> 4 :: 2>>)
      :eq ->
        only_ws(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>)
      b4 ->
        decode_list(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6>>)
    end
  end

  defp decode_list(_ModeOffset, padding, [], a, b1, b2, b3) do
    case padding do
      true ->
        missing_padding_error()
      false when b3 == :eq ->
        <<a :: bits, b1 :: 6, b2 >>> 4 :: 2>>
      false ->
        <<a :: bits, b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>
    end
  end

  defp decode_list_to_string(modeOffset, padding, [c1 | cs]) do
    case b64d(c1, modeOffset) do
      :ws ->
        decode_list_to_string(modeOffset, padding, cs)
      b1 ->
        decode_list_to_string(modeOffset, padding, cs, b1)
    end
  end

  defp decode_list_to_string(_ModeOffset, _Padding, []), do: []

  defp decode_list_to_string(modeOffset, padding, [c2 | cs], b1) do
    case b64d(c2, modeOffset) do
      :ws ->
        decode_list_to_string(modeOffset, padding, cs, b1)
      b2 ->
        decode_list_to_string(modeOffset, padding, cs, b1, b2)
    end
  end

  defp decode_list_to_string(modeOffset, padding, [c3 | cs], b1, b2) do
    case b64d(c3, modeOffset) do
      :ws ->
        decode_list_to_string(modeOffset, padding, cs, b1, b2)
      b3 ->
        decode_list_to_string(modeOffset, padding, cs, b1, b2, b3)
    end
  end

  defp decode_list_to_string(modeOffset, padding, [], b1, b2) do
    case padding do
      true ->
        missing_padding_error()
      false ->
        decode_list_to_string(modeOffset, padding, [], b1, b2, :eq)
    end
  end

  defp decode_list_to_string(modeOffset, padding, [c4 | cs], b1, b2, b3) do
    case b64d(c4, modeOffset) do
      :ws ->
        decode_list_to_string(modeOffset, padding, cs, b1, b2, b3)
      :eq when b3 === :eq ->
        only_ws(modeOffset, padding, cs, binary_to_list(<<b1 :: 6, b2 >>> 4 :: 2>>))
      :eq ->
        only_ws(modeOffset, padding, cs, binary_to_list(<<b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>))
      b4 ->
        bits4x6 = b1 <<< 18 ||| b2 <<< 12 ||| b3 <<< 6 ||| b4
        octet1 = bits4x6 >>> 16
        octet2 = bits4x6 >>> 8 &&& 255
        octet3 = bits4x6 &&& 255
        [octet1, octet2, octet3 | decode_list_to_string(modeOffset, padding, cs)]
    end
  end

  defp decode_list_to_string(_ModeOffset, padding, [], b1, b2, b3) do
    case padding do
      true ->
        missing_padding_error()
      false when b3 === :eq ->
        binary_to_list(<<b1 :: 6, b2 >>> 4 :: 2>>)
      false ->
        binary_to_list(<<b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>)
    end
  end

  defp encode_binary(modeOffset, padding, <<b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6, b5 :: 6, b6 :: 6, b7 :: 6, b8 :: 6, ls :: bits>>, a), do: encode_binary(modeOffset, padding, ls, <<a :: bits, b64e(b1, modeOffset) :: 8, b64e(b2, modeOffset) :: 8, b64e(b3, modeOffset) :: 8, b64e(b4, modeOffset) :: 8, b64e(b5, modeOffset) :: 8, b64e(b6, modeOffset) :: 8, b64e(b7, modeOffset) :: 8, b64e(b8, modeOffset) :: 8>>)

  defp encode_binary(_ModeOffset, _Padding, <<>>, a), do: a

  defp encode_binary(modeOffset, padding, <<b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6, ls :: bits>>, a), do: encode_binary(modeOffset, padding, ls, <<a :: bits, b64e(b1, modeOffset) :: 8, b64e(b2, modeOffset) :: 8, b64e(b3, modeOffset) :: 8, b64e(b4, modeOffset) :: 8>>)

  defp encode_binary(modeOffset, padding, <<b1 :: 6, b2 :: 2>>, a) do
    e1 = b64e(b1, modeOffset)
    e2 = b64e(b2 <<< 4, modeOffset)
    case padding do
      true ->
        <<a :: bits, e1, e2, ?=, ?=>>
      _ ->
        <<a :: bits, e1, e2>>
    end
  end

  defp encode_binary(modeOffset, padding, <<b1 :: 6, b2 :: 6, b3 :: 4>>, a) do
    e1 = b64e(b1, modeOffset)
    e2 = b64e(b2, modeOffset)
    e3 = b64e(b3 <<< 2, modeOffset)
    case padding do
      true ->
        <<a :: bits, e1, e2, e3, ?=>>
      _ ->
        <<a :: bits, e1, e2, e3>>
    end
  end

  defp encode_list(_ModeOffset, _Padding, [], a), do: a

  defp encode_list(modeOffset, padding, [b1], a) do
    e1 = b64e(b1 >>> 2, modeOffset)
    e2 = b64e(b1 &&& 3 <<< 4, modeOffset)
    case padding do
      true ->
        <<a :: bits, e1, e2, ?=, ?=>>
      false ->
        <<a :: bits, e1, e2>>
    end
  end

  defp encode_list(modeOffset, padding, [b1, b2], a) do
    e1 = b64e(b1 >>> 2, modeOffset)
    e2 = b64e(b1 &&& 3 <<< 4 ||| b2 >>> 4, modeOffset)
    e3 = b64e(b2 &&& 15 <<< 2, modeOffset)
    case padding do
      true ->
        <<a :: bits, e1, e2, e3, ?=>>
      false ->
        <<a :: bits, e1, e2, e3>>
    end
  end

  defp encode_list(modeOffset, padding, [b1, b2, b3 | ls], a) do
    bB = b1 <<< 16 ||| b2 <<< 8 ||| b3
    encode_list(modeOffset, padding, ls, <<a :: bits, b64e(bB >>> 18, modeOffset) :: 8, b64e(bB >>> 12 &&& 63, modeOffset) :: 8, b64e(bB >>> 6 &&& 63, modeOffset) :: 8, b64e(bB &&& 63, modeOffset) :: 8>>)
  end

  defp encode_list_to_string(_ModeOffset, _Padding, []), do: []

  defp encode_list_to_string(modeOffset, padding, [b1]) do
    [b64e(b1 >>> 2, modeOffset), b64e(b1 &&& 3 <<< 4, modeOffset) | (case padding do
      true ->
        '=='
      false ->
        ""
    end)]
  end

  defp encode_list_to_string(modeOffset, padding, [b1, b2]) do
    [b64e(b1 >>> 2, modeOffset), b64e(b1 &&& 3 <<< 4 ||| b2 >>> 4, modeOffset), b64e(b2 &&& 15 <<< 2, modeOffset) | (case padding do
      true ->
        '='
      false ->
        ""
    end)]
  end

  defp encode_list_to_string(modeOffset, padding, [b1, b2, b3 | ls]) do
    bB = b1 <<< 16 ||| b2 <<< 8 ||| b3
    [b64e(bB >>> 18, modeOffset), b64e(bB >>> 12 &&& 63, modeOffset), b64e(bB >>> 6 &&& 63, modeOffset), b64e(bB &&& 63, modeOffset) | encode_list_to_string(modeOffset, padding, ls)]
  end

  defp get_decoding_offset(%{:mode => :standard}), do: 1

  defp get_decoding_offset(%{:mode => :urlsafe}), do: 257

  defp get_decoding_offset(%{}), do: 1

  defp get_encoding_offset(%{:mode => :standard}), do: 1

  defp get_encoding_offset(%{:mode => :urlsafe}), do: 65

  defp get_encoding_offset(%{}), do: 1

  defp get_padding(%{:padding => bool}) when is_boolean(bool), do: bool

  defp get_padding(%{}), do: true

  defp mime_decode_binary(modeOffset, padding, <<c1 :: 8, cs :: bits>>, a) do
    case b64d(c1, modeOffset) do
      b1 when is_integer(b1) ->
        mime_decode_binary(modeOffset, padding, cs, a, b1)
      _ ->
        mime_decode_binary(modeOffset, padding, cs, a)
    end
  end

  defp mime_decode_binary(_ModeOffset, _Padding, <<>>, a), do: a

  defp mime_decode_binary(modeOffset, padding, <<c2 :: 8, cs :: bits>>, a, b1) do
    case b64d(c2, modeOffset) do
      b2 when is_integer(b2) ->
        mime_decode_binary(modeOffset, padding, cs, a, b1, b2)
      _ ->
        mime_decode_binary(modeOffset, padding, cs, a, b1)
    end
  end

  defp mime_decode_binary(modeOffset, padding, <<c3 :: 8, cs :: bits>>, a, b1, b2) do
    case b64d(c3, modeOffset) do
      b3 when is_integer(b3) ->
        mime_decode_binary(modeOffset, padding, cs, a, b1, b2, b3)
      :eq = b3 ->
        mime_decode_binary_after_eq(modeOffset, padding, cs, a, b1, b2, b3)
      _ ->
        mime_decode_binary(modeOffset, padding, cs, a, b1, b2)
    end
  end

  defp mime_decode_binary(modeOffset, padding, <<cs :: bits>>, a, b1, b2) do
    case padding do
      true ->
        missing_padding_error()
      false ->
        mime_decode_binary_after_eq(modeOffset, padding, cs, a, b1, b2, :eq)
    end
  end

  defp mime_decode_binary(modeOffset, padding, <<c4 :: 8, cs :: bits>>, a, b1, b2, b3) do
    case b64d(c4, modeOffset) do
      b4 when is_integer(b4) ->
        mime_decode_binary(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6>>)
      :eq ->
        mime_decode_binary_after_eq(modeOffset, padding, cs, a, b1, b2, b3)
      _ ->
        mime_decode_binary(modeOffset, padding, cs, a, b1, b2, b3)
    end
  end

  defp mime_decode_binary(modeOffset, padding, <<cs :: bits>>, a, b1, b2, b3) do
    case padding do
      true ->
        missing_padding_error()
      false ->
        mime_decode_binary_after_eq(modeOffset, padding, cs, a, b1, b2, b3)
    end
  end

  defp mime_decode_binary_after_eq(modeOffset, padding, <<c :: 8, cs :: bits>>, a, b1, b2, b3) do
    case b64d(c, modeOffset) do
      b when is_integer(b) ->
        case b3 do
          :eq ->
            mime_decode_binary(modeOffset, padding, cs, a, b1, b2, b)
          _ ->
            mime_decode_binary(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b :: 6>>)
        end
      _ ->
        mime_decode_binary_after_eq(modeOffset, padding, cs, a, b1, b2, b3)
    end
  end

  defp mime_decode_binary_after_eq(_ModeOffset, _Padding, <<>>, a, b1, b2, :eq), do: <<a :: bits, b1 :: 6, b2 >>> 4 :: 2>>

  defp mime_decode_binary_after_eq(_ModeOffset, _Padding, <<>>, a, b1, b2, b3), do: <<a :: bits, b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>

  defp mime_decode_list(modeOffset, padding, [c1 | cs], a) do
    case b64d(c1, modeOffset) do
      b1 when is_integer(b1) ->
        mime_decode_list(modeOffset, padding, cs, a, b1)
      _ ->
        mime_decode_list(modeOffset, padding, cs, a)
    end
  end

  defp mime_decode_list(_ModeOffset, _Padding, [], a), do: a

  defp mime_decode_list(modeOffset, padding, [c2 | cs], a, b1) do
    case b64d(c2, modeOffset) do
      b2 when is_integer(b2) ->
        mime_decode_list(modeOffset, padding, cs, a, b1, b2)
      _ ->
        mime_decode_list(modeOffset, padding, cs, a, b1)
    end
  end

  defp mime_decode_list(modeOffset, padding, [c3 | cs], a, b1, b2) do
    case b64d(c3, modeOffset) do
      b3 when is_integer(b3) ->
        mime_decode_list(modeOffset, padding, cs, a, b1, b2, b3)
      :eq = b3 ->
        mime_decode_list_after_eq(modeOffset, padding, cs, a, b1, b2, b3)
      _ ->
        mime_decode_list(modeOffset, padding, cs, a, b1, b2)
    end
  end

  defp mime_decode_list(modeOffset, padding, [], a, b1, b2) do
    case padding do
      true ->
        missing_padding_error()
      false ->
        mime_decode_list_after_eq(modeOffset, padding, [], a, b1, b2, :eq)
    end
  end

  defp mime_decode_list(modeOffset, padding, [c4 | cs], a, b1, b2, b3) do
    case b64d(c4, modeOffset) do
      b4 when is_integer(b4) ->
        mime_decode_list(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6>>)
      :eq ->
        mime_decode_list_after_eq(modeOffset, padding, cs, a, b1, b2, b3)
      _ ->
        mime_decode_list(modeOffset, padding, cs, a, b1, b2, b3)
    end
  end

  defp mime_decode_list(modeOffset, padding, [], a, b1, b2, b3) do
    case padding do
      true ->
        missing_padding_error()
      false ->
        mime_decode_list_after_eq(modeOffset, padding, [], a, b1, b2, b3)
    end
  end

  defp mime_decode_list_after_eq(modeOffset, padding, [c | cs], a, b1, b2, b3) do
    case b64d(c, modeOffset) do
      b when is_integer(b) ->
        case b3 do
          :eq ->
            mime_decode_list(modeOffset, padding, cs, a, b1, b2, b)
          _ ->
            mime_decode_list(modeOffset, padding, cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b :: 6>>)
        end
      _ ->
        mime_decode_list_after_eq(modeOffset, padding, cs, a, b1, b2, b3)
    end
  end

  defp mime_decode_list_after_eq(_ModeOffset, _Padding, [], a, b1, b2, :eq), do: <<a :: bits, b1 :: 6, b2 >>> 4 :: 2>>

  defp mime_decode_list_after_eq(_ModeOffset, _Padding, [], a, b1, b2, b3), do: <<a :: bits, b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>

  defp mime_decode_list_to_string(modeOffset, padding, [c1 | cs]) do
    case b64d(c1, modeOffset) do
      b1 when is_integer(b1) ->
        mime_decode_list_to_string(modeOffset, padding, cs, b1)
      _ ->
        mime_decode_list_to_string(modeOffset, padding, cs)
    end
  end

  defp mime_decode_list_to_string(_ModeOffset, _Padding, []), do: []

  defp mime_decode_list_to_string(modeOffset, padding, [c2 | cs], b1) do
    case b64d(c2, modeOffset) do
      b2 when is_integer(b2) ->
        mime_decode_list_to_string(modeOffset, padding, cs, b1, b2)
      _ ->
        mime_decode_list_to_string(modeOffset, padding, cs, b1)
    end
  end

  defp mime_decode_list_to_string(modeOffset, padding, [c3 | cs], b1, b2) do
    case b64d(c3, modeOffset) do
      b3 when is_integer(b3) ->
        mime_decode_list_to_string(modeOffset, padding, cs, b1, b2, b3)
      :eq = b3 ->
        mime_decode_list_to_string_after_eq(modeOffset, padding, cs, b1, b2, b3)
      _ ->
        mime_decode_list_to_string(modeOffset, padding, cs, b1, b2)
    end
  end

  defp mime_decode_list_to_string(modeOffset, padding, [], b1, b2) do
    case padding do
      true ->
        missing_padding_error()
      false ->
        mime_decode_list_to_string_after_eq(modeOffset, padding, [], b1, b2, :eq)
    end
  end

  defp mime_decode_list_to_string(modeOffset, padding, [c4 | cs], b1, b2, b3) do
    case b64d(c4, modeOffset) do
      b4 when is_integer(b4) ->
        bits4x6 = b1 <<< 18 ||| b2 <<< 12 ||| b3 <<< 6 ||| b4
        octet1 = bits4x6 >>> 16
        octet2 = bits4x6 >>> 8 &&& 255
        octet3 = bits4x6 &&& 255
        [octet1, octet2, octet3 | mime_decode_list_to_string(modeOffset, padding, cs)]
      :eq ->
        mime_decode_list_to_string_after_eq(modeOffset, padding, cs, b1, b2, b3)
      _ ->
        mime_decode_list_to_string(modeOffset, padding, cs, b1, b2, b3)
    end
  end

  defp mime_decode_list_to_string(modeOffset, padding, [], b1, b2, b3) do
    case padding do
      true ->
        missing_padding_error()
      false ->
        mime_decode_list_to_string_after_eq(modeOffset, padding, [], b1, b2, b3)
    end
  end

  defp mime_decode_list_to_string_after_eq(modeOffset, padding, [c | cs], b1, b2, b3) do
    case b64d(c, modeOffset) do
      b when is_integer(b) ->
        case b3 do
          :eq ->
            mime_decode_list_to_string(modeOffset, padding, cs, b1, b2, b)
          _ ->
            bits4x6 = b1 <<< 18 ||| b2 <<< 12 ||| b3 <<< 6 ||| b
            octet1 = bits4x6 >>> 16
            octet2 = bits4x6 >>> 8 &&& 255
            octet3 = bits4x6 &&& 255
            [octet1, octet2, octet3 | mime_decode_list_to_string(modeOffset, padding, cs)]
        end
      _ ->
        mime_decode_list_to_string_after_eq(modeOffset, padding, cs, b1, b2, b3)
    end
  end

  defp mime_decode_list_to_string_after_eq(_ModeOffset, _Padding, [], b1, b2, :eq), do: binary_to_list(<<b1 :: 6, b2 >>> 4 :: 2>>)

  defp mime_decode_list_to_string_after_eq(_ModeOffset, _Padding, [], b1, b2, b3), do: binary_to_list(<<b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>)

  defp only_ws(_ModeOffset, _Padding, [], a), do: a

  defp only_ws(modeOffset, padding, [c | cs], a) do
    case b64d(c, modeOffset) do
      :ws ->
        only_ws(modeOffset, padding, cs, a)
    end
  end

  defp only_ws_binary(_ModeOffset, _Padding, <<>>, a), do: a

  defp only_ws_binary(modeOffset, padding, <<c :: 8, cs :: bits>>, a) do
    case b64d(c, modeOffset) do
      :ws ->
        only_ws_binary(modeOffset, padding, cs, a)
    end
  end
end
