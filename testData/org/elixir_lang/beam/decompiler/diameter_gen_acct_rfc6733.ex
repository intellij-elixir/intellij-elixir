# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :diameter_gen_acct_rfc6733 do

  # Functions

  def unquote(:"#get-")(diameter_base_accounting_ACR() = rec), do: [:diameter_base_accounting_ACR | apply(__MODULE__, :"#get-diameter_base_accounting_ACR", [rec])]

  def unquote(:"#get-")(diameter_base_accounting_ACA() = rec), do: [:diameter_base_accounting_ACA | apply(__MODULE__, :"#get-diameter_base_accounting_ACA", [rec])]

  def unquote(:"#get-")(unquote(:"diameter_base_accounting_Proxy-Info")() = rec), do: [:"diameter_base_accounting_Proxy-Info" | apply(__MODULE__, :"#get-diameter_base_accounting_Proxy-Info", [rec])]

  def unquote(:"#get-")(unquote(:"diameter_base_accounting_Failed-AVP")() = rec), do: [:"diameter_base_accounting_Failed-AVP" | apply(__MODULE__, :"#get-diameter_base_accounting_Failed-AVP", [rec])]

  def unquote(:"#get-")(unquote(:"diameter_base_accounting_Experimental-Result")() = rec), do: [:"diameter_base_accounting_Experimental-Result" | apply(__MODULE__, :"#get-diameter_base_accounting_Experimental-Result", [rec])]

  def unquote(:"#get-")(unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")() = rec), do: [:"diameter_base_accounting_Vendor-Specific-Application-Id" | apply(__MODULE__, :"#get-diameter_base_accounting_Vendor-Specific-Application-Id", [rec])]

  def unquote(:"#get-")(_), do: :erlang.error(:badarg)

  def unquote(:"#get-")(attrs, diameter_base_accounting_ACR() = rec), do: apply(__MODULE__, :"#get-diameter_base_accounting_ACR", [attrs, rec])

  def unquote(:"#get-")(attrs, diameter_base_accounting_ACA() = rec), do: apply(__MODULE__, :"#get-diameter_base_accounting_ACA", [attrs, rec])

  def unquote(:"#get-")(attrs, unquote(:"diameter_base_accounting_Proxy-Info")() = rec), do: apply(__MODULE__, :"#get-diameter_base_accounting_Proxy-Info", [attrs, rec])

  def unquote(:"#get-")(attrs, unquote(:"diameter_base_accounting_Failed-AVP")() = rec), do: apply(__MODULE__, :"#get-diameter_base_accounting_Failed-AVP", [attrs, rec])

  def unquote(:"#get-")(attrs, unquote(:"diameter_base_accounting_Experimental-Result")() = rec), do: apply(__MODULE__, :"#get-diameter_base_accounting_Experimental-Result", [attrs, rec])

  def unquote(:"#get-")(attrs, unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")() = rec), do: apply(__MODULE__, :"#get-diameter_base_accounting_Vendor-Specific-Application-Id", [attrs, rec])

  def unquote(:"#get-")(_, _), do: :erlang.error(:badarg)

  def unquote(:"#get-diameter_base_accounting_ACA")(rec), do: :lists.zip([:"Session-Id", :"Result-Code", :"Origin-Host", :"Origin-Realm", :"Accounting-Record-Type", :"Accounting-Record-Number", :"Acct-Application-Id", :"Vendor-Specific-Application-Id", :"User-Name", :"Accounting-Sub-Session-Id", :"Acct-Session-Id", :"Acct-Multi-Session-Id", :"Error-Message", :"Error-Reporting-Host", :"Failed-AVP", :"Acct-Interim-Interval", :"Accounting-Realtime-Required", :"Origin-State-Id", :"Event-Timestamp", :"Proxy-Info", :AVP], tl(tuple_to_list(rec)))

  def unquote(:"#get-diameter_base_accounting_ACA")(attrs, rec) when is_list(attrs) do
    for a <- attrs do
      apply(__MODULE__, :"#get-diameter_base_accounting_ACA", [a, rec])
    end
  end

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Session-Id", rec), do: diameter_base_accounting_ACA(rec, :"Session-Id")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Result-Code", rec), do: diameter_base_accounting_ACA(rec, :"Result-Code")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Origin-Host", rec), do: diameter_base_accounting_ACA(rec, :"Origin-Host")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Origin-Realm", rec), do: diameter_base_accounting_ACA(rec, :"Origin-Realm")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Accounting-Record-Type", rec), do: diameter_base_accounting_ACA(rec, :"Accounting-Record-Type")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Accounting-Record-Number", rec), do: diameter_base_accounting_ACA(rec, :"Accounting-Record-Number")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Acct-Application-Id", rec), do: diameter_base_accounting_ACA(rec, :"Acct-Application-Id")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Vendor-Specific-Application-Id", rec), do: diameter_base_accounting_ACA(rec, :"Vendor-Specific-Application-Id")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"User-Name", rec), do: diameter_base_accounting_ACA(rec, :"User-Name")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Accounting-Sub-Session-Id", rec), do: diameter_base_accounting_ACA(rec, :"Accounting-Sub-Session-Id")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Acct-Session-Id", rec), do: diameter_base_accounting_ACA(rec, :"Acct-Session-Id")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Acct-Multi-Session-Id", rec), do: diameter_base_accounting_ACA(rec, :"Acct-Multi-Session-Id")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Error-Message", rec), do: diameter_base_accounting_ACA(rec, :"Error-Message")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Error-Reporting-Host", rec), do: diameter_base_accounting_ACA(rec, :"Error-Reporting-Host")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Failed-AVP", rec), do: diameter_base_accounting_ACA(rec, :"Failed-AVP")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Acct-Interim-Interval", rec), do: diameter_base_accounting_ACA(rec, :"Acct-Interim-Interval")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Accounting-Realtime-Required", rec), do: diameter_base_accounting_ACA(rec, :"Accounting-Realtime-Required")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Origin-State-Id", rec), do: diameter_base_accounting_ACA(rec, :"Origin-State-Id")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Event-Timestamp", rec), do: diameter_base_accounting_ACA(rec, :"Event-Timestamp")

  def unquote(:"#get-diameter_base_accounting_ACA")(:"Proxy-Info", rec), do: diameter_base_accounting_ACA(rec, :"Proxy-Info")

  def unquote(:"#get-diameter_base_accounting_ACA")(:AVP, rec), do: diameter_base_accounting_ACA(rec, :AVP)

  def unquote(:"#get-diameter_base_accounting_ACR")(rec), do: :lists.zip([:"Session-Id", :"Origin-Host", :"Origin-Realm", :"Destination-Realm", :"Accounting-Record-Type", :"Accounting-Record-Number", :"Acct-Application-Id", :"Vendor-Specific-Application-Id", :"User-Name", :"Destination-Host", :"Accounting-Sub-Session-Id", :"Acct-Session-Id", :"Acct-Multi-Session-Id", :"Acct-Interim-Interval", :"Accounting-Realtime-Required", :"Origin-State-Id", :"Event-Timestamp", :"Proxy-Info", :"Route-Record", :AVP], tl(tuple_to_list(rec)))

  def unquote(:"#get-diameter_base_accounting_ACR")(attrs, rec) when is_list(attrs) do
    for a <- attrs do
      apply(__MODULE__, :"#get-diameter_base_accounting_ACR", [a, rec])
    end
  end

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Session-Id", rec), do: diameter_base_accounting_ACR(rec, :"Session-Id")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Origin-Host", rec), do: diameter_base_accounting_ACR(rec, :"Origin-Host")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Origin-Realm", rec), do: diameter_base_accounting_ACR(rec, :"Origin-Realm")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Destination-Realm", rec), do: diameter_base_accounting_ACR(rec, :"Destination-Realm")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Accounting-Record-Type", rec), do: diameter_base_accounting_ACR(rec, :"Accounting-Record-Type")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Accounting-Record-Number", rec), do: diameter_base_accounting_ACR(rec, :"Accounting-Record-Number")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Acct-Application-Id", rec), do: diameter_base_accounting_ACR(rec, :"Acct-Application-Id")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Vendor-Specific-Application-Id", rec), do: diameter_base_accounting_ACR(rec, :"Vendor-Specific-Application-Id")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"User-Name", rec), do: diameter_base_accounting_ACR(rec, :"User-Name")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Destination-Host", rec), do: diameter_base_accounting_ACR(rec, :"Destination-Host")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Accounting-Sub-Session-Id", rec), do: diameter_base_accounting_ACR(rec, :"Accounting-Sub-Session-Id")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Acct-Session-Id", rec), do: diameter_base_accounting_ACR(rec, :"Acct-Session-Id")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Acct-Multi-Session-Id", rec), do: diameter_base_accounting_ACR(rec, :"Acct-Multi-Session-Id")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Acct-Interim-Interval", rec), do: diameter_base_accounting_ACR(rec, :"Acct-Interim-Interval")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Accounting-Realtime-Required", rec), do: diameter_base_accounting_ACR(rec, :"Accounting-Realtime-Required")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Origin-State-Id", rec), do: diameter_base_accounting_ACR(rec, :"Origin-State-Id")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Event-Timestamp", rec), do: diameter_base_accounting_ACR(rec, :"Event-Timestamp")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Proxy-Info", rec), do: diameter_base_accounting_ACR(rec, :"Proxy-Info")

  def unquote(:"#get-diameter_base_accounting_ACR")(:"Route-Record", rec), do: diameter_base_accounting_ACR(rec, :"Route-Record")

  def unquote(:"#get-diameter_base_accounting_ACR")(:AVP, rec), do: diameter_base_accounting_ACR(rec, :AVP)

  def unquote(:"#get-diameter_base_accounting_Experimental-Result")(rec), do: :lists.zip([:"Vendor-Id", :"Experimental-Result-Code"], tl(tuple_to_list(rec)))

  def unquote(:"#get-diameter_base_accounting_Experimental-Result")(attrs, rec) when is_list(attrs) do
    for a <- attrs do
      apply(__MODULE__, :"#get-diameter_base_accounting_Experimental-Result", [a, rec])
    end
  end

  def unquote(:"#get-diameter_base_accounting_Experimental-Result")(:"Vendor-Id", rec), do: unquote(:"diameter_base_accounting_Experimental-Result")(rec, :"Vendor-Id")

  def unquote(:"#get-diameter_base_accounting_Experimental-Result")(:"Experimental-Result-Code", rec), do: unquote(:"diameter_base_accounting_Experimental-Result")(rec, :"Experimental-Result-Code")

  def unquote(:"#get-diameter_base_accounting_Failed-AVP")(rec), do: :lists.zip([:AVP], tl(tuple_to_list(rec)))

  def unquote(:"#get-diameter_base_accounting_Failed-AVP")(attrs, rec) when is_list(attrs) do
    for a <- attrs do
      apply(__MODULE__, :"#get-diameter_base_accounting_Failed-AVP", [a, rec])
    end
  end

  def unquote(:"#get-diameter_base_accounting_Failed-AVP")(:AVP, rec), do: unquote(:"diameter_base_accounting_Failed-AVP")(rec, :AVP)

  def unquote(:"#get-diameter_base_accounting_Proxy-Info")(rec), do: :lists.zip([:"Proxy-Host", :"Proxy-State", :AVP], tl(tuple_to_list(rec)))

  def unquote(:"#get-diameter_base_accounting_Proxy-Info")(attrs, rec) when is_list(attrs) do
    for a <- attrs do
      apply(__MODULE__, :"#get-diameter_base_accounting_Proxy-Info", [a, rec])
    end
  end

  def unquote(:"#get-diameter_base_accounting_Proxy-Info")(:"Proxy-Host", rec), do: unquote(:"diameter_base_accounting_Proxy-Info")(rec, :"Proxy-Host")

  def unquote(:"#get-diameter_base_accounting_Proxy-Info")(:"Proxy-State", rec), do: unquote(:"diameter_base_accounting_Proxy-Info")(rec, :"Proxy-State")

  def unquote(:"#get-diameter_base_accounting_Proxy-Info")(:AVP, rec), do: unquote(:"diameter_base_accounting_Proxy-Info")(rec, :AVP)

  def unquote(:"#get-diameter_base_accounting_Vendor-Specific-Application-Id")(rec), do: :lists.zip([:"Vendor-Id", :"Auth-Application-Id", :"Acct-Application-Id"], tl(tuple_to_list(rec)))

  def unquote(:"#get-diameter_base_accounting_Vendor-Specific-Application-Id")(attrs, rec) when is_list(attrs) do
    for a <- attrs do
      apply(__MODULE__, :"#get-diameter_base_accounting_Vendor-Specific-Application-Id", [a, rec])
    end
  end

  def unquote(:"#get-diameter_base_accounting_Vendor-Specific-Application-Id")(:"Vendor-Id", rec), do: unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")(rec, :"Vendor-Id")

  def unquote(:"#get-diameter_base_accounting_Vendor-Specific-Application-Id")(:"Auth-Application-Id", rec), do: unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")(rec, :"Auth-Application-Id")

  def unquote(:"#get-diameter_base_accounting_Vendor-Specific-Application-Id")(:"Acct-Application-Id", rec), do: unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")(rec, :"Acct-Application-Id")

  def unquote(:"#info-")(recName), do: apply(__MODULE__, :"#info-", [recName, :fields])

  def unquote(:"#info-")(:diameter_base_accounting_ACR, info), do: apply(__MODULE__, :"#info-diameter_base_accounting_ACR", [info])

  def unquote(:"#info-")(:diameter_base_accounting_ACA, info), do: apply(__MODULE__, :"#info-diameter_base_accounting_ACA", [info])

  def unquote(:"#info-")(:"diameter_base_accounting_Proxy-Info", info), do: apply(__MODULE__, :"#info-diameter_base_accounting_Proxy-Info", [info])

  def unquote(:"#info-")(:"diameter_base_accounting_Failed-AVP", info), do: apply(__MODULE__, :"#info-diameter_base_accounting_Failed-AVP", [info])

  def unquote(:"#info-")(:"diameter_base_accounting_Experimental-Result", info), do: apply(__MODULE__, :"#info-diameter_base_accounting_Experimental-Result", [info])

  def unquote(:"#info-")(:"diameter_base_accounting_Vendor-Specific-Application-Id", info), do: apply(__MODULE__, :"#info-diameter_base_accounting_Vendor-Specific-Application-Id", [info])

  def unquote(:"#info-")(_, _), do: :erlang.error(:badarg)

  def unquote(:"#info-diameter_base_accounting_ACA")(:fields), do: record_info(:fields, :diameter_base_accounting_ACA)

  def unquote(:"#info-diameter_base_accounting_ACA")(:size), do: record_info(:size, :diameter_base_accounting_ACA)

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Session-Id"}), do: diameter_base_accounting_ACA(:"Session-Id")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Result-Code"}), do: diameter_base_accounting_ACA(:"Result-Code")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Origin-Host"}), do: diameter_base_accounting_ACA(:"Origin-Host")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Origin-Realm"}), do: diameter_base_accounting_ACA(:"Origin-Realm")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Accounting-Record-Type"}), do: diameter_base_accounting_ACA(:"Accounting-Record-Type")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Accounting-Record-Number"}), do: diameter_base_accounting_ACA(:"Accounting-Record-Number")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Acct-Application-Id"}), do: diameter_base_accounting_ACA(:"Acct-Application-Id")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Vendor-Specific-Application-Id"}), do: diameter_base_accounting_ACA(:"Vendor-Specific-Application-Id")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"User-Name"}), do: diameter_base_accounting_ACA(:"User-Name")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Accounting-Sub-Session-Id"}), do: diameter_base_accounting_ACA(:"Accounting-Sub-Session-Id")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Acct-Session-Id"}), do: diameter_base_accounting_ACA(:"Acct-Session-Id")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Acct-Multi-Session-Id"}), do: diameter_base_accounting_ACA(:"Acct-Multi-Session-Id")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Error-Message"}), do: diameter_base_accounting_ACA(:"Error-Message")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Error-Reporting-Host"}), do: diameter_base_accounting_ACA(:"Error-Reporting-Host")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Failed-AVP"}), do: diameter_base_accounting_ACA(:"Failed-AVP")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Acct-Interim-Interval"}), do: diameter_base_accounting_ACA(:"Acct-Interim-Interval")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Accounting-Realtime-Required"}), do: diameter_base_accounting_ACA(:"Accounting-Realtime-Required")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Origin-State-Id"}), do: diameter_base_accounting_ACA(:"Origin-State-Id")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Event-Timestamp"}), do: diameter_base_accounting_ACA(:"Event-Timestamp")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :"Proxy-Info"}), do: diameter_base_accounting_ACA(:"Proxy-Info")

  def unquote(:"#info-diameter_base_accounting_ACA")({:index, :AVP}), do: diameter_base_accounting_ACA(:AVP)

  def unquote(:"#info-diameter_base_accounting_ACR")(:fields), do: record_info(:fields, :diameter_base_accounting_ACR)

  def unquote(:"#info-diameter_base_accounting_ACR")(:size), do: record_info(:size, :diameter_base_accounting_ACR)

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Session-Id"}), do: diameter_base_accounting_ACR(:"Session-Id")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Origin-Host"}), do: diameter_base_accounting_ACR(:"Origin-Host")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Origin-Realm"}), do: diameter_base_accounting_ACR(:"Origin-Realm")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Destination-Realm"}), do: diameter_base_accounting_ACR(:"Destination-Realm")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Accounting-Record-Type"}), do: diameter_base_accounting_ACR(:"Accounting-Record-Type")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Accounting-Record-Number"}), do: diameter_base_accounting_ACR(:"Accounting-Record-Number")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Acct-Application-Id"}), do: diameter_base_accounting_ACR(:"Acct-Application-Id")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Vendor-Specific-Application-Id"}), do: diameter_base_accounting_ACR(:"Vendor-Specific-Application-Id")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"User-Name"}), do: diameter_base_accounting_ACR(:"User-Name")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Destination-Host"}), do: diameter_base_accounting_ACR(:"Destination-Host")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Accounting-Sub-Session-Id"}), do: diameter_base_accounting_ACR(:"Accounting-Sub-Session-Id")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Acct-Session-Id"}), do: diameter_base_accounting_ACR(:"Acct-Session-Id")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Acct-Multi-Session-Id"}), do: diameter_base_accounting_ACR(:"Acct-Multi-Session-Id")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Acct-Interim-Interval"}), do: diameter_base_accounting_ACR(:"Acct-Interim-Interval")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Accounting-Realtime-Required"}), do: diameter_base_accounting_ACR(:"Accounting-Realtime-Required")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Origin-State-Id"}), do: diameter_base_accounting_ACR(:"Origin-State-Id")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Event-Timestamp"}), do: diameter_base_accounting_ACR(:"Event-Timestamp")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Proxy-Info"}), do: diameter_base_accounting_ACR(:"Proxy-Info")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :"Route-Record"}), do: diameter_base_accounting_ACR(:"Route-Record")

  def unquote(:"#info-diameter_base_accounting_ACR")({:index, :AVP}), do: diameter_base_accounting_ACR(:AVP)

  def unquote(:"#info-diameter_base_accounting_Experimental-Result")(:fields), do: record_info(:fields, :"diameter_base_accounting_Experimental-Result")

  def unquote(:"#info-diameter_base_accounting_Experimental-Result")(:size), do: record_info(:size, :"diameter_base_accounting_Experimental-Result")

  def unquote(:"#info-diameter_base_accounting_Experimental-Result")({:index, :"Vendor-Id"}), do: unquote(:"diameter_base_accounting_Experimental-Result")(:"Vendor-Id")

  def unquote(:"#info-diameter_base_accounting_Experimental-Result")({:index, :"Experimental-Result-Code"}), do: unquote(:"diameter_base_accounting_Experimental-Result")(:"Experimental-Result-Code")

  def unquote(:"#info-diameter_base_accounting_Failed-AVP")(:fields), do: record_info(:fields, :"diameter_base_accounting_Failed-AVP")

  def unquote(:"#info-diameter_base_accounting_Failed-AVP")(:size), do: record_info(:size, :"diameter_base_accounting_Failed-AVP")

  def unquote(:"#info-diameter_base_accounting_Failed-AVP")({:index, :AVP}), do: unquote(:"diameter_base_accounting_Failed-AVP")(:AVP)

  def unquote(:"#info-diameter_base_accounting_Proxy-Info")(:fields), do: record_info(:fields, :"diameter_base_accounting_Proxy-Info")

  def unquote(:"#info-diameter_base_accounting_Proxy-Info")(:size), do: record_info(:size, :"diameter_base_accounting_Proxy-Info")

  def unquote(:"#info-diameter_base_accounting_Proxy-Info")({:index, :"Proxy-Host"}), do: unquote(:"diameter_base_accounting_Proxy-Info")(:"Proxy-Host")

  def unquote(:"#info-diameter_base_accounting_Proxy-Info")({:index, :"Proxy-State"}), do: unquote(:"diameter_base_accounting_Proxy-Info")(:"Proxy-State")

  def unquote(:"#info-diameter_base_accounting_Proxy-Info")({:index, :AVP}), do: unquote(:"diameter_base_accounting_Proxy-Info")(:AVP)

  def unquote(:"#info-diameter_base_accounting_Vendor-Specific-Application-Id")(:fields), do: record_info(:fields, :"diameter_base_accounting_Vendor-Specific-Application-Id")

  def unquote(:"#info-diameter_base_accounting_Vendor-Specific-Application-Id")(:size), do: record_info(:size, :"diameter_base_accounting_Vendor-Specific-Application-Id")

  def unquote(:"#info-diameter_base_accounting_Vendor-Specific-Application-Id")({:index, :"Vendor-Id"}), do: unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")(:"Vendor-Id")

  def unquote(:"#info-diameter_base_accounting_Vendor-Specific-Application-Id")({:index, :"Auth-Application-Id"}), do: unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")(:"Auth-Application-Id")

  def unquote(:"#info-diameter_base_accounting_Vendor-Specific-Application-Id")({:index, :"Acct-Application-Id"}), do: unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")(:"Acct-Application-Id")

  def unquote(:"#new-")(:diameter_base_accounting_ACR), do: diameter_base_accounting_ACR()

  def unquote(:"#new-")([:diameter_base_accounting_ACR | vals]), do: apply(__MODULE__, :"#new-diameter_base_accounting_ACR", [vals])

  def unquote(:"#new-")(:diameter_base_accounting_ACA), do: diameter_base_accounting_ACA()

  def unquote(:"#new-")([:diameter_base_accounting_ACA | vals]), do: apply(__MODULE__, :"#new-diameter_base_accounting_ACA", [vals])

  def unquote(:"#new-")(:"diameter_base_accounting_Proxy-Info"), do: unquote(:"diameter_base_accounting_Proxy-Info")()

  def unquote(:"#new-")([:"diameter_base_accounting_Proxy-Info" | vals]), do: apply(__MODULE__, :"#new-diameter_base_accounting_Proxy-Info", [vals])

  def unquote(:"#new-")(:"diameter_base_accounting_Failed-AVP"), do: unquote(:"diameter_base_accounting_Failed-AVP")()

  def unquote(:"#new-")([:"diameter_base_accounting_Failed-AVP" | vals]), do: apply(__MODULE__, :"#new-diameter_base_accounting_Failed-AVP", [vals])

  def unquote(:"#new-")(:"diameter_base_accounting_Experimental-Result"), do: unquote(:"diameter_base_accounting_Experimental-Result")()

  def unquote(:"#new-")([:"diameter_base_accounting_Experimental-Result" | vals]), do: apply(__MODULE__, :"#new-diameter_base_accounting_Experimental-Result", [vals])

  def unquote(:"#new-")(:"diameter_base_accounting_Vendor-Specific-Application-Id"), do: unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")()

  def unquote(:"#new-")([:"diameter_base_accounting_Vendor-Specific-Application-Id" | vals]), do: apply(__MODULE__, :"#new-diameter_base_accounting_Vendor-Specific-Application-Id", [vals])

  def unquote(:"#new-")(_), do: :erlang.error(:badarg)

  def unquote(:"#new-")(:diameter_base_accounting_ACR, vals), do: apply(__MODULE__, :"#new-diameter_base_accounting_ACR", [vals])

  def unquote(:"#new-")(:diameter_base_accounting_ACA, vals), do: apply(__MODULE__, :"#new-diameter_base_accounting_ACA", [vals])

  def unquote(:"#new-")(:"diameter_base_accounting_Proxy-Info", vals), do: apply(__MODULE__, :"#new-diameter_base_accounting_Proxy-Info", [vals])

  def unquote(:"#new-")(:"diameter_base_accounting_Failed-AVP", vals), do: apply(__MODULE__, :"#new-diameter_base_accounting_Failed-AVP", [vals])

  def unquote(:"#new-")(:"diameter_base_accounting_Experimental-Result", vals), do: apply(__MODULE__, :"#new-diameter_base_accounting_Experimental-Result", [vals])

  def unquote(:"#new-")(:"diameter_base_accounting_Vendor-Specific-Application-Id", vals), do: apply(__MODULE__, :"#new-diameter_base_accounting_Vendor-Specific-Application-Id", [vals])

  def unquote(:"#new-")(_, _), do: :erlang.error(:badarg)

  def unquote(:"#new-diameter_base_accounting_ACA")(), do: diameter_base_accounting_ACA()

  def unquote(:"#new-diameter_base_accounting_ACA")(vals), do: apply(__MODULE__, :"#set-diameter_base_accounting_ACA", [vals, diameter_base_accounting_ACA()])

  def unquote(:"#new-diameter_base_accounting_ACR")(), do: diameter_base_accounting_ACR()

  def unquote(:"#new-diameter_base_accounting_ACR")(vals), do: apply(__MODULE__, :"#set-diameter_base_accounting_ACR", [vals, diameter_base_accounting_ACR()])

  def unquote(:"#new-diameter_base_accounting_Experimental-Result")(), do: unquote(:"diameter_base_accounting_Experimental-Result")()

  def unquote(:"#new-diameter_base_accounting_Experimental-Result")(vals), do: apply(__MODULE__, :"#set-diameter_base_accounting_Experimental-Result", [vals, unquote(:"diameter_base_accounting_Experimental-Result")()])

  def unquote(:"#new-diameter_base_accounting_Failed-AVP")(), do: unquote(:"diameter_base_accounting_Failed-AVP")()

  def unquote(:"#new-diameter_base_accounting_Failed-AVP")(vals), do: apply(__MODULE__, :"#set-diameter_base_accounting_Failed-AVP", [vals, unquote(:"diameter_base_accounting_Failed-AVP")()])

  def unquote(:"#new-diameter_base_accounting_Proxy-Info")(), do: unquote(:"diameter_base_accounting_Proxy-Info")()

  def unquote(:"#new-diameter_base_accounting_Proxy-Info")(vals), do: apply(__MODULE__, :"#set-diameter_base_accounting_Proxy-Info", [vals, unquote(:"diameter_base_accounting_Proxy-Info")()])

  def unquote(:"#new-diameter_base_accounting_Vendor-Specific-Application-Id")(), do: unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")()

  def unquote(:"#new-diameter_base_accounting_Vendor-Specific-Application-Id")(vals), do: apply(__MODULE__, :"#set-diameter_base_accounting_Vendor-Specific-Application-Id", [vals, unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")()])

  def unquote(:"#set-")(vals, diameter_base_accounting_ACR() = rec), do: apply(__MODULE__, :"#set-diameter_base_accounting_ACR", [vals, rec])

  def unquote(:"#set-")(vals, diameter_base_accounting_ACA() = rec), do: apply(__MODULE__, :"#set-diameter_base_accounting_ACA", [vals, rec])

  def unquote(:"#set-")(vals, unquote(:"diameter_base_accounting_Proxy-Info")() = rec), do: apply(__MODULE__, :"#set-diameter_base_accounting_Proxy-Info", [vals, rec])

  def unquote(:"#set-")(vals, unquote(:"diameter_base_accounting_Failed-AVP")() = rec), do: apply(__MODULE__, :"#set-diameter_base_accounting_Failed-AVP", [vals, rec])

  def unquote(:"#set-")(vals, unquote(:"diameter_base_accounting_Experimental-Result")() = rec), do: apply(__MODULE__, :"#set-diameter_base_accounting_Experimental-Result", [vals, rec])

  def unquote(:"#set-")(vals, unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")() = rec), do: apply(__MODULE__, :"#set-diameter_base_accounting_Vendor-Specific-Application-Id", [vals, rec])

  def unquote(:"#set-")(_, _), do: :erlang.error(:badarg)

  def unquote(:"#set-diameter_base_accounting_ACA")(vals, rec) when is_list(vals), do: :lists.foldl(&unquote(:"#set-diameter_base_accounting_ACA")/2, rec, vals)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Session-Id", v}, rec), do: diameter_base_accounting_ACA(rec, "Session-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Result-Code", v}, rec), do: diameter_base_accounting_ACA(rec, "Result-Code": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Origin-Host", v}, rec), do: diameter_base_accounting_ACA(rec, "Origin-Host": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Origin-Realm", v}, rec), do: diameter_base_accounting_ACA(rec, "Origin-Realm": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Accounting-Record-Type", v}, rec), do: diameter_base_accounting_ACA(rec, "Accounting-Record-Type": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Accounting-Record-Number", v}, rec), do: diameter_base_accounting_ACA(rec, "Accounting-Record-Number": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Acct-Application-Id", v}, rec), do: diameter_base_accounting_ACA(rec, "Acct-Application-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Vendor-Specific-Application-Id", v}, rec), do: diameter_base_accounting_ACA(rec, "Vendor-Specific-Application-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"User-Name", v}, rec), do: diameter_base_accounting_ACA(rec, "User-Name": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Accounting-Sub-Session-Id", v}, rec), do: diameter_base_accounting_ACA(rec, "Accounting-Sub-Session-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Acct-Session-Id", v}, rec), do: diameter_base_accounting_ACA(rec, "Acct-Session-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Acct-Multi-Session-Id", v}, rec), do: diameter_base_accounting_ACA(rec, "Acct-Multi-Session-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Error-Message", v}, rec), do: diameter_base_accounting_ACA(rec, "Error-Message": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Error-Reporting-Host", v}, rec), do: diameter_base_accounting_ACA(rec, "Error-Reporting-Host": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Failed-AVP", v}, rec), do: diameter_base_accounting_ACA(rec, "Failed-AVP": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Acct-Interim-Interval", v}, rec), do: diameter_base_accounting_ACA(rec, "Acct-Interim-Interval": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Accounting-Realtime-Required", v}, rec), do: diameter_base_accounting_ACA(rec, "Accounting-Realtime-Required": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Origin-State-Id", v}, rec), do: diameter_base_accounting_ACA(rec, "Origin-State-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Event-Timestamp", v}, rec), do: diameter_base_accounting_ACA(rec, "Event-Timestamp": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:"Proxy-Info", v}, rec), do: diameter_base_accounting_ACA(rec, "Proxy-Info": v)

  def unquote(:"#set-diameter_base_accounting_ACA")({:AVP, v}, rec), do: diameter_base_accounting_ACA(rec, AVP: v)

  def unquote(:"#set-diameter_base_accounting_ACR")(vals, rec) when is_list(vals), do: :lists.foldl(&unquote(:"#set-diameter_base_accounting_ACR")/2, rec, vals)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Session-Id", v}, rec), do: diameter_base_accounting_ACR(rec, "Session-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Origin-Host", v}, rec), do: diameter_base_accounting_ACR(rec, "Origin-Host": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Origin-Realm", v}, rec), do: diameter_base_accounting_ACR(rec, "Origin-Realm": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Destination-Realm", v}, rec), do: diameter_base_accounting_ACR(rec, "Destination-Realm": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Accounting-Record-Type", v}, rec), do: diameter_base_accounting_ACR(rec, "Accounting-Record-Type": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Accounting-Record-Number", v}, rec), do: diameter_base_accounting_ACR(rec, "Accounting-Record-Number": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Acct-Application-Id", v}, rec), do: diameter_base_accounting_ACR(rec, "Acct-Application-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Vendor-Specific-Application-Id", v}, rec), do: diameter_base_accounting_ACR(rec, "Vendor-Specific-Application-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"User-Name", v}, rec), do: diameter_base_accounting_ACR(rec, "User-Name": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Destination-Host", v}, rec), do: diameter_base_accounting_ACR(rec, "Destination-Host": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Accounting-Sub-Session-Id", v}, rec), do: diameter_base_accounting_ACR(rec, "Accounting-Sub-Session-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Acct-Session-Id", v}, rec), do: diameter_base_accounting_ACR(rec, "Acct-Session-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Acct-Multi-Session-Id", v}, rec), do: diameter_base_accounting_ACR(rec, "Acct-Multi-Session-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Acct-Interim-Interval", v}, rec), do: diameter_base_accounting_ACR(rec, "Acct-Interim-Interval": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Accounting-Realtime-Required", v}, rec), do: diameter_base_accounting_ACR(rec, "Accounting-Realtime-Required": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Origin-State-Id", v}, rec), do: diameter_base_accounting_ACR(rec, "Origin-State-Id": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Event-Timestamp", v}, rec), do: diameter_base_accounting_ACR(rec, "Event-Timestamp": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Proxy-Info", v}, rec), do: diameter_base_accounting_ACR(rec, "Proxy-Info": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:"Route-Record", v}, rec), do: diameter_base_accounting_ACR(rec, "Route-Record": v)

  def unquote(:"#set-diameter_base_accounting_ACR")({:AVP, v}, rec), do: diameter_base_accounting_ACR(rec, AVP: v)

  def unquote(:"#set-diameter_base_accounting_Experimental-Result")(vals, rec) when is_list(vals), do: :lists.foldl(&unquote(:"#set-diameter_base_accounting_Experimental-Result")/2, rec, vals)

  def unquote(:"#set-diameter_base_accounting_Experimental-Result")({:"Vendor-Id", v}, rec), do: unquote(:"diameter_base_accounting_Experimental-Result")(rec, "Vendor-Id": v)

  def unquote(:"#set-diameter_base_accounting_Experimental-Result")({:"Experimental-Result-Code", v}, rec), do: unquote(:"diameter_base_accounting_Experimental-Result")(rec, "Experimental-Result-Code": v)

  def unquote(:"#set-diameter_base_accounting_Failed-AVP")(vals, rec) when is_list(vals), do: :lists.foldl(&unquote(:"#set-diameter_base_accounting_Failed-AVP")/2, rec, vals)

  def unquote(:"#set-diameter_base_accounting_Failed-AVP")({:AVP, v}, rec), do: unquote(:"diameter_base_accounting_Failed-AVP")(rec, AVP: v)

  def unquote(:"#set-diameter_base_accounting_Proxy-Info")(vals, rec) when is_list(vals), do: :lists.foldl(&unquote(:"#set-diameter_base_accounting_Proxy-Info")/2, rec, vals)

  def unquote(:"#set-diameter_base_accounting_Proxy-Info")({:"Proxy-Host", v}, rec), do: unquote(:"diameter_base_accounting_Proxy-Info")(rec, "Proxy-Host": v)

  def unquote(:"#set-diameter_base_accounting_Proxy-Info")({:"Proxy-State", v}, rec), do: unquote(:"diameter_base_accounting_Proxy-Info")(rec, "Proxy-State": v)

  def unquote(:"#set-diameter_base_accounting_Proxy-Info")({:AVP, v}, rec), do: unquote(:"diameter_base_accounting_Proxy-Info")(rec, AVP: v)

  def unquote(:"#set-diameter_base_accounting_Vendor-Specific-Application-Id")(vals, rec) when is_list(vals), do: :lists.foldl(&unquote(:"#set-diameter_base_accounting_Vendor-Specific-Application-Id")/2, rec, vals)

  def unquote(:"#set-diameter_base_accounting_Vendor-Specific-Application-Id")({:"Vendor-Id", v}, rec), do: unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")(rec, "Vendor-Id": v)

  def unquote(:"#set-diameter_base_accounting_Vendor-Specific-Application-Id")({:"Auth-Application-Id", v}, rec), do: unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")(rec, "Auth-Application-Id": v)

  def unquote(:"#set-diameter_base_accounting_Vendor-Specific-Application-Id")({:"Acct-Application-Id", v}, rec), do: unquote(:"diameter_base_accounting_Vendor-Specific-Application-Id")(rec, "Acct-Application-Id": v)

  def avp(t, data, :"Accounting-Realtime-Required", opts), do: avp(t, data, :"Accounting-Realtime-Required", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Accounting-Record-Number", opts), do: avp(t, data, :"Accounting-Record-Number", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Accounting-Record-Type", opts), do: avp(t, data, :"Accounting-Record-Type", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Accounting-Sub-Session-Id", opts), do: avp(t, data, :"Accounting-Sub-Session-Id", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Acct-Application-Id", opts), do: avp(t, data, :"Acct-Application-Id", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Acct-Interim-Interval", opts), do: avp(t, data, :"Acct-Interim-Interval", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Acct-Multi-Session-Id", opts), do: avp(t, data, :"Acct-Multi-Session-Id", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Acct-Session-Id", opts), do: avp(t, data, :"Acct-Session-Id", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Auth-Application-Id", opts), do: avp(t, data, :"Auth-Application-Id", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Auth-Grace-Period", opts), do: avp(t, data, :"Auth-Grace-Period", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Auth-Request-Type", opts), do: avp(t, data, :"Auth-Request-Type", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Auth-Session-State", opts), do: avp(t, data, :"Auth-Session-State", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Authorization-Lifetime", opts), do: avp(t, data, :"Authorization-Lifetime", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :Class, opts), do: avp(t, data, :Class, opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Destination-Host", opts), do: avp(t, data, :"Destination-Host", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Destination-Realm", opts), do: avp(t, data, :"Destination-Realm", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Disconnect-Cause", opts), do: avp(t, data, :"Disconnect-Cause", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Error-Message", opts), do: avp(t, data, :"Error-Message", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Error-Reporting-Host", opts), do: avp(t, data, :"Error-Reporting-Host", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Event-Timestamp", opts), do: avp(t, data, :"Event-Timestamp", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Experimental-Result", opts), do: grouped_avp(t, :"Experimental-Result", data, opts)

  def avp(t, data, :"Experimental-Result-Code", opts), do: avp(t, data, :"Experimental-Result-Code", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Failed-AVP", opts), do: grouped_avp(t, :"Failed-AVP", data, opts)

  def avp(t, data, :"Firmware-Revision", opts), do: avp(t, data, :"Firmware-Revision", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Host-IP-Address", opts), do: avp(t, data, :"Host-IP-Address", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Inband-Security-Id", opts), do: avp(t, data, :"Inband-Security-Id", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Multi-Round-Time-Out", opts), do: avp(t, data, :"Multi-Round-Time-Out", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Origin-Host", opts), do: avp(t, data, :"Origin-Host", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Origin-Realm", opts), do: avp(t, data, :"Origin-Realm", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Origin-State-Id", opts), do: avp(t, data, :"Origin-State-Id", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Product-Name", opts), do: avp(t, data, :"Product-Name", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Proxy-Host", opts), do: avp(t, data, :"Proxy-Host", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Proxy-Info", opts), do: grouped_avp(t, :"Proxy-Info", data, opts)

  def avp(t, data, :"Proxy-State", opts), do: avp(t, data, :"Proxy-State", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Re-Auth-Request-Type", opts), do: avp(t, data, :"Re-Auth-Request-Type", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Redirect-Host", opts), do: avp(t, data, :"Redirect-Host", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Redirect-Host-Usage", opts), do: avp(t, data, :"Redirect-Host-Usage", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Redirect-Max-Cache-Time", opts), do: avp(t, data, :"Redirect-Max-Cache-Time", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Result-Code", opts), do: avp(t, data, :"Result-Code", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Route-Record", opts), do: avp(t, data, :"Route-Record", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Session-Binding", opts), do: avp(t, data, :"Session-Binding", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Session-Id", opts), do: avp(t, data, :"Session-Id", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Session-Server-Failover", opts), do: avp(t, data, :"Session-Server-Failover", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Session-Timeout", opts), do: avp(t, data, :"Session-Timeout", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Supported-Vendor-Id", opts), do: avp(t, data, :"Supported-Vendor-Id", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Termination-Cause", opts), do: avp(t, data, :"Termination-Cause", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"User-Name", opts), do: avp(t, data, :"User-Name", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Vendor-Id", opts), do: avp(t, data, :"Vendor-Id", opts, :diameter_gen_base_rfc6733)

  def avp(t, data, :"Vendor-Specific-Application-Id", opts), do: grouped_avp(t, :"Vendor-Specific-Application-Id", data, opts)

  def avp(_, _, _, _), do: :erlang.error(:badarg)

  def avp_arity(:ACR), do: [{:"Session-Id", 1}, {:"Origin-Host", 1}, {:"Origin-Realm", 1}, {:"Destination-Realm", 1}, {:"Accounting-Record-Type", 1}, {:"Accounting-Record-Number", 1}, {:"Acct-Application-Id", {0, 1}}, {:"Vendor-Specific-Application-Id", {0, 1}}, {:"User-Name", {0, 1}}, {:"Destination-Host", {0, 1}}, {:"Accounting-Sub-Session-Id", {0, 1}}, {:"Acct-Session-Id", {0, 1}}, {:"Acct-Multi-Session-Id", {0, 1}}, {:"Acct-Interim-Interval", {0, 1}}, {:"Accounting-Realtime-Required", {0, 1}}, {:"Origin-State-Id", {0, 1}}, {:"Event-Timestamp", {0, 1}}, {:"Proxy-Info", {0, :*}}, {:"Route-Record", {0, :*}}, {:AVP, {0, :*}}]

  def avp_arity(:ACA), do: [{:"Session-Id", 1}, {:"Result-Code", 1}, {:"Origin-Host", 1}, {:"Origin-Realm", 1}, {:"Accounting-Record-Type", 1}, {:"Accounting-Record-Number", 1}, {:"Acct-Application-Id", {0, 1}}, {:"Vendor-Specific-Application-Id", {0, 1}}, {:"User-Name", {0, 1}}, {:"Accounting-Sub-Session-Id", {0, 1}}, {:"Acct-Session-Id", {0, 1}}, {:"Acct-Multi-Session-Id", {0, 1}}, {:"Error-Message", {0, 1}}, {:"Error-Reporting-Host", {0, 1}}, {:"Failed-AVP", {0, 1}}, {:"Acct-Interim-Interval", {0, 1}}, {:"Accounting-Realtime-Required", {0, 1}}, {:"Origin-State-Id", {0, 1}}, {:"Event-Timestamp", {0, 1}}, {:"Proxy-Info", {0, :*}}, {:AVP, {0, :*}}]

  def avp_arity(:"Proxy-Info"), do: [{:"Proxy-Host", 1}, {:"Proxy-State", 1}, {:AVP, {0, :*}}]

  def avp_arity(:"Failed-AVP"), do: [{:AVP, {1, :*}}]

  def avp_arity(:"Experimental-Result"), do: [{:"Vendor-Id", 1}, {:"Experimental-Result-Code", 1}]

  def avp_arity(:"Vendor-Specific-Application-Id"), do: [{:"Vendor-Id", 1}, {:"Auth-Application-Id", {0, 1}}, {:"Acct-Application-Id", {0, 1}}]

  def avp_arity(_), do: :erlang.error(:badarg)

  def avp_arity(:ACR, :"Session-Id"), do: 1

  def avp_arity(:ACR, :"Origin-Host"), do: 1

  def avp_arity(:ACR, :"Origin-Realm"), do: 1

  def avp_arity(:ACR, :"Destination-Realm"), do: 1

  def avp_arity(:ACR, :"Accounting-Record-Type"), do: 1

  def avp_arity(:ACR, :"Accounting-Record-Number"), do: 1

  def avp_arity(:ACR, :"Acct-Application-Id"), do: {0, 1}

  def avp_arity(:ACR, :"Vendor-Specific-Application-Id"), do: {0, 1}

  def avp_arity(:ACR, :"User-Name"), do: {0, 1}

  def avp_arity(:ACR, :"Destination-Host"), do: {0, 1}

  def avp_arity(:ACR, :"Accounting-Sub-Session-Id"), do: {0, 1}

  def avp_arity(:ACR, :"Acct-Session-Id"), do: {0, 1}

  def avp_arity(:ACR, :"Acct-Multi-Session-Id"), do: {0, 1}

  def avp_arity(:ACR, :"Acct-Interim-Interval"), do: {0, 1}

  def avp_arity(:ACR, :"Accounting-Realtime-Required"), do: {0, 1}

  def avp_arity(:ACR, :"Origin-State-Id"), do: {0, 1}

  def avp_arity(:ACR, :"Event-Timestamp"), do: {0, 1}

  def avp_arity(:ACR, :"Proxy-Info"), do: {0, :*}

  def avp_arity(:ACR, :"Route-Record"), do: {0, :*}

  def avp_arity(:ACR, :AVP), do: {0, :*}

  def avp_arity(:ACA, :"Session-Id"), do: 1

  def avp_arity(:ACA, :"Result-Code"), do: 1

  def avp_arity(:ACA, :"Origin-Host"), do: 1

  def avp_arity(:ACA, :"Origin-Realm"), do: 1

  def avp_arity(:ACA, :"Accounting-Record-Type"), do: 1

  def avp_arity(:ACA, :"Accounting-Record-Number"), do: 1

  def avp_arity(:ACA, :"Acct-Application-Id"), do: {0, 1}

  def avp_arity(:ACA, :"Vendor-Specific-Application-Id"), do: {0, 1}

  def avp_arity(:ACA, :"User-Name"), do: {0, 1}

  def avp_arity(:ACA, :"Accounting-Sub-Session-Id"), do: {0, 1}

  def avp_arity(:ACA, :"Acct-Session-Id"), do: {0, 1}

  def avp_arity(:ACA, :"Acct-Multi-Session-Id"), do: {0, 1}

  def avp_arity(:ACA, :"Error-Message"), do: {0, 1}

  def avp_arity(:ACA, :"Error-Reporting-Host"), do: {0, 1}

  def avp_arity(:ACA, :"Failed-AVP"), do: {0, 1}

  def avp_arity(:ACA, :"Acct-Interim-Interval"), do: {0, 1}

  def avp_arity(:ACA, :"Accounting-Realtime-Required"), do: {0, 1}

  def avp_arity(:ACA, :"Origin-State-Id"), do: {0, 1}

  def avp_arity(:ACA, :"Event-Timestamp"), do: {0, 1}

  def avp_arity(:ACA, :"Proxy-Info"), do: {0, :*}

  def avp_arity(:ACA, :AVP), do: {0, :*}

  def avp_arity(:"Proxy-Info", :"Proxy-Host"), do: 1

  def avp_arity(:"Proxy-Info", :"Proxy-State"), do: 1

  def avp_arity(:"Proxy-Info", :AVP), do: {0, :*}

  def avp_arity(:"Failed-AVP", :AVP), do: {1, :*}

  def avp_arity(:"Experimental-Result", :"Vendor-Id"), do: 1

  def avp_arity(:"Experimental-Result", :"Experimental-Result-Code"), do: 1

  def avp_arity(:"Vendor-Specific-Application-Id", :"Vendor-Id"), do: 1

  def avp_arity(:"Vendor-Specific-Application-Id", :"Auth-Application-Id"), do: {0, 1}

  def avp_arity(:"Vendor-Specific-Application-Id", :"Acct-Application-Id"), do: {0, 1}

  def avp_arity(_, _), do: 0

  def avp_header(:"Accounting-Realtime-Required"), do: :diameter_gen_base_rfc6733.avp_header(:"Accounting-Realtime-Required")

  def avp_header(:"Accounting-Record-Number"), do: :diameter_gen_base_rfc6733.avp_header(:"Accounting-Record-Number")

  def avp_header(:"Accounting-Record-Type"), do: :diameter_gen_base_rfc6733.avp_header(:"Accounting-Record-Type")

  def avp_header(:"Accounting-Sub-Session-Id"), do: :diameter_gen_base_rfc6733.avp_header(:"Accounting-Sub-Session-Id")

  def avp_header(:"Acct-Application-Id"), do: :diameter_gen_base_rfc6733.avp_header(:"Acct-Application-Id")

  def avp_header(:"Acct-Interim-Interval"), do: :diameter_gen_base_rfc6733.avp_header(:"Acct-Interim-Interval")

  def avp_header(:"Acct-Multi-Session-Id"), do: :diameter_gen_base_rfc6733.avp_header(:"Acct-Multi-Session-Id")

  def avp_header(:"Acct-Session-Id"), do: :diameter_gen_base_rfc6733.avp_header(:"Acct-Session-Id")

  def avp_header(:"Auth-Application-Id"), do: :diameter_gen_base_rfc6733.avp_header(:"Auth-Application-Id")

  def avp_header(:"Auth-Grace-Period"), do: :diameter_gen_base_rfc6733.avp_header(:"Auth-Grace-Period")

  def avp_header(:"Auth-Request-Type"), do: :diameter_gen_base_rfc6733.avp_header(:"Auth-Request-Type")

  def avp_header(:"Auth-Session-State"), do: :diameter_gen_base_rfc6733.avp_header(:"Auth-Session-State")

  def avp_header(:"Authorization-Lifetime"), do: :diameter_gen_base_rfc6733.avp_header(:"Authorization-Lifetime")

  def avp_header(:Class), do: :diameter_gen_base_rfc6733.avp_header(:Class)

  def avp_header(:"Destination-Host"), do: :diameter_gen_base_rfc6733.avp_header(:"Destination-Host")

  def avp_header(:"Destination-Realm"), do: :diameter_gen_base_rfc6733.avp_header(:"Destination-Realm")

  def avp_header(:"Disconnect-Cause"), do: :diameter_gen_base_rfc6733.avp_header(:"Disconnect-Cause")

  def avp_header(:"Error-Message"), do: :diameter_gen_base_rfc6733.avp_header(:"Error-Message")

  def avp_header(:"Error-Reporting-Host"), do: :diameter_gen_base_rfc6733.avp_header(:"Error-Reporting-Host")

  def avp_header(:"Event-Timestamp"), do: :diameter_gen_base_rfc6733.avp_header(:"Event-Timestamp")

  def avp_header(:"Experimental-Result"), do: :diameter_gen_base_rfc6733.avp_header(:"Experimental-Result")

  def avp_header(:"Experimental-Result-Code"), do: :diameter_gen_base_rfc6733.avp_header(:"Experimental-Result-Code")

  def avp_header(:"Failed-AVP"), do: :diameter_gen_base_rfc6733.avp_header(:"Failed-AVP")

  def avp_header(:"Firmware-Revision"), do: :diameter_gen_base_rfc6733.avp_header(:"Firmware-Revision")

  def avp_header(:"Host-IP-Address"), do: :diameter_gen_base_rfc6733.avp_header(:"Host-IP-Address")

  def avp_header(:"Inband-Security-Id"), do: :diameter_gen_base_rfc6733.avp_header(:"Inband-Security-Id")

  def avp_header(:"Multi-Round-Time-Out"), do: :diameter_gen_base_rfc6733.avp_header(:"Multi-Round-Time-Out")

  def avp_header(:"Origin-Host"), do: :diameter_gen_base_rfc6733.avp_header(:"Origin-Host")

  def avp_header(:"Origin-Realm"), do: :diameter_gen_base_rfc6733.avp_header(:"Origin-Realm")

  def avp_header(:"Origin-State-Id"), do: :diameter_gen_base_rfc6733.avp_header(:"Origin-State-Id")

  def avp_header(:"Product-Name"), do: :diameter_gen_base_rfc6733.avp_header(:"Product-Name")

  def avp_header(:"Proxy-Host"), do: :diameter_gen_base_rfc6733.avp_header(:"Proxy-Host")

  def avp_header(:"Proxy-Info"), do: :diameter_gen_base_rfc6733.avp_header(:"Proxy-Info")

  def avp_header(:"Proxy-State"), do: :diameter_gen_base_rfc6733.avp_header(:"Proxy-State")

  def avp_header(:"Re-Auth-Request-Type"), do: :diameter_gen_base_rfc6733.avp_header(:"Re-Auth-Request-Type")

  def avp_header(:"Redirect-Host"), do: :diameter_gen_base_rfc6733.avp_header(:"Redirect-Host")

  def avp_header(:"Redirect-Host-Usage"), do: :diameter_gen_base_rfc6733.avp_header(:"Redirect-Host-Usage")

  def avp_header(:"Redirect-Max-Cache-Time"), do: :diameter_gen_base_rfc6733.avp_header(:"Redirect-Max-Cache-Time")

  def avp_header(:"Result-Code"), do: :diameter_gen_base_rfc6733.avp_header(:"Result-Code")

  def avp_header(:"Route-Record"), do: :diameter_gen_base_rfc6733.avp_header(:"Route-Record")

  def avp_header(:"Session-Binding"), do: :diameter_gen_base_rfc6733.avp_header(:"Session-Binding")

  def avp_header(:"Session-Id"), do: :diameter_gen_base_rfc6733.avp_header(:"Session-Id")

  def avp_header(:"Session-Server-Failover"), do: :diameter_gen_base_rfc6733.avp_header(:"Session-Server-Failover")

  def avp_header(:"Session-Timeout"), do: :diameter_gen_base_rfc6733.avp_header(:"Session-Timeout")

  def avp_header(:"Supported-Vendor-Id"), do: :diameter_gen_base_rfc6733.avp_header(:"Supported-Vendor-Id")

  def avp_header(:"Termination-Cause"), do: :diameter_gen_base_rfc6733.avp_header(:"Termination-Cause")

  def avp_header(:"User-Name"), do: :diameter_gen_base_rfc6733.avp_header(:"User-Name")

  def avp_header(:"Vendor-Id"), do: :diameter_gen_base_rfc6733.avp_header(:"Vendor-Id")

  def avp_header(:"Vendor-Specific-Application-Id"), do: :diameter_gen_base_rfc6733.avp_header(:"Vendor-Specific-Application-Id")

  def avp_header(_), do: :erlang.error(:badarg)

  def avp_name(483, :undefined), do: {:"Accounting-Realtime-Required", :Enumerated}

  def avp_name(485, :undefined), do: {:"Accounting-Record-Number", :Unsigned32}

  def avp_name(480, :undefined), do: {:"Accounting-Record-Type", :Enumerated}

  def avp_name(287, :undefined), do: {:"Accounting-Sub-Session-Id", :Unsigned64}

  def avp_name(259, :undefined), do: {:"Acct-Application-Id", :Unsigned32}

  def avp_name(85, :undefined), do: {:"Acct-Interim-Interval", :Unsigned32}

  def avp_name(50, :undefined), do: {:"Acct-Multi-Session-Id", :UTF8String}

  def avp_name(44, :undefined), do: {:"Acct-Session-Id", :OctetString}

  def avp_name(258, :undefined), do: {:"Auth-Application-Id", :Unsigned32}

  def avp_name(276, :undefined), do: {:"Auth-Grace-Period", :Unsigned32}

  def avp_name(274, :undefined), do: {:"Auth-Request-Type", :Enumerated}

  def avp_name(277, :undefined), do: {:"Auth-Session-State", :Enumerated}

  def avp_name(291, :undefined), do: {:"Authorization-Lifetime", :Unsigned32}

  def avp_name(25, :undefined), do: {:Class, :OctetString}

  def avp_name(293, :undefined), do: {:"Destination-Host", :DiameterIdentity}

  def avp_name(283, :undefined), do: {:"Destination-Realm", :DiameterIdentity}

  def avp_name(273, :undefined), do: {:"Disconnect-Cause", :Enumerated}

  def avp_name(281, :undefined), do: {:"Error-Message", :UTF8String}

  def avp_name(294, :undefined), do: {:"Error-Reporting-Host", :DiameterIdentity}

  def avp_name(55, :undefined), do: {:"Event-Timestamp", :Time}

  def avp_name(297, :undefined), do: {:"Experimental-Result", :Grouped}

  def avp_name(298, :undefined), do: {:"Experimental-Result-Code", :Unsigned32}

  def avp_name(279, :undefined), do: {:"Failed-AVP", :Grouped}

  def avp_name(267, :undefined), do: {:"Firmware-Revision", :Unsigned32}

  def avp_name(257, :undefined), do: {:"Host-IP-Address", :Address}

  def avp_name(299, :undefined), do: {:"Inband-Security-Id", :Unsigned32}

  def avp_name(272, :undefined), do: {:"Multi-Round-Time-Out", :Unsigned32}

  def avp_name(264, :undefined), do: {:"Origin-Host", :DiameterIdentity}

  def avp_name(296, :undefined), do: {:"Origin-Realm", :DiameterIdentity}

  def avp_name(278, :undefined), do: {:"Origin-State-Id", :Unsigned32}

  def avp_name(269, :undefined), do: {:"Product-Name", :UTF8String}

  def avp_name(280, :undefined), do: {:"Proxy-Host", :DiameterIdentity}

  def avp_name(284, :undefined), do: {:"Proxy-Info", :Grouped}

  def avp_name(33, :undefined), do: {:"Proxy-State", :OctetString}

  def avp_name(285, :undefined), do: {:"Re-Auth-Request-Type", :Enumerated}

  def avp_name(292, :undefined), do: {:"Redirect-Host", :DiameterURI}

  def avp_name(261, :undefined), do: {:"Redirect-Host-Usage", :Enumerated}

  def avp_name(262, :undefined), do: {:"Redirect-Max-Cache-Time", :Unsigned32}

  def avp_name(268, :undefined), do: {:"Result-Code", :Unsigned32}

  def avp_name(282, :undefined), do: {:"Route-Record", :DiameterIdentity}

  def avp_name(270, :undefined), do: {:"Session-Binding", :Unsigned32}

  def avp_name(263, :undefined), do: {:"Session-Id", :UTF8String}

  def avp_name(271, :undefined), do: {:"Session-Server-Failover", :Enumerated}

  def avp_name(27, :undefined), do: {:"Session-Timeout", :Unsigned32}

  def avp_name(265, :undefined), do: {:"Supported-Vendor-Id", :Unsigned32}

  def avp_name(295, :undefined), do: {:"Termination-Cause", :Enumerated}

  def avp_name(1, :undefined), do: {:"User-Name", :UTF8String}

  def avp_name(266, :undefined), do: {:"Vendor-Id", :Unsigned32}

  def avp_name(260, :undefined), do: {:"Vendor-Specific-Application-Id", :Grouped}

  def avp_name(_, _), do: :AVP

  def decode_avps(name, avps, opts), do: :diameter_gen.decode_avps(name, avps, %{opts | :module => :diameter_gen_acct_rfc6733})

  def dict(), do: [1, {:avp_types, []}, {:avp_vendor_id, []}, {:codecs, []}, {:command_codes, [{271, 'ACR', 'ACA'}]}, {:custom_types, []}, {:define, []}, {:enum, []}, {:grouped, []}, {:id, 3}, {:import_avps, [{:diameter_gen_base_rfc6733, [{'Accounting-Realtime-Required', 483, 'Enumerated', 'M'}, {'Accounting-Record-Number', 485, 'Unsigned32', 'M'}, {'Accounting-Record-Type', 480, 'Enumerated', 'M'}, {'Accounting-Sub-Session-Id', 287, 'Unsigned64', 'M'}, {'Acct-Application-Id', 259, 'Unsigned32', 'M'}, {'Acct-Interim-Interval', 85, 'Unsigned32', 'M'}, {'Acct-Multi-Session-Id', 50, 'UTF8String', 'M'}, {'Acct-Session-Id', 44, 'OctetString', 'M'}, {'Auth-Application-Id', 258, 'Unsigned32', 'M'}, {'Auth-Grace-Period', 276, 'Unsigned32', 'M'}, {'Auth-Request-Type', 274, 'Enumerated', 'M'}, {'Auth-Session-State', 277, 'Enumerated', 'M'}, {'Authorization-Lifetime', 291, 'Unsigned32', 'M'}, {'Class', 25, 'OctetString', 'M'}, {'Destination-Host', 293, 'DiameterIdentity', 'M'}, {'Destination-Realm', 283, 'DiameterIdentity', 'M'}, {'Disconnect-Cause', 273, 'Enumerated', 'M'}, {'Error-Message', 281, 'UTF8String', []}, {'Error-Reporting-Host', 294, 'DiameterIdentity', []}, {'Event-Timestamp', 55, 'Time', 'M'}, {'Experimental-Result', 297, 'Grouped', 'M'}, {'Experimental-Result-Code', 298, 'Unsigned32', 'M'}, {'Failed-AVP', 279, 'Grouped', 'M'}, {'Firmware-Revision', 267, 'Unsigned32', []}, {'Host-IP-Address', 257, 'Address', 'M'}, {'Inband-Security-Id', 299, 'Unsigned32', 'M'}, {'Multi-Round-Time-Out', 272, 'Unsigned32', 'M'}, {'Origin-Host', 264, 'DiameterIdentity', 'M'}, {'Origin-Realm', 296, 'DiameterIdentity', 'M'}, {'Origin-State-Id', 278, 'Unsigned32', 'M'}, {'Product-Name', 269, 'UTF8String', []}, {'Proxy-Host', 280, 'DiameterIdentity', 'M'}, {'Proxy-Info', 284, 'Grouped', 'M'}, {'Proxy-State', 33, 'OctetString', 'M'}, {'Re-Auth-Request-Type', 285, 'Enumerated', 'M'}, {'Redirect-Host', 292, 'DiameterURI', 'M'}, {'Redirect-Host-Usage', 261, 'Enumerated', 'M'}, {'Redirect-Max-Cache-Time', 262, 'Unsigned32', 'M'}, {'Result-Code', 268, 'Unsigned32', 'M'}, {'Route-Record', 282, 'DiameterIdentity', 'M'}, {'Session-Binding', 270, 'Unsigned32', 'M'}, {'Session-Id', 263, 'UTF8String', 'M'}, {'Session-Server-Failover', 271, 'Enumerated', 'M'}, {'Session-Timeout', 27, 'Unsigned32', 'M'}, {'Supported-Vendor-Id', 265, 'Unsigned32', 'M'}, {'Termination-Cause', 295, 'Enumerated', 'M'}, {'User-Name', 1, 'UTF8String', 'M'}, {'Vendor-Id', 266, 'Unsigned32', 'M'}, {'Vendor-Specific-Application-Id', 260, 'Grouped', 'M'}]}]}, {:import_enums, [{:diameter_gen_base_rfc6733, [{'Disconnect-Cause', [{'REBOOTING', 0}, {'BUSY', 1}, {'DO_NOT_WANT_TO_TALK_TO_YOU', 2}]}, {'Redirect-Host-Usage', [{'DONT_CACHE', 0}, {'ALL_SESSION', 1}, {'ALL_REALM', 2}, {'REALM_AND_APPLICATION', 3}, {'ALL_APPLICATION', 4}, {'ALL_HOST', 5}, {'ALL_USER', 6}]}, {'Auth-Request-Type', [{'AUTHENTICATE_ONLY', 1}, {'AUTHORIZE_ONLY', 2}, {'AUTHORIZE_AUTHENTICATE', 3}]}, {'Auth-Session-State', [{'STATE_MAINTAINED', 0}, {'NO_STATE_MAINTAINED', 1}]}, {'Re-Auth-Request-Type', [{'AUTHORIZE_ONLY', 0}, {'AUTHORIZE_AUTHENTICATE', 1}]}, {'Termination-Cause', [{'LOGOUT', 1}, {'SERVICE_NOT_PROVIDED', 2}, {'BAD_ANSWER', 3}, {'ADMINISTRATIVE', 4}, {'LINK_BROKEN', 5}, {'AUTH_EXPIRED', 6}, {'USER_MOVED', 7}, {'SESSION_TIMEOUT', 8}]}, {'Session-Server-Failover', [{'REFUSE_SERVICE', 0}, {'TRY_AGAIN', 1}, {'ALLOW_SERVICE', 2}, {'TRY_AGAIN_ALLOW_SERVICE', 3}]}, {'Accounting-Record-Type', [{'EVENT_RECORD', 1}, {'START_RECORD', 2}, {'INTERIM_RECORD', 3}, {'STOP_RECORD', 4}]}, {'Accounting-Realtime-Required', [{'DELIVER_AND_GRANT', 1}, {'GRANT_AND_STORE', 2}, {'GRANT_AND_LOSE', 3}]}]}]}, {:import_groups, [{:diameter_gen_base_rfc6733, [{'Proxy-Info', 284, [], [{'Proxy-Host'}, {'Proxy-State'}, {:*, ['AVP']}]}, {'Failed-AVP', 279, [], [{:*, {'AVP'}}]}, {'Experimental-Result', 297, [], [{'Vendor-Id'}, {'Experimental-Result-Code'}]}, {'Vendor-Specific-Application-Id', 260, [], [{'Vendor-Id'}, ['Auth-Application-Id'], ['Acct-Application-Id']]}]}]}, {:inherits, [{'diameter_gen_base_rfc6733', []}]}, {:messages, [{'ACR', 271, [:REQ, :PXY], [], [{{'Session-Id'}}, {'Origin-Host'}, {'Origin-Realm'}, {'Destination-Realm'}, {'Accounting-Record-Type'}, {'Accounting-Record-Number'}, ['Acct-Application-Id'], ['Vendor-Specific-Application-Id'], ['User-Name'], ['Destination-Host'], ['Accounting-Sub-Session-Id'], ['Acct-Session-Id'], ['Acct-Multi-Session-Id'], ['Acct-Interim-Interval'], ['Accounting-Realtime-Required'], ['Origin-State-Id'], ['Event-Timestamp'], {:*, ['Proxy-Info']}, {:*, ['Route-Record']}, {:*, ['AVP']}]}, {'ACA', 271, [:PXY], [], [{{'Session-Id'}}, {'Result-Code'}, {'Origin-Host'}, {'Origin-Realm'}, {'Accounting-Record-Type'}, {'Accounting-Record-Number'}, ['Acct-Application-Id'], ['Vendor-Specific-Application-Id'], ['User-Name'], ['Accounting-Sub-Session-Id'], ['Acct-Session-Id'], ['Acct-Multi-Session-Id'], ['Error-Message'], ['Error-Reporting-Host'], ['Failed-AVP'], ['Acct-Interim-Interval'], ['Accounting-Realtime-Required'], ['Origin-State-Id'], ['Event-Timestamp'], {:*, ['Proxy-Info']}, {:*, ['AVP']}]}]}, {:name, 'diameter_gen_acct_rfc6733'}, {:prefix, 'diameter_base_accounting'}, {:vendor, {0, 'IETF'}}]

  def empty_value(:"Proxy-Info", opts), do: empty_group(:"Proxy-Info", opts)

  def empty_value(:"Failed-AVP", opts), do: empty_group(:"Failed-AVP", opts)

  def empty_value(:"Experimental-Result", opts), do: empty_group(:"Experimental-Result", opts)

  def empty_value(:"Vendor-Specific-Application-Id", opts), do: empty_group(:"Vendor-Specific-Application-Id", opts)

  def empty_value(:"Disconnect-Cause", _), do: <<0, 0, 0, 0>>

  def empty_value(:"Redirect-Host-Usage", _), do: <<0, 0, 0, 0>>

  def empty_value(:"Auth-Request-Type", _), do: <<0, 0, 0, 0>>

  def empty_value(:"Auth-Session-State", _), do: <<0, 0, 0, 0>>

  def empty_value(:"Re-Auth-Request-Type", _), do: <<0, 0, 0, 0>>

  def empty_value(:"Termination-Cause", _), do: <<0, 0, 0, 0>>

  def empty_value(:"Session-Server-Failover", _), do: <<0, 0, 0, 0>>

  def empty_value(:"Accounting-Record-Type", _), do: <<0, 0, 0, 0>>

  def empty_value(:"Accounting-Realtime-Required", _), do: <<0, 0, 0, 0>>

  def empty_value(name, opts), do: empty(name, opts)

  def encode_avps(name, avps, opts), do: :diameter_gen.encode_avps(name, avps, %{opts | :module => :diameter_gen_acct_rfc6733})

  def enumerated_avp(_, _, _), do: :erlang.error(:badarg)

  def grouped_avp(t, name, data, opts), do: :diameter_gen.grouped_avp(t, name, data, opts)

  def id(), do: 3

  def module_info() do
    # body not decompiled
  end

  def module_info(p0) do
    # body not decompiled
  end

  def msg2rec(:ACR), do: :diameter_base_accounting_ACR

  def msg2rec(:ACA), do: :diameter_base_accounting_ACA

  def msg2rec(_), do: :erlang.error(:badarg)

  def msg_header(:ACR), do: {271, 192, 3}

  def msg_header(:ACA), do: {271, 64, 3}

  def msg_header(_), do: :erlang.error(:badarg)

  def msg_name(271, true), do: :ACR

  def msg_name(271, false), do: :ACA

  def msg_name(_, _), do: :""

  def name(), do: :diameter_gen_acct_rfc6733

  def name2rec(:"Proxy-Info"), do: :"diameter_base_accounting_Proxy-Info"

  def name2rec(:"Failed-AVP"), do: :"diameter_base_accounting_Failed-AVP"

  def name2rec(:"Experimental-Result"), do: :"diameter_base_accounting_Experimental-Result"

  def name2rec(:"Vendor-Specific-Application-Id"), do: :"diameter_base_accounting_Vendor-Specific-Application-Id"

  def name2rec(t), do: msg2rec(t)

  def rec2msg(:diameter_base_accounting_ACR), do: :ACR

  def rec2msg(:diameter_base_accounting_ACA), do: :ACA

  def rec2msg(_), do: :erlang.error(:badarg)

  def vendor_id(), do: 0

  def vendor_name(), do: :IETF

  # Private Functions

  defp unquote(:"-#get-diameter_base_accounting_ACA/2-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-#get-diameter_base_accounting_ACR/2-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-#get-diameter_base_accounting_Experimental-Result/2-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-#get-diameter_base_accounting_Failed-AVP/2-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-#get-diameter_base_accounting_Proxy-Info/2-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-#get-diameter_base_accounting_Vendor-Specific-Application-Id/2-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-fun.#set-diameter_base_accounting_ACA/2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-fun.#set-diameter_base_accounting_ACR/2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-fun.#set-diameter_base_accounting_Experimental-Result/2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-fun.#set-diameter_base_accounting_Failed-AVP/2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-fun.#set-diameter_base_accounting_Proxy-Info/2-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-fun.#set-diameter_base_accounting_Vendor-Specific-Application-Id/2-")(p0, p1) do
    # body not decompiled
  end

  defp avp(t, data, name, opts, mod), do: mod.avp(t, data, name, %{opts | :module => mod})

  defp empty(name, opts), do: :diameter_gen.empty(name, opts)

  defp empty_group(name, opts), do: :diameter_gen.empty_group(name, opts)
end
