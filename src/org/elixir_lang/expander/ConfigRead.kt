package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.expander.interpreter.EMPTY
import org.elixir_lang.expander.interpreter.Unknown
import org.elixir_lang.expander.interpreter.atom
import org.elixir_lang.expander.interpreter.holdsAlready
import org.elixir_lang.expander.interpreter.holdsClosure
import org.elixir_lang.expander.interpreter.holdsUnknown
import org.elixir_lang.expander.interpreter.isKeyword
import org.elixir_lang.expander.interpreter.isUnknown
import org.elixir_lang.expander.interpreter.keyValue
import org.elixir_lang.expander.interpreter.list
import org.elixir_lang.expander.interpreter.properElements
import org.elixir_lang.expander.interpreter.sameKnownTerm
import org.elixir_lang.expander.interpreter.sameTerm
import org.elixir_lang.expander.interpreter.tuple
import org.elixir_lang.expander.interpreter.unknownOf
import org.elixir_lang.language_level.ElixirLanguageFeature.CONFIG_VALIDATES_EVERY_ENTRY
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * `Config`'s state for one read of a project's config, as `Config.__eval__!/3` keeps it in the process dictionary: the
 * env and target, the [config] its `config/2,3` calls have merged, and the files read, which every file the read
 * imports shares. [directory] is the reading project's, which Mix stays in for every file the read imports.
 */
internal class ConfigRead(val env: String, val target: String, val directory: String) {
    var config: OtpErlangObject = OtpErlangList()
        private set

    /** What of [config]'s shape is known. */
    private var shape = Shape()

    /** The files read, newest first, as `Config.Reader.read_imports!/2` lists them. */
    val imports = ArrayList<String>()

    /** The files in [imports] that only code that may not have run read, so a later import of one may or may not raise. */
    val maybeImports = HashSet<String>()

    /** The arms that may not be taken the read's files have run, every file it imports counted toward one bound. */
    val arms = ArmsTaken()

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

    /** What of the read's config's shape is known, as code that may not have run left it. */
    data class Shape(
        /**
         * The apps whose list in the config a [ConfigRead.joined] built from lists whose keys weren't alike pair for pair, so
         * how many pairs it holds, and in what order, isn't known: only each key's first value is.
         */
        val shapeless: Set<OtpErlangObject> = emptySet(),
        /**
         * Whether a [ConfigRead.joined] built the config from configs whose apps weren't alike pair for pair, so which apps
         * it holds, and in what order, isn't known.
         */
        val appsShapeless: Boolean = false,
        /** Whether the config may be empty, as code that may not have run left it, since no `config` call ran after that. */
        val mayBeEmpty: Boolean = false,
    )

    /** The read's config, what of the config's shape is known, and the files it read. */
    class Snapshot(val config: OtpErlangObject, val imports: List<String>, val shape: Shape)

    /** The read now ([Snapshot]), for code that may not run to start from and to be joined. */
    fun snapshot() = at(imports.toList())

    /** Whether the read is as [snapshot] holds it ([sameRead]). */
    fun isAt(snapshot: Snapshot?) = sameRead(snapshot, at(imports))

    private fun at(imports: List<String>) = Snapshot(config, imports, shape)

    /** Puts the read back to [snapshot], as code that may not run starts from what the read held before it. */
    fun restore(snapshot: Snapshot) {
        config = snapshot.config
        shape = snapshot.shape
        imports.clear()
        imports += snapshot.imports
    }

    /** The read's config once a `config` call merged it, which it leaves not empty. */
    fun configured(config: OtpErlangObject) {
        this.config = config
        shape = shape.copy(mayBeEmpty = false)
    }

    /**
     * [config] as code reads it whole: not known where its apps are [Shape.appsShapeless], else each [Shape.shapeless]
     * app's settings.
     */
    fun configAsRead(): OtpErlangObject {
        if (shape.appsShapeless) return unknownOf(listOf(config))
        if (shape.shapeless.isEmpty()) return config

        val apps = properElements(config) ?: return config

        return list(apps.map { entry ->
            val (app, settings) = (entry as OtpErlangTuple).elements()

            settingsAsRead(app, settings).let { if (it === settings) entry else tuple(app, it) }
        })
    }

    /** [app]'s [settings] in [config] as code reads them, not known where the app is [Shape.shapeless]. */
    fun settingsAsRead(app: OtpErlangObject, settings: OtpErlangObject): OtpErlangObject =
        if (app in shape.shapeless) unknownOf(listOf(settings)) else settings

    /**
     * The read once code that may not have run, starting from [before], ended in one of [returned] or raised in one of
     * [raised]: a key the same term in each of [returned] keeps it, and one they set apart, or only some set, is unknown,
     * else [before]'s config stands where none returned; an app whose list they leave apart in its keys, or only some
     * set, is [Shape.shapeless], the config's apps are [Shape.appsShapeless] where they leave them apart, and the config
     * may be empty where one of them leaves it so; a file any of them read may have been read ([maybeImports]), unless
     * each of [returned] read it. Of the read itself, only those files move.
     */
    fun joined(before: Snapshot, returned: List<Snapshot>, raised: List<Snapshot>): Snapshot {
        // Each started from before's files and added its own at the front.
        val read = (returned + raised).flatMap { it.imports.subList(0, it.imports.size - before.imports.size) }.distinct()
        val configs = returned.ifEmpty { listOf(before) }
        val shapeless = configs.flatMapTo(HashSet()) { it.shape.shapeless }
        var appsShapeless = configs.any { it.shape.appsShapeless }
        val joined = configs.map(Snapshot::config).reduce { left, right ->
            if (alikePairs(left, right) == null) appsShapeless = true
            joinedConfig(left, right, shapeless)
        }
        val mayBeEmpty = configs.any { it.shape.mayBeEmpty || configs.size > 1 && properElements(it.config)?.isEmpty() == true }

        maybeImports += read.filterNot { file -> returned.isNotEmpty() && returned.all { file in it.imports } }

        return Snapshot(joined, read + before.imports, Shape(shapeless, appsShapeless, mayBeEmpty))
    }

    /** Records that the read wrote [app]'s env, or, `null`, an app that isn't known. */
    fun wroteEnv(app: String?) {
        if (app == null) written = null else written?.add(app)
    }

    /**
     * The read's outcome, as `Config.__eval__!/3` takes it: where no `config` call wrote any, a list [result] validated. A
     * [result] that isn't known is declined, as whether it is that list isn't known, and so is one that is or may be a
     * list with entries where the config [Shape.mayBeEmpty], as which of them Elixir takes isn't known.
     */
    fun table(level: ElixirLanguageLevel): ConfigTable {
        val result = result
        val empty = properElements(config)?.isEmpty()
        val undecided = when (empty) {
            true -> isUnknown(result)
            false -> shape.mayBeEmpty && (isUnknown(result) || result is OtpErlangString || result is OtpErlangList && result.arity() > 0)
            null -> false
        }

        if (undecided) unitDeclined()

        return when {
            declined || empty != true || result !is OtpErlangList && result !is OtpErlangString -> outcome(config)
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

/** How many arms that may not be taken evaluations have run ([Run.takeArm]). */
internal class ArmsTaken {
    var count = 0
}

/** Whether [left] and [right] are one state of a read: the same config ([sameTerm]), shape and files, or no read. */
internal fun sameRead(left: ConfigRead.Snapshot?, right: ConfigRead.Snapshot?): Boolean =
    if (left == null || right == null) {
        left === right
    } else {
        left.imports == right.imports && left.shape == right.shape && sameTerm(left.config, right.config, held = true)
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
 * [left] and [right], two configs a read may have, joined per app: pair for pair where both wrote the app and its lists
 * are alike in their keys ([pairwise]), else per key, an app only one wrote holding nothing in the other, the app going
 * in [shapeless].
 */
private fun joinedConfig(left: OtpErlangObject, right: OtpErlangObject, shapeless: MutableSet<OtpErlangObject>): OtpErlangObject =
    joinedPerKey(left, right) { app, settings, other ->
        settings?.let { other?.let { pairwise(settings, other) } }
            ?: joinedPerKey(settings ?: EMPTY, other ?: EMPTY) { _, value, otherValue -> joinedValue(value, otherValue) }.also { shapeless += app }
    }

/** [left] and [right], keyword lists, each key's first value in either joined by [joined]; else [joinedValue]. */
private fun joinedPerKey(
    left: OtpErlangObject,
    right: OtpErlangObject,
    joined: (OtpErlangObject, OtpErlangObject?, OtpErlangObject?) -> OtpErlangObject,
): OtpErlangObject {
    if (!bothKeywords(left, right)) return joinedValue(left, right)

    val lefts = firstPairs(left)
    val rights = firstPairs(right)

    return list((lefts.keys + rights.keys).map { key -> tuple(key, joined(key, lefts[key], rights[key])) })
}

/** [left] and [right], keyword lists whose keys are alike pair for pair, each pair's values joined ([joinedValue]); else `null`. */
private fun pairwise(left: OtpErlangObject, right: OtpErlangObject): OtpErlangObject? {
    val (lefts, rights) = alikePairs(left, right) ?: return null

    return list(lefts.indices.map { tuple(lefts[it].elementAt(0), joinedValue(lefts[it].elementAt(1), rights[it].elementAt(1))) })
}

/** [left]'s and [right]'s pairs where they are known keyword lists with the same keys in the same order; else `null`. */
private fun alikePairs(left: OtpErlangObject, right: OtpErlangObject): Pair<List<OtpErlangTuple>, List<OtpErlangTuple>>? {
    if (!bothKeywords(left, right)) return null

    val lefts = properElements(left)!!.map { it as OtpErlangTuple }
    val rights = properElements(right)!!.map { it as OtpErlangTuple }

    return (lefts to rights).takeIf { lefts.size == rights.size && lefts.indices.all { lefts[it].elementAt(0) == rights[it].elementAt(0) } }
}

/** Whether [left] and [right] are both known keyword lists. */
private fun bothKeywords(left: OtpErlangObject, right: OtpErlangObject): Boolean =
    !isUnknown(left) && !isUnknown(right) && isKeyword(left) && isKeyword(right)

/**
 * [value] where [other] is the same term, else what isn't known of whichever of them is set ([unknownOf]), kept as it is
 * where it already holds the other, so a loop's joins meet the same read again ([sameRead]).
 */
private fun joinedValue(value: OtpErlangObject?, other: OtpErlangObject?): OtpErlangObject =
    when {
        value != null && other != null && sameKnownTerm(value, other) -> value
        other != null && holdsAlready(other, value) -> other
        value != null && holdsAlready(value, other) -> value
        else -> unknownOf(listOfNotNull(value, other))
    }

/** [keywords]' first value under each key. */
private fun firstPairs(keywords: OtpErlangObject): Map<OtpErlangObject, OtpErlangObject> =
    LinkedHashMap<OtpErlangObject, OtpErlangObject>().apply {
        for (pair in properElements(keywords).orEmpty()) {
            val (key, value) = (pair as OtpErlangTuple).elements()

            putIfAbsent(key, value)
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
        return keyValue(elements, { if (isUnknown(it)) return Unknown else it }) { first ->
            if (isUnknown(first)) return Unknown

            first == key
        }
    }

    companion object {
        val EMPTY = ConfigTable(OtpErlangList(), emptyList())
        val UNKNOWN = ConfigTable(OtpErlangList(), emptyList(), unknown = true)
    }
}
