defmodule Greeter do
  def greet(name, greeting \\ "Hello"), do: "#{greeting}, #{name}!"

  def welcome(user), do: greet(/*<# name: #>*/user.name, /*<# greeting: #>*/"Welcome")
end
