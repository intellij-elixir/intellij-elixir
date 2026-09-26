package org.elixir_lang.mix

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.elixir_lang.errorreport.Logger
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.literalName
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.impl.stripAccessExpressions
import org.elixir_lang.sdk.wsl.wslCompat

/**
 * `Mix.Dep`
 */
data class Dep(val application: String, val path: String, val type: Type = Type.LIBRARY) {
    /**
     * Resolves the virtual file for this dependency relative to [moduleRoot].
     *
     * [path] is typically "deps/<name>" (relative) or an absolute path from a `path:` option.
     * Relative paths (including ones with `../` traversal like `"../../../exc1"`) are first
     * resolved via [VirtualFile.findFileByRelativePath]; if that fails (e.g. because parent
     * directories above the content root are not yet in the VFS), the path is resolved against
     * [moduleRoot]'s filesystem path using [java.nio.file.Path] before attempting a
     * refreshing [org.elixir_lang.sdk.wsl.WslCompatService.findFileByPath] lookup.
     *
     * @param moduleRoot The content root of the module that declared this dependency.
     * @return The VirtualFile for the dep directory, or null if not found.
     */
    fun virtualFile(moduleRoot: VirtualFile): VirtualFile? {
        // First try VFS traversal - VirtualFile.findFileByRelativePath handles ../ by walking up
        // via getParent(), so this works whenever the ancestor directories are in the VFS.
        moduleRoot.findFileByRelativePath(path)?.let { return it }

        // Compute an absolute path for the fallback refresh.
        // For absolute paths (Unix "/" or Windows "C:\") use as-is.
        // For relative paths, resolve against the module root's *filesystem* path so that
        // directories above the content root (not tracked by the VFS) can still be found.
        // Passing a bare relative path like "../../../exc1" to refreshAndFindFileByPath
        // is meaningless because the method has no working-directory context.
        val absolutePath: String = if (java.io.File(path).isAbsolute) {
            path
        } else {
            try {
                java.nio.file.Path.of(moduleRoot.path).resolve(path).normalize().toString()
            } catch (_: Throwable) {
                return null
            }
        }
        return wslCompat.findFileByPath(absolutePath, refresh = true)
    }

    enum class Type {
        LIBRARY,
        MODULE
    }

    companion object {
        fun from(depsListElement: ElixirTuple): Dep? = from(depsListElement, isDependency = false)

        /**
         * @param isDependency whether the `mix.exs` being read belongs to a dependency rather than to
         *   a project the IDE is building. Mix checks a dependency's own deps against `:prod` rather
         *   than the current `MIX_ENV`, and does not force a dependency's optional deps on the project
         *   using it, so under this flag either option can drop the dep.
         */
        fun from(depsListElement: ElixirTuple, isDependency: Boolean): Dep? {
            val stripped = depsListElement.children.stripAccessExpressions()

            return if (stripped.isNotEmpty()) {
                name(stripped[0])?.let { name ->
                    val initial = Options(Dep(application = name, path = "deps/$name"))

                    val options = if (stripped.size > 1) {
                        keywords(stripped.last())?.keywordPairList?.let { keywordPairList ->
                            keywordPairList.fold(initial) { acc, keywordPair ->
                                when (val key = keywordPair.keywordKey.text) {
                                    "allow_pre", "app", "branch", "commit", "compile", "env", "hex",
                                    "manager", "organization", "override", "ref", "repo", "runtime",
                                    GUARDIAN_RUNTIME_TYPO, "submodules", "system_env", "tag", "targets",
                                    EDELIVER_DISTILLERY_WARN_MISSING, "sha", "depth", "warn_if_outdated" -> acc

                                    // Only `Mix.SCM.Git` joins `sparse`/`subdir` onto the destination,
                                    // and it declines a dep naming neither `git:` nor `github:`.
                                    "git", "github" -> acc.copy(hasGitScm = true)
                                    "sparse" -> acc.copy(sparse = stringBody(keywordPair.keywordValue))
                                    "subdir" -> acc.copy(subdir = stringBody(keywordPair.keywordValue))

                                    "only" -> acc.copy(only = environments(keywordPair.keywordValue))
                                    "optional" -> acc.copy(optional = isTrue(keywordPair.keywordValue))

                                    "in_umbrella" ->
                                        acc.copy(dep = acc.dep.copy(path = "apps/$name", type = Type.MODULE))
                                    "path" -> acc.copy(dep = putPath(acc.dep, keywordPair.keywordValue))
                                    else -> {
                                        Logger.error(
                                            logger,
                                            "Don't know if Mix.Dep option `$key` is important for determining location of dependency",
                                            depsListElement
                                        )
                                        acc
                                    }
                                }
                            }
                        } ?: initial
                    } else {
                        initial
                    }

                    // Accumulate first and resolve once: an elvis on the resolved dep would read a
                    // deliberate null as "this tuple had no options" and hand back the unfiltered dep.
                    options.resolve(isDependency)
                }
            } else {
                null
            }
        }

        /**
         * The options of one dep tuple, accumulated across the whole fold before any is acted on.
         *
         * An option cannot always be applied where it is read. Two can combine into one path, Mix can
         * apply a pair in a fixed order that need not match the order they are written, one can gate
         * whether another applies at all and may appear after it, and one can mean different things
         * depending on which `mix.exs` the tuple came from. Deciding once, at the end, is the only
         * shape that accommodates any of that.
         */
        private data class Options(
            val dep: Dep,
            val sparse: String? = null,
            val subdir: String? = null,
            val only: List<String>? = null,
            val optional: Boolean = false,
            val hasGitScm: Boolean = false,
        )

        /** The dep this tuple describes, or `null` when Mix would not fetch it here. */
        private fun Options.resolve(isDependency: Boolean): Dep? {
            if (isDependency) {
                // An `in_umbrella:` dep of a dependency is an app of *that* umbrella, never a module
                // of this project, so it cannot be wired here either.
                if (dep.type == Type.MODULE) return null
                if (optional || (only != null && PROD_ENVIRONMENT !in only)) return null
            }

            return if (hasGitScm && dep.type == Type.LIBRARY) {
                dep.copy(path = listOfNotNull(dep.path, sparse, subdir).joinToString("/"))
            } else {
                dep
            }
        }

        /**
         * The environments an `only:` names, or `null` when the value cannot be read.
         *
         * Unreadable means unrestricted. Dropping a dep that is physically present costs resolution
         * and completion, while keeping one Mix never fetches costs an empty placeholder library -
         * so every shape this cannot parse, including a quoted atom, keeps the dep.
         */
        private fun environments(keywordValue: Quotable): List<String>? =
            when (keywordValue) {
                is ElixirAtom -> keywordValue.name?.let { listOf(it) }
                is ElixirList ->
                    keywordValue.children.stripAccessExpressions().map { element ->
                        (element as? ElixirAtom)?.name ?: return null
                    }
                else -> null
            }

        private fun isTrue(keywordValue: Quotable): Boolean =
            keywordValue is ElixirAtomKeyword && keywordValue.text == "true"

        private fun stringBody(keywordValue: Quotable): String? =
            (keywordValue as? ElixirLine)?.body?.text

        private const val PROD_ENVIRONMENT = "prod"

        /**
         * The options of a dep tuple, whether or not they are wrapped in a list.
         *
         * `{:dep, "~> 1.0", optional: true}` and `{:dep, "~> 1.0", [optional: true]}` are the same
         * declaration and both are written in the wild - `ecto` brackets neither, `db_connection`
         * brackets its `optional:`. Only the bare form was read, so the bracketed one silently had
         * every option ignored, `path:` and `in_umbrella:` included.
         */
        private fun keywords(optionsElement: PsiElement): ElixirKeywords? =
            optionsElement as? ElixirKeywords
                ?: (optionsElement as? ElixirList)
                    ?.children
                    ?.singleOrNull()
                    ?.stripAccessExpression() as? ElixirKeywords

        private val logger by lazy { com.intellij.openapi.diagnostic.Logger.getInstance(Dep::class.java) }

        private fun name(nameElement: PsiElement): String? =
            when (nameElement) {
                is ElixirAtom -> name(nameElement)
                else -> null
            }

        private fun name(atom: ElixirAtom): String = atom.literalName() ?: atom.node.lastChildNode.text

        private fun putPath(dep: Dep, keywordValue: Quotable): Dep {
            return when (val strippedKeywordValue = keywordValue.stripAccessExpression()) {
                is ElixirLine -> putPath(dep, strippedKeywordValue)
                is Call -> putPath(dep, strippedKeywordValue)
                // Anything else cannot be read as a path either, and leaves the dep where it was
                else -> dep
            }
        }

        private fun putPath(dep: Dep, stringLine: ElixirLine): Dep = dep.copy(path = stringLine.body!!.text)

        // NOTE: path: <call> patterns (e.g. path: some_helper()) are not resolved because multiResolve is expensive
        // and the resolution chain always returns the dep unchanged. If full resolution is needed in the future,
        // re-implement with a non-blocking, suspending approach.
        @Suppress("UNUSED_PARAMETER")
        private fun putPath(dep: Dep, call: Call): Dep = dep
    }
}

// https://github.com/ueberauth/guardian/issues/594
@Suppress("SpellCheckingInspection")
const val GUARDIAN_RUNTIME_TYPO: String = "runtume"
const val EDELIVER_DISTILLERY_WARN_MISSING: String = "warn_missing"
