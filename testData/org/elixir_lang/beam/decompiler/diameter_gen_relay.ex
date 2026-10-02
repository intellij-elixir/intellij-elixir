# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :diameter_gen_relay do

  # Functions

  def unquote(:"#get-")(_), do: :erlang.error(:badarg)

  def unquote(:"#get-")(_, _), do: :erlang.error(:badarg)

  def unquote(:"#info-")(recName), do: apply(__MODULE__, :"#info-", [recName, :fields])

  def unquote(:"#info-")(_, _), do: :erlang.error(:badarg)

  def unquote(:"#new-")(_), do: :erlang.error(:badarg)

  def unquote(:"#new-")(_, _), do: :erlang.error(:badarg)

  def unquote(:"#set-")(_, _), do: :erlang.error(:badarg)

  def avp(_, _, _, _), do: :erlang.error(:badarg)

  def avp_arity(_), do: :erlang.error(:badarg)

  def avp_arity(_, _), do: 0

  def avp_header(_), do: :erlang.error(:badarg)

  def avp_name(_, _), do: :AVP

  def decode_avps(name, avps, opts), do: :diameter_gen.decode_avps(name, avps, %{opts | :module => :diameter_gen_relay})

  def dict(), do: [1, {:avp_types, []}, {:avp_vendor_id, []}, {:codecs, []}, {:command_codes, []}, {:custom_types, []}, {:define, []}, {:enum, []}, {:grouped, []}, {:id, 4294967295}, {:import_avps, []}, {:import_enums, []}, {:import_groups, []}, {:inherits, []}, {:messages, []}, {:name, 'diameter_gen_relay'}, {:prefix, 'diameter_relay'}, {:vendor, {0, 'IETF'}}]

  def empty_value(name, opts), do: empty(name, opts)

  def encode_avps(name, avps, opts), do: :diameter_gen.encode_avps(name, avps, %{opts | :module => :diameter_gen_relay})

  def enumerated_avp(_, _, _), do: :erlang.error(:badarg)

  def grouped_avp(t, name, data, opts), do: :diameter_gen.grouped_avp(t, name, data, opts)

  def id(), do: 4294967295

  def module_info() do
    # body not decompiled
  end

  def module_info(arg1) do
    # body not decompiled
  end

  def msg2rec(_), do: :erlang.error(:badarg)

  def msg_header(_), do: :erlang.error(:badarg)

  def msg_name(_, _), do: :""

  def name(), do: :diameter_gen_relay

  def name2rec(t), do: msg2rec(t)

  def rec2msg(_), do: :erlang.error(:badarg)

  def vendor_id(), do: 0

  def vendor_name(), do: :IETF

  # Private Functions

  defp empty(name, opts), do: :diameter_gen.empty(name, opts)
end
