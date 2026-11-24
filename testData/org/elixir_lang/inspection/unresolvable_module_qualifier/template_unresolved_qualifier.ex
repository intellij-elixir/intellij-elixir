defmodule Caller do
  def render(assigns) do
    ~H"""
    <p><%= <error descr="Module 'Missing' is not defined or aliased in this scope">Missing</error>.f() %></p>
    """
  end
end
