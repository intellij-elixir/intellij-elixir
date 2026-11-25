package org.elixir_lang.expander

/** Typespecs that name a type of another module, each constant named for the placement of the module it names. */
internal object TypeQualifierShapes {
    const val STRING_TYPES = """defmodule S1 do
  @type t :: String.t()
  @type nope :: String.nope()
  @spec f(String.t()) :: String.t()
  def f(s), do: s
end
"""

    const val NESTED_BEFORE_AND_AFTER = """defmodule Outer do
  @type before :: Inner.t()
  @spec b(Inner.t()) :: :ok
  def b(_), do: :ok

  defmodule Inner do
    @type t :: integer()
  end

  @type later :: Inner.t()
end
"""

    const val MISSING = """defmodule S10 do
  @type t :: Missing.t()
  @spec f(Missing.t()) :: :ok
  def f(_), do: :ok
end
"""

    const val MISSING_UNDER_IF = """if Application.get_env(:elixir, :s10_defines_missing) do
  defmodule Missing do
    @type t :: term
  end
end
"""

    const val QUOTED_ALIAS_TARGETS = """defmodule Q12.A do
  @type t :: integer()
end

defmodule Q12.B do
  @type t :: integer()
end
"""

    const val QUOTED_ALIAS_USING = """defmodule Quoting12 do
  defmacro __using__(_) do
    quote do
      alias Q12.B
      @spec qb() :: B.t()
      def qb, do: 1
    end
  end
end
"""

    const val QUOTED_ALIAS_USER = """defmodule S12 do
  use Quoting12
end
"""
}
