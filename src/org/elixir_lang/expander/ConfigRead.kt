package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.expander.interpreter.Unknown
import org.elixir_lang.expander.interpreter.atom
import org.elixir_lang.expander.interpreter.holdsClosure
import org.elixir_lang.expander.interpreter.holdsUnknown
import org.elixir_lang.expander.interpreter.isKeyword
import org.elixir_lang.expander.interpreter.isUnknown
import org.elixir_lang.expander.interpreter.keyFound
import org.elixir_lang.expander.interpreter.properElements
import org.elixir_lang.language_level.ElixirLanguageFeature.CONFIG_VALIDATES_EVERY_ENTRY
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `Config`'s state for one read of a project's config, as `Config.__eval__!/3` keeps it in the process dictionary: the
 * env and target, the [config] its `config/2,3` calls have merged, and the files read, which every file the read
 * imports shares. [directory] is the reading project's, which Mix stays in for every file the read imports.
 */
internal class ConfigRead(val env: String, val target: String, val directory: String) {
    var config: OtpErlangObject = OtpErlangList()

    /** The files read, newest first, as `Config.Reader.read_imports!/2` lists them. */
    val imports = ArrayList<String>()

    /** The value of the last file evaluated's last expression: the top file's, as it finishes last. */
    var result: OtpErlangObject = Unknown

    /** Whether a unit declining marks nothing: a statement run again for its value, whose first run marked it. */
    var muted = false

    private var declined = false

    /** The apps whose env the read wrote, across every file it imports, `null` once it wrote one that isn't known. */
    private var written: MutableSet<String>? = HashSet()

    /** A unit of the read declined, or code it doesn't follow ran, so its table isn't what Elixir's is. */
    fun unitDeclined() {
        if (!muted) declined = true
    }

    /** Records that the read wrote [app]'s env, or, `null`, an app that isn't known. */
    fun wroteEnv(app: String?) {
        if (app == null) written = null else written?.add(app)
    }

    /**
     * The read's outcome, as `Config.__eval__!/3` takes it: where no `config` call wrote any, a list [result] validated. A
     * [result] that isn't known is declined, as whether it is that list isn't known.
     */
    fun table(level: ElixirLanguageLevel): ConfigTable {
        val result = result

        return when {
            declined || properElements(config)?.isEmpty() != true -> outcome(config)
            isUnknown(result) -> {
                unitDeclined()
                outcome(config)
            }
            result !is OtpErlangList && result !is OtpErlangString -> outcome(config)
            holdsUnknown(result) || !isValid(result, level) -> raised()
            else -> outcome(result)
        }
    }

    /** The read's outcome where it raised, or its result holds a value not known. */
    fun raised(): ConfigTable = ConfigTable(OtpErlangList(), imports.toList(), declined, unknown = true)

    private fun outcome(config: OtpErlangObject) = ConfigTable(config, imports.toList(), declined, written?.toSet())

    /** Whether `Config.validate!/2` lets [result] by: before [CONFIG_VALIDATES_EVERY_ENTRY], the first entry not `{atom, _}` ends its check. */
    private fun isValid(result: OtpErlangObject, level: ElixirLanguageLevel): Boolean {
        for (entry in properElements(result) ?: return false) {
            val pair = (entry as? OtpErlangTuple)?.takeIf { it.arity() == 2 && it.elementAt(0) is OtpErlangAtom }
                ?: return !CONFIG_VALIDATES_EVERY_ENTRY.isSufficient(level)

            if (!isKeyword(pair.elementAt(1))) return false
        }

        return true
    }
}

/** What a run reaches of the project's files by path: imported config files and existence checks, each stamped. */
internal interface RunFiles {
    /** Whether a file or directory is at [path], `null` where that can't be known. */
    fun exists(path: String): Boolean?

    /** The file at [path] evaluated in the same [read], or `null` where there is no such file. */
    fun imported(path: String, read: ConfigRead): FileExpansion?

    /** The files whose paths match the wildcard [pattern] under its fixed prefix directory, `null` where not known. */
    fun wildcard(pattern: String): List<String>?

    companion object {
        val NONE = object : RunFiles {
            override fun exists(path: String): Boolean? = null
            override fun imported(path: String, read: ConfigRead): FileExpansion? = null
            override fun wildcard(pattern: String): List<String>? = null
        }
    }
}

/**
 * A config read's outcome: the merged [config] and the [files] it read, newest first. [unknown] where the read raised,
 * as the build then fails, or its result was a list holding a value not known, so no key's value is known.
 *
 * @property declined whether a unit of the read declined, so [config] isn't what Elixir's is
 * @property written the apps whose env the read wrote, `null` where one isn't known
 */
internal class ConfigTable(
    val config: OtpErlangObject,
    val files: List<String>,
    val declined: Boolean = false,
    private val written: Set<String>? = emptySet(),
    val unknown: Boolean = false,
) {
    /**
     * [app]'s [key]: [AppEnvValue.Declared] with its value where the table knows it, [AppEnvValue.Configured] where it
     * sets the key to a value not known, or one holding a function the read made, which runs in the read's run and not its
     * caller's, [AppEnvValue.Unreadable] where the read wrote the app, whose env is what loading
     * it makes of the write, and `null` where the read leaves the key alone, so the app's `.app` answers.
     */
    fun of(app: String, key: OtpErlangObject): AppEnvValue? {
        if (unknown || isUnknown(key)) return AppEnvValue.Configured

        val value = valueOf(config, atom(app))?.let { settings -> valueOf(settings, key) }

        return when {
            value == null -> if (written?.contains(app) != false) AppEnvValue.Unreadable else null
            holdsUnknown(value) || holdsClosure(value) -> AppEnvValue.Configured
            else -> AppEnvValue.Declared(value)
        }
    }

    /** `Keyword.get/2`'s first value under [key], `null` where there is none; where an unknown comes first, unknown. */
    private fun valueOf(keywords: OtpErlangObject, key: OtpErlangObject): OtpErlangObject? {
        if (isUnknown(keywords)) return keywords

        val elements = properElements(keywords) ?: return Unknown
        val pair = keyFound(elements, 1, { if (isUnknown(it)) return Unknown else it }) { first ->
            if (isUnknown(first)) return Unknown

            first == key
        }

        return pair?.takeIf { it.arity() == 2 }?.elementAt(1)
    }

    companion object {
        val EMPTY = ConfigTable(OtpErlangList(), emptyList())
        val UNKNOWN = ConfigTable(OtpErlangList(), emptyList(), unknown = true)
    }
}
