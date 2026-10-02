# Source code recreated from a .beam file by IntelliJ Elixir
defmodule :"OTP-PUB-KEY" do

  # Functions

  def unquote(:"ansi-X9-62")(), do: {1, 2, 840, 10045}

  def anyExtendedKeyUsage(), do: {2, 5, 29, 37, 0}

  def anyPolicy(), do: {2, 5, 29, 32, 0}

  def bit_string_format(), do: :bitstring

  def brainpoolP160r1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 1}

  def brainpoolP160t1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 2}

  def brainpoolP192r1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 3}

  def brainpoolP192t1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 4}

  def brainpoolP224r1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 5}

  def brainpoolP224t1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 6}

  def brainpoolP256r1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 7}

  def brainpoolP256t1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 8}

  def brainpoolP320r1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 9}

  def brainpoolP320t1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 10}

  def brainpoolP384r1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 11}

  def brainpoolP384t1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 12}

  def brainpoolP512r1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 13}

  def brainpoolP512t1(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1, 14}

  def unquote(:"certicom-arc")(), do: {1, 3, 132}

  def unquote(:"characteristic-two-field")(), do: {1, 2, 840, 10045, 1, 2}

  def unquote(:"common-name")(), do: 1

  def data(), do: {1, 2, 840, 113549, 1, 7, 1}

  def dec_AAControls(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{2, v1} | tempTlv2] ->
        {(val1 = decode_integer(v1, []); cond do
          0 <= val1 and val1 <= :MAX ->
            val1
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{131072, v2} | tempTlv3] ->
        {dec_AttrSpec(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{131073, v3} | tempTlv4] ->
        {dec_AttrSpec(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    {term4, tlv5} = case tlv4 do
      [{1, v4} | tempTlv5] ->
        {decode_boolean(v4, []), tempTlv5}
      _ ->
        {true, tlv4}
    end
    case tlv5 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv5}}})
    end
    res1 = {:AAControls, term1, term2, term3, term4}
    res1
  end

  def dec_ACClearAttrs(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_GeneralName(v1, [])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    [v3 | tlv4] = tlv3
    term3 = dec_ACClearAttrs_attrs(v3, [16])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:ACClearAttrs, term1, term2, term3}
    res1
  end

  def dec_AccessDescription(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    [v2 | tlv3] = tlv2
    term2 = dec_GeneralName(v2, [])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:AccessDescription, term1, term2}
    res1
  end

  def dec_AdministrationDomainName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {18, v1} ->
        {:numeric, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          0 <= c1 and c1 <= 16 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:printable, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          0 <= c2 and c2 <= 16 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_Algorithm(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {term2, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type_as_binary(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:Algorithm, term1, term2}
    res1
  end

  def dec_AlgorithmIdentifier(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {term2, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type_as_binary(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:AlgorithmIdentifier, term1, term2}
    res1
  end

  def dec_AlgorithmNull(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    [v2 | tlv3] = tlv2
    term2 = decode_null(v2, [5])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:AlgorithmNull, term1, term2}
    res1
  end

  def dec_AnotherName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    [v2 | tlv3] = tlv2
    term2 = decode_open_type_as_binary(v2, [131072])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:AnotherName, term1, term2}
    res1
  end

  def dec_Any(tlv, tagIn), do: decode_open_type_as_binary(tlv, tagIn)

  def dec_AttCertIssuer(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {16, v1} ->
        {:v1Form, dec_GeneralNames(v1, [])}
      {131072, v1} ->
        {:v2Form, dec_V2Form(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_AttCertValidityPeriod(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (binary_to_list(decode_restricted_string(v1, [24])))
    [v2 | tlv3] = tlv2
    term2 = (binary_to_list(decode_restricted_string(v2, [24])))
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:AttCertValidityPeriod, term1, term2}
    res1
  end

  def dec_AttCertVersion(tlv, tagIn), do: (val1 = decode_integer(tlv, tagIn); number2name(val1, [{:v2, 1}]))

  def dec_AttrSpec(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      decode_object_identifier(v1, [6])
    end
  end

  def dec_Attribute(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    [v2 | tlv3] = tlv2
    term2 = dec_Attribute_values(v2, [17])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:Attribute, term1, term2}
    res1
  end

  def dec_AttributeCertificate(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_AttributeCertificateInfo(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = dec_AlgorithmIdentifier(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = decode_native_bit_string(v3, [3])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:AttributeCertificate, term1, term2, term3}
    res1
  end

  def dec_AttributeCertificateInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [2]); number2name(val1, [{:v2, 1}]))
    [v2 | tlv3] = tlv2
    term2 = dec_Holder(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = dec_AttCertIssuer(v3, [])
    [v4 | tlv5] = tlv4
    term4 = dec_AlgorithmIdentifier(v4, [16])
    [v5 | tlv6] = tlv5
    term5 = decode_integer(v5, [2])
    [v6 | tlv7] = tlv6
    term6 = dec_AttCertValidityPeriod(v6, [16])
    [v7 | tlv8] = tlv7
    term7 = dec_AttributeCertificateInfo_attributes(v7, [16])
    {term8, tlv9} = case tlv8 do
      [{3, v8} | tempTlv9] ->
        {decode_native_bit_string(v8, []), tempTlv9}
      _ ->
        {:asn1_NOVALUE, tlv8}
    end
    {term9, tlv10} = case tlv9 do
      [{16, v9} | tempTlv10] ->
        {dec_Extensions(v9, []), tempTlv10}
      _ ->
        {:asn1_NOVALUE, tlv9}
    end
    case tlv10 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv10}}})
    end
    res1 = {:AttributeCertificateInfo, term1, term2, term3, term4, term5, term6, term7, term8, term9}
    res1
  end

  def dec_AttributeType(tlv, tagIn), do: decode_object_identifier(tlv, tagIn)

  def dec_AttributeTypeAndValue(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    [v2 | tlv3] = tlv2
    term2 = dec_AttributeValue(v2, [])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:AttributeTypeAndValue, term1, term2}
    res1
  end

  def dec_AttributeValue(tlv, tagIn), do: decode_open_type_as_binary(tlv, tagIn)

  def dec_AuthorityInfoAccessSyntax(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_AccessDescription(v1, [16])
    end
  end

  def dec_AuthorityKeyIdentifier(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {decode_octet_string(v1, []), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{131073, v2} | tempTlv3] ->
        {dec_GeneralNames(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{131074, v3} | tempTlv4] ->
        {decode_integer(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:AuthorityKeyIdentifier, term1, term2, term3}
    res1
  end

  def dec_BaseCRLNumber(tlv, tagIn) do
    (val1 = decode_integer(tlv, tagIn); cond do
      0 <= val1 and val1 <= :MAX ->
        val1
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_BaseDistance(tlv, tagIn) do
    (val1 = decode_integer(tlv, tagIn); cond do
      0 <= val1 and val1 <= :MAX ->
        val1
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_BasicConstraints(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{1, v1} | tempTlv2] ->
        {decode_boolean(v1, []), tempTlv2}
      _ ->
        {false, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{2, v2} | tempTlv3] ->
        {(val1 = decode_integer(v2, []); cond do
          0 <= val1 and val1 <= :MAX ->
            val1
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:BasicConstraints, term1, term2}
    res1
  end

  def dec_Boolean(tlv, tagIn), do: decode_boolean(tlv, tagIn)

  def dec_BuiltInDomainDefinedAttribute(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_restricted_string(v1, [19]); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 8 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
    [v2 | tlv3] = tlv2
    term2 = (val2 = decode_restricted_string(v2, [19]); c2 = byte_size(val2); cond do
      1 <= c2 and c2 <= 128 ->
        binary_to_list(val2)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:BuiltInDomainDefinedAttribute, term1, term2}
    res1
  end

  def dec_BuiltInDomainDefinedAttributes(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_BuiltInDomainDefinedAttribute(v1, [16])
    end
  end

  def dec_BuiltInStandardAttributes(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{65537, v1} | tempTlv2] ->
        {dec_CountryName(v1, []), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{65538, v2} | tempTlv3] ->
        {dec_AdministrationDomainName(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{131072, v3} | tempTlv4] ->
        {(val1 = decode_restricted_string(v3, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 16 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    {term4, tlv5} = case tlv4 do
      [{131073, v4} | tempTlv5] ->
        {(val2 = decode_restricted_string(v4, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 24 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv5}
      _ ->
        {:asn1_NOVALUE, tlv4}
    end
    {term5, tlv6} = case tlv5 do
      [{131074, v5} | tempTlv6] ->
        {dec_PrivateDomainName(v5, []), tempTlv6}
      _ ->
        {:asn1_NOVALUE, tlv5}
    end
    {term6, tlv7} = case tlv6 do
      [{131075, v6} | tempTlv7] ->
        {(val3 = decode_restricted_string(v6, []); c3 = byte_size(val3); cond do
          1 <= c3 and c3 <= 128 ->
            binary_to_list(val3)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv7}
      _ ->
        {:asn1_NOVALUE, tlv6}
    end
    {term7, tlv8} = case tlv7 do
      [{131076, v7} | tempTlv8] ->
        {(val4 = decode_restricted_string(v7, []); c4 = byte_size(val4); cond do
          1 <= c4 and c4 <= 32 ->
            binary_to_list(val4)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv8}
      _ ->
        {:asn1_NOVALUE, tlv7}
    end
    {term8, tlv9} = case tlv8 do
      [{131077, v8} | tempTlv9] ->
        {dec_PersonalName(v8, []), tempTlv9}
      _ ->
        {:asn1_NOVALUE, tlv8}
    end
    {term9, tlv10} = case tlv9 do
      [{131078, v9} | tempTlv10] ->
        {dec_OrganizationalUnitNames(v9, []), tempTlv10}
      _ ->
        {:asn1_NOVALUE, tlv9}
    end
    case tlv10 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv10}}})
    end
    res1 = {:BuiltInStandardAttributes, term1, term2, term3, term4, term5, term6, term7, term8, term9}
    res1
  end

  def dec_CPSuri(tlv, tagIn), do: (binary_to_list(decode_restricted_string(tlv, tagIn)))

  def dec_CRLDistributionPoints(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_DistributionPoint(v1, [16])
    end
  end

  def dec_CRLNumber(tlv, tagIn) do
    (val1 = decode_integer(tlv, tagIn); cond do
      0 <= val1 and val1 <= :MAX ->
        val1
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_CRLReason(tlv, tagIn) do
    case decode_integer(tlv, tagIn) do
      0 ->
        :unspecified
      1 ->
        :keyCompromise
      2 ->
        :cACompromise
      3 ->
        :affiliationChanged
      4 ->
        :superseded
      5 ->
        :cessationOfOperation
      6 ->
        :certificateHold
      8 ->
        :removeFromCRL
      9 ->
        :privilegeWithdrawn
      10 ->
        :aACompromise
      default1 ->
        exit({:error, {:asn1, {:illegal_enumerated, default1}}})
    end
  end

  def dec_CRLSequence(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_CertificateList(v1, [16])
    end
  end

  def dec_CertPolicyId(tlv, tagIn), do: decode_object_identifier(tlv, tagIn)

  def dec_Certificate(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_TBSCertificate(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = dec_AlgorithmIdentifier(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = decode_native_bit_string(v3, [3])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:Certificate, term1, term2, term3}
    res1
  end

  def dec_CertificateIssuer(tlv, tagIn), do: dec_GeneralNames(tlv, tagIn)

  def dec_CertificateList(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_TBSCertList(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = dec_AlgorithmIdentifier(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = decode_native_bit_string(v3, [3])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:CertificateList, term1, term2, term3}
    res1
  end

  def dec_CertificatePolicies(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_PolicyInformation(v1, [16])
    end
  end

  def dec_CertificateRevocationLists(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_CertificateList(v1, [16])
    end
  end

  def dec_CertificateSerialNumber(tlv, tagIn), do: decode_integer(tlv, tagIn)

  def dec_Certificates(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_Certificate(v1, [16])
    end
  end

  def dec_CertificationRequest(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_CertificationRequestInfo(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = dec_CertificationRequest_signatureAlgorithm(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = decode_native_bit_string(v3, [3])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:CertificationRequest, term1, term2, term3}
    res1
  end

  def dec_CertificationRequestInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [2]); number2name(val1, [{:v1, 0}]))
    [v2 | tlv3] = tlv2
    term2 = dec_Name(v2, [])
    [v3 | tlv4] = tlv3
    term3 = dec_CertificationRequestInfo_subjectPKInfo(v3, [16])
    [v4 | tlv5] = tlv4
    term4 = dec_CertificationRequestInfo_attributes(v4, [131072])
    case tlv5 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv5}}})
    end
    res1 = {:CertificationRequestInfo, term1, term2, term3, term4}
    res1
  end

  def unquote(:"dec_Characteristic-two")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_object_identifier(v2, [6])
    [v3 | tlv4] = tlv3
    term3 = decode_open_type_as_binary(v3, [])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:"Characteristic-two", term1, term2, term3}
    res1
  end

  def dec_ClassList(tlv, tagIn), do: decode_named_bit_string(tlv, [{:unmarked, 0}, {:unclassified, 1}, {:restricted, 2}, {:confidential, 3}, {:secret, 4}, {:topSecret, 5}], tagIn)

  def dec_Clearance(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [131072])
    {term2, tlv3} = case tlv2 do
      [{131073, v2} | tempTlv3] ->
        {decode_named_bit_string(v2, [{:unmarked, 0}, {:unclassified, 1}, {:restricted, 2}, {:confidential, 3}, {:secret, 4}, {:topSecret, 5}], []), tempTlv3}
      _ ->
        {[:unclassified], tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{131074, v3} | tempTlv4] ->
        {dec_Clearance_securityCategories(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:Clearance, term1, term2, term3}
    res1
  end

  def dec_CommonName(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 128 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_ContentEncryptionAlgorithmIdentifier(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {tmpterm1, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    decObjalgorithmTerm1 = :"OTP-PUB-KEY".getdec_internal_object_set_argument_1(term1)
    term2 = case tmpterm1 do
      :asn1_NOVALUE ->
        :asn1_NOVALUE
      _ ->
        try do
          decObjalgorithmTerm1.(:Type, tmpterm1, [])
        catch
          error -> error
        end
        |> case do
          {:EXIT, reason1} ->
            exit({:"Type not compatible with table constraint", reason1})
          tmpterm2 ->
            tmpterm2
        end
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:ContentEncryptionAlgorithmIdentifier, term1, term2}
    res1
  end

  def dec_ContentInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {tmpterm1, tlv3} = case tlv2 do
      [{131072, v2} | tempTlv3] ->
        {decode_open_type(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    decObjcontentTypeTerm1 = :"OTP-PUB-KEY".getdec_Contents(term1)
    term2 = case tmpterm1 do
      :asn1_NOVALUE ->
        :asn1_NOVALUE
      _ ->
        try do
          decObjcontentTypeTerm1.(:Type, tmpterm1, [])
        catch
          error -> error
        end
        |> case do
          {:EXIT, reason1} ->
            exit({:"Type not compatible with table constraint", reason1})
          tmpterm2 ->
            tmpterm2
        end
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:ContentInfo, term1, term2}
    res1
  end

  def dec_ContentType(tlv, tagIn), do: decode_object_identifier(tlv, tagIn)

  def dec_CountryName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {18, v1} ->
        {:"x121-dcc-code", (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          c1 === 3 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:"iso-3166-alpha2-code", (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          c2 === 2 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_Curve(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_octet_string(v1, [4])
    [v2 | tlv3] = tlv2
    term2 = decode_octet_string(v2, [4])
    {term3, tlv4} = case tlv3 do
      [{3, v3} | tempTlv4] ->
        {decode_native_bit_string(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:Curve, term1, term2, term3}
    res1
  end

  def dec_DHParameter(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    {term3, tlv4} = case tlv3 do
      [{2, v3} | tempTlv4] ->
        {decode_integer(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:DHParameter, term1, term2, term3}
    res1
  end

  def dec_DHPublicKey(tlv, tagIn), do: decode_integer(tlv, tagIn)

  def dec_DSAParams(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {16, v1} ->
        {:params, apply(__MODULE__, :"dec_Dss-Parms", [v1, []])}
      {5, v1} ->
        {:null, decode_null(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_DSAPrivateKey(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    [v3 | tlv4] = tlv3
    term3 = decode_integer(v3, [2])
    [v4 | tlv5] = tlv4
    term4 = decode_integer(v4, [2])
    [v5 | tlv6] = tlv5
    term5 = decode_integer(v5, [2])
    [v6 | tlv7] = tlv6
    term6 = decode_integer(v6, [2])
    case tlv7 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv7}}})
    end
    res1 = {:DSAPrivateKey, term1, term2, term3, term4, term5, term6}
    res1
  end

  def dec_DSAPublicKey(tlv, tagIn), do: decode_integer(tlv, tagIn)

  def dec_Data(tlv, tagIn), do: decode_octet_string(tlv, tagIn)

  def dec_Digest(tlv, tagIn), do: decode_octet_string(tlv, tagIn)

  def dec_DigestAlgorithmIdentifier(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {tmpterm1, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    decObjalgorithmTerm1 = :"OTP-PUB-KEY".getdec_internal_object_set_argument_2(term1)
    term2 = case tmpterm1 do
      :asn1_NOVALUE ->
        :asn1_NOVALUE
      _ ->
        try do
          decObjalgorithmTerm1.(:Type, tmpterm1, [])
        catch
          error -> error
        end
        |> case do
          {:EXIT, reason1} ->
            exit({:"Type not compatible with table constraint", reason1})
          tmpterm2 ->
            tmpterm2
        end
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:DigestAlgorithmIdentifier, term1, term2}
    res1
  end

  def dec_DigestAlgorithmIdentifiers(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {17, v1} ->
        {:daSet, dec_DigestAlgorithmIdentifiers_daSet(v1, [])}
      {16, v1} ->
        {:daSequence, dec_DigestAlgorithmIdentifiers_daSequence(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_DigestEncryptionAlgorithmIdentifier(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {tmpterm1, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    decObjalgorithmTerm1 = :"OTP-PUB-KEY".getdec_internal_object_set_argument_6(term1)
    term2 = case tmpterm1 do
      :asn1_NOVALUE ->
        :asn1_NOVALUE
      _ ->
        try do
          decObjalgorithmTerm1.(:Type, tmpterm1, [])
        catch
          error -> error
        end
        |> case do
          {:EXIT, reason1} ->
            exit({:"Type not compatible with table constraint", reason1})
          tmpterm2 ->
            tmpterm2
        end
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:DigestEncryptionAlgorithmIdentifier, term1, term2}
    res1
  end

  def dec_DigestInfoNull(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_AlgorithmNull(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = decode_octet_string(v2, [4])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:DigestInfoNull, term1, term2}
    res1
  end

  def unquote(:"dec_DigestInfoPKCS-1")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_Algorithm(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = decode_octet_string(v2, [4])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"DigestInfoPKCS-1", term1, term2}
    res1
  end

  def unquote(:"dec_DigestInfoPKCS-7")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_DigestAlgorithmIdentifier(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = decode_octet_string(v2, [4])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"DigestInfoPKCS-7", term1, term2}
    res1
  end

  def dec_DigestedData(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [2]); number2name(val1, [{:ddVer0, 0}]))
    [v2 | tlv3] = tlv2
    term2 = dec_DigestAlgorithmIdentifier(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = dec_ContentInfo(v3, [16])
    [v4 | tlv5] = tlv4
    term4 = decode_octet_string(v4, [4])
    case tlv5 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv5}}})
    end
    res1 = {:DigestedData, term1, term2, term3, term4}
    res1
  end

  def dec_DirectoryString(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {20, v1} ->
        {:teletexString, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= :MAX ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:printableString, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= :MAX ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {28, v1} ->
        {:universalString, (val3 = decode_universal_string(v1, []); c3 = length(val3); cond do
          1 <= c3 and c3 <= :MAX ->
            val3
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      {30, v1} ->
        {:bmpString, (val4 = decode_BMP_string(v1, []); c4 = length(val4); cond do
          1 <= c4 and c4 <= :MAX ->
            val4
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_DisplayText(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {22, v1} ->
        {:ia5String, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 200 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {26, v1} ->
        {:visibleString, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 200 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {30, v1} ->
        {:bmpString, (val3 = decode_BMP_string(v1, []); c3 = length(val3); cond do
          1 <= c3 and c3 <= 200 ->
            val3
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_DistinguishedName(tlv, tagIn), do: dec_RDNSequence(tlv, tagIn)

  def dec_DistributionPoint(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {dec_DistributionPointName(v1, []), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{131073, v2} | tempTlv3] ->
        {decode_named_bit_string(v2, [{:unused, 0}, {:keyCompromise, 1}, {:cACompromise, 2}, {:affiliationChanged, 3}, {:superseded, 4}, {:cessationOfOperation, 5}, {:certificateHold, 6}, {:privilegeWithdrawn, 7}, {:aACompromise, 8}], []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{131074, v3} | tempTlv4] ->
        {dec_GeneralNames(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:DistributionPoint, term1, term2, term3}
    res1
  end

  def dec_DistributionPointName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {131072, v1} ->
        {:fullName, dec_GeneralNames(v1, [])}
      {131073, v1} ->
        {:nameRelativeToCRLIssuer, dec_RelativeDistinguishedName(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_DomainComponent(tlv, tagIn), do: (binary_to_list(decode_restricted_string(tlv, tagIn)))

  def dec_DomainParameters(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    [v3 | tlv4] = tlv3
    term3 = decode_integer(v3, [2])
    {term4, tlv5} = case tlv4 do
      [{2, v4} | tempTlv5] ->
        {decode_integer(v4, []), tempTlv5}
      _ ->
        {:asn1_NOVALUE, tlv4}
    end
    {term5, tlv6} = case tlv5 do
      [{16, v5} | tempTlv6] ->
        {dec_ValidationParms(v5, []), tempTlv6}
      _ ->
        {:asn1_NOVALUE, tlv5}
    end
    case tlv6 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv6}}})
    end
    res1 = {:DomainParameters, term1, term2, term3, term4, term5}
    res1
  end

  def unquote(:"dec_Dss-Parms")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    [v3 | tlv4] = tlv3
    term3 = decode_integer(v3, [2])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:"Dss-Parms", term1, term2, term3}
    res1
  end

  def unquote(:"dec_Dss-Sig-Value")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"Dss-Sig-Value", term1, term2}
    res1
  end

  def unquote(:"dec_ECDSA-Sig-Value")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"ECDSA-Sig-Value", term1, term2}
    res1
  end

  def dec_ECPVer(tlv, tagIn), do: (val1 = decode_integer(tlv, tagIn); number2name(val1, [{:ecpVer1, 1}]))

  def dec_ECParameters(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [2]); number2name(val1, [{:ecpVer1, 1}]))
    [v2 | tlv3] = tlv2
    term2 = dec_FieldID(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = dec_Curve(v3, [16])
    [v4 | tlv5] = tlv4
    term4 = decode_octet_string(v4, [4])
    [v5 | tlv6] = tlv5
    term5 = decode_integer(v5, [2])
    {term6, tlv7} = case tlv6 do
      [{2, v6} | tempTlv7] ->
        {decode_integer(v6, []), tempTlv7}
      _ ->
        {:asn1_NOVALUE, tlv6}
    end
    case tlv7 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv7}}})
    end
    res1 = {:ECParameters, term1, term2, term3, term4, term5, term6}
    res1
  end

  def dec_ECPoint(tlv, tagIn), do: decode_octet_string(tlv, tagIn)

  def dec_ECPrivateKey(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_octet_string(v2, [4])
    {term3, tlv4} = case tlv3 do
      [{131072, v3} | tempTlv4] ->
        {dec_EcpkParameters(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    {term4, tlv5} = case tlv4 do
      [{131073, v4} | tempTlv5] ->
        {decode_native_bit_string(v4, [3]), tempTlv5}
      _ ->
        {:asn1_NOVALUE, tlv4}
    end
    case tlv5 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv5}}})
    end
    res1 = {:ECPrivateKey, term1, term2, term3, term4}
    res1
  end

  def dec_EDIPartyName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {dec_DirectoryString(v1, []), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    [v2 | tlv3] = tlv2
    term2 = dec_DirectoryString(v2, [131073])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:EDIPartyName, term1, term2}
    res1
  end

  def dec_EcpkParameters(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {16, v1} ->
        {:ecParameters, dec_ECParameters(v1, [])}
      {6, v1} ->
        {:namedCurve, decode_object_identifier(v1, [])}
      {5, v1} ->
        {:implicitlyCA, decode_null(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_EmailAddress(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 255 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_EncryptedContent(tlv, tagIn), do: decode_octet_string(tlv, tagIn)

  def dec_EncryptedContentInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    [v2 | tlv3] = tlv2
    term2 = dec_ContentEncryptionAlgorithmIdentifier(v2, [16])
    {term3, tlv4} = case tlv3 do
      [{131072, v3} | tempTlv4] ->
        {decode_octet_string(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:EncryptedContentInfo, term1, term2, term3}
    res1
  end

  def dec_EncryptedData(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [2]); number2name(val1, [{:edVer0, 0}]))
    [v2 | tlv3] = tlv2
    term2 = dec_EncryptedContentInfo(v2, [16])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:EncryptedData, term1, term2}
    res1
  end

  def dec_EncryptedDigest(tlv, tagIn), do: decode_octet_string(tlv, tagIn)

  def dec_EncryptedKey(tlv, tagIn), do: decode_octet_string(tlv, tagIn)

  def dec_EnvelopedData(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [2]); number2name(val1, [{:edVer0, 0}, {:edVer1, 1}]))
    [v2 | tlv3] = tlv2
    term2 = dec_RecipientInfos(v2, [])
    [v3 | tlv4] = tlv3
    term3 = dec_EncryptedContentInfo(v3, [16])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:EnvelopedData, term1, term2, term3}
    res1
  end

  def dec_ExtKeyUsageSyntax(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      decode_object_identifier(v1, [6])
    end
  end

  def dec_ExtendedCertificate(tlv, tagIn), do: dec_Certificate(tlv, tagIn)

  def dec_ExtendedCertificateOrCertificate(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {16, v1} ->
        {:certificate, dec_Certificate(v1, [])}
      {131072, v1} ->
        {:extendedCertificate, dec_ExtendedCertificate(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_ExtendedCertificatesAndCertificates(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_ExtendedCertificateOrCertificate(v1, [])
    end
  end

  def dec_ExtendedNetworkAddress(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {16, v1} ->
        {:"e163-4-address", apply(__MODULE__, :"dec_ExtendedNetworkAddress_e163-4-address", [v1, []])}
      {131072, v1} ->
        {:"psap-address", dec_PresentationAddress(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_Extension(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {term2, tlv3} = case tlv2 do
      [{1, v2} | tempTlv3] ->
        {decode_boolean(v2, []), tempTlv3}
      _ ->
        {false, tlv2}
    end
    [v3 | tlv4] = tlv3
    term3 = decode_octet_string(v3, [4])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:Extension, term1, term2, term3}
    res1
  end

  def unquote(:"dec_Extension-Any")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {term2, tlv3} = case tlv2 do
      [{1, v2} | tempTlv3] ->
        {decode_boolean(v2, []), tempTlv3}
      _ ->
        {false, tlv2}
    end
    [v3 | tlv4] = tlv3
    term3 = decode_open_type_as_binary(v3, [])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:"Extension-Any", term1, term2, term3}
    res1
  end

  def dec_ExtensionAttribute(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [131072]); cond do
      0 <= val1 and val1 <= 256 ->
        val1
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
    [v2 | tlv3] = tlv2
    term2 = decode_open_type_as_binary(v2, [131073])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:ExtensionAttribute, term1, term2}
    res1
  end

  def dec_ExtensionAttributes(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_ExtensionAttribute(v1, [16])
    end
  end

  def dec_ExtensionORAddressComponents(tlv, tagIn), do: dec_PDSParameter(tlv, tagIn)

  def dec_ExtensionPhysicalDeliveryAddressComponents(tlv, tagIn), do: dec_PDSParameter(tlv, tagIn)

  def dec_ExtensionRequest(tlv, tagIn), do: dec_Extensions(tlv, tagIn)

  def dec_Extensions(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_Extension(v1, [16])
    end
  end

  def dec_FieldElement(tlv, tagIn), do: decode_octet_string(tlv, tagIn)

  def dec_FieldID(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    [v2 | tlv3] = tlv2
    term2 = decode_open_type_as_binary(v2, [])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:FieldID, term1, term2}
    res1
  end

  def dec_FreshestCRL(tlv, tagIn), do: dec_CRLDistributionPoints(tlv, tagIn)

  def dec_GeneralName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {131072, v1} ->
        {:otherName, dec_AnotherName(v1, [])}
      {131073, v1} ->
        {:rfc822Name, (binary_to_list(decode_restricted_string(v1, [])))}
      {131074, v1} ->
        {:dNSName, (binary_to_list(decode_restricted_string(v1, [])))}
      {131075, v1} ->
        {:x400Address, dec_ORAddress(v1, [])}
      {131076, v1} ->
        {:directoryName, dec_Name(v1, [])}
      {131077, v1} ->
        {:ediPartyName, dec_EDIPartyName(v1, [])}
      {131078, v1} ->
        {:uniformResourceIdentifier, (binary_to_list(decode_restricted_string(v1, [])))}
      {131079, v1} ->
        {:iPAddress, decode_octet_string(v1, [])}
      {131080, v1} ->
        {:registeredID, decode_object_identifier(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_GeneralNames(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_GeneralName(v1, [])
    end
  end

  def dec_GeneralSubtree(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_GeneralName(v1, [])
    {term2, tlv3} = case tlv2 do
      [{131072, v2} | tempTlv3] ->
        {(val1 = decode_integer(v2, []); cond do
          0 <= val1 and val1 <= :MAX ->
            val1
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv3}
      _ ->
        {0, tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{131073, v3} | tempTlv4] ->
        {(val2 = decode_integer(v3, []); cond do
          0 <= val2 and val2 <= :MAX ->
            val2
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:GeneralSubtree, term1, term2, term3}
    res1
  end

  def dec_GeneralSubtrees(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_GeneralSubtree(v1, [16])
    end
  end

  def dec_HoldInstructionCode(tlv, tagIn), do: decode_object_identifier(tlv, tagIn)

  def dec_Holder(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {dec_IssuerSerial(v1, []), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{131073, v2} | tempTlv3] ->
        {dec_GeneralNames(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{131074, v3} | tempTlv4] ->
        {dec_ObjectDigestInfo(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:Holder, term1, term2, term3}
    res1
  end

  def dec_IetfAttrSyntax(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {dec_GeneralNames(v1, []), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    [v2 | tlv3] = tlv2
    term2 = dec_IetfAttrSyntax_values(v2, [16])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:IetfAttrSyntax, term1, term2}
    res1
  end

  def dec_InhibitAnyPolicy(tlv, tagIn) do
    (val1 = decode_integer(tlv, tagIn); cond do
      0 <= val1 and val1 <= :MAX ->
        val1
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_InvalidityDate(tlv, tagIn), do: (binary_to_list(decode_restricted_string(tlv, tagIn)))

  def dec_IssuerAltName(tlv, tagIn), do: dec_GeneralNames(tlv, tagIn)

  def dec_IssuerAndSerialNumber(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_Name(v1, [])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:IssuerAndSerialNumber, term1, term2}
    res1
  end

  def dec_IssuerSerial(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_GeneralNames(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    {term3, tlv4} = case tlv3 do
      [{3, v3} | tempTlv4] ->
        {decode_native_bit_string(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:IssuerSerial, term1, term2, term3}
    res1
  end

  def dec_IssuingDistributionPoint(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {dec_DistributionPointName(v1, []), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{131073, v2} | tempTlv3] ->
        {decode_boolean(v2, []), tempTlv3}
      _ ->
        {false, tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{131074, v3} | tempTlv4] ->
        {decode_boolean(v3, []), tempTlv4}
      _ ->
        {false, tlv3}
    end
    {term4, tlv5} = case tlv4 do
      [{131075, v4} | tempTlv5] ->
        {decode_named_bit_string(v4, [{:unused, 0}, {:keyCompromise, 1}, {:cACompromise, 2}, {:affiliationChanged, 3}, {:superseded, 4}, {:cessationOfOperation, 5}, {:certificateHold, 6}, {:privilegeWithdrawn, 7}, {:aACompromise, 8}], []), tempTlv5}
      _ ->
        {:asn1_NOVALUE, tlv4}
    end
    {term5, tlv6} = case tlv5 do
      [{131076, v5} | tempTlv6] ->
        {decode_boolean(v5, []), tempTlv6}
      _ ->
        {false, tlv5}
    end
    {term6, tlv7} = case tlv6 do
      [{131077, v6} | tempTlv7] ->
        {decode_boolean(v6, []), tempTlv7}
      _ ->
        {false, tlv6}
    end
    case tlv7 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv7}}})
    end
    res1 = {:IssuingDistributionPoint, term1, term2, term3, term4, term5, term6}
    res1
  end

  def unquote(:"dec_KEA-Parms-Id")(tlv, tagIn), do: decode_octet_string(tlv, tagIn)

  def unquote(:"dec_KEA-PublicKey")(tlv, tagIn), do: decode_integer(tlv, tagIn)

  def dec_KeyEncryptionAlgorithmIdentifier(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {tmpterm1, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    decObjalgorithmTerm1 = :"OTP-PUB-KEY".getdec_internal_object_set_argument_3(term1)
    term2 = case tmpterm1 do
      :asn1_NOVALUE ->
        :asn1_NOVALUE
      _ ->
        try do
          decObjalgorithmTerm1.(:Type, tmpterm1, [])
        catch
          error -> error
        end
        |> case do
          {:EXIT, reason1} ->
            exit({:"Type not compatible with table constraint", reason1})
          tmpterm2 ->
            tmpterm2
        end
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:KeyEncryptionAlgorithmIdentifier, term1, term2}
    res1
  end

  def dec_KeyIdentifier(tlv, tagIn), do: decode_octet_string(tlv, tagIn)

  def dec_KeyPurposeId(tlv, tagIn), do: decode_object_identifier(tlv, tagIn)

  def dec_KeyUsage(tlv, tagIn), do: decode_named_bit_string(tlv, [{:digitalSignature, 0}, {:nonRepudiation, 1}, {:keyEncipherment, 2}, {:dataEncipherment, 3}, {:keyAgreement, 4}, {:keyCertSign, 5}, {:cRLSign, 6}, {:encipherOnly, 7}, {:decipherOnly, 8}], tagIn)

  def dec_LocalPostalAttributes(tlv, tagIn), do: dec_PDSParameter(tlv, tagIn)

  def dec_MessageDigest(tlv, tagIn), do: decode_octet_string(tlv, tagIn)

  def dec_Name(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {16, v1} ->
        {:rdnSequence, dec_RDNSequence(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_NameConstraints(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {dec_GeneralSubtrees(v1, []), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{131073, v2} | tempTlv3] ->
        {dec_GeneralSubtrees(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:NameConstraints, term1, term2}
    res1
  end

  def dec_NetworkAddress(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 16 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_NoticeReference(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_DisplayText(v1, [])
    [v2 | tlv3] = tlv2
    term2 = dec_NoticeReference_noticeNumbers(v2, [16])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:NoticeReference, term1, term2}
    res1
  end

  def dec_NumericUserIdentifier(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 32 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_ORAddress(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_BuiltInStandardAttributes(v1, [16])
    {term2, tlv3} = case tlv2 do
      [{16, v2} | tempTlv3] ->
        {dec_BuiltInDomainDefinedAttributes(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{17, v3} | tempTlv4] ->
        {dec_ExtensionAttributes(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:ORAddress, term1, term2, term3}
    res1
  end

  def unquote(:"dec_OTP-X520countryname")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {19, v1} ->
        {:printableString, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          c1 === 2 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def unquote(:"dec_OTP-emailAddress")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {22, v1} ->
        {:ia5String, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 255 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_OTPAttributeTypeAndValue(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    [v2 | tlv3] = tlv2
    tmpterm1 = decode_open_type(v2, [])
    decObjtypeTerm1 = :"OTP-PUB-KEY".getdec_SupportedAttributeTypeAndValues(term1)
    term2 = try do
      decObjtypeTerm1.(:Type, tmpterm1, [])
    catch
      error -> error
    end
    |> case do
      {:EXIT, reason1} ->
        exit({:"Type not compatible with table constraint", reason1})
      tmpterm2 ->
        tmpterm2
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:OTPAttributeTypeAndValue, term1, term2}
    res1
  end

  def dec_OTPCertificate(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_OTPTBSCertificate(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = dec_SignatureAlgorithm(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = decode_native_bit_string(v3, [3])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:OTPCertificate, term1, term2, term3}
    res1
  end

  def unquote(:"dec_OTPCharacteristic-two")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_object_identifier(v2, [6])
    [v3 | tlv4] = tlv3
    tmpterm1 = decode_open_type(v3, [])
    decObjbasisTerm2 = :"OTP-PUB-KEY".getdec_SupportedCharacteristicTwos(term2)
    term3 = try do
      decObjbasisTerm2.(:Type, tmpterm1, [])
    catch
      error -> error
    end
    |> case do
      {:EXIT, reason1} ->
        exit({:"Type not compatible with table constraint", reason1})
      tmpterm2 ->
        tmpterm2
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:"OTPCharacteristic-two", term1, term2, term3}
    res1
  end

  def dec_OTPExtension(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {term2, tlv3} = case tlv2 do
      [{1, v2} | tempTlv3] ->
        {decode_boolean(v2, []), tempTlv3}
      _ ->
        {false, tlv2}
    end
    [v3 | tlv4] = tlv3
    tmpterm1 = decode_open_type(v3, [])
    decObjextnIDTerm1 = :"OTP-PUB-KEY".getdec_SupportedExtensions(term1)
    term3 = try do
      decObjextnIDTerm1.(:Type, tmpterm1, [])
    catch
      error -> error
    end
    |> case do
      {:EXIT, reason1} ->
        exit({:"Type not compatible with table constraint", reason1})
      tmpterm2 ->
        tmpterm2
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:OTPExtension, term1, term2, term3}
    res1
  end

  def dec_OTPExtensionAttribute(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [131072])
    [v2 | tlv3] = tlv2
    tmpterm1 = decode_open_type(v2, [131073])
    decObjextensionAttributeTypeTerm1 = :"OTP-PUB-KEY".getdec_SupportedExtensionAttributes(term1)
    term2 = try do
      decObjextensionAttributeTypeTerm1.(:Type, tmpterm1, [])
    catch
      error -> error
    end
    |> case do
      {:EXIT, reason1} ->
        exit({:"Type not compatible with table constraint", reason1})
      tmpterm2 ->
        tmpterm2
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:OTPExtensionAttribute, term1, term2}
    res1
  end

  def dec_OTPExtensionAttributes(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_ExtensionAttribute(v1, [16])
    end
  end

  def dec_OTPExtensions(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_Extension(v1, [16])
    end
  end

  def dec_OTPFieldID(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    [v2 | tlv3] = tlv2
    tmpterm1 = decode_open_type(v2, [])
    decObjfieldTypeTerm1 = :"OTP-PUB-KEY".getdec_SupportedFieldIds(term1)
    term2 = try do
      decObjfieldTypeTerm1.(:Type, tmpterm1, [])
    catch
      error -> error
    end
    |> case do
      {:EXIT, reason1} ->
        exit({:"Type not compatible with table constraint", reason1})
      tmpterm2 ->
        tmpterm2
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:OTPFieldID, term1, term2}
    res1
  end

  def dec_OTPOLDSubjectPublicKeyInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_OTPOLDSubjectPublicKeyInfo_algorithm(v1, [16])
    [v2 | tlv3] = tlv2
    tmpterm1 = decode_open_type(v2, [])
    decObjalgorithmTerm1 = :"OTP-PUB-KEY".getdec_SupportedPublicKeyAlgorithms(element(2, term1))
    term2 = try do
      decObjalgorithmTerm1.(:PublicKeyType, tmpterm1, [])
    catch
      error -> error
    end
    |> case do
      {:EXIT, reason1} ->
        exit({:"Type not compatible with table constraint", reason1})
      tmpterm2 ->
        tmpterm2
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:OTPOLDSubjectPublicKeyInfo, term1, term2}
    res1
  end

  def dec_OTPSubjectPublicKeyInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_PublicKeyAlgorithm(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = decode_native_bit_string(v2, [3])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:OTPSubjectPublicKeyInfo, term1, term2}
    res1
  end

  def unquote(:"dec_OTPSubjectPublicKeyInfo-Any")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_PublicKeyAlgorithm(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = decode_open_type_as_binary(v2, [])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"OTPSubjectPublicKeyInfo-Any", term1, term2}
    res1
  end

  def dec_OTPTBSCertificate(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {(val1 = decode_integer(v1, [2]); number2name(val1, [{:v1, 0}, {:v2, 1}, {:v3, 2}])), tempTlv2}
      _ ->
        {0, tlv1}
    end
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    [v3 | tlv4] = tlv3
    term3 = dec_SignatureAlgorithm(v3, [16])
    [v4 | tlv5] = tlv4
    term4 = dec_Name(v4, [])
    [v5 | tlv6] = tlv5
    term5 = dec_Validity(v5, [16])
    [v6 | tlv7] = tlv6
    term6 = dec_Name(v6, [])
    [v7 | tlv8] = tlv7
    term7 = dec_OTPSubjectPublicKeyInfo(v7, [16])
    {term8, tlv9} = case tlv8 do
      [{131073, v8} | tempTlv9] ->
        {decode_native_bit_string(v8, []), tempTlv9}
      _ ->
        {:asn1_NOVALUE, tlv8}
    end
    {term9, tlv10} = case tlv9 do
      [{131074, v9} | tempTlv10] ->
        {decode_native_bit_string(v9, []), tempTlv10}
      _ ->
        {:asn1_NOVALUE, tlv9}
    end
    {term10, tlv11} = case tlv10 do
      [{131075, v10} | tempTlv11] ->
        {dec_Extensions(v10, [16]), tempTlv11}
      _ ->
        {:asn1_NOVALUE, tlv10}
    end
    case tlv11 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv11}}})
    end
    res1 = {:OTPTBSCertificate, term1, term2, term3, term4, term5, term6, term7, term8, term9, term10}
    res1
  end

  def dec_ObjId(tlv, tagIn), do: decode_object_identifier(tlv, tagIn)

  def dec_ObjectDigestInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = case decode_integer(v1, [10]) do
      0 ->
        :publicKey
      1 ->
        :publicKeyCert
      2 ->
        :otherObjectTypes
      default1 ->
        exit({:error, {:asn1, {:illegal_enumerated, default1}}})
    end
    {term2, tlv3} = case tlv2 do
      [{6, v2} | tempTlv3] ->
        {decode_object_identifier(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    [v3 | tlv4] = tlv3
    term3 = dec_AlgorithmIdentifier(v3, [16])
    [v4 | tlv5] = tlv4
    term4 = decode_native_bit_string(v4, [3])
    case tlv5 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv5}}})
    end
    res1 = {:ObjectDigestInfo, term1, term2, term3, term4}
    res1
  end

  def dec_OrganizationName(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 128 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_OrganizationalUnitName(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 128 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_OrganizationalUnitNames(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      (val1 = decode_restricted_string(v1, [19]); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 128 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
    end
  end

  def dec_OtherPrimeInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    [v3 | tlv4] = tlv3
    term3 = decode_integer(v3, [2])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:OtherPrimeInfo, term1, term2, term3}
    res1
  end

  def dec_OtherPrimeInfos(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_OtherPrimeInfo(v1, [16])
    end
  end

  def dec_PDSName(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 16 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_PDSParameter(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    setFun = fn funTlv ->
        case funTlv do
          tTlv = {19, _} ->
            {1, tTlv}
          tTlv = {20, _} ->
            {2, tTlv}
          erlangVariableElse ->
            {3, erlangVariableElse}
        end
    end
    positionList = for tempTlv <- tlv1 do
      setFun.(tempTlv)
    end
    tlv2 = for {_, stlv} <- :lists.sort(positionList) do
      stlv
    end
    {term1, tlv3} = case tlv2 do
      [{19, v1} | tempTlv3] ->
        {(val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 30 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    {term2, tlv4} = case tlv3 do
      [{20, v2} | tempTlv4] ->
        {(val2 = decode_restricted_string(v2, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 30 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:PDSParameter, term1, term2}
    res1
  end

  def dec_Pentanomial(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    [v3 | tlv4] = tlv3
    term3 = decode_integer(v3, [2])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:Pentanomial, term1, term2, term3}
    res1
  end

  def dec_PersonalName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    setFun = fn funTlv ->
        case funTlv do
          tTlv = {131072, _} ->
            {1, tTlv}
          tTlv = {131073, _} ->
            {2, tTlv}
          tTlv = {131074, _} ->
            {3, tTlv}
          tTlv = {131075, _} ->
            {4, tTlv}
          erlangVariableElse ->
            {5, erlangVariableElse}
        end
    end
    positionList = for tempTlv <- tlv1 do
      setFun.(tempTlv)
    end
    tlv2 = for {_, stlv} <- :lists.sort(positionList) do
      stlv
    end
    [v1 | tlv3] = tlv2
    term1 = (val1 = decode_restricted_string(v1, [131072]); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 40 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
    {term2, tlv4} = case tlv3 do
      [{131073, v2} | tempTlv4] ->
        {(val2 = decode_restricted_string(v2, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 16 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    {term3, tlv5} = case tlv4 do
      [{131074, v3} | tempTlv5] ->
        {(val3 = decode_restricted_string(v3, []); c3 = byte_size(val3); cond do
          1 <= c3 and c3 <= 5 ->
            binary_to_list(val3)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv5}
      _ ->
        {:asn1_NOVALUE, tlv4}
    end
    {term4, tlv6} = case tlv5 do
      [{131075, v4} | tempTlv6] ->
        {(val4 = decode_restricted_string(v4, []); c4 = byte_size(val4); cond do
          1 <= c4 and c4 <= 3 ->
            binary_to_list(val4)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv6}
      _ ->
        {:asn1_NOVALUE, tlv5}
    end
    case tlv6 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv6}}})
    end
    res1 = {:PersonalName, term1, term2, term3, term4}
    res1
  end

  def dec_PhysicalDeliveryCountryName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {18, v1} ->
        {:"x121-dcc-code", (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          c1 === 3 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:"iso-3166-alpha2-code", (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          c2 === 2 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_PhysicalDeliveryOfficeName(tlv, tagIn), do: dec_PDSParameter(tlv, tagIn)

  def dec_PhysicalDeliveryOfficeNumber(tlv, tagIn), do: dec_PDSParameter(tlv, tagIn)

  def dec_PhysicalDeliveryOrganizationName(tlv, tagIn), do: dec_PDSParameter(tlv, tagIn)

  def dec_PhysicalDeliveryPersonalName(tlv, tagIn), do: dec_PDSParameter(tlv, tagIn)

  def dec_PolicyConstraints(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {(val1 = decode_integer(v1, []); cond do
          0 <= val1 and val1 <= :MAX ->
            val1
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{131073, v2} | tempTlv3] ->
        {(val2 = decode_integer(v2, []); cond do
          0 <= val2 and val2 <= :MAX ->
            val2
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:PolicyConstraints, term1, term2}
    res1
  end

  def dec_PolicyInformation(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {term2, tlv3} = case tlv2 do
      [{16, v2} | tempTlv3] ->
        {dec_PolicyInformation_policyQualifiers(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:PolicyInformation, term1, term2}
    res1
  end

  def dec_PolicyMappings(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_PolicyMappings_SEQOF(v1, [16])
    end
  end

  def dec_PolicyQualifierId(tlv, tagIn), do: decode_object_identifier(tlv, tagIn)

  def dec_PolicyQualifierInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    [v2 | tlv3] = tlv2
    term2 = decode_open_type_as_binary(v2, [])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:PolicyQualifierInfo, term1, term2}
    res1
  end

  def dec_PostOfficeBoxAddress(tlv, tagIn), do: dec_PDSParameter(tlv, tagIn)

  def dec_PostalCode(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {18, v1} ->
        {:"numeric-code", (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 16 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:"printable-code", (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 16 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_PosteRestanteAddress(tlv, tagIn), do: dec_PDSParameter(tlv, tagIn)

  def dec_PresentationAddress(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {decode_octet_string(v1, [4]), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{131073, v2} | tempTlv3] ->
        {decode_octet_string(v2, [4]), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{131074, v3} | tempTlv4] ->
        {decode_octet_string(v3, [4]), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    [v4 | tlv5] = tlv4
    term4 = dec_PresentationAddress_nAddresses(v4, [131075, 17])
    case tlv5 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv5}}})
    end
    res1 = {:PresentationAddress, term1, term2, term3, term4}
    res1
  end

  def unquote(:"dec_Prime-p")(tlv, tagIn), do: decode_integer(tlv, tagIn)

  def dec_PrivateDomainName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {18, v1} ->
        {:numeric, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 16 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:printable, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 16 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_PrivateKeyUsagePeriod(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {(binary_to_list(decode_restricted_string(v1, []))), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{131073, v2} | tempTlv3] ->
        {(binary_to_list(decode_restricted_string(v2, []))), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:PrivateKeyUsagePeriod, term1, term2}
    res1
  end

  def dec_ProxyInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_Targets(v1, [16])
    end
  end

  def dec_PublicKeyAlgorithm(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {tmpterm1, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    decObjalgorithmTerm1 = :"OTP-PUB-KEY".getdec_SupportedPublicKeyAlgorithms(term1)
    term2 = case tmpterm1 do
      :asn1_NOVALUE ->
        :asn1_NOVALUE
      _ ->
        try do
          decObjalgorithmTerm1.(:Type, tmpterm1, [])
        catch
          error -> error
        end
        |> case do
          {:EXIT, reason1} ->
            exit({:"Type not compatible with table constraint", reason1})
          tmpterm2 ->
            tmpterm2
        end
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:PublicKeyAlgorithm, term1, term2}
    res1
  end

  def dec_RDNSequence(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_RelativeDistinguishedName(v1, [17])
    end
  end

  def dec_RSAPrivateKey(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [2]); number2name(val1, [{:"two-prime", 0}, {:multi, 1}]))
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    [v3 | tlv4] = tlv3
    term3 = decode_integer(v3, [2])
    [v4 | tlv5] = tlv4
    term4 = decode_integer(v4, [2])
    [v5 | tlv6] = tlv5
    term5 = decode_integer(v5, [2])
    [v6 | tlv7] = tlv6
    term6 = decode_integer(v6, [2])
    [v7 | tlv8] = tlv7
    term7 = decode_integer(v7, [2])
    [v8 | tlv9] = tlv8
    term8 = decode_integer(v8, [2])
    [v9 | tlv10] = tlv9
    term9 = decode_integer(v9, [2])
    {term10, tlv11} = case tlv10 do
      [{16, v10} | tempTlv11] ->
        {dec_OtherPrimeInfos(v10, []), tempTlv11}
      _ ->
        {:asn1_NOVALUE, tlv10}
    end
    case tlv11 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv11}}})
    end
    res1 = {:RSAPrivateKey, term1, term2, term3, term4, term5, term6, term7, term8, term9, term10}
    res1
  end

  def dec_RSAPublicKey(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:RSAPublicKey, term1, term2}
    res1
  end

  def unquote(:"dec_RSASSA-PSS-params")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_Algorithm(v1, [131072, 16])
    [v2 | tlv3] = tlv2
    term2 = dec_Algorithm(v2, [131073, 16])
    {term3, tlv4} = case tlv3 do
      [{131074, v3} | tempTlv4] ->
        {decode_integer(v3, [2]), tempTlv4}
      _ ->
        {20, tlv3}
    end
    {term4, tlv5} = case tlv4 do
      [{131075, v4} | tempTlv5] ->
        {(val1 = decode_integer(v4, [2]); number2name(val1, [{:trailerFieldBC, 1}])), tempTlv5}
      _ ->
        {1, tlv4}
    end
    case tlv5 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv5}}})
    end
    res1 = {:"RSASSA-PSS-params", term1, term2, term3, term4}
    res1
  end

  def dec_ReasonFlags(tlv, tagIn), do: decode_named_bit_string(tlv, [{:unused, 0}, {:keyCompromise, 1}, {:cACompromise, 2}, {:affiliationChanged, 3}, {:superseded, 4}, {:cessationOfOperation, 5}, {:certificateHold, 6}, {:privilegeWithdrawn, 7}, {:aACompromise, 8}], tagIn)

  def dec_RecipientInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [2]); number2name(val1, [{:riVer0, 0}]))
    [v2 | tlv3] = tlv2
    term2 = dec_IssuerAndSerialNumber(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = dec_KeyEncryptionAlgorithmIdentifier(v3, [16])
    [v4 | tlv5] = tlv4
    term4 = decode_octet_string(v4, [4])
    case tlv5 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv5}}})
    end
    res1 = {:RecipientInfo, term1, term2, term3, term4}
    res1
  end

  def dec_RecipientInfos(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {17, v1} ->
        {:riSet, dec_RecipientInfos_riSet(v1, [])}
      {16, v1} ->
        {:riSequence, dec_RecipientInfos_riSequence(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_RelativeDistinguishedName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_AttributeTypeAndValue(v1, [16])
    end
  end

  def dec_RoleSyntax(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {dec_GeneralNames(v1, []), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    [v2 | tlv3] = tlv2
    term2 = dec_GeneralName(v2, [131073])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:RoleSyntax, term1, term2}
    res1
  end

  def dec_SecurityCategory(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [131072])
    [v2 | tlv3] = tlv2
    term2 = decode_open_type_as_binary(v2, [131073])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:SecurityCategory, term1, term2}
    res1
  end

  def dec_SignatureAlgorithm(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {tmpterm1, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    decObjalgorithmTerm1 = :"OTP-PUB-KEY".getdec_SupportedSignatureAlgorithms(term1)
    term2 = case tmpterm1 do
      :asn1_NOVALUE ->
        :asn1_NOVALUE
      _ ->
        try do
          decObjalgorithmTerm1.(:Type, tmpterm1, [])
        catch
          error -> error
        end
        |> case do
          {:EXIT, reason1} ->
            exit({:"Type not compatible with table constraint", reason1})
          tmpterm2 ->
            tmpterm2
        end
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:SignatureAlgorithm, term1, term2}
    res1
  end

  def unquote(:"dec_SignatureAlgorithm-Any")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {term2, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type_as_binary(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"SignatureAlgorithm-Any", term1, term2}
    res1
  end

  def dec_SignedAndEnvelopedData(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [2]); number2name(val1, [{:seVer1, 1}, {:seVer2, 2}]))
    [v2 | tlv3] = tlv2
    term2 = dec_RecipientInfos(v2, [])
    [v3 | tlv4] = tlv3
    term3 = dec_DigestAlgorithmIdentifiers(v3, [])
    [v4 | tlv5] = tlv4
    term4 = dec_EncryptedContentInfo(v4, [16])
    {term5, tlv6} = case tlv5 do
      [v5 = {131072, _} | tempTlv6] ->
        {dec_SignedAndEnvelopedData_certificates(v5, []), tempTlv6}
      [v5 = {131074, _} | tempTlv6] ->
        {dec_SignedAndEnvelopedData_certificates(v5, []), tempTlv6}
      _ ->
        {:asn1_NOVALUE, tlv5}
    end
    {term6, tlv7} = case tlv6 do
      [v6 = {131073, _} | tempTlv7] ->
        {dec_SignedAndEnvelopedData_crls(v6, []), tempTlv7}
      [v6 = {131075, _} | tempTlv7] ->
        {dec_SignedAndEnvelopedData_crls(v6, []), tempTlv7}
      _ ->
        {:asn1_NOVALUE, tlv6}
    end
    [v7 | tlv8] = tlv7
    term7 = dec_SignerInfos(v7, [])
    case tlv8 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv8}}})
    end
    res1 = {:SignedAndEnvelopedData, term1, term2, term3, term4, term5, term6, term7}
    res1
  end

  def dec_SignedData(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [2]); number2name(val1, [{:sdVer1, 1}, {:sdVer2, 2}]))
    [v2 | tlv3] = tlv2
    term2 = dec_DigestAlgorithmIdentifiers(v2, [])
    [v3 | tlv4] = tlv3
    term3 = dec_ContentInfo(v3, [16])
    {term4, tlv5} = case tlv4 do
      [v4 = {131072, _} | tempTlv5] ->
        {dec_SignedData_certificates(v4, []), tempTlv5}
      [v4 = {131074, _} | tempTlv5] ->
        {dec_SignedData_certificates(v4, []), tempTlv5}
      _ ->
        {:asn1_NOVALUE, tlv4}
    end
    {term5, tlv6} = case tlv5 do
      [v5 = {131073, _} | tempTlv6] ->
        {dec_SignedData_crls(v5, []), tempTlv6}
      [v5 = {131075, _} | tempTlv6] ->
        {dec_SignedData_crls(v5, []), tempTlv6}
      _ ->
        {:asn1_NOVALUE, tlv5}
    end
    [v6 | tlv7] = tlv6
    term6 = dec_SignerInfos(v6, [])
    case tlv7 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv7}}})
    end
    res1 = {:SignedData, term1, term2, term3, term4, term5, term6}
    res1
  end

  def dec_SignerInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_integer(v1, [2]); number2name(val1, [{:siVer1, 1}, {:siVer2, 2}]))
    [v2 | tlv3] = tlv2
    term2 = dec_IssuerAndSerialNumber(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = dec_DigestAlgorithmIdentifier(v3, [16])
    {term4, tlv5} = case tlv4 do
      [v4 = {131072, _} | tempTlv5] ->
        {dec_SignerInfoAuthenticatedAttributes(v4, []), tempTlv5}
      [v4 = {131074, _} | tempTlv5] ->
        {dec_SignerInfoAuthenticatedAttributes(v4, []), tempTlv5}
      _ ->
        {:asn1_NOVALUE, tlv4}
    end
    [v5 | tlv6] = tlv5
    term5 = dec_DigestEncryptionAlgorithmIdentifier(v5, [16])
    [v6 | tlv7] = tlv6
    term6 = decode_octet_string(v6, [4])
    {term7, tlv8} = case tlv7 do
      [v7 = {131073, _} | tempTlv8] ->
        {dec_SignerInfo_unauthenticatedAttributes(v7, []), tempTlv8}
      [v7 = {131075, _} | tempTlv8] ->
        {dec_SignerInfo_unauthenticatedAttributes(v7, []), tempTlv8}
      _ ->
        {:asn1_NOVALUE, tlv7}
    end
    case tlv8 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv8}}})
    end
    res1 = {:SignerInfo, term1, term2, term3, term4, term5, term6, term7}
    res1
  end

  def dec_SignerInfoAuthenticatedAttributes(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {131072, v1} ->
        {:aaSet, dec_SignerInfoAuthenticatedAttributes_aaSet(v1, [])}
      {131074, v1} ->
        {:aaSequence, dec_SignerInfoAuthenticatedAttributes_aaSequence(v1, [16])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_SignerInfos(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {17, v1} ->
        {:siSet, dec_SignerInfos_siSet(v1, [])}
      {16, v1} ->
        {:siSequence, dec_SignerInfos_siSequence(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_SigningTime(tlv, tagIn), do: dec_Time(tlv, tagIn)

  def dec_SkipCerts(tlv, tagIn) do
    (val1 = decode_integer(tlv, tagIn); cond do
      0 <= val1 and val1 <= :MAX ->
        val1
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_StreetAddress(tlv, tagIn), do: dec_PDSParameter(tlv, tagIn)

  def dec_SubjectAltName(tlv, tagIn), do: dec_GeneralNames(tlv, tagIn)

  def dec_SubjectDirectoryAttributes(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_Attribute(v1, [16])
    end
  end

  def dec_SubjectInfoAccessSyntax(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_AccessDescription(v1, [16])
    end
  end

  def dec_SubjectKeyIdentifier(tlv, tagIn), do: decode_octet_string(tlv, tagIn)

  def dec_SubjectPublicKeyInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_AlgorithmIdentifier(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = decode_native_bit_string(v2, [3])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:SubjectPublicKeyInfo, term1, term2}
    res1
  end

  def dec_SvceAuthInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_GeneralName(v1, [])
    [v2 | tlv3] = tlv2
    term2 = dec_GeneralName(v2, [])
    {term3, tlv4} = case tlv3 do
      [{4, v3} | tempTlv4] ->
        {decode_octet_string(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:SvceAuthInfo, term1, term2, term3}
    res1
  end

  def dec_TBSCertList(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{2, v1} | tempTlv2] ->
        {(val1 = decode_integer(v1, []); number2name(val1, [{:v1, 0}, {:v2, 1}, {:v3, 2}])), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    [v2 | tlv3] = tlv2
    term2 = dec_AlgorithmIdentifier(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = dec_Name(v3, [])
    [v4 | tlv5] = tlv4
    term4 = dec_Time(v4, [])
    {term5, tlv6} = case tlv5 do
      [v5 = {23, _} | tempTlv6] ->
        {dec_Time(v5, []), tempTlv6}
      [v5 = {24, _} | tempTlv6] ->
        {dec_Time(v5, []), tempTlv6}
      _ ->
        {:asn1_NOVALUE, tlv5}
    end
    {term6, tlv7} = case tlv6 do
      [{16, v6} | tempTlv7] ->
        {dec_TBSCertList_revokedCertificates(v6, []), tempTlv7}
      _ ->
        {:asn1_NOVALUE, tlv6}
    end
    {term7, tlv8} = case tlv7 do
      [{131072, v7} | tempTlv8] ->
        {dec_Extensions(v7, [16]), tempTlv8}
      _ ->
        {:asn1_NOVALUE, tlv7}
    end
    case tlv8 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv8}}})
    end
    res1 = {:TBSCertList, term1, term2, term3, term4, term5, term6, term7}
    res1
  end

  def dec_TBSCertificate(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{131072, v1} | tempTlv2] ->
        {(val1 = decode_integer(v1, [2]); number2name(val1, [{:v1, 0}, {:v2, 1}, {:v3, 2}])), tempTlv2}
      _ ->
        {0, tlv1}
    end
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    [v3 | tlv4] = tlv3
    term3 = dec_AlgorithmIdentifier(v3, [16])
    [v4 | tlv5] = tlv4
    term4 = dec_Name(v4, [])
    [v5 | tlv6] = tlv5
    term5 = dec_Validity(v5, [16])
    [v6 | tlv7] = tlv6
    term6 = dec_Name(v6, [])
    [v7 | tlv8] = tlv7
    term7 = dec_SubjectPublicKeyInfo(v7, [16])
    {term8, tlv9} = case tlv8 do
      [{131073, v8} | tempTlv9] ->
        {decode_native_bit_string(v8, []), tempTlv9}
      _ ->
        {:asn1_NOVALUE, tlv8}
    end
    {term9, tlv10} = case tlv9 do
      [{131074, v9} | tempTlv10] ->
        {decode_native_bit_string(v9, []), tempTlv10}
      _ ->
        {:asn1_NOVALUE, tlv9}
    end
    {term10, tlv11} = case tlv10 do
      [{131075, v10} | tempTlv11] ->
        {dec_Extensions(v10, [16]), tempTlv11}
      _ ->
        {:asn1_NOVALUE, tlv10}
    end
    case tlv11 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv11}}})
    end
    res1 = {:TBSCertificate, term1, term2, term3, term4, term5, term6, term7, term8, term9, term10}
    res1
  end

  def dec_Target(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {131072, v1} ->
        {:targetName, dec_GeneralName(v1, [])}
      {131073, v1} ->
        {:targetGroup, dec_GeneralName(v1, [])}
      {131074, v1} ->
        {:targetCert, dec_TargetCert(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_TargetCert(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_IssuerSerial(v1, [16])
    {term2, tlv3} = case tlv2 do
      [v2 = {131072, _} | tempTlv3] ->
        {dec_GeneralName(v2, []), tempTlv3}
      [v2 = {131073, _} | tempTlv3] ->
        {dec_GeneralName(v2, []), tempTlv3}
      [v2 = {131074, _} | tempTlv3] ->
        {dec_GeneralName(v2, []), tempTlv3}
      [v2 = {131075, _} | tempTlv3] ->
        {dec_GeneralName(v2, []), tempTlv3}
      [v2 = {131076, _} | tempTlv3] ->
        {dec_GeneralName(v2, []), tempTlv3}
      [v2 = {131077, _} | tempTlv3] ->
        {dec_GeneralName(v2, []), tempTlv3}
      [v2 = {131078, _} | tempTlv3] ->
        {dec_GeneralName(v2, []), tempTlv3}
      [v2 = {131079, _} | tempTlv3] ->
        {dec_GeneralName(v2, []), tempTlv3}
      [v2 = {131080, _} | tempTlv3] ->
        {dec_GeneralName(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{16, v3} | tempTlv4] ->
        {dec_ObjectDigestInfo(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:TargetCert, term1, term2, term3}
    res1
  end

  def dec_Targets(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_Target(v1, [])
    end
  end

  def dec_TeletexCommonName(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 128 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_TeletexDomainDefinedAttribute(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_restricted_string(v1, [20]); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 8 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
    [v2 | tlv3] = tlv2
    term2 = (val2 = decode_restricted_string(v2, [20]); c2 = byte_size(val2); cond do
      1 <= c2 and c2 <= 128 ->
        binary_to_list(val2)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:TeletexDomainDefinedAttribute, term1, term2}
    res1
  end

  def dec_TeletexDomainDefinedAttributes(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_TeletexDomainDefinedAttribute(v1, [16])
    end
  end

  def dec_TeletexOrganizationName(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 128 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_TeletexOrganizationalUnitName(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 128 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_TeletexOrganizationalUnitNames(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      (val1 = decode_restricted_string(v1, [20]); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 128 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
    end
  end

  def dec_TeletexPersonalName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    setFun = fn funTlv ->
        case funTlv do
          tTlv = {131072, _} ->
            {1, tTlv}
          tTlv = {131073, _} ->
            {2, tTlv}
          tTlv = {131074, _} ->
            {3, tTlv}
          tTlv = {131075, _} ->
            {4, tTlv}
          erlangVariableElse ->
            {5, erlangVariableElse}
        end
    end
    positionList = for tempTlv <- tlv1 do
      setFun.(tempTlv)
    end
    tlv2 = for {_, stlv} <- :lists.sort(positionList) do
      stlv
    end
    [v1 | tlv3] = tlv2
    term1 = (val1 = decode_restricted_string(v1, [131072]); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 40 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
    {term2, tlv4} = case tlv3 do
      [{131073, v2} | tempTlv4] ->
        {(val2 = decode_restricted_string(v2, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 16 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    {term3, tlv5} = case tlv4 do
      [{131074, v3} | tempTlv5] ->
        {(val3 = decode_restricted_string(v3, []); c3 = byte_size(val3); cond do
          1 <= c3 and c3 <= 5 ->
            binary_to_list(val3)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv5}
      _ ->
        {:asn1_NOVALUE, tlv4}
    end
    {term4, tlv6} = case tlv5 do
      [{131075, v4} | tempTlv6] ->
        {(val4 = decode_restricted_string(v4, []); c4 = byte_size(val4); cond do
          1 <= c4 and c4 <= 3 ->
            binary_to_list(val4)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv6}
      _ ->
        {:asn1_NOVALUE, tlv5}
    end
    case tlv6 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv6}}})
    end
    res1 = {:TeletexPersonalName, term1, term2, term3, term4}
    res1
  end

  def dec_TerminalIdentifier(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 24 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_TerminalType(tlv, tagIn) do
    (val1 = decode_integer(tlv, tagIn); cond do
      0 <= val1 and val1 <= 256 ->
        val2 = val1
        number2name(val2, [{:telex, 3}, {:teletex, 4}, {:"g3-facsimile", 5}, {:"g4-facsimile", 6}, {:"ia5-terminal", 7}, {:videotex, 8}])
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_Time(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {23, v1} ->
        {:utcTime, (binary_to_list(decode_restricted_string(v1, [])))}
      {24, v1} ->
        {:generalTime, (binary_to_list(decode_restricted_string(v1, [])))}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_TrailerField(tlv, tagIn), do: (val1 = decode_integer(tlv, tagIn); number2name(val1, [{:trailerFieldBC, 1}]))

  def dec_Trinomial(tlv, tagIn), do: decode_integer(tlv, tagIn)

  def dec_UnformattedPostalAddress(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    setFun = fn funTlv ->
        case funTlv do
          tTlv = {16, _} ->
            {1, tTlv}
          tTlv = {20, _} ->
            {2, tTlv}
          erlangVariableElse ->
            {3, erlangVariableElse}
        end
    end
    positionList = for tempTlv <- tlv1 do
      setFun.(tempTlv)
    end
    tlv2 = for {_, stlv} <- :lists.sort(positionList) do
      stlv
    end
    {term1, tlv3} = case tlv2 do
      [{16, v1} | tempTlv3] ->
        {apply(__MODULE__, :"dec_UnformattedPostalAddress_printable-address", [v1, []]), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    {term2, tlv4} = case tlv3 do
      [{20, v2} | tempTlv4] ->
        {(val1 = decode_restricted_string(v2, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 180 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:UnformattedPostalAddress, term1, term2}
    res1
  end

  def dec_UniqueIdentifier(tlv, tagIn), do: decode_native_bit_string(tlv, tagIn)

  def dec_UniquePostalName(tlv, tagIn), do: dec_PDSParameter(tlv, tagIn)

  def dec_UserNotice(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{16, v1} | tempTlv2] ->
        {dec_NoticeReference(v1, []), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [v2 = {22, _} | tempTlv3] ->
        {dec_DisplayText(v2, []), tempTlv3}
      [v2 = {26, _} | tempTlv3] ->
        {dec_DisplayText(v2, []), tempTlv3}
      [v2 = {30, _} | tempTlv3] ->
        {dec_DisplayText(v2, []), tempTlv3}
      [v2 = {12, _} | tempTlv3] ->
        {dec_DisplayText(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:UserNotice, term1, term2}
    res1
  end

  def dec_V2Form(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    {term1, tlv2} = case tlv1 do
      [{16, v1} | tempTlv2] ->
        {dec_GeneralNames(v1, []), tempTlv2}
      _ ->
        {:asn1_NOVALUE, tlv1}
    end
    {term2, tlv3} = case tlv2 do
      [{131072, v2} | tempTlv3] ->
        {dec_IssuerSerial(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    {term3, tlv4} = case tlv3 do
      [{131073, v3} | tempTlv4] ->
        {dec_ObjectDigestInfo(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:V2Form, term1, term2, term3}
    res1
  end

  def dec_ValidationParms(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_native_bit_string(v1, [3])
    [v2 | tlv3] = tlv2
    term2 = decode_integer(v2, [2])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:ValidationParms, term1, term2}
    res1
  end

  def dec_Validity(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_Time(v1, [])
    [v2 | tlv3] = tlv2
    term2 = dec_Time(v2, [])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:Validity, term1, term2}
    res1
  end

  def unquote(:"dec_VersionPKCS-1")(tlv, tagIn), do: (val1 = decode_integer(tlv, tagIn); number2name(val1, [{:"two-prime", 0}, {:multi, 1}]))

  def dec_VersionPKIX1Explicit88(tlv, tagIn), do: (val1 = decode_integer(tlv, tagIn); number2name(val1, [{:v1, 0}, {:v2, 1}, {:v3, 2}]))

  def dec_X121Address(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 16 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_X520CommonName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {20, v1} ->
        {:teletexString, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 128 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:printableString, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 128 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {28, v1} ->
        {:universalString, (val3 = decode_universal_string(v1, []); c3 = length(val3); cond do
          1 <= c3 and c3 <= 256 ->
            val3
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      {30, v1} ->
        {:bmpString, (val4 = decode_BMP_string(v1, []); c4 = length(val4); cond do
          1 <= c4 and c4 <= 256 ->
            val4
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_X520LocalityName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {20, v1} ->
        {:teletexString, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 128 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:printableString, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 128 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {28, v1} ->
        {:universalString, (val3 = decode_universal_string(v1, []); c3 = length(val3); cond do
          1 <= c3 and c3 <= 256 ->
            val3
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      {30, v1} ->
        {:bmpString, (val4 = decode_BMP_string(v1, []); c4 = length(val4); cond do
          1 <= c4 and c4 <= 256 ->
            val4
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_X520OrganizationName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {20, v1} ->
        {:teletexString, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 128 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:printableString, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 128 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {28, v1} ->
        {:universalString, (val3 = decode_universal_string(v1, []); c3 = length(val3); cond do
          1 <= c3 and c3 <= 256 ->
            val3
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      {30, v1} ->
        {:bmpString, (val4 = decode_BMP_string(v1, []); c4 = length(val4); cond do
          1 <= c4 and c4 <= 256 ->
            val4
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_X520OrganizationalUnitName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {20, v1} ->
        {:teletexString, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 128 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:printableString, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 128 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {28, v1} ->
        {:universalString, (val3 = decode_universal_string(v1, []); c3 = length(val3); cond do
          1 <= c3 and c3 <= 256 ->
            val3
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      {30, v1} ->
        {:bmpString, (val4 = decode_BMP_string(v1, []); c4 = length(val4); cond do
          1 <= c4 and c4 <= 256 ->
            val4
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_X520Pseudonym(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {20, v1} ->
        {:teletexString, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 128 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:printableString, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 128 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {28, v1} ->
        {:universalString, (val3 = decode_universal_string(v1, []); c3 = length(val3); cond do
          1 <= c3 and c3 <= 256 ->
            val3
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      {30, v1} ->
        {:bmpString, (val4 = decode_BMP_string(v1, []); c4 = length(val4); cond do
          1 <= c4 and c4 <= 256 ->
            val4
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_X520SerialNumber(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 64 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_X520StateOrProvinceName(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {20, v1} ->
        {:teletexString, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 128 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:printableString, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 128 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {28, v1} ->
        {:universalString, (val3 = decode_universal_string(v1, []); c3 = length(val3); cond do
          1 <= c3 and c3 <= 256 ->
            val3
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      {30, v1} ->
        {:bmpString, (val4 = decode_BMP_string(v1, []); c4 = length(val4); cond do
          1 <= c4 and c4 <= 256 ->
            val4
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_X520Title(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {20, v1} ->
        {:teletexString, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 128 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:printableString, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 128 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {28, v1} ->
        {:universalString, (val3 = decode_universal_string(v1, []); c3 = length(val3); cond do
          1 <= c3 and c3 <= 256 ->
            val3
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      {30, v1} ->
        {:bmpString, (val4 = decode_BMP_string(v1, []); c4 = length(val4); cond do
          1 <= c4 and c4 <= 256 ->
            val4
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_X520countryName(tlv, tagIn) do
    (val1 = decode_restricted_string(tlv, tagIn); c1 = byte_size(val1); cond do
      c1 === 2 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
  end

  def dec_X520dnQualifier(tlv, tagIn), do: (binary_to_list(decode_restricted_string(tlv, tagIn)))

  def dec_X520name(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {20, v1} ->
        {:teletexString, (val1 = decode_restricted_string(v1, []); c1 = byte_size(val1); cond do
          1 <= c1 and c1 <= 65536 ->
            binary_to_list(val1)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {19, v1} ->
        {:printableString, (val2 = decode_restricted_string(v1, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 65536 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {28, v1} ->
        {:universalString, (val3 = decode_universal_string(v1, []); c3 = length(val3); cond do
          1 <= c3 and c3 <= 131072 ->
            val3
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      {12, v1} ->
        {:utf8String, decode_UTF8_string(v1, [])}
      {30, v1} ->
        {:bmpString, (val4 = decode_BMP_string(v1, []); c4 = length(val4); cond do
          1 <= c4 and c4 <= 131072 ->
            val4
          true ->
            exit({:error, {:asn1, :bad_range}})
        end)}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  def dec_authorityInfoAccess(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_AuthorityInfoAccessSyntax(tlv, [16])
  end

  def dec_authorityKeyIdentifier(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_AuthorityKeyIdentifier(tlv, [16])
  end

  def dec_basicConstraints(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_BasicConstraints(tlv, [16])
  end

  def dec_cRLDistributionPoints(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_CRLDistributionPoints(tlv, [16])
  end

  def dec_cRLNumber(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_CRLNumber(tlv, [2])
  end

  def dec_cRLReasons(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_CRLReason(tlv, [10])
  end

  def dec_certificateIssuer(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_CertificateIssuer(tlv, [16])
  end

  def dec_certificatePolicies(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_CertificatePolicies(tlv, [16])
  end

  def dec_challengePassword(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_challengePassword(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_DirectoryString(tlv, [])
  end

  def dec_challengePassword(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_challengePassword(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_challengePassword(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def dec_commonName(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520CommonName(tlv, [])
  end

  def dec_contentType(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_contentType(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_ContentType(tlv, [6])
  end

  def dec_contentType(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_contentType(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_contentType(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def dec_counterSignature(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_counterSignature(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_SignerInfo(tlv, [16])
  end

  def dec_counterSignature(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_counterSignature(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_counterSignature(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def dec_countryName(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520countryName(tlv, [19])
  end

  def dec_deltaCRLIndicator(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_BaseCRLNumber(tlv, [2])
  end

  def dec_dh(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_DomainParameters(tlv, [16])
  end

  def dec_dh(:PublicKeyType, bytes, _) do
    tlv = tlv_format(bytes)
    dec_DHPublicKey(tlv, [2])
  end

  def dec_dnQualifier(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520dnQualifier(tlv, [19])
  end

  def dec_domainComponent(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_DomainComponent(tlv, [22])
  end

  def dec_dsa(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_DSAParams(tlv, [])
  end

  def dec_dsa(:PublicKeyType, bytes, _) do
    tlv = tlv_format(bytes)
    dec_DSAPublicKey(tlv, [2])
  end

  def unquote(:"dec_dsa-with-sha1")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_DSAParams(tlv, [])
  end

  def dec_dsaWithSHA1(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_DSAParams(tlv, [])
  end

  def unquote(:"dec_ec-public-key")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_EcpkParameters(tlv, [])
  end

  def unquote(:"dec_ec-public-key")(:PublicKeyType, bytes, _) do
    tlv = tlv_format(bytes)
    dec_ECPoint(tlv, [4])
  end

  def unquote(:"dec_ecdsa-with-sha1")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_EcpkParameters(tlv, [])
  end

  def unquote(:"dec_ecdsa-with-sha224")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_EcpkParameters(tlv, [])
  end

  def unquote(:"dec_ecdsa-with-sha256")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_EcpkParameters(tlv, [])
  end

  def unquote(:"dec_ecdsa-with-sha384")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_EcpkParameters(tlv, [])
  end

  def unquote(:"dec_ecdsa-with-sha512")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_EcpkParameters(tlv, [])
  end

  def dec_emailAddress(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_EmailAddress(tlv, [22])
  end

  def dec_extKeyUsage(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_ExtKeyUsageSyntax(tlv, [16])
  end

  def dec_extensionRequest(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_extensionRequest(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_ExtensionRequest(tlv, [16])
  end

  def dec_extensionRequest(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_extensionRequest(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_extensionRequest(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def dec_failInfo(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_failInfo(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    (binary_to_list(decode_restricted_string(tlv, [19])))
  end

  def dec_failInfo(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_failInfo(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_failInfo(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def unquote(:"dec_field-characteristic-two")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    apply(__MODULE__, :"dec_Characteristic-two", [tlv, [16]])
  end

  def unquote(:"dec_field-prime-field")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    apply(__MODULE__, :"dec_Prime-p", [tlv, [2]])
  end

  def dec_freshestCRL(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_FreshestCRL(tlv, [16])
  end

  def dec_generationQualifier(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520name(tlv, [])
  end

  def dec_givenName(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520name(tlv, [])
  end

  def unquote(:"dec_gn-basis")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_null(tlv, [5])
  end

  def dec_holdInstructionCode(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_HoldInstructionCode(tlv, [6])
  end

  def dec_inhibitAnyPolicy(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_InhibitAnyPolicy(tlv, [2])
  end

  def dec_initials(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520name(tlv, [])
  end

  def dec_invalidityDate(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_InvalidityDate(tlv, [24])
  end

  def dec_issuerAltName(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_IssuerAltName(tlv, [16])
  end

  def dec_issuingDistributionPoint(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_IssuingDistributionPoint(tlv, [16])
  end

  def dec_kea(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    apply(__MODULE__, :"dec_KEA-Parms-Id", [tlv, [4]])
  end

  def dec_kea(:PublicKeyType, bytes, _) do
    tlv = tlv_format(bytes)
    apply(__MODULE__, :"dec_KEA-PublicKey", [tlv, [2]])
  end

  def dec_keyUsage(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_KeyUsage(tlv, [3])
  end

  def dec_localityName(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520LocalityName(tlv, [])
  end

  def unquote(:"dec_md2-with-rsa-encryption")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_null(tlv, [5])
  end

  def unquote(:"dec_md5-with-rsa-encryption")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_null(tlv, [5])
  end

  def dec_messageDigest(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_messageDigest(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_MessageDigest(tlv, [4])
  end

  def dec_messageDigest(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_messageDigest(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_messageDigest(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def dec_messageType(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_messageType(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    (binary_to_list(decode_restricted_string(tlv, [19])))
  end

  def dec_messageType(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_messageType(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_messageType(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def dec_name(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520name(tlv, [])
  end

  def dec_nameConstraints(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_NameConstraints(tlv, [16])
  end

  def dec_organizationName(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520OrganizationName(tlv, [])
  end

  def dec_organizationalUnitName(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520OrganizationalUnitName(tlv, [])
  end

  def dec_pkiStatus(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_pkiStatus(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    (binary_to_list(decode_restricted_string(tlv, [19])))
  end

  def dec_pkiStatus(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_pkiStatus(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_pkiStatus(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def dec_policyConstraints(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PolicyConstraints(tlv, [16])
  end

  def dec_policyMappings(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PolicyMappings(tlv, [16])
  end

  def unquote(:"dec_pp-basis")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_Pentanomial(tlv, [16])
  end

  def dec_privateKeyUsagePeriod(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PrivateKeyUsagePeriod(tlv, [16])
  end

  def dec_pseudonym(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520Pseudonym(tlv, [])
  end

  def dec_recipientNonce(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_recipientNonce(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_octet_string(tlv, [4])
  end

  def dec_recipientNonce(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_recipientNonce(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_recipientNonce(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def unquote(:"dec_rsa-encryption")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_null(tlv, [5])
  end

  def unquote(:"dec_rsa-encryption")(:PublicKeyType, bytes, _) do
    tlv = tlv_format(bytes)
    dec_RSAPublicKey(tlv, [16])
  end

  def dec_senderNonce(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_senderNonce(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_octet_string(tlv, [4])
  end

  def dec_senderNonce(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_senderNonce(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_senderNonce(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def dec_serialNumber(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520SerialNumber(tlv, [19])
  end

  def unquote(:"dec_sha-1with-rsa-encryption")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_null(tlv, [5])
  end

  def unquote(:"dec_sha1-with-rsa-encryption")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_null(tlv, [5])
  end

  def unquote(:"dec_sha224-with-rsa-encryption")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_null(tlv, [5])
  end

  def unquote(:"dec_sha256-with-rsa-encryption")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_null(tlv, [5])
  end

  def unquote(:"dec_sha384-with-rsa-encryption")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_null(tlv, [5])
  end

  def unquote(:"dec_sha512-with-rsa-encryption")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    decode_null(tlv, [5])
  end

  def dec_signingTime(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_signingTime(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_SigningTime(tlv, [])
  end

  def dec_signingTime(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_signingTime(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_signingTime(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def dec_stateOrProvinceName(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520StateOrProvinceName(tlv, [])
  end

  def dec_subjectAltName(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_SubjectAltName(tlv, [16])
  end

  def dec_subjectDirectoryAttributes(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_SubjectDirectoryAttributes(tlv, [16])
  end

  def dec_subjectInfoAccess(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_SubjectInfoAccessSyntax(tlv, [16])
  end

  def dec_subjectKeyIdentifier(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_SubjectKeyIdentifier(tlv, [4])
  end

  def dec_surname(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520name(tlv, [])
  end

  def dec_title(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_X520Title(tlv, [])
  end

  def unquote(:"dec_tp-basis")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_Trinomial(tlv, [2])
  end

  def dec_transactionID(:derivation, _, _), do: exit({:error, {:"illegal use of missing field in object", :derivation}})

  def dec_transactionID(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    (binary_to_list(decode_restricted_string(tlv, [19])))
  end

  def dec_transactionID(:"equality-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"equality-match"}})

  def dec_transactionID(:"ordering-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"ordering-match"}})

  def dec_transactionID(:"substrings-match", _, _), do: exit({:error, {:"illegal use of missing field in object", :"substrings-match"}})

  def unquote(:"dec_x400-common-name")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_CommonName(tlv, [19])
  end

  def unquote(:"dec_x400-extended-network-address")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_ExtendedNetworkAddress(tlv, [])
  end

  def unquote(:"dec_x400-extension-OR-address-components")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_ExtensionORAddressComponents(tlv, [17])
  end

  def unquote(:"dec_x400-extension-physical-delivery-address-components")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_ExtensionPhysicalDeliveryAddressComponents(tlv, [17])
  end

  def unquote(:"dec_x400-local-postal-attributes")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_LocalPostalAttributes(tlv, [17])
  end

  def unquote(:"dec_x400-pds-name")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PDSName(tlv, [19])
  end

  def unquote(:"dec_x400-physical-delivery-country-name")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PhysicalDeliveryCountryName(tlv, [])
  end

  def unquote(:"dec_x400-physical-delivery-office-name")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PhysicalDeliveryOfficeName(tlv, [17])
  end

  def unquote(:"dec_x400-physical-delivery-office-number")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PhysicalDeliveryOfficeNumber(tlv, [17])
  end

  def unquote(:"dec_x400-physical-delivery-organization-name")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PhysicalDeliveryOrganizationName(tlv, [17])
  end

  def unquote(:"dec_x400-physical-delivery-personal-name")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PhysicalDeliveryPersonalName(tlv, [17])
  end

  def unquote(:"dec_x400-post-office-box-address")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PostOfficeBoxAddress(tlv, [17])
  end

  def unquote(:"dec_x400-postal-code")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PostalCode(tlv, [])
  end

  def unquote(:"dec_x400-poste-restante-address")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_PosteRestanteAddress(tlv, [17])
  end

  def unquote(:"dec_x400-street-address")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_StreetAddress(tlv, [17])
  end

  def unquote(:"dec_x400-teletex-common-name")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_TeletexCommonName(tlv, [20])
  end

  def unquote(:"dec_x400-teletex-domain-defined-attributes")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_TeletexDomainDefinedAttributes(tlv, [16])
  end

  def unquote(:"dec_x400-teletex-personal-name")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_TeletexPersonalName(tlv, [17])
  end

  def unquote(:"dec_x400-terminal-type")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_TerminalType(tlv, [2])
  end

  def unquote(:"dec_x400-unformatted-postal-address")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_UnformattedPostalAddress(tlv, [17])
  end

  def unquote(:"dec_x400-unique-postal-name")(:Type, bytes, _) do
    tlv = tlv_format(bytes)
    dec_UniquePostalName(tlv, [17])
  end

  def decode(type, data) do
    try do
      decode_disp(type, element(1, ber_decode_nif(data)))
    catch
      {class, exception, _} when class === :error or class === :exit ->
        stk = :erlang.get_stacktrace()
        case exception do
          {:error, {:asn1, reason}} ->
            {:error, {:asn1, {reason, stk}}}
          reason ->
            {:error, {:asn1, {reason, stk}}}
        end
    else
      result ->
        {:ok, result}
    end
  end

  def decode_TBSCertList_exclusive(bytes), do: decode_partial_incomplete(:CertificateList, bytes, [:mandatory, [[:undec, 16]]])

  def decode_TBSCert_exclusive(bytes), do: decode_partial_incomplete(:Certificate, bytes, [:mandatory, [[:undec, 16]]])

  def decode_part(type, data0) when is_binary(data0) do
    try do
      decode_inc_disp(type, element(1, ber_decode_nif(data0)))
    catch
      error -> error
    end
    |> case do
      {:EXIT, {:error, reason}} ->
        {:error, reason}
      {:EXIT, reason} ->
        {:error, {:asn1, reason}}
      result ->
        {:ok, result}
    end
  end

  def decode_part(type, data0) do
    try do
      decode_inc_disp(type, data0)
    catch
      error -> error
    end
    |> case do
      {:EXIT, {:error, reason}} ->
        {:error, reason}
      {:EXIT, reason} ->
        {:error, {:asn1, reason}}
      result ->
        {:ok, result}
    end
  end

  def dhKeyAgreement(), do: {1, 2, 840, 113549, 1, 3, 1}

  def dhpublicnumber(), do: {1, 2, 840, 10046, 2, 1}

  def unquote(:"dialyzer-suppressions")(arg), do: :ok

  def digestedData(), do: {1, 2, 840, 113549, 1, 7, 5}

  def ecStdCurvesAndGeneration(), do: {1, 3, 36, 3, 3, 2, 8}

  def unquote(:"ecdsa-with-SHA1")(), do: {1, 2, 840, 10045, 4, 1}

  def unquote(:"ecdsa-with-SHA2")(), do: {1, 2, 840, 10045, 4, 3}

  def unquote(:"ecdsa-with-SHA224")(), do: {1, 2, 840, 10045, 4, 3, 1}

  def unquote(:"ecdsa-with-SHA256")(), do: {1, 2, 840, 10045, 4, 3, 2}

  def unquote(:"ecdsa-with-SHA384")(), do: {1, 2, 840, 10045, 4, 3, 3}

  def unquote(:"ecdsa-with-SHA512")(), do: {1, 2, 840, 10045, 4, 3, 4}

  def ellipticCurve(), do: {1, 3, 132, 0}

  def ellipticCurveRFC5639(), do: {1, 3, 36, 3, 3, 2, 8, 1}

  def enc_AAControls(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_integer(cindex1, [<<2>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_AttrSpec(cindex2, [<<160>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_AttrSpec(cindex3, [<<161>>])
    end
    {encBytes4, encLen4} = case is_default_3(cindex4) do
      true ->
        {[], 0}
      false ->
        encode_boolean(cindex4, [<<1>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ACClearAttrs(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = enc_GeneralName(cindex1, [])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    {encBytes3, encLen3} = enc_ACClearAttrs_attrs(cindex3, [<<48>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_AccessDescription(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = enc_GeneralName(cindex2, [])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_AdministrationDomainName(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :numeric ->
        encode_restricted_string(element(2, val), [<<18>>])
      :printable ->
        encode_restricted_string(element(2, val), [<<19>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_Algorithm(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_open_type(cindex2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_AlgorithmIdentifier(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_open_type(cindex2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_AlgorithmNull(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = encode_null(cindex2, [<<5>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_AnotherName(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = encode_open_type(cindex2, [<<160>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_Any(val, tagIn), do: encode_open_type(val, tagIn)

  def enc_AttCertIssuer(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :v1Form ->
        enc_GeneralNames(element(2, val), [<<48>>])
      :v2Form ->
        enc_V2Form(element(2, val), [<<160>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_AttCertValidityPeriod(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_restricted_string(cindex1, [<<24>>])
    {encBytes2, encLen2} = encode_restricted_string(cindex2, [<<24>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_AttCertVersion(val, tagIn), do: encode_integer(val, [{:v2, 1}], tagIn)

  def enc_AttrSpec(val, tagIn) do
    {encBytes, encLen} = enc_AttrSpec_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_Attribute(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = enc_Attribute_values(cindex2, [<<49>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_AttributeCertificate(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = enc_AttributeCertificateInfo(cindex1, [<<48>>])
    {encBytes2, encLen2} = enc_AlgorithmIdentifier(cindex2, [<<48>>])
    {encBytes3, encLen3} = encode_unnamed_bit_string(cindex3, [<<3>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_AttributeCertificateInfo(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6, cindex7, cindex8, cindex9} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [{:v2, 1}], [<<2>>])
    {encBytes2, encLen2} = enc_Holder(cindex2, [<<48>>])
    {encBytes3, encLen3} = enc_AttCertIssuer(cindex3, [])
    {encBytes4, encLen4} = enc_AlgorithmIdentifier(cindex4, [<<48>>])
    {encBytes5, encLen5} = encode_integer(cindex5, [<<2>>])
    {encBytes6, encLen6} = enc_AttCertValidityPeriod(cindex6, [<<48>>])
    {encBytes7, encLen7} = enc_AttributeCertificateInfo_attributes(cindex7, [<<48>>])
    {encBytes8, encLen8} = case cindex8 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_unnamed_bit_string(cindex8, [<<3>>])
    end
    {encBytes9, encLen9} = case cindex9 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_Extensions(cindex9, [<<48>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6, encBytes7, encBytes8, encBytes9]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6 + encLen7 + encLen8 + encLen9
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_AttributeType(val, tagIn), do: encode_object_identifier(val, tagIn)

  def enc_AttributeTypeAndValue(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = enc_AttributeValue(cindex2, [])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_AttributeValue(val, tagIn), do: encode_open_type(val, tagIn)

  def enc_AuthorityInfoAccessSyntax(val, tagIn) do
    {encBytes, encLen} = enc_AuthorityInfoAccessSyntax_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_AuthorityKeyIdentifier(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex1, [<<128>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_GeneralNames(cindex2, [<<161>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_integer(cindex3, [<<130>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_BaseCRLNumber(val, tagIn), do: encode_integer(val, tagIn)

  def enc_BaseDistance(val, tagIn), do: encode_integer(val, tagIn)

  def enc_BasicConstraints(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = case is_default_10(cindex1) do
      true ->
        {[], 0}
      false ->
        encode_boolean(cindex1, [<<1>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_integer(cindex2, [<<2>>])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_Boolean(val, tagIn), do: encode_boolean(val, tagIn)

  def enc_BuiltInDomainDefinedAttribute(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_restricted_string(cindex1, [<<19>>])
    {encBytes2, encLen2} = encode_restricted_string(cindex2, [<<19>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_BuiltInDomainDefinedAttributes(val, tagIn) do
    {encBytes, encLen} = enc_BuiltInDomainDefinedAttributes_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_BuiltInStandardAttributes(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6, cindex7, cindex8, cindex9} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_CountryName(cindex1, [<<97>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_AdministrationDomainName(cindex2, [<<98>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex3, [<<128>>])
    end
    {encBytes4, encLen4} = case cindex4 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex4, [<<129>>])
    end
    {encBytes5, encLen5} = case cindex5 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_PrivateDomainName(cindex5, [<<162>>])
    end
    {encBytes6, encLen6} = case cindex6 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex6, [<<131>>])
    end
    {encBytes7, encLen7} = case cindex7 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex7, [<<132>>])
    end
    {encBytes8, encLen8} = case cindex8 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_PersonalName(cindex8, [<<165>>])
    end
    {encBytes9, encLen9} = case cindex9 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_OrganizationalUnitNames(cindex9, [<<166>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6, encBytes7, encBytes8, encBytes9]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6 + encLen7 + encLen8 + encLen9
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_CPSuri(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_CRLDistributionPoints(val, tagIn) do
    {encBytes, encLen} = enc_CRLDistributionPoints_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_CRLNumber(val, tagIn), do: encode_integer(val, tagIn)

  def enc_CRLReason(val, tagIn) do
    case val do
      :unspecified ->
        encode_tags(tagIn, [0], 1)
      :keyCompromise ->
        encode_tags(tagIn, [1], 1)
      :cACompromise ->
        encode_tags(tagIn, [2], 1)
      :affiliationChanged ->
        encode_tags(tagIn, [3], 1)
      :superseded ->
        encode_tags(tagIn, [4], 1)
      :cessationOfOperation ->
        encode_tags(tagIn, [5], 1)
      :certificateHold ->
        encode_tags(tagIn, [6], 1)
      :removeFromCRL ->
        encode_tags(tagIn, [8], 1)
      :privilegeWithdrawn ->
        encode_tags(tagIn, [9], 1)
      :aACompromise ->
        encode_tags(tagIn, [10], 1)
      enumval1 ->
        exit({:error, {:asn1, {:enumerated_not_in_range, enumval1}}})
    end
  end

  def enc_CRLSequence(val, tagIn) do
    {encBytes, encLen} = enc_CRLSequence_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_CertPolicyId(val, tagIn), do: encode_object_identifier(val, tagIn)

  def enc_Certificate(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = enc_TBSCertificate(cindex1, [<<48>>])
    {encBytes2, encLen2} = enc_AlgorithmIdentifier(cindex2, [<<48>>])
    {encBytes3, encLen3} = encode_unnamed_bit_string(cindex3, [<<3>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_CertificateIssuer(val, tagIn), do: enc_GeneralNames(val, tagIn)

  def enc_CertificateList(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = enc_TBSCertList(cindex1, [<<48>>])
    {encBytes2, encLen2} = enc_AlgorithmIdentifier(cindex2, [<<48>>])
    {encBytes3, encLen3} = encode_unnamed_bit_string(cindex3, [<<3>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_CertificatePolicies(val, tagIn) do
    {encBytes, encLen} = enc_CertificatePolicies_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_CertificateRevocationLists(val, tagIn) do
    {encBytes, encLen} = enc_CertificateRevocationLists_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_CertificateSerialNumber(val, tagIn), do: encode_integer(val, tagIn)

  def enc_Certificates(val, tagIn) do
    {encBytes, encLen} = enc_Certificates_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_CertificationRequest(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = enc_CertificationRequestInfo(cindex1, [<<48>>])
    {encBytes2, encLen2} = enc_CertificationRequest_signatureAlgorithm(cindex2, [<<48>>])
    {encBytes3, encLen3} = encode_unnamed_bit_string(cindex3, [<<3>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_CertificationRequestInfo(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [{:v1, 0}], [<<2>>])
    {encBytes2, encLen2} = enc_Name(cindex2, [])
    {encBytes3, encLen3} = enc_CertificationRequestInfo_subjectPKInfo(cindex3, [<<48>>])
    {encBytes4, encLen4} = enc_CertificationRequestInfo_attributes(cindex4, [<<160>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_Characteristic-two")(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_object_identifier(cindex2, [<<6>>])
    {encBytes3, encLen3} = encode_open_type(cindex3, [])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ClassList(val, tagIn), do: encode_named_bit_string(val, [{:unmarked, 0}, {:unclassified, 1}, {:restricted, 2}, {:confidential, 3}, {:secret, 4}, {:topSecret, 5}], tagIn)

  def enc_Clearance(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<128>>])
    {encBytes2, encLen2} = case is_default_4(cindex2) do
      true ->
        {[], 0}
      false ->
        encode_named_bit_string(cindex2, [{:unmarked, 0}, {:unclassified, 1}, {:restricted, 2}, {:confidential, 3}, {:secret, 4}, {:topSecret, 5}], [<<129>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_Clearance_securityCategories(cindex3, [<<162>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_CommonName(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_ContentEncryptionAlgorithmIdentifier(val, tagIn) do
    {_, cindex1, cindex2} = val
    objalgorithm = :"OTP-PUB-KEY".getenc_internal_object_set_argument_1(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        {tmpBytes2, _} = objalgorithm.(:Type, cindex2, [])
        encode_open_type(tmpBytes2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ContentInfo(val, tagIn) do
    {_, cindex1, cindex2} = val
    objcontentType = :"OTP-PUB-KEY".getenc_Contents(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        {tmpBytes2, _} = objcontentType.(:Type, cindex2, [])
        encode_open_type(tmpBytes2, [<<160>>])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ContentType(val, tagIn), do: encode_object_identifier(val, tagIn)

  def enc_CountryName(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :"x121-dcc-code" ->
        encode_restricted_string(element(2, val), [<<18>>])
      :"iso-3166-alpha2-code" ->
        encode_restricted_string(element(2, val), [<<19>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_Curve(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_restricted_string(cindex1, [<<4>>])
    {encBytes2, encLen2} = encode_restricted_string(cindex2, [<<4>>])
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_unnamed_bit_string(cindex3, [<<3>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_DHParameter(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_integer(cindex3, [<<2>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_DHPublicKey(val, tagIn), do: encode_integer(val, tagIn)

  def enc_DSAParams(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :params ->
        apply(__MODULE__, :"enc_Dss-Parms", [element(2, val), [<<48>>]])
      :null ->
        encode_null(element(2, val), [<<5>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_DSAPrivateKey(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    {encBytes3, encLen3} = encode_integer(cindex3, [<<2>>])
    {encBytes4, encLen4} = encode_integer(cindex4, [<<2>>])
    {encBytes5, encLen5} = encode_integer(cindex5, [<<2>>])
    {encBytes6, encLen6} = encode_integer(cindex6, [<<2>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_DSAPublicKey(val, tagIn), do: encode_integer(val, tagIn)

  def enc_Data(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_Digest(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_DigestAlgorithmIdentifier(val, tagIn) do
    {_, cindex1, cindex2} = val
    objalgorithm = :"OTP-PUB-KEY".getenc_internal_object_set_argument_2(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        {tmpBytes2, _} = objalgorithm.(:Type, cindex2, [])
        encode_open_type(tmpBytes2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_DigestAlgorithmIdentifiers(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :daSet ->
        enc_DigestAlgorithmIdentifiers_daSet(element(2, val), [<<49>>])
      :daSequence ->
        enc_DigestAlgorithmIdentifiers_daSequence(element(2, val), [<<48>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_DigestEncryptionAlgorithmIdentifier(val, tagIn) do
    {_, cindex1, cindex2} = val
    objalgorithm = :"OTP-PUB-KEY".getenc_internal_object_set_argument_6(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        {tmpBytes2, _} = objalgorithm.(:Type, cindex2, [])
        encode_open_type(tmpBytes2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_DigestInfoNull(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = enc_AlgorithmNull(cindex1, [<<48>>])
    {encBytes2, encLen2} = encode_restricted_string(cindex2, [<<4>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_DigestInfoPKCS-1")(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = enc_Algorithm(cindex1, [<<48>>])
    {encBytes2, encLen2} = encode_restricted_string(cindex2, [<<4>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_DigestInfoPKCS-7")(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = enc_DigestAlgorithmIdentifier(cindex1, [<<48>>])
    {encBytes2, encLen2} = encode_restricted_string(cindex2, [<<4>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_DigestedData(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [{:ddVer0, 0}], [<<2>>])
    {encBytes2, encLen2} = enc_DigestAlgorithmIdentifier(cindex2, [<<48>>])
    {encBytes3, encLen3} = enc_ContentInfo(cindex3, [<<48>>])
    {encBytes4, encLen4} = encode_restricted_string(cindex4, [<<4>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_DirectoryString(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :teletexString ->
        encode_restricted_string(element(2, val), [<<20>>])
      :printableString ->
        encode_restricted_string(element(2, val), [<<19>>])
      :universalString ->
        encode_universal_string(element(2, val), [<<28>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      :bmpString ->
        encode_BMP_string(element(2, val), [<<30>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_DisplayText(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :ia5String ->
        encode_restricted_string(element(2, val), [<<22>>])
      :visibleString ->
        encode_restricted_string(element(2, val), [<<26>>])
      :bmpString ->
        encode_BMP_string(element(2, val), [<<30>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_DistinguishedName(val, tagIn), do: enc_RDNSequence(val, tagIn)

  def enc_DistributionPoint(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_DistributionPointName(cindex1, [<<160>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_named_bit_string(cindex2, [{:unused, 0}, {:keyCompromise, 1}, {:cACompromise, 2}, {:affiliationChanged, 3}, {:superseded, 4}, {:cessationOfOperation, 5}, {:certificateHold, 6}, {:privilegeWithdrawn, 7}, {:aACompromise, 8}], [<<129>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_GeneralNames(cindex3, [<<162>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_DistributionPointName(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :fullName ->
        enc_GeneralNames(element(2, val), [<<160>>])
      :nameRelativeToCRLIssuer ->
        enc_RelativeDistinguishedName(element(2, val), [<<161>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_DomainComponent(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_DomainParameters(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    {encBytes3, encLen3} = encode_integer(cindex3, [<<2>>])
    {encBytes4, encLen4} = case cindex4 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_integer(cindex4, [<<2>>])
    end
    {encBytes5, encLen5} = case cindex5 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_ValidationParms(cindex5, [<<48>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_Dss-Parms")(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    {encBytes3, encLen3} = encode_integer(cindex3, [<<2>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_Dss-Sig-Value")(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_ECDSA-Sig-Value")(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ECPVer(val, tagIn), do: encode_integer(val, [{:ecpVer1, 1}], tagIn)

  def enc_ECParameters(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [{:ecpVer1, 1}], [<<2>>])
    {encBytes2, encLen2} = enc_FieldID(cindex2, [<<48>>])
    {encBytes3, encLen3} = enc_Curve(cindex3, [<<48>>])
    {encBytes4, encLen4} = encode_restricted_string(cindex4, [<<4>>])
    {encBytes5, encLen5} = encode_integer(cindex5, [<<2>>])
    {encBytes6, encLen6} = case cindex6 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_integer(cindex6, [<<2>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ECPoint(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_ECPrivateKey(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_restricted_string(cindex2, [<<4>>])
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_EcpkParameters(cindex3, [<<160>>])
    end
    {encBytes4, encLen4} = case cindex4 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_unnamed_bit_string(cindex4, [<<3>>, <<161>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_EDIPartyName(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_DirectoryString(cindex1, [<<160>>])
    end
    {encBytes2, encLen2} = enc_DirectoryString(cindex2, [<<161>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_EcpkParameters(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :ecParameters ->
        enc_ECParameters(element(2, val), [<<48>>])
      :namedCurve ->
        encode_object_identifier(element(2, val), [<<6>>])
      :implicitlyCA ->
        encode_null(element(2, val), [<<5>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_EmailAddress(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_EncryptedContent(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_EncryptedContentInfo(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = enc_ContentEncryptionAlgorithmIdentifier(cindex2, [<<48>>])
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex3, [<<128>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_EncryptedData(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [{:edVer0, 0}], [<<2>>])
    {encBytes2, encLen2} = enc_EncryptedContentInfo(cindex2, [<<48>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_EncryptedDigest(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_EncryptedKey(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_EnvelopedData(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [{:edVer0, 0}, {:edVer1, 1}], [<<2>>])
    {encBytes2, encLen2} = enc_RecipientInfos(cindex2, [])
    {encBytes3, encLen3} = enc_EncryptedContentInfo(cindex3, [<<48>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ExtKeyUsageSyntax(val, tagIn) do
    {encBytes, encLen} = enc_ExtKeyUsageSyntax_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_ExtendedCertificate(val, tagIn), do: enc_Certificate(val, tagIn)

  def enc_ExtendedCertificateOrCertificate(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :certificate ->
        enc_Certificate(element(2, val), [<<48>>])
      :extendedCertificate ->
        enc_ExtendedCertificate(element(2, val), [<<160>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_ExtendedCertificatesAndCertificates(val, tagIn) do
    {encBytes, encLen} = enc_ExtendedCertificatesAndCertificates_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_ExtendedNetworkAddress(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :"e163-4-address" ->
        apply(__MODULE__, :"enc_ExtendedNetworkAddress_e163-4-address", [element(2, val), [<<48>>]])
      :"psap-address" ->
        enc_PresentationAddress(element(2, val), [<<160>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_Extension(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case is_default_10(cindex2) do
      true ->
        {[], 0}
      false ->
        encode_boolean(cindex2, [<<1>>])
    end
    {encBytes3, encLen3} = encode_restricted_string(cindex3, [<<4>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_Extension-Any")(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case is_default_10(cindex2) do
      true ->
        {[], 0}
      false ->
        encode_boolean(cindex2, [<<1>>])
    end
    {encBytes3, encLen3} = encode_open_type(cindex3, [])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ExtensionAttribute(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<128>>])
    {encBytes2, encLen2} = encode_open_type(cindex2, [<<161>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ExtensionAttributes(val, tagIn) do
    {encBytes, encLen} = enc_ExtensionAttributes_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_ExtensionORAddressComponents(val, tagIn), do: enc_PDSParameter(val, tagIn)

  def enc_ExtensionPhysicalDeliveryAddressComponents(val, tagIn), do: enc_PDSParameter(val, tagIn)

  def enc_ExtensionRequest(val, tagIn), do: enc_Extensions(val, tagIn)

  def enc_Extensions(val, tagIn) do
    {encBytes, encLen} = enc_Extensions_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_FieldElement(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_FieldID(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = encode_open_type(cindex2, [])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_FreshestCRL(val, tagIn), do: enc_CRLDistributionPoints(val, tagIn)

  def enc_GeneralName(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :otherName ->
        enc_AnotherName(element(2, val), [<<160>>])
      :rfc822Name ->
        encode_restricted_string(element(2, val), [<<129>>])
      :dNSName ->
        encode_restricted_string(element(2, val), [<<130>>])
      :x400Address ->
        enc_ORAddress(element(2, val), [<<163>>])
      :directoryName ->
        enc_Name(element(2, val), [<<164>>])
      :ediPartyName ->
        enc_EDIPartyName(element(2, val), [<<165>>])
      :uniformResourceIdentifier ->
        encode_restricted_string(element(2, val), [<<134>>])
      :iPAddress ->
        encode_restricted_string(element(2, val), [<<135>>])
      :registeredID ->
        encode_object_identifier(element(2, val), [<<136>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_GeneralNames(val, tagIn) do
    {encBytes, encLen} = enc_GeneralNames_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_GeneralSubtree(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = enc_GeneralName(cindex1, [])
    {encBytes2, encLen2} = case is_default_9(cindex2) do
      true ->
        {[], 0}
      false ->
        encode_integer(cindex2, [<<128>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_integer(cindex3, [<<129>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_GeneralSubtrees(val, tagIn) do
    {encBytes, encLen} = enc_GeneralSubtrees_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_HoldInstructionCode(val, tagIn), do: encode_object_identifier(val, tagIn)

  def enc_Holder(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_IssuerSerial(cindex1, [<<160>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_GeneralNames(cindex2, [<<161>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_ObjectDigestInfo(cindex3, [<<162>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_IetfAttrSyntax(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_GeneralNames(cindex1, [<<160>>])
    end
    {encBytes2, encLen2} = enc_IetfAttrSyntax_values(cindex2, [<<48>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_InhibitAnyPolicy(val, tagIn), do: encode_integer(val, tagIn)

  def enc_InvalidityDate(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_IssuerAltName(val, tagIn), do: enc_GeneralNames(val, tagIn)

  def enc_IssuerAndSerialNumber(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = enc_Name(cindex1, [])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_IssuerSerial(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = enc_GeneralNames(cindex1, [<<48>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_unnamed_bit_string(cindex3, [<<3>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_IssuingDistributionPoint(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_DistributionPointName(cindex1, [<<160>>])
    end
    {encBytes2, encLen2} = case is_default_5(cindex2) do
      true ->
        {[], 0}
      false ->
        encode_boolean(cindex2, [<<129>>])
    end
    {encBytes3, encLen3} = case is_default_6(cindex3) do
      true ->
        {[], 0}
      false ->
        encode_boolean(cindex3, [<<130>>])
    end
    {encBytes4, encLen4} = case cindex4 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_named_bit_string(cindex4, [{:unused, 0}, {:keyCompromise, 1}, {:cACompromise, 2}, {:affiliationChanged, 3}, {:superseded, 4}, {:cessationOfOperation, 5}, {:certificateHold, 6}, {:privilegeWithdrawn, 7}, {:aACompromise, 8}], [<<131>>])
    end
    {encBytes5, encLen5} = case is_default_7(cindex5) do
      true ->
        {[], 0}
      false ->
        encode_boolean(cindex5, [<<132>>])
    end
    {encBytes6, encLen6} = case is_default_8(cindex6) do
      true ->
        {[], 0}
      false ->
        encode_boolean(cindex6, [<<133>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_KEA-Parms-Id")(val, tagIn), do: encode_restricted_string(val, tagIn)

  def unquote(:"enc_KEA-PublicKey")(val, tagIn), do: encode_integer(val, tagIn)

  def enc_KeyEncryptionAlgorithmIdentifier(val, tagIn) do
    {_, cindex1, cindex2} = val
    objalgorithm = :"OTP-PUB-KEY".getenc_internal_object_set_argument_3(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        {tmpBytes2, _} = objalgorithm.(:Type, cindex2, [])
        encode_open_type(tmpBytes2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_KeyIdentifier(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_KeyPurposeId(val, tagIn), do: encode_object_identifier(val, tagIn)

  def enc_KeyUsage(val, tagIn), do: encode_named_bit_string(val, [{:digitalSignature, 0}, {:nonRepudiation, 1}, {:keyEncipherment, 2}, {:dataEncipherment, 3}, {:keyAgreement, 4}, {:keyCertSign, 5}, {:cRLSign, 6}, {:encipherOnly, 7}, {:decipherOnly, 8}], tagIn)

  def enc_LocalPostalAttributes(val, tagIn), do: enc_PDSParameter(val, tagIn)

  def enc_MessageDigest(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_Name(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :rdnSequence ->
        enc_RDNSequence(element(2, val), [<<48>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_NameConstraints(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_GeneralSubtrees(cindex1, [<<160>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_GeneralSubtrees(cindex2, [<<161>>])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_NetworkAddress(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_NoticeReference(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = enc_DisplayText(cindex1, [])
    {encBytes2, encLen2} = enc_NoticeReference_noticeNumbers(cindex2, [<<48>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_NumericUserIdentifier(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_ORAddress(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = enc_BuiltInStandardAttributes(cindex1, [<<48>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_BuiltInDomainDefinedAttributes(cindex2, [<<48>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_ExtensionAttributes(cindex3, [<<49>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_OTP-X520countryname")(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :printableString ->
        encode_restricted_string(element(2, val), [<<19>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def unquote(:"enc_OTP-emailAddress")(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :ia5String ->
        encode_restricted_string(element(2, val), [<<22>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_OTPAttributeTypeAndValue(val, tagIn) do
    {_, cindex1, cindex2} = val
    objtype = :"OTP-PUB-KEY".getenc_SupportedAttributeTypeAndValues(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {tmpBytes2, _} = objtype.(:Type, cindex2, [])
    {encBytes2, encLen2} = encode_open_type(tmpBytes2, [])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_OTPCertificate(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = enc_OTPTBSCertificate(cindex1, [<<48>>])
    {encBytes2, encLen2} = enc_SignatureAlgorithm(cindex2, [<<48>>])
    {encBytes3, encLen3} = encode_unnamed_bit_string(cindex3, [<<3>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_OTPCharacteristic-two")(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    objbasis = :"OTP-PUB-KEY".getenc_SupportedCharacteristicTwos(cindex2)
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_object_identifier(cindex2, [<<6>>])
    {tmpBytes3, _} = objbasis.(:Type, cindex3, [])
    {encBytes3, encLen3} = encode_open_type(tmpBytes3, [])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_OTPExtension(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    objextnID = :"OTP-PUB-KEY".getenc_SupportedExtensions(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case is_default_10(cindex2) do
      true ->
        {[], 0}
      false ->
        encode_boolean(cindex2, [<<1>>])
    end
    {tmpBytes3, _} = objextnID.(:Type, cindex3, [])
    {encBytes3, encLen3} = encode_open_type(tmpBytes3, [])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_OTPExtensionAttribute(val, tagIn) do
    {_, cindex1, cindex2} = val
    objextensionAttributeType = :"OTP-PUB-KEY".getenc_SupportedExtensionAttributes(cindex1)
    {encBytes1, encLen1} = encode_integer(cindex1, [<<128>>])
    {tmpBytes2, _} = objextensionAttributeType.(:Type, cindex2, [])
    {encBytes2, encLen2} = encode_open_type(tmpBytes2, [<<161>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_OTPExtensionAttributes(val, tagIn) do
    {encBytes, encLen} = enc_OTPExtensionAttributes_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_OTPExtensions(val, tagIn) do
    {encBytes, encLen} = enc_OTPExtensions_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_OTPFieldID(val, tagIn) do
    {_, cindex1, cindex2} = val
    objfieldType = :"OTP-PUB-KEY".getenc_SupportedFieldIds(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {tmpBytes2, _} = objfieldType.(:Type, cindex2, [])
    {encBytes2, encLen2} = encode_open_type(tmpBytes2, [])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_OTPOLDSubjectPublicKeyInfo(val, tagIn) do
    {_, cindex1, cindex2} = val
    objalgorithm = :"OTP-PUB-KEY".getenc_SupportedPublicKeyAlgorithms(element(2, cindex1))
    {encBytes1, encLen1} = enc_OTPOLDSubjectPublicKeyInfo_algorithm(cindex1, [<<48>>])
    {tmpBytes2, _} = objalgorithm.(:PublicKeyType, cindex2, [])
    {encBytes2, encLen2} = encode_open_type(tmpBytes2, [])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_OTPSubjectPublicKeyInfo(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = enc_PublicKeyAlgorithm(cindex1, [<<48>>])
    {encBytes2, encLen2} = encode_unnamed_bit_string(cindex2, [<<3>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_OTPSubjectPublicKeyInfo-Any")(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = enc_PublicKeyAlgorithm(cindex1, [<<48>>])
    {encBytes2, encLen2} = encode_open_type(cindex2, [])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_OTPTBSCertificate(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6, cindex7, cindex8, cindex9, cindex10} = val
    {encBytes1, encLen1} = case is_default_11(cindex1) do
      true ->
        {[], 0}
      false ->
        encode_integer(cindex1, [{:v1, 0}, {:v2, 1}, {:v3, 2}], [<<2>>, <<160>>])
    end
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    {encBytes3, encLen3} = enc_SignatureAlgorithm(cindex3, [<<48>>])
    {encBytes4, encLen4} = enc_Name(cindex4, [])
    {encBytes5, encLen5} = enc_Validity(cindex5, [<<48>>])
    {encBytes6, encLen6} = enc_Name(cindex6, [])
    {encBytes7, encLen7} = enc_OTPSubjectPublicKeyInfo(cindex7, [<<48>>])
    {encBytes8, encLen8} = case cindex8 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_unnamed_bit_string(cindex8, [<<129>>])
    end
    {encBytes9, encLen9} = case cindex9 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_unnamed_bit_string(cindex9, [<<130>>])
    end
    {encBytes10, encLen10} = case cindex10 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_Extensions(cindex10, [<<48>>, <<163>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6, encBytes7, encBytes8, encBytes9, encBytes10]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6 + encLen7 + encLen8 + encLen9 + encLen10
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ObjId(val, tagIn), do: encode_object_identifier(val, tagIn)

  def enc_ObjectDigestInfo(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4} = val
    {encBytes1, encLen1} = case cindex1 do
      :publicKey ->
        encode_tags([<<10>>], [0], 1)
      :publicKeyCert ->
        encode_tags([<<10>>], [1], 1)
      :otherObjectTypes ->
        encode_tags([<<10>>], [2], 1)
      enumval1 ->
        exit({:error, {:asn1, {:enumerated_not_in_range, enumval1}}})
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_object_identifier(cindex2, [<<6>>])
    end
    {encBytes3, encLen3} = enc_AlgorithmIdentifier(cindex3, [<<48>>])
    {encBytes4, encLen4} = encode_unnamed_bit_string(cindex4, [<<3>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_OrganizationName(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_OrganizationalUnitName(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_OrganizationalUnitNames(val, tagIn) do
    {encBytes, encLen} = enc_OrganizationalUnitNames_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_OtherPrimeInfo(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    {encBytes3, encLen3} = encode_integer(cindex3, [<<2>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_OtherPrimeInfos(val, tagIn) do
    {encBytes, encLen} = enc_OtherPrimeInfos_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_PDSName(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_PDSParameter(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex1, [<<19>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex2, [<<20>>])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_Pentanomial(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    {encBytes3, encLen3} = encode_integer(cindex3, [<<2>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_PersonalName(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4} = val
    {encBytes1, encLen1} = encode_restricted_string(cindex1, [<<128>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex2, [<<129>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex3, [<<130>>])
    end
    {encBytes4, encLen4} = case cindex4 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex4, [<<131>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_PhysicalDeliveryCountryName(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :"x121-dcc-code" ->
        encode_restricted_string(element(2, val), [<<18>>])
      :"iso-3166-alpha2-code" ->
        encode_restricted_string(element(2, val), [<<19>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_PhysicalDeliveryOfficeName(val, tagIn), do: enc_PDSParameter(val, tagIn)

  def enc_PhysicalDeliveryOfficeNumber(val, tagIn), do: enc_PDSParameter(val, tagIn)

  def enc_PhysicalDeliveryOrganizationName(val, tagIn), do: enc_PDSParameter(val, tagIn)

  def enc_PhysicalDeliveryPersonalName(val, tagIn), do: enc_PDSParameter(val, tagIn)

  def enc_PolicyConstraints(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_integer(cindex1, [<<128>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_integer(cindex2, [<<129>>])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_PolicyInformation(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_PolicyInformation_policyQualifiers(cindex2, [<<48>>])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_PolicyMappings(val, tagIn) do
    {encBytes, encLen} = enc_PolicyMappings_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_PolicyQualifierId(val, tagIn), do: encode_object_identifier(val, tagIn)

  def enc_PolicyQualifierInfo(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = encode_open_type(cindex2, [])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_PostOfficeBoxAddress(val, tagIn), do: enc_PDSParameter(val, tagIn)

  def enc_PostalCode(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :"numeric-code" ->
        encode_restricted_string(element(2, val), [<<18>>])
      :"printable-code" ->
        encode_restricted_string(element(2, val), [<<19>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_PosteRestanteAddress(val, tagIn), do: enc_PDSParameter(val, tagIn)

  def enc_PresentationAddress(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex1, [<<4>>, <<160>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex2, [<<4>>, <<161>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex3, [<<4>>, <<162>>])
    end
    {encBytes4, encLen4} = enc_PresentationAddress_nAddresses(cindex4, [<<49>>, <<163>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_Prime-p")(val, tagIn), do: encode_integer(val, tagIn)

  def enc_PrivateDomainName(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :numeric ->
        encode_restricted_string(element(2, val), [<<18>>])
      :printable ->
        encode_restricted_string(element(2, val), [<<19>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_PrivateKeyUsagePeriod(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex1, [<<128>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex2, [<<129>>])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ProxyInfo(val, tagIn) do
    {encBytes, encLen} = enc_ProxyInfo_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_PublicKeyAlgorithm(val, tagIn) do
    {_, cindex1, cindex2} = val
    objalgorithm = :"OTP-PUB-KEY".getenc_SupportedPublicKeyAlgorithms(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        {tmpBytes2, _} = objalgorithm.(:Type, cindex2, [])
        encode_open_type(tmpBytes2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_RDNSequence(val, tagIn) do
    {encBytes, encLen} = enc_RDNSequence_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_RSAPrivateKey(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6, cindex7, cindex8, cindex9, cindex10} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [{:"two-prime", 0}, {:multi, 1}], [<<2>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    {encBytes3, encLen3} = encode_integer(cindex3, [<<2>>])
    {encBytes4, encLen4} = encode_integer(cindex4, [<<2>>])
    {encBytes5, encLen5} = encode_integer(cindex5, [<<2>>])
    {encBytes6, encLen6} = encode_integer(cindex6, [<<2>>])
    {encBytes7, encLen7} = encode_integer(cindex7, [<<2>>])
    {encBytes8, encLen8} = encode_integer(cindex8, [<<2>>])
    {encBytes9, encLen9} = encode_integer(cindex9, [<<2>>])
    {encBytes10, encLen10} = case cindex10 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_OtherPrimeInfos(cindex10, [<<48>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6, encBytes7, encBytes8, encBytes9, encBytes10]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6 + encLen7 + encLen8 + encLen9 + encLen10
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_RSAPublicKey(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_RSASSA-PSS-params")(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4} = val
    {encBytes1, encLen1} = enc_Algorithm(cindex1, [<<48>>, <<160>>])
    {encBytes2, encLen2} = enc_Algorithm(cindex2, [<<48>>, <<161>>])
    {encBytes3, encLen3} = case is_default_1(cindex3) do
      true ->
        {[], 0}
      false ->
        encode_integer(cindex3, [<<2>>, <<162>>])
    end
    {encBytes4, encLen4} = case is_default_2(cindex4) do
      true ->
        {[], 0}
      false ->
        encode_integer(cindex4, [{:trailerFieldBC, 1}], [<<2>>, <<163>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ReasonFlags(val, tagIn), do: encode_named_bit_string(val, [{:unused, 0}, {:keyCompromise, 1}, {:cACompromise, 2}, {:affiliationChanged, 3}, {:superseded, 4}, {:cessationOfOperation, 5}, {:certificateHold, 6}, {:privilegeWithdrawn, 7}, {:aACompromise, 8}], tagIn)

  def enc_RecipientInfo(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [{:riVer0, 0}], [<<2>>])
    {encBytes2, encLen2} = enc_IssuerAndSerialNumber(cindex2, [<<48>>])
    {encBytes3, encLen3} = enc_KeyEncryptionAlgorithmIdentifier(cindex3, [<<48>>])
    {encBytes4, encLen4} = encode_restricted_string(cindex4, [<<4>>])
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_RecipientInfos(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :riSet ->
        enc_RecipientInfos_riSet(element(2, val), [<<49>>])
      :riSequence ->
        enc_RecipientInfos_riSequence(element(2, val), [<<48>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_RelativeDistinguishedName(val, tagIn) do
    {encBytes, encLen} = enc_RelativeDistinguishedName_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_RoleSyntax(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_GeneralNames(cindex1, [<<160>>])
    end
    {encBytes2, encLen2} = enc_GeneralName(cindex2, [<<161>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_SecurityCategory(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<128>>])
    {encBytes2, encLen2} = encode_open_type(cindex2, [<<161>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_SignatureAlgorithm(val, tagIn) do
    {_, cindex1, cindex2} = val
    objalgorithm = :"OTP-PUB-KEY".getenc_SupportedSignatureAlgorithms(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        {tmpBytes2, _} = objalgorithm.(:Type, cindex2, [])
        encode_open_type(tmpBytes2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_SignatureAlgorithm-Any")(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_open_type(cindex2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_SignedAndEnvelopedData(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6, cindex7} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [{:seVer1, 1}, {:seVer2, 2}], [<<2>>])
    {encBytes2, encLen2} = enc_RecipientInfos(cindex2, [])
    {encBytes3, encLen3} = enc_DigestAlgorithmIdentifiers(cindex3, [])
    {encBytes4, encLen4} = enc_EncryptedContentInfo(cindex4, [<<48>>])
    {encBytes5, encLen5} = case cindex5 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_SignedAndEnvelopedData_certificates(cindex5, [])
    end
    {encBytes6, encLen6} = case cindex6 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_SignedAndEnvelopedData_crls(cindex6, [])
    end
    {encBytes7, encLen7} = enc_SignerInfos(cindex7, [])
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6, encBytes7]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6 + encLen7
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_SignedData(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [{:sdVer1, 1}, {:sdVer2, 2}], [<<2>>])
    {encBytes2, encLen2} = enc_DigestAlgorithmIdentifiers(cindex2, [])
    {encBytes3, encLen3} = enc_ContentInfo(cindex3, [<<48>>])
    {encBytes4, encLen4} = case cindex4 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_SignedData_certificates(cindex4, [])
    end
    {encBytes5, encLen5} = case cindex5 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_SignedData_crls(cindex5, [])
    end
    {encBytes6, encLen6} = enc_SignerInfos(cindex6, [])
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_SignerInfo(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6, cindex7} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [{:siVer1, 1}, {:siVer2, 2}], [<<2>>])
    {encBytes2, encLen2} = enc_IssuerAndSerialNumber(cindex2, [<<48>>])
    {encBytes3, encLen3} = enc_DigestAlgorithmIdentifier(cindex3, [<<48>>])
    {encBytes4, encLen4} = case cindex4 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_SignerInfoAuthenticatedAttributes(cindex4, [])
    end
    {encBytes5, encLen5} = enc_DigestEncryptionAlgorithmIdentifier(cindex5, [<<48>>])
    {encBytes6, encLen6} = encode_restricted_string(cindex6, [<<4>>])
    {encBytes7, encLen7} = case cindex7 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_SignerInfo_unauthenticatedAttributes(cindex7, [])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6, encBytes7]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6 + encLen7
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_SignerInfoAuthenticatedAttributes(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :aaSet ->
        enc_SignerInfoAuthenticatedAttributes_aaSet(element(2, val), [<<160>>])
      :aaSequence ->
        enc_SignerInfoAuthenticatedAttributes_aaSequence(element(2, val), [<<48>>, <<162>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_SignerInfos(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :siSet ->
        enc_SignerInfos_siSet(element(2, val), [<<49>>])
      :siSequence ->
        enc_SignerInfos_siSequence(element(2, val), [<<48>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_SigningTime(val, tagIn), do: enc_Time(val, tagIn)

  def enc_SkipCerts(val, tagIn), do: encode_integer(val, tagIn)

  def enc_StreetAddress(val, tagIn), do: enc_PDSParameter(val, tagIn)

  def enc_SubjectAltName(val, tagIn), do: enc_GeneralNames(val, tagIn)

  def enc_SubjectDirectoryAttributes(val, tagIn) do
    {encBytes, encLen} = enc_SubjectDirectoryAttributes_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_SubjectInfoAccessSyntax(val, tagIn) do
    {encBytes, encLen} = enc_SubjectInfoAccessSyntax_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_SubjectKeyIdentifier(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_SubjectPublicKeyInfo(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = enc_AlgorithmIdentifier(cindex1, [<<48>>])
    {encBytes2, encLen2} = encode_unnamed_bit_string(cindex2, [<<3>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_SvceAuthInfo(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = enc_GeneralName(cindex1, [])
    {encBytes2, encLen2} = enc_GeneralName(cindex2, [])
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex3, [<<4>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_TBSCertList(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6, cindex7} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_integer(cindex1, [{:v1, 0}, {:v2, 1}, {:v3, 2}], [<<2>>])
    end
    {encBytes2, encLen2} = enc_AlgorithmIdentifier(cindex2, [<<48>>])
    {encBytes3, encLen3} = enc_Name(cindex3, [])
    {encBytes4, encLen4} = enc_Time(cindex4, [])
    {encBytes5, encLen5} = case cindex5 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_Time(cindex5, [])
    end
    {encBytes6, encLen6} = case cindex6 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_TBSCertList_revokedCertificates(cindex6, [<<48>>])
    end
    {encBytes7, encLen7} = case cindex7 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_Extensions(cindex7, [<<48>>, <<160>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6, encBytes7]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6 + encLen7
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_TBSCertificate(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4, cindex5, cindex6, cindex7, cindex8, cindex9, cindex10} = val
    {encBytes1, encLen1} = case is_default_11(cindex1) do
      true ->
        {[], 0}
      false ->
        encode_integer(cindex1, [{:v1, 0}, {:v2, 1}, {:v3, 2}], [<<2>>, <<160>>])
    end
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    {encBytes3, encLen3} = enc_AlgorithmIdentifier(cindex3, [<<48>>])
    {encBytes4, encLen4} = enc_Name(cindex4, [])
    {encBytes5, encLen5} = enc_Validity(cindex5, [<<48>>])
    {encBytes6, encLen6} = enc_Name(cindex6, [])
    {encBytes7, encLen7} = enc_SubjectPublicKeyInfo(cindex7, [<<48>>])
    {encBytes8, encLen8} = case cindex8 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_unnamed_bit_string(cindex8, [<<129>>])
    end
    {encBytes9, encLen9} = case cindex9 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_unnamed_bit_string(cindex9, [<<130>>])
    end
    {encBytes10, encLen10} = case cindex10 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_Extensions(cindex10, [<<48>>, <<163>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4, encBytes5, encBytes6, encBytes7, encBytes8, encBytes9, encBytes10]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4 + encLen5 + encLen6 + encLen7 + encLen8 + encLen9 + encLen10
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_Target(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :targetName ->
        enc_GeneralName(element(2, val), [<<160>>])
      :targetGroup ->
        enc_GeneralName(element(2, val), [<<161>>])
      :targetCert ->
        enc_TargetCert(element(2, val), [<<162>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_TargetCert(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = enc_IssuerSerial(cindex1, [<<48>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_GeneralName(cindex2, [])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_ObjectDigestInfo(cindex3, [<<48>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_Targets(val, tagIn) do
    {encBytes, encLen} = enc_Targets_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_TeletexCommonName(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_TeletexDomainDefinedAttribute(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_restricted_string(cindex1, [<<20>>])
    {encBytes2, encLen2} = encode_restricted_string(cindex2, [<<20>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_TeletexDomainDefinedAttributes(val, tagIn) do
    {encBytes, encLen} = enc_TeletexDomainDefinedAttributes_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_TeletexOrganizationName(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_TeletexOrganizationalUnitName(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_TeletexOrganizationalUnitNames(val, tagIn) do
    {encBytes, encLen} = enc_TeletexOrganizationalUnitNames_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_TeletexPersonalName(val, tagIn) do
    {_, cindex1, cindex2, cindex3, cindex4} = val
    {encBytes1, encLen1} = encode_restricted_string(cindex1, [<<128>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex2, [<<129>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex3, [<<130>>])
    end
    {encBytes4, encLen4} = case cindex4 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex4, [<<131>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3, encBytes4]
    lenSoFar = encLen1 + encLen2 + encLen3 + encLen4
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_TerminalIdentifier(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_TerminalType(val, tagIn), do: encode_integer(val, [{:telex, 3}, {:teletex, 4}, {:"g3-facsimile", 5}, {:"g4-facsimile", 6}, {:"ia5-terminal", 7}, {:videotex, 8}], tagIn)

  def enc_Time(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :utcTime ->
        encode_restricted_string(element(2, val), [<<23>>])
      :generalTime ->
        encode_restricted_string(element(2, val), [<<24>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_TrailerField(val, tagIn), do: encode_integer(val, [{:trailerFieldBC, 1}], tagIn)

  def enc_Trinomial(val, tagIn), do: encode_integer(val, tagIn)

  def enc_UnformattedPostalAddress(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        apply(__MODULE__, :"enc_UnformattedPostalAddress_printable-address", [cindex1, [<<48>>]])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex2, [<<20>>])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_UniqueIdentifier(val, tagIn), do: encode_unnamed_bit_string(val, tagIn)

  def enc_UniquePostalName(val, tagIn), do: enc_PDSParameter(val, tagIn)

  def enc_UserNotice(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_NoticeReference(cindex1, [<<48>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_DisplayText(cindex2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_V2Form(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = case cindex1 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_GeneralNames(cindex1, [<<48>>])
    end
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_IssuerSerial(cindex2, [<<160>>])
    end
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_ObjectDigestInfo(cindex3, [<<161>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_ValidationParms(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_unnamed_bit_string(cindex1, [<<3>>])
    {encBytes2, encLen2} = encode_integer(cindex2, [<<2>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def enc_Validity(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = enc_Time(cindex1, [])
    {encBytes2, encLen2} = enc_Time(cindex2, [])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  def unquote(:"enc_VersionPKCS-1")(val, tagIn), do: encode_integer(val, [{:"two-prime", 0}, {:multi, 1}], tagIn)

  def enc_VersionPKIX1Explicit88(val, tagIn), do: encode_integer(val, [{:v1, 0}, {:v2, 1}, {:v3, 2}], tagIn)

  def enc_X121Address(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_X520CommonName(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :teletexString ->
        encode_restricted_string(element(2, val), [<<20>>])
      :printableString ->
        encode_restricted_string(element(2, val), [<<19>>])
      :universalString ->
        encode_universal_string(element(2, val), [<<28>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      :bmpString ->
        encode_BMP_string(element(2, val), [<<30>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_X520LocalityName(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :teletexString ->
        encode_restricted_string(element(2, val), [<<20>>])
      :printableString ->
        encode_restricted_string(element(2, val), [<<19>>])
      :universalString ->
        encode_universal_string(element(2, val), [<<28>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      :bmpString ->
        encode_BMP_string(element(2, val), [<<30>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_X520OrganizationName(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :teletexString ->
        encode_restricted_string(element(2, val), [<<20>>])
      :printableString ->
        encode_restricted_string(element(2, val), [<<19>>])
      :universalString ->
        encode_universal_string(element(2, val), [<<28>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      :bmpString ->
        encode_BMP_string(element(2, val), [<<30>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_X520OrganizationalUnitName(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :teletexString ->
        encode_restricted_string(element(2, val), [<<20>>])
      :printableString ->
        encode_restricted_string(element(2, val), [<<19>>])
      :universalString ->
        encode_universal_string(element(2, val), [<<28>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      :bmpString ->
        encode_BMP_string(element(2, val), [<<30>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_X520Pseudonym(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :teletexString ->
        encode_restricted_string(element(2, val), [<<20>>])
      :printableString ->
        encode_restricted_string(element(2, val), [<<19>>])
      :universalString ->
        encode_universal_string(element(2, val), [<<28>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      :bmpString ->
        encode_BMP_string(element(2, val), [<<30>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_X520SerialNumber(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_X520StateOrProvinceName(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :teletexString ->
        encode_restricted_string(element(2, val), [<<20>>])
      :printableString ->
        encode_restricted_string(element(2, val), [<<19>>])
      :universalString ->
        encode_universal_string(element(2, val), [<<28>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      :bmpString ->
        encode_BMP_string(element(2, val), [<<30>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_X520Title(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :teletexString ->
        encode_restricted_string(element(2, val), [<<20>>])
      :printableString ->
        encode_restricted_string(element(2, val), [<<19>>])
      :universalString ->
        encode_universal_string(element(2, val), [<<28>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      :bmpString ->
        encode_BMP_string(element(2, val), [<<30>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_X520countryName(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_X520dnQualifier(val, tagIn), do: encode_restricted_string(val, tagIn)

  def enc_X520name(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :teletexString ->
        encode_restricted_string(element(2, val), [<<20>>])
      :printableString ->
        encode_restricted_string(element(2, val), [<<19>>])
      :universalString ->
        encode_universal_string(element(2, val), [<<28>>])
      :utf8String ->
        encode_UTF8_string(element(2, val), [<<12>>])
      :bmpString ->
        encode_BMP_string(element(2, val), [<<30>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  def enc_authorityInfoAccess(:Type, val, _RestPrimFieldName), do: enc_AuthorityInfoAccessSyntax(val, [<<48>>])

  def enc_authorityKeyIdentifier(:Type, val, _RestPrimFieldName), do: enc_AuthorityKeyIdentifier(val, [<<48>>])

  def enc_basicConstraints(:Type, val, _RestPrimFieldName), do: enc_BasicConstraints(val, [<<48>>])

  def enc_cRLDistributionPoints(:Type, val, _RestPrimFieldName), do: enc_CRLDistributionPoints(val, [<<48>>])

  def enc_cRLNumber(:Type, val, _RestPrimFieldName), do: enc_CRLNumber(val, [<<2>>])

  def enc_cRLReasons(:Type, val, _RestPrimFieldName), do: enc_CRLReason(val, [<<10>>])

  def enc_certificateIssuer(:Type, val, _RestPrimFieldName), do: enc_CertificateIssuer(val, [<<48>>])

  def enc_certificatePolicies(:Type, val, _RestPrimFieldName), do: enc_CertificatePolicies(val, [<<48>>])

  def enc_challengePassword(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_challengePassword(:Type, val, _RestPrimFieldName), do: enc_DirectoryString(val, [])

  def enc_challengePassword(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_challengePassword(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_challengePassword(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def enc_commonName(:Type, val, _RestPrimFieldName), do: enc_X520CommonName(val, [])

  def enc_contentType(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_contentType(:Type, val, _RestPrimFieldName), do: enc_ContentType(val, [<<6>>])

  def enc_contentType(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_contentType(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_contentType(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def enc_counterSignature(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_counterSignature(:Type, val, _RestPrimFieldName), do: enc_SignerInfo(val, [<<48>>])

  def enc_counterSignature(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_counterSignature(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_counterSignature(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def enc_countryName(:Type, val, _RestPrimFieldName), do: enc_X520countryName(val, [<<19>>])

  def enc_deltaCRLIndicator(:Type, val, _RestPrimFieldName), do: enc_BaseCRLNumber(val, [<<2>>])

  def enc_dh(:Type, val, _RestPrimFieldName), do: enc_DomainParameters(val, [<<48>>])

  def enc_dh(:PublicKeyType, val, _RestPrimFieldName), do: enc_DHPublicKey(val, [<<2>>])

  def enc_dnQualifier(:Type, val, _RestPrimFieldName), do: enc_X520dnQualifier(val, [<<19>>])

  def enc_domainComponent(:Type, val, _RestPrimFieldName), do: enc_DomainComponent(val, [<<22>>])

  def enc_dsa(:Type, val, _RestPrimFieldName), do: enc_DSAParams(val, [])

  def enc_dsa(:PublicKeyType, val, _RestPrimFieldName), do: enc_DSAPublicKey(val, [<<2>>])

  def unquote(:"enc_dsa-with-sha1")(:Type, val, _RestPrimFieldName), do: enc_DSAParams(val, [])

  def enc_dsaWithSHA1(:Type, val, _RestPrimFieldName), do: enc_DSAParams(val, [])

  def unquote(:"enc_ec-public-key")(:Type, val, _RestPrimFieldName), do: enc_EcpkParameters(val, [])

  def unquote(:"enc_ec-public-key")(:PublicKeyType, val, _RestPrimFieldName), do: enc_ECPoint(val, [<<4>>])

  def unquote(:"enc_ecdsa-with-sha1")(:Type, val, _RestPrimFieldName), do: enc_EcpkParameters(val, [])

  def unquote(:"enc_ecdsa-with-sha224")(:Type, val, _RestPrimFieldName), do: enc_EcpkParameters(val, [])

  def unquote(:"enc_ecdsa-with-sha256")(:Type, val, _RestPrimFieldName), do: enc_EcpkParameters(val, [])

  def unquote(:"enc_ecdsa-with-sha384")(:Type, val, _RestPrimFieldName), do: enc_EcpkParameters(val, [])

  def unquote(:"enc_ecdsa-with-sha512")(:Type, val, _RestPrimFieldName), do: enc_EcpkParameters(val, [])

  def enc_emailAddress(:Type, val, _RestPrimFieldName), do: enc_EmailAddress(val, [<<22>>])

  def enc_extKeyUsage(:Type, val, _RestPrimFieldName), do: enc_ExtKeyUsageSyntax(val, [<<48>>])

  def enc_extensionRequest(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_extensionRequest(:Type, val, _RestPrimFieldName), do: enc_ExtensionRequest(val, [<<48>>])

  def enc_extensionRequest(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_extensionRequest(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_extensionRequest(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def enc_failInfo(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_failInfo(:Type, val, _RestPrimFieldName), do: encode_restricted_string(val, [<<19>>])

  def enc_failInfo(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_failInfo(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_failInfo(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def unquote(:"enc_field-characteristic-two")(:Type, val, _RestPrimFieldName), do: apply(__MODULE__, :"enc_Characteristic-two", [val, [<<48>>]])

  def unquote(:"enc_field-prime-field")(:Type, val, _RestPrimFieldName), do: apply(__MODULE__, :"enc_Prime-p", [val, [<<2>>]])

  def enc_freshestCRL(:Type, val, _RestPrimFieldName), do: enc_FreshestCRL(val, [<<48>>])

  def enc_generationQualifier(:Type, val, _RestPrimFieldName), do: enc_X520name(val, [])

  def enc_givenName(:Type, val, _RestPrimFieldName), do: enc_X520name(val, [])

  def unquote(:"enc_gn-basis")(:Type, val, _RestPrimFieldName), do: encode_null(val, [<<5>>])

  def enc_holdInstructionCode(:Type, val, _RestPrimFieldName), do: enc_HoldInstructionCode(val, [<<6>>])

  def enc_inhibitAnyPolicy(:Type, val, _RestPrimFieldName), do: enc_InhibitAnyPolicy(val, [<<2>>])

  def enc_initials(:Type, val, _RestPrimFieldName), do: enc_X520name(val, [])

  def enc_invalidityDate(:Type, val, _RestPrimFieldName), do: enc_InvalidityDate(val, [<<24>>])

  def enc_issuerAltName(:Type, val, _RestPrimFieldName), do: enc_IssuerAltName(val, [<<48>>])

  def enc_issuingDistributionPoint(:Type, val, _RestPrimFieldName), do: enc_IssuingDistributionPoint(val, [<<48>>])

  def enc_kea(:Type, val, _RestPrimFieldName), do: apply(__MODULE__, :"enc_KEA-Parms-Id", [val, [<<4>>]])

  def enc_kea(:PublicKeyType, val, _RestPrimFieldName), do: apply(__MODULE__, :"enc_KEA-PublicKey", [val, [<<2>>]])

  def enc_keyUsage(:Type, val, _RestPrimFieldName), do: enc_KeyUsage(val, [<<3>>])

  def enc_localityName(:Type, val, _RestPrimFieldName), do: enc_X520LocalityName(val, [])

  def unquote(:"enc_md2-with-rsa-encryption")(:Type, val, _RestPrimFieldName), do: encode_null(val, [<<5>>])

  def unquote(:"enc_md5-with-rsa-encryption")(:Type, val, _RestPrimFieldName), do: encode_null(val, [<<5>>])

  def enc_messageDigest(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_messageDigest(:Type, val, _RestPrimFieldName), do: enc_MessageDigest(val, [<<4>>])

  def enc_messageDigest(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_messageDigest(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_messageDigest(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def enc_messageType(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_messageType(:Type, val, _RestPrimFieldName), do: encode_restricted_string(val, [<<19>>])

  def enc_messageType(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_messageType(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_messageType(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def enc_name(:Type, val, _RestPrimFieldName), do: enc_X520name(val, [])

  def enc_nameConstraints(:Type, val, _RestPrimFieldName), do: enc_NameConstraints(val, [<<48>>])

  def enc_organizationName(:Type, val, _RestPrimFieldName), do: enc_X520OrganizationName(val, [])

  def enc_organizationalUnitName(:Type, val, _RestPrimFieldName), do: enc_X520OrganizationalUnitName(val, [])

  def enc_pkiStatus(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_pkiStatus(:Type, val, _RestPrimFieldName), do: encode_restricted_string(val, [<<19>>])

  def enc_pkiStatus(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_pkiStatus(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_pkiStatus(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def enc_policyConstraints(:Type, val, _RestPrimFieldName), do: enc_PolicyConstraints(val, [<<48>>])

  def enc_policyMappings(:Type, val, _RestPrimFieldName), do: enc_PolicyMappings(val, [<<48>>])

  def unquote(:"enc_pp-basis")(:Type, val, _RestPrimFieldName), do: enc_Pentanomial(val, [<<48>>])

  def enc_privateKeyUsagePeriod(:Type, val, _RestPrimFieldName), do: enc_PrivateKeyUsagePeriod(val, [<<48>>])

  def enc_pseudonym(:Type, val, _RestPrimFieldName), do: enc_X520Pseudonym(val, [])

  def enc_recipientNonce(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_recipientNonce(:Type, val, _RestPrimFieldName), do: encode_restricted_string(val, [<<4>>])

  def enc_recipientNonce(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_recipientNonce(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_recipientNonce(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def unquote(:"enc_rsa-encryption")(:Type, val, _RestPrimFieldName), do: encode_null(val, [<<5>>])

  def unquote(:"enc_rsa-encryption")(:PublicKeyType, val, _RestPrimFieldName), do: enc_RSAPublicKey(val, [<<48>>])

  def enc_senderNonce(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_senderNonce(:Type, val, _RestPrimFieldName), do: encode_restricted_string(val, [<<4>>])

  def enc_senderNonce(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_senderNonce(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_senderNonce(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def enc_serialNumber(:Type, val, _RestPrimFieldName), do: enc_X520SerialNumber(val, [<<19>>])

  def unquote(:"enc_sha-1with-rsa-encryption")(:Type, val, _RestPrimFieldName), do: encode_null(val, [<<5>>])

  def unquote(:"enc_sha1-with-rsa-encryption")(:Type, val, _RestPrimFieldName), do: encode_null(val, [<<5>>])

  def unquote(:"enc_sha224-with-rsa-encryption")(:Type, val, _RestPrimFieldName), do: encode_null(val, [<<5>>])

  def unquote(:"enc_sha256-with-rsa-encryption")(:Type, val, _RestPrimFieldName), do: encode_null(val, [<<5>>])

  def unquote(:"enc_sha384-with-rsa-encryption")(:Type, val, _RestPrimFieldName), do: encode_null(val, [<<5>>])

  def unquote(:"enc_sha512-with-rsa-encryption")(:Type, val, _RestPrimFieldName), do: encode_null(val, [<<5>>])

  def enc_signingTime(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_signingTime(:Type, val, _RestPrimFieldName), do: enc_SigningTime(val, [])

  def enc_signingTime(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_signingTime(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_signingTime(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def enc_stateOrProvinceName(:Type, val, _RestPrimFieldName), do: enc_X520StateOrProvinceName(val, [])

  def enc_subjectAltName(:Type, val, _RestPrimFieldName), do: enc_SubjectAltName(val, [<<48>>])

  def enc_subjectDirectoryAttributes(:Type, val, _RestPrimFieldName), do: enc_SubjectDirectoryAttributes(val, [<<48>>])

  def enc_subjectInfoAccess(:Type, val, _RestPrimFieldName), do: enc_SubjectInfoAccessSyntax(val, [<<48>>])

  def enc_subjectKeyIdentifier(:Type, val, _RestPrimFieldName), do: enc_SubjectKeyIdentifier(val, [<<4>>])

  def enc_surname(:Type, val, _RestPrimFieldName), do: enc_X520name(val, [])

  def enc_title(:Type, val, _RestPrimFieldName), do: enc_X520Title(val, [])

  def unquote(:"enc_tp-basis")(:Type, val, _RestPrimFieldName), do: enc_Trinomial(val, [<<2>>])

  def enc_transactionID(:derivation, _, _), do: exit({:error, {:"use of missing field in object", :derivation}})

  def enc_transactionID(:Type, val, _RestPrimFieldName), do: encode_restricted_string(val, [<<19>>])

  def enc_transactionID(:"equality-match", _, _), do: exit({:error, {:"use of missing field in object", :"equality-match"}})

  def enc_transactionID(:"ordering-match", _, _), do: exit({:error, {:"use of missing field in object", :"ordering-match"}})

  def enc_transactionID(:"substrings-match", _, _), do: exit({:error, {:"use of missing field in object", :"substrings-match"}})

  def unquote(:"enc_x400-common-name")(:Type, val, _RestPrimFieldName), do: enc_CommonName(val, [<<19>>])

  def unquote(:"enc_x400-extended-network-address")(:Type, val, _RestPrimFieldName), do: enc_ExtendedNetworkAddress(val, [])

  def unquote(:"enc_x400-extension-OR-address-components")(:Type, val, _RestPrimFieldName), do: enc_ExtensionORAddressComponents(val, [<<49>>])

  def unquote(:"enc_x400-extension-physical-delivery-address-components")(:Type, val, _RestPrimFieldName), do: enc_ExtensionPhysicalDeliveryAddressComponents(val, [<<49>>])

  def unquote(:"enc_x400-local-postal-attributes")(:Type, val, _RestPrimFieldName), do: enc_LocalPostalAttributes(val, [<<49>>])

  def unquote(:"enc_x400-pds-name")(:Type, val, _RestPrimFieldName), do: enc_PDSName(val, [<<19>>])

  def unquote(:"enc_x400-physical-delivery-country-name")(:Type, val, _RestPrimFieldName), do: enc_PhysicalDeliveryCountryName(val, [])

  def unquote(:"enc_x400-physical-delivery-office-name")(:Type, val, _RestPrimFieldName), do: enc_PhysicalDeliveryOfficeName(val, [<<49>>])

  def unquote(:"enc_x400-physical-delivery-office-number")(:Type, val, _RestPrimFieldName), do: enc_PhysicalDeliveryOfficeNumber(val, [<<49>>])

  def unquote(:"enc_x400-physical-delivery-organization-name")(:Type, val, _RestPrimFieldName), do: enc_PhysicalDeliveryOrganizationName(val, [<<49>>])

  def unquote(:"enc_x400-physical-delivery-personal-name")(:Type, val, _RestPrimFieldName), do: enc_PhysicalDeliveryPersonalName(val, [<<49>>])

  def unquote(:"enc_x400-post-office-box-address")(:Type, val, _RestPrimFieldName), do: enc_PostOfficeBoxAddress(val, [<<49>>])

  def unquote(:"enc_x400-postal-code")(:Type, val, _RestPrimFieldName), do: enc_PostalCode(val, [])

  def unquote(:"enc_x400-poste-restante-address")(:Type, val, _RestPrimFieldName), do: enc_PosteRestanteAddress(val, [<<49>>])

  def unquote(:"enc_x400-street-address")(:Type, val, _RestPrimFieldName), do: enc_StreetAddress(val, [<<49>>])

  def unquote(:"enc_x400-teletex-common-name")(:Type, val, _RestPrimFieldName), do: enc_TeletexCommonName(val, [<<20>>])

  def unquote(:"enc_x400-teletex-domain-defined-attributes")(:Type, val, _RestPrimFieldName), do: enc_TeletexDomainDefinedAttributes(val, [<<48>>])

  def unquote(:"enc_x400-teletex-personal-name")(:Type, val, _RestPrimFieldName), do: enc_TeletexPersonalName(val, [<<49>>])

  def unquote(:"enc_x400-terminal-type")(:Type, val, _RestPrimFieldName), do: enc_TerminalType(val, [<<2>>])

  def unquote(:"enc_x400-unformatted-postal-address")(:Type, val, _RestPrimFieldName), do: enc_UnformattedPostalAddress(val, [<<49>>])

  def unquote(:"enc_x400-unique-postal-name")(:Type, val, _RestPrimFieldName), do: enc_UniquePostalName(val, [<<49>>])

  def encode(type, data) do
    try do
      iolist_to_binary(element(1, encode_disp(type, data)))
    catch
      {class, exception, _} when class === :error or class === :exit ->
        stk = :erlang.get_stacktrace()
        case exception do
          {:error, {:asn1, reason}} ->
            {:error, {:asn1, {reason, stk}}}
          reason ->
            {:error, {:asn1, {reason, stk}}}
        end
    else
      bytes ->
        {:ok, bytes}
    end
  end

  def encoding_rule(), do: :ber

  def encryptedData(), do: {1, 2, 840, 113549, 1, 7, 6}

  def envelopedData(), do: {1, 2, 840, 113549, 1, 7, 3}

  def unquote(:"extended-network-address")(), do: 22

  def unquote(:"extension-OR-address-components")(), do: 12

  def unquote(:"extension-physical-delivery-address-components")(), do: 15

  def getdec_Authenticated(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &dec_contentType/3

  def getdec_Authenticated(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &dec_messageDigest/3

  def getdec_Authenticated(id) when id === {1, 2, 840, 113549, 1, 9, 5}, do: &dec_signingTime/3

  def getdec_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 2}, do: &dec_messageType/3

  def getdec_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 3}, do: &dec_pkiStatus/3

  def getdec_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 4}, do: &dec_failInfo/3

  def getdec_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 5}, do: &dec_senderNonce/3

  def getdec_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 6}, do: &dec_recipientNonce/3

  def getdec_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 7}, do: &dec_transactionID/3

  def getdec_Authenticated(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_CRIAttributes(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_ContentEncryptionAlgorithms(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_Contents({1, 2, 840, 113549, 1, 7, 1}) do
    fn type, bytes, _RestPrimFieldName ->
        case type do
          :Type ->
            dec_Data(bytes)
        end
    end
  end

  def getdec_Contents({1, 2, 840, 113549, 1, 7, 2}) do
    fn type, bytes, _RestPrimFieldName ->
        case type do
          :Type ->
            dec_SignedData(bytes)
        end
    end
  end

  def getdec_Contents({1, 2, 840, 113549, 1, 7, 3}) do
    fn type, bytes, _RestPrimFieldName ->
        case type do
          :Type ->
            dec_EnvelopedData(bytes)
        end
    end
  end

  def getdec_Contents({1, 2, 840, 113549, 1, 7, 4}) do
    fn type, bytes, _RestPrimFieldName ->
        case type do
          :Type ->
            dec_SignedAndEnvelopedData(bytes)
        end
    end
  end

  def getdec_Contents({1, 2, 840, 113549, 1, 7, 5}) do
    fn type, bytes, _RestPrimFieldName ->
        case type do
          :Type ->
            dec_DigestedData(bytes)
        end
    end
  end

  def getdec_Contents({1, 2, 840, 113549, 1, 7, 6}) do
    fn type, bytes, _RestPrimFieldName ->
        case type do
          :Type ->
            dec_EncryptedData(bytes)
        end
    end
  end

  def getdec_Contents(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_DigestAlgorithms(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_DigestEncryptionAlgorithms(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_KeyEncryptionAlgorithms(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_PKInfoAlgorithms(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_SignatureAlgorithms(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 3}, do: &dec_commonName/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 4}, do: &dec_surname/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 5}, do: &dec_serialNumber/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 6}, do: &dec_countryName/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 7}, do: &dec_localityName/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 8}, do: &dec_stateOrProvinceName/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 10}, do: &dec_organizationName/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 11}, do: &dec_organizationalUnitName/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 12}, do: &dec_title/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 41}, do: &dec_name/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 42}, do: &dec_givenName/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 43}, do: &dec_initials/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 44}, do: &dec_generationQualifier/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 46}, do: &dec_dnQualifier/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 65}, do: &dec_pseudonym/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {0, 9, 2342, 19200300, 100, 1, 25}, do: &dec_domainComponent/3

  def getdec_SupportedAttributeTypeAndValues(id) when id === {1, 2, 840, 113549, 1, 9, 1}, do: &dec_emailAddress/3

  def getdec_SupportedAttributeTypeAndValues(errV) do
    fn c, v, _ ->
        exit({{:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getdec_SupportedCharacteristicTwos(id) when id === {1, 2, 840, 10045, 1, 2, 3, 1}, do: &unquote(:"dec_gn-basis")/3

  def getdec_SupportedCharacteristicTwos(id) when id === {1, 2, 840, 10045, 1, 2, 3, 2}, do: &unquote(:"dec_tp-basis")/3

  def getdec_SupportedCharacteristicTwos(id) when id === {1, 2, 840, 10045, 1, 2, 3, 3}, do: &unquote(:"dec_pp-basis")/3

  def getdec_SupportedCharacteristicTwos(errV) do
    fn c, v, _ ->
        exit({{:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getdec_SupportedExtensionAttributes(id) when id === 1, do: &unquote(:"dec_x400-common-name")/3

  def getdec_SupportedExtensionAttributes(id) when id === 2, do: &unquote(:"dec_x400-teletex-common-name")/3

  def getdec_SupportedExtensionAttributes(id) when id === 4, do: &unquote(:"dec_x400-teletex-personal-name")/3

  def getdec_SupportedExtensionAttributes(id) when id === 6, do: &unquote(:"dec_x400-teletex-domain-defined-attributes")/3

  def getdec_SupportedExtensionAttributes(id) when id === 7, do: &unquote(:"dec_x400-pds-name")/3

  def getdec_SupportedExtensionAttributes(id) when id === 8, do: &unquote(:"dec_x400-physical-delivery-country-name")/3

  def getdec_SupportedExtensionAttributes(id) when id === 9, do: &unquote(:"dec_x400-postal-code")/3

  def getdec_SupportedExtensionAttributes(id) when id === 10, do: &unquote(:"dec_x400-physical-delivery-office-name")/3

  def getdec_SupportedExtensionAttributes(id) when id === 11, do: &unquote(:"dec_x400-physical-delivery-office-number")/3

  def getdec_SupportedExtensionAttributes(id) when id === 12, do: &unquote(:"dec_x400-extension-OR-address-components")/3

  def getdec_SupportedExtensionAttributes(id) when id === 13, do: &unquote(:"dec_x400-physical-delivery-personal-name")/3

  def getdec_SupportedExtensionAttributes(id) when id === 14, do: &unquote(:"dec_x400-physical-delivery-organization-name")/3

  def getdec_SupportedExtensionAttributes(id) when id === 15, do: &unquote(:"dec_x400-extension-physical-delivery-address-components")/3

  def getdec_SupportedExtensionAttributes(id) when id === 16, do: &unquote(:"dec_x400-unformatted-postal-address")/3

  def getdec_SupportedExtensionAttributes(id) when id === 17, do: &unquote(:"dec_x400-street-address")/3

  def getdec_SupportedExtensionAttributes(id) when id === 18, do: &unquote(:"dec_x400-post-office-box-address")/3

  def getdec_SupportedExtensionAttributes(id) when id === 19, do: &unquote(:"dec_x400-poste-restante-address")/3

  def getdec_SupportedExtensionAttributes(id) when id === 20, do: &unquote(:"dec_x400-unique-postal-name")/3

  def getdec_SupportedExtensionAttributes(id) when id === 21, do: &unquote(:"dec_x400-local-postal-attributes")/3

  def getdec_SupportedExtensionAttributes(id) when id === 22, do: &unquote(:"dec_x400-extended-network-address")/3

  def getdec_SupportedExtensionAttributes(id) when id === 23, do: &unquote(:"dec_x400-terminal-type")/3

  def getdec_SupportedExtensionAttributes(errV) do
    fn c, v, _ ->
        exit({{:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 9}, do: &dec_subjectDirectoryAttributes/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 14}, do: &dec_subjectKeyIdentifier/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 15}, do: &dec_keyUsage/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 16}, do: &dec_privateKeyUsagePeriod/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 17}, do: &dec_subjectAltName/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 18}, do: &dec_issuerAltName/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 19}, do: &dec_basicConstraints/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 20}, do: &dec_cRLNumber/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 21}, do: &dec_cRLReasons/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 23}, do: &dec_holdInstructionCode/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 24}, do: &dec_invalidityDate/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 27}, do: &dec_deltaCRLIndicator/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 28}, do: &dec_issuingDistributionPoint/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 29}, do: &dec_certificateIssuer/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 30}, do: &dec_nameConstraints/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 31}, do: &dec_cRLDistributionPoints/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 32}, do: &dec_certificatePolicies/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 33}, do: &dec_policyMappings/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 35}, do: &dec_authorityKeyIdentifier/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 36}, do: &dec_policyConstraints/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 37}, do: &dec_extKeyUsage/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 46}, do: &dec_freshestCRL/3

  def getdec_SupportedExtensions(id) when id === {2, 5, 29, 54}, do: &dec_inhibitAnyPolicy/3

  def getdec_SupportedExtensions(id) when id === {1, 3, 6, 1, 5, 5, 7, 1, 1}, do: &dec_authorityInfoAccess/3

  def getdec_SupportedExtensions(id) when id === {1, 3, 6, 1, 5, 5, 7, 1, 11}, do: &dec_subjectInfoAccess/3

  def getdec_SupportedExtensions(errV) do
    fn c, v, _ ->
        exit({{:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getdec_SupportedFieldIds(id) when id === {1, 2, 840, 10045, 1, 1}, do: &unquote(:"dec_field-prime-field")/3

  def getdec_SupportedFieldIds(id) when id === {1, 2, 840, 10045, 1, 2}, do: &unquote(:"dec_field-characteristic-two")/3

  def getdec_SupportedFieldIds(errV) do
    fn c, v, _ ->
        exit({{:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getdec_SupportedPublicKeyAlgorithms(id) when id === {1, 2, 840, 10040, 4, 1}, do: &dec_dsa/3

  def getdec_SupportedPublicKeyAlgorithms(id) when id === {1, 2, 840, 10045, 2, 1}, do: &unquote(:"dec_ec-public-key")/3

  def getdec_SupportedPublicKeyAlgorithms(id) when id === {1, 2, 840, 10046, 2, 1}, do: &dec_dh/3

  def getdec_SupportedPublicKeyAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 1}, do: &unquote(:"dec_rsa-encryption")/3

  def getdec_SupportedPublicKeyAlgorithms(id) when id === {2, 16, 840, 1, 101, 2, 1, 1, 22}, do: &dec_kea/3

  def getdec_SupportedPublicKeyAlgorithms(errV) do
    fn c, v, _ ->
        exit({{:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10040, 4, 3}, do: &unquote(:"dec_dsa-with-sha1")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10045, 4, 1}, do: &unquote(:"dec_ecdsa-with-sha1")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 3, 14, 3, 2, 27}, do: &dec_dsaWithSHA1/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 3, 14, 3, 2, 29}, do: &unquote(:"dec_sha-1with-rsa-encryption")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10045, 4, 3, 1}, do: &unquote(:"dec_ecdsa-with-sha224")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10045, 4, 3, 2}, do: &unquote(:"dec_ecdsa-with-sha256")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10045, 4, 3, 3}, do: &unquote(:"dec_ecdsa-with-sha384")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10045, 4, 3, 4}, do: &unquote(:"dec_ecdsa-with-sha512")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 2}, do: &unquote(:"dec_md2-with-rsa-encryption")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 4}, do: &unquote(:"dec_md5-with-rsa-encryption")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 5}, do: &unquote(:"dec_sha1-with-rsa-encryption")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 11}, do: &unquote(:"dec_sha256-with-rsa-encryption")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 12}, do: &unquote(:"dec_sha384-with-rsa-encryption")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 13}, do: &unquote(:"dec_sha512-with-rsa-encryption")/3

  def getdec_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 14}, do: &unquote(:"dec_sha224-with-rsa-encryption")/3

  def getdec_SupportedSignatureAlgorithms(errV) do
    fn c, v, _ ->
        exit({{:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getdec_Unauthenticated(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &dec_contentType/3

  def getdec_Unauthenticated(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &dec_messageDigest/3

  def getdec_Unauthenticated(id) when id === {1, 2, 840, 113549, 1, 9, 6}, do: &dec_counterSignature/3

  def getdec_Unauthenticated(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_1(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_10(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_11(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_12(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_13(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_2(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_3(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_4(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &dec_contentType/3

  def getdec_internal_object_set_argument_4(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &dec_messageDigest/3

  def getdec_internal_object_set_argument_4(id) when id === {1, 2, 840, 113549, 1, 9, 5}, do: &dec_signingTime/3

  def getdec_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 2}, do: &dec_messageType/3

  def getdec_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 3}, do: &dec_pkiStatus/3

  def getdec_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 4}, do: &dec_failInfo/3

  def getdec_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 5}, do: &dec_senderNonce/3

  def getdec_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 6}, do: &dec_recipientNonce/3

  def getdec_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 7}, do: &dec_transactionID/3

  def getdec_internal_object_set_argument_4(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_5(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &dec_contentType/3

  def getdec_internal_object_set_argument_5(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &dec_messageDigest/3

  def getdec_internal_object_set_argument_5(id) when id === {1, 2, 840, 113549, 1, 9, 5}, do: &dec_signingTime/3

  def getdec_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 2}, do: &dec_messageType/3

  def getdec_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 3}, do: &dec_pkiStatus/3

  def getdec_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 4}, do: &dec_failInfo/3

  def getdec_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 5}, do: &dec_senderNonce/3

  def getdec_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 6}, do: &dec_recipientNonce/3

  def getdec_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 7}, do: &dec_transactionID/3

  def getdec_internal_object_set_argument_5(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_6(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_7(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &dec_contentType/3

  def getdec_internal_object_set_argument_7(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &dec_messageDigest/3

  def getdec_internal_object_set_argument_7(id) when id === {1, 2, 840, 113549, 1, 9, 6}, do: &dec_counterSignature/3

  def getdec_internal_object_set_argument_7(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_8(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &dec_contentType/3

  def getdec_internal_object_set_argument_8(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &dec_messageDigest/3

  def getdec_internal_object_set_argument_8(id) when id === {1, 2, 840, 113549, 1, 9, 6}, do: &dec_counterSignature/3

  def getdec_internal_object_set_argument_8(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getdec_internal_object_set_argument_9(_) do
    fn _, bytes, _RestPrimFieldName ->
        case bytes do
          bin when is_binary(bin) ->
            {:asn1_OPENTYPE, bin}
          _ ->
            {:asn1_OPENTYPE, ber_encode(bytes)}
        end
    end
  end

  def getenc_Authenticated(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &enc_contentType/3

  def getenc_Authenticated(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &enc_messageDigest/3

  def getenc_Authenticated(id) when id === {1, 2, 840, 113549, 1, 9, 5}, do: &enc_signingTime/3

  def getenc_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 2}, do: &enc_messageType/3

  def getenc_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 3}, do: &enc_pkiStatus/3

  def getenc_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 4}, do: &enc_failInfo/3

  def getenc_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 5}, do: &enc_senderNonce/3

  def getenc_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 6}, do: &enc_recipientNonce/3

  def getenc_Authenticated(id) when id === {2, 16, 840, 1, 113733, 1, 9, 7}, do: &enc_transactionID/3

  def getenc_Authenticated(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_CRIAttributes(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_ContentEncryptionAlgorithms(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_Contents({1, 2, 840, 113549, 1, 7, 1}) do
    fn type, val, _RestPrimFieldName ->
        case type do
          :Type ->
            enc_Data(val)
        end
    end
  end

  def getenc_Contents({1, 2, 840, 113549, 1, 7, 2}) do
    fn type, val, _RestPrimFieldName ->
        case type do
          :Type ->
            enc_SignedData(val)
        end
    end
  end

  def getenc_Contents({1, 2, 840, 113549, 1, 7, 3}) do
    fn type, val, _RestPrimFieldName ->
        case type do
          :Type ->
            enc_EnvelopedData(val)
        end
    end
  end

  def getenc_Contents({1, 2, 840, 113549, 1, 7, 4}) do
    fn type, val, _RestPrimFieldName ->
        case type do
          :Type ->
            enc_SignedAndEnvelopedData(val)
        end
    end
  end

  def getenc_Contents({1, 2, 840, 113549, 1, 7, 5}) do
    fn type, val, _RestPrimFieldName ->
        case type do
          :Type ->
            enc_DigestedData(val)
        end
    end
  end

  def getenc_Contents({1, 2, 840, 113549, 1, 7, 6}) do
    fn type, val, _RestPrimFieldName ->
        case type do
          :Type ->
            enc_EncryptedData(val)
        end
    end
  end

  def getenc_Contents(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_DigestAlgorithms(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_DigestEncryptionAlgorithms(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_KeyEncryptionAlgorithms(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_PKInfoAlgorithms(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_SignatureAlgorithms(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 3}, do: &enc_commonName/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 4}, do: &enc_surname/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 5}, do: &enc_serialNumber/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 6}, do: &enc_countryName/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 7}, do: &enc_localityName/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 8}, do: &enc_stateOrProvinceName/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 10}, do: &enc_organizationName/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 11}, do: &enc_organizationalUnitName/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 12}, do: &enc_title/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 41}, do: &enc_name/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 42}, do: &enc_givenName/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 43}, do: &enc_initials/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 44}, do: &enc_generationQualifier/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 46}, do: &enc_dnQualifier/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {2, 5, 4, 65}, do: &enc_pseudonym/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {0, 9, 2342, 19200300, 100, 1, 25}, do: &enc_domainComponent/3

  def getenc_SupportedAttributeTypeAndValues(id) when id === {1, 2, 840, 113549, 1, 9, 1}, do: &enc_emailAddress/3

  def getenc_SupportedAttributeTypeAndValues(errV) do
    fn c, v, _ ->
        exit({:"Type not compatible with table constraint", {:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getenc_SupportedCharacteristicTwos(id) when id === {1, 2, 840, 10045, 1, 2, 3, 1}, do: &unquote(:"enc_gn-basis")/3

  def getenc_SupportedCharacteristicTwos(id) when id === {1, 2, 840, 10045, 1, 2, 3, 2}, do: &unquote(:"enc_tp-basis")/3

  def getenc_SupportedCharacteristicTwos(id) when id === {1, 2, 840, 10045, 1, 2, 3, 3}, do: &unquote(:"enc_pp-basis")/3

  def getenc_SupportedCharacteristicTwos(errV) do
    fn c, v, _ ->
        exit({:"Type not compatible with table constraint", {:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getenc_SupportedExtensionAttributes(id) when id === 1, do: &unquote(:"enc_x400-common-name")/3

  def getenc_SupportedExtensionAttributes(id) when id === 2, do: &unquote(:"enc_x400-teletex-common-name")/3

  def getenc_SupportedExtensionAttributes(id) when id === 4, do: &unquote(:"enc_x400-teletex-personal-name")/3

  def getenc_SupportedExtensionAttributes(id) when id === 6, do: &unquote(:"enc_x400-teletex-domain-defined-attributes")/3

  def getenc_SupportedExtensionAttributes(id) when id === 7, do: &unquote(:"enc_x400-pds-name")/3

  def getenc_SupportedExtensionAttributes(id) when id === 8, do: &unquote(:"enc_x400-physical-delivery-country-name")/3

  def getenc_SupportedExtensionAttributes(id) when id === 9, do: &unquote(:"enc_x400-postal-code")/3

  def getenc_SupportedExtensionAttributes(id) when id === 10, do: &unquote(:"enc_x400-physical-delivery-office-name")/3

  def getenc_SupportedExtensionAttributes(id) when id === 11, do: &unquote(:"enc_x400-physical-delivery-office-number")/3

  def getenc_SupportedExtensionAttributes(id) when id === 12, do: &unquote(:"enc_x400-extension-OR-address-components")/3

  def getenc_SupportedExtensionAttributes(id) when id === 13, do: &unquote(:"enc_x400-physical-delivery-personal-name")/3

  def getenc_SupportedExtensionAttributes(id) when id === 14, do: &unquote(:"enc_x400-physical-delivery-organization-name")/3

  def getenc_SupportedExtensionAttributes(id) when id === 15, do: &unquote(:"enc_x400-extension-physical-delivery-address-components")/3

  def getenc_SupportedExtensionAttributes(id) when id === 16, do: &unquote(:"enc_x400-unformatted-postal-address")/3

  def getenc_SupportedExtensionAttributes(id) when id === 17, do: &unquote(:"enc_x400-street-address")/3

  def getenc_SupportedExtensionAttributes(id) when id === 18, do: &unquote(:"enc_x400-post-office-box-address")/3

  def getenc_SupportedExtensionAttributes(id) when id === 19, do: &unquote(:"enc_x400-poste-restante-address")/3

  def getenc_SupportedExtensionAttributes(id) when id === 20, do: &unquote(:"enc_x400-unique-postal-name")/3

  def getenc_SupportedExtensionAttributes(id) when id === 21, do: &unquote(:"enc_x400-local-postal-attributes")/3

  def getenc_SupportedExtensionAttributes(id) when id === 22, do: &unquote(:"enc_x400-extended-network-address")/3

  def getenc_SupportedExtensionAttributes(id) when id === 23, do: &unquote(:"enc_x400-terminal-type")/3

  def getenc_SupportedExtensionAttributes(errV) do
    fn c, v, _ ->
        exit({:"Type not compatible with table constraint", {:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 9}, do: &enc_subjectDirectoryAttributes/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 14}, do: &enc_subjectKeyIdentifier/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 15}, do: &enc_keyUsage/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 16}, do: &enc_privateKeyUsagePeriod/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 17}, do: &enc_subjectAltName/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 18}, do: &enc_issuerAltName/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 19}, do: &enc_basicConstraints/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 20}, do: &enc_cRLNumber/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 21}, do: &enc_cRLReasons/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 23}, do: &enc_holdInstructionCode/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 24}, do: &enc_invalidityDate/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 27}, do: &enc_deltaCRLIndicator/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 28}, do: &enc_issuingDistributionPoint/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 29}, do: &enc_certificateIssuer/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 30}, do: &enc_nameConstraints/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 31}, do: &enc_cRLDistributionPoints/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 32}, do: &enc_certificatePolicies/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 33}, do: &enc_policyMappings/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 35}, do: &enc_authorityKeyIdentifier/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 36}, do: &enc_policyConstraints/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 37}, do: &enc_extKeyUsage/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 46}, do: &enc_freshestCRL/3

  def getenc_SupportedExtensions(id) when id === {2, 5, 29, 54}, do: &enc_inhibitAnyPolicy/3

  def getenc_SupportedExtensions(id) when id === {1, 3, 6, 1, 5, 5, 7, 1, 1}, do: &enc_authorityInfoAccess/3

  def getenc_SupportedExtensions(id) when id === {1, 3, 6, 1, 5, 5, 7, 1, 11}, do: &enc_subjectInfoAccess/3

  def getenc_SupportedExtensions(errV) do
    fn c, v, _ ->
        exit({:"Type not compatible with table constraint", {:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getenc_SupportedFieldIds(id) when id === {1, 2, 840, 10045, 1, 1}, do: &unquote(:"enc_field-prime-field")/3

  def getenc_SupportedFieldIds(id) when id === {1, 2, 840, 10045, 1, 2}, do: &unquote(:"enc_field-characteristic-two")/3

  def getenc_SupportedFieldIds(errV) do
    fn c, v, _ ->
        exit({:"Type not compatible with table constraint", {:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getenc_SupportedPublicKeyAlgorithms(id) when id === {1, 2, 840, 10040, 4, 1}, do: &enc_dsa/3

  def getenc_SupportedPublicKeyAlgorithms(id) when id === {1, 2, 840, 10045, 2, 1}, do: &unquote(:"enc_ec-public-key")/3

  def getenc_SupportedPublicKeyAlgorithms(id) when id === {1, 2, 840, 10046, 2, 1}, do: &enc_dh/3

  def getenc_SupportedPublicKeyAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 1}, do: &unquote(:"enc_rsa-encryption")/3

  def getenc_SupportedPublicKeyAlgorithms(id) when id === {2, 16, 840, 1, 101, 2, 1, 1, 22}, do: &enc_kea/3

  def getenc_SupportedPublicKeyAlgorithms(errV) do
    fn c, v, _ ->
        exit({:"Type not compatible with table constraint", {:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10040, 4, 3}, do: &unquote(:"enc_dsa-with-sha1")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10045, 4, 1}, do: &unquote(:"enc_ecdsa-with-sha1")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 3, 14, 3, 2, 27}, do: &enc_dsaWithSHA1/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 3, 14, 3, 2, 29}, do: &unquote(:"enc_sha-1with-rsa-encryption")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10045, 4, 3, 1}, do: &unquote(:"enc_ecdsa-with-sha224")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10045, 4, 3, 2}, do: &unquote(:"enc_ecdsa-with-sha256")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10045, 4, 3, 3}, do: &unquote(:"enc_ecdsa-with-sha384")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 10045, 4, 3, 4}, do: &unquote(:"enc_ecdsa-with-sha512")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 2}, do: &unquote(:"enc_md2-with-rsa-encryption")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 4}, do: &unquote(:"enc_md5-with-rsa-encryption")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 5}, do: &unquote(:"enc_sha1-with-rsa-encryption")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 11}, do: &unquote(:"enc_sha256-with-rsa-encryption")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 12}, do: &unquote(:"enc_sha384-with-rsa-encryption")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 13}, do: &unquote(:"enc_sha512-with-rsa-encryption")/3

  def getenc_SupportedSignatureAlgorithms(id) when id === {1, 2, 840, 113549, 1, 1, 14}, do: &unquote(:"enc_sha224-with-rsa-encryption")/3

  def getenc_SupportedSignatureAlgorithms(errV) do
    fn c, v, _ ->
        exit({:"Type not compatible with table constraint", {:component, c}, {:value, v}, {:unique_name_and_value, :id, errV}})
    end
  end

  def getenc_Unauthenticated(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &enc_contentType/3

  def getenc_Unauthenticated(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &enc_messageDigest/3

  def getenc_Unauthenticated(id) when id === {1, 2, 840, 113549, 1, 9, 6}, do: &enc_counterSignature/3

  def getenc_Unauthenticated(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_1(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_10(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_11(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_12(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_13(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_2(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_3(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_4(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &enc_contentType/3

  def getenc_internal_object_set_argument_4(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &enc_messageDigest/3

  def getenc_internal_object_set_argument_4(id) when id === {1, 2, 840, 113549, 1, 9, 5}, do: &enc_signingTime/3

  def getenc_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 2}, do: &enc_messageType/3

  def getenc_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 3}, do: &enc_pkiStatus/3

  def getenc_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 4}, do: &enc_failInfo/3

  def getenc_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 5}, do: &enc_senderNonce/3

  def getenc_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 6}, do: &enc_recipientNonce/3

  def getenc_internal_object_set_argument_4(id) when id === {2, 16, 840, 1, 113733, 1, 9, 7}, do: &enc_transactionID/3

  def getenc_internal_object_set_argument_4(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_5(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &enc_contentType/3

  def getenc_internal_object_set_argument_5(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &enc_messageDigest/3

  def getenc_internal_object_set_argument_5(id) when id === {1, 2, 840, 113549, 1, 9, 5}, do: &enc_signingTime/3

  def getenc_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 2}, do: &enc_messageType/3

  def getenc_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 3}, do: &enc_pkiStatus/3

  def getenc_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 4}, do: &enc_failInfo/3

  def getenc_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 5}, do: &enc_senderNonce/3

  def getenc_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 6}, do: &enc_recipientNonce/3

  def getenc_internal_object_set_argument_5(id) when id === {2, 16, 840, 1, 113733, 1, 9, 7}, do: &enc_transactionID/3

  def getenc_internal_object_set_argument_5(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_6(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_7(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &enc_contentType/3

  def getenc_internal_object_set_argument_7(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &enc_messageDigest/3

  def getenc_internal_object_set_argument_7(id) when id === {1, 2, 840, 113549, 1, 9, 6}, do: &enc_counterSignature/3

  def getenc_internal_object_set_argument_7(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_8(id) when id === {1, 2, 840, 113549, 1, 9, 3}, do: &enc_contentType/3

  def getenc_internal_object_set_argument_8(id) when id === {1, 2, 840, 113549, 1, 9, 4}, do: &enc_messageDigest/3

  def getenc_internal_object_set_argument_8(id) when id === {1, 2, 840, 113549, 1, 9, 6}, do: &enc_counterSignature/3

  def getenc_internal_object_set_argument_8(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def getenc_internal_object_set_argument_9(_) do
    fn _, val, _RestPrimFieldName ->
        case val do
          {:asn1_OPENTYPE, bin} when is_binary(bin) ->
            {bin, byte_size(bin)}
        end
    end
  end

  def gnBasis(), do: {1, 2, 840, 10045, 1, 2, 3, 1}

  def holdInstruction(), do: {2, 2, 840, 10040, 2}

  def unquote(:"id-RSAES-OAEP")(), do: {1, 2, 840, 113549, 1, 1, 7}

  def unquote(:"id-RSASSA-PSS")(), do: {1, 2, 840, 113549, 1, 1, 10}

  def unquote(:"id-VeriSign")(), do: {2, 16, 840, 1, 113733}

  def unquote(:"id-aca")(), do: {1, 3, 6, 1, 5, 5, 7, 10}

  def unquote(:"id-aca-accessIdentity")(), do: {1, 3, 6, 1, 5, 5, 7, 10, 2}

  def unquote(:"id-aca-authenticationInfo")(), do: {1, 3, 6, 1, 5, 5, 7, 10, 1}

  def unquote(:"id-aca-chargingIdentity")(), do: {1, 3, 6, 1, 5, 5, 7, 10, 3}

  def unquote(:"id-aca-encAttrs")(), do: {1, 3, 6, 1, 5, 5, 7, 10, 6}

  def unquote(:"id-aca-group")(), do: {1, 3, 6, 1, 5, 5, 7, 10, 4}

  def unquote(:"id-ad")(), do: {1, 3, 6, 1, 5, 5, 7, 48}

  def unquote(:"id-ad-caIssuers")(), do: {1, 3, 6, 1, 5, 5, 7, 48, 2}

  def unquote(:"id-ad-caRepository")(), do: {1, 3, 6, 1, 5, 5, 7, 48, 5}

  def unquote(:"id-ad-ocsp")(), do: {1, 3, 6, 1, 5, 5, 7, 48, 1}

  def unquote(:"id-ad-timeStamping")(), do: {1, 3, 6, 1, 5, 5, 7, 48, 3}

  def unquote(:"id-at")(), do: {2, 5, 4}

  def unquote(:"id-at-clearance")(), do: {2, 5, 1, 5, 55}

  def unquote(:"id-at-commonName")(), do: {2, 5, 4, 3}

  def unquote(:"id-at-countryName")(), do: {2, 5, 4, 6}

  def unquote(:"id-at-dnQualifier")(), do: {2, 5, 4, 46}

  def unquote(:"id-at-generationQualifier")(), do: {2, 5, 4, 44}

  def unquote(:"id-at-givenName")(), do: {2, 5, 4, 42}

  def unquote(:"id-at-initials")(), do: {2, 5, 4, 43}

  def unquote(:"id-at-localityName")(), do: {2, 5, 4, 7}

  def unquote(:"id-at-name")(), do: {2, 5, 4, 41}

  def unquote(:"id-at-organizationName")(), do: {2, 5, 4, 10}

  def unquote(:"id-at-organizationalUnitName")(), do: {2, 5, 4, 11}

  def unquote(:"id-at-pseudonym")(), do: {2, 5, 4, 65}

  def unquote(:"id-at-role")(), do: {2, 5, 4, 72}

  def unquote(:"id-at-serialNumber")(), do: {2, 5, 4, 5}

  def unquote(:"id-at-stateOrProvinceName")(), do: {2, 5, 4, 8}

  def unquote(:"id-at-surname")(), do: {2, 5, 4, 4}

  def unquote(:"id-at-title")(), do: {2, 5, 4, 12}

  def unquote(:"id-attributes")(), do: {2, 16, 840, 1, 113733, 1, 9}

  def unquote(:"id-ce")(), do: {2, 5, 29}

  def unquote(:"id-ce-authorityKeyIdentifier")(), do: {2, 5, 29, 35}

  def unquote(:"id-ce-basicConstraints")(), do: {2, 5, 29, 19}

  def unquote(:"id-ce-cRLDistributionPoints")(), do: {2, 5, 29, 31}

  def unquote(:"id-ce-cRLNumber")(), do: {2, 5, 29, 20}

  def unquote(:"id-ce-cRLReasons")(), do: {2, 5, 29, 21}

  def unquote(:"id-ce-certificateIssuer")(), do: {2, 5, 29, 29}

  def unquote(:"id-ce-certificatePolicies")(), do: {2, 5, 29, 32}

  def unquote(:"id-ce-deltaCRLIndicator")(), do: {2, 5, 29, 27}

  def unquote(:"id-ce-extKeyUsage")(), do: {2, 5, 29, 37}

  def unquote(:"id-ce-freshestCRL")(), do: {2, 5, 29, 46}

  def unquote(:"id-ce-holdInstructionCode")(), do: {2, 5, 29, 23}

  def unquote(:"id-ce-inhibitAnyPolicy")(), do: {2, 5, 29, 54}

  def unquote(:"id-ce-invalidityDate")(), do: {2, 5, 29, 24}

  def unquote(:"id-ce-issuerAltName")(), do: {2, 5, 29, 18}

  def unquote(:"id-ce-issuingDistributionPoint")(), do: {2, 5, 29, 28}

  def unquote(:"id-ce-keyUsage")(), do: {2, 5, 29, 15}

  def unquote(:"id-ce-nameConstraints")(), do: {2, 5, 29, 30}

  def unquote(:"id-ce-policyConstraints")(), do: {2, 5, 29, 36}

  def unquote(:"id-ce-policyMappings")(), do: {2, 5, 29, 33}

  def unquote(:"id-ce-privateKeyUsagePeriod")(), do: {2, 5, 29, 16}

  def unquote(:"id-ce-subjectAltName")(), do: {2, 5, 29, 17}

  def unquote(:"id-ce-subjectDirectoryAttributes")(), do: {2, 5, 29, 9}

  def unquote(:"id-ce-subjectKeyIdentifier")(), do: {2, 5, 29, 14}

  def unquote(:"id-ce-targetInformation")(), do: {2, 5, 29, 55}

  def unquote(:"id-characteristic-two-basis")(), do: {1, 2, 840, 10045, 1, 2, 3}

  def unquote(:"id-domainComponent")(), do: {0, 9, 2342, 19200300, 100, 1, 25}

  def unquote(:"id-dsa")(), do: {1, 2, 840, 10040, 4, 1}

  def unquote(:"id-dsa-with-sha1")(), do: {1, 2, 840, 10040, 4, 3}

  def unquote(:"id-dsaWithSHA1")(), do: {1, 3, 14, 3, 2, 27}

  def unquote(:"id-ecPublicKey")(), do: {1, 2, 840, 10045, 2, 1}

  def unquote(:"id-ecSigType")(), do: {1, 2, 840, 10045, 4}

  def unquote(:"id-emailAddress")(), do: {1, 2, 840, 113549, 1, 9, 1}

  def unquote(:"id-extensionReq")(), do: {2, 16, 840, 1, 113733, 1, 9, 8}

  def unquote(:"id-failInfo")(), do: {2, 16, 840, 1, 113733, 1, 9, 4}

  def unquote(:"id-fieldType")(), do: {1, 2, 840, 10045, 1}

  def unquote(:"id-hmacWithSHA224")(), do: {1, 2, 840, 113549, 2, 8}

  def unquote(:"id-hmacWithSHA256")(), do: {1, 2, 840, 113549, 2, 9}

  def unquote(:"id-hmacWithSHA384")(), do: {1, 2, 840, 113549, 2, 10}

  def unquote(:"id-hmacWithSHA512")(), do: {1, 2, 840, 113549, 2, 11}

  def unquote(:"id-holdinstruction-callissuer")(), do: {2, 2, 840, 10040, 2, 2}

  def unquote(:"id-holdinstruction-none")(), do: {2, 2, 840, 10040, 2, 1}

  def unquote(:"id-holdinstruction-reject")(), do: {2, 2, 840, 10040, 2, 3}

  def unquote(:"id-keyExchangeAlgorithm")(), do: {2, 16, 840, 1, 101, 2, 1, 1, 22}

  def unquote(:"id-kp")(), do: {1, 3, 6, 1, 5, 5, 7, 3}

  def unquote(:"id-kp-OCSPSigning")(), do: {1, 3, 6, 1, 5, 5, 7, 3, 9}

  def unquote(:"id-kp-clientAuth")(), do: {1, 3, 6, 1, 5, 5, 7, 3, 2}

  def unquote(:"id-kp-codeSigning")(), do: {1, 3, 6, 1, 5, 5, 7, 3, 3}

  def unquote(:"id-kp-emailProtection")(), do: {1, 3, 6, 1, 5, 5, 7, 3, 4}

  def unquote(:"id-kp-serverAuth")(), do: {1, 3, 6, 1, 5, 5, 7, 3, 1}

  def unquote(:"id-kp-timeStamping")(), do: {1, 3, 6, 1, 5, 5, 7, 3, 8}

  def unquote(:"id-md2")(), do: {1, 2, 840, 113549, 2, 2}

  def unquote(:"id-md5")(), do: {1, 2, 840, 113549, 2, 5}

  def unquote(:"id-messageType")(), do: {2, 16, 840, 1, 113733, 1, 9, 2}

  def unquote(:"id-mgf1")(), do: {1, 2, 840, 113549, 1, 1, 8}

  def unquote(:"id-pSpecified")(), do: {1, 2, 840, 113549, 1, 1, 9}

  def unquote(:"id-pe")(), do: {1, 3, 6, 1, 5, 5, 7, 1}

  def unquote(:"id-pe-aaControls")(), do: {1, 3, 6, 1, 5, 5, 7, 1, 6}

  def unquote(:"id-pe-ac-auditIdentity")(), do: {1, 3, 6, 1, 5, 5, 7, 1, 4}

  def unquote(:"id-pe-ac-proxying")(), do: {1, 3, 6, 1, 5, 5, 7, 1, 10}

  def unquote(:"id-pe-authorityInfoAccess")(), do: {1, 3, 6, 1, 5, 5, 7, 1, 1}

  def unquote(:"id-pe-subjectInfoAccess")(), do: {1, 3, 6, 1, 5, 5, 7, 1, 11}

  def unquote(:"id-pki")(), do: {2, 16, 840, 1, 113733, 1}

  def unquote(:"id-pkiStatus")(), do: {2, 16, 840, 1, 113733, 1, 9, 3}

  def unquote(:"id-pkix")(), do: {1, 3, 6, 1, 5, 5, 7}

  def unquote(:"id-publicKeyType")(), do: {1, 2, 840, 10045, 2}

  def unquote(:"id-qt")(), do: {1, 3, 6, 1, 5, 5, 7, 2}

  def unquote(:"id-qt-cps")(), do: {1, 3, 6, 1, 5, 5, 7, 2, 1}

  def unquote(:"id-qt-unotice")(), do: {1, 3, 6, 1, 5, 5, 7, 2, 2}

  def unquote(:"id-recipientNonce")(), do: {2, 16, 840, 1, 113733, 1, 9, 6}

  def unquote(:"id-senderNonce")(), do: {2, 16, 840, 1, 113733, 1, 9, 5}

  def unquote(:"id-sha1")(), do: {1, 3, 14, 3, 2, 26}

  def unquote(:"id-sha224")(), do: {2, 16, 840, 1, 101, 3, 4, 2, 4}

  def unquote(:"id-sha256")(), do: {2, 16, 840, 1, 101, 3, 4, 2, 1}

  def unquote(:"id-sha384")(), do: {2, 16, 840, 1, 101, 3, 4, 2, 2}

  def unquote(:"id-sha512")(), do: {2, 16, 840, 1, 101, 3, 4, 2, 3}

  def unquote(:"id-transId")(), do: {2, 16, 840, 1, 113733, 1, 9, 7}

  def info() do
    case :"OTP-PUB-KEY".module_info(:attributes) do
      attributes when is_list(attributes) ->
        case :lists.keyfind(:asn1_info, 1, attributes) do
          {_, info} when is_list(info) ->
            info
          _ ->
            []
        end
      _ ->
        []
    end
  end

  def legacy_erlang_types(), do: false

  def unquote(:"local-postal-attributes")(), do: 21

  def maps(), do: false

  def md2WithRSAEncryption(), do: {1, 2, 840, 113549, 1, 1, 2}

  def md5WithRSAEncryption(), do: {1, 2, 840, 113549, 1, 1, 4}

  def module_info() do
    # body not decompiled
  end

  def module_info(p0) do
    # body not decompiled
  end

  def unquote(:"pds-name")(), do: 7

  def unquote(:"physical-delivery-country-name")(), do: 8

  def unquote(:"physical-delivery-office-name")(), do: 10

  def unquote(:"physical-delivery-office-number")(), do: 11

  def unquote(:"physical-delivery-organization-name")(), do: 14

  def unquote(:"physical-delivery-personal-name")(), do: 13

  def unquote(:"pkcs-1")(), do: {1, 2, 840, 113549, 1, 1}

  def unquote(:"pkcs-3")(), do: {1, 2, 840, 113549, 1, 3}

  def unquote(:"pkcs-7")(), do: {1, 2, 840, 113549, 1, 7}

  def unquote(:"pkcs-9")(), do: {1, 2, 840, 113549, 1, 9}

  def unquote(:"pkcs-9-at-challengePassword")(), do: {1, 2, 840, 113549, 1, 9, 7}

  def unquote(:"pkcs-9-at-contentType")(), do: {1, 2, 840, 113549, 1, 9, 3}

  def unquote(:"pkcs-9-at-counterSignature")(), do: {1, 2, 840, 113549, 1, 9, 6}

  def unquote(:"pkcs-9-at-extensionRequest")(), do: {1, 2, 840, 113549, 1, 9, 14}

  def unquote(:"pkcs-9-at-messageDigest")(), do: {1, 2, 840, 113549, 1, 9, 4}

  def unquote(:"pkcs-9-at-signingTime")(), do: {1, 2, 840, 113549, 1, 9, 5}

  def unquote(:"post-office-box-address")(), do: 18

  def unquote(:"postal-code")(), do: 9

  def unquote(:"poste-restante-address")(), do: 19

  def ppBasis(), do: {1, 2, 840, 10045, 1, 2, 3, 3}

  def unquote(:"prime-field")(), do: {1, 2, 840, 10045, 1, 1}

  def rsaEncryption(), do: {1, 2, 840, 113549, 1, 1, 1}

  def secp112r1(), do: {1, 3, 132, 0, 6}

  def secp112r2(), do: {1, 3, 132, 0, 7}

  def secp128r1(), do: {1, 3, 132, 0, 28}

  def secp128r2(), do: {1, 3, 132, 0, 29}

  def secp160k1(), do: {1, 3, 132, 0, 9}

  def secp160r1(), do: {1, 3, 132, 0, 8}

  def secp160r2(), do: {1, 3, 132, 0, 30}

  def secp192k1(), do: {1, 3, 132, 0, 31}

  def secp192r1(), do: {1, 2, 840, 10045, 3, 1, 1}

  def secp224k1(), do: {1, 3, 132, 0, 32}

  def secp224r1(), do: {1, 3, 132, 0, 33}

  def secp256k1(), do: {1, 3, 132, 0, 10}

  def secp256r1(), do: {1, 2, 840, 10045, 3, 1, 7}

  def secp384r1(), do: {1, 3, 132, 0, 34}

  def secp521r1(), do: {1, 3, 132, 0, 35}

  def sect113r1(), do: {1, 3, 132, 0, 4}

  def sect113r2(), do: {1, 3, 132, 0, 5}

  def sect131r1(), do: {1, 3, 132, 0, 22}

  def sect131r2(), do: {1, 3, 132, 0, 23}

  def sect163k1(), do: {1, 3, 132, 0, 1}

  def sect163r1(), do: {1, 3, 132, 0, 2}

  def sect163r2(), do: {1, 3, 132, 0, 15}

  def sect193r1(), do: {1, 3, 132, 0, 24}

  def sect193r2(), do: {1, 3, 132, 0, 25}

  def sect233k1(), do: {1, 3, 132, 0, 26}

  def sect233r1(), do: {1, 3, 132, 0, 27}

  def sect239k1(), do: {1, 3, 132, 0, 3}

  def sect283k1(), do: {1, 3, 132, 0, 16}

  def sect283r1(), do: {1, 3, 132, 0, 17}

  def sect409k1(), do: {1, 3, 132, 0, 36}

  def sect409r1(), do: {1, 3, 132, 0, 37}

  def sect571k1(), do: {1, 3, 132, 0, 38}

  def sect571r1(), do: {1, 3, 132, 0, 39}

  def unquote(:"sha-1WithRSAEncryption")(), do: {1, 3, 14, 3, 2, 29}

  def sha1WithRSAEncryption(), do: {1, 2, 840, 113549, 1, 1, 5}

  def sha224WithRSAEncryption(), do: {1, 2, 840, 113549, 1, 1, 14}

  def sha256WithRSAEncryption(), do: {1, 2, 840, 113549, 1, 1, 11}

  def sha384WithRSAEncryption(), do: {1, 2, 840, 113549, 1, 1, 12}

  def sha512WithRSAEncryption(), do: {1, 2, 840, 113549, 1, 1, 13}

  def signedAndEnvelopedData(), do: {1, 2, 840, 113549, 1, 7, 4}

  def signedData(), do: {1, 2, 840, 113549, 1, 7, 2}

  def unquote(:"street-address")(), do: 17

  def unquote(:"teletex-common-name")(), do: 2

  def unquote(:"teletex-domain-defined-attributes")(), do: 6

  def unquote(:"teletex-organization-name")(), do: 3

  def unquote(:"teletex-organizational-unit-names")(), do: 5

  def unquote(:"teletex-personal-name")(), do: 4

  def unquote(:"terminal-type")(), do: 23

  def tpBasis(), do: {1, 2, 840, 10045, 1, 2, 3, 2}

  def unquote(:"ub-common-name")(), do: 64

  def unquote(:"ub-common-name-printable")(), do: 128

  def unquote(:"ub-common-name-teletex")(), do: 128

  def unquote(:"ub-common-name-universal")(), do: 256

  def unquote(:"ub-common-name-utf8")(), do: 256

  def unquote(:"ub-country-name-alpha-length")(), do: 2

  def unquote(:"ub-country-name-numeric-length")(), do: 3

  def unquote(:"ub-domain-defined-attribute-type-length")(), do: 8

  def unquote(:"ub-domain-defined-attribute-value-length")(), do: 128

  def unquote(:"ub-domain-defined-attributes")(), do: 4

  def unquote(:"ub-domain-name-length")(), do: 16

  def unquote(:"ub-e163-4-number-length")(), do: 15

  def unquote(:"ub-e163-4-sub-address-length")(), do: 40

  def unquote(:"ub-emailaddress-length")(), do: 255

  def unquote(:"ub-extension-attributes")(), do: 256

  def unquote(:"ub-generation-qualifier-length")(), do: 3

  def unquote(:"ub-given-name-length")(), do: 16

  def unquote(:"ub-initials-length")(), do: 5

  def unquote(:"ub-integer-options")(), do: 256

  def unquote(:"ub-locality-name")(), do: 128

  def unquote(:"ub-locality-name-universal")(), do: 256

  def unquote(:"ub-locality-name-utf8")(), do: 256

  def unquote(:"ub-match")(), do: 128

  def unquote(:"ub-name")(), do: 32768

  def unquote(:"ub-name-printable")(), do: 65536

  def unquote(:"ub-name-teletex")(), do: 65536

  def unquote(:"ub-name-universal")(), do: 131072

  def unquote(:"ub-name-utf8")(), do: 131072

  def unquote(:"ub-numeric-user-id-length")(), do: 32

  def unquote(:"ub-organization-name")(), do: 64

  def unquote(:"ub-organization-name-printable")(), do: 128

  def unquote(:"ub-organization-name-teletex")(), do: 128

  def unquote(:"ub-organization-name-universal")(), do: 256

  def unquote(:"ub-organization-name-utf8")(), do: 256

  def unquote(:"ub-organizational-unit-name")(), do: 64

  def unquote(:"ub-organizational-unit-name-printable")(), do: 128

  def unquote(:"ub-organizational-unit-name-teletex")(), do: 128

  def unquote(:"ub-organizational-unit-name-universal")(), do: 256

  def unquote(:"ub-organizational-unit-name-utf8")(), do: 256

  def unquote(:"ub-organizational-units")(), do: 4

  def unquote(:"ub-pds-name-length")(), do: 16

  def unquote(:"ub-pds-parameter-length")(), do: 30

  def unquote(:"ub-pds-physical-address-lines")(), do: 6

  def unquote(:"ub-postal-code-length")(), do: 16

  def unquote(:"ub-pseudonym")(), do: 128

  def unquote(:"ub-pseudonym-universal")(), do: 256

  def unquote(:"ub-pseudonym-utf8")(), do: 256

  def unquote(:"ub-serial-number")(), do: 64

  def unquote(:"ub-state-name")(), do: 128

  def unquote(:"ub-state-name-universal")(), do: 256

  def unquote(:"ub-state-name-utf8")(), do: 256

  def unquote(:"ub-surname-length")(), do: 40

  def unquote(:"ub-terminal-id-length")(), do: 24

  def unquote(:"ub-title")(), do: 64

  def unquote(:"ub-title-printable")(), do: 128

  def unquote(:"ub-title-teletex")(), do: 128

  def unquote(:"ub-title-universal")(), do: 256

  def unquote(:"ub-title-utf8")(), do: 256

  def unquote(:"ub-unformatted-address-length")(), do: 180

  def unquote(:"ub-x121-address-length")(), do: 16

  def unquote(:"unformatted-postal-address")(), do: 16

  def unquote(:"unique-postal-name")(), do: 20

  def versionOne(), do: {1, 3, 36, 3, 3, 2, 8, 1, 1}

  # Private Functions

  defp unquote(:"-dec_ACClearAttrs_attrs/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_AttrSpec/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_AttributeCertificateInfo_attributes/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_Attribute_values/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_AuthorityInfoAccessSyntax/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_BuiltInDomainDefinedAttributes/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_CRLDistributionPoints/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_CRLSequence/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_CertificatePolicies/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_CertificateRevocationLists/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_Certificates/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_CertificationRequestInfo_attributes/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_CertificationRequestInfo_attributes_AttributePKCS-10_values/3-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-dec_Clearance_securityCategories/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_DigestAlgorithmIdentifiers_daSequence/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_DigestAlgorithmIdentifiers_daSet/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_ExtKeyUsageSyntax/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_ExtendedCertificatesAndCertificates/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_ExtensionAttributes/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_Extensions/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_GeneralNames/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_GeneralSubtrees/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_IetfAttrSyntax_values/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_NoticeReference_noticeNumbers/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_OTPExtensionAttributes/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_OTPExtensions/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_OrganizationalUnitNames/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_OtherPrimeInfos/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_PDSParameter/2-fun-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_PDSParameter/2-lc$^1/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-dec_PDSParameter/2-lc$^2/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_PersonalName/2-fun-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_PersonalName/2-lc$^1/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-dec_PersonalName/2-lc$^2/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_PolicyInformation_policyQualifiers/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_PolicyMappings/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_PresentationAddress_nAddresses/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_ProxyInfo/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_RDNSequence/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_RecipientInfos_riSequence/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_RecipientInfos_riSet/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_RelativeDistinguishedName/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_SignerInfoAuthenticatedAttributes_aaSequence/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7_values/3-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-dec_SignerInfoAuthenticatedAttributes_aaSet/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7_values/3-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-dec_SignerInfo_unauthenticatedAttributes_uaSequence/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7_values/3-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-dec_SignerInfo_unauthenticatedAttributes_uaSet/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7_values/3-lc$^0/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-dec_SignerInfos_siSequence/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_SignerInfos_siSet/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_SubjectDirectoryAttributes/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_SubjectInfoAccessSyntax/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_TBSCertList_revokedCertificates/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_Targets/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_TeletexDomainDefinedAttributes/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_TeletexOrganizationalUnitNames/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_TeletexPersonalName/2-fun-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_TeletexPersonalName/2-lc$^1/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-dec_TeletexPersonalName/2-lc$^2/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_UnformattedPostalAddress/2-fun-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_UnformattedPostalAddress/2-lc$^1/1-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-dec_UnformattedPostalAddress/2-lc$^2/1-1-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dec_UnformattedPostalAddress_printable-address/2-lc$^0/1-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-dynamicsort_SETOF/1-fun-0-")(p0) do
    # body not decompiled
  end

  defp unquote(:"-e_object_identifier/1-fun-0-")(p0, p1) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Authenticated/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Authenticated/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Authenticated/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Authenticated/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Authenticated/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Authenticated/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Authenticated/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Authenticated/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Authenticated/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Authenticated/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_CRIAttributes/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_ContentEncryptionAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Contents/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Contents/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Contents/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Contents/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Contents/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Contents/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Contents/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_DigestAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_DigestEncryptionAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_KeyEncryptionAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_PKInfoAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SignatureAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-10-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-11-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-12-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-13-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-14-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-15-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-16-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-17-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedAttributeTypeAndValues/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedCharacteristicTwos/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedCharacteristicTwos/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedCharacteristicTwos/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedCharacteristicTwos/1-fun-3-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-10-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-11-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-12-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-13-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-14-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-15-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-16-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-17-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-18-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-19-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-20-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-21-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensionAttributes/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-10-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-11-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-12-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-13-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-14-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-15-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-16-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-17-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-18-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-19-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-20-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-21-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-22-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-23-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-24-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-25-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedExtensions/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedFieldIds/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedFieldIds/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedFieldIds/1-fun-2-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedPublicKeyAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedPublicKeyAlgorithms/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedPublicKeyAlgorithms/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedPublicKeyAlgorithms/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedPublicKeyAlgorithms/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedPublicKeyAlgorithms/1-fun-5-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-10-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-11-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-12-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-13-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-14-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-15-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_SupportedSignatureAlgorithms/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Unauthenticated/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Unauthenticated/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Unauthenticated/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_Unauthenticated/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_1/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_10/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_11/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_12/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_13/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_2/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_3/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_4/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_4/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_4/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_4/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_4/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_4/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_4/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_4/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_4/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_4/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_5/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_5/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_5/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_5/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_5/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_5/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_5/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_5/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_5/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_5/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_6/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_7/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_7/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_7/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_7/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_8/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_8/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_8/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_8/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getdec_internal_object_set_argument_9/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Authenticated/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Authenticated/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Authenticated/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Authenticated/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Authenticated/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Authenticated/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Authenticated/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Authenticated/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Authenticated/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Authenticated/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_CRIAttributes/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_ContentEncryptionAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Contents/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Contents/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Contents/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Contents/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Contents/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Contents/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Contents/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_DigestAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_DigestEncryptionAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_KeyEncryptionAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_PKInfoAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SignatureAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-10-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-11-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-12-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-13-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-14-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-15-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-16-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-17-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedAttributeTypeAndValues/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedCharacteristicTwos/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedCharacteristicTwos/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedCharacteristicTwos/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedCharacteristicTwos/1-fun-3-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-10-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-11-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-12-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-13-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-14-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-15-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-16-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-17-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-18-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-19-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-20-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-21-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensionAttributes/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-10-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-11-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-12-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-13-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-14-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-15-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-16-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-17-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-18-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-19-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-20-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-21-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-22-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-23-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-24-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-25-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedExtensions/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedFieldIds/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedFieldIds/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedFieldIds/1-fun-2-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedPublicKeyAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedPublicKeyAlgorithms/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedPublicKeyAlgorithms/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedPublicKeyAlgorithms/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedPublicKeyAlgorithms/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedPublicKeyAlgorithms/1-fun-5-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-10-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-11-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-12-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-13-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-14-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-15-")(p0, p1, p2, p3) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_SupportedSignatureAlgorithms/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Unauthenticated/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Unauthenticated/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Unauthenticated/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_Unauthenticated/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_1/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_10/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_11/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_12/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_13/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_2/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_3/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_4/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_4/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_4/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_4/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_4/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_4/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_4/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_4/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_4/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_4/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_5/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_5/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_5/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_5/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_5/1-fun-4-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_5/1-fun-5-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_5/1-fun-6-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_5/1-fun-7-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_5/1-fun-8-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_5/1-fun-9-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_6/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_7/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_7/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_7/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_7/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_8/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_8/1-fun-1-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_8/1-fun-2-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_8/1-fun-3-")(p0, p1, p2) do
    # body not decompiled
  end

  defp unquote(:"-getenc_internal_object_set_argument_9/1-fun-0-")(p0, p1, p2) do
    # body not decompiled
  end

  defp ber_decode_nif(b), do: :asn1rt_nif.decode_ber_tlv(b)

  defp ber_encode([tlv]), do: ber_encode(tlv)

  defp ber_encode(tlv) when is_binary(tlv), do: tlv

  defp ber_encode(tlv), do: :asn1rt_nif.encode_ber_tlv(tlv)

  defp check_int(value, ^value, _) when is_integer(value), do: true

  defp check_int(value, defValue, nNL) when is_atom(value) do
    case :lists.keyfind(value, 1, nNL) do
      {_, defValue} ->
        true
      _ ->
        throw(false)
    end
  end

  defp check_int(_, _, _), do: throw(false)

  defp check_named_bitstring([_ | _] = val, names, _, _) do
    case :lists.sort(val) do
      names ->
        true
      _ ->
        throw(false)
    end
  end

  defp check_named_bitstring(bs, _, ^bs, _), do: true

  defp check_named_bitstring(val, _, bs, bsSize) do
    rest = bit_size(val) - bsSize
    case val do
      <<bs :: size(bsSize)-bits, 0 :: size(rest)>> ->
        true
      _ ->
        throw(false)
    end
  end

  defp collect_parts(tlvList), do: collect_parts(tlvList, [])

  defp collect_parts([{_, l} | rest], acc) when is_list(l), do: collect_parts(rest, [collect_parts(l) | acc])

  defp collect_parts([{3, <<unused, bits :: binary>>} | rest], _Acc), do: collect_parts_bit(rest, [bits], unused)

  defp collect_parts([{_T, v} | rest], acc), do: collect_parts(rest, [v | acc])

  defp collect_parts([], acc), do: list_to_binary(:lists.reverse(acc))

  defp collect_parts_bit([{3, <<unused, bits :: binary>>} | rest], acc, uacc), do: collect_parts_bit(rest, [bits | acc], unused + uacc)

  defp collect_parts_bit([], acc, uacc), do: list_to_binary([uacc | :lists.reverse(acc)])

  defp unquote(:"dec-inc-Certificate")(tlv), do: apply(__MODULE__, :"dec-inc-Certificate", [tlv, [16]])

  defp unquote(:"dec-inc-Certificate")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = {:Certificate_tbsCertificate, v1}
    [v2 | tlv3] = tlv2
    term2 = dec_AlgorithmIdentifier(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = decode_native_bit_string(v3, [3])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:Certificate, term1, term2, term3}
    res1
  end

  defp unquote(:"dec-inc-CertificateList")(tlv), do: apply(__MODULE__, :"dec-inc-CertificateList", [tlv, [16]])

  defp unquote(:"dec-inc-CertificateList")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = {:CertificateList_tbsCertList, v1}
    [v2 | tlv3] = tlv2
    term2 = dec_AlgorithmIdentifier(v2, [16])
    [v3 | tlv4] = tlv3
    term3 = decode_native_bit_string(v3, [3])
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:CertificateList, term1, term2, term3}
    res1
  end

  defp dec_AAControls(tlv), do: dec_AAControls(tlv, [16])

  defp dec_ACClearAttrs(tlv), do: dec_ACClearAttrs(tlv, [16])

  defp dec_ACClearAttrs_attrs(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_Attribute(v1, [16])
    end
  end

  defp dec_AccessDescription(tlv), do: dec_AccessDescription(tlv, [16])

  defp dec_AdministrationDomainName(tlv), do: dec_AdministrationDomainName(tlv, [65538])

  defp dec_Algorithm(tlv), do: dec_Algorithm(tlv, [16])

  defp dec_AlgorithmIdentifier(tlv), do: dec_AlgorithmIdentifier(tlv, [16])

  defp dec_AlgorithmNull(tlv), do: dec_AlgorithmNull(tlv, [16])

  defp dec_AnotherName(tlv), do: dec_AnotherName(tlv, [16])

  defp dec_Any(tlv), do: dec_Any(tlv, [])

  defp dec_AttCertIssuer(tlv), do: dec_AttCertIssuer(tlv, [])

  defp dec_AttCertValidityPeriod(tlv), do: dec_AttCertValidityPeriod(tlv, [16])

  defp dec_AttCertVersion(tlv), do: dec_AttCertVersion(tlv, [2])

  defp dec_AttrSpec(tlv), do: dec_AttrSpec(tlv, [16])

  defp dec_Attribute(tlv), do: dec_Attribute(tlv, [16])

  defp dec_AttributeCertificate(tlv), do: dec_AttributeCertificate(tlv, [16])

  defp dec_AttributeCertificateInfo(tlv), do: dec_AttributeCertificateInfo(tlv, [16])

  defp dec_AttributeCertificateInfo_attributes(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_Attribute(v1, [16])
    end
  end

  defp dec_AttributeType(tlv), do: dec_AttributeType(tlv, [6])

  defp dec_AttributeTypeAndValue(tlv), do: dec_AttributeTypeAndValue(tlv, [16])

  defp dec_AttributeValue(tlv), do: dec_AttributeValue(tlv, [])

  defp dec_Attribute_values(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_AttributeValue(v1, [])
    end
  end

  defp dec_AuthorityInfoAccessSyntax(tlv), do: dec_AuthorityInfoAccessSyntax(tlv, [16])

  defp dec_AuthorityKeyIdentifier(tlv), do: dec_AuthorityKeyIdentifier(tlv, [16])

  defp dec_BaseCRLNumber(tlv), do: dec_BaseCRLNumber(tlv, [2])

  defp dec_BaseDistance(tlv), do: dec_BaseDistance(tlv, [2])

  defp dec_BasicConstraints(tlv), do: dec_BasicConstraints(tlv, [16])

  defp dec_Boolean(tlv), do: dec_Boolean(tlv, [1])

  defp dec_BuiltInDomainDefinedAttribute(tlv), do: dec_BuiltInDomainDefinedAttribute(tlv, [16])

  defp dec_BuiltInDomainDefinedAttributes(tlv), do: dec_BuiltInDomainDefinedAttributes(tlv, [16])

  defp dec_BuiltInStandardAttributes(tlv), do: dec_BuiltInStandardAttributes(tlv, [16])

  defp dec_CPSuri(tlv), do: dec_CPSuri(tlv, [22])

  defp dec_CRLDistributionPoints(tlv), do: dec_CRLDistributionPoints(tlv, [16])

  defp dec_CRLNumber(tlv), do: dec_CRLNumber(tlv, [2])

  defp dec_CRLReason(tlv), do: dec_CRLReason(tlv, [10])

  defp dec_CRLSequence(tlv), do: dec_CRLSequence(tlv, [16])

  defp dec_CertPolicyId(tlv), do: dec_CertPolicyId(tlv, [6])

  defp dec_Certificate(tlv), do: dec_Certificate(tlv, [16])

  defp dec_CertificateIssuer(tlv), do: dec_CertificateIssuer(tlv, [16])

  defp dec_CertificateList(tlv), do: dec_CertificateList(tlv, [16])

  defp dec_CertificatePolicies(tlv), do: dec_CertificatePolicies(tlv, [16])

  defp dec_CertificateRevocationLists(tlv), do: dec_CertificateRevocationLists(tlv, [17])

  defp dec_CertificateSerialNumber(tlv), do: dec_CertificateSerialNumber(tlv, [2])

  defp dec_Certificates(tlv), do: dec_Certificates(tlv, [16])

  defp dec_CertificationRequest(tlv), do: dec_CertificationRequest(tlv, [16])

  defp dec_CertificationRequestInfo(tlv), do: dec_CertificationRequestInfo(tlv, [16])

  defp dec_CertificationRequestInfo_attributes(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      apply(__MODULE__, :"dec_CertificationRequestInfo_attributes_AttributePKCS-10", [v1, [16]])
    end
  end

  defp unquote(:"dec_CertificationRequestInfo_attributes_AttributePKCS-10")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    objFun = :"OTP-PUB-KEY".getdec_internal_object_set_argument_12(term1)
    [v2 | tlv3] = tlv2
    term2 = apply(__MODULE__, :"dec_CertificationRequestInfo_attributes_AttributePKCS-10_values", [v2, [17], objFun])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"AttributePKCS-10", term1, term2}
    res1
  end

  defp unquote(:"dec_CertificationRequestInfo_attributes_AttributePKCS-10_values")(tlv, tagIn, objFun) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      (tmptlv1 = decode_open_type(v1, []); try do
      objFun.(:Type, tmptlv1, [])
    catch
      error -> error
    end
    |> case do
      {:EXIT, reason1} ->
        exit({:"Type not compatible with table constraint", reason1})
      tmpterm1 ->
        tmpterm1
    end)
    end
  end

  defp dec_CertificationRequestInfo_subjectPKInfo(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = dec_CertificationRequestInfo_subjectPKInfo_algorithm(v1, [16])
    [v2 | tlv3] = tlv2
    term2 = decode_native_bit_string(v2, [3])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:CertificationRequestInfo_subjectPKInfo, term1, term2}
    res1
  end

  defp dec_CertificationRequestInfo_subjectPKInfo_algorithm(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {tmpterm1, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    decObjalgorithmTerm1 = :"OTP-PUB-KEY".getdec_internal_object_set_argument_10(term1)
    term2 = case tmpterm1 do
      :asn1_NOVALUE ->
        :asn1_NOVALUE
      _ ->
        try do
          decObjalgorithmTerm1.(:Type, tmpterm1, [])
        catch
          error -> error
        end
        |> case do
          {:EXIT, reason1} ->
            exit({:"Type not compatible with table constraint", reason1})
          tmpterm2 ->
            tmpterm2
        end
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:CertificationRequestInfo_subjectPKInfo_algorithm, term1, term2}
    res1
  end

  defp dec_CertificationRequest_signatureAlgorithm(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {tmpterm1, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    decObjalgorithmTerm1 = :"OTP-PUB-KEY".getdec_internal_object_set_argument_13(term1)
    term2 = case tmpterm1 do
      :asn1_NOVALUE ->
        :asn1_NOVALUE
      _ ->
        try do
          decObjalgorithmTerm1.(:Type, tmpterm1, [])
        catch
          error -> error
        end
        |> case do
          {:EXIT, reason1} ->
            exit({:"Type not compatible with table constraint", reason1})
          tmpterm2 ->
            tmpterm2
        end
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:CertificationRequest_signatureAlgorithm, term1, term2}
    res1
  end

  defp unquote(:"dec_Characteristic-two")(tlv), do: apply(__MODULE__, :"dec_Characteristic-two", [tlv, [16]])

  defp dec_ClassList(tlv), do: dec_ClassList(tlv, [3])

  defp dec_Clearance(tlv), do: dec_Clearance(tlv, [16])

  defp dec_Clearance_securityCategories(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_SecurityCategory(v1, [16])
    end
  end

  defp dec_CommonName(tlv), do: dec_CommonName(tlv, [19])

  defp dec_ContentEncryptionAlgorithmIdentifier(tlv), do: dec_ContentEncryptionAlgorithmIdentifier(tlv, [16])

  defp dec_ContentInfo(tlv), do: dec_ContentInfo(tlv, [16])

  defp dec_ContentType(tlv), do: dec_ContentType(tlv, [6])

  defp dec_CountryName(tlv), do: dec_CountryName(tlv, [65537])

  defp dec_Curve(tlv), do: dec_Curve(tlv, [16])

  defp dec_DHParameter(tlv), do: dec_DHParameter(tlv, [16])

  defp dec_DHPublicKey(tlv), do: dec_DHPublicKey(tlv, [2])

  defp dec_DSAParams(tlv), do: dec_DSAParams(tlv, [])

  defp dec_DSAPrivateKey(tlv), do: dec_DSAPrivateKey(tlv, [16])

  defp dec_DSAPublicKey(tlv), do: dec_DSAPublicKey(tlv, [2])

  defp dec_Data(tlv), do: dec_Data(tlv, [4])

  defp dec_Digest(tlv), do: dec_Digest(tlv, [4])

  defp dec_DigestAlgorithmIdentifier(tlv), do: dec_DigestAlgorithmIdentifier(tlv, [16])

  defp dec_DigestAlgorithmIdentifiers(tlv), do: dec_DigestAlgorithmIdentifiers(tlv, [])

  defp dec_DigestAlgorithmIdentifiers_daSequence(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_DigestAlgorithmIdentifier(v1, [16])
    end
  end

  defp dec_DigestAlgorithmIdentifiers_daSet(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_DigestAlgorithmIdentifier(v1, [16])
    end
  end

  defp dec_DigestEncryptionAlgorithmIdentifier(tlv), do: dec_DigestEncryptionAlgorithmIdentifier(tlv, [16])

  defp dec_DigestInfoNull(tlv), do: dec_DigestInfoNull(tlv, [16])

  defp unquote(:"dec_DigestInfoPKCS-1")(tlv), do: apply(__MODULE__, :"dec_DigestInfoPKCS-1", [tlv, [16]])

  defp unquote(:"dec_DigestInfoPKCS-7")(tlv), do: apply(__MODULE__, :"dec_DigestInfoPKCS-7", [tlv, [16]])

  defp dec_DigestedData(tlv), do: dec_DigestedData(tlv, [16])

  defp dec_DirectoryString(tlv), do: dec_DirectoryString(tlv, [])

  defp dec_DisplayText(tlv), do: dec_DisplayText(tlv, [])

  defp dec_DistinguishedName(tlv), do: dec_DistinguishedName(tlv, [16])

  defp dec_DistributionPoint(tlv), do: dec_DistributionPoint(tlv, [16])

  defp dec_DistributionPointName(tlv), do: dec_DistributionPointName(tlv, [])

  defp dec_DomainComponent(tlv), do: dec_DomainComponent(tlv, [22])

  defp dec_DomainParameters(tlv), do: dec_DomainParameters(tlv, [16])

  defp unquote(:"dec_Dss-Parms")(tlv), do: apply(__MODULE__, :"dec_Dss-Parms", [tlv, [16]])

  defp unquote(:"dec_Dss-Sig-Value")(tlv), do: apply(__MODULE__, :"dec_Dss-Sig-Value", [tlv, [16]])

  defp unquote(:"dec_ECDSA-Sig-Value")(tlv), do: apply(__MODULE__, :"dec_ECDSA-Sig-Value", [tlv, [16]])

  defp dec_ECPVer(tlv), do: dec_ECPVer(tlv, [2])

  defp dec_ECParameters(tlv), do: dec_ECParameters(tlv, [16])

  defp dec_ECPoint(tlv), do: dec_ECPoint(tlv, [4])

  defp dec_ECPrivateKey(tlv), do: dec_ECPrivateKey(tlv, [16])

  defp dec_EDIPartyName(tlv), do: dec_EDIPartyName(tlv, [16])

  defp dec_EcpkParameters(tlv), do: dec_EcpkParameters(tlv, [])

  defp dec_EmailAddress(tlv), do: dec_EmailAddress(tlv, [22])

  defp dec_EncryptedContent(tlv), do: dec_EncryptedContent(tlv, [4])

  defp dec_EncryptedContentInfo(tlv), do: dec_EncryptedContentInfo(tlv, [16])

  defp dec_EncryptedData(tlv), do: dec_EncryptedData(tlv, [16])

  defp dec_EncryptedDigest(tlv), do: dec_EncryptedDigest(tlv, [4])

  defp dec_EncryptedKey(tlv), do: dec_EncryptedKey(tlv, [4])

  defp dec_EnvelopedData(tlv), do: dec_EnvelopedData(tlv, [16])

  defp dec_ExtKeyUsageSyntax(tlv), do: dec_ExtKeyUsageSyntax(tlv, [16])

  defp dec_ExtendedCertificate(tlv), do: dec_ExtendedCertificate(tlv, [16])

  defp dec_ExtendedCertificateOrCertificate(tlv), do: dec_ExtendedCertificateOrCertificate(tlv, [])

  defp dec_ExtendedCertificatesAndCertificates(tlv), do: dec_ExtendedCertificatesAndCertificates(tlv, [17])

  defp dec_ExtendedNetworkAddress(tlv), do: dec_ExtendedNetworkAddress(tlv, [])

  defp unquote(:"dec_ExtendedNetworkAddress_e163-4-address")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = (val1 = decode_restricted_string(v1, [131072]); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 15 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
    {term2, tlv3} = case tlv2 do
      [{131073, v2} | tempTlv3] ->
        {(val2 = decode_restricted_string(v2, []); c2 = byte_size(val2); cond do
          1 <= c2 and c2 <= 40 ->
            binary_to_list(val2)
          true ->
            exit({:error, {:asn1, :bad_range}})
        end), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"ExtendedNetworkAddress_e163-4-address", term1, term2}
    res1
  end

  defp dec_Extension(tlv), do: dec_Extension(tlv, [16])

  defp unquote(:"dec_Extension-Any")(tlv), do: apply(__MODULE__, :"dec_Extension-Any", [tlv, [16]])

  defp dec_ExtensionAttribute(tlv), do: dec_ExtensionAttribute(tlv, [16])

  defp dec_ExtensionAttributes(tlv), do: dec_ExtensionAttributes(tlv, [17])

  defp dec_ExtensionORAddressComponents(tlv), do: dec_ExtensionORAddressComponents(tlv, [17])

  defp dec_ExtensionPhysicalDeliveryAddressComponents(tlv), do: dec_ExtensionPhysicalDeliveryAddressComponents(tlv, [17])

  defp dec_ExtensionRequest(tlv), do: dec_ExtensionRequest(tlv, [16])

  defp dec_Extensions(tlv), do: dec_Extensions(tlv, [16])

  defp dec_FieldElement(tlv), do: dec_FieldElement(tlv, [4])

  defp dec_FieldID(tlv), do: dec_FieldID(tlv, [16])

  defp dec_FreshestCRL(tlv), do: dec_FreshestCRL(tlv, [16])

  defp dec_GeneralName(tlv), do: dec_GeneralName(tlv, [])

  defp dec_GeneralNames(tlv), do: dec_GeneralNames(tlv, [16])

  defp dec_GeneralSubtree(tlv), do: dec_GeneralSubtree(tlv, [16])

  defp dec_GeneralSubtrees(tlv), do: dec_GeneralSubtrees(tlv, [16])

  defp dec_HoldInstructionCode(tlv), do: dec_HoldInstructionCode(tlv, [6])

  defp dec_Holder(tlv), do: dec_Holder(tlv, [16])

  defp dec_IetfAttrSyntax(tlv), do: dec_IetfAttrSyntax(tlv, [16])

  defp dec_IetfAttrSyntax_values(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_IetfAttrSyntax_values_SEQOF(v1, [])
    end
  end

  defp dec_IetfAttrSyntax_values_SEQOF(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {4, v1} ->
        {:octets, decode_octet_string(v1, [])}
      {6, v1} ->
        {:oid, decode_object_identifier(v1, [])}
      {12, v1} ->
        {:string, decode_UTF8_string(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  defp dec_InhibitAnyPolicy(tlv), do: dec_InhibitAnyPolicy(tlv, [2])

  defp dec_InvalidityDate(tlv), do: dec_InvalidityDate(tlv, [24])

  defp dec_IssuerAltName(tlv), do: dec_IssuerAltName(tlv, [16])

  defp dec_IssuerAndSerialNumber(tlv), do: dec_IssuerAndSerialNumber(tlv, [16])

  defp dec_IssuerSerial(tlv), do: dec_IssuerSerial(tlv, [16])

  defp dec_IssuingDistributionPoint(tlv), do: dec_IssuingDistributionPoint(tlv, [16])

  defp unquote(:"dec_KEA-Parms-Id")(tlv), do: apply(__MODULE__, :"dec_KEA-Parms-Id", [tlv, [4]])

  defp unquote(:"dec_KEA-PublicKey")(tlv), do: apply(__MODULE__, :"dec_KEA-PublicKey", [tlv, [2]])

  defp dec_KeyEncryptionAlgorithmIdentifier(tlv), do: dec_KeyEncryptionAlgorithmIdentifier(tlv, [16])

  defp dec_KeyIdentifier(tlv), do: dec_KeyIdentifier(tlv, [4])

  defp dec_KeyPurposeId(tlv), do: dec_KeyPurposeId(tlv, [6])

  defp dec_KeyUsage(tlv), do: dec_KeyUsage(tlv, [3])

  defp dec_LocalPostalAttributes(tlv), do: dec_LocalPostalAttributes(tlv, [17])

  defp dec_MessageDigest(tlv), do: dec_MessageDigest(tlv, [4])

  defp dec_Name(tlv), do: dec_Name(tlv, [])

  defp dec_NameConstraints(tlv), do: dec_NameConstraints(tlv, [16])

  defp dec_NetworkAddress(tlv), do: dec_NetworkAddress(tlv, [18])

  defp dec_NoticeReference(tlv), do: dec_NoticeReference(tlv, [16])

  defp dec_NoticeReference_noticeNumbers(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      decode_integer(v1, [2])
    end
  end

  defp dec_NumericUserIdentifier(tlv), do: dec_NumericUserIdentifier(tlv, [18])

  defp dec_ORAddress(tlv), do: dec_ORAddress(tlv, [16])

  defp unquote(:"dec_OTP-X520countryname")(tlv), do: apply(__MODULE__, :"dec_OTP-X520countryname", [tlv, []])

  defp unquote(:"dec_OTP-emailAddress")(tlv), do: apply(__MODULE__, :"dec_OTP-emailAddress", [tlv, []])

  defp dec_OTPAttributeTypeAndValue(tlv), do: dec_OTPAttributeTypeAndValue(tlv, [16])

  defp dec_OTPCertificate(tlv), do: dec_OTPCertificate(tlv, [16])

  defp unquote(:"dec_OTPCharacteristic-two")(tlv), do: apply(__MODULE__, :"dec_OTPCharacteristic-two", [tlv, [16]])

  defp dec_OTPExtension(tlv), do: dec_OTPExtension(tlv, [16])

  defp dec_OTPExtensionAttribute(tlv), do: dec_OTPExtensionAttribute(tlv, [16])

  defp dec_OTPExtensionAttributes(tlv), do: dec_OTPExtensionAttributes(tlv, [17])

  defp dec_OTPExtensions(tlv), do: dec_OTPExtensions(tlv, [16])

  defp dec_OTPFieldID(tlv), do: dec_OTPFieldID(tlv, [16])

  defp dec_OTPOLDSubjectPublicKeyInfo(tlv), do: dec_OTPOLDSubjectPublicKeyInfo(tlv, [16])

  defp dec_OTPOLDSubjectPublicKeyInfo_algorithm(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    {tmpterm1, tlv3} = case tlv2 do
      [v2 | tempTlv3] ->
        {decode_open_type(v2, []), tempTlv3}
      _ ->
        {:asn1_NOVALUE, tlv2}
    end
    decObjalgoTerm1 = :"OTP-PUB-KEY".getdec_SupportedPublicKeyAlgorithms(term1)
    term2 = case tmpterm1 do
      :asn1_NOVALUE ->
        :asn1_NOVALUE
      _ ->
        try do
          decObjalgoTerm1.(:Type, tmpterm1, [])
        catch
          error -> error
        end
        |> case do
          {:EXIT, reason1} ->
            exit({:"Type not compatible with table constraint", reason1})
          tmpterm2 ->
            tmpterm2
        end
    end
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:OTPOLDSubjectPublicKeyInfo_algorithm, term1, term2}
    res1
  end

  defp dec_OTPSubjectPublicKeyInfo(tlv), do: dec_OTPSubjectPublicKeyInfo(tlv, [16])

  defp unquote(:"dec_OTPSubjectPublicKeyInfo-Any")(tlv), do: apply(__MODULE__, :"dec_OTPSubjectPublicKeyInfo-Any", [tlv, [16]])

  defp dec_OTPTBSCertificate(tlv), do: dec_OTPTBSCertificate(tlv, [16])

  defp dec_ObjId(tlv), do: dec_ObjId(tlv, [6])

  defp dec_ObjectDigestInfo(tlv), do: dec_ObjectDigestInfo(tlv, [16])

  defp dec_OrganizationName(tlv), do: dec_OrganizationName(tlv, [19])

  defp dec_OrganizationalUnitName(tlv), do: dec_OrganizationalUnitName(tlv, [19])

  defp dec_OrganizationalUnitNames(tlv), do: dec_OrganizationalUnitNames(tlv, [16])

  defp dec_OtherPrimeInfo(tlv), do: dec_OtherPrimeInfo(tlv, [16])

  defp dec_OtherPrimeInfos(tlv), do: dec_OtherPrimeInfos(tlv, [16])

  defp dec_PDSName(tlv), do: dec_PDSName(tlv, [19])

  defp dec_PDSParameter(tlv), do: dec_PDSParameter(tlv, [17])

  defp dec_Pentanomial(tlv), do: dec_Pentanomial(tlv, [16])

  defp dec_PersonalName(tlv), do: dec_PersonalName(tlv, [17])

  defp dec_PhysicalDeliveryCountryName(tlv), do: dec_PhysicalDeliveryCountryName(tlv, [])

  defp dec_PhysicalDeliveryOfficeName(tlv), do: dec_PhysicalDeliveryOfficeName(tlv, [17])

  defp dec_PhysicalDeliveryOfficeNumber(tlv), do: dec_PhysicalDeliveryOfficeNumber(tlv, [17])

  defp dec_PhysicalDeliveryOrganizationName(tlv), do: dec_PhysicalDeliveryOrganizationName(tlv, [17])

  defp dec_PhysicalDeliveryPersonalName(tlv), do: dec_PhysicalDeliveryPersonalName(tlv, [17])

  defp dec_PolicyConstraints(tlv), do: dec_PolicyConstraints(tlv, [16])

  defp dec_PolicyInformation(tlv), do: dec_PolicyInformation(tlv, [16])

  defp dec_PolicyInformation_policyQualifiers(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_PolicyQualifierInfo(v1, [16])
    end
  end

  defp dec_PolicyMappings(tlv), do: dec_PolicyMappings(tlv, [16])

  defp dec_PolicyMappings_SEQOF(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    [v2 | tlv3] = tlv2
    term2 = decode_object_identifier(v2, [6])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:PolicyMappings_SEQOF, term1, term2}
    res1
  end

  defp dec_PolicyQualifierId(tlv), do: dec_PolicyQualifierId(tlv, [6])

  defp dec_PolicyQualifierInfo(tlv), do: dec_PolicyQualifierInfo(tlv, [16])

  defp dec_PostOfficeBoxAddress(tlv), do: dec_PostOfficeBoxAddress(tlv, [17])

  defp dec_PostalCode(tlv), do: dec_PostalCode(tlv, [])

  defp dec_PosteRestanteAddress(tlv), do: dec_PosteRestanteAddress(tlv, [17])

  defp dec_PresentationAddress(tlv), do: dec_PresentationAddress(tlv, [16])

  defp dec_PresentationAddress_nAddresses(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      decode_octet_string(v1, [4])
    end
  end

  defp unquote(:"dec_Prime-p")(tlv), do: apply(__MODULE__, :"dec_Prime-p", [tlv, [2]])

  defp dec_PrivateDomainName(tlv), do: dec_PrivateDomainName(tlv, [])

  defp dec_PrivateKeyUsagePeriod(tlv), do: dec_PrivateKeyUsagePeriod(tlv, [16])

  defp dec_ProxyInfo(tlv), do: dec_ProxyInfo(tlv, [16])

  defp dec_PublicKeyAlgorithm(tlv), do: dec_PublicKeyAlgorithm(tlv, [16])

  defp dec_RDNSequence(tlv), do: dec_RDNSequence(tlv, [16])

  defp dec_RSAPrivateKey(tlv), do: dec_RSAPrivateKey(tlv, [16])

  defp dec_RSAPublicKey(tlv), do: dec_RSAPublicKey(tlv, [16])

  defp unquote(:"dec_RSASSA-PSS-params")(tlv), do: apply(__MODULE__, :"dec_RSASSA-PSS-params", [tlv, [16]])

  defp dec_ReasonFlags(tlv), do: dec_ReasonFlags(tlv, [3])

  defp dec_RecipientInfo(tlv), do: dec_RecipientInfo(tlv, [16])

  defp dec_RecipientInfos(tlv), do: dec_RecipientInfos(tlv, [])

  defp dec_RecipientInfos_riSequence(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_RecipientInfo(v1, [16])
    end
  end

  defp dec_RecipientInfos_riSet(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_RecipientInfo(v1, [16])
    end
  end

  defp dec_RelativeDistinguishedName(tlv), do: dec_RelativeDistinguishedName(tlv, [17])

  defp dec_RoleSyntax(tlv), do: dec_RoleSyntax(tlv, [16])

  defp dec_SecurityCategory(tlv), do: dec_SecurityCategory(tlv, [16])

  defp dec_SignatureAlgorithm(tlv), do: dec_SignatureAlgorithm(tlv, [16])

  defp unquote(:"dec_SignatureAlgorithm-Any")(tlv), do: apply(__MODULE__, :"dec_SignatureAlgorithm-Any", [tlv, [16]])

  defp dec_SignedAndEnvelopedData(tlv), do: dec_SignedAndEnvelopedData(tlv, [16])

  defp dec_SignedAndEnvelopedData_certificates(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {131072, v1} ->
        {:certSet, dec_ExtendedCertificatesAndCertificates(v1, [])}
      {131074, v1} ->
        {:certSequence, dec_Certificates(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  defp dec_SignedAndEnvelopedData_crls(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {131073, v1} ->
        {:crlSet, dec_CertificateRevocationLists(v1, [])}
      {131075, v1} ->
        {:crlSequence, dec_CRLSequence(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  defp dec_SignedData(tlv), do: dec_SignedData(tlv, [16])

  defp dec_SignedData_certificates(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {131072, v1} ->
        {:certSet, dec_ExtendedCertificatesAndCertificates(v1, [])}
      {131074, v1} ->
        {:certSequence, dec_Certificates(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  defp dec_SignedData_crls(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {131073, v1} ->
        {:crlSet, dec_CertificateRevocationLists(v1, [])}
      {131075, v1} ->
        {:crlSequence, dec_CRLSequence(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  defp dec_SignerInfo(tlv), do: dec_SignerInfo(tlv, [16])

  defp dec_SignerInfoAuthenticatedAttributes(tlv), do: dec_SignerInfoAuthenticatedAttributes(tlv, [])

  defp dec_SignerInfoAuthenticatedAttributes_aaSequence(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      apply(__MODULE__, :"dec_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7", [v1, [16]])
    end
  end

  defp unquote(:"dec_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    objFun = :"OTP-PUB-KEY".getdec_internal_object_set_argument_5(term1)
    [v2 | tlv3] = tlv2
    term2 = apply(__MODULE__, :"dec_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7_values", [v2, [17], objFun])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"AttributePKCS-7", term1, term2}
    res1
  end

  defp unquote(:"dec_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7_values")(tlv, tagIn, objFun) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      (tmptlv1 = decode_open_type(v1, []); try do
      objFun.(:Type, tmptlv1, [])
    catch
      error -> error
    end
    |> case do
      {:EXIT, reason1} ->
        exit({:"Type not compatible with table constraint", reason1})
      tmpterm1 ->
        tmpterm1
    end)
    end
  end

  defp dec_SignerInfoAuthenticatedAttributes_aaSet(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      apply(__MODULE__, :"dec_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7", [v1, [16]])
    end
  end

  defp unquote(:"dec_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    objFun = :"OTP-PUB-KEY".getdec_internal_object_set_argument_4(term1)
    [v2 | tlv3] = tlv2
    term2 = apply(__MODULE__, :"dec_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7_values", [v2, [17], objFun])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"AttributePKCS-7", term1, term2}
    res1
  end

  defp unquote(:"dec_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7_values")(tlv, tagIn, objFun) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      (tmptlv1 = decode_open_type(v1, []); try do
      objFun.(:Type, tmptlv1, [])
    catch
      error -> error
    end
    |> case do
      {:EXIT, reason1} ->
        exit({:"Type not compatible with table constraint", reason1})
      tmpterm1 ->
        tmpterm1
    end)
    end
  end

  defp dec_SignerInfo_unauthenticatedAttributes(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    case tlv1 do
      [ctempTlv1] ->
        ctempTlv1
      _ ->
        tlv1
    end
    |> case do
      {131073, v1} ->
        {:uaSet, dec_SignerInfo_unauthenticatedAttributes_uaSet(v1, [])}
      {131075, v1} ->
        {:uaSequence, dec_SignerInfo_unauthenticatedAttributes_uaSequence(v1, [])}
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_tag, erlangVariableElse}}})
    end
  end

  defp dec_SignerInfo_unauthenticatedAttributes_uaSequence(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      apply(__MODULE__, :"dec_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7", [v1, [16]])
    end
  end

  defp unquote(:"dec_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    objFun = :"OTP-PUB-KEY".getdec_internal_object_set_argument_8(term1)
    [v2 | tlv3] = tlv2
    term2 = apply(__MODULE__, :"dec_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7_values", [v2, [17], objFun])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"AttributePKCS-7", term1, term2}
    res1
  end

  defp unquote(:"dec_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7_values")(tlv, tagIn, objFun) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      (tmptlv1 = decode_open_type(v1, []); try do
      objFun.(:Type, tmptlv1, [])
    catch
      error -> error
    end
    |> case do
      {:EXIT, reason1} ->
        exit({:"Type not compatible with table constraint", reason1})
      tmpterm1 ->
        tmpterm1
    end)
    end
  end

  defp dec_SignerInfo_unauthenticatedAttributes_uaSet(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      apply(__MODULE__, :"dec_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7", [v1, [16]])
    end
  end

  defp unquote(:"dec_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_object_identifier(v1, [6])
    objFun = :"OTP-PUB-KEY".getdec_internal_object_set_argument_7(term1)
    [v2 | tlv3] = tlv2
    term2 = apply(__MODULE__, :"dec_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7_values", [v2, [17], objFun])
    case tlv3 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv3}}})
    end
    res1 = {:"AttributePKCS-7", term1, term2}
    res1
  end

  defp unquote(:"dec_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7_values")(tlv, tagIn, objFun) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      (tmptlv1 = decode_open_type(v1, []); try do
      objFun.(:Type, tmptlv1, [])
    catch
      error -> error
    end
    |> case do
      {:EXIT, reason1} ->
        exit({:"Type not compatible with table constraint", reason1})
      tmpterm1 ->
        tmpterm1
    end)
    end
  end

  defp dec_SignerInfos(tlv), do: dec_SignerInfos(tlv, [])

  defp dec_SignerInfos_siSequence(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_SignerInfo(v1, [16])
    end
  end

  defp dec_SignerInfos_siSet(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_SignerInfo(v1, [16])
    end
  end

  defp dec_SigningTime(tlv), do: dec_SigningTime(tlv, [])

  defp dec_SkipCerts(tlv), do: dec_SkipCerts(tlv, [2])

  defp dec_StreetAddress(tlv), do: dec_StreetAddress(tlv, [17])

  defp dec_SubjectAltName(tlv), do: dec_SubjectAltName(tlv, [16])

  defp dec_SubjectDirectoryAttributes(tlv), do: dec_SubjectDirectoryAttributes(tlv, [16])

  defp dec_SubjectInfoAccessSyntax(tlv), do: dec_SubjectInfoAccessSyntax(tlv, [16])

  defp dec_SubjectKeyIdentifier(tlv), do: dec_SubjectKeyIdentifier(tlv, [4])

  defp dec_SubjectPublicKeyInfo(tlv), do: dec_SubjectPublicKeyInfo(tlv, [16])

  defp dec_SvceAuthInfo(tlv), do: dec_SvceAuthInfo(tlv, [16])

  defp dec_TBSCertList(tlv), do: dec_TBSCertList(tlv, [16])

  defp dec_TBSCertList_revokedCertificates(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      dec_TBSCertList_revokedCertificates_SEQOF(v1, [16])
    end
  end

  defp dec_TBSCertList_revokedCertificates_SEQOF(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    [v1 | tlv2] = tlv1
    term1 = decode_integer(v1, [2])
    [v2 | tlv3] = tlv2
    term2 = dec_Time(v2, [])
    {term3, tlv4} = case tlv3 do
      [{16, v3} | tempTlv4] ->
        {dec_Extensions(v3, []), tempTlv4}
      _ ->
        {:asn1_NOVALUE, tlv3}
    end
    case tlv4 do
      [] ->
        true
      _ ->
        exit({:error, {:asn1, {:unexpected, tlv4}}})
    end
    res1 = {:TBSCertList_revokedCertificates_SEQOF, term1, term2, term3}
    res1
  end

  defp dec_TBSCertificate(tlv), do: dec_TBSCertificate(tlv, [16])

  defp dec_Target(tlv), do: dec_Target(tlv, [])

  defp dec_TargetCert(tlv), do: dec_TargetCert(tlv, [16])

  defp dec_Targets(tlv), do: dec_Targets(tlv, [16])

  defp dec_TeletexCommonName(tlv), do: dec_TeletexCommonName(tlv, [20])

  defp dec_TeletexDomainDefinedAttribute(tlv), do: dec_TeletexDomainDefinedAttribute(tlv, [16])

  defp dec_TeletexDomainDefinedAttributes(tlv), do: dec_TeletexDomainDefinedAttributes(tlv, [16])

  defp dec_TeletexOrganizationName(tlv), do: dec_TeletexOrganizationName(tlv, [20])

  defp dec_TeletexOrganizationalUnitName(tlv), do: dec_TeletexOrganizationalUnitName(tlv, [20])

  defp dec_TeletexOrganizationalUnitNames(tlv), do: dec_TeletexOrganizationalUnitNames(tlv, [16])

  defp dec_TeletexPersonalName(tlv), do: dec_TeletexPersonalName(tlv, [17])

  defp dec_TerminalIdentifier(tlv), do: dec_TerminalIdentifier(tlv, [19])

  defp dec_TerminalType(tlv), do: dec_TerminalType(tlv, [2])

  defp dec_Time(tlv), do: dec_Time(tlv, [])

  defp dec_TrailerField(tlv), do: dec_TrailerField(tlv, [2])

  defp dec_Trinomial(tlv), do: dec_Trinomial(tlv, [2])

  defp dec_UnformattedPostalAddress(tlv), do: dec_UnformattedPostalAddress(tlv, [17])

  defp unquote(:"dec_UnformattedPostalAddress_printable-address")(tlv, tagIn) do
    tlv1 = match_tags(tlv, tagIn)
    for v1 <- tlv1 do
      (val1 = decode_restricted_string(v1, [19]); c1 = byte_size(val1); cond do
      1 <= c1 and c1 <= 30 ->
        binary_to_list(val1)
      true ->
        exit({:error, {:asn1, :bad_range}})
    end)
    end
  end

  defp dec_UniqueIdentifier(tlv), do: dec_UniqueIdentifier(tlv, [3])

  defp dec_UniquePostalName(tlv), do: dec_UniquePostalName(tlv, [17])

  defp dec_UserNotice(tlv), do: dec_UserNotice(tlv, [16])

  defp dec_V2Form(tlv), do: dec_V2Form(tlv, [16])

  defp dec_ValidationParms(tlv), do: dec_ValidationParms(tlv, [16])

  defp dec_Validity(tlv), do: dec_Validity(tlv, [16])

  defp unquote(:"dec_VersionPKCS-1")(tlv), do: apply(__MODULE__, :"dec_VersionPKCS-1", [tlv, [2]])

  defp dec_VersionPKIX1Explicit88(tlv), do: dec_VersionPKIX1Explicit88(tlv, [2])

  defp dec_X121Address(tlv), do: dec_X121Address(tlv, [18])

  defp dec_X520CommonName(tlv), do: dec_X520CommonName(tlv, [])

  defp dec_X520LocalityName(tlv), do: dec_X520LocalityName(tlv, [])

  defp dec_X520OrganizationName(tlv), do: dec_X520OrganizationName(tlv, [])

  defp dec_X520OrganizationalUnitName(tlv), do: dec_X520OrganizationalUnitName(tlv, [])

  defp dec_X520Pseudonym(tlv), do: dec_X520Pseudonym(tlv, [])

  defp dec_X520SerialNumber(tlv), do: dec_X520SerialNumber(tlv, [19])

  defp dec_X520StateOrProvinceName(tlv), do: dec_X520StateOrProvinceName(tlv, [])

  defp dec_X520Title(tlv), do: dec_X520Title(tlv, [])

  defp dec_X520countryName(tlv), do: dec_X520countryName(tlv, [19])

  defp dec_X520dnQualifier(tlv), do: dec_X520dnQualifier(tlv, [19])

  defp dec_X520name(tlv), do: dec_X520name(tlv, [])

  defp dec_subidentifiers(<<>>, _Av, al), do: :lists.reverse(al)

  defp dec_subidentifiers(<<1 :: 1, h :: 7, t :: binary>>, av, al), do: dec_subidentifiers(t, av <<< 7 + h, al)

  defp dec_subidentifiers(<<h, t :: binary>>, av, al), do: dec_subidentifiers(t, 0, [av <<< 7 + h | al])

  defp decode_BMP_string(buffer, tags) do
    bin = match_and_collect(buffer, tags)
    mk_BMP_string(binary_to_list(bin))
  end

  defp decode_UTF8_string(tlv, tagsIn) do
    val = match_tags(tlv, tagsIn)
    case val do
      [_ | _] = partList ->
        collect_parts(partList)
      bin ->
        bin
    end
  end

  defp decode_bitstring2(1, unused, <<b7 :: 1, b6 :: 1, b5 :: 1, b4 :: 1, b3 :: 1, b2 :: 1, b1 :: 1, b0 :: 1, _ :: binary>>), do: :lists.sublist([b7, b6, b5, b4, b3, b2, b1, b0], 8 - unused)

  defp decode_bitstring2(len, unused, <<b7 :: 1, b6 :: 1, b5 :: 1, b4 :: 1, b3 :: 1, b2 :: 1, b1 :: 1, b0 :: 1, buffer :: binary>>), do: [b7, b6, b5, b4, b3, b2, b1, b0 | decode_bitstring2(len - 1, unused, buffer)]

  defp decode_bitstring_NNL(bitList, namedNumberList), do: decode_bitstring_NNL(bitList, namedNumberList, 0, [])

  defp decode_bitstring_NNL([], _, _No, result), do: :lists.reverse(result)

  defp decode_bitstring_NNL([b | bitList], [{name, no} | namedNumberList], ^no, result) do
    cond do
      b === 0 ->
        decode_bitstring_NNL(bitList, namedNumberList, no + 1, result)
      true ->
        decode_bitstring_NNL(bitList, namedNumberList, no + 1, [name | result])
    end
  end

  defp decode_bitstring_NNL([1 | bitList], namedNumberList, no, result), do: decode_bitstring_NNL(bitList, namedNumberList, no + 1, [{:bit, no} | result])

  defp decode_bitstring_NNL([0 | bitList], namedNumberList, no, result), do: decode_bitstring_NNL(bitList, namedNumberList, no + 1, result)

  defp decode_boolean(tlv, tagIn) do
    val = match_tags(tlv, tagIn)
    case val do
      <<0 :: 8>> ->
        false
      <<_ :: 8>> ->
        true
      _ ->
        exit({:error, {:asn1, {:decode_boolean, val}}})
    end
  end

  defp decode_constr_indef_incomplete(_TagMatch, <<0, 0, rest :: binary>>, acc), do: {:lists.reverse(acc), rest}

  defp decode_constr_indef_incomplete([tag | restTags], bin, acc) do
    case decode_primitive_incomplete([tag], bin) do
      {tlv, rest} ->
        decode_constr_indef_incomplete(restTags, rest, [tlv | acc])
      :asn1_NOVALUE ->
        decode_constr_indef_incomplete(restTags, bin, acc)
    end
  end

  defp decode_constructed(bin) when byte_size(bin) === 0, do: []

  defp decode_constructed(bin) do
    {tlv, rest} = decode_primitive(bin)
    [tlv | decode_constructed(rest)]
  end

  defp decode_constructed_incomplete([tags = [ts]], bin) when is_list(ts), do: decode_constructed_incomplete(tags, bin)

  defp decode_constructed_incomplete(_TagMatch, <<>>), do: []

  defp decode_constructed_incomplete([:mandatory | restTag], bin) do
    {tlv, rest} = decode_primitive(bin)
    [tlv | decode_constructed_incomplete(restTag, rest)]
  end

  defp decode_constructed_incomplete(directives = [[alt, _] | _], bin) when alt === :alt_undec or alt === :alt or alt === :alt_parts do
    {_Form, tagNo, v, rest} = decode_tag_and_length(bin)
    case incomplete_choice_alt(tagNo, directives) do
      {:alt_undec, _} ->
        lenA = byte_size(bin) - byte_size(rest)
        <<a :: size(lenA)-binary, rest :: binary>> = bin
        a
      {:alt, innerDirectives} ->
        {tlv, rest} = decode_primitive_incomplete(innerDirectives, v)
        {tagNo, tlv}
      {:alt_parts, _} ->
        [{tagNo, decode_parts_incomplete(v)}]
      :no_match ->
        {tlv, _} = decode_primitive(bin)
        tlv
    end
  end

  defp decode_constructed_incomplete([tagNo | restTag], bin) do
    case decode_primitive_incomplete([tagNo], bin) do
      {tlv, rest} ->
        [tlv | decode_constructed_incomplete(restTag, rest)]
      :asn1_NOVALUE ->
        decode_constructed_incomplete(restTag, bin)
    end
  end

  defp decode_constructed_incomplete([], bin) do
    {tlv, rest} = decode_primitive(bin)
    [tlv | decode_constructed_incomplete([], rest)]
  end

  defp decode_constructed_indefinite(<<0, 0, rest :: binary>>, acc), do: {:lists.reverse(acc), rest}

  defp decode_constructed_indefinite(bin, acc) do
    {tlv, rest} = decode_primitive(bin)
    decode_constructed_indefinite(rest, [tlv | acc])
  end

  defp decode_disp(:ECPrivateKey, data), do: dec_ECPrivateKey(data)

  defp decode_disp(:DSAPrivateKey, data), do: dec_DSAPrivateKey(data)

  defp decode_disp(:DHParameter, data), do: dec_DHParameter(data)

  defp decode_disp(:DigestInfoNull, data), do: dec_DigestInfoNull(data)

  defp decode_disp(:"DigestInfoPKCS-1", data), do: apply(__MODULE__, :"dec_DigestInfoPKCS-1", [data])

  defp decode_disp(:TrailerField, data), do: dec_TrailerField(data)

  defp decode_disp(:"RSASSA-PSS-params", data), do: apply(__MODULE__, :"dec_RSASSA-PSS-params", [data])

  defp decode_disp(:AlgorithmNull, data), do: dec_AlgorithmNull(data)

  defp decode_disp(:Algorithm, data), do: dec_Algorithm(data)

  defp decode_disp(:OtherPrimeInfo, data), do: dec_OtherPrimeInfo(data)

  defp decode_disp(:OtherPrimeInfos, data), do: dec_OtherPrimeInfos(data)

  defp decode_disp(:"VersionPKCS-1", data), do: apply(__MODULE__, :"dec_VersionPKCS-1", [data])

  defp decode_disp(:RSAPrivateKey, data), do: dec_RSAPrivateKey(data)

  defp decode_disp(:RSAPublicKey, data), do: dec_RSAPublicKey(data)

  defp decode_disp(:Curve, data), do: dec_Curve(data)

  defp decode_disp(:ECPVer, data), do: dec_ECPVer(data)

  defp decode_disp(:ECParameters, data), do: dec_ECParameters(data)

  defp decode_disp(:EcpkParameters, data), do: dec_EcpkParameters(data)

  defp decode_disp(:ECPoint, data), do: dec_ECPoint(data)

  defp decode_disp(:FieldElement, data), do: dec_FieldElement(data)

  defp decode_disp(:Pentanomial, data), do: dec_Pentanomial(data)

  defp decode_disp(:Trinomial, data), do: dec_Trinomial(data)

  defp decode_disp(:"Characteristic-two", data), do: apply(__MODULE__, :"dec_Characteristic-two", [data])

  defp decode_disp(:"Prime-p", data), do: apply(__MODULE__, :"dec_Prime-p", [data])

  defp decode_disp(:"ECDSA-Sig-Value", data), do: apply(__MODULE__, :"dec_ECDSA-Sig-Value", [data])

  defp decode_disp(:FieldID, data), do: dec_FieldID(data)

  defp decode_disp(:"KEA-Parms-Id", data), do: apply(__MODULE__, :"dec_KEA-Parms-Id", [data])

  defp decode_disp(:ValidationParms, data), do: dec_ValidationParms(data)

  defp decode_disp(:DomainParameters, data), do: dec_DomainParameters(data)

  defp decode_disp(:DHPublicKey, data), do: dec_DHPublicKey(data)

  defp decode_disp(:"Dss-Sig-Value", data), do: apply(__MODULE__, :"dec_Dss-Sig-Value", [data])

  defp decode_disp(:"Dss-Parms", data), do: apply(__MODULE__, :"dec_Dss-Parms", [data])

  defp decode_disp(:DSAPublicKey, data), do: dec_DSAPublicKey(data)

  defp decode_disp(:ProxyInfo, data), do: dec_ProxyInfo(data)

  defp decode_disp(:ACClearAttrs, data), do: dec_ACClearAttrs(data)

  defp decode_disp(:AttrSpec, data), do: dec_AttrSpec(data)

  defp decode_disp(:AAControls, data), do: dec_AAControls(data)

  defp decode_disp(:SecurityCategory, data), do: dec_SecurityCategory(data)

  defp decode_disp(:ClassList, data), do: dec_ClassList(data)

  defp decode_disp(:Clearance, data), do: dec_Clearance(data)

  defp decode_disp(:RoleSyntax, data), do: dec_RoleSyntax(data)

  defp decode_disp(:SvceAuthInfo, data), do: dec_SvceAuthInfo(data)

  defp decode_disp(:IetfAttrSyntax, data), do: dec_IetfAttrSyntax(data)

  defp decode_disp(:TargetCert, data), do: dec_TargetCert(data)

  defp decode_disp(:Target, data), do: dec_Target(data)

  defp decode_disp(:Targets, data), do: dec_Targets(data)

  defp decode_disp(:AttCertValidityPeriod, data), do: dec_AttCertValidityPeriod(data)

  defp decode_disp(:IssuerSerial, data), do: dec_IssuerSerial(data)

  defp decode_disp(:V2Form, data), do: dec_V2Form(data)

  defp decode_disp(:AttCertIssuer, data), do: dec_AttCertIssuer(data)

  defp decode_disp(:ObjectDigestInfo, data), do: dec_ObjectDigestInfo(data)

  defp decode_disp(:Holder, data), do: dec_Holder(data)

  defp decode_disp(:AttCertVersion, data), do: dec_AttCertVersion(data)

  defp decode_disp(:AttributeCertificateInfo, data), do: dec_AttributeCertificateInfo(data)

  defp decode_disp(:AttributeCertificate, data), do: dec_AttributeCertificate(data)

  defp decode_disp(:InvalidityDate, data), do: dec_InvalidityDate(data)

  defp decode_disp(:HoldInstructionCode, data), do: dec_HoldInstructionCode(data)

  defp decode_disp(:CertificateIssuer, data), do: dec_CertificateIssuer(data)

  defp decode_disp(:CRLReason, data), do: dec_CRLReason(data)

  defp decode_disp(:BaseCRLNumber, data), do: dec_BaseCRLNumber(data)

  defp decode_disp(:IssuingDistributionPoint, data), do: dec_IssuingDistributionPoint(data)

  defp decode_disp(:CRLNumber, data), do: dec_CRLNumber(data)

  defp decode_disp(:SubjectInfoAccessSyntax, data), do: dec_SubjectInfoAccessSyntax(data)

  defp decode_disp(:AccessDescription, data), do: dec_AccessDescription(data)

  defp decode_disp(:AuthorityInfoAccessSyntax, data), do: dec_AuthorityInfoAccessSyntax(data)

  defp decode_disp(:FreshestCRL, data), do: dec_FreshestCRL(data)

  defp decode_disp(:InhibitAnyPolicy, data), do: dec_InhibitAnyPolicy(data)

  defp decode_disp(:KeyPurposeId, data), do: dec_KeyPurposeId(data)

  defp decode_disp(:ExtKeyUsageSyntax, data), do: dec_ExtKeyUsageSyntax(data)

  defp decode_disp(:ReasonFlags, data), do: dec_ReasonFlags(data)

  defp decode_disp(:DistributionPointName, data), do: dec_DistributionPointName(data)

  defp decode_disp(:DistributionPoint, data), do: dec_DistributionPoint(data)

  defp decode_disp(:CRLDistributionPoints, data), do: dec_CRLDistributionPoints(data)

  defp decode_disp(:SkipCerts, data), do: dec_SkipCerts(data)

  defp decode_disp(:PolicyConstraints, data), do: dec_PolicyConstraints(data)

  defp decode_disp(:BaseDistance, data), do: dec_BaseDistance(data)

  defp decode_disp(:GeneralSubtree, data), do: dec_GeneralSubtree(data)

  defp decode_disp(:GeneralSubtrees, data), do: dec_GeneralSubtrees(data)

  defp decode_disp(:NameConstraints, data), do: dec_NameConstraints(data)

  defp decode_disp(:BasicConstraints, data), do: dec_BasicConstraints(data)

  defp decode_disp(:SubjectDirectoryAttributes, data), do: dec_SubjectDirectoryAttributes(data)

  defp decode_disp(:IssuerAltName, data), do: dec_IssuerAltName(data)

  defp decode_disp(:EDIPartyName, data), do: dec_EDIPartyName(data)

  defp decode_disp(:AnotherName, data), do: dec_AnotherName(data)

  defp decode_disp(:GeneralName, data), do: dec_GeneralName(data)

  defp decode_disp(:GeneralNames, data), do: dec_GeneralNames(data)

  defp decode_disp(:SubjectAltName, data), do: dec_SubjectAltName(data)

  defp decode_disp(:PolicyMappings, data), do: dec_PolicyMappings(data)

  defp decode_disp(:DisplayText, data), do: dec_DisplayText(data)

  defp decode_disp(:NoticeReference, data), do: dec_NoticeReference(data)

  defp decode_disp(:UserNotice, data), do: dec_UserNotice(data)

  defp decode_disp(:CPSuri, data), do: dec_CPSuri(data)

  defp decode_disp(:PolicyQualifierId, data), do: dec_PolicyQualifierId(data)

  defp decode_disp(:PolicyQualifierInfo, data), do: dec_PolicyQualifierInfo(data)

  defp decode_disp(:CertPolicyId, data), do: dec_CertPolicyId(data)

  defp decode_disp(:PolicyInformation, data), do: dec_PolicyInformation(data)

  defp decode_disp(:CertificatePolicies, data), do: dec_CertificatePolicies(data)

  defp decode_disp(:PrivateKeyUsagePeriod, data), do: dec_PrivateKeyUsagePeriod(data)

  defp decode_disp(:KeyUsage, data), do: dec_KeyUsage(data)

  defp decode_disp(:SubjectKeyIdentifier, data), do: dec_SubjectKeyIdentifier(data)

  defp decode_disp(:KeyIdentifier, data), do: dec_KeyIdentifier(data)

  defp decode_disp(:AuthorityKeyIdentifier, data), do: dec_AuthorityKeyIdentifier(data)

  defp decode_disp(:EncryptedData, data), do: dec_EncryptedData(data)

  defp decode_disp(:DigestedData, data), do: dec_DigestedData(data)

  defp decode_disp(:SignedAndEnvelopedData, data), do: dec_SignedAndEnvelopedData(data)

  defp decode_disp(:EncryptedKey, data), do: dec_EncryptedKey(data)

  defp decode_disp(:RecipientInfo, data), do: dec_RecipientInfo(data)

  defp decode_disp(:EncryptedContent, data), do: dec_EncryptedContent(data)

  defp decode_disp(:EncryptedContentInfo, data), do: dec_EncryptedContentInfo(data)

  defp decode_disp(:RecipientInfos, data), do: dec_RecipientInfos(data)

  defp decode_disp(:EnvelopedData, data), do: dec_EnvelopedData(data)

  defp decode_disp(:Digest, data), do: dec_Digest(data)

  defp decode_disp(:"DigestInfoPKCS-7", data), do: apply(__MODULE__, :"dec_DigestInfoPKCS-7", [data])

  defp decode_disp(:EncryptedDigest, data), do: dec_EncryptedDigest(data)

  defp decode_disp(:SignerInfo, data), do: dec_SignerInfo(data)

  defp decode_disp(:DigestAlgorithmIdentifiers, data), do: dec_DigestAlgorithmIdentifiers(data)

  defp decode_disp(:SignerInfos, data), do: dec_SignerInfos(data)

  defp decode_disp(:SignedData, data), do: dec_SignedData(data)

  defp decode_disp(:Data, data), do: dec_Data(data)

  defp decode_disp(:ContentType, data), do: dec_ContentType(data)

  defp decode_disp(:ContentInfo, data), do: dec_ContentInfo(data)

  defp decode_disp(:KeyEncryptionAlgorithmIdentifier, data), do: dec_KeyEncryptionAlgorithmIdentifier(data)

  defp decode_disp(:IssuerAndSerialNumber, data), do: dec_IssuerAndSerialNumber(data)

  defp decode_disp(:ExtendedCertificatesAndCertificates, data), do: dec_ExtendedCertificatesAndCertificates(data)

  defp decode_disp(:ExtendedCertificate, data), do: dec_ExtendedCertificate(data)

  defp decode_disp(:ExtendedCertificateOrCertificate, data), do: dec_ExtendedCertificateOrCertificate(data)

  defp decode_disp(:DigestEncryptionAlgorithmIdentifier, data), do: dec_DigestEncryptionAlgorithmIdentifier(data)

  defp decode_disp(:DigestAlgorithmIdentifier, data), do: dec_DigestAlgorithmIdentifier(data)

  defp decode_disp(:ContentEncryptionAlgorithmIdentifier, data), do: dec_ContentEncryptionAlgorithmIdentifier(data)

  defp decode_disp(:CRLSequence, data), do: dec_CRLSequence(data)

  defp decode_disp(:Certificates, data), do: dec_Certificates(data)

  defp decode_disp(:CertificateRevocationLists, data), do: dec_CertificateRevocationLists(data)

  defp decode_disp(:SignerInfoAuthenticatedAttributes, data), do: dec_SignerInfoAuthenticatedAttributes(data)

  defp decode_disp(:SigningTime, data), do: dec_SigningTime(data)

  defp decode_disp(:MessageDigest, data), do: dec_MessageDigest(data)

  defp decode_disp(:CertificationRequest, data), do: dec_CertificationRequest(data)

  defp decode_disp(:CertificationRequestInfo, data), do: dec_CertificationRequestInfo(data)

  defp decode_disp(:ExtensionRequest, data), do: dec_ExtensionRequest(data)

  defp decode_disp(:TeletexDomainDefinedAttribute, data), do: dec_TeletexDomainDefinedAttribute(data)

  defp decode_disp(:TeletexDomainDefinedAttributes, data), do: dec_TeletexDomainDefinedAttributes(data)

  defp decode_disp(:TerminalType, data), do: dec_TerminalType(data)

  defp decode_disp(:PresentationAddress, data), do: dec_PresentationAddress(data)

  defp decode_disp(:ExtendedNetworkAddress, data), do: dec_ExtendedNetworkAddress(data)

  defp decode_disp(:PDSParameter, data), do: dec_PDSParameter(data)

  defp decode_disp(:LocalPostalAttributes, data), do: dec_LocalPostalAttributes(data)

  defp decode_disp(:UniquePostalName, data), do: dec_UniquePostalName(data)

  defp decode_disp(:PosteRestanteAddress, data), do: dec_PosteRestanteAddress(data)

  defp decode_disp(:PostOfficeBoxAddress, data), do: dec_PostOfficeBoxAddress(data)

  defp decode_disp(:StreetAddress, data), do: dec_StreetAddress(data)

  defp decode_disp(:UnformattedPostalAddress, data), do: dec_UnformattedPostalAddress(data)

  defp decode_disp(:ExtensionPhysicalDeliveryAddressComponents, data), do: dec_ExtensionPhysicalDeliveryAddressComponents(data)

  defp decode_disp(:PhysicalDeliveryOrganizationName, data), do: dec_PhysicalDeliveryOrganizationName(data)

  defp decode_disp(:PhysicalDeliveryPersonalName, data), do: dec_PhysicalDeliveryPersonalName(data)

  defp decode_disp(:ExtensionORAddressComponents, data), do: dec_ExtensionORAddressComponents(data)

  defp decode_disp(:PhysicalDeliveryOfficeNumber, data), do: dec_PhysicalDeliveryOfficeNumber(data)

  defp decode_disp(:PhysicalDeliveryOfficeName, data), do: dec_PhysicalDeliveryOfficeName(data)

  defp decode_disp(:PostalCode, data), do: dec_PostalCode(data)

  defp decode_disp(:PhysicalDeliveryCountryName, data), do: dec_PhysicalDeliveryCountryName(data)

  defp decode_disp(:PDSName, data), do: dec_PDSName(data)

  defp decode_disp(:TeletexOrganizationalUnitName, data), do: dec_TeletexOrganizationalUnitName(data)

  defp decode_disp(:TeletexOrganizationalUnitNames, data), do: dec_TeletexOrganizationalUnitNames(data)

  defp decode_disp(:TeletexPersonalName, data), do: dec_TeletexPersonalName(data)

  defp decode_disp(:TeletexOrganizationName, data), do: dec_TeletexOrganizationName(data)

  defp decode_disp(:TeletexCommonName, data), do: dec_TeletexCommonName(data)

  defp decode_disp(:CommonName, data), do: dec_CommonName(data)

  defp decode_disp(:ExtensionAttribute, data), do: dec_ExtensionAttribute(data)

  defp decode_disp(:ExtensionAttributes, data), do: dec_ExtensionAttributes(data)

  defp decode_disp(:BuiltInDomainDefinedAttribute, data), do: dec_BuiltInDomainDefinedAttribute(data)

  defp decode_disp(:BuiltInDomainDefinedAttributes, data), do: dec_BuiltInDomainDefinedAttributes(data)

  defp decode_disp(:OrganizationalUnitName, data), do: dec_OrganizationalUnitName(data)

  defp decode_disp(:OrganizationalUnitNames, data), do: dec_OrganizationalUnitNames(data)

  defp decode_disp(:PersonalName, data), do: dec_PersonalName(data)

  defp decode_disp(:NumericUserIdentifier, data), do: dec_NumericUserIdentifier(data)

  defp decode_disp(:OrganizationName, data), do: dec_OrganizationName(data)

  defp decode_disp(:PrivateDomainName, data), do: dec_PrivateDomainName(data)

  defp decode_disp(:TerminalIdentifier, data), do: dec_TerminalIdentifier(data)

  defp decode_disp(:X121Address, data), do: dec_X121Address(data)

  defp decode_disp(:NetworkAddress, data), do: dec_NetworkAddress(data)

  defp decode_disp(:AdministrationDomainName, data), do: dec_AdministrationDomainName(data)

  defp decode_disp(:CountryName, data), do: dec_CountryName(data)

  defp decode_disp(:BuiltInStandardAttributes, data), do: dec_BuiltInStandardAttributes(data)

  defp decode_disp(:ORAddress, data), do: dec_ORAddress(data)

  defp decode_disp(:AlgorithmIdentifier, data), do: dec_AlgorithmIdentifier(data)

  defp decode_disp(:TBSCertList, data), do: dec_TBSCertList(data)

  defp decode_disp(:CertificateList, data), do: dec_CertificateList(data)

  defp decode_disp(:Extension, data), do: dec_Extension(data)

  defp decode_disp(:Extensions, data), do: dec_Extensions(data)

  defp decode_disp(:SubjectPublicKeyInfo, data), do: dec_SubjectPublicKeyInfo(data)

  defp decode_disp(:UniqueIdentifier, data), do: dec_UniqueIdentifier(data)

  defp decode_disp(:Time, data), do: dec_Time(data)

  defp decode_disp(:Validity, data), do: dec_Validity(data)

  defp decode_disp(:CertificateSerialNumber, data), do: dec_CertificateSerialNumber(data)

  defp decode_disp(:VersionPKIX1Explicit88, data), do: dec_VersionPKIX1Explicit88(data)

  defp decode_disp(:TBSCertificate, data), do: dec_TBSCertificate(data)

  defp decode_disp(:Certificate, data), do: dec_Certificate(data)

  defp decode_disp(:DirectoryString, data), do: dec_DirectoryString(data)

  defp decode_disp(:RelativeDistinguishedName, data), do: dec_RelativeDistinguishedName(data)

  defp decode_disp(:DistinguishedName, data), do: dec_DistinguishedName(data)

  defp decode_disp(:RDNSequence, data), do: dec_RDNSequence(data)

  defp decode_disp(:Name, data), do: dec_Name(data)

  defp decode_disp(:EmailAddress, data), do: dec_EmailAddress(data)

  defp decode_disp(:DomainComponent, data), do: dec_DomainComponent(data)

  defp decode_disp(:X520Pseudonym, data), do: dec_X520Pseudonym(data)

  defp decode_disp(:X520SerialNumber, data), do: dec_X520SerialNumber(data)

  defp decode_disp(:X520countryName, data), do: dec_X520countryName(data)

  defp decode_disp(:X520dnQualifier, data), do: dec_X520dnQualifier(data)

  defp decode_disp(:X520Title, data), do: dec_X520Title(data)

  defp decode_disp(:X520OrganizationalUnitName, data), do: dec_X520OrganizationalUnitName(data)

  defp decode_disp(:X520OrganizationName, data), do: dec_X520OrganizationName(data)

  defp decode_disp(:X520StateOrProvinceName, data), do: dec_X520StateOrProvinceName(data)

  defp decode_disp(:X520LocalityName, data), do: dec_X520LocalityName(data)

  defp decode_disp(:X520CommonName, data), do: dec_X520CommonName(data)

  defp decode_disp(:X520name, data), do: dec_X520name(data)

  defp decode_disp(:AttributeTypeAndValue, data), do: dec_AttributeTypeAndValue(data)

  defp decode_disp(:AttributeValue, data), do: dec_AttributeValue(data)

  defp decode_disp(:AttributeType, data), do: dec_AttributeType(data)

  defp decode_disp(:Attribute, data), do: dec_Attribute(data)

  defp decode_disp(:"Extension-Any", data), do: apply(__MODULE__, :"dec_Extension-Any", [data])

  defp decode_disp(:Any, data), do: dec_Any(data)

  defp decode_disp(:Boolean, data), do: dec_Boolean(data)

  defp decode_disp(:ObjId, data), do: dec_ObjId(data)

  defp decode_disp(:OTPExtension, data), do: dec_OTPExtension(data)

  defp decode_disp(:OTPExtensions, data), do: dec_OTPExtensions(data)

  defp decode_disp(:OTPExtensionAttribute, data), do: dec_OTPExtensionAttribute(data)

  defp decode_disp(:OTPExtensionAttributes, data), do: dec_OTPExtensionAttributes(data)

  defp decode_disp(:"OTPCharacteristic-two", data), do: apply(__MODULE__, :"dec_OTPCharacteristic-two", [data])

  defp decode_disp(:OTPFieldID, data), do: dec_OTPFieldID(data)

  defp decode_disp(:"KEA-PublicKey", data), do: apply(__MODULE__, :"dec_KEA-PublicKey", [data])

  defp decode_disp(:DSAParams, data), do: dec_DSAParams(data)

  defp decode_disp(:PublicKeyAlgorithm, data), do: dec_PublicKeyAlgorithm(data)

  defp decode_disp(:"SignatureAlgorithm-Any", data), do: apply(__MODULE__, :"dec_SignatureAlgorithm-Any", [data])

  defp decode_disp(:SignatureAlgorithm, data), do: dec_SignatureAlgorithm(data)

  defp decode_disp(:"OTPSubjectPublicKeyInfo-Any", data), do: apply(__MODULE__, :"dec_OTPSubjectPublicKeyInfo-Any", [data])

  defp decode_disp(:OTPSubjectPublicKeyInfo, data), do: dec_OTPSubjectPublicKeyInfo(data)

  defp decode_disp(:OTPOLDSubjectPublicKeyInfo, data), do: dec_OTPOLDSubjectPublicKeyInfo(data)

  defp decode_disp(:"OTP-emailAddress", data), do: apply(__MODULE__, :"dec_OTP-emailAddress", [data])

  defp decode_disp(:"OTP-X520countryname", data), do: apply(__MODULE__, :"dec_OTP-X520countryname", [data])

  defp decode_disp(:OTPAttributeTypeAndValue, data), do: dec_OTPAttributeTypeAndValue(data)

  defp decode_disp(:OTPTBSCertificate, data), do: dec_OTPTBSCertificate(data)

  defp decode_disp(:OTPCertificate, data), do: dec_OTPCertificate(data)

  defp decode_disp(type, _Data), do: exit({:error, {:asn1, {:undefined_type, type}}})

  defp decode_inc_disp(:CertificateList_tbsCertList, data), do: dec_TBSCertList(data, [16])

  defp decode_inc_disp(:Certificate_tbsCertificate, data), do: dec_TBSCertificate(data, [16])

  defp decode_incomplete2(_Form = 2, tagNo, v, tagMatch, _) do
    {vlist, rest2} = decode_constr_indef_incomplete(tagMatch, v, [])
    {{tagNo, vlist}, rest2}
  end

  defp decode_incomplete2(1, tagNo, v, [tagMatch], rest) when is_list(tagMatch), do: {{tagNo, decode_constructed_incomplete(tagMatch, v)}, rest}

  defp decode_incomplete2(1, tagNo, v, tagMatch, rest), do: {{tagNo, decode_constructed_incomplete(tagMatch, v)}, rest}

  defp decode_incomplete2(0, tagNo, v, _TagMatch, rest), do: {{tagNo, v}, rest}

  defp decode_incomplete_bin(bin) do
    {:ok, rest} = skip_tag(bin)
    {:ok, rest2} = skip_length_and_value(rest)
    incLen = byte_size(bin) - byte_size(rest2)
    <<incBin :: size(incLen)-binary, ret :: binary>> = bin
    {incBin, ret}
  end

  defp decode_integer(tlv, tagIn) do
    bin = match_tags(tlv, tagIn)
    len = byte_size(bin)
    <<int :: size(len)-signed-unit(8)>> = bin
    int
  end

  defp decode_length(<<1 :: 1, 0 :: 7, t :: binary>>), do: {:indefinite, t}

  defp decode_length(<<0 :: 1, length :: 7, t :: binary>>), do: {length, t}

  defp decode_length(<<1 :: 1, lL :: 7, length :: size(lL)-unit(8), t :: binary>>), do: {length, t}

  defp decode_named_bit_string(buffer, namedNumberList, tags) do
    case match_and_collect(buffer, tags) do
      <<0>> ->
        []
      <<unused, bits :: binary>> ->
        bitString = decode_bitstring2(byte_size(bits), unused, bits)
        decode_bitstring_NNL(bitString, namedNumberList)
    end
  end

  defp decode_native_bit_string(buffer, tags) do
    case match_and_collect(buffer, tags) do
      <<0>> ->
        <<>>
      <<unused, bits :: binary>> ->
        size = bit_size(bits) - unused
        <<val :: size(size)-bitstring, _ :: size(unused)-bitstring>> = bits
        val
    end
  end

  defp decode_null(tlv, tags) do
    val = match_tags(tlv, tags)
    case val do
      <<>> ->
        :NULL
      _ ->
        exit({:error, {:asn1, {:decode_null, val}}})
    end
  end

  defp decode_object_identifier(tlv, tags) do
    val = match_tags(tlv, tags)
    [addedObjVal | objVals] = dec_subidentifiers(val, 0, [])
    {val1, val2} = cond do
      addedObjVal < 40 ->
        {0, addedObjVal}
      addedObjVal < 80 ->
        {1, addedObjVal - 40}
      true ->
        {2, addedObjVal - 80}
    end
    list_to_tuple([val1, val2 | objVals])
  end

  defp decode_octet_string(tlv, tagsIn) do
    bin = match_and_collect(tlv, tagsIn)
    :binary.copy(bin)
  end

  defp decode_open_type(tlv, tagIn) do
    case match_tags(tlv, tagIn) do
      bin when is_binary(bin) ->
        {innerTlv, _} = ber_decode_nif(bin)
        innerTlv
      tlvBytes ->
        tlvBytes
    end
  end

  defp decode_open_type_as_binary(tlv, tagIn), do: ber_encode(match_tags(tlv, tagIn))

  defp decode_partial_inc_disp(:Certificate, data), do: apply(__MODULE__, :"dec-inc-Certificate", [data])

  defp decode_partial_inc_disp(:CertificateList, data), do: apply(__MODULE__, :"dec-inc-CertificateList", [data])

  defp decode_partial_incomplete(type, data0, pattern) do
    {data, _RestBin} = decode_primitive_incomplete(pattern, data0)
    try do
      decode_partial_inc_disp(type, data)
    catch
      error -> error
    end
    |> case do
      {:EXIT, {:error, reason}} ->
        {:error, reason}
      {:EXIT, reason} ->
        {:error, {:asn1, reason}}
      result ->
        {:ok, result}
    end
  end

  defp decode_parts_incomplete(<<>>), do: []

  defp decode_parts_incomplete(bin) do
    {:ok, rest} = skip_tag(bin)
    {:ok, rest2} = skip_length_and_value(rest)
    lenPart = byte_size(bin) - byte_size(rest2)
    <<part :: size(lenPart)-binary, restBin :: binary>> = bin
    [part | decode_parts_incomplete(restBin)]
  end

  defp decode_primitive(bin) do
    {form, tagNo, v, rest} = decode_tag_and_length(bin)
    case form do
      1 ->
        {{tagNo, decode_constructed(v)}, rest}
      0 ->
        {{tagNo, v}, rest}
      2 ->
        {vlist, rest2} = decode_constructed_indefinite(v, [])
        {{tagNo, vlist}, rest2}
    end
  end

  defp decode_primitive_incomplete([[:default, tagNo]], bin) do
    case decode_tag_and_length(bin) do
      {form, tagNo, v, rest} ->
        decode_incomplete2(form, tagNo, v, [], rest)
      _ ->
        :asn1_NOVALUE
    end
  end

  defp decode_primitive_incomplete([[:default, tagNo, directives]], bin) do
    case decode_tag_and_length(bin) do
      {form, tagNo, v, rest} ->
        decode_incomplete2(form, tagNo, v, directives, rest)
      _ ->
        :asn1_NOVALUE
    end
  end

  defp decode_primitive_incomplete([[:opt, tagNo]], bin) do
    case decode_tag_and_length(bin) do
      {form, tagNo, v, rest} ->
        decode_incomplete2(form, tagNo, v, [], rest)
      _ ->
        :asn1_NOVALUE
    end
  end

  defp decode_primitive_incomplete([[:opt, tagNo, directives]], bin) do
    case decode_tag_and_length(bin) do
      {form, tagNo, v, rest} ->
        decode_incomplete2(form, tagNo, v, directives, rest)
      _ ->
        :asn1_NOVALUE
    end
  end

  defp decode_primitive_incomplete([[:opt_undec, tag]], bin) do
    case decode_tag_and_length(bin) do
      {_, tag, _, _} ->
        decode_incomplete_bin(bin)
      _ ->
        :asn1_NOVALUE
    end
  end

  defp decode_primitive_incomplete([[:alt_undec, tagNo] | restAlts], bin) do
    case decode_tag_and_length(bin) do
      {_, tagNo, _, _} ->
        decode_incomplete_bin(bin)
      _ ->
        decode_primitive_incomplete(restAlts, bin)
    end
  end

  defp decode_primitive_incomplete([[:alt, tagNo] | restAlts], bin) do
    case decode_tag_and_length(bin) do
      {_Form, tagNo, v, rest} ->
        {{tagNo, v}, rest}
      _ ->
        decode_primitive_incomplete(restAlts, bin)
    end
  end

  defp decode_primitive_incomplete([[:alt, tagNo, directives] | restAlts], bin) do
    case decode_tag_and_length(bin) do
      {form, tagNo, v, rest} ->
        decode_incomplete2(form, tagNo, v, directives, rest)
      _ ->
        decode_primitive_incomplete(restAlts, bin)
    end
  end

  defp decode_primitive_incomplete([[:alt_parts, tagNo]], bin) do
    case decode_tag_and_length(bin) do
      {_Form, tagNo, v, rest} ->
        {{tagNo, v}, rest}
      _ ->
        :asn1_NOVALUE
    end
  end

  defp decode_primitive_incomplete([[:alt_parts, tagNo] | restAlts], bin) do
    case decode_tag_and_length(bin) do
      {_Form, tagNo, v, rest} ->
        {{tagNo, decode_parts_incomplete(v)}, rest}
      _ ->
        decode_primitive_incomplete(restAlts, bin)
    end
  end

  defp decode_primitive_incomplete([[:undec, _TagNo] | _RestTag], bin), do: decode_incomplete_bin(bin)

  defp decode_primitive_incomplete([[:parts, tagNo] | _RestTag], bin) do
    case decode_tag_and_length(bin) do
      {_Form, tagNo, v, rest} ->
        {{tagNo, decode_parts_incomplete(v)}, rest}
      err ->
        {:error, {:asn1, 'tag failure', tagNo, err}}
    end
  end

  defp decode_primitive_incomplete([:mandatory | restTag], bin) do
    {form, tagNo, v, rest} = decode_tag_and_length(bin)
    decode_incomplete2(form, tagNo, v, restTag, rest)
  end

  defp decode_primitive_incomplete([[:mandatory | directives]], bin) do
    {form, tagNo, v, rest} = decode_tag_and_length(bin)
    decode_incomplete2(form, tagNo, v, directives, rest)
  end

  defp decode_primitive_incomplete([], bin), do: decode_primitive(bin)

  defp decode_restricted_string(tlv, tagsIn), do: match_and_collect(tlv, tagsIn)

  defp decode_tag(<<0 :: 1, partialTag :: 7, buffer :: binary>>, tagAck) do
    tagNo = tagAck <<< 7 ||| partialTag
    {tagNo, buffer}
  end

  defp decode_tag(<<_ :: 1, partialTag :: 7, buffer :: binary>>, tagAck) do
    tagAck1 = tagAck <<< 7 ||| partialTag
    decode_tag(buffer, tagAck1)
  end

  defp decode_tag_and_length(<<class :: 2, form :: 1, tagNo :: 5, 0 :: 1, length :: 7, v :: size(length)-binary, restBuffer :: binary>>) when tagNo < 31, do: {form, class <<< 16 ||| tagNo, v, restBuffer}

  defp decode_tag_and_length(<<class :: 2, 1 :: 1, tagNo :: 5, 1 :: 1, 0 :: 7, t :: binary>>) when tagNo < 31, do: {2, class <<< 16 + tagNo, t, <<>>}

  defp decode_tag_and_length(<<class :: 2, form :: 1, tagNo :: 5, 1 :: 1, lL :: 7, length :: size(lL)-unit(8), v :: size(length)-binary, t :: binary>>) when tagNo < 31, do: {form, class <<< 16 ||| tagNo, v, t}

  defp decode_tag_and_length(<<class :: 2, form :: 1, 31 :: 5, 0 :: 1, tagNo :: 7, 0 :: 1, length :: 7, v :: size(length)-binary, restBuffer :: binary>>), do: {form, class <<< 16 ||| tagNo, v, restBuffer}

  defp decode_tag_and_length(<<class :: 2, 1 :: 1, 31 :: 5, 0 :: 1, tagNo :: 7, 1 :: 1, 0 :: 7, t :: binary>>), do: {2, class <<< 16 ||| tagNo, t, <<>>}

  defp decode_tag_and_length(<<class :: 2, form :: 1, 31 :: 5, 0 :: 1, tagNo :: 7, 1 :: 1, lL :: 7, length :: size(lL)-unit(8), v :: size(length)-binary, t :: binary>>), do: {form, class <<< 16 ||| tagNo, v, t}

  defp decode_tag_and_length(<<class :: 2, form :: 1, 31 :: 5, 1 :: 1, tagPart1 :: 7, 0 :: 1, tagPartLast, buffer :: binary>>) do
    tagNo = tagPart1 <<< 7 ||| tagPartLast
    {length, restBuffer} = decode_length(buffer)
    <<v :: size(length)-binary, restBuffer2 :: binary>> = restBuffer
    {form, class <<< 16 ||| tagNo, v, restBuffer2}
  end

  defp decode_tag_and_length(<<class :: 2, form :: 1, 31 :: 5, buffer :: binary>>) do
    {tagNo, buffer1} = decode_tag(buffer, 0)
    {length, restBuffer} = decode_length(buffer1)
    <<v :: size(length)-binary, restBuffer2 :: binary>> = restBuffer
    {form, class <<< 16 ||| tagNo, v, restBuffer2}
  end

  defp decode_universal_string(buffer, tags) do
    bin = match_and_collect(buffer, tags)
    mk_universal_string(binary_to_list(bin))
  end

  defp do_encode_named_bit_string([firstVal | restVal], namedBitList, tagIn) do
    toSetPos = get_all_bitposes([firstVal | restVal], namedBitList, [])
    size = :lists.max(toSetPos) + 1
    bitList = make_and_set_list(size, toSetPos, 0)
    {len, unused, octetList} = encode_bitstring(bitList)
    encode_tags(tagIn, [unused | octetList], len + 1)
  end

  defp dynamicsort_SETOF(listOfEncVal) do
    binL = :lists.map(fn l when is_list(l) ->
        list_to_binary(l)
      b ->
        b
    end, listOfEncVal)
    :lists.sort(binL)
  end

  defp e_object_identifier({:"OBJECT IDENTIFIER", v}), do: e_object_identifier(v)

  defp e_object_identifier(v) when is_tuple(v), do: e_object_identifier(tuple_to_list(v))

  defp e_object_identifier([e1, e2 | tail]) do
    head = 40 * e1 + e2
    {h, lh} = mk_object_val(head)
    {r, lr} = :lists.mapfoldl(&enc_obj_id_tail/2, 0, tail)
    {[h | r], lh + lr}
  end

  defp enc_AAControls(val), do: enc_AAControls(val, [<<48>>])

  defp enc_ACClearAttrs(val), do: enc_ACClearAttrs(val, [<<48>>])

  defp enc_ACClearAttrs_attrs(val, tagIn) do
    {encBytes, encLen} = enc_ACClearAttrs_attrs_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_ACClearAttrs_attrs_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_ACClearAttrs_attrs_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_Attribute(h, [<<48>>])
    enc_ACClearAttrs_attrs_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_AccessDescription(val), do: enc_AccessDescription(val, [<<48>>])

  defp enc_AdministrationDomainName(val), do: enc_AdministrationDomainName(val, [<<98>>])

  defp enc_Algorithm(val), do: enc_Algorithm(val, [<<48>>])

  defp enc_AlgorithmIdentifier(val), do: enc_AlgorithmIdentifier(val, [<<48>>])

  defp enc_AlgorithmNull(val), do: enc_AlgorithmNull(val, [<<48>>])

  defp enc_AnotherName(val), do: enc_AnotherName(val, [<<48>>])

  defp enc_Any(val), do: enc_Any(val, [])

  defp enc_AttCertIssuer(val), do: enc_AttCertIssuer(val, [])

  defp enc_AttCertValidityPeriod(val), do: enc_AttCertValidityPeriod(val, [<<48>>])

  defp enc_AttCertVersion(val), do: enc_AttCertVersion(val, [<<2>>])

  defp enc_AttrSpec(val), do: enc_AttrSpec(val, [<<48>>])

  defp enc_AttrSpec_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_AttrSpec_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = encode_object_identifier(h, [<<6>>])
    enc_AttrSpec_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_Attribute(val), do: enc_Attribute(val, [<<48>>])

  defp enc_AttributeCertificate(val), do: enc_AttributeCertificate(val, [<<48>>])

  defp enc_AttributeCertificateInfo(val), do: enc_AttributeCertificateInfo(val, [<<48>>])

  defp enc_AttributeCertificateInfo_attributes(val, tagIn) do
    {encBytes, encLen} = enc_AttributeCertificateInfo_attributes_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_AttributeCertificateInfo_attributes_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_AttributeCertificateInfo_attributes_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_Attribute(h, [<<48>>])
    enc_AttributeCertificateInfo_attributes_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_AttributeType(val), do: enc_AttributeType(val, [<<6>>])

  defp enc_AttributeTypeAndValue(val), do: enc_AttributeTypeAndValue(val, [<<48>>])

  defp enc_AttributeValue(val), do: enc_AttributeValue(val, [])

  defp enc_Attribute_values(val, tagIn) do
    {encBytes, encLen} = enc_Attribute_values_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_Attribute_values_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_Attribute_values_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_AttributeValue(h, [])
    enc_Attribute_values_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_AuthorityInfoAccessSyntax(val), do: enc_AuthorityInfoAccessSyntax(val, [<<48>>])

  defp enc_AuthorityInfoAccessSyntax_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_AuthorityInfoAccessSyntax_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_AccessDescription(h, [<<48>>])
    enc_AuthorityInfoAccessSyntax_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_AuthorityKeyIdentifier(val), do: enc_AuthorityKeyIdentifier(val, [<<48>>])

  defp enc_BaseCRLNumber(val), do: enc_BaseCRLNumber(val, [<<2>>])

  defp enc_BaseDistance(val), do: enc_BaseDistance(val, [<<2>>])

  defp enc_BasicConstraints(val), do: enc_BasicConstraints(val, [<<48>>])

  defp enc_Boolean(val), do: enc_Boolean(val, [<<1>>])

  defp enc_BuiltInDomainDefinedAttribute(val), do: enc_BuiltInDomainDefinedAttribute(val, [<<48>>])

  defp enc_BuiltInDomainDefinedAttributes(val), do: enc_BuiltInDomainDefinedAttributes(val, [<<48>>])

  defp enc_BuiltInDomainDefinedAttributes_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_BuiltInDomainDefinedAttributes_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_BuiltInDomainDefinedAttribute(h, [<<48>>])
    enc_BuiltInDomainDefinedAttributes_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_BuiltInStandardAttributes(val), do: enc_BuiltInStandardAttributes(val, [<<48>>])

  defp enc_CPSuri(val), do: enc_CPSuri(val, [<<22>>])

  defp enc_CRLDistributionPoints(val), do: enc_CRLDistributionPoints(val, [<<48>>])

  defp enc_CRLDistributionPoints_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_CRLDistributionPoints_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_DistributionPoint(h, [<<48>>])
    enc_CRLDistributionPoints_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_CRLNumber(val), do: enc_CRLNumber(val, [<<2>>])

  defp enc_CRLReason(val), do: enc_CRLReason(val, [<<10>>])

  defp enc_CRLSequence(val), do: enc_CRLSequence(val, [<<48>>])

  defp enc_CRLSequence_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_CRLSequence_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_CertificateList(h, [<<48>>])
    enc_CRLSequence_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_CertPolicyId(val), do: enc_CertPolicyId(val, [<<6>>])

  defp enc_Certificate(val), do: enc_Certificate(val, [<<48>>])

  defp enc_CertificateIssuer(val), do: enc_CertificateIssuer(val, [<<48>>])

  defp enc_CertificateList(val), do: enc_CertificateList(val, [<<48>>])

  defp enc_CertificatePolicies(val), do: enc_CertificatePolicies(val, [<<48>>])

  defp enc_CertificatePolicies_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_CertificatePolicies_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_PolicyInformation(h, [<<48>>])
    enc_CertificatePolicies_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_CertificateRevocationLists(val), do: enc_CertificateRevocationLists(val, [<<49>>])

  defp enc_CertificateRevocationLists_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_CertificateRevocationLists_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_CertificateList(h, [<<48>>])
    enc_CertificateRevocationLists_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_CertificateSerialNumber(val), do: enc_CertificateSerialNumber(val, [<<2>>])

  defp enc_Certificates(val), do: enc_Certificates(val, [<<48>>])

  defp enc_Certificates_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_Certificates_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_Certificate(h, [<<48>>])
    enc_Certificates_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_CertificationRequest(val), do: enc_CertificationRequest(val, [<<48>>])

  defp enc_CertificationRequestInfo(val), do: enc_CertificationRequestInfo(val, [<<48>>])

  defp enc_CertificationRequestInfo_attributes(val, tagIn) do
    {encBytes, encLen} = enc_CertificationRequestInfo_attributes_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp unquote(:"enc_CertificationRequestInfo_attributes_AttributePKCS-10")(val, tagIn) do
    {_, cindex1, cindex2} = val
    objtype = :"OTP-PUB-KEY".getenc_internal_object_set_argument_12(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = apply(__MODULE__, :"enc_CertificationRequestInfo_attributes_AttributePKCS-10_values", [cindex2, [<<49>>], objtype])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp unquote(:"enc_CertificationRequestInfo_attributes_AttributePKCS-10_values")(val, tagIn, objFun) do
    {encBytes, encLen} = apply(__MODULE__, :"enc_CertificationRequestInfo_attributes_AttributePKCS-10_values_components", [val, objFun, [], 0])
    encode_tags(tagIn, encBytes, encLen)
  end

  defp unquote(:"enc_CertificationRequestInfo_attributes_AttributePKCS-10_values_components")([], _, accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp unquote(:"enc_CertificationRequestInfo_attributes_AttributePKCS-10_values_components")([h | t], objFun, accBytes, accLen) do
    {tmpBytes, _} = objFun.(:Type, h, [])
    {encBytes, encLen} = encode_open_type(tmpBytes, [])
    apply(__MODULE__, :"enc_CertificationRequestInfo_attributes_AttributePKCS-10_values_components", [t, objFun, [encBytes | accBytes], accLen + encLen])
  end

  defp enc_CertificationRequestInfo_attributes_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_CertificationRequestInfo_attributes_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = apply(__MODULE__, :"enc_CertificationRequestInfo_attributes_AttributePKCS-10", [h, [<<48>>]])
    enc_CertificationRequestInfo_attributes_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_CertificationRequestInfo_subjectPKInfo(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = enc_CertificationRequestInfo_subjectPKInfo_algorithm(cindex1, [<<48>>])
    {encBytes2, encLen2} = encode_unnamed_bit_string(cindex2, [<<3>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp enc_CertificationRequestInfo_subjectPKInfo_algorithm(val, tagIn) do
    {_, cindex1, cindex2} = val
    objalgorithm = :"OTP-PUB-KEY".getenc_internal_object_set_argument_10(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        {tmpBytes2, _} = objalgorithm.(:Type, cindex2, [])
        encode_open_type(tmpBytes2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp enc_CertificationRequest_signatureAlgorithm(val, tagIn) do
    {_, cindex1, cindex2} = val
    objalgorithm = :"OTP-PUB-KEY".getenc_internal_object_set_argument_13(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        {tmpBytes2, _} = objalgorithm.(:Type, cindex2, [])
        encode_open_type(tmpBytes2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp unquote(:"enc_Characteristic-two")(val), do: apply(__MODULE__, :"enc_Characteristic-two", [val, [<<48>>]])

  defp enc_ClassList(val), do: enc_ClassList(val, [<<3>>])

  defp enc_Clearance(val), do: enc_Clearance(val, [<<48>>])

  defp enc_Clearance_securityCategories(val, tagIn) do
    {encBytes, encLen} = enc_Clearance_securityCategories_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_Clearance_securityCategories_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_Clearance_securityCategories_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_SecurityCategory(h, [<<48>>])
    enc_Clearance_securityCategories_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_CommonName(val), do: enc_CommonName(val, [<<19>>])

  defp enc_ContentEncryptionAlgorithmIdentifier(val), do: enc_ContentEncryptionAlgorithmIdentifier(val, [<<48>>])

  defp enc_ContentInfo(val), do: enc_ContentInfo(val, [<<48>>])

  defp enc_ContentType(val), do: enc_ContentType(val, [<<6>>])

  defp enc_CountryName(val), do: enc_CountryName(val, [<<97>>])

  defp enc_Curve(val), do: enc_Curve(val, [<<48>>])

  defp enc_DHParameter(val), do: enc_DHParameter(val, [<<48>>])

  defp enc_DHPublicKey(val), do: enc_DHPublicKey(val, [<<2>>])

  defp enc_DSAParams(val), do: enc_DSAParams(val, [])

  defp enc_DSAPrivateKey(val), do: enc_DSAPrivateKey(val, [<<48>>])

  defp enc_DSAPublicKey(val), do: enc_DSAPublicKey(val, [<<2>>])

  defp enc_Data(val), do: enc_Data(val, [<<4>>])

  defp enc_Digest(val), do: enc_Digest(val, [<<4>>])

  defp enc_DigestAlgorithmIdentifier(val), do: enc_DigestAlgorithmIdentifier(val, [<<48>>])

  defp enc_DigestAlgorithmIdentifiers(val), do: enc_DigestAlgorithmIdentifiers(val, [])

  defp enc_DigestAlgorithmIdentifiers_daSequence(val, tagIn) do
    {encBytes, encLen} = enc_DigestAlgorithmIdentifiers_daSequence_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_DigestAlgorithmIdentifiers_daSequence_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_DigestAlgorithmIdentifiers_daSequence_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_DigestAlgorithmIdentifier(h, [<<48>>])
    enc_DigestAlgorithmIdentifiers_daSequence_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_DigestAlgorithmIdentifiers_daSet(val, tagIn) do
    {encBytes, encLen} = enc_DigestAlgorithmIdentifiers_daSet_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_DigestAlgorithmIdentifiers_daSet_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_DigestAlgorithmIdentifiers_daSet_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_DigestAlgorithmIdentifier(h, [<<48>>])
    enc_DigestAlgorithmIdentifiers_daSet_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_DigestEncryptionAlgorithmIdentifier(val), do: enc_DigestEncryptionAlgorithmIdentifier(val, [<<48>>])

  defp enc_DigestInfoNull(val), do: enc_DigestInfoNull(val, [<<48>>])

  defp unquote(:"enc_DigestInfoPKCS-1")(val), do: apply(__MODULE__, :"enc_DigestInfoPKCS-1", [val, [<<48>>]])

  defp unquote(:"enc_DigestInfoPKCS-7")(val), do: apply(__MODULE__, :"enc_DigestInfoPKCS-7", [val, [<<48>>]])

  defp enc_DigestedData(val), do: enc_DigestedData(val, [<<48>>])

  defp enc_DirectoryString(val), do: enc_DirectoryString(val, [])

  defp enc_DisplayText(val), do: enc_DisplayText(val, [])

  defp enc_DistinguishedName(val), do: enc_DistinguishedName(val, [<<48>>])

  defp enc_DistributionPoint(val), do: enc_DistributionPoint(val, [<<48>>])

  defp enc_DistributionPointName(val), do: enc_DistributionPointName(val, [])

  defp enc_DomainComponent(val), do: enc_DomainComponent(val, [<<22>>])

  defp enc_DomainParameters(val), do: enc_DomainParameters(val, [<<48>>])

  defp unquote(:"enc_Dss-Parms")(val), do: apply(__MODULE__, :"enc_Dss-Parms", [val, [<<48>>]])

  defp unquote(:"enc_Dss-Sig-Value")(val), do: apply(__MODULE__, :"enc_Dss-Sig-Value", [val, [<<48>>]])

  defp unquote(:"enc_ECDSA-Sig-Value")(val), do: apply(__MODULE__, :"enc_ECDSA-Sig-Value", [val, [<<48>>]])

  defp enc_ECPVer(val), do: enc_ECPVer(val, [<<2>>])

  defp enc_ECParameters(val), do: enc_ECParameters(val, [<<48>>])

  defp enc_ECPoint(val), do: enc_ECPoint(val, [<<4>>])

  defp enc_ECPrivateKey(val), do: enc_ECPrivateKey(val, [<<48>>])

  defp enc_EDIPartyName(val), do: enc_EDIPartyName(val, [<<48>>])

  defp enc_EcpkParameters(val), do: enc_EcpkParameters(val, [])

  defp enc_EmailAddress(val), do: enc_EmailAddress(val, [<<22>>])

  defp enc_EncryptedContent(val), do: enc_EncryptedContent(val, [<<4>>])

  defp enc_EncryptedContentInfo(val), do: enc_EncryptedContentInfo(val, [<<48>>])

  defp enc_EncryptedData(val), do: enc_EncryptedData(val, [<<48>>])

  defp enc_EncryptedDigest(val), do: enc_EncryptedDigest(val, [<<4>>])

  defp enc_EncryptedKey(val), do: enc_EncryptedKey(val, [<<4>>])

  defp enc_EnvelopedData(val), do: enc_EnvelopedData(val, [<<48>>])

  defp enc_ExtKeyUsageSyntax(val), do: enc_ExtKeyUsageSyntax(val, [<<48>>])

  defp enc_ExtKeyUsageSyntax_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_ExtKeyUsageSyntax_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = encode_object_identifier(h, [<<6>>])
    enc_ExtKeyUsageSyntax_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_ExtendedCertificate(val), do: enc_ExtendedCertificate(val, [<<48>>])

  defp enc_ExtendedCertificateOrCertificate(val), do: enc_ExtendedCertificateOrCertificate(val, [])

  defp enc_ExtendedCertificatesAndCertificates(val), do: enc_ExtendedCertificatesAndCertificates(val, [<<49>>])

  defp enc_ExtendedCertificatesAndCertificates_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_ExtendedCertificatesAndCertificates_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_ExtendedCertificateOrCertificate(h, [])
    enc_ExtendedCertificatesAndCertificates_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_ExtendedNetworkAddress(val), do: enc_ExtendedNetworkAddress(val, [])

  defp unquote(:"enc_ExtendedNetworkAddress_e163-4-address")(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_restricted_string(cindex1, [<<128>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        encode_restricted_string(cindex2, [<<129>>])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp enc_Extension(val), do: enc_Extension(val, [<<48>>])

  defp unquote(:"enc_Extension-Any")(val), do: apply(__MODULE__, :"enc_Extension-Any", [val, [<<48>>]])

  defp enc_ExtensionAttribute(val), do: enc_ExtensionAttribute(val, [<<48>>])

  defp enc_ExtensionAttributes(val), do: enc_ExtensionAttributes(val, [<<49>>])

  defp enc_ExtensionAttributes_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_ExtensionAttributes_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_ExtensionAttribute(h, [<<48>>])
    enc_ExtensionAttributes_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_ExtensionORAddressComponents(val), do: enc_ExtensionORAddressComponents(val, [<<49>>])

  defp enc_ExtensionPhysicalDeliveryAddressComponents(val), do: enc_ExtensionPhysicalDeliveryAddressComponents(val, [<<49>>])

  defp enc_ExtensionRequest(val), do: enc_ExtensionRequest(val, [<<48>>])

  defp enc_Extensions(val), do: enc_Extensions(val, [<<48>>])

  defp enc_Extensions_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_Extensions_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_Extension(h, [<<48>>])
    enc_Extensions_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_FieldElement(val), do: enc_FieldElement(val, [<<4>>])

  defp enc_FieldID(val), do: enc_FieldID(val, [<<48>>])

  defp enc_FreshestCRL(val), do: enc_FreshestCRL(val, [<<48>>])

  defp enc_GeneralName(val), do: enc_GeneralName(val, [])

  defp enc_GeneralNames(val), do: enc_GeneralNames(val, [<<48>>])

  defp enc_GeneralNames_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_GeneralNames_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_GeneralName(h, [])
    enc_GeneralNames_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_GeneralSubtree(val), do: enc_GeneralSubtree(val, [<<48>>])

  defp enc_GeneralSubtrees(val), do: enc_GeneralSubtrees(val, [<<48>>])

  defp enc_GeneralSubtrees_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_GeneralSubtrees_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_GeneralSubtree(h, [<<48>>])
    enc_GeneralSubtrees_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_HoldInstructionCode(val), do: enc_HoldInstructionCode(val, [<<6>>])

  defp enc_Holder(val), do: enc_Holder(val, [<<48>>])

  defp enc_IetfAttrSyntax(val), do: enc_IetfAttrSyntax(val, [<<48>>])

  defp enc_IetfAttrSyntax_values(val, tagIn) do
    {encBytes, encLen} = enc_IetfAttrSyntax_values_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_IetfAttrSyntax_values_SEQOF(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :octets ->
        encode_restricted_string(element(2, val), [<<4>>])
      :oid ->
        encode_object_identifier(element(2, val), [<<6>>])
      :string ->
        encode_UTF8_string(element(2, val), [<<12>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_IetfAttrSyntax_values_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_IetfAttrSyntax_values_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_IetfAttrSyntax_values_SEQOF(h, [])
    enc_IetfAttrSyntax_values_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_InhibitAnyPolicy(val), do: enc_InhibitAnyPolicy(val, [<<2>>])

  defp enc_InvalidityDate(val), do: enc_InvalidityDate(val, [<<24>>])

  defp enc_IssuerAltName(val), do: enc_IssuerAltName(val, [<<48>>])

  defp enc_IssuerAndSerialNumber(val), do: enc_IssuerAndSerialNumber(val, [<<48>>])

  defp enc_IssuerSerial(val), do: enc_IssuerSerial(val, [<<48>>])

  defp enc_IssuingDistributionPoint(val), do: enc_IssuingDistributionPoint(val, [<<48>>])

  defp unquote(:"enc_KEA-Parms-Id")(val), do: apply(__MODULE__, :"enc_KEA-Parms-Id", [val, [<<4>>]])

  defp unquote(:"enc_KEA-PublicKey")(val), do: apply(__MODULE__, :"enc_KEA-PublicKey", [val, [<<2>>]])

  defp enc_KeyEncryptionAlgorithmIdentifier(val), do: enc_KeyEncryptionAlgorithmIdentifier(val, [<<48>>])

  defp enc_KeyIdentifier(val), do: enc_KeyIdentifier(val, [<<4>>])

  defp enc_KeyPurposeId(val), do: enc_KeyPurposeId(val, [<<6>>])

  defp enc_KeyUsage(val), do: enc_KeyUsage(val, [<<3>>])

  defp enc_LocalPostalAttributes(val), do: enc_LocalPostalAttributes(val, [<<49>>])

  defp enc_MessageDigest(val), do: enc_MessageDigest(val, [<<4>>])

  defp enc_Name(val), do: enc_Name(val, [])

  defp enc_NameConstraints(val), do: enc_NameConstraints(val, [<<48>>])

  defp enc_NetworkAddress(val), do: enc_NetworkAddress(val, [<<18>>])

  defp enc_NoticeReference(val), do: enc_NoticeReference(val, [<<48>>])

  defp enc_NoticeReference_noticeNumbers(val, tagIn) do
    {encBytes, encLen} = enc_NoticeReference_noticeNumbers_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_NoticeReference_noticeNumbers_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_NoticeReference_noticeNumbers_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = encode_integer(h, [<<2>>])
    enc_NoticeReference_noticeNumbers_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_NumericUserIdentifier(val), do: enc_NumericUserIdentifier(val, [<<18>>])

  defp enc_ORAddress(val), do: enc_ORAddress(val, [<<48>>])

  defp unquote(:"enc_OTP-X520countryname")(val), do: apply(__MODULE__, :"enc_OTP-X520countryname", [val, []])

  defp unquote(:"enc_OTP-emailAddress")(val), do: apply(__MODULE__, :"enc_OTP-emailAddress", [val, []])

  defp enc_OTPAttributeTypeAndValue(val), do: enc_OTPAttributeTypeAndValue(val, [<<48>>])

  defp enc_OTPCertificate(val), do: enc_OTPCertificate(val, [<<48>>])

  defp unquote(:"enc_OTPCharacteristic-two")(val), do: apply(__MODULE__, :"enc_OTPCharacteristic-two", [val, [<<48>>]])

  defp enc_OTPExtension(val), do: enc_OTPExtension(val, [<<48>>])

  defp enc_OTPExtensionAttribute(val), do: enc_OTPExtensionAttribute(val, [<<48>>])

  defp enc_OTPExtensionAttributes(val), do: enc_OTPExtensionAttributes(val, [<<49>>])

  defp enc_OTPExtensionAttributes_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_OTPExtensionAttributes_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_ExtensionAttribute(h, [<<48>>])
    enc_OTPExtensionAttributes_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_OTPExtensions(val), do: enc_OTPExtensions(val, [<<48>>])

  defp enc_OTPExtensions_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_OTPExtensions_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_Extension(h, [<<48>>])
    enc_OTPExtensions_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_OTPFieldID(val), do: enc_OTPFieldID(val, [<<48>>])

  defp enc_OTPOLDSubjectPublicKeyInfo(val), do: enc_OTPOLDSubjectPublicKeyInfo(val, [<<48>>])

  defp enc_OTPOLDSubjectPublicKeyInfo_algorithm(val, tagIn) do
    {_, cindex1, cindex2} = val
    objalgo = :"OTP-PUB-KEY".getenc_SupportedPublicKeyAlgorithms(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = case cindex2 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        {tmpBytes2, _} = objalgo.(:Type, cindex2, [])
        encode_open_type(tmpBytes2, [])
    end
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp enc_OTPSubjectPublicKeyInfo(val), do: enc_OTPSubjectPublicKeyInfo(val, [<<48>>])

  defp unquote(:"enc_OTPSubjectPublicKeyInfo-Any")(val), do: apply(__MODULE__, :"enc_OTPSubjectPublicKeyInfo-Any", [val, [<<48>>]])

  defp enc_OTPTBSCertificate(val), do: enc_OTPTBSCertificate(val, [<<48>>])

  defp enc_ObjId(val), do: enc_ObjId(val, [<<6>>])

  defp enc_ObjectDigestInfo(val), do: enc_ObjectDigestInfo(val, [<<48>>])

  defp enc_OrganizationName(val), do: enc_OrganizationName(val, [<<19>>])

  defp enc_OrganizationalUnitName(val), do: enc_OrganizationalUnitName(val, [<<19>>])

  defp enc_OrganizationalUnitNames(val), do: enc_OrganizationalUnitNames(val, [<<48>>])

  defp enc_OrganizationalUnitNames_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_OrganizationalUnitNames_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = encode_restricted_string(h, [<<19>>])
    enc_OrganizationalUnitNames_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_OtherPrimeInfo(val), do: enc_OtherPrimeInfo(val, [<<48>>])

  defp enc_OtherPrimeInfos(val), do: enc_OtherPrimeInfos(val, [<<48>>])

  defp enc_OtherPrimeInfos_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_OtherPrimeInfos_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_OtherPrimeInfo(h, [<<48>>])
    enc_OtherPrimeInfos_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_PDSName(val), do: enc_PDSName(val, [<<19>>])

  defp enc_PDSParameter(val), do: enc_PDSParameter(val, [<<49>>])

  defp enc_Pentanomial(val), do: enc_Pentanomial(val, [<<48>>])

  defp enc_PersonalName(val), do: enc_PersonalName(val, [<<49>>])

  defp enc_PhysicalDeliveryCountryName(val), do: enc_PhysicalDeliveryCountryName(val, [])

  defp enc_PhysicalDeliveryOfficeName(val), do: enc_PhysicalDeliveryOfficeName(val, [<<49>>])

  defp enc_PhysicalDeliveryOfficeNumber(val), do: enc_PhysicalDeliveryOfficeNumber(val, [<<49>>])

  defp enc_PhysicalDeliveryOrganizationName(val), do: enc_PhysicalDeliveryOrganizationName(val, [<<49>>])

  defp enc_PhysicalDeliveryPersonalName(val), do: enc_PhysicalDeliveryPersonalName(val, [<<49>>])

  defp enc_PolicyConstraints(val), do: enc_PolicyConstraints(val, [<<48>>])

  defp enc_PolicyInformation(val), do: enc_PolicyInformation(val, [<<48>>])

  defp enc_PolicyInformation_policyQualifiers(val, tagIn) do
    {encBytes, encLen} = enc_PolicyInformation_policyQualifiers_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_PolicyInformation_policyQualifiers_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_PolicyInformation_policyQualifiers_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_PolicyQualifierInfo(h, [<<48>>])
    enc_PolicyInformation_policyQualifiers_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_PolicyMappings(val), do: enc_PolicyMappings(val, [<<48>>])

  defp enc_PolicyMappings_SEQOF(val, tagIn) do
    {_, cindex1, cindex2} = val
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = encode_object_identifier(cindex2, [<<6>>])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp enc_PolicyMappings_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_PolicyMappings_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_PolicyMappings_SEQOF(h, [<<48>>])
    enc_PolicyMappings_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_PolicyQualifierId(val), do: enc_PolicyQualifierId(val, [<<6>>])

  defp enc_PolicyQualifierInfo(val), do: enc_PolicyQualifierInfo(val, [<<48>>])

  defp enc_PostOfficeBoxAddress(val), do: enc_PostOfficeBoxAddress(val, [<<49>>])

  defp enc_PostalCode(val), do: enc_PostalCode(val, [])

  defp enc_PosteRestanteAddress(val), do: enc_PosteRestanteAddress(val, [<<49>>])

  defp enc_PresentationAddress(val), do: enc_PresentationAddress(val, [<<48>>])

  defp enc_PresentationAddress_nAddresses(val, tagIn) do
    {encBytes, encLen} = enc_PresentationAddress_nAddresses_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_PresentationAddress_nAddresses_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_PresentationAddress_nAddresses_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = encode_restricted_string(h, [<<4>>])
    enc_PresentationAddress_nAddresses_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp unquote(:"enc_Prime-p")(val), do: apply(__MODULE__, :"enc_Prime-p", [val, [<<2>>]])

  defp enc_PrivateDomainName(val), do: enc_PrivateDomainName(val, [])

  defp enc_PrivateKeyUsagePeriod(val), do: enc_PrivateKeyUsagePeriod(val, [<<48>>])

  defp enc_ProxyInfo(val), do: enc_ProxyInfo(val, [<<48>>])

  defp enc_ProxyInfo_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_ProxyInfo_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_Targets(h, [<<48>>])
    enc_ProxyInfo_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_PublicKeyAlgorithm(val), do: enc_PublicKeyAlgorithm(val, [<<48>>])

  defp enc_RDNSequence(val), do: enc_RDNSequence(val, [<<48>>])

  defp enc_RDNSequence_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_RDNSequence_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_RelativeDistinguishedName(h, [<<49>>])
    enc_RDNSequence_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_RSAPrivateKey(val), do: enc_RSAPrivateKey(val, [<<48>>])

  defp enc_RSAPublicKey(val), do: enc_RSAPublicKey(val, [<<48>>])

  defp unquote(:"enc_RSASSA-PSS-params")(val), do: apply(__MODULE__, :"enc_RSASSA-PSS-params", [val, [<<48>>]])

  defp enc_ReasonFlags(val), do: enc_ReasonFlags(val, [<<3>>])

  defp enc_RecipientInfo(val), do: enc_RecipientInfo(val, [<<48>>])

  defp enc_RecipientInfos(val), do: enc_RecipientInfos(val, [])

  defp enc_RecipientInfos_riSequence(val, tagIn) do
    {encBytes, encLen} = enc_RecipientInfos_riSequence_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_RecipientInfos_riSequence_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_RecipientInfos_riSequence_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_RecipientInfo(h, [<<48>>])
    enc_RecipientInfos_riSequence_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_RecipientInfos_riSet(val, tagIn) do
    {encBytes, encLen} = enc_RecipientInfos_riSet_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_RecipientInfos_riSet_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_RecipientInfos_riSet_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_RecipientInfo(h, [<<48>>])
    enc_RecipientInfos_riSet_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_RelativeDistinguishedName(val), do: enc_RelativeDistinguishedName(val, [<<49>>])

  defp enc_RelativeDistinguishedName_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_RelativeDistinguishedName_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_AttributeTypeAndValue(h, [<<48>>])
    enc_RelativeDistinguishedName_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_RoleSyntax(val), do: enc_RoleSyntax(val, [<<48>>])

  defp enc_SecurityCategory(val), do: enc_SecurityCategory(val, [<<48>>])

  defp enc_SignatureAlgorithm(val), do: enc_SignatureAlgorithm(val, [<<48>>])

  defp unquote(:"enc_SignatureAlgorithm-Any")(val), do: apply(__MODULE__, :"enc_SignatureAlgorithm-Any", [val, [<<48>>]])

  defp enc_SignedAndEnvelopedData(val), do: enc_SignedAndEnvelopedData(val, [<<48>>])

  defp enc_SignedAndEnvelopedData_certificates(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :certSet ->
        enc_ExtendedCertificatesAndCertificates(element(2, val), [<<160>>])
      :certSequence ->
        enc_Certificates(element(2, val), [<<162>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_SignedAndEnvelopedData_crls(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :crlSet ->
        enc_CertificateRevocationLists(element(2, val), [<<161>>])
      :crlSequence ->
        enc_CRLSequence(element(2, val), [<<163>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_SignedData(val), do: enc_SignedData(val, [<<48>>])

  defp enc_SignedData_certificates(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :certSet ->
        enc_ExtendedCertificatesAndCertificates(element(2, val), [<<160>>])
      :certSequence ->
        enc_Certificates(element(2, val), [<<162>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_SignedData_crls(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :crlSet ->
        enc_CertificateRevocationLists(element(2, val), [<<161>>])
      :crlSequence ->
        enc_CRLSequence(element(2, val), [<<163>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_SignerInfo(val), do: enc_SignerInfo(val, [<<48>>])

  defp enc_SignerInfoAuthenticatedAttributes(val), do: enc_SignerInfoAuthenticatedAttributes(val, [])

  defp enc_SignerInfoAuthenticatedAttributes_aaSequence(val, tagIn) do
    {encBytes, encLen} = enc_SignerInfoAuthenticatedAttributes_aaSequence_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp unquote(:"enc_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7")(val, tagIn) do
    {_, cindex1, cindex2} = val
    objtype = :"OTP-PUB-KEY".getenc_internal_object_set_argument_5(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = apply(__MODULE__, :"enc_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7_values", [cindex2, [<<49>>], objtype])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp unquote(:"enc_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7_values")(val, tagIn, objFun) do
    {encBytes, encLen} = apply(__MODULE__, :"enc_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7_values_components", [val, objFun, [], 0])
    encode_tags(tagIn, encBytes, encLen)
  end

  defp unquote(:"enc_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7_values_components")([], _, accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp unquote(:"enc_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7_values_components")([h | t], objFun, accBytes, accLen) do
    {tmpBytes, _} = objFun.(:Type, h, [])
    {encBytes, encLen} = encode_open_type(tmpBytes, [])
    apply(__MODULE__, :"enc_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7_values_components", [t, objFun, [encBytes | accBytes], accLen + encLen])
  end

  defp enc_SignerInfoAuthenticatedAttributes_aaSequence_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_SignerInfoAuthenticatedAttributes_aaSequence_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = apply(__MODULE__, :"enc_SignerInfoAuthenticatedAttributes_aaSequence_AttributePKCS-7", [h, [<<48>>]])
    enc_SignerInfoAuthenticatedAttributes_aaSequence_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_SignerInfoAuthenticatedAttributes_aaSet(val, tagIn) do
    {encBytes, encLen} = enc_SignerInfoAuthenticatedAttributes_aaSet_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp unquote(:"enc_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7")(val, tagIn) do
    {_, cindex1, cindex2} = val
    objtype = :"OTP-PUB-KEY".getenc_internal_object_set_argument_4(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = apply(__MODULE__, :"enc_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7_values", [cindex2, [<<49>>], objtype])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp unquote(:"enc_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7_values")(val, tagIn, objFun) do
    {encBytes, encLen} = apply(__MODULE__, :"enc_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7_values_components", [val, objFun, [], 0])
    encode_tags(tagIn, encBytes, encLen)
  end

  defp unquote(:"enc_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7_values_components")([], _, accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp unquote(:"enc_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7_values_components")([h | t], objFun, accBytes, accLen) do
    {tmpBytes, _} = objFun.(:Type, h, [])
    {encBytes, encLen} = encode_open_type(tmpBytes, [])
    apply(__MODULE__, :"enc_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7_values_components", [t, objFun, [encBytes | accBytes], accLen + encLen])
  end

  defp enc_SignerInfoAuthenticatedAttributes_aaSet_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_SignerInfoAuthenticatedAttributes_aaSet_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = apply(__MODULE__, :"enc_SignerInfoAuthenticatedAttributes_aaSet_AttributePKCS-7", [h, [<<48>>]])
    enc_SignerInfoAuthenticatedAttributes_aaSet_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_SignerInfo_unauthenticatedAttributes(val, tagIn) do
    {encBytes, encLen} = case element(1, val) do
      :uaSet ->
        enc_SignerInfo_unauthenticatedAttributes_uaSet(element(2, val), [<<161>>])
      :uaSequence ->
        enc_SignerInfo_unauthenticatedAttributes_uaSequence(element(2, val), [<<163>>])
      erlangVariableElse ->
        exit({:error, {:asn1, {:invalid_choice_type, erlangVariableElse}}})
    end
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_SignerInfo_unauthenticatedAttributes_uaSequence(val, tagIn) do
    {encBytes, encLen} = enc_SignerInfo_unauthenticatedAttributes_uaSequence_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp unquote(:"enc_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7")(val, tagIn) do
    {_, cindex1, cindex2} = val
    objtype = :"OTP-PUB-KEY".getenc_internal_object_set_argument_8(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = apply(__MODULE__, :"enc_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7_values", [cindex2, [<<49>>], objtype])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp unquote(:"enc_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7_values")(val, tagIn, objFun) do
    {encBytes, encLen} = apply(__MODULE__, :"enc_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7_values_components", [val, objFun, [], 0])
    encode_tags(tagIn, encBytes, encLen)
  end

  defp unquote(:"enc_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7_values_components")([], _, accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp unquote(:"enc_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7_values_components")([h | t], objFun, accBytes, accLen) do
    {tmpBytes, _} = objFun.(:Type, h, [])
    {encBytes, encLen} = encode_open_type(tmpBytes, [])
    apply(__MODULE__, :"enc_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7_values_components", [t, objFun, [encBytes | accBytes], accLen + encLen])
  end

  defp enc_SignerInfo_unauthenticatedAttributes_uaSequence_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_SignerInfo_unauthenticatedAttributes_uaSequence_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = apply(__MODULE__, :"enc_SignerInfo_unauthenticatedAttributes_uaSequence_AttributePKCS-7", [h, [<<48>>]])
    enc_SignerInfo_unauthenticatedAttributes_uaSequence_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_SignerInfo_unauthenticatedAttributes_uaSet(val, tagIn) do
    {encBytes, encLen} = enc_SignerInfo_unauthenticatedAttributes_uaSet_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp unquote(:"enc_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7")(val, tagIn) do
    {_, cindex1, cindex2} = val
    objtype = :"OTP-PUB-KEY".getenc_internal_object_set_argument_7(cindex1)
    {encBytes1, encLen1} = encode_object_identifier(cindex1, [<<6>>])
    {encBytes2, encLen2} = apply(__MODULE__, :"enc_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7_values", [cindex2, [<<49>>], objtype])
    bytesSoFar = [encBytes1, encBytes2]
    lenSoFar = encLen1 + encLen2
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp unquote(:"enc_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7_values")(val, tagIn, objFun) do
    {encBytes, encLen} = apply(__MODULE__, :"enc_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7_values_components", [val, objFun, [], 0])
    encode_tags(tagIn, encBytes, encLen)
  end

  defp unquote(:"enc_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7_values_components")([], _, accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp unquote(:"enc_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7_values_components")([h | t], objFun, accBytes, accLen) do
    {tmpBytes, _} = objFun.(:Type, h, [])
    {encBytes, encLen} = encode_open_type(tmpBytes, [])
    apply(__MODULE__, :"enc_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7_values_components", [t, objFun, [encBytes | accBytes], accLen + encLen])
  end

  defp enc_SignerInfo_unauthenticatedAttributes_uaSet_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_SignerInfo_unauthenticatedAttributes_uaSet_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = apply(__MODULE__, :"enc_SignerInfo_unauthenticatedAttributes_uaSet_AttributePKCS-7", [h, [<<48>>]])
    enc_SignerInfo_unauthenticatedAttributes_uaSet_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_SignerInfos(val), do: enc_SignerInfos(val, [])

  defp enc_SignerInfos_siSequence(val, tagIn) do
    {encBytes, encLen} = enc_SignerInfos_siSequence_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_SignerInfos_siSequence_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_SignerInfos_siSequence_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_SignerInfo(h, [<<48>>])
    enc_SignerInfos_siSequence_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_SignerInfos_siSet(val, tagIn) do
    {encBytes, encLen} = enc_SignerInfos_siSet_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_SignerInfos_siSet_components([], accBytes, accLen), do: {dynamicsort_SETOF(accBytes), accLen}

  defp enc_SignerInfos_siSet_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_SignerInfo(h, [<<48>>])
    enc_SignerInfos_siSet_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_SigningTime(val), do: enc_SigningTime(val, [])

  defp enc_SkipCerts(val), do: enc_SkipCerts(val, [<<2>>])

  defp enc_StreetAddress(val), do: enc_StreetAddress(val, [<<49>>])

  defp enc_SubjectAltName(val), do: enc_SubjectAltName(val, [<<48>>])

  defp enc_SubjectDirectoryAttributes(val), do: enc_SubjectDirectoryAttributes(val, [<<48>>])

  defp enc_SubjectDirectoryAttributes_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_SubjectDirectoryAttributes_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_Attribute(h, [<<48>>])
    enc_SubjectDirectoryAttributes_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_SubjectInfoAccessSyntax(val), do: enc_SubjectInfoAccessSyntax(val, [<<48>>])

  defp enc_SubjectInfoAccessSyntax_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_SubjectInfoAccessSyntax_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_AccessDescription(h, [<<48>>])
    enc_SubjectInfoAccessSyntax_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_SubjectKeyIdentifier(val), do: enc_SubjectKeyIdentifier(val, [<<4>>])

  defp enc_SubjectPublicKeyInfo(val), do: enc_SubjectPublicKeyInfo(val, [<<48>>])

  defp enc_SvceAuthInfo(val), do: enc_SvceAuthInfo(val, [<<48>>])

  defp enc_TBSCertList(val), do: enc_TBSCertList(val, [<<48>>])

  defp enc_TBSCertList_revokedCertificates(val, tagIn) do
    {encBytes, encLen} = enc_TBSCertList_revokedCertificates_components(val, [], 0)
    encode_tags(tagIn, encBytes, encLen)
  end

  defp enc_TBSCertList_revokedCertificates_SEQOF(val, tagIn) do
    {_, cindex1, cindex2, cindex3} = val
    {encBytes1, encLen1} = encode_integer(cindex1, [<<2>>])
    {encBytes2, encLen2} = enc_Time(cindex2, [])
    {encBytes3, encLen3} = case cindex3 do
      :asn1_NOVALUE ->
        {<<>>, 0}
      _ ->
        enc_Extensions(cindex3, [<<48>>])
    end
    bytesSoFar = [encBytes1, encBytes2, encBytes3]
    lenSoFar = encLen1 + encLen2 + encLen3
    encode_tags(tagIn, bytesSoFar, lenSoFar)
  end

  defp enc_TBSCertList_revokedCertificates_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_TBSCertList_revokedCertificates_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_TBSCertList_revokedCertificates_SEQOF(h, [<<48>>])
    enc_TBSCertList_revokedCertificates_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_TBSCertificate(val), do: enc_TBSCertificate(val, [<<48>>])

  defp enc_Target(val), do: enc_Target(val, [])

  defp enc_TargetCert(val), do: enc_TargetCert(val, [<<48>>])

  defp enc_Targets(val), do: enc_Targets(val, [<<48>>])

  defp enc_Targets_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_Targets_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_Target(h, [])
    enc_Targets_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_TeletexCommonName(val), do: enc_TeletexCommonName(val, [<<20>>])

  defp enc_TeletexDomainDefinedAttribute(val), do: enc_TeletexDomainDefinedAttribute(val, [<<48>>])

  defp enc_TeletexDomainDefinedAttributes(val), do: enc_TeletexDomainDefinedAttributes(val, [<<48>>])

  defp enc_TeletexDomainDefinedAttributes_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_TeletexDomainDefinedAttributes_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = enc_TeletexDomainDefinedAttribute(h, [<<48>>])
    enc_TeletexDomainDefinedAttributes_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_TeletexOrganizationName(val), do: enc_TeletexOrganizationName(val, [<<20>>])

  defp enc_TeletexOrganizationalUnitName(val), do: enc_TeletexOrganizationalUnitName(val, [<<20>>])

  defp enc_TeletexOrganizationalUnitNames(val), do: enc_TeletexOrganizationalUnitNames(val, [<<48>>])

  defp enc_TeletexOrganizationalUnitNames_components([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp enc_TeletexOrganizationalUnitNames_components([h | t], accBytes, accLen) do
    {encBytes, encLen} = encode_restricted_string(h, [<<20>>])
    enc_TeletexOrganizationalUnitNames_components(t, [encBytes | accBytes], accLen + encLen)
  end

  defp enc_TeletexPersonalName(val), do: enc_TeletexPersonalName(val, [<<49>>])

  defp enc_TerminalIdentifier(val), do: enc_TerminalIdentifier(val, [<<19>>])

  defp enc_TerminalType(val), do: enc_TerminalType(val, [<<2>>])

  defp enc_Time(val), do: enc_Time(val, [])

  defp enc_TrailerField(val), do: enc_TrailerField(val, [<<2>>])

  defp enc_Trinomial(val), do: enc_Trinomial(val, [<<2>>])

  defp enc_UnformattedPostalAddress(val), do: enc_UnformattedPostalAddress(val, [<<49>>])

  defp unquote(:"enc_UnformattedPostalAddress_printable-address")(val, tagIn) do
    {encBytes, encLen} = apply(__MODULE__, :"enc_UnformattedPostalAddress_printable-address_components", [val, [], 0])
    encode_tags(tagIn, encBytes, encLen)
  end

  defp unquote(:"enc_UnformattedPostalAddress_printable-address_components")([], accBytes, accLen), do: {:lists.reverse(accBytes), accLen}

  defp unquote(:"enc_UnformattedPostalAddress_printable-address_components")([h | t], accBytes, accLen) do
    {encBytes, encLen} = encode_restricted_string(h, [<<19>>])
    apply(__MODULE__, :"enc_UnformattedPostalAddress_printable-address_components", [t, [encBytes | accBytes], accLen + encLen])
  end

  defp enc_UniqueIdentifier(val), do: enc_UniqueIdentifier(val, [<<3>>])

  defp enc_UniquePostalName(val), do: enc_UniquePostalName(val, [<<49>>])

  defp enc_UserNotice(val), do: enc_UserNotice(val, [<<48>>])

  defp enc_V2Form(val), do: enc_V2Form(val, [<<48>>])

  defp enc_ValidationParms(val), do: enc_ValidationParms(val, [<<48>>])

  defp enc_Validity(val), do: enc_Validity(val, [<<48>>])

  defp unquote(:"enc_VersionPKCS-1")(val), do: apply(__MODULE__, :"enc_VersionPKCS-1", [val, [<<2>>]])

  defp enc_VersionPKIX1Explicit88(val), do: enc_VersionPKIX1Explicit88(val, [<<2>>])

  defp enc_X121Address(val), do: enc_X121Address(val, [<<18>>])

  defp enc_X520CommonName(val), do: enc_X520CommonName(val, [])

  defp enc_X520LocalityName(val), do: enc_X520LocalityName(val, [])

  defp enc_X520OrganizationName(val), do: enc_X520OrganizationName(val, [])

  defp enc_X520OrganizationalUnitName(val), do: enc_X520OrganizationalUnitName(val, [])

  defp enc_X520Pseudonym(val), do: enc_X520Pseudonym(val, [])

  defp enc_X520SerialNumber(val), do: enc_X520SerialNumber(val, [<<19>>])

  defp enc_X520StateOrProvinceName(val), do: enc_X520StateOrProvinceName(val, [])

  defp enc_X520Title(val), do: enc_X520Title(val, [])

  defp enc_X520countryName(val), do: enc_X520countryName(val, [<<19>>])

  defp enc_X520dnQualifier(val), do: enc_X520dnQualifier(val, [<<19>>])

  defp enc_X520name(val), do: enc_X520name(val, [])

  defp enc_obj_id_tail(h, len) do
    {b, l} = mk_object_val(h)
    {b, len + l}
  end

  defp encode_BMP_string(bMPString, tagIn) do
    octetList = mk_BMP_list(bMPString)
    encode_tags(tagIn, octetList, length(octetList))
  end

  defp encode_UTF8_string(uTF8String, tagIn) when is_binary(uTF8String), do: encode_tags(tagIn, uTF8String, byte_size(uTF8String))

  defp encode_UTF8_string(uTF8String, tagIn), do: encode_tags(tagIn, uTF8String, length(uTF8String))

  defp encode_bitstring([b8, b7, b6, b5, b4, b3, b2, b1 | rest]) do
    val = b8 <<< 7 ||| b7 <<< 6 ||| b6 <<< 5 ||| b5 <<< 4 ||| b4 <<< 3 ||| b3 <<< 2 ||| b2 <<< 1 ||| b1
    encode_bitstring(rest, [val], 1)
  end

  defp encode_bitstring(val) do
    {unused, octet} = unused_bitlist(val, 7, 0)
    {1, unused, [octet]}
  end

  defp encode_bitstring([b8, b7, b6, b5, b4, b3, b2, b1 | rest], ack, len) do
    val = b8 <<< 7 ||| b7 <<< 6 ||| b6 <<< 5 ||| b5 <<< 4 ||| b4 <<< 3 ||| b3 <<< 2 ||| b2 <<< 1 ||| b1
    encode_bitstring(rest, [ack, val], len + 1)
  end

  defp encode_bitstring([], ack, len), do: {len, 0, ack}

  defp encode_bitstring(rest, ack, len) do
    {unused, val} = unused_bitlist(rest, 7, 0)
    {len + 1, unused, [ack, val]}
  end

  defp encode_boolean(true, tagIn), do: encode_tags(tagIn, [255], 1)

  defp encode_boolean(false, tagIn), do: encode_tags(tagIn, [0], 1)

  defp encode_boolean(x, _), do: exit({:error, {:asn1, {:encode_boolean, x}}})

  defp encode_disp(:ECPrivateKey, data), do: enc_ECPrivateKey(data)

  defp encode_disp(:DSAPrivateKey, data), do: enc_DSAPrivateKey(data)

  defp encode_disp(:DHParameter, data), do: enc_DHParameter(data)

  defp encode_disp(:DigestInfoNull, data), do: enc_DigestInfoNull(data)

  defp encode_disp(:"DigestInfoPKCS-1", data), do: apply(__MODULE__, :"enc_DigestInfoPKCS-1", [data])

  defp encode_disp(:TrailerField, data), do: enc_TrailerField(data)

  defp encode_disp(:"RSASSA-PSS-params", data), do: apply(__MODULE__, :"enc_RSASSA-PSS-params", [data])

  defp encode_disp(:AlgorithmNull, data), do: enc_AlgorithmNull(data)

  defp encode_disp(:Algorithm, data), do: enc_Algorithm(data)

  defp encode_disp(:OtherPrimeInfo, data), do: enc_OtherPrimeInfo(data)

  defp encode_disp(:OtherPrimeInfos, data), do: enc_OtherPrimeInfos(data)

  defp encode_disp(:"VersionPKCS-1", data), do: apply(__MODULE__, :"enc_VersionPKCS-1", [data])

  defp encode_disp(:RSAPrivateKey, data), do: enc_RSAPrivateKey(data)

  defp encode_disp(:RSAPublicKey, data), do: enc_RSAPublicKey(data)

  defp encode_disp(:Curve, data), do: enc_Curve(data)

  defp encode_disp(:ECPVer, data), do: enc_ECPVer(data)

  defp encode_disp(:ECParameters, data), do: enc_ECParameters(data)

  defp encode_disp(:EcpkParameters, data), do: enc_EcpkParameters(data)

  defp encode_disp(:ECPoint, data), do: enc_ECPoint(data)

  defp encode_disp(:FieldElement, data), do: enc_FieldElement(data)

  defp encode_disp(:Pentanomial, data), do: enc_Pentanomial(data)

  defp encode_disp(:Trinomial, data), do: enc_Trinomial(data)

  defp encode_disp(:"Characteristic-two", data), do: apply(__MODULE__, :"enc_Characteristic-two", [data])

  defp encode_disp(:"Prime-p", data), do: apply(__MODULE__, :"enc_Prime-p", [data])

  defp encode_disp(:"ECDSA-Sig-Value", data), do: apply(__MODULE__, :"enc_ECDSA-Sig-Value", [data])

  defp encode_disp(:FieldID, data), do: enc_FieldID(data)

  defp encode_disp(:"KEA-Parms-Id", data), do: apply(__MODULE__, :"enc_KEA-Parms-Id", [data])

  defp encode_disp(:ValidationParms, data), do: enc_ValidationParms(data)

  defp encode_disp(:DomainParameters, data), do: enc_DomainParameters(data)

  defp encode_disp(:DHPublicKey, data), do: enc_DHPublicKey(data)

  defp encode_disp(:"Dss-Sig-Value", data), do: apply(__MODULE__, :"enc_Dss-Sig-Value", [data])

  defp encode_disp(:"Dss-Parms", data), do: apply(__MODULE__, :"enc_Dss-Parms", [data])

  defp encode_disp(:DSAPublicKey, data), do: enc_DSAPublicKey(data)

  defp encode_disp(:ProxyInfo, data), do: enc_ProxyInfo(data)

  defp encode_disp(:ACClearAttrs, data), do: enc_ACClearAttrs(data)

  defp encode_disp(:AttrSpec, data), do: enc_AttrSpec(data)

  defp encode_disp(:AAControls, data), do: enc_AAControls(data)

  defp encode_disp(:SecurityCategory, data), do: enc_SecurityCategory(data)

  defp encode_disp(:ClassList, data), do: enc_ClassList(data)

  defp encode_disp(:Clearance, data), do: enc_Clearance(data)

  defp encode_disp(:RoleSyntax, data), do: enc_RoleSyntax(data)

  defp encode_disp(:SvceAuthInfo, data), do: enc_SvceAuthInfo(data)

  defp encode_disp(:IetfAttrSyntax, data), do: enc_IetfAttrSyntax(data)

  defp encode_disp(:TargetCert, data), do: enc_TargetCert(data)

  defp encode_disp(:Target, data), do: enc_Target(data)

  defp encode_disp(:Targets, data), do: enc_Targets(data)

  defp encode_disp(:AttCertValidityPeriod, data), do: enc_AttCertValidityPeriod(data)

  defp encode_disp(:IssuerSerial, data), do: enc_IssuerSerial(data)

  defp encode_disp(:V2Form, data), do: enc_V2Form(data)

  defp encode_disp(:AttCertIssuer, data), do: enc_AttCertIssuer(data)

  defp encode_disp(:ObjectDigestInfo, data), do: enc_ObjectDigestInfo(data)

  defp encode_disp(:Holder, data), do: enc_Holder(data)

  defp encode_disp(:AttCertVersion, data), do: enc_AttCertVersion(data)

  defp encode_disp(:AttributeCertificateInfo, data), do: enc_AttributeCertificateInfo(data)

  defp encode_disp(:AttributeCertificate, data), do: enc_AttributeCertificate(data)

  defp encode_disp(:InvalidityDate, data), do: enc_InvalidityDate(data)

  defp encode_disp(:HoldInstructionCode, data), do: enc_HoldInstructionCode(data)

  defp encode_disp(:CertificateIssuer, data), do: enc_CertificateIssuer(data)

  defp encode_disp(:CRLReason, data), do: enc_CRLReason(data)

  defp encode_disp(:BaseCRLNumber, data), do: enc_BaseCRLNumber(data)

  defp encode_disp(:IssuingDistributionPoint, data), do: enc_IssuingDistributionPoint(data)

  defp encode_disp(:CRLNumber, data), do: enc_CRLNumber(data)

  defp encode_disp(:SubjectInfoAccessSyntax, data), do: enc_SubjectInfoAccessSyntax(data)

  defp encode_disp(:AccessDescription, data), do: enc_AccessDescription(data)

  defp encode_disp(:AuthorityInfoAccessSyntax, data), do: enc_AuthorityInfoAccessSyntax(data)

  defp encode_disp(:FreshestCRL, data), do: enc_FreshestCRL(data)

  defp encode_disp(:InhibitAnyPolicy, data), do: enc_InhibitAnyPolicy(data)

  defp encode_disp(:KeyPurposeId, data), do: enc_KeyPurposeId(data)

  defp encode_disp(:ExtKeyUsageSyntax, data), do: enc_ExtKeyUsageSyntax(data)

  defp encode_disp(:ReasonFlags, data), do: enc_ReasonFlags(data)

  defp encode_disp(:DistributionPointName, data), do: enc_DistributionPointName(data)

  defp encode_disp(:DistributionPoint, data), do: enc_DistributionPoint(data)

  defp encode_disp(:CRLDistributionPoints, data), do: enc_CRLDistributionPoints(data)

  defp encode_disp(:SkipCerts, data), do: enc_SkipCerts(data)

  defp encode_disp(:PolicyConstraints, data), do: enc_PolicyConstraints(data)

  defp encode_disp(:BaseDistance, data), do: enc_BaseDistance(data)

  defp encode_disp(:GeneralSubtree, data), do: enc_GeneralSubtree(data)

  defp encode_disp(:GeneralSubtrees, data), do: enc_GeneralSubtrees(data)

  defp encode_disp(:NameConstraints, data), do: enc_NameConstraints(data)

  defp encode_disp(:BasicConstraints, data), do: enc_BasicConstraints(data)

  defp encode_disp(:SubjectDirectoryAttributes, data), do: enc_SubjectDirectoryAttributes(data)

  defp encode_disp(:IssuerAltName, data), do: enc_IssuerAltName(data)

  defp encode_disp(:EDIPartyName, data), do: enc_EDIPartyName(data)

  defp encode_disp(:AnotherName, data), do: enc_AnotherName(data)

  defp encode_disp(:GeneralName, data), do: enc_GeneralName(data)

  defp encode_disp(:GeneralNames, data), do: enc_GeneralNames(data)

  defp encode_disp(:SubjectAltName, data), do: enc_SubjectAltName(data)

  defp encode_disp(:PolicyMappings, data), do: enc_PolicyMappings(data)

  defp encode_disp(:DisplayText, data), do: enc_DisplayText(data)

  defp encode_disp(:NoticeReference, data), do: enc_NoticeReference(data)

  defp encode_disp(:UserNotice, data), do: enc_UserNotice(data)

  defp encode_disp(:CPSuri, data), do: enc_CPSuri(data)

  defp encode_disp(:PolicyQualifierId, data), do: enc_PolicyQualifierId(data)

  defp encode_disp(:PolicyQualifierInfo, data), do: enc_PolicyQualifierInfo(data)

  defp encode_disp(:CertPolicyId, data), do: enc_CertPolicyId(data)

  defp encode_disp(:PolicyInformation, data), do: enc_PolicyInformation(data)

  defp encode_disp(:CertificatePolicies, data), do: enc_CertificatePolicies(data)

  defp encode_disp(:PrivateKeyUsagePeriod, data), do: enc_PrivateKeyUsagePeriod(data)

  defp encode_disp(:KeyUsage, data), do: enc_KeyUsage(data)

  defp encode_disp(:SubjectKeyIdentifier, data), do: enc_SubjectKeyIdentifier(data)

  defp encode_disp(:KeyIdentifier, data), do: enc_KeyIdentifier(data)

  defp encode_disp(:AuthorityKeyIdentifier, data), do: enc_AuthorityKeyIdentifier(data)

  defp encode_disp(:EncryptedData, data), do: enc_EncryptedData(data)

  defp encode_disp(:DigestedData, data), do: enc_DigestedData(data)

  defp encode_disp(:SignedAndEnvelopedData, data), do: enc_SignedAndEnvelopedData(data)

  defp encode_disp(:EncryptedKey, data), do: enc_EncryptedKey(data)

  defp encode_disp(:RecipientInfo, data), do: enc_RecipientInfo(data)

  defp encode_disp(:EncryptedContent, data), do: enc_EncryptedContent(data)

  defp encode_disp(:EncryptedContentInfo, data), do: enc_EncryptedContentInfo(data)

  defp encode_disp(:RecipientInfos, data), do: enc_RecipientInfos(data)

  defp encode_disp(:EnvelopedData, data), do: enc_EnvelopedData(data)

  defp encode_disp(:Digest, data), do: enc_Digest(data)

  defp encode_disp(:"DigestInfoPKCS-7", data), do: apply(__MODULE__, :"enc_DigestInfoPKCS-7", [data])

  defp encode_disp(:EncryptedDigest, data), do: enc_EncryptedDigest(data)

  defp encode_disp(:SignerInfo, data), do: enc_SignerInfo(data)

  defp encode_disp(:DigestAlgorithmIdentifiers, data), do: enc_DigestAlgorithmIdentifiers(data)

  defp encode_disp(:SignerInfos, data), do: enc_SignerInfos(data)

  defp encode_disp(:SignedData, data), do: enc_SignedData(data)

  defp encode_disp(:Data, data), do: enc_Data(data)

  defp encode_disp(:ContentType, data), do: enc_ContentType(data)

  defp encode_disp(:ContentInfo, data), do: enc_ContentInfo(data)

  defp encode_disp(:KeyEncryptionAlgorithmIdentifier, data), do: enc_KeyEncryptionAlgorithmIdentifier(data)

  defp encode_disp(:IssuerAndSerialNumber, data), do: enc_IssuerAndSerialNumber(data)

  defp encode_disp(:ExtendedCertificatesAndCertificates, data), do: enc_ExtendedCertificatesAndCertificates(data)

  defp encode_disp(:ExtendedCertificate, data), do: enc_ExtendedCertificate(data)

  defp encode_disp(:ExtendedCertificateOrCertificate, data), do: enc_ExtendedCertificateOrCertificate(data)

  defp encode_disp(:DigestEncryptionAlgorithmIdentifier, data), do: enc_DigestEncryptionAlgorithmIdentifier(data)

  defp encode_disp(:DigestAlgorithmIdentifier, data), do: enc_DigestAlgorithmIdentifier(data)

  defp encode_disp(:ContentEncryptionAlgorithmIdentifier, data), do: enc_ContentEncryptionAlgorithmIdentifier(data)

  defp encode_disp(:CRLSequence, data), do: enc_CRLSequence(data)

  defp encode_disp(:Certificates, data), do: enc_Certificates(data)

  defp encode_disp(:CertificateRevocationLists, data), do: enc_CertificateRevocationLists(data)

  defp encode_disp(:SignerInfoAuthenticatedAttributes, data), do: enc_SignerInfoAuthenticatedAttributes(data)

  defp encode_disp(:SigningTime, data), do: enc_SigningTime(data)

  defp encode_disp(:MessageDigest, data), do: enc_MessageDigest(data)

  defp encode_disp(:CertificationRequest, data), do: enc_CertificationRequest(data)

  defp encode_disp(:CertificationRequestInfo, data), do: enc_CertificationRequestInfo(data)

  defp encode_disp(:ExtensionRequest, data), do: enc_ExtensionRequest(data)

  defp encode_disp(:TeletexDomainDefinedAttribute, data), do: enc_TeletexDomainDefinedAttribute(data)

  defp encode_disp(:TeletexDomainDefinedAttributes, data), do: enc_TeletexDomainDefinedAttributes(data)

  defp encode_disp(:TerminalType, data), do: enc_TerminalType(data)

  defp encode_disp(:PresentationAddress, data), do: enc_PresentationAddress(data)

  defp encode_disp(:ExtendedNetworkAddress, data), do: enc_ExtendedNetworkAddress(data)

  defp encode_disp(:PDSParameter, data), do: enc_PDSParameter(data)

  defp encode_disp(:LocalPostalAttributes, data), do: enc_LocalPostalAttributes(data)

  defp encode_disp(:UniquePostalName, data), do: enc_UniquePostalName(data)

  defp encode_disp(:PosteRestanteAddress, data), do: enc_PosteRestanteAddress(data)

  defp encode_disp(:PostOfficeBoxAddress, data), do: enc_PostOfficeBoxAddress(data)

  defp encode_disp(:StreetAddress, data), do: enc_StreetAddress(data)

  defp encode_disp(:UnformattedPostalAddress, data), do: enc_UnformattedPostalAddress(data)

  defp encode_disp(:ExtensionPhysicalDeliveryAddressComponents, data), do: enc_ExtensionPhysicalDeliveryAddressComponents(data)

  defp encode_disp(:PhysicalDeliveryOrganizationName, data), do: enc_PhysicalDeliveryOrganizationName(data)

  defp encode_disp(:PhysicalDeliveryPersonalName, data), do: enc_PhysicalDeliveryPersonalName(data)

  defp encode_disp(:ExtensionORAddressComponents, data), do: enc_ExtensionORAddressComponents(data)

  defp encode_disp(:PhysicalDeliveryOfficeNumber, data), do: enc_PhysicalDeliveryOfficeNumber(data)

  defp encode_disp(:PhysicalDeliveryOfficeName, data), do: enc_PhysicalDeliveryOfficeName(data)

  defp encode_disp(:PostalCode, data), do: enc_PostalCode(data)

  defp encode_disp(:PhysicalDeliveryCountryName, data), do: enc_PhysicalDeliveryCountryName(data)

  defp encode_disp(:PDSName, data), do: enc_PDSName(data)

  defp encode_disp(:TeletexOrganizationalUnitName, data), do: enc_TeletexOrganizationalUnitName(data)

  defp encode_disp(:TeletexOrganizationalUnitNames, data), do: enc_TeletexOrganizationalUnitNames(data)

  defp encode_disp(:TeletexPersonalName, data), do: enc_TeletexPersonalName(data)

  defp encode_disp(:TeletexOrganizationName, data), do: enc_TeletexOrganizationName(data)

  defp encode_disp(:TeletexCommonName, data), do: enc_TeletexCommonName(data)

  defp encode_disp(:CommonName, data), do: enc_CommonName(data)

  defp encode_disp(:ExtensionAttribute, data), do: enc_ExtensionAttribute(data)

  defp encode_disp(:ExtensionAttributes, data), do: enc_ExtensionAttributes(data)

  defp encode_disp(:BuiltInDomainDefinedAttribute, data), do: enc_BuiltInDomainDefinedAttribute(data)

  defp encode_disp(:BuiltInDomainDefinedAttributes, data), do: enc_BuiltInDomainDefinedAttributes(data)

  defp encode_disp(:OrganizationalUnitName, data), do: enc_OrganizationalUnitName(data)

  defp encode_disp(:OrganizationalUnitNames, data), do: enc_OrganizationalUnitNames(data)

  defp encode_disp(:PersonalName, data), do: enc_PersonalName(data)

  defp encode_disp(:NumericUserIdentifier, data), do: enc_NumericUserIdentifier(data)

  defp encode_disp(:OrganizationName, data), do: enc_OrganizationName(data)

  defp encode_disp(:PrivateDomainName, data), do: enc_PrivateDomainName(data)

  defp encode_disp(:TerminalIdentifier, data), do: enc_TerminalIdentifier(data)

  defp encode_disp(:X121Address, data), do: enc_X121Address(data)

  defp encode_disp(:NetworkAddress, data), do: enc_NetworkAddress(data)

  defp encode_disp(:AdministrationDomainName, data), do: enc_AdministrationDomainName(data)

  defp encode_disp(:CountryName, data), do: enc_CountryName(data)

  defp encode_disp(:BuiltInStandardAttributes, data), do: enc_BuiltInStandardAttributes(data)

  defp encode_disp(:ORAddress, data), do: enc_ORAddress(data)

  defp encode_disp(:AlgorithmIdentifier, data), do: enc_AlgorithmIdentifier(data)

  defp encode_disp(:TBSCertList, data), do: enc_TBSCertList(data)

  defp encode_disp(:CertificateList, data), do: enc_CertificateList(data)

  defp encode_disp(:Extension, data), do: enc_Extension(data)

  defp encode_disp(:Extensions, data), do: enc_Extensions(data)

  defp encode_disp(:SubjectPublicKeyInfo, data), do: enc_SubjectPublicKeyInfo(data)

  defp encode_disp(:UniqueIdentifier, data), do: enc_UniqueIdentifier(data)

  defp encode_disp(:Time, data), do: enc_Time(data)

  defp encode_disp(:Validity, data), do: enc_Validity(data)

  defp encode_disp(:CertificateSerialNumber, data), do: enc_CertificateSerialNumber(data)

  defp encode_disp(:VersionPKIX1Explicit88, data), do: enc_VersionPKIX1Explicit88(data)

  defp encode_disp(:TBSCertificate, data), do: enc_TBSCertificate(data)

  defp encode_disp(:Certificate, data), do: enc_Certificate(data)

  defp encode_disp(:DirectoryString, data), do: enc_DirectoryString(data)

  defp encode_disp(:RelativeDistinguishedName, data), do: enc_RelativeDistinguishedName(data)

  defp encode_disp(:DistinguishedName, data), do: enc_DistinguishedName(data)

  defp encode_disp(:RDNSequence, data), do: enc_RDNSequence(data)

  defp encode_disp(:Name, data), do: enc_Name(data)

  defp encode_disp(:EmailAddress, data), do: enc_EmailAddress(data)

  defp encode_disp(:DomainComponent, data), do: enc_DomainComponent(data)

  defp encode_disp(:X520Pseudonym, data), do: enc_X520Pseudonym(data)

  defp encode_disp(:X520SerialNumber, data), do: enc_X520SerialNumber(data)

  defp encode_disp(:X520countryName, data), do: enc_X520countryName(data)

  defp encode_disp(:X520dnQualifier, data), do: enc_X520dnQualifier(data)

  defp encode_disp(:X520Title, data), do: enc_X520Title(data)

  defp encode_disp(:X520OrganizationalUnitName, data), do: enc_X520OrganizationalUnitName(data)

  defp encode_disp(:X520OrganizationName, data), do: enc_X520OrganizationName(data)

  defp encode_disp(:X520StateOrProvinceName, data), do: enc_X520StateOrProvinceName(data)

  defp encode_disp(:X520LocalityName, data), do: enc_X520LocalityName(data)

  defp encode_disp(:X520CommonName, data), do: enc_X520CommonName(data)

  defp encode_disp(:X520name, data), do: enc_X520name(data)

  defp encode_disp(:AttributeTypeAndValue, data), do: enc_AttributeTypeAndValue(data)

  defp encode_disp(:AttributeValue, data), do: enc_AttributeValue(data)

  defp encode_disp(:AttributeType, data), do: enc_AttributeType(data)

  defp encode_disp(:Attribute, data), do: enc_Attribute(data)

  defp encode_disp(:"Extension-Any", data), do: apply(__MODULE__, :"enc_Extension-Any", [data])

  defp encode_disp(:Any, data), do: enc_Any(data)

  defp encode_disp(:Boolean, data), do: enc_Boolean(data)

  defp encode_disp(:ObjId, data), do: enc_ObjId(data)

  defp encode_disp(:OTPExtension, data), do: enc_OTPExtension(data)

  defp encode_disp(:OTPExtensions, data), do: enc_OTPExtensions(data)

  defp encode_disp(:OTPExtensionAttribute, data), do: enc_OTPExtensionAttribute(data)

  defp encode_disp(:OTPExtensionAttributes, data), do: enc_OTPExtensionAttributes(data)

  defp encode_disp(:"OTPCharacteristic-two", data), do: apply(__MODULE__, :"enc_OTPCharacteristic-two", [data])

  defp encode_disp(:OTPFieldID, data), do: enc_OTPFieldID(data)

  defp encode_disp(:"KEA-PublicKey", data), do: apply(__MODULE__, :"enc_KEA-PublicKey", [data])

  defp encode_disp(:DSAParams, data), do: enc_DSAParams(data)

  defp encode_disp(:PublicKeyAlgorithm, data), do: enc_PublicKeyAlgorithm(data)

  defp encode_disp(:"SignatureAlgorithm-Any", data), do: apply(__MODULE__, :"enc_SignatureAlgorithm-Any", [data])

  defp encode_disp(:SignatureAlgorithm, data), do: enc_SignatureAlgorithm(data)

  defp encode_disp(:"OTPSubjectPublicKeyInfo-Any", data), do: apply(__MODULE__, :"enc_OTPSubjectPublicKeyInfo-Any", [data])

  defp encode_disp(:OTPSubjectPublicKeyInfo, data), do: enc_OTPSubjectPublicKeyInfo(data)

  defp encode_disp(:OTPOLDSubjectPublicKeyInfo, data), do: enc_OTPOLDSubjectPublicKeyInfo(data)

  defp encode_disp(:"OTP-emailAddress", data), do: apply(__MODULE__, :"enc_OTP-emailAddress", [data])

  defp encode_disp(:"OTP-X520countryname", data), do: apply(__MODULE__, :"enc_OTP-X520countryname", [data])

  defp encode_disp(:OTPAttributeTypeAndValue, data), do: enc_OTPAttributeTypeAndValue(data)

  defp encode_disp(:OTPTBSCertificate, data), do: enc_OTPTBSCertificate(data)

  defp encode_disp(:OTPCertificate, data), do: enc_OTPCertificate(data)

  defp encode_disp(type, _Data), do: exit({:error, {:asn1, {:undefined_type, type}}})

  defp encode_integer(val) do
    bytes = cond do
      val >= 0 ->
        encode_integer_pos(val, [])
      true ->
        encode_integer_neg(val, [])
    end
    {bytes, length(bytes)}
  end

  defp encode_integer(val, tag) when is_integer(val), do: encode_tags(tag, encode_integer(val))

  defp encode_integer(val, _Tag), do: exit({:error, {:asn1, {:encode_integer, val}}})

  defp encode_integer(val, namedNumberList, tag) when is_atom(val) do
    case :lists.keyfind(val, 1, namedNumberList) do
      {_, newVal} ->
        encode_tags(tag, encode_integer(newVal))
      _ ->
        exit({:error, {:asn1, {:encode_integer_namednumber, val}}})
    end
  end

  defp encode_integer(val, _NamedNumberList, tag), do: encode_tags(tag, encode_integer(val))

  defp encode_integer_neg(-1, [b1 | _T] = l) when b1 > 127, do: l

  defp encode_integer_neg(n, acc), do: encode_integer_neg(n >>> 8, [n &&& 255 | acc])

  defp encode_integer_pos(0, [b | _Acc] = l) when b < 128, do: l

  defp encode_integer_pos(n, acc), do: encode_integer_pos(n >>> 8, [n &&& 255 | acc])

  defp encode_length(l) when l <= 127, do: {[l], 1}

  defp encode_length(l) do
    oct = minimum_octets(l)
    len = length(oct)
    cond do
      len <= 126 ->
        {[128 ||| len | oct], len + 1}
      true ->
        exit({:error, {:asn1, :too_long_length_oct, len}})
    end
  end

  defp encode_named_bit_string([h | _] = bits, namedBitList, tagIn) when is_atom(h), do: do_encode_named_bit_string(bits, namedBitList, tagIn)

  defp encode_named_bit_string([{:bit, _} | _] = bits, namedBitList, tagIn), do: do_encode_named_bit_string(bits, namedBitList, tagIn)

  defp encode_named_bit_string([], _NamedBitList, tagIn), do: encode_unnamed_bit_string(<<>>, tagIn)

  defp encode_named_bit_string(bits, _NamedBitList, tagIn) when is_bitstring(bits), do: encode_unnamed_bit_string(bits, tagIn)

  defp encode_null(_Val, tagIn), do: encode_tags(tagIn, [], 0)

  defp encode_object_identifier(val, tagIn), do: encode_tags(tagIn, e_object_identifier(val))

  defp encode_open_type(val, t) when is_list(val), do: encode_open_type(list_to_binary(val), t)

  defp encode_open_type(val, tag), do: encode_tags(tag, val, byte_size(val))

  defp encode_restricted_string(octetList, tagIn) when is_binary(octetList), do: encode_tags(tagIn, octetList, byte_size(octetList))

  defp encode_restricted_string(octetList, tagIn) when is_list(octetList), do: encode_tags(tagIn, octetList, length(octetList))

  defp encode_tags(tagIn, {bytesSoFar, lenSoFar}), do: encode_tags(tagIn, bytesSoFar, lenSoFar)

  defp encode_tags([tag | trest], bytesSoFar, lenSoFar) do
    {bytes2, l2} = encode_length(lenSoFar)
    encode_tags(trest, [tag, bytes2 | bytesSoFar], lenSoFar + byte_size(tag) + l2)
  end

  defp encode_tags([], bytesSoFar, lenSoFar), do: {bytesSoFar, lenSoFar}

  defp encode_universal_string(universal, tagIn) do
    octetList = mk_uni_list(universal)
    encode_tags(tagIn, octetList, length(octetList))
  end

  defp encode_unnamed_bit_string(bits, tagIn) do
    unused = 8 - bit_size(bits) &&& 7 &&& 7
    bin = <<unused, bits :: bitstring, 0 :: size(unused)>>
    encode_tags(tagIn, bin, byte_size(bin))
  end

  defp get_all_bitposes([{:bit, valPos} | rest], namedBitList, ack), do: get_all_bitposes(rest, namedBitList, [valPos | ack])

  defp get_all_bitposes([val | rest], namedBitList, ack) when is_atom(val) do
    case :lists.keyfind(val, 1, namedBitList) do
      {_ValName, valPos} ->
        get_all_bitposes(rest, namedBitList, [valPos | ack])
      _ ->
        exit({:error, {:asn1, {:bitstring_namedbit, val}}})
    end
  end

  defp get_all_bitposes([], _NamedBitList, ack), do: :lists.sort(ack)

  defp incomplete_choice_alt(tagNo, [[alt, ^tagNo] | directives]), do: {alt, directives}

  defp incomplete_choice_alt(tagNo, [d]) when is_list(d), do: incomplete_choice_alt(tagNo, d)

  defp incomplete_choice_alt(tagNo, [_H | directives]), do: incomplete_choice_alt(tagNo, directives)

  defp incomplete_choice_alt(_, []), do: :no_match

  defp is_default_1(:asn1_DEFAULT), do: true

  defp is_default_1(def) when def === 20, do: true

  defp is_default_1(_), do: false

  defp is_default_10(:asn1_DEFAULT), do: true

  defp is_default_10(def) when def === false, do: true

  defp is_default_10(_), do: false

  defp is_default_11(:asn1_DEFAULT), do: true

  defp is_default_11(value) do
    try do
      check_int(value, 0, [{:v1, 0}, {:v2, 1}, {:v3, 2}])
    catch
      {:throw, false, _} ->
        false
    else
      _ ->
        true
    end
  end

  defp is_default_2(:asn1_DEFAULT), do: true

  defp is_default_2(value) do
    try do
      check_int(value, 1, [{:trailerFieldBC, 1}])
    catch
      {:throw, false, _} ->
        false
    else
      _ ->
        true
    end
  end

  defp is_default_3(:asn1_DEFAULT), do: true

  defp is_default_3(def) when def === true, do: true

  defp is_default_3(_), do: false

  defp is_default_4(:asn1_DEFAULT), do: true

  defp is_default_4(value) do
    try do
      check_named_bitstring(value, [:unclassified], <<1 :: 2>>, 2)
    catch
      {:throw, false, _} ->
        false
    else
      _ ->
        true
    end
  end

  defp is_default_5(:asn1_DEFAULT), do: true

  defp is_default_5(def) when def === false, do: true

  defp is_default_5(_), do: false

  defp is_default_6(:asn1_DEFAULT), do: true

  defp is_default_6(def) when def === false, do: true

  defp is_default_6(_), do: false

  defp is_default_7(:asn1_DEFAULT), do: true

  defp is_default_7(def) when def === false, do: true

  defp is_default_7(_), do: false

  defp is_default_8(:asn1_DEFAULT), do: true

  defp is_default_8(def) when def === false, do: true

  defp is_default_8(_), do: false

  defp is_default_9(:asn1_DEFAULT), do: true

  defp is_default_9(def) when def === 0, do: true

  defp is_default_9(_), do: false

  defp make_and_set_list(0, [], _), do: []

  defp make_and_set_list(0, _, _), do: exit({:error, {:asn1, :bitstring_sizeconstraint}})

  defp make_and_set_list(len, [xPos | setPos], ^xPos), do: [1 | make_and_set_list(len - 1, setPos, xPos + 1)]

  defp make_and_set_list(len, [pos | setPos], xPos), do: [0 | make_and_set_list(len - 1, [pos | setPos], xPos + 1)]

  defp make_and_set_list(len, [], xPos), do: [0 | make_and_set_list(len - 1, [], xPos + 1)]

  defp match_and_collect(tlv, tagsIn) do
    val = match_tags(tlv, tagsIn)
    case val do
      [_ | _] = partList ->
        collect_parts(partList)
      bin when is_binary(bin) ->
        bin
    end
  end

  defp match_tags({t, v}, [^t]), do: v

  defp match_tags({t, v}, [^t | tt]), do: match_tags(v, tt)

  defp match_tags([{t, v}], [^t | tt]), do: match_tags(v, tt)

  defp match_tags([{t, _V} | _] = vlist, [^t]), do: vlist

  defp match_tags(tlv, []), do: tlv

  defp match_tags({tag, _V} = tlv, [t | _Tt]), do: exit({:error, {:asn1, {:wrong_tag, {{:expected, t}, {:got, tag, tlv}}}}})

  defp minimum_octets(val), do: minimum_octets(val, [])

  defp minimum_octets(0, acc), do: acc

  defp minimum_octets(val, acc), do: minimum_octets(val >>> 8, [val &&& 255 | acc])

  defp mk_BMP_list(erlangVariableIn), do: mk_BMP_list(erlangVariableIn, [])

  defp mk_BMP_list([], list), do: :lists.reverse(list)

  defp mk_BMP_list([{0, 0, c, d} | t], list), do: mk_BMP_list(t, [d, c | list])

  defp mk_BMP_list([h | t], list), do: mk_BMP_list(t, [h, 0 | list])

  defp mk_BMP_string(erlangVariableIn), do: mk_BMP_string(erlangVariableIn, [])

  defp mk_BMP_string([], uS), do: :lists.reverse(uS)

  defp mk_BMP_string([0, b | t], uS), do: mk_BMP_string(t, [b | uS])

  defp mk_BMP_string([c, d | t], uS), do: mk_BMP_string(t, [{0, 0, c, d} | uS])

  defp mk_object_val(val) when val <= 127, do: {[255 &&& val], 1}

  defp mk_object_val(val), do: mk_object_val(val >>> 7, [val &&& 127], 1)

  defp mk_object_val(0, ack, len), do: {ack, len}

  defp mk_object_val(val, ack, len), do: mk_object_val(val >>> 7, [val &&& 127 ||| 128 | ack], len + 1)

  defp mk_uni_list(erlangVariableIn), do: mk_uni_list(erlangVariableIn, [])

  defp mk_uni_list([], list), do: :lists.reverse(list)

  defp mk_uni_list([{a, b, c, d} | t], list), do: mk_uni_list(t, [d, c, b, a | list])

  defp mk_uni_list([h | t], list), do: mk_uni_list(t, [h, 0, 0, 0 | list])

  defp mk_universal_string(erlangVariableIn), do: mk_universal_string(erlangVariableIn, [])

  defp mk_universal_string([], acc), do: :lists.reverse(acc)

  defp mk_universal_string([0, 0, 0, d | t], acc), do: mk_universal_string(t, [d | acc])

  defp mk_universal_string([a, b, c, d | t], acc), do: mk_universal_string(t, [{a, b, c, d} | acc])

  defp number2name(int, namedNumberList) do
    case :lists.keyfind(int, 2, namedNumberList) do
      {namedVal, _} ->
        namedVal
      _ ->
        int
    end
  end

  defp skip_indefinite_value(<<0, 0, rest :: binary>>), do: {:ok, rest}

  defp skip_indefinite_value(binary) do
    {:ok, restBinary} = skip_tag(binary)
    {:ok, restBinary2} = skip_length_and_value(restBinary)
    skip_indefinite_value(restBinary2)
  end

  defp skip_length_and_value(binary) do
    case decode_length(binary) do
      {:indefinite, restBinary} ->
        skip_indefinite_value(restBinary)
      {length, restBinary} ->
        <<_ :: size(length)-unit(8), rest :: binary>> = restBinary
        {:ok, rest}
    end
  end

  defp skip_long_tag(<<1 :: 1, _ :: 7, rest :: binary>>), do: skip_long_tag(rest)

  defp skip_long_tag(<<0 :: 1, _ :: 7, rest :: binary>>), do: {:ok, rest}

  defp skip_tag(<<_ :: 3, 31 :: 5, rest :: binary>>), do: skip_long_tag(rest)

  defp skip_tag(<<_ :: 3, _Tag :: 5, rest :: binary>>), do: {:ok, rest}

  defp tlv_format(bytes) when is_binary(bytes) do
    {tlv, _} = ber_decode_nif(bytes)
    tlv
  end

  defp tlv_format(bytes), do: bytes

  defp unused_bitlist([], trail, ack), do: {trail + 1, ack}

  defp unused_bitlist([bit | rest], trail, ack), do: unused_bitlist(rest, trail - 1, bit <<< trail ||| ack)
end
