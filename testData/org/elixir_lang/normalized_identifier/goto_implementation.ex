defprotocol NormalizedImplementation do
  def sn<caret>oć(t)
end

defimpl NormalizedImplementation, for: List do
  def snoć(t), do: t
end
