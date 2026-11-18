package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangRef
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.expander.interpreter.CounterArray
import org.elixir_lang.expander.interpreter.EMPTY
import org.elixir_lang.expander.interpreter.Unknown
import org.elixir_lang.expander.interpreter.atom
import org.elixir_lang.expander.interpreter.holdsAlready
import org.elixir_lang.expander.interpreter.holdsClosure
import org.elixir_lang.expander.interpreter.holdsUnknown
import org.elixir_lang.expander.interpreter.isKeyword
import org.elixir_lang.expander.interpreter.isUnknown
import org.elixir_lang.expander.interpreter.joinedUnknown
import org.elixir_lang.expander.interpreter.keyValue
import org.elixir_lang.expander.interpreter.list
import org.elixir_lang.expander.interpreter.properElements
import org.elixir_lang.expander.interpreter.sameKnownTerm
import org.elixir_lang.expander.interpreter.sameTerm
import org.elixir_lang.expander.interpreter.tuple
import org.elixir_lang.expander.interpreter.unknownOf
import org.elixir_lang.language_level.ElixirLanguageFeature.CONFIG_VALIDATES_EVERY_ENTRY
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.mix.ProjectKeywords.Reading
import org.elixir_lang.psi.ElementFactory
import org.elixir_lang.psi.ElixirTypes

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

    /** What every file the read follows shares. */
    val shared = RunShared()

    /** The modules the read's follows loaded, with their clauses, which the build the read runs before then sees. */
    private val followed = LinkedHashMap<String, FollowedLoad>()

    /** [loaded], which a follow of the read loaded. */
    fun followed(loaded: FollowedLoad) {
        followed[loaded.module.name] = loaded
    }

    /** The clauses of each module in [followed] that has them. */
    fun followedClauses(): Map<String, ModuleClauses> =
        followed.entries.mapNotNull { (name, loaded) -> loaded.clauses?.let { name to it } }.toMap()

    /** The files in [imports] that only code that may not have run read, so a later import of one may or may not raise. */
    val maybeImports = HashSet<String>()

    /** How many answers the read's files assumed, across every file it imports (`Run.assumptions`). */
    var assumed = 0

    /** The arms that may not be taken the read's files have run, every file it imports counted toward one bound. */
    val arms = ArmsTaken()

    /**
     * How many bodies of a `try` with `catch` or `rescue` are around the import being run, any of which may catch a raise
     * in the file it imports.
     */
    var catching = 0

    /**
     * Whether the import being run, in whichever of the read's files, is in code that may not run ([Run.mayNotRun]), as
     * the file it imports then is.
     */
    var mayNotRun = false

    /**
     * How many units stepped unordered ([Run.unordered]) are around the import being run, in whichever of the read's
     * files, as they are around the file it imports.
     */
    var unordered = 0

    /**
     * The read as it was where the body of the `try` being evaluated, in whichever of the read's files, may have raised,
     * oldest first; `null` outside one.
     */
    var raisedAt: MutableList<Snapshot>? = null

    /** The value of the last file evaluated's last expression: the top file's, as it finishes last. */
    var result: OtpErlangObject = Unknown

    /** Whether a unit declining marks nothing: a statement run again for its value, whose first run marked it. */
    var muted = false

    private var declined = false

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

    /**
     * What [block] gives from the read joined over [before] and [returned] ([join]), as code that may not run, the read
     * then put back whole, the files it may have read included.
     */
    fun <T> aside(before: Snapshot, returned: List<Snapshot>, block: () -> T): T {
        val read = snapshot()
        val maybeRead = maybeImports.toSet()

        join(before, returned)

        try {
            return block()
        } finally {
            restore(read)
            maybeImports.clear()
            maybeImports += maybeRead
        }
    }

    /**
     * The read's config once a `config` call merged [written] into it, which it leaves not empty. An app [written] sets
     * every key of, each once, is no longer [Shape.shapeless]: whatever pairs its list held, the merged list holds one pair
     * for each key [written] sets.
     */
    fun configured(config: OtpErlangObject, written: OtpErlangObject) {
        val shapeless = stillShapeless(written)

        this.config = config
        shape = shape.copy(shapeless = shapeless, mayBeEmpty = false)
    }

    /**
     * The apps of [Shape.shapeless] [written] doesn't set every key of that their list in the config holds, each once: a
     * key written twice merges its second value into the list's second copy of it, which a shapeless list doesn't hold.
     */
    private fun stillShapeless(written: OtpErlangObject): Set<OtpErlangObject> {
        if (shape.shapeless.isEmpty()) return shape.shapeless
        val apps = config.takeUnless(::isUnknown)?.let(::firstPairs) ?: return shape.shapeless
        val writes = firstPairs(written)

        return shape.shapeless.filterNotTo(HashSet()) { app ->
            val settings = apps[app] ?: return@filterNotTo false
            val sets = writes[app] ?: return@filterNotTo false
            if (!bothKeywords(settings, sets)) return@filterNotTo false
            val keys = firstPairs(sets).keys

            keys.size == properElements(sets)!!.size && keys.containsAll(firstPairs(settings).keys)
        }
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
     * [raised], which a `try` around it may have [caught]: a key the same term in each that goes on keeps it, and one
     * they set apart, or only some set, is unknown, else [before]'s config stands where none goes on; an app whose list
     * they leave apart in its keys, or only some set, is [Shape.shapeless], the config's apps are [Shape.appsShapeless]
     * where they leave them apart, and the config may be empty where one of them leaves it so; a file any of them read
     * may have been read ([maybeImports]), unless each that goes on read it. Of the read itself, only those files move.
     */
    fun joined(before: Snapshot, returned: List<Snapshot>, raised: List<Snapshot>, caught: Boolean): Snapshot {
        val ended = returned + raised
        val goesOn = if (caught) ended else returned
        // Each started from before's files and added its own at the front.
        val read = ended.flatMap { it.imports.subList(0, it.imports.size - before.imports.size) }.distinct()
        val configs = goesOn.ifEmpty { listOf(before) }
        val shapeless = configs.flatMapTo(HashSet()) { it.shape.shapeless }
        var appsShapeless = configs.any { it.shape.appsShapeless }
        val joined = configs.map(Snapshot::config).reduce { left, right ->
            if (alikePairs(left, right) == null) appsShapeless = true
            joinedConfig(left, right, shapeless)
        }
        val mayBeEmpty = configs.any { it.shape.mayBeEmpty || configs.size > 1 && properElements(it.config)?.isEmpty() == true }

        maybeImports += if (goesOn.isEmpty()) read else read.filter { file -> goesOn.any { file !in it.imports } }

        return Snapshot(joined, read + before.imports, Shape(shapeless, appsShapeless, mayBeEmpty))
    }

    /** Puts in place the read once code that may not have run, from [before], ended in one of [returned] ([joined]). */
    fun join(before: Snapshot, returned: List<Snapshot>) =
        restore(joined(before, returned, emptyList(), caught = false))

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
    fun raised(): ConfigTable = outcome(EMPTY, WrittenApps(), unknown = true)

    private fun outcome(config: OtpErlangObject, written: WrittenApps = shared.written.copy(), unknown: Boolean = false) =
        ConfigTable(config, imports.toList(), declined, written, unknown, shared.required.toSet(), followed.mapValues { it.value.module })

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

/** The apps whose env evaluations wrote: every app once one wrote an app that isn't known. */
internal class WrittenApps {
    /** The apps written, `null` once one isn't known. */
    private var apps: MutableSet<String>? = HashSet()

    /** Records that an evaluation wrote [app]'s env, or, `null`, an app that isn't known. */
    fun add(app: String?) {
        if (app == null) apps = null else apps?.add(app)
    }

    /** Whether an evaluation wrote [app]'s env, or, `null`, that of any app. */
    operator fun contains(app: String?): Boolean = apps?.let { if (app == null) it.isNotEmpty() else app in it } != false

    /** Whether [other] holds the same apps, or every app as this does. */
    fun isAt(other: WrittenApps): Boolean = apps == other.apps

    /** Puts the apps back to [other]'s, as code run again from where they were starts there. */
    fun restore(other: WrittenApps) {
        apps = other.apps?.toHashSet()
    }

    /** Adds [other]'s apps. */
    fun addAll(other: WrittenApps) {
        val more = other.apps

        if (more == null) apps = null else apps?.addAll(more)
    }

    /** The apps written so far, apart from later writes. */
    fun copy(): WrittenApps = WrittenApps().also { it.restore(this) }
}

/** What a config read, or else the outermost run, shares with every file it follows. */
internal class RunShared {
    /** The apps whose env an evaluation wrote, or, `null` among them, an app that isn't known. */
    val written = WrittenApps()

    /** The paths `Code.require_file/1,2` required. */
    val required = HashSet<String>()

    /** Whether a write of `Code`'s compiler options was reached, after which none is known. */
    var compilerOptionsWritten = false

    /** The arrays `counters:new/2` made, by the reference each is named by; not hygiene's [Run.counters]. */
    val counterArrays = HashMap<OtpErlangRef, CounterArray>()

    /** How many cells the [counterArrays] hold in all. */
    var counterCells = 0

    /** The keys of apps' envs an evaluation wrote, as later reads give them, outside a config read. */
    val envWrites = EnvWrites()
}

/** What a run wrote an app's key as. */
internal sealed interface EnvWrite {
    /** `set_env`'s [term]. */
    class Value(val term: OtpErlangObject) : EnvWrite

    /** `unset_env`'s. */
    data object Unset : EnvWrite

    /** A write whose value, or whether it ran, isn't known. */
    data object Unknown : EnvWrite
}

/**
 * The keys of apps' envs a run wrote ([EnvWrite]), which later reads give: a key not written reads as no write made it,
 * unless a write took out its app's keys, or every app's.
 */
internal class EnvWrites {
    /** Each app written, its keys and whether a write took out the rest. */
    private class App(val out: Boolean) {
        val keys = HashMap<OtpErlangAtom, EnvWrite>()
    }

    private val apps = HashMap<String, App>()

    /** Whether a write took out every app's keys. */
    private var allOut = false

    /** A race begun ([race]): the writes [before] it, and the keys written in it, `null` for a whole app's. */
    private class Race(val before: EnvWrites) {
        val written = HashSet<Pair<String?, OtpErlangAtom?>>()
    }

    /** Each race begun and not ended, innermost last. */
    private val races = ArrayList<Race>()

    /** What [app]'s [key] was written as, `null` where no write reached it. */
    operator fun get(app: String, key: OtpErlangAtom): EnvWrite? {
        val written = apps[app]

        return written?.keys?.get(key) ?: EnvWrite.Unknown.takeIf { written?.out ?: allOut }
    }

    /** Records [app]'s [key] written as [write], not known in a race where a write before it reached the key. */
    operator fun set(app: String, key: OtpErlangAtom, write: EnvWrite) {
        val race = races.lastOrNull()

        apps.getOrPut(app) { App(allOut) }.keys[key] = if (race?.before?.get(app, key) != null) EnvWrite.Unknown else write
        race?.written?.add(app to key)
    }

    /** Takes out every key of [app], or, `null`, of every app, as a write that may have written any. */
    fun takeOut(app: String?) {
        races.lastOrNull()?.written?.add(app to null)

        if (app == null) {
            apps.clear()
            allOut = true
        } else {
            apps[app] = App(out = true)
        }
    }

    /** Takes out every key of each app written, as code that ran unseen may have written any. */
    fun takeOutWritten() {
        races.lastOrNull()?.written?.addAll(apps.keys.map { it to null })
        apps.replaceAll { _, _ -> App(out = true) }
    }

    /**
     * [block], raced against what was written so far: code Elixir may run before it, or after it, or between its steps,
     * as a file a run beside others requires. While it runs, every key written before it reads as not known, and so does
     * one [block] writes that a write before it reached, as either write may be the later ([set]); after it, each key
     * reads as it did where [block] didn't write it or take out its app.
     */
    fun <T> race(block: () -> T): T {
        val before = EnvWrites().also { copy ->
            apps.forEach { (name, app) -> copy.apps[name] = App(app.out).apply { keys.putAll(app.keys) } }
            copy.allOut = allOut
        }

        apps.values.forEach { app -> app.keys.replaceAll { _, _ -> EnvWrite.Unknown } }
        races.add(Race(before))

        try {
            return block()
        } finally {
            endRace()
        }
    }

    private fun endRace() {
        val race = races.removeAt(races.lastIndex)
        val written = race.written

        races.lastOrNull()?.written?.addAll(written)
        val everyAppOut = null to null in written

        for ((name, app) in apps) {
            if (everyAppOut || name to null in written) continue

            race.before.apps[name]?.keys?.forEach { (key, write) -> if (name to key !in written) app.keys[key] = write }
        }
    }
}

/** Whether [left] and [right] are one state of a read: the same config ([sameTerm]), shape and files, or no read. */
internal fun sameRead(left: ConfigRead.Snapshot?, right: ConfigRead.Snapshot?): Boolean =
    if (left == null || right == null) {
        left === right
    } else {
        left.imports == right.imports && left.shape == right.shape && sameTerm(left.config, right.config, held = true)
    }

/** Whether earlier code required a path with `Code.require_file/1,2`, so a later require of it gives `nil`. */
internal enum class Require {
    /** It did: a later require gives `nil`. */
    REQUIRED,

    /** Code that declined may have: a later require may give `nil`, or run the file. */
    MAYBE,
}

/** What a run reaches of the project's files by path, each stamped. */
internal interface RunFiles {
    /** What is at [path], stamped by its path, `null` where that can't be known. */
    fun entry(path: String): FileEntry?

    /** Whether a file or directory is at [path] ([entry]), `null` where that can't be known. */
    fun exists(path: String): Boolean? = entry(path)?.let { it != FileEntry.Missing }

    /** The file at [path] evaluated in the same [read], or `null` where there is no such file. */
    fun imported(path: String, read: ConfigRead): FileExpansion?

    /**
     * The saved bytes of the file at [path], which [entry] finds a file, stamped by them, `null` where they can't be read.
     * A file larger than the largest bitstring the interpreter builds declines.
     */
    fun read(path: String): ByteArray? = null

    /** The names in the directory at [path], stamped by its listing, `null` where that can't be known. */
    fun listing(path: String): DirectoryListing? = null

    /** The file at [path] lowered, for `Code` to follow, `null` where it can't be read. */
    fun followed(path: String): FollowedFile? = null

    /**
     * [text] parsed and lowered as if it began on [line] at [column], with `Code`'s [indentation], for `Code` to evaluate
     * or quote: [Parsed.NoProject] where there is no project to parse it in.
     */
    fun parsed(text: String, line: Int, column: Int, indentation: Int): Parsed = Parsed.NoProject

    /**
     * The `parser_options` the compile of the run's file is given by its project's `elixirc_options`, the term written
     * there; [Reading.Absent] where it is left the leg's, as it is for a file with no Mix project and for a config read,
     * which runs before the compile sets them.
     */
    fun parserOptions(): Reading<OtpErlangObject> = Reading.Absent

    /**
     * Whether the config read the run's build follows required [path] with `Code.require_file/1,2`, `null` where it
     * didn't; a read's run follows none.
     */
    fun requiredByRead(path: String): Require? = null

    /**
     * Which copy of [module] the index holds for the run's file: `true` one compiled copy, `false` one source copy,
     * several, or any where the index can't say, `null` none.
     */
    fun copyOf(module: String): Boolean? = null

    /**
     * The modules the follows of [module]'s file loaded, with their clauses, where the index holds one source copy of it
     * that isn't still running; none for a config read, which runs no file of the build, or for a file whose follows the
     * run already reached. Where there are any, [reaching] runs first, and may decline before the run reaches them.
     */
    fun loadedWith(module: String, reaching: () -> Unit): List<FollowedLoad> = emptyList()

    companion object {
        val NONE = object : RunFiles {
            override fun entry(path: String): FileEntry? = null
            override fun imported(path: String, read: ConfigRead): FileExpansion? = null
        }

        /**
         * [text] parsed in [project] and lowered for [level] as if it began on [line] at [column], with `Code`'s
         * [indentation]: what [parsed] gives. A heredoc's lines start at 1, so where the others start at another
         * [Lowering.laterColumn] it is [Parsed.UnplacedHeredoc].
         */
        @RequiresReadLock
        fun parse(project: Project, level: ElixirLanguageLevel, text: String, line: Int, column: Int, indentation: Int): Parsed {
            val file = ElementFactory.createFile(project, text)
            val laterColumn = Lowering.laterColumn(level, column, indentation)
            val heredoc = laterColumn != 1 && SyntaxTraverser.psiTraverser(file).traverse().any {
                ProgressManager.checkCanceled()
                PsiUtilCore.getElementType(it) == ElixirTypes.HEREDOC_PROMOTER
            }

            return if (heredoc) Parsed.UnplacedHeredoc(laterColumn) else Parsed.Forms(Lowering.lower(file, level, line, column, indentation))
        }
    }
}

/** What [RunFiles.entry] finds at a path. */
internal sealed interface FileEntry {
    /** The path on disk of a [Directory], through any link, with `/` separators, `null` for anything else. */
    val physical: String? get() = null

    /** Nothing. */
    data object Missing : FileEntry

    /** A link to nothing: nothing to what follows it, a name to what reads the link. */
    data object Dangling : FileEntry

    /** A file. */
    data object File : FileEntry

    /** A directory, at [physical] on disk, through any link. */
    class Directory(override val physical: String) : FileEntry
}

/** What [RunFiles.listing] finds at a path. */
internal sealed interface DirectoryListing {
    /** A directory, and the [entries] in it. */
    class Names(val entries: List<Entry>) : DirectoryListing

    /** A [name] in a directory, and whether it is one itself. */
    class Entry(val name: String, val isDirectory: Boolean)

    /** Nothing, or a file. */
    data object NotADirectory : DirectoryListing
}

/** What [RunFiles.parsed] gives. */
internal sealed interface Parsed {
    /** The text's [forms], lowered. */
    class Forms(val forms: ElixirAst) : Parsed

    /** There is no project to parse the text in. */
    data object NoProject : Parsed

    /** The text holds a heredoc, whose lines after the first start at 1, where the others start at [laterColumn]. */
    class UnplacedHeredoc(val laterColumn: Int) : Parsed
}

/** A file `Code` follows: its [forms], lowered, and the [virtualFile] they were read from. */
internal class FollowedFile(val forms: ElixirAst, val virtualFile: VirtualFile)

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
        value != null && other != null -> joinedUnknown(listOf(value, other))
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
 * @property written the apps whose env the read wrote
 * @property required the paths the read required with `Code.require_file/1,2`, which the build's own files then don't
 * @property followed the modules the read's follows loaded, by name, which the build's files then see
 */
internal class ConfigTable(
    val config: OtpErlangObject,
    val files: List<String>,
    val declined: Boolean = false,
    private val written: WrittenApps = WrittenApps(),
    val unknown: Boolean = false,
    val required: Set<String> = emptySet(),
    val followed: Map<String, FollowedModule> = emptyMap(),
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
            value == null -> if (app in written) AppEnvValue.Unreadable else null
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
