defprotocol NormalizedProtocolGutter do
  def snoć(t)
end

defimpl NormalizedProtocolGutter, for: List do
  def snoć(t), do: t
end
