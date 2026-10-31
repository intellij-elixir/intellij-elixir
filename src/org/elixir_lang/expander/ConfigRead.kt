package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.expander.interpreter.Unknown
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

    /** The read's outcome, as `Config.__eval__!/3` takes it: where no `config` call wrote any, a list [result] validated. */
    fun table(level: ElixirLanguageLevel): ConfigTable {
        val files = imports.toList()
        val result = result

        return when {
            properElements(config)?.isEmpty() != true -> ConfigTable(config, files)
            isUnknown(result) -> ConfigTable.UNKNOWN
            result !is OtpErlangList && result !is OtpErlangString -> ConfigTable(config, files)
            holdsUnknown(result) || !isValid(result, level) -> ConfigTable.UNKNOWN
            else -> ConfigTable(result, files)
        }
    }

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
 * A config read's outcome: the merged [config] and the [files] it read, newest first, or [unknown] where the read
 * raised, as the build then fails.
 */
internal class ConfigTable(val config: OtpErlangObject, val files: List<String>, val unknown: Boolean = false) {
    companion object {
        val EMPTY = ConfigTable(OtpErlangList(), emptyList())
        val UNKNOWN = ConfigTable(OtpErlangList(), emptyList(), unknown = true)
    }
}
