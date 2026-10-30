package org.elixir_lang.model.psi.config

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** The code that reads an application env key. */
internal object ConfigReads {
    /**
     * Where a read names its key: at [range] in [element], the key as written, the whole call where it reads every key,
     * or the template sigil holding it. A read is not [decided] where the template's own Elixir names its module or key,
     * which only expanding it could tell: Find Usages leaves it out, and the inspection counts it.
     */
    class Site(val element: PsiElement, val range: TextRange, val decided: Boolean = true)

    /**
     * The reads of [app]'s [key] in [scope]. A read of [app] whose key is not written as a literal may read [key], so it
     * is one. A template read's key and module are named in the env its sigil compiles in. A template read of [app] whose
     * key that env can't name may read [key], as a key not written as a literal does, so it is one, not [Site.decided].
     */
    @RequiresReadLock
    fun of(project: Project, app: String, key: String, scope: GlobalSearchScope): Sequence<Site> {
        val files = ReadFiles.of(app, scope)

        return (indexedReadsOf(project, files.byKey[key].orEmpty() + files.anyKey + files.named, app, HashMap()) +
            templateReadsOf(project, app, scope, HashMap()))
            .filter { it.key == null || it.key == key }
            .map { it.site }
    }

    /**
     * The keys of an app that code reads, as [of] finds them. The files whose reads write the key asked about as an atom,
     * or read any key, are classified first, each only until one reads it. Those with an alias or `__MODULE__` key, then
     * the template reads, are gathered only when none does, once for every key.
     */
    class ReadKeys internal constructor(private val literal: (String) -> Sequence<FileRead>, private val others: List<Lazy<KeySet>>) {
        operator fun contains(key: String): Boolean =
            literal(key).any { it.key == null || it.key == key } || others.any { key in it.value }
    }

    /** The keys [reads] read, or where one reads any key, every key. */
    internal class KeySet(reads: Sequence<FileRead>) {
        private val keys = HashSet<String>()
        private var any = false

        init {
            for (read in reads) read.key?.let(keys::add) ?: run { any = true }
        }

        operator fun contains(key: String): Boolean = any || key in keys
    }

    /** The keys of [app] that code in [scope] reads. */
    @RequiresReadLock
    fun readKeys(project: Project, app: String, scope: GlobalSearchScope): ReadKeys {
        val files = ReadFiles.of(app, scope)
        val kept = ConcurrentHashMap<VirtualFile, PsiFile>()

        return ReadKeys(
            { key -> indexedReadsOf(project, files.byKey[key].orEmpty() + files.anyKey, app, kept) },
            listOf(
                lazy { KeySet(indexedReadsOf(project, files.named, app, kept)) },
                lazy { KeySet(templateReadsOf(project, app, scope, kept)) },
            ),
        )
    }

    /**
     * The files in [scope] whose reads, as the index files them, may read a key of an app: [byKey], by the key a read
     * writes as an atom; [anyKey], those with a read of any key; [named], those with an alias or `__MODULE__` key, which
     * only their expansion names.
     */
    private class ReadFiles(
        val byKey: Map<String, kotlin.collections.Set<VirtualFile>>,
        val anyKey: kotlin.collections.Set<VirtualFile>,
        val named: kotlin.collections.Set<VirtualFile>,
    ) {
        /** Every file with a candidate read. */
        val every: List<VirtualFile> get() = byKey.values.flatten() + anyKey + named

        companion object {
            /** The files with a candidate read of [app], or of an app not written as a literal. */
            @RequiresReadLock
            fun of(app: String, scope: GlobalSearchScope): ReadFiles {
                val byKey = HashMap<String, MutableSet<VirtualFile>>()
                val anyKey = LinkedHashSet<VirtualFile>()
                val named = LinkedHashSet<VirtualFile>()

                for (readApp in listOf(ConfigIndexKey.App(app), ConfigIndexKey.AnyApp)) {
                    for ((file, keys) in ConfigIndexing.readKeys(readApp, scope)) {
                        for (key in keys) {
                            ProgressManager.checkCanceled()

                            when (key) {
                                WrittenKey.Alias, WrittenKey.Module -> named += file
                                else -> reading(readApp, key) { Name.None }?.let { reading ->
                                    reading.key?.let { byKey.getOrPut(it, ::LinkedHashSet) += file } ?: anyKey.add(file)
                                }
                            }
                        }
                    }
                }

                return ReadFiles(byKey, anyKey, named)
            }
        }
    }

    /** What a read reads: [app]'s [key], or where [key] is `null`, any key of [app]; [site] is where it is. */
    internal class FileRead(val app: ReadApp, val key: String?, val site: Site) {
        /** Whether this may read a key of [app]: its app is [app], or not written as a literal. */
        fun isOf(app: String): Boolean = this.app == ConfigIndexKey.App(app) || this.app == ConfigIndexKey.AnyApp
    }

    /** The reads in [files], other than in templates, of [app] or of an app not written as a literal. */
    @RequiresReadLock
    private fun indexedReadsOf(
        project: Project,
        files: Collection<VirtualFile>,
        app: String,
        kept: MutableMap<VirtualFile, PsiFile>,
    ): Sequence<FileRead> = readsOf(project, files.distinct(), kept, INDEXED, ConfigIndexing::reads, ::classified).filter { it.isOf(app) }

    /** The reads in the template sigils in [scope] of [app] or of an app not written as a literal. */
    @RequiresReadLock
    private fun templateReadsOf(
        project: Project,
        app: String,
        scope: GlobalSearchScope,
        kept: MutableMap<VirtualFile, PsiFile>,
    ): Sequence<FileRead> = templateReadsOf(project, scope, kept).filter { it.isOf(app) }

    /** The reads in the template sigils in [scope]. */
    @RequiresReadLock
    private fun templateReadsOf(project: Project, scope: GlobalSearchScope, kept: MutableMap<VirtualFile, PsiFile>): Sequence<FileRead> =
        readsOf(project, TemplateReads.files(scope), kept, TEMPLATED, TemplateReads::of, ::classified)

    /**
     * The reads in [files]. Each file read is held in [kept], since the platform holds a file that isn't open only weakly:
     * while [kept] lives, the file's PSI and the caches held on it do.
     */
    @RequiresReadLock
    private fun <C> readsOf(
        project: Project,
        files: Collection<VirtualFile>,
        kept: MutableMap<VirtualFile, PsiFile>,
        key: Key<CachedValue<Held<C>>>,
        candidates: (PsiFile) -> List<C>,
        classify: (C) -> FileRead?,
    ): Sequence<FileRead> =
        files.asSequence()
            .mapNotNull { file -> kept[file] ?: PsiManager.getInstance(project).findFile(file)?.also { kept[file] = it } }
            .flatMap { readsIn(it, key, candidates, classify) }

    /**
     * [file]'s reads, each as what it reads. Its [candidates] are held on [file] while it is unchanged; what they read is
     * held with them, with the [KeyAtom.basis] it was named against, while that basis is the same object.
     */
    @RequiresReadLock
    private fun <C> readsIn(
        file: PsiFile,
        key: Key<CachedValue<Held<C>>>,
        candidates: (PsiFile) -> List<C>,
        classify: (C) -> FileRead?,
    ): List<FileRead> {
        ProgressManager.checkCanceled()
        val held = CachedValuesManager.getCachedValue(file, key) { CachedValueProvider.Result.create(Held(candidates(file)), file) }
        if (held.candidates.isEmpty()) return emptyList()

        val basis = KeyAtom.basis(file) ?: return held.candidates.mapNotNull(classify)
        held.classified.get()?.takeIf { it.first === basis }?.let { return it.second }

        return held.candidates.mapNotNull(classify).also { held.classified.set(basis to it) }
    }

    /** A file's candidate reads, and what they read when last classified, with the basis it was named against. */
    private class Held<C>(val candidates: List<C>) {
        val classified = AtomicReference<Pair<Any, List<FileRead>>>()
    }

    private val INDEXED = Key.create<CachedValue<Held<Pair<ReadApp, ConfigIndexing.Written>>>>("elixir.config.indexed.reads")
    private val TEMPLATED = Key.create<CachedValue<Held<TemplateReads.TemplateRead>>>("elixir.config.template.reads")

    @RequiresReadLock
    private fun classified(candidate: Pair<ReadApp, ConfigIndexing.Written>): FileRead? {
        val (readApp, written) = candidate
        if (isRead(written) != CallModule.Answer.REACHES) return null

        return reading(readApp, written.key) { written.key.atom(written.element)?.let(Name::Atom) ?: Name.None }
            ?.let { FileRead(it.app, it.key, site(written.element)) }
    }

    @RequiresReadLock
    private fun classified(templateRead: TemplateReads.TemplateRead): FileRead? {
        val read = templateRead.read
        val named = reading(read.app, read.key) {
            when (val at = templateRead.keyAt) {
                null -> Name.Undecided
                else -> KeyAtom.of(read.element, at)?.let(Name::Atom) ?: Name.None
            }
        } ?: return null

        return site(templateRead, named.keyDecided)?.let { FileRead(named.app, named.key, it) }
    }

    /** How an alias or `__MODULE__` key is named: as an atom, not at all, or only by the template's own Elixir. */
    private sealed interface Name {
        class Atom(val name: String) : Name
        data object None : Name
        data object Undecided : Name
    }

    /** [app]'s [key], or where [key] is `null`, any key of [app]; not [keyDecided] where only a template's own Elixir names it. */
    private class Reading(val app: ReadApp, val key: String?, val keyDecided: Boolean = true)

    /**
     * What a read of [readApp]'s key, written as [written], reads. A key not written as a literal reads any key of a
     * literal app, and nothing where the app is not one either. An alias or `__MODULE__` key reads the key [named]
     * gives, any key of a literal app where only the template's own Elixir names it, and nothing where nothing names it.
     */
    private fun reading(readApp: ReadApp, written: WrittenKey, named: () -> Name): Reading? =
        when (written) {
            is WrittenKey.Atom -> Reading(readApp, written.name)
            WrittenKey.Unknown, WrittenKey.All -> (readApp as? ConfigIndexKey.App)?.let { Reading(it, null) }
            WrittenKey.Alias, WrittenKey.Module ->
                when (val name = named()) {
                    is Name.Atom -> Reading(readApp, name.name)
                    Name.Undecided -> (readApp as? ConfigIndexKey.App)?.let { Reading(it, null, keyDecided = false) }
                    Name.None -> null
                }
        }

    /** The keys that reads of [app] in [scope] read, as Find Usages finds them. */
    @RequiresReadLock
    fun keys(project: Project, app: String, scope: GlobalSearchScope): List<String> {
        val kept = HashMap<VirtualFile, PsiFile>()

        return (indexedReadsOf(project, ReadFiles.of(app, scope).every, app, kept) + templateReadsOf(project, app, scope, kept))
            .filter { it.site.decided }
            .mapNotNull { it.key }
            .toList()
    }

    /** The literal apps that code in [scope] reads, as Find Usages finds the reads. */
    @RequiresReadLock
    fun apps(project: Project, scope: GlobalSearchScope): List<String> {
        val kept = HashMap<VirtualFile, PsiFile>()
        val indexed = ConfigIndexing.apps(project).filter { app ->
            indexedReadsOf(project, ReadFiles.of(app, scope).every, app, kept).any { it.app == ConfigIndexKey.App(app) }
        }

        return indexed + templateReadsOf(project, scope, kept).filter { it.site.decided }.mapNotNull { (it.app as? ConfigIndexKey.App)?.atom }
    }

    @RequiresReadLock
    private fun isRead(written: ConfigIndexing.Written): CallModule.Answer {
        val module = (written.kind as? ConfigEntry.Kind.Read)?.module ?: return CallModule.Answer.ELSEWHERE
        val call = if (written.key == WrittenKey.All) written.element as? Call else Keys.call(written.element)

        return call?.let { Read.isRead(module, it) } ?: CallModule.Answer.ELSEWHERE
    }

    /**
     * [templateRead]'s site, its module named in the env its sigil compiles in; `null` where its call is not a read. It
     * is [Site.decided] only where its module is, and its key is as [keyDecided] says.
     */
    @RequiresReadLock
    private fun site(templateRead: TemplateReads.TemplateRead, keyDecided: Boolean): Site? =
        when (Read.isRead(templateRead.read.module, templateRead.read.call, templateRead.host)) {
            CallModule.Answer.ELSEWHERE -> null
            CallModule.Answer.REACHES -> Site(templateRead.host, templateRead.range, keyDecided)
            CallModule.Answer.UNDECIDED -> Site(templateRead.host, templateRead.range, decided = false)
        }

    private fun site(element: PsiElement): Site = Site(element, Keys.range(element))
}
