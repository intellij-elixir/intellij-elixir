package org.elixir_lang.mix

import com.intellij.openapi.vfs.VirtualFile

/**
 * The Mix env whose `_build` holds [file], the name of the directory directly under `_build` (e.g. `"dev"`, `"test"`),
 * or `null` if [file] is not under a `_build` directory.
 */
internal fun buildEnv(file: VirtualFile?): String? =
    generateSequence(file) { it.parent }.firstOrNull { it.parent?.name == "_build" }?.name
