defmodule ErlangAtomQualifierHoverFunctionTest do
  def test do
	:queue.<caret>cons(:item, :queue.new())
  end
end
