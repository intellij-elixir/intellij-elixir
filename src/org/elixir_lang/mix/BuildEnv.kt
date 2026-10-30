package org.elixir_lang.mix

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresReadLock

/**
 * The Mix env whose `_build` holds [file], the name of the directory directly under `_build` (e.g. `"dev"`, `"test"`),
 * or `null` if [file] is not under a `_build` directory.
 */
internal fun buildEnv(file: VirtualFile?): String? =
    generateSequence(file) { it.parent }.firstOrNull { it.parent?.name == "_build" }?.name

/** The Mix env a project's [file] is taken to build in: `test` in test-source content, `dev` elsewhere. */
@RequiresReadLock
internal fun defaultBuildEnv(project: Project, file: VirtualFile?): String =
    if (file != null && ProjectFileIndex.getInstance(project).isInTestSourceContent(file)) "test" else "dev"
