package org.elixir_lang.mix

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirTuple

/**
 * Pins how [Dep.from] sorts a Mix dep's options.
 *
 * Every option lands in one of three arms: ignored because it cannot move the dep's directory,
 * handled because it can, or reported through [org.elixir_lang.errorreport.Logger] because the
 * plugin has never seen it. The reported arm is the only one a user ever notices, and none of the
 * three was asserted anywhere before this class - an ignored option and an unknown one both return
 * `deps/<name>`, so the logged error is the only difference between them.
 *
 * The option names here are [Dep.from]'s own list, so dropping one from either without the other
 * fails.
 */
class DepTest : PlatformTestCase() {
    /**
     * Options Mix accepts that genuinely cannot change where the dep is checked out.
     *
     * `sha`, `commit` and `organization` are absent from `Mix.Tasks.Deps`' documented set. The two
     * named constants are misspellings that widely installed packages ship, referenced by name rather
     * than spelled out so a future reader does not "correct" them.
     *
     * `sparse` and `subdir` are absent because they are not path-neutral - `Mix.SCM.Git` joins each
     * onto the dep's destination. They are handled options, covered by their own cases below.
     *
     * `only` and `optional` stay here because neither moves the dep, but they are no longer *merely*
     * path-neutral: inside a dependency's own `mix.exs` either can drop the dep entirely, which is
     * what the `isDependency` cases below assert.
     */
    private val pathNeutralOptions = listOf(
        "allow_pre", "app", "branch", "commit", "compile", "depth", "env", "git", "github", "hex",
        "manager", "only", "optional", "organization", "override", "ref", "repo", "runtime",
        GUARDIAN_RUNTIME_TYPO, "sha", "submodules", "system_env", "tag", "targets",
        EDELIVER_DISTILLERY_WARN_MISSING, "warn_if_outdated"
    )

    fun testPathNeutralOptionsAreNotReported() {
        val depTuples = pathNeutralOptions.joinToString(",\n") { "{:dep_$it, \"~> 1.0\", $it: true}" }

        val (deps, errorTitles) = depsFrom(depTuples)

        assertEmpty("No path-neutral option may be reported as unknown", errorTitles)
        assertEquals("Expected every dep tuple to parse", pathNeutralOptions.size, deps.size)

        pathNeutralOptions.forEachIndexed { index, option ->
            assertEquals("`$option` must not change the dep path", "deps/dep_$option", deps[index]?.path)
            assertEquals("`$option` must not change the dep type", Dep.Type.LIBRARY, deps[index]?.type)
        }
    }

    fun testUnknownOptionIsReported() {
        val (deps, errorTitles) = depsFrom("{:my_dep, \"~> 1.0\", not_a_mix_dep_option: true}")

        assertEquals(
            listOf(
                "Don't know if Mix.Dep option `not_a_mix_dep_option` is important for determining " +
                        "location of dependency"
            ),
            errorTitles
        )
        assertEquals("An unknown option must still leave the accumulated dep", "deps/my_dep", deps.single()?.path)
    }

    /**
     * A quoted atom is a legal dep name: `{:"my-dep", "~> 1.0"}` checks out under `deps/my-dep`. The
     * quoting was reported as an unconvertible name and the dep dropped, so nothing under it resolved.
     */
    fun testQuotedAtomNameIsTheDepName() {
        val (deps, errorTitles) = depsFrom("{:\"my-dep\", \"~> 1.0\"}")

        assertEmpty("A quoted atom is an ordinary dep name", errorTitles)
        assertEquals("deps/my-dep", deps.single()?.path)
    }

    /**
     * A `path:` value the plugin cannot read as a string leaves the dep where it was, exactly as a
     * `path:` given by a call does; it is not something to report.
     */
    fun testUnreadablePathValueLeavesDepsPath() {
        val (deps, errorTitles) = depsFrom("{:my_dep, path: ~s(../my_dep)}")

        assertEmpty("An unreadable `path` value is not an error", errorTitles)
        assertEquals("deps/my_dep", deps.single()?.path)
    }

    fun testPathOptionReplacesDepsPath() {
        val (deps, errorTitles) = depsFrom("{:my_dep, path: \"../my_dep\"}")

        assertEmpty("`path` is handled, so it must not be reported", errorTitles)
        assertEquals("../my_dep", deps.single()?.path)
    }

    /** A quoted key is the atom it spells, so `"path":` is `path:` to Mix. */
    fun testQuotedPathOptionReplacesDepsPath() {
        val (deps, errorTitles) = depsFrom("{:my_dep, \"path\": \"../my_dep\"}")

        assertEmpty("`\"path\":` is `path:`, so it must not be reported", errorTitles)
        assertEquals("../my_dep", deps.single()?.path)
    }

    fun testInUmbrellaOptionUsesUmbrellaApplicationPath() {
        val (deps, errorTitles) = depsFrom("{:my_dep, in_umbrella: true}")

        assertEmpty("`in_umbrella` is handled, so it must not be reported", errorTitles)
        assertEquals("apps/my_dep", deps.single()?.path)
        assertEquals(Dep.Type.MODULE, deps.single()?.type)
    }

    // ---------------------------------------------------------------------
    // `sparse:` / `subdir:` - both join onto the dep's destination
    // ---------------------------------------------------------------------

    fun testSparseAppendsToDepsPath() {
        val (deps, errorTitles) = depsFrom("{:d, git: \"u\", sparse: \"s\"}")

        assertEmpty("`sparse` is handled, so it must not be reported", errorTitles)
        assertEquals("deps/d/s", deps.single()?.path)
    }

    fun testSubdirAppendsToDepsPath() {
        val (deps, errorTitles) = depsFrom("{:d, git: \"u\", subdir: \"sd\"}")

        assertEmpty("`subdir` is handled, so it must not be reported", errorTitles)
        assertEquals("deps/d/sd", deps.single()?.path)
    }

    fun testSparseAndSubdirBothAppend() {
        val (deps, _) = depsFrom("{:d, git: \"u\", sparse: \"s\", subdir: \"sd\"}")

        assertEquals("deps/d/s/sd", deps.single()?.path)
    }

    /**
     * `Mix.SCM.Git.accepts_options` pipes `sparse_opts()` then `subdir_opts()`, so the order is
     * Mix's, not the source's. Written reversed so inheriting the fold's order fails.
     */
    fun testSparseIsAppliedBeforeSubdirWhateverTheSourceOrder() {
        val (deps, _) = depsFrom("{:d, git: \"u\", subdir: \"sd\", sparse: \"s\"}")

        assertEquals("deps/d/s/sd", deps.single()?.path)
    }

    fun testSubdirAppliesToAGithubDep() {
        val (deps, _) = depsFrom("{:d, github: \"o/r\", subdir: \"sd\"}")

        assertEquals("deps/d/sd", deps.single()?.path)
    }

    /**
     * `Mix.SCM.Git.accepts_options` returns nil without `git:`/`github:`, so `Mix.SCM.Path` wins and
     * sets the destination from `path:` alone - `subdir` never applies.
     */
    fun testSubdirIsIgnoredForAPathDep() {
        val (deps, _) = depsFrom("{:d, path: \"vendor/d\", subdir: \"sd\"}")

        assertEquals("vendor/d", deps.single()?.path)
    }

    fun testSubdirIsIgnoredWithoutAnScmOption() {
        val (deps, _) = depsFrom("{:d, subdir: \"sd\"}")

        assertEquals("deps/d", deps.single()?.path)
    }

    fun testSubdirIsIgnoredForAnInUmbrellaDep() {
        val (deps, _) = depsFrom("{:d, in_umbrella: true, subdir: \"sd\"}")

        assertEquals("apps/d", deps.single()?.path)
        assertEquals(Dep.Type.MODULE, deps.single()?.type)
    }

    /** A non-literal value cannot be read, so it is a no-op - as `path:` already treats a call. */
    fun testNonLiteralSubdirIsANoOp() {
        val (deps, _) = depsFrom("{:d, git: \"u\", subdir: helper()}")

        assertEquals("deps/d", deps.single()?.path)
    }

    // ---------------------------------------------------------------------
    // `only:` / `optional:` inside a dependency's own mix.exs
    // ---------------------------------------------------------------------

    fun testEnvironmentRestrictedDepOfADepIsDropped() {
        val (deps, _) = depsFrom("{:d, \"~> 1.0\", only: [:test]}", isDependency = true)

        assertNull("`only:` excluding :prod must drop the dep", deps.single())
    }

    fun testSingleAtomEnvironmentRestrictedDepOfADepIsDropped() {
        val (deps, _) = depsFrom("{:d, \"~> 1.0\", only: :test}", isDependency = true)

        assertNull("The single-atom `only:` form must drop the dep too", deps.single())
    }

    fun testOptionalDepOfADepIsDropped() {
        val (deps, _) = depsFrom("{:d, \"~> 1.0\", optional: true}", isDependency = true)

        assertNull("`optional: true` must drop the dep", deps.single())
    }

    /** The two gates are independent: clearing the environment one does not rescue an optional dep. */
    fun testOptionalDepOfADepIsDroppedEvenWhenOnlyIncludesProd() {
        val (deps, _) = depsFrom("{:d, \"~> 1.0\", optional: true, only: [:prod]}", isDependency = true)

        assertNull("`optional: true` must drop the dep whatever `only:` says", deps.single())
    }

    fun testProdOnlyDepOfADepIsKept() {
        val (deps, _) = depsFrom("{:d, \"~> 1.0\", only: [:prod]}", isDependency = true)

        assertEquals("deps/d", deps.single()?.path)
    }

    fun testDepOfADepIsKeptWhenOnlyIncludesProdAmongOthers() {
        val (deps, _) = depsFrom("{:d, \"~> 1.0\", only: [:dev, :prod]}", isDependency = true)

        assertEquals("deps/d", deps.single()?.path)
    }

    fun testOptionalFalseDepOfADepIsKept() {
        val (deps, _) = depsFrom("{:d, \"~> 1.0\", optional: false}", isDependency = true)

        assertEquals("deps/d", deps.single()?.path)
    }

    /**
     * Every `only:` shape the plugin cannot read must keep the dep. Dropping one that is physically
     * present costs resolution and completion; keeping one Mix never fetches costs an empty
     * placeholder library, which is the status quo.
     */
    fun testUnreadableOnlyValuesKeepTheDep() {
        val unreadable = listOf(
            "only: true",
            "only: @envs",
            "only: Mix.env()",
            "only: [:dev] ++ other()",
        )

        unreadable.forEach { option ->
            val (deps, _) = depsFrom("{:d, \"~> 1.0\", $option}", isDependency = true)

            assertEquals("`$option` cannot be read, so the dep must be kept", "deps/d", deps.single()?.path)
        }
    }

    /** A quoted atom is the atom it quotes to, so these read as `:test` and `:prod` and gate the dep as the unquoted forms do. */
    fun testQuotedOnlyValuesAreReadAsTheirAtoms() {
        val (testOnly, _) = depsFrom("{:d, \"~> 1.0\", only: :\"test\"}", isDependency = true)
        val (testOnlyInList, _) = depsFrom("{:d, \"~> 1.0\", only: [:\"te\\x73t\"]}", isDependency = true)
        val (prodAndTest, _) = depsFrom("{:d, \"~> 1.0\", only: [:\"test\", :\"prod\"]}", isDependency = true)

        assertNull("`only: :\"test\"` excludes :prod", testOnly.single())
        assertNull("`only: [:\"te\\x73t\"]` excludes :prod", testOnlyInList.single())
        assertEquals("deps/d", prodAndTest.single()?.path)
    }

    fun testQuotedTrueIsTrue() {
        val (optional, _) = depsFrom("{:d, \"~> 1.0\", optional: :true}", isDependency = true)
        val (quotedOptional, _) = depsFrom("{:d, \"~> 1.0\", optional: :\"true\"}", isDependency = true)

        assertNull("`optional: :true` is `optional: true`", optional.single())
        assertNull("`optional: :\"true\"` is `optional: true`", quotedOptional.single())
    }

    /** `{:"my\x2Ddep", ...}` is `:"my-dep"`. */
    fun testEscapeInAQuotedNameIsDecoded() {
        val (deps, errorTitles) = depsFrom("{:\"my\\x2Ddep\", \"~> 1.0\"}")

        assertEmpty(errorTitles)
        assertEquals("my-dep", deps.single()?.application)
        assertEquals("deps/my-dep", deps.single()?.path)
    }

    // ---------------------------------------------------------------------
    // Options written as an explicit list
    // ---------------------------------------------------------------------

    /**
     * `{:dep, "~> 1.0", [optional: true]}` is the same declaration as one without the brackets, and
     * packages in the wild write both - `db_connection` brackets its `optional:` where `ecto` does
     * not. Every option was dropped for the bracketed form, so nothing below was ever read.
     */
    fun testBracketedOptionalIsHonoured() {
        val (deps, _) = depsFrom("{:d, \"~> 1.0\", [optional: true]}", isDependency = true)

        assertNull("Bracketed `optional: true` must drop the dep", deps.single())
    }

    fun testBracketedOnlyIsHonoured() {
        val (deps, _) = depsFrom("{:d, \"~> 1.0\", [only: [:test]]}", isDependency = true)

        assertNull("Bracketed `only:` excluding :prod must drop the dep", deps.single())
    }

    fun testBracketedPathIsHonoured() {
        val (deps, errorTitles) = depsFrom("{:d, [path: \"../d\"]}")

        assertEmpty("`path` is handled, bracketed or not", errorTitles)
        assertEquals("../d", deps.single()?.path)
    }

    fun testBracketedInUmbrellaIsHonoured() {
        val (deps, _) = depsFrom("{:d, [in_umbrella: true]}")

        assertEquals("apps/d", deps.single()?.path)
        assertEquals(Dep.Type.MODULE, deps.single()?.type)
    }

    fun testBracketedSubdirIsHonoured() {
        val (deps, _) = depsFrom("{:d, [git: \"u\", subdir: \"sd\"]}")

        assertEquals("deps/d/sd", deps.single()?.path)
    }

    /** An unknown option must still be reported when bracketed, or the tripwire has a blind spot. */
    fun testBracketedUnknownOptionIsReported() {
        val (_, errorTitles) = depsFrom("{:d, \"~> 1.0\", [not_a_mix_dep_option: true]}")

        assertEquals(
            listOf(
                "Don't know if Mix.Dep option `not_a_mix_dep_option` is important for determining " +
                        "location of dependency"
            ),
            errorTitles
        )
    }

    /**
     * Parses [depTuples] as the `deps` of a `mix.exs` and runs [Dep.from] over each tuple, returning
     * the deps in source order alongside the title of every error [Dep] logged.
     */
    private fun depsFrom(depTuples: String, isDependency: Boolean = false): Pair<List<Dep?>, List<String>> {
        val (deps, loggedErrors) = captureLoggedErrors {
            val psiFile = myFixture.configureByText(
                "mix.exs",
                """
                defmodule Sample.MixProject do
                  def project do
                    [
                      deps: [
                        $depTuples
                      ]
                    ]
                  end
                end
                """.trimIndent()
            )

            PsiTreeUtil.findChildrenOfType(psiFile, ElixirTuple::class.java).map { Dep.from(it, isDependency) }
        }

        return Pair(
            deps,
            loggedErrors.filter { it.category == Dep::class.java.name }.map { it.title ?: it.message }
        )
    }
}
