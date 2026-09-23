package org.elixir_lang.structure_view.element.modular

import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewLongNameLocation
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

/** A nested `defmodule`'s long name is every module it is written in, whatever runs it in between. */
class ModuleLongNameTest : PlatformTestCase() {
    fun testAModuleNestedDirectly() =
        assertEquals("Foo.Bar.Baz", longName("defmodule Foo do\n  defmodule Bar do\n    defmodule Baz do\n    end\n  end\nend\n"))

    fun testAModuleInAnFnPassedToACall() =
        assertEquals(
            "Foo.Bar.Baz",
            longName("defmodule Foo do\n  defmodule Bar do\n    Enum.each([1], fn _ -> defmodule Baz do\n    end end)\n  end\nend\n")
        )

    /** An `fn` written as a statement runs in the body it is written in, not in the module above it. */
    fun testAModuleInAnFnStatement() =
        assertEquals(
            "Foo.Bar.Baz",
            longName("defmodule Foo do\n  defmodule Bar do\n    fn -> defmodule Baz do\n    end end\n  end\nend\n")
        )

    /** A keyword pair that is not a `do` block only carries the `fn`, so the module is still the one written in. */
    fun testAModuleInAnFnHeldInAKeywordList() =
        assertEquals(
            "Foo.Bar.Baz",
            longName("defmodule Foo do\n  defmodule Bar do\n    [a: fn -> defmodule Baz do\n    end end]\n  end\nend\n")
        )

    /** A module under an `if` or `case` is named for the module around it, as Elixir names it, not for the construct. */
    fun testAModuleUnderAnIf() =
        assertEquals("Foo.Baz", longName("defmodule Foo do\n  if true do\n    defmodule Baz do\n    end\n  end\nend\n"))

    fun testAModuleUnderACase() =
        assertEquals(
            "Foo.Baz",
            longName("defmodule Foo do\n  case :ok do\n    :ok ->\n      defmodule Baz do\n      end\n  end\nend\n")
        )

    private fun longName(code: String): String {
        myFixture.configureByText("long_name.ex", code)
        val baz = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).single { it.text.startsWith("defmodule Baz") }

        return ElementDescriptionUtil.getElementDescription(baz, UsageViewLongNameLocation.INSTANCE)
    }
}
