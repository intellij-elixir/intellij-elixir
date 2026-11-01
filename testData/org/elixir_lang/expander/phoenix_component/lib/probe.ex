defmodule PhoenixComponentFixture.Probe do
  defmacro components(module), do: Macro.escape(module(module, __CALLER__).__components__())

  defmacro global?(module, name), do: module(module, __CALLER__).__global__?(name)

  defmacro called(module, function, assigns), do: Macro.escape(apply(module(module, __CALLER__), function, [Map.new(assigns)]))

  defp module(alias, env), do: Macro.expand(alias, env)
end
