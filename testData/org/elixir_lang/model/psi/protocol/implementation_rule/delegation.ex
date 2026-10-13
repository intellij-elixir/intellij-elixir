defprotocol Size do
  def size(data)
end

defimpl Size, for: Atom do
  defdelegate size(atom), to: Sizes
end
