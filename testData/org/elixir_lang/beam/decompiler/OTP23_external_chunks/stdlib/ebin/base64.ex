# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :base64 do
  @moduledoc ~S"""
  Provides base64 encode and decode, see [RFC 2045](https://www.ietf.org/rfc/rfc2045.txt).
  """

  # Private Types

  @typedoc ~S"""
  A `binary()` with ASCII characters in the range 1 to 255.
  """
  @typep ascii_binary :: binary()

  @typep ascii_string :: [1..255]

  # Functions

  @doc ~S"""
  Decodes a base64-encoded string to plain ASCII. See [RFC 4648](https://www.ietf.org/html/rfc4648).

  `mime_decode/1` and `mime_decode_to_string/1` strip away illegal characters, while `decode/1` and `decode_to_string/1` only strip away whitespace characters.
  """
  @spec decode(base64) :: data when base64: (ascii_string() | ascii_binary()), data: ascii_binary()
  def decode(bin) when is_binary(bin), do: decode_binary(bin, <<>>)

  def decode(list) when is_list(list), do: decode_list(list, <<>>)

  @spec decode_to_string(base64) :: dataString when base64: (ascii_string() | ascii_binary()), dataString: ascii_string()
  def decode_to_string(bin) when is_binary(bin), do: decode_to_string(binary_to_list(bin))

  def decode_to_string(list) when is_list(list), do: decode_list_to_string(list)

  @doc ~S"""
  Encodes a plain ASCII string into base64. The result is 33% larger than the data.
  """
  @spec encode(data) :: base64 when data: (ascii_string() | ascii_binary()), base64: ascii_binary()
  def encode(bin) when is_binary(bin), do: encode_binary(bin, <<>>)

  def encode(list) when is_list(list), do: encode_list(list, <<>>)

  @spec encode_to_string(data) :: base64String when data: (ascii_string() | ascii_binary()), base64String: ascii_string()
  def encode_to_string(bin) when is_binary(bin), do: encode_to_string(binary_to_list(bin))

  def encode_to_string(list) when is_list(list), do: encode_list_to_string(list)

  @spec mime_decode(base64) :: data when base64: (ascii_string() | ascii_binary()), data: ascii_binary()
  def mime_decode(bin) when is_binary(bin), do: mime_decode_binary(bin, <<>>)

  def mime_decode(list) when is_list(list), do: mime_decode_list(list, <<>>)

  @spec mime_decode_to_string(base64) :: dataString when base64: (ascii_string() | ascii_binary()), dataString: ascii_string()
  def mime_decode_to_string(bin) when is_binary(bin), do: mime_decode_to_string(binary_to_list(bin))

  def mime_decode_to_string(list) when is_list(list), do: mime_decode_list_to_string(list)

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  # Private Functions

  defp decode_binary(<<c1 :: 8, cs :: bits>>, a) do
    case b64d(c1) do
      :ws ->
        decode_binary(cs, a)
      b1 ->
        decode_binary(cs, a, b1)
    end
  end

  defp decode_binary(<<>>, a), do: a

  defp decode_binary(<<c2 :: 8, cs :: bits>>, a, b1) do
    case b64d(c2) do
      :ws ->
        decode_binary(cs, a, b1)
      b2 ->
        decode_binary(cs, a, b1, b2)
    end
  end

  defp decode_binary(<<c3 :: 8, cs :: bits>>, a, b1, b2) do
    case b64d(c3) do
      :ws ->
        decode_binary(cs, a, b1, b2)
      b3 ->
        decode_binary(cs, a, b1, b2, b3)
    end
  end

  defp decode_binary(<<c4 :: 8, cs :: bits>>, a, b1, b2, b3) do
    case b64d(c4) do
      :ws ->
        decode_binary(cs, a, b1, b2, b3)
      :eq when b3 === :eq ->
        only_ws_binary(cs, <<a :: bits, b1 :: 6, b2 >>> 4 :: 2>>)
      :eq ->
        only_ws_binary(cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>)
      b4 ->
        decode_binary(cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6>>)
    end
  end

  defp decode_list([c1 | cs], a) do
    case b64d(c1) do
      :ws ->
        decode_list(cs, a)
      b1 ->
        decode_list(cs, a, b1)
    end
  end

  defp decode_list([], a), do: a

  defp decode_list([c2 | cs], a, b1) do
    case b64d(c2) do
      :ws ->
        decode_list(cs, a, b1)
      b2 ->
        decode_list(cs, a, b1, b2)
    end
  end

  defp decode_list([c3 | cs], a, b1, b2) do
    case b64d(c3) do
      :ws ->
        decode_list(cs, a, b1, b2)
      b3 ->
        decode_list(cs, a, b1, b2, b3)
    end
  end

  defp decode_list([c4 | cs], a, b1, b2, b3) do
    case b64d(c4) do
      :ws ->
        decode_list(cs, a, b1, b2, b3)
      :eq when b3 === :eq ->
        only_ws(cs, <<a :: bits, b1 :: 6, b2 >>> 4 :: 2>>)
      :eq ->
        only_ws(cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>)
      b4 ->
        decode_list(cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6>>)
    end
  end

  defp decode_list_to_string([c1 | cs]) do
    case b64d(c1) do
      :ws ->
        decode_list_to_string(cs)
      b1 ->
        decode_list_to_string(cs, b1)
    end
  end

  defp decode_list_to_string([]), do: []

  defp decode_list_to_string([c2 | cs], b1) do
    case b64d(c2) do
      :ws ->
        decode_list_to_string(cs, b1)
      b2 ->
        decode_list_to_string(cs, b1, b2)
    end
  end

  defp decode_list_to_string([c3 | cs], b1, b2) do
    case b64d(c3) do
      :ws ->
        decode_list_to_string(cs, b1, b2)
      b3 ->
        decode_list_to_string(cs, b1, b2, b3)
    end
  end

  defp decode_list_to_string([c4 | cs], b1, b2, b3) do
    case b64d(c4) do
      :ws ->
        decode_list_to_string(cs, b1, b2, b3)
      :eq when b3 === :eq ->
        only_ws(cs, binary_to_list(<<b1 :: 6, b2 >>> 4 :: 2>>))
      :eq ->
        only_ws(cs, binary_to_list(<<b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>))
      b4 ->
        bits4x6 = b1 <<< 18 ||| b2 <<< 12 ||| b3 <<< 6 ||| b4
        octet1 = bits4x6 >>> 16
        octet2 = bits4x6 >>> 8 &&& 255
        octet3 = bits4x6 &&& 255
        [octet1, octet2, octet3 | decode_list_to_string(cs)]
    end
  end

  defp encode_binary(<<>>, a), do: a

  defp encode_binary(<<b1 :: 8>>, a), do: <<a :: bits, b64e(b1 >>> 2) :: 8, b64e(b1 &&& 3 <<< 4) :: 8, ?= :: 8, ?= :: 8>>

  defp encode_binary(<<b1 :: 8, b2 :: 8>>, a), do: <<a :: bits, b64e(b1 >>> 2) :: 8, b64e(b1 &&& 3 <<< 4 ||| b2 >>> 4) :: 8, b64e(b2 &&& 15 <<< 2) :: 8, ?= :: 8>>

  defp encode_binary(<<b1 :: 8, b2 :: 8, b3 :: 8, ls :: bits>>, a) do
    bB = b1 <<< 16 ||| b2 <<< 8 ||| b3
    encode_binary(ls, <<a :: bits, b64e(bB >>> 18) :: 8, b64e(bB >>> 12 &&& 63) :: 8, b64e(bB >>> 6 &&& 63) :: 8, b64e(bB &&& 63) :: 8>>)
  end

  defp encode_list([], a), do: a

  defp encode_list([b1], a), do: <<a :: bits, b64e(b1 >>> 2) :: 8, b64e(b1 &&& 3 <<< 4) :: 8, ?= :: 8, ?= :: 8>>

  defp encode_list([b1, b2], a), do: <<a :: bits, b64e(b1 >>> 2) :: 8, b64e(b1 &&& 3 <<< 4 ||| b2 >>> 4) :: 8, b64e(b2 &&& 15 <<< 2) :: 8, ?= :: 8>>

  defp encode_list([b1, b2, b3 | ls], a) do
    bB = b1 <<< 16 ||| b2 <<< 8 ||| b3
    encode_list(ls, <<a :: bits, b64e(bB >>> 18) :: 8, b64e(bB >>> 12 &&& 63) :: 8, b64e(bB >>> 6 &&& 63) :: 8, b64e(bB &&& 63) :: 8>>)
  end

  defp encode_list_to_string([]), do: []

  defp encode_list_to_string([b1]), do: [b64e(b1 >>> 2), b64e(b1 &&& 3 <<< 4), ?=, ?=]

  defp encode_list_to_string([b1, b2]), do: [b64e(b1 >>> 2), b64e(b1 &&& 3 <<< 4 ||| b2 >>> 4), b64e(b2 &&& 15 <<< 2), ?=]

  defp encode_list_to_string([b1, b2, b3 | ls]) do
    bB = b1 <<< 16 ||| b2 <<< 8 ||| b3
    [b64e(bB >>> 18), b64e(bB >>> 12 &&& 63), b64e(bB >>> 6 &&& 63), b64e(bB &&& 63) | encode_list_to_string(ls)]
  end

  defp mime_decode_binary(<<0 :: 8, cs :: bits>>, a), do: mime_decode_binary(cs, a)

  defp mime_decode_binary(<<c1 :: 8, cs :: bits>>, a) do
    case b64d(c1) do
      b1 when is_integer(b1) ->
        mime_decode_binary(cs, a, b1)
      _ ->
        mime_decode_binary(cs, a)
    end
  end

  defp mime_decode_binary(<<>>, a), do: a

  defp mime_decode_binary(<<0 :: 8, cs :: bits>>, a, b1), do: mime_decode_binary(cs, a, b1)

  defp mime_decode_binary(<<c2 :: 8, cs :: bits>>, a, b1) do
    case b64d(c2) do
      b2 when is_integer(b2) ->
        mime_decode_binary(cs, a, b1, b2)
      _ ->
        mime_decode_binary(cs, a, b1)
    end
  end

  defp mime_decode_binary(<<0 :: 8, cs :: bits>>, a, b1, b2), do: mime_decode_binary(cs, a, b1, b2)

  defp mime_decode_binary(<<c3 :: 8, cs :: bits>>, a, b1, b2) do
    case b64d(c3) do
      b3 when is_integer(b3) ->
        mime_decode_binary(cs, a, b1, b2, b3)
      :eq = b3 ->
        mime_decode_binary_after_eq(cs, a, b1, b2, b3)
      _ ->
        mime_decode_binary(cs, a, b1, b2)
    end
  end

  defp mime_decode_binary(<<0 :: 8, cs :: bits>>, a, b1, b2, b3), do: mime_decode_binary(cs, a, b1, b2, b3)

  defp mime_decode_binary(<<c4 :: 8, cs :: bits>>, a, b1, b2, b3) do
    case b64d(c4) do
      b4 when is_integer(b4) ->
        mime_decode_binary(cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6>>)
      :eq ->
        mime_decode_binary_after_eq(cs, a, b1, b2, b3)
      _ ->
        mime_decode_binary(cs, a, b1, b2, b3)
    end
  end

  defp mime_decode_binary_after_eq(<<0 :: 8, cs :: bits>>, a, b1, b2, b3), do: mime_decode_binary_after_eq(cs, a, b1, b2, b3)

  defp mime_decode_binary_after_eq(<<c :: 8, cs :: bits>>, a, b1, b2, b3) do
    case b64d(c) do
      b when is_integer(b) ->
        case b3 do
          :eq ->
            mime_decode_binary(cs, a, b1, b2, b)
          _ ->
            mime_decode_binary(cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b :: 6>>)
        end
      _ ->
        mime_decode_binary_after_eq(cs, a, b1, b2, b3)
    end
  end

  defp mime_decode_binary_after_eq(<<>>, a, b1, b2, :eq), do: <<a :: bits, b1 :: 6, b2 >>> 4 :: 2>>

  defp mime_decode_binary_after_eq(<<>>, a, b1, b2, b3), do: <<a :: bits, b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>

  defp mime_decode_list([0 | cs], a), do: mime_decode_list(cs, a)

  defp mime_decode_list([c1 | cs], a) do
    case b64d(c1) do
      b1 when is_integer(b1) ->
        mime_decode_list(cs, a, b1)
      _ ->
        mime_decode_list(cs, a)
    end
  end

  defp mime_decode_list([], a), do: a

  defp mime_decode_list([0 | cs], a, b1), do: mime_decode_list(cs, a, b1)

  defp mime_decode_list([c2 | cs], a, b1) do
    case b64d(c2) do
      b2 when is_integer(b2) ->
        mime_decode_list(cs, a, b1, b2)
      _ ->
        mime_decode_list(cs, a, b1)
    end
  end

  defp mime_decode_list([0 | cs], a, b1, b2), do: mime_decode_list(cs, a, b1, b2)

  defp mime_decode_list([c3 | cs], a, b1, b2) do
    case b64d(c3) do
      b3 when is_integer(b3) ->
        mime_decode_list(cs, a, b1, b2, b3)
      :eq = b3 ->
        mime_decode_list_after_eq(cs, a, b1, b2, b3)
      _ ->
        mime_decode_list(cs, a, b1, b2)
    end
  end

  defp mime_decode_list([0 | cs], a, b1, b2, b3), do: mime_decode_list(cs, a, b1, b2, b3)

  defp mime_decode_list([c4 | cs], a, b1, b2, b3) do
    case b64d(c4) do
      b4 when is_integer(b4) ->
        mime_decode_list(cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b4 :: 6>>)
      :eq ->
        mime_decode_list_after_eq(cs, a, b1, b2, b3)
      _ ->
        mime_decode_list(cs, a, b1, b2, b3)
    end
  end

  defp mime_decode_list_after_eq([0 | cs], a, b1, b2, b3), do: mime_decode_list_after_eq(cs, a, b1, b2, b3)

  defp mime_decode_list_after_eq([c | cs], a, b1, b2, b3) do
    case b64d(c) do
      b when is_integer(b) ->
        case b3 do
          :eq ->
            mime_decode_list(cs, a, b1, b2, b)
          _ ->
            mime_decode_list(cs, <<a :: bits, b1 :: 6, b2 :: 6, b3 :: 6, b :: 6>>)
        end
      _ ->
        mime_decode_list_after_eq(cs, a, b1, b2, b3)
    end
  end

  defp mime_decode_list_after_eq([], a, b1, b2, :eq), do: <<a :: bits, b1 :: 6, b2 >>> 4 :: 2>>

  defp mime_decode_list_after_eq([], a, b1, b2, b3), do: <<a :: bits, b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>

  defp mime_decode_list_to_string([0 | cs]), do: mime_decode_list_to_string(cs)

  defp mime_decode_list_to_string([c1 | cs]) do
    case b64d(c1) do
      b1 when is_integer(b1) ->
        mime_decode_list_to_string(cs, b1)
      _ ->
        mime_decode_list_to_string(cs)
    end
  end

  defp mime_decode_list_to_string([]), do: []

  defp mime_decode_list_to_string([0 | cs], b1), do: mime_decode_list_to_string(cs, b1)

  defp mime_decode_list_to_string([c2 | cs], b1) do
    case b64d(c2) do
      b2 when is_integer(b2) ->
        mime_decode_list_to_string(cs, b1, b2)
      _ ->
        mime_decode_list_to_string(cs, b1)
    end
  end

  defp mime_decode_list_to_string([0 | cs], b1, b2), do: mime_decode_list_to_string(cs, b1, b2)

  defp mime_decode_list_to_string([c3 | cs], b1, b2) do
    case b64d(c3) do
      b3 when is_integer(b3) ->
        mime_decode_list_to_string(cs, b1, b2, b3)
      :eq = b3 ->
        mime_decode_list_to_string_after_eq(cs, b1, b2, b3)
      _ ->
        mime_decode_list_to_string(cs, b1, b2)
    end
  end

  defp mime_decode_list_to_string([0 | cs], b1, b2, b3), do: mime_decode_list_to_string(cs, b1, b2, b3)

  defp mime_decode_list_to_string([c4 | cs], b1, b2, b3) do
    case b64d(c4) do
      b4 when is_integer(b4) ->
        bits4x6 = b1 <<< 18 ||| b2 <<< 12 ||| b3 <<< 6 ||| b4
        octet1 = bits4x6 >>> 16
        octet2 = bits4x6 >>> 8 &&& 255
        octet3 = bits4x6 &&& 255
        [octet1, octet2, octet3 | mime_decode_list_to_string(cs)]
      :eq ->
        mime_decode_list_to_string_after_eq(cs, b1, b2, b3)
      _ ->
        mime_decode_list_to_string(cs, b1, b2, b3)
    end
  end

  defp mime_decode_list_to_string_after_eq([0 | cs], b1, b2, b3), do: mime_decode_list_to_string_after_eq(cs, b1, b2, b3)

  defp mime_decode_list_to_string_after_eq([c | cs], b1, b2, b3) do
    case b64d(c) do
      b when is_integer(b) ->
        case b3 do
          :eq ->
            mime_decode_list_to_string(cs, b1, b2, b)
          _ ->
            bits4x6 = b1 <<< 18 ||| b2 <<< 12 ||| b3 <<< 6 ||| b
            octet1 = bits4x6 >>> 16
            octet2 = bits4x6 >>> 8 &&& 255
            octet3 = bits4x6 &&& 255
            [octet1, octet2, octet3 | mime_decode_list_to_string(cs)]
        end
      _ ->
        mime_decode_list_to_string_after_eq(cs, b1, b2, b3)
    end
  end

  defp mime_decode_list_to_string_after_eq([], b1, b2, :eq), do: binary_to_list(<<b1 :: 6, b2 >>> 4 :: 2>>)

  defp mime_decode_list_to_string_after_eq([], b1, b2, b3), do: binary_to_list(<<b1 :: 6, b2 :: 6, b3 >>> 2 :: 4>>)

  defp only_ws([], a), do: a

  defp only_ws([c | cs], a) do
    case b64d(c) do
      :ws ->
        only_ws(cs, a)
    end
  end

  defp only_ws_binary(<<>>, a), do: a

  defp only_ws_binary(<<c :: 8, cs :: bits>>, a) do
    case b64d(c) do
      :ws ->
        only_ws_binary(cs, a)
    end
  end
end
