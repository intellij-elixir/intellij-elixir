package org.elixir_lang.model.psi.config

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.IOUtil
import com.intellij.util.io.KeyDescriptor
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.lowering.AtomName
import org.elixir_lang.mix.Project.MIX_EXS
import org.elixir_lang.mix.ProjectKeywords
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.__MODULE__
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.sdk.PARSED_AS_ELIXIR
import java.io.DataInput
import java.io.DataOutput

/**
 * Each file's application env settings and reads, by app, and a `mix.exs` file's project entry, all as written: no
 * config file is evaluated.
 */
internal class ConfigIndex : FileBasedIndexExtension<ConfigIndexKey, List<ConfigEntry>>() {
    override fun dependsOnFileContent() = true
    override fun getIndexer(): DataIndexer<ConfigIndexKey, List<ConfigEntry>, FileContent> = ConfigIndexing.INDEXER
    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(*PARSED_AS_ELIXIR)
    override fun getKeyDescriptor(): KeyDescriptor<ConfigIndexKey> = ConfigIndexKeyDescriptor
    override fun getValueExternalizer(): DataExternalizer<List<ConfigEntry>> = ConfigEntriesExternalizer
    override fun getName() = ConfigIndexing.NAME
    override fun getVersion() = VERSION * 1000 + AtomName.VERSION

    companion object {
        /** Bumped when what is indexed, or how it is written, changes. */
        private const val VERSION = 8
    }
}

/** [ConfigIndex]'s name, how it reads a file, and what it holds. */
internal object ConfigIndexing {
    val NAME = ID.create<ConfigIndexKey, List<ConfigEntry>>("elixir.config")

    val INDEXER = DataIndexer<ConfigIndexKey, List<ConfigEntry>, FileContent> { inputData ->
        val entries = mutableMapOf<ConfigIndexKey, MutableList<ConfigEntry>>()

        inputData.psiFile.viewProvider.getPsi(ElixirLanguage)?.let { root ->
            // Only a config file's setting that can't be read, its writes and its value can change what a build sees.
            val config = ConfigFiles.isConfig(inputData.fileName)

            fun add(app: ConfigIndexKey, kind: ConfigEntry.Kind, element: PsiElement) {
                entries.getOrPut(app) { mutableListOf() }.add(ConfigEntry.KeyEntry(kind, WrittenKey.Unknown, element.textRange))
            }

            PsiTreeUtil.processElements(root, Call::class.java) { call ->
                CallModule.of(call)?.let { module ->
                    for (set in Set.candidates(call)) {
                        entries.getOrPut(ConfigIndexKey.App(set.app)) { mutableListOf() }
                            .add(ConfigEntry.KeyEntry(ConfigEntry.Kind.Set(module), set.key, set.element.textRange))
                    }

                    if (config) {
                        for ((app, element) in Set.unread(call)) add(app, ConfigEntry.Kind.Unread(module), element)
                        Set.imported(call)?.let { add(ConfigIndexKey.AnyApp, ConfigEntry.Kind.Import(module), it) }
                    }
                }

                Read.of(call)?.let { read ->
                    entries.getOrPut(read.app) { mutableListOf() }
                        .add(ConfigEntry.KeyEntry(ConfigEntry.Kind.Read(read.module), read.key, read.element.textRange))
                }

                true
            }

            if (config) Set.returnedCandidate(root)?.let { add(ConfigIndexKey.AnyApp, ConfigEntry.Kind.Returned, it) }

            if (inputData.fileName == MIX_EXS && root is ElixirFile) {
                entries[ConfigIndexKey.Project] =
                    mutableListOf(ConfigEntry.ProjectEntry(ProjectKeywords.app(root), ProjectKeywords.configPath(root)))
            }
        }

        entries
    }

    /** Each entry filed under [app] in [scope] that [decided] says is [kind]. */
    @RequiresReadLock
    fun written(project: Project, app: ConfigIndexKey, scope: GlobalSearchScope, kind: Decided): List<Written> {
        val entriesByFile = mutableListOf<Pair<VirtualFile, List<ConfigEntry>>>()
        FileBasedIndex.getInstance().processValues(
            NAME,
            app,
            null,
            { file, entries ->
                entriesByFile += file to entries
                true
            },
            scope,
        )

        return entriesByFile.flatMap { (virtualFile, entries) ->
            ProgressManager.checkCanceled()
            val file = PsiManager.getInstance(project).findFile(virtualFile) ?: return@flatMap emptyList()

            entries
                .filterIsInstance<ConfigEntry.KeyEntry>()
                .mapNotNull { entry ->
                    element(file, entry.range)?.takeIf { decided(entry.kind, it) == kind }?.let { Written(entry.kind, entry.key, it) }
                }
        }
    }

    /** What a config file's entry is, once the module its call reaches decides it. */
    enum class Decided {
        /** A setting of a key. */
        SET,

        /** What may set any key of its app. */
        UNREAD,

        /** An `import_config`'s argument. */
        IMPORT,
    }

    /**
     * What an entry of [kind], at [element], is: a setting where its call reaches `Config` or `Mix.Config`, and unread
     * where no one module can be named; an unread or an import where its call may reach a module that makes it one; and
     * a value [Set.returned] gives, unread. `null` for a candidate read, which [ConfigReads] decides, and for a call that
     * reaches another module.
     */
    @RequiresReadLock
    fun decided(kind: ConfigEntry.Kind, element: PsiElement): Decided? =
        when (kind) {
            is ConfigEntry.Kind.Set ->
                when (reaches(kind.module, element)) {
                    CallModule.Answer.REACHES -> Decided.SET
                    CallModule.Answer.UNDECIDED -> Decided.UNREAD
                    CallModule.Answer.ELSEWHERE, null -> null
                }
            is ConfigEntry.Kind.Unread -> Decided.UNREAD.takeIf { reaches(kind.module, element).let { it != null && it != CallModule.Answer.ELSEWHERE } }
            is ConfigEntry.Kind.Import -> Decided.IMPORT.takeIf { reaches(kind.module, element).let { it != null && it != CallModule.Answer.ELSEWHERE } }
            ConfigEntry.Kind.Returned -> Decided.UNREAD.takeIf { Set.returned(element.containingFile)?.textRange == element.textRange }
            is ConfigEntry.Kind.Read -> null
        }

    /** Whether the call [module] names nearest [element] reaches a module whose call of its name and arity it is. */
    @RequiresReadLock
    private fun reaches(module: CallModule, element: PsiElement): CallModule.Answer? =
        module.callAt(element)?.let { module.reaches(it, Set.modules(module.name, module.arity)) }

    /** Each candidate read [file] holds, with the app it is filed under, as written. */
    @RequiresReadLock
    fun reads(file: PsiFile): List<Pair<ReadApp, Written>> {
        val virtualFile = file.virtualFile ?: return emptyList()

        return FileBasedIndex.getInstance().getFileData(NAME, virtualFile, file.project).flatMap { (app, entries) ->
            if (app !is ReadApp) return@flatMap emptyList()

            entries
                .filterIsInstance<ConfigEntry.KeyEntry>()
                .filter { it.kind is ConfigEntry.Kind.Read }
                .mapNotNull { entry -> element(file, entry.range)?.let { app to Written(entry.kind, entry.key, it) } }
        }
    }

    /** Each file in [scope] with a candidate read filed under [app], with how each of those reads writes its key. */
    @RequiresReadLock
    fun readKeys(app: ReadApp, scope: GlobalSearchScope): List<Pair<VirtualFile, List<WrittenKey>>> {
        val files = mutableListOf<Pair<VirtualFile, List<WrittenKey>>>()
        FileBasedIndex.getInstance().processValues(
            NAME,
            app,
            null,
            { file, entries ->
                files += file to entries.filterIsInstance<ConfigEntry.KeyEntry>().filter { it.kind is ConfigEntry.Kind.Read }.map { it.key }
                true
            },
            scope,
        )

        return files
    }

    /** A setting or a candidate read of [key], written at [element]. */
    class Written(val kind: ConfigEntry.Kind, val key: WrittenKey, val element: PsiElement)

    /** The literal apps filed in the index, some perhaps with no entry left. */
    @RequiresReadLock
    fun apps(project: Project): List<String> =
        FileBasedIndex.getInstance().getAllKeys(NAME, project).filterIsInstance<ConfigIndexKey.App>().map { it.atom }

    /** The element [file] holds at [range], as an entry's range names it. */
    @RequiresReadLock
    fun element(file: PsiFile, range: TextRange): PsiElement? =
        generateSequence(file.findElementAt(range.startOffset)) { it.parent }
            .takeWhile { it.textRange.startOffset == range.startOffset && it.textRange.endOffset <= range.endOffset }
            .lastOrNull()
            ?.takeIf { it.textRange == range }
            ?.stripAccessExpression()
}

/** The app an entry is filed under. */
sealed interface ConfigIndexKey {
    /** A literal app. */
    data class App(val atom: String) : ReadApp

    /** A read or a setting whose app is not written as a literal, so it may be any app's. */
    data object AnyApp : ReadApp

    /** A `mix.exs` file's project. */
    data object Project : ConfigIndexKey
}

/** The app a read names. */
sealed interface ReadApp : ConfigIndexKey

/** A first-level key as written in a setting or a read. */
sealed interface WrittenKey {
    data class Atom(val name: String) : WrittenKey

    /** An alias, whose atom depends on the aliases in scope. */
    data object Alias : WrittenKey

    /** `__MODULE__`. */
    data object Module : WrittenKey

    /** A key not written as a literal, which may be any key of the app. */
    data object Unknown : WrittenKey

    /** Every key of the app, as `get_all_env/1` reads. */
    data object All : WrittenKey

    /** The atom this key, written at [element], names; `null` where that can't be known. */
    @RequiresReadLock
    fun atom(element: PsiElement): String? =
        when (this) {
            is Atom -> name
            Alias, Module -> KeyAtom.of(element)
            Unknown, All -> null
        }

    companion object {
        /** The key [element], stripped of its access expression, is written as. */
        @RequiresReadLock
        fun of(element: PsiElement): WrittenKey =
            when (element) {
                is ElixirAtom -> AtomName.of(element)?.let(::Atom) ?: Unknown
                is QualifiableAlias -> Alias
                is Call ->
                    if (element !is Qualified && element.functionName() == __MODULE__ && element.resolvedFinalArity() == 0) {
                        Module
                    } else {
                        Unknown
                    }
                else -> Unknown
            }
    }
}

sealed interface ConfigEntry {
    /** A setting or a read of [key], written at [range]. */
    data class KeyEntry(val kind: Kind, val key: WrittenKey, val range: TextRange) : ConfigEntry

    data class ProjectEntry(val app: String?, val configPath: ProjectKeywords.ConfigPath) : ConfigEntry

    /** What an entry is as written; [ConfigIndexing.decided] decides each but [Read] from the module its call reaches. */
    sealed interface Kind {
        /** A candidate setting, of [org.elixir_lang.model.psi.config.Set.candidates]. */
        data class Set(val module: CallModule) : Kind

        /**
         * A candidate setting whose key, options or app [org.elixir_lang.model.psi.config.Set.candidates] can't read, so
         * it may set any key of its app, at the argument it can't read; or a candidate write of the env.
         */
        data class Unread(val module: CallModule) : Kind

        /**
         * A candidate `import_config` of the file its argument names, at that argument: a file it may name that doesn't
         * count may set any key of any app.
         */
        data class Import(val module: CallModule) : Kind

        /** A config file's last expression, which may be its value. */
        data object Returned : Kind

        /** A candidate read, a read where [module] says its call is one. */
        data class Read(val module: CallModule) : Kind
    }
}

private object ConfigIndexKeyDescriptor : KeyDescriptor<ConfigIndexKey> {
    private const val APP = 0
    private const val ANY_APP = 1
    private const val PROJECT = 2

    override fun getHashCode(value: ConfigIndexKey): Int = value.hashCode()

    override fun isEqual(val1: ConfigIndexKey, val2: ConfigIndexKey): Boolean = val1 == val2

    override fun save(out: DataOutput, value: ConfigIndexKey) {
        when (value) {
            is ConfigIndexKey.App -> {
                out.writeByte(APP)
                IOUtil.writeUTF(out, value.atom)
            }
            ConfigIndexKey.AnyApp -> out.writeByte(ANY_APP)
            ConfigIndexKey.Project -> out.writeByte(PROJECT)
        }
    }

    override fun read(input: DataInput): ConfigIndexKey =
        when (val tag = input.readByte().toInt()) {
            APP -> ConfigIndexKey.App(IOUtil.readUTF(input))
            ANY_APP -> ConfigIndexKey.AnyApp
            PROJECT -> ConfigIndexKey.Project
            else -> error("unknown config index key tag $tag")
        }
}

private object ConfigEntriesExternalizer : DataExternalizer<List<ConfigEntry>> {
    private const val KEY_ENTRY = 0
    private const val PROJECT_ENTRY = 1

    private const val ATOM = 0
    private const val ALIAS = 1
    private const val MODULE = 2
    private const val UNKNOWN = 3
    private const val ALL = 4

    private const val WRITTEN = 0
    private const val UNKNOWN_PATH = 1

    private const val SET = 0
    private const val READ = 1
    private const val UNREAD = 2
    private const val IMPORT = 3
    private const val RETURNED = 4

    private const val WRITTEN_QUALIFIER = 0
    private const val OTHER_QUALIFIER = 1
    private const val UNQUALIFIED = 2

    override fun save(out: DataOutput, value: List<ConfigEntry>) {
        out.writeInt(value.size)

        for (entry in value) {
            when (entry) {
                is ConfigEntry.KeyEntry -> {
                    out.writeByte(KEY_ENTRY)
                    save(out, entry.kind)
                    save(out, entry.key)
                    out.writeInt(entry.range.startOffset)
                    out.writeInt(entry.range.endOffset)
                }
                is ConfigEntry.ProjectEntry -> {
                    out.writeByte(PROJECT_ENTRY)
                    out.writeBoolean(entry.app != null)
                    entry.app?.let { IOUtil.writeUTF(out, it) }

                    when (val configPath = entry.configPath) {
                        is ProjectKeywords.ConfigPath.Written -> {
                            out.writeByte(WRITTEN)
                            IOUtil.writeUTF(out, configPath.path)
                        }
                        ProjectKeywords.ConfigPath.Unknown -> out.writeByte(UNKNOWN_PATH)
                    }
                }
            }
        }
    }

    override fun read(input: DataInput): List<ConfigEntry> =
        List(input.readInt()) {
            when (val tag = input.readByte().toInt()) {
                KEY_ENTRY -> {
                    val kind = readKind(input)
                    val key = readKey(input)
                    val start = input.readInt()

                    ConfigEntry.KeyEntry(kind, key, TextRange(start, input.readInt()))
                }
                PROJECT_ENTRY -> {
                    val app = if (input.readBoolean()) IOUtil.readUTF(input) else null
                    val configPath = when (val pathTag = input.readByte().toInt()) {
                        WRITTEN -> ProjectKeywords.ConfigPath.Written(IOUtil.readUTF(input))
                        UNKNOWN_PATH -> ProjectKeywords.ConfigPath.Unknown
                        else -> error("unknown config path tag $pathTag")
                    }

                    ConfigEntry.ProjectEntry(app, configPath)
                }
                else -> error("unknown config entry tag $tag")
            }
        }

    private fun save(out: DataOutput, kind: ConfigEntry.Kind) {
        when (kind) {
            is ConfigEntry.Kind.Set -> save(out, SET, kind.module)
            is ConfigEntry.Kind.Unread -> save(out, UNREAD, kind.module)
            is ConfigEntry.Kind.Import -> save(out, IMPORT, kind.module)
            ConfigEntry.Kind.Returned -> out.writeByte(RETURNED)
            is ConfigEntry.Kind.Read -> save(out, READ, kind.module)
        }
    }

    private fun save(out: DataOutput, tag: Int, module: CallModule) {
        out.writeByte(tag)

        when (val qualifier = module.qualifier) {
            is CallModule.Qualifier.Written -> {
                out.writeByte(WRITTEN_QUALIFIER)
                IOUtil.writeUTF(out, qualifier.module)
            }
            CallModule.Qualifier.Other -> out.writeByte(OTHER_QUALIFIER)
            CallModule.Qualifier.Unqualified -> out.writeByte(UNQUALIFIED)
        }

        IOUtil.writeUTF(out, module.name)
        out.writeInt(module.arity)
    }

    private fun readKind(input: DataInput): ConfigEntry.Kind =
        when (val tag = input.readByte().toInt()) {
            SET -> ConfigEntry.Kind.Set(readModule(input))
            UNREAD -> ConfigEntry.Kind.Unread(readModule(input))
            IMPORT -> ConfigEntry.Kind.Import(readModule(input))
            RETURNED -> ConfigEntry.Kind.Returned
            READ -> ConfigEntry.Kind.Read(readModule(input))
            else -> error("unknown config entry kind tag $tag")
        }

    private fun readModule(input: DataInput): CallModule {
        val qualifier = when (val tag = input.readByte().toInt()) {
            WRITTEN_QUALIFIER -> CallModule.Qualifier.Written(IOUtil.readUTF(input))
            OTHER_QUALIFIER -> CallModule.Qualifier.Other
            UNQUALIFIED -> CallModule.Qualifier.Unqualified
            else -> error("unknown call qualifier tag $tag")
        }
        val name = IOUtil.readUTF(input)

        return CallModule(qualifier, name, input.readInt())
    }

    private fun save(out: DataOutput, key: WrittenKey) {
        when (key) {
            is WrittenKey.Atom -> {
                out.writeByte(ATOM)
                IOUtil.writeUTF(out, key.name)
            }
            WrittenKey.Alias -> out.writeByte(ALIAS)
            WrittenKey.Module -> out.writeByte(MODULE)
            WrittenKey.Unknown -> out.writeByte(UNKNOWN)
            WrittenKey.All -> out.writeByte(ALL)
        }
    }

    private fun readKey(input: DataInput): WrittenKey =
        when (val tag = input.readByte().toInt()) {
            ATOM -> WrittenKey.Atom(IOUtil.readUTF(input))
            ALIAS -> WrittenKey.Alias
            MODULE -> WrittenKey.Module
            UNKNOWN -> WrittenKey.Unknown
            ALL -> WrittenKey.All
            else -> error("unknown written key tag $tag")
        }
}
