package org.elixir_lang.junit

import com.intellij.openapi.application.PathManager
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Gives each forked test JVM its own IDE system, config and log directories and its own quoter client node, when the
 * build runs more than one fork: forks sharing a system directory contend for the same index locks, and two JVMs
 * cannot register one Erlang node name.
 *
 * A fork claims the lowest free slot by holding a lock file for its lifetime, so a later build's forks reuse the same
 * directories.
 */
class ForkIsolation : LauncherSessionListener {
    override fun launcherSessionOpened(session: LauncherSession) {
        if (slot != null) return
        val forks = System.getProperty(FORKS_PROPERTY)?.toIntOrNull() ?: return
        if (forks <= 1) return

        val system = System.getProperty(SYSTEM_PATH)?.let(::path) ?: return
        val slot = claimSlot(system.resolveSibling("${system.fileName}-forks"))
        Companion.slot = slot

        for (property in listOf(SYSTEM_PATH, CONFIG_PATH, LOG_PATH)) {
            val shared = System.getProperty(property)?.let(::path) ?: continue
            val own = shared.resolveSibling("${shared.fileName}-fork-$slot")
            // The config directory carries what the sandbox was prepared with, so it is copied fresh each run; the
            // others keep what earlier builds left, the fork's index included.
            if (property == CONFIG_PATH) {
                deleteTree(own)
                copyTree(shared, own)
            } else {
                Files.createDirectories(own)
            }
            System.setProperty(property, own.toString())
        }
        checkPathManagerFollowed()

        System.getProperty(QUOTER_LOCAL_NODE)?.let { node ->
            val (name, host) = node.split('@', limit = 2)
            System.setProperty(QUOTER_LOCAL_NODE, "${name}_$slot@$host")
        }
    }

    companion object {
        /** This JVM's fork slot, or null when the build runs a single fork. */
        @Volatile
        var slot: Int? = null
            private set

        private const val FORKS_PROPERTY = "elixir.test.forks"
        private const val SYSTEM_PATH = "idea.system.path"
        private const val CONFIG_PATH = "idea.config.path"
        private const val LOG_PATH = "idea.log.path"
        private const val QUOTER_LOCAL_NODE = "elixir.quoter.localNode"

        /** Held until the JVM exits, which releases the slot for the next build's fork. */
        private var slotLock: FileLock? = null

        private fun path(value: String): Path = Path.of(value.removeSurrounding("\""))

        private fun claimSlot(locks: Path): Int {
            Files.createDirectories(locks)
            return generateSequence(0) { it + 1 }.first { slot ->
                val channel = FileChannel.open(locks.resolve("$slot.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val lock = channel.tryLock()
                if (lock != null) slotLock = lock else channel.close()
                lock != null
            }
        }

        /**
         * [PathManager] caches each path on first use, so a listener that ran after something had already asked would
         * leave every fork sharing one index, silently.
         */
        private fun checkPathManagerFollowed() {
            val resolved = mapOf(
                SYSTEM_PATH to PathManager.getSystemPath(),
                CONFIG_PATH to PathManager.getConfigPath(),
                LOG_PATH to PathManager.getLogPath(),
            )
            for ((property, actual) in resolved) {
                val wanted = System.getProperty(property)?.let(::path) ?: continue
                check(path(actual).toAbsolutePath().normalize() == wanted.toAbsolutePath().normalize()) {
                    "$property was already resolved to $actual before fork isolation could set $wanted"
                }
            }
        }

        private fun deleteTree(root: Path) {
            if (!Files.exists(root)) return
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }

        private fun copyTree(from: Path, to: Path) {
            Files.createDirectories(to)
            if (!Files.isDirectory(from)) return
            Files.walk(from).use { paths ->
                paths.forEach { source ->
                    val target = to.resolve(from.relativize(source).toString())
                    if (Files.isDirectory(source)) Files.createDirectories(target)
                    else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
