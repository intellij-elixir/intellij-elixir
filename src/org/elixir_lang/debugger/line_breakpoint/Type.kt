/*
 * Copyright 2012-2014 Sergey Ignatov
 * Copyright 2017 Jake Becker
 * Copyright 2017-2018 Kadie Enheduanna Inanna
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.elixir_lang.debugger.line_breakpoint

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import org.elixir_lang.ElixirFileType
import org.elixir_lang.debugger.line_breakpoint.availability_processor.EEx
import org.elixir_lang.debugger.line_breakpoint.availability_processor.Elixir
import org.elixir_lang.util.ReadActions

class Type private constructor() : XLineBreakpointType<Properties>(ID, NAME) {
    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        // A processor per attempt, as a non-blocking read action restarts after a write.
        ReadActions.compute {
            processor(file)?.let { processor ->
                FileDocumentManager.getInstance().getDocument(file)?.let { document ->
                    canPutAt(document, line, project, processor)
                }
            } ?: false
        }

    override fun createBreakpointProperties(file: VirtualFile, line: Int): Properties =
            Properties()

    companion object {
        private const val ID = "ElixirLineBreakpoint"
        private const val NAME = "Elixir Line Breakpoints"

        private fun canPutAt(
                document: Document,
                line: Int,
                project: Project,
                availabilityProcessor: AvailabilityProcessor
        ): Boolean {
            XDebuggerUtil.getInstance().iterateLine(project, document, line, availabilityProcessor)

            return availabilityProcessor.isAvailable
        }

        private fun processor(file: VirtualFile): AvailabilityProcessor? =
                when (file.fileType) {
                    ElixirFileType.INSTANCE -> Elixir()
                    org.elixir_lang.eex.file.Type.INSTANCE -> EEx()
                    else -> null
                }
    }
}
