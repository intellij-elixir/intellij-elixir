# The beams HeldAgainstFreshHeavyTest copies into its project, made with Elixir 1.20.4 on OTP 29.0.6. Run from this
# directory: `mise exec elixir@1.20.4-otp-29 erlang@29.0.6 -- elixir generate.exs`.
#
# - v1/Elixir.HeldPool.N<i>.beam, a module with one version, for the rows that take `pool()`;
# - v1/ and v2/Elixir.HeldPool.R<i>.beam, a module whose second version also exports g/1, for the rows that take `pool2()`;
# - Elixir.Dep1.beam, the dep's own module;
# - erl/held_erl_pool.beam, an Erlang module, from held_erl_pool.erl.
one_version = 42
two_versions = 12

write = fn dir, source ->
  File.mkdir_p!(dir)

  for {module, binary} <- Code.compile_string(source) do
    File.write!(Path.join(dir, "#{module}.beam"), binary)
  end
end

pool = fn name, extra ->
  """
  defmodule HeldPool.#{name} do
    defstruct [:a]
    def f(x), do: x
    #{extra}defmacro m, do: 1
  end
  """
end

for i <- 1..one_version, do: write.("v1", pool.("N#{i}", ""))

for i <- 1..two_versions do
  write.("v1", pool.("R#{i}", ""))
  write.("v2", pool.("R#{i}", "def g(x), do: x\n"))
end

write.(".", "defmodule Dep1 do\n  def f, do: 1\nend\n")

{:ok, module, binary} = :compile.file(~c"erl/held_erl_pool.erl", [:binary])
File.write!("erl/#{module}.beam", binary)
