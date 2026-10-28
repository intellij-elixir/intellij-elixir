defmodule MfaCompletion do
  def usage, do: apply(Mfa, :pub<caret>, [])
end
