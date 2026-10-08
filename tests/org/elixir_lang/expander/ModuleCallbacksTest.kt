package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.NameArity
import org.elixir_lang.expander.ModuleExports.Behaviour

/**
 * The callbacks the leg's [Exports] gives for each module in its Elixir and OTP ebins, against what Elixir's
 * `behaviour_info(:callbacks)` returns when the leg loads it. A module the leg reads as having none must have no
 * `behaviour_info/1`; one it can't read is left out, as is one the node can't load, which the failure lists.
 */
class ModuleCallbacksTest : ProbeTestCase() {
    fun testEveryBehaviourTheLegReadsGivesTheCallbacksElixirDoes() {
        val (read, probed, unloaded) = compared()

        assertEquals("behaviours compared (the node loads no $unloaded)", emptyList<String>(), mismatches(read, probed))
        assertTrue("Elixir's and OTP's behaviours are compared: ${read.keys}", "Elixir.Access" in read && "gen_server" in read)
    }

    fun testALostCallbackIsAMismatch() {
        val (read, probed) = compared()
        val module = read.entries.first { it.value.size > 1 }
        val lost = read + (module.key to module.value.dropLast(1))

        assertEquals(listOf(module.key), mismatches(lost, probed).map { it.substringBefore(":") })
    }

    fun testABehaviourOnlyOneSideHasIsAMismatch() {
        val (read, probed) = compared()
        val module = read.keys.first()

        assertEquals(
            listOf("$module: only Elixir has callbacks ${probed[module]}"),
            mismatches(read - module, probed),
        )
        assertEquals(
            listOf("$module: only the leg read callbacks ${read[module]}"),
            mismatches(read, probed - module),
        )
    }

    /** An Elixir module always keeps its `@callback`s; an Erlang one whose `behaviour_info/1` is written out has none. */
    fun testOnlyAnErlangBehaviourIsUnreadable() {
        val unreadable = modules().filter { behaviour(it) == Behaviour.Unreadable }

        assertEquals("unreadable Elixir behaviours", emptyList<String>(), unreadable.filter { it.startsWith("Elixir.") })
    }

    /** What the leg read and the node probed, for each module the leg reads and the node loads. */
    private data class Compared(
        val read: Map<String, List<NameArity>>,
        val probed: Map<String, List<NameArity>>,
        val unloaded: Set<String>,
    )

    /**
     * The callbacks read, and those probed, for every readable module. A module the leg can't read is not compared, and
     * one the node can't load is [Compared.unloaded].
     */
    private fun compared(): Compared {
        val present = modules()
        val unreadable = present.filter { behaviour(it) == Behaviour.Unreadable }.toSet()
        val (probed, unloaded) = probed(present)

        return Compared(
            readCallbacks().filterKeys { it !in unloaded },
            probed.filterKeys { it !in unreadable },
            unloaded,
        )
    }

    private fun modules(): List<String> = legModules().sorted().filter { legExports.of(it) is ModuleExports.Present }

    private fun behaviour(module: String) = (legExports.of(module) as ModuleExports.Present).behaviour

    /** Each readable behaviour's callbacks, sorted. */
    private fun readCallbacks(): Map<String, List<NameArity>> =
        modules().mapNotNull { module ->
            (behaviour(module) as? Behaviour.Callbacks)?.let { module to it.callbacks.sorted() }
        }.toMap()

    /**
     * Each of [modules] the quoter's node loads that has `behaviour_info/1`, with `behaviour_info(:callbacks)` sorted,
     * and those it doesn't load: an OTP application off the node's code path isn't loaded.
     */
    private fun probed(modules: List<String>): Pair<Map<String, List<NameArity>>, Set<String>> {
        // Names are binaries made atoms at run time: a quoted atom per module makes the compiler warn once for each.
        val list = modules.sorted().joinToString(", ") { "\"$it\"" }
        val compiled = harness.compileSource(
            """
            for name <- [$list] do
              m = String.to_atom(name)

              cond do
                not Code.ensure_loaded?(m) -> IntellijElixir.Quoter.Probe.send(__ENV__, {m, :unloaded})
                function_exported?(m, :behaviour_info, 1) ->
                  IntellijElixir.Quoter.Probe.send(__ENV__, {m, m.behaviour_info(:callbacks)})
                true -> :ok
              end
            end
            """.trimIndent(),
        )

        assertEquals("probe compile status", OtpErlangAtom("ok"), compiled.status)

        val answers = compiled.messages.map { message ->
            val (module, callbacks) = (message as OtpErlangTuple).elements()

            (module as OtpErlangAtom).atomValue() to callbacks
        }
        val unloaded = answers.filter { it.second is OtpErlangAtom }.map { it.first }.toSet()
        val probed = answers.filter { it.second is OtpErlangList }.associate { (module, callbacks) ->
            module to (callbacks as OtpErlangList).elements().map {
                val (name, arity) = (it as OtpErlangTuple).elements()

                NameArity((name as OtpErlangAtom).atomValue(), (arity as OtpErlangLong).intValue())
            }.sorted()
        }

        return probed to unloaded
    }

    /** The modules whose [read] callbacks differ from the [probed] ones. */
    private fun mismatches(read: Map<String, List<NameArity>>, probed: Map<String, List<NameArity>>): List<String> =
        (read.keys + probed.keys).sorted().mapNotNull { module ->
            val ours = read[module]
            val theirs = probed[module]

            when {
                ours == theirs -> null
                ours == null -> "$module: only Elixir has callbacks $theirs"
                theirs == null -> "$module: only the leg read callbacks $ours"
                else -> "$module: lost ${theirs - ours.toSet()}, extra ${ours - theirs.toSet()}"
            }
        }

    private fun List<NameArity>.sorted() = sortedWith(compareBy({ it.name }, { it.arity }))
}
