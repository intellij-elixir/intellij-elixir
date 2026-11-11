package org.elixir_lang.expander

import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageFeature.CONFIG_READ_CONFIG
import org.elixir_lang.language_level.ElixirLanguageFeature.RAISE_ERROR_INFO
import org.elixir_lang.language_level.ElixirLanguageFeature.STRING_SPLIT_TRIMS_IN_BINARY_SPLIT

/**
 * Each control-flow construct a config file runs, over a value known, one not known, one that may raise or one read
 * back from an app's env the file wrote, with a config write, an app's env written then read, or nothing inside it, in
 * the file itself or in an `fn` a file it imports made. A value that may raise is read inside a `try` that rescues it.
 * Each answer is `Config.Reader`'s over `X` unset, `"y"`, `"22"` and `"0"`: where they differ, `Configured`, and a
 * written app's env reads unknown.
 */
class ConfigConstructsHeavyTest : ConfigHeavyTestCase() {
    fun testACase() =
        assertCells(
            Construct.CASE,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
        )

    fun testACond() =
        assertCells(
            Construct.COND,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
        )

    fun testAnIf() =
        assertCells(
            Construct.IF,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
        )

    fun testAnUnless() =
        assertCells(
            Construct.UNLESS,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
        )

    fun testAWith() =
        assertCells(
            Construct.WITH,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
        )

    fun testAWithsElse() =
        assertCells(
            Construct.WITH_ELSE,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
        )

    fun testATrysRescue() =
        assertCells(
            Construct.TRY_RESCUE,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=0 v=:no k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=0 v=:no k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:no j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:no j=Configured k1=1"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:no j=nil k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=0 v=:no j=Configured k1=1"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:no k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:no k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=0 v=:no k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=0 v=:no k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=:no j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:no j=Configured k1=1"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:no j=nil k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=0 v=:no j=Configured k1=1"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=:no k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:no k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testATrysCatch() =
        assertCells(
            Construct.TRY_CATCH,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=0 v=:no k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=0 v=:no k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:no j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:no j=Configured k1=1"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:no j=nil k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=0 v=:no j=Configured k1=1"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:no k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:no k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=0 v=:no k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=0 v=:no k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=:no j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:no j=Configured k1=1"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:no j=nil k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=0 v=:no j=Configured k1=1"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=:no k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:no k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testATrysAfter() =
        assertCells(
            Construct.TRY_AFTER,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=\"1\" k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=\"1\" k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=\"1\" j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=\"1\" j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=\"1\" k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=\"1\" k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=1 v=Configured k1=1", pins = listOf(Pin(null, "k=Configured v=Configured k1=1"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=1 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=Configured v=Configured k1=1"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testATrysElse() =
        assertCells(
            Construct.TRY_ELSE,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAReceive() =
        assertCells(
            Construct.RECEIVE,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testAnFnsCall() =
        assertCells(
            Construct.FN,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testALocalCall() =
        assertCells(
            Construct.LOCAL_CALL,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testARemoteCall() =
        assertCells(
            Construct.REMOTE_CALL,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testATailLoop() =
        assertCells(
            Construct.TAIL_LOOP,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=[:yes] k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=[:yes] j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=[:yes] j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=[:yes] k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testARecursion() =
        assertCells(
            Construct.RECURSION,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=[:yes] k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=[:yes] j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=[:yes] j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=[:yes] k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testAFor() =
        assertCells(
            Construct.FOR_LIST,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=[:yes] k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=[:yes] j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=[:yes] j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=[:yes] k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAForOverABitstring() =
        assertCells(
            Construct.FOR_BITSTRING,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=[:yes] k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=[:yes] j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=[:yes] j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=[:yes] k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testAForsFilter() =
        assertCells(
            Construct.FOR_FILTER,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=[:yes] k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=[:yes] j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=[:yes] j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=[:yes] k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testAForsGeneratorGuard() =
        assertCells(
            Construct.FOR_GUARD,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=[:yes] k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=[:yes] j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=[:yes] j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=[:yes] k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=nil k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=nil k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.WRITTEN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.WRITTEN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.WRITTEN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.WRITTEN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testAForIntoAMap() =
        assertCells(
            Construct.FOR_INTO,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=%{1 => :yes} k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=%{1 => :yes} k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=%{1 => :yes} j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=%{1 => :yes} j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=%{1 => :yes} k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=%{1 => :yes} k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testAForsReduce() =
        assertCells(
            Construct.FOR_REDUCE,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=1 k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=1 k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=1 j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=1 j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=1 k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=1 k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAForsUniq() =
        assertCells(
            Construct.FOR_UNIQ,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=[:yes] k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=[:yes] j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=[:yes] j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=[:yes] k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnAnd() =
        assertCells(
            Construct.AND,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=true k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=true k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=true j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=true j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=true k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=true k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnOr() =
        assertCells(
            Construct.OR,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=0 v=true k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=0 v=true k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=true j=nil k1=1", pins = listOf(Pin(null, "k=0 v=true j=Configured k1=1"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=true j=nil k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=0 v=true j=Configured k1=1"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=true k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=true k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=true k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=true k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=true j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=true j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=true k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=true k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testANot() =
        assertCells(
            Construct.NOT,
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=false k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=false k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testARelaxedAnd() =
        assertCells(
            Construct.RELAXED_AND,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testARelaxedOr() =
        assertCells(
            Construct.RELAXED_OR,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=0 v=\"1\" k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=0 v=\"1\" k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=\"1\" j=nil k1=1", pins = listOf(Pin(null, "k=0 v=\"1\" j=Configured k1=1"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=\"1\" j=nil k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=0 v=\"1\" j=Configured k1=1"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=\"1\" k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=\"1\" k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "k=Configured v=Configured k1=1"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=Configured v=Configured k1=1"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=nil k1=1", pins = listOf(Pin(null, "k=0 v=Configured j=Configured k1=1"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=nil k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "k=0 v=Configured j=Configured k1=1"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAMatch() =
        assertCells(
            Construct.MATCH,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.WRITTEN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
        )

    fun testARaise() =
        assertCells(
            Construct.RAISE,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=\"x\" k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=\"x\" k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=\"x\" j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=\"x\" j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=\"x\" k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=\"x\" k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testAThrow() =
        assertCells(
            Construct.THROW,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=\"1\" k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=\"1\" k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=\"1\" j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=\"1\" j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=\"1\" k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=\"1\" k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testAnExit() =
        assertCells(
            Construct.EXIT,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=\"1\" k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=\"1\" k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=\"1\" j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=\"1\" j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=\"1\" k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=\"1\" k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testAnEnumReduce() =
        assertCells(
            Construct.ENUM_REDUCE,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=1 k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=1 k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=1 j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=1 j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=1 k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=1 k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnEnumEach() =
        assertCells(
            Construct.ENUM_EACH,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:ok k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:ok k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:ok j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:ok j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:ok k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:ok k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=:ok k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=:ok k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=:ok j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:ok j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=:ok k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:ok k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnEnumMap() =
        assertCells(
            Construct.ENUM_MAP,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=[:yes] k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=[:yes] j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=[:yes] j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=[:yes] k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAListsForeach() =
        assertCells(
            Construct.LISTS_FOREACH,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:ok k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:ok k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:ok j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:ok j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:ok k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:ok k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=:ok k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=:ok k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=:ok j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:ok j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=:ok k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:ok k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnIn() =
        assertCells(
            Construct.IN,
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=true k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=true k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
        )

    fun testAnEnumMember() =
        assertCells(
            Construct.MEMBER,
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=true k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=true k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=false k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, "k=0 v=Configured k1=1"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=false k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=0 v=Configured k1=1"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testARelaxedNot() =
        assertCells(
            Construct.RELAXED_NOT,
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=false k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=false k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAMatchQuestion() =
        assertCells(
            Construct.MATCH_QUESTION,
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=true k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=true k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAReraise() =
        assertCells(
            Construct.RERAISE,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=\"x\" k1=1", pins = listOf(Pin(RAISE_ERROR_INFO, "declined"))),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=\"x\" k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=\"x\" j=Configured k1=1", pins = listOf(Pin(RAISE_ERROR_INFO, "declined"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=\"x\" j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=\"x\" k1=1", pins = listOf(Pin(RAISE_ERROR_INFO, "declined"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=\"x\" k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=Configured k1=1", pins = listOf(Pin(RAISE_ERROR_INFO, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(RAISE_ERROR_INFO, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(RAISE_ERROR_INFO, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testAnInARange() =
        assertCells(
            Construct.IN_RANGE,
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=true k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=true k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnInAVariable() =
        assertCells(
            Construct.IN_VARIABLE,
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=true k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=true k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testALocalCapture() =
        assertCells(
            Construct.LOCAL_CAPTURE,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
        )

    fun testACaseNoClauseMatches() =
        assertCells(
            Construct.CASE_UNMATCHED,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=0 v=:rescued k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=0 v=:rescued k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"))),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:rescued j=nil k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=0 v=:rescued j=Configured k1=1"))),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:rescued k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:rescued k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=0 v=:rescued k1=1", pins = listOf(Pin(null, "k=Configured v=Configured k1=1"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=Configured v=Configured k1=1"))),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=Configured j=Configured k1=1"))),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, "k=0 v=Configured j=Configured k1=1"))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=:rescued k1=1", pins = listOf(Pin(null, "k=0 v=Configured k1=1"))),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=:rescued k1=1", pins = listOf(Pin(CONFIG_READ_CONFIG, "k=0 v=Configured k1=1"))),
        )

    fun testAWithWithoutElse() =
        assertCells(
            Construct.WITHOUT_ELSE,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAForIntoABinary() =
        assertCells(
            Construct.FOR_INTO_BINARY,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=\"a\" k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=\"a\" k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=\"a\" j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=\"a\" j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=\"a\" k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=\"a\" k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"), Pin(STRING_SPLIT_TRIMS_IN_BINARY_SPLIT, null))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnApply() =
        assertCells(
            Construct.APPLY,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=:yes k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=:yes j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=:yes j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=:yes k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=:yes k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnEnumFilter() =
        assertCells(
            Construct.ENUM_FILTER,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=[1] k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=[1] k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=[1] j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=[1] j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=[1] k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=[1] k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnEnumFlatMap() =
        assertCells(
            Construct.ENUM_FLAT_MAP,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=[:yes] k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=[:yes] j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=[:yes] j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=[:yes] k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=[:yes] k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnEnumFind() =
        assertCells(
            Construct.ENUM_FIND,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=1 k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=1 k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=1 j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=1 j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=1 k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=1 k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAListsFoldl() =
        assertCells(
            Construct.LISTS_FOLDL,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=1 k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=1 k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=1 j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=1 j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=1 k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=1 k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAListsSort() =
        assertCells(
            Construct.LISTS_SORT,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=[0, 1] k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=[0, 1] k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=[0, 1] j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=[0, 1] j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=[0, 1] k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=[0, 1] k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1", pins = listOf(Pin(null, "declined"))),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised", pins = listOf(Pin(CONFIG_READ_CONFIG, "declined"))),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnUpdateIn() =
        assertCells(
            Construct.UPDATE_IN,
            Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=%{a: :yes} k1=1"),
            Cell(Value.KNOWN, Effect.CONFIG, Origin.IMPORTED, "k=1 v=%{a: :yes} k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.ENV, Origin.HERE, "k=0 v=%{a: :yes} j=Configured k1=1"),
            Cell(Value.KNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=%{a: :yes} j=Configured k1=1", before = "raised"),
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=%{a: :yes} k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=%{a: :yes} k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.HERE, "k=Configured v=Configured k1=1"),
            Cell(Value.RAISING, Effect.CONFIG, Origin.IMPORTED, "k=Configured v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.HERE, "k=0 v=Configured j=Configured k1=1"),
            Cell(Value.RAISING, Effect.ENV, Origin.IMPORTED, "k=0 v=Configured j=Configured k1=1", before = "k=0 v=:rescued j=nil k1=1", pins = listOf(Pin(null, "k=0 v=:rescued j=Configured k1=1"), Pin(CONFIG_READ_CONFIG, null))),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAKeywordHasKey() =
        assertCells(
            Construct.KEYWORD_HAS_KEY,
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=true k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=true k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
        )

    fun testAnEqual() =
        assertCells(
            Construct.EQUAL,
            Cell(Value.KNOWN, Effect.NONE, Origin.HERE, "k=0 v=false k1=1"),
            Cell(Value.KNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=false k1=1", before = "raised"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.UNKNOWN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
            Cell(Value.RAISING, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.RAISING, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "k=0 v=:rescued k1=1"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.HERE, "k=0 v=Configured k1=1"),
            Cell(Value.WRITTEN, Effect.NONE, Origin.IMPORTED, "k=0 v=Configured k1=1", before = "raised"),
        )

    /**
     * A read after one that wrote `:app`'s config starts from none of it: what `:app` has set so far reads `nil`, or
     * `:none` without `Config.read_config/1`. An app's env isn't read here: a config file reads it unknown whether or not
     * a read wrote it.
     */
    fun testAReadStartsFromNoneOfTheConfigAnEarlierReadWrote() {
        val writer = Cell(Value.KNOWN, Effect.CONFIG, Origin.HERE, "k=1 v=:yes k1=1")
        val sentinel =
            "s =\n  try do\n    Config.read_config(:app)\n  rescue\n    _ -> :none\n  end\n\nconfig :app, k: 0, v: s, k1: 1\n"

        assertEquals(
            listOf(writer.expected, onLeg("k=0 v=nil k1=1", "k=0 v=:none k1=1")),
            listOf(answer(Construct.CASE, writer), read(sentinel, listOf("k", "v", "k1"))),
        )
    }

    /**
     * The values a construct reads: known, not known, one that raises where `X` isn't an integer, and, where the
     * construct branches on or compares it, one read back from an app's env the file wrote.
     */
    enum class Family(val known: String, val unknown: String, val raising: String, val written: String? = null) {
        TRUTHY(
            "\"1\"",
            "System.get_env(\"X\")",
            "String.to_integer(System.get_env(\"X\", \"1\"))",
            "(Application.put_env(:w, :x, System.get_env(\"X\")); Application.get_env(:w, :x))",
        ),
        COUNT("1", "byte_size(System.get_env(\"X\", \"\"))", "String.to_integer(System.get_env(\"X\", \"1\"))"),
        LIST("[1]", "String.split(System.get_env(\"X\", \"\"), \",\", trim: true)", "[String.to_integer(System.get_env(\"X\", \"1\"))]"),
        BINARY("\"a\"", "System.get_env(\"X\", \"\")", "<<String.to_integer(System.get_env(\"X\", \"1\"))>>"),
        BOOLEAN("true", "System.get_env(\"X\") != nil", "String.to_integer(System.get_env(\"X\", \"1\")) > 0"),
        TUPLE(
            "{:ok, 1}",
            "if(System.get_env(\"X\"), do: {:ok, 1}, else: {:error, 1})",
            "{:ok, String.to_integer(System.get_env(\"X\", \"1\"))}",
            "(Application.put_env(:w, :x, System.get_env(\"X\")); if(Application.get_env(:w, :x), do: {:ok, 1}, else: {:error, 1}))",
        ),
        MESSAGE("\"x\"", "System.get_env(\"X\", \"x\")", "Integer.to_string(String.to_integer(System.get_env(\"X\", \"1\")))"),
    }

    enum class Value { KNOWN, UNKNOWN, RAISING, WRITTEN }

    enum class Effect(val statement: String?) {
        CONFIG("Config.config(:app, k: 1)"),
        ENV("Application.put_env(:other, :a, 1)"),
        NONE(null),
    }

    enum class Origin { HERE, IMPORTED }

    /**
     * A construct: its [expression] of the value and of the arm it takes, `taken(x)` running the effect then giving `x`,
     * and the module its expression calls, given that arm and the effect as a statement line. One with no module, whose
     * expression takes no arm, is a strict call with no code of its own: an effect inside it is its argument's
     * construct, so it has none.
     */
    enum class Construct(
        val family: Family,
        val expression: (value: String, taken: (String) -> String) -> String,
        val module: ((taken: (String) -> String, statement: String) -> String)? = null,
    ) {
        CASE(Family.TRUTHY, { v, t -> "case $v do\n  nil -> :no\n  _ -> ${t(":yes")}\nend" }),
        COND(Family.TRUTHY, { v, t -> "cond do\n  $v -> ${t(":yes")}\n  true -> :no\nend" }),
        IF(Family.TRUTHY, { v, t -> "if $v, do: ${t(":yes")}, else: :no" }),
        UNLESS(Family.TRUTHY, { v, t -> "unless $v, do: :no, else: ${t(":yes")}" }),
        WITH(Family.TRUTHY, { v, t -> "with x when x != nil <- $v do\n  ${t(":yes")}\nelse\n  _ -> :no\nend" }),
        WITH_ELSE(Family.TRUTHY, { v, t -> "with nil <- $v do\n  :no\nelse\n  _ -> ${t(":yes")}\nend" }),
        TRY_RESCUE(Family.TRUTHY, { v, t -> "try do\n  $v\n  :no\nrescue\n  _ -> ${t(":yes")}\nend" }),
        TRY_CATCH(Family.TRUTHY, { v, t -> "try do\n  $v\n  :no\ncatch\n  _, _ -> ${t(":yes")}\nend" }),
        TRY_AFTER(Family.TRUTHY, { v, t -> "try do\n  $v\nafter\n  ${t(":yes")}\nend" }),
        TRY_ELSE(Family.TRUTHY, { v, t -> "try do\n  $v\nrescue\n  _ -> :rescued\nelse\n  nil -> :no\n  _ -> ${t(":yes")}\nend" }),
        RECEIVE(Family.TRUTHY, { v, t -> "send(self(), $v)\n\nreceive do\n  nil -> :no\n  _ -> ${t(":yes")}\nend" }),
        FN(Family.TRUTHY, { v, t -> "(fn\n   nil -> :no\n   _ -> ${t(":yes")}\n end).($v)" }),
        LOCAL_CALL(
            Family.TRUTHY,
            { v, _ -> "H.g($v)" },
            { t, _ -> "defmodule H do\n  def g(v), do: f(v)\n\n  defp f(nil), do: :no\n  defp f(_), do: ${t(":yes")}\nend\n" },
        ),
        REMOTE_CALL(
            Family.TRUTHY,
            { v, _ -> "H.f($v)" },
            { t, _ -> "defmodule H do\n  def f(nil), do: :no\n  def f(_), do: ${t(":yes")}\nend\n" },
        ),
        TAIL_LOOP(
            Family.COUNT,
            { v, _ -> "H.loop($v, [])" },
            { _, s -> "defmodule H do\n  def loop(0, acc), do: acc\n\n  def loop(n, acc) do\n$s    loop(n - 1, [:yes | acc])\n  end\nend\n" },
        ),
        RECURSION(
            Family.COUNT,
            { v, _ -> "H.rec($v)" },
            { _, s -> "defmodule H do\n  def rec(0), do: []\n\n  def rec(n) do\n    r = rec(n - 1)\n$s    [:yes | r]\n  end\nend\n" },
        ),
        FOR_LIST(Family.LIST, { v, t -> "for _ <- $v, do: ${t(":yes")}" }),
        FOR_BITSTRING(Family.BINARY, { v, t -> "for <<_ <- $v >>, do: ${t(":yes")}" }),
        FOR_FILTER(Family.TRUTHY, { v, t -> "for _ <- [1], $v, do: ${t(":yes")}" }),
        FOR_GUARD(Family.TRUTHY, { v, t -> "for x when is_binary(x) <- [$v], do: ${t(":yes")}" }),
        FOR_INTO(Family.LIST, { v, t -> "for x <- $v, into: %{}, do: ${t("{x, :yes}")}" }),
        FOR_REDUCE(Family.LIST, { v, t -> "for _ <- $v, reduce: 0 do\n  acc -> ${t("acc + 1")}\nend" }),
        FOR_UNIQ(Family.LIST, { v, t -> "for _ <- $v, uniq: true, do: ${t(":yes")}" }),
        AND(Family.BOOLEAN, { v, t -> "$v and ${t("true")}" }),
        OR(Family.BOOLEAN, { v, t -> "$v or ${t("true")}" }),
        NOT(Family.BOOLEAN, { v, _ -> "not($v)" }),
        RELAXED_AND(Family.TRUTHY, { v, t -> "$v && ${t(":yes")}" }),
        RELAXED_OR(Family.TRUTHY, { v, t -> "$v || ${t(":yes")}" }),
        MATCH(Family.TUPLE, { v, t -> "{:ok, _} = $v\n${t(":yes")}" }),
        RAISE(Family.MESSAGE, { v, t -> "try do\n  raise $v\nrescue\n  e in RuntimeError -> ${t("e.message")}\nend" }),
        THROW(Family.TRUTHY, { v, t -> "try do\n  throw($v)\ncatch\n  v -> ${t("v")}\nend" }),
        EXIT(Family.TRUTHY, { v, t -> "try do\n  exit($v)\ncatch\n  :exit, v -> ${t("v")}\nend" }),
        ENUM_REDUCE(Family.LIST, { v, t -> "Enum.reduce($v, 0, fn _, acc -> ${t("acc + 1")} end)" }),
        ENUM_EACH(Family.LIST, { v, t -> "Enum.each($v, fn _ -> ${t(":yes")} end)" }),
        ENUM_MAP(Family.LIST, { v, t -> "Enum.map($v, fn _ -> ${t(":yes")} end)" }),
        LISTS_FOREACH(Family.LIST, { v, t -> ":lists.foreach(fn _ -> ${t(":yes")} end, $v)" }),
        IN(Family.TRUTHY, { v, _ -> "$v in [\"1\", \"y\"]" }),
        MEMBER(Family.LIST, { v, _ -> "Enum.member?($v, 1)" }),
        RELAXED_NOT(Family.TRUTHY, { v, _ -> "!$v" }),
        MATCH_QUESTION(Family.TUPLE, { v, _ -> "match?({:ok, _}, $v)" }),
        RERAISE(Family.MESSAGE, { v, t -> "try do\n  try do\n    raise $v\n  rescue\n    e -> reraise e, __STACKTRACE__\n  end\nrescue\n  e in RuntimeError -> ${t("e.message")}\nend" }),
        IN_RANGE(Family.COUNT, { v, _ -> "$v in 1..3" }),
        IN_VARIABLE(Family.TRUTHY, { v, _ -> "r = [\"1\", \"y\"]\n$v in r" }),
        LOCAL_CAPTURE(
            Family.TRUTHY,
            { v, _ -> "H.g($v)" },
            { t, _ -> "defmodule H do\n  def g(v), do: (&f/1).(v)\n\n  defp f(nil), do: :no\n  defp f(_), do: ${t(":yes")}\nend\n" },
        ),
        CASE_UNMATCHED(Family.TRUTHY, { v, t -> "try do\n  case $v do\n    nil -> ${t(":no")}\n  end\nrescue\n  _ -> :rescued\nend" }),
        WITHOUT_ELSE(Family.TRUTHY, { v, t -> "with x when x != nil <- $v do\n  ${t(":yes")}\nend" }),
        FOR_INTO_BINARY(Family.LIST, { v, t -> "for _ <- $v, into: \"\", do: ${t("\"a\"")}" }),
        APPLY(Family.TRUTHY, { v, t -> "apply(\n  fn\n    nil -> :no\n    _ -> ${t(":yes")}\n  end,\n  [$v]\n)" }),
        ENUM_FILTER(Family.LIST, { v, t -> "Enum.filter($v, fn _ -> ${t("true")} end)" }),
        ENUM_FLAT_MAP(Family.LIST, { v, t -> "Enum.flat_map($v, fn _ -> ${t("[:yes]")} end)" }),
        ENUM_FIND(Family.LIST, { v, t -> "Enum.find($v, fn _ -> ${t("true")} end)" }),
        LISTS_FOLDL(Family.LIST, { v, t -> ":lists.foldl(fn _, acc -> ${t("acc + 1")} end, 0, $v)" }),
        LISTS_SORT(Family.LIST, { v, t -> ":lists.sort(fn _, _ -> ${t("true")} end, [0 | $v])" }),
        UPDATE_IN(Family.TRUTHY, { v, t -> "update_in(%{a: $v}, [:a], fn\n  nil -> :no\n  _ -> ${t(":yes")}\nend)" }),
        KEYWORD_HAS_KEY(Family.TUPLE, { v, _ -> "Keyword.has_key?([$v], :ok)" }),
        EQUAL(Family.TRUTHY, { v, _ -> "$v == \"y\"" }),
    }

    /**
     * One reading of a construct: over [value], with [effect] inside it, from [origin]. Elixir's answer is [expected], or
     * [before] on a leg without `Config.read_config/1`. Where it has [pins], it asserts the plugin's answer now, which its
     * #4508 entry moves to Elixir's.
     */
    class Cell(
        val value: Value,
        val effect: Effect,
        val origin: Origin,
        val expected: String,
        val before: String = expected,
        val pins: List<Pin> = emptyList(),
    )

    /**
     * On the legs [since] is sufficient for (every leg where `null`), until its cell's next pin, the plugin's answer now:
     * [answer], or Elixir's where `null`.
     */
    class Pin(val since: ElixirLanguageFeature?, val answer: String?)

    /** Each of [construct]'s [cells] read alone ([answer]), in one assertion, so no cell's answer hides another's. */
    private fun assertCells(construct: Construct, vararg cells: Cell) {
        val level = legLevel()
        val expected = cells.joinToString("\n") { cell ->
            val pin = cell.pins.lastOrNull { it.since?.isSufficient(level) != false }

            line(cell, pin?.answer ?: onLeg(cell.expected, cell.before))
        }
        val actual = cells.joinToString("\n") { line(it, answer(construct, it)) }

        assertEquals(expected, actual)
    }

    private fun line(cell: Cell, answer: String) = "${cell.value} ${cell.effect} ${cell.origin}: $answer"

    private fun answer(construct: Construct, cell: Cell): String {
        val (config, extra) = files(construct, cell.value, cell.effect, cell.origin)

        return read(config, listOfNotNull("k", "v", "j".takeIf { cell.effect == Effect.ENV }, "k1"), extra)
    }

    /**
     * [keys] as [config] and the [extra] files beside it set them, from a content root of their own, which is removed
     * once read.
     */
    private fun read(config: String, keys: List<String>, extra: Array<Pair<String, String>> = emptyArray()): String {
        val roots = ModuleRootManager.getInstance(module).contentRoots.toSet()

        try {
            return keys(config, *keys.toTypedArray(), extra = extra)
        } finally {
            ModuleRootManager.getInstance(module).contentRoots
                .filterNot(roots::contains)
                .forEach { PsiTestUtil.removeContentEntry(module, it) }
        }
    }

    /** `config/config.exs`, less its `import Config`, and the files beside it. */
    private fun files(construct: Construct, value: Value, effect: Effect, origin: Origin): Pair<String, Array<Pair<String, String>>> {
        val taken = { x: String -> effect.statement?.let { "($it; $x)" } ?: x }
        val read = when (value) {
            Value.KNOWN -> construct.family.known
            Value.UNKNOWN -> construct.family.unknown
            Value.RAISING -> construct.family.raising
            Value.WRITTEN -> checkNotNull(construct.family.written)
        }
        val expression = construct.expression(read, taken)
        val imported = origin == Origin.IMPORTED
        val extra: Array<Pair<String, String>> =
            if (imported) {
                arrayOf(
                    "config/o.exs" to "import Config\n\nconfig :fns,\n  f: fn ->\n${indent(expression)}\n  end\n",
                )
            } else {
                emptyArray()
            }
        val body = if (imported) "Config.read_config(:fns)[:f].()" else expression
        val bound = when {
            value == Value.RAISING -> "v =\n  try do\n${indent(body)}\n  rescue\n    _ -> :rescued\n  end\n"
            '\n' in body -> "v =\n  (\n${indent(body)}\n  )\n"
            else -> "v = $body\n"
        }
        val module = construct.module?.let { it(taken, effect.statement?.let { s -> "    $s\n" } ?: "") + "\n" } ?: ""
        val config = buildString {
            append("config :app, k: 0\n\n")
            append(module)
            if (imported) append("import_config \"o.exs\"\n\n")
            append(bound)
            append("\nconfig :app, v: v\n")
            if (effect == Effect.ENV) append("config :app, j: Application.get_env(:other, :a)\n")
            append("config :app, k1: 1\n")
        }

        return config to extra
    }

    /** [text], each line not empty four spaces in. */
    private fun indent(text: String) = text.split('\n').joinToString("\n") { if (it.isEmpty()) it else "    $it" }
}
