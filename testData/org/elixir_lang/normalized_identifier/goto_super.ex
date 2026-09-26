defprotocol NormalizedSuper do
  def snoć(t)
end

defimpl NormalizedSuper, for: List do
  def sn<caret>oć(t), do: t
end
