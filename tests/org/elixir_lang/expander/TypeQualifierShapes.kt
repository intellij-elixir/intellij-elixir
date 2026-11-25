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

    const val ALIASED = """defmodule Foo.Bar do
  @type t :: integer()
end

defmodule S2a do
  alias Foo.Bar
  @type t :: Bar.t()
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

    const val CURRENT_MODULE_NESTED = """defmodule S5 do
  defmodule Sub do
    @type t :: integer()
  end

  @type sub :: __MODULE__.Sub.t()
end
"""

    const val MODULE_CREATE = """defmodule S9 do
  Module.create(S9.Created, quote(do: @type(t :: integer())), Macro.Env.location(__ENV__))
  @type created :: S9.Created.t()
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

    const val ERLANG_QUEUE = """defmodule S11 do
  @type t :: :queue.queue()
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

    const val DUPLICATED = """defmodule Dup13 do
  @type t :: integer()
end
"""

    const val OF_DUPLICATED = """defmodule S13 do
  @type t :: Dup13.t()
end
"""

    /** `M.m/0`, a macro whose expansion the expander stops at. */
    const val STOPS = """defmodule M do
  defmacro m, do: (:erlang.send(:erlang.self(), :decline); receive(do: (:decline -> :ok; x -> x), after: (0 -> :ok)))
end
"""

    const val ACTUAL = """defmodule Actual do
  @type t :: integer()
end
"""

    const val FALLBACK = """defmodule Fallback do
end
"""

    /** `Fallback.t()` past a stop, after `alias Actual, as: Fallback`, which the env at the stop misses. */
    const val PAST_A_STOP = """defmodule Caller do
  require M
  M.m()
  alias Actual, as: Fallback
  @type t :: Fallback.t()
end
"""
}
