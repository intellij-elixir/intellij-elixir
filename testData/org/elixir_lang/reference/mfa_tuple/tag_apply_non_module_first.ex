defmodule Usage do
  def example do
    apply(:error, :<caret>f, [])
  end
end
