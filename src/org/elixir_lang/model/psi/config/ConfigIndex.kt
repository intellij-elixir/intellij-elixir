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
        private const val VERSION = 2
    }
}

/** [ConfigIndex]'s name, how it reads a file, and what it holds. */
internal object ConfigIndexing {
    val NAME = ID.create<ConfigIndexKey, List<ConfigEntry>>("elixir.config")

    val INDEXER = DataIndexer<ConfigIndexKey, List<ConfigEntry>, FileContent> { inputData ->
        val entries = mutableMapOf<ConfigIndexKey, MutableList<ConfigEntry>>()

        inputData.psiFile.viewProvider.getPsi(ElixirLanguage)?.let { root ->
            PsiTreeUtil.processElements(root, Call::class.java) { call ->
                for (set in Set.of(call)) {
                    entries.getOrPut(ConfigIndexKey.App(set.app)) { mutableListOf() }
                        .add(ConfigEntry.KeyEntry(ConfigEntry.Kind.SET, set.key, set.element.textRange))
                }

                Read.of(call)?.let { read ->
                    entries.getOrPut(read.app) { mutableListOf() }
                        .add(ConfigEntry.KeyEntry(ConfigEntry.Kind.READ, read.key, read.element.textRange))
                }

                true
            }

            if (inputData.fileName == MIX_EXS && root is ElixirFile) {
                entries[ConfigIndexKey.Project] =
                    mutableListOf(ConfigEntry.ProjectEntry(ProjectKeywords.app(root), ProjectKeywords.configPath(root)))
            }
        }

        entries
    }

    /** Each key of [kind] filed under [app] in [scope], as written, and the element it is written at. */
    @RequiresReadLock
    fun written(project: Project, app: ConfigIndexKey, kind: ConfigEntry.Kind, scope: GlobalSearchScope): List<Pair<WrittenKey, PsiElement>> {
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
                .filter { it.kind == kind }
                .mapNotNull { entry -> element(file, entry.range)?.let { entry.key to it } }
        }
    }

    private fun element(file: PsiFile, range: TextRange): PsiElement? =
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

    /** A read whose app is not written as a literal, so it may be any app's. */
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

    enum class Kind { SET, READ }
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

    override fun save(out: DataOutput, value: List<ConfigEntry>) {
        out.writeInt(value.size)

        for (entry in value) {
            when (entry) {
                is ConfigEntry.KeyEntry -> {
                    out.writeByte(KEY_ENTRY)
                    out.writeByte(entry.kind.ordinal)
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
                    val kind = ConfigEntry.Kind.entries[input.readByte().toInt()]
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
