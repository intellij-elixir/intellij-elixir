package org.elixir_lang.ui.sdk_choice

import org.elixir_lang.ui.framework.core.IdeTestContext
import org.elixir_lang.ui.framework.core.NoScreen
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A copy of the Elixir install where mise keeps installs under the private home, named by its version; its home. A copy,
 * never a link: the guard empties the private home following links.
 */
@OptIn(ExperimentalPathApi::class)
internal fun stageElixirInstall(): String {
    val elixir = Path.of(IdeTestContext.elixirSdkPath).normalize()
    val app = elixir.resolve("lib/elixir/ebin/elixir.app").readText()
    val version = Regex("""\{vsn,"([^"]+)"}""").find(app)!!.groupValues[1]
    val installs = NoScreen.privateHome.resolve(".local/share/mise/installs/elixir").createDirectories()
    return elixir.copyToRecursively(installs.resolve(version), followLinks = true, overwrite = false).toString()
}

/**
 * A project under the private home with one module, [name], whose `.iml` is [iml], and [files], by path from the
 * project's folder; its folder. It has its own `.idea`, so opening it keeps what the module file says.
 */
internal fun stageProject(name: String, iml: String, files: Map<String, String> = emptyMap()): Path {
    val project = NoScreen.privateHome.resolve("projects/$name")
    val modules = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project version="4">
          <component name="ProjectModuleManager">
            <modules>
              <module fileurl="file://${'$'}PROJECT_DIR${'$'}/$name.iml" filepath="${'$'}PROJECT_DIR${'$'}/$name.iml" />
            </modules>
          </component>
        </project>
    """.trimIndent()
    (files + mapOf(".idea/modules.xml" to modules, "$name.iml" to iml)).forEach { (path, text) ->
        project.resolve(path).apply { parent.createDirectories() }.writeText(text + "\n")
    }
    return project
}
