package org.elixir_lang.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SystemInfoRt
import org.elixir_lang.sdk.elixir.ElixirVersions
import org.elixir_lang.sdk.erlang.Release
import org.elixir_lang.sdk.wsl.wslCompat
import org.jetbrains.annotations.TestOnly
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What an Elixir or Erlang installation reports about itself, so it can be had under a read lock with no file I/O.
 *
 * Keyed by the home rather than the SDK: an SDK the settings dialog has not saved yet has a home but no
 * [com.intellij.openapi.projectRoots.SdkAdditionalData].
 *
 * Held for this session only, so a `null` from any read means the home has not been read since the IDE started, or
 * has been forgotten since.
 */
@Service(Service.Level.APP)
class SdkVersionsStore : ModificationTracker {
    private val byHomePath = ConcurrentHashMap<String, Install>()
    private val modificationCount = AtomicLong()

    /**
     * [canonicalHome] differs from the key when the SDK is configured through a symlink, so both spellings are
     * recognised as one installation without resolving anything. It counts as a value: re-keying an entry is a change.
     */
    private data class Install(
        val canonicalHome: String,
        val elixirVersions: ElixirVersions?,
        val otpRelease: Release?,
    ) {
        // `Release` equality is ordering, under which `25.3.2.7-1` equals `25.3.2.7`, but the text is what is shown.
        override fun equals(other: Any?): Boolean =
            other is Install &&
                canonicalHome == other.canonicalHome &&
                elixirVersions == other.elixirVersions &&
                otpRelease?.otpVersion == other.otpRelease?.otpVersion &&
                otpRelease?.otpMajor == other.otpRelease?.otpMajor

        override fun hashCode(): Int = canonicalHome.hashCode()
    }

    fun elixirVersions(homePath: String?): ElixirVersions? = install(homePath)?.elixirVersions

    fun otpVersion(homePath: String?): String? = otpRelease(homePath)?.otpVersion

    fun otpRelease(homePath: String?): Release? = install(homePath)?.otpRelease

    /** What a symlinked home resolved to when it was read; [homePath] itself, normalised, when it has not been read. */
    fun canonicalHome(homePath: String?): String? = install(homePath)?.canonicalHome ?: installationKey(homePath)

    /** Every home held, under every spelling it was read through. */
    // Not `toSet`: for one key it calls `next()` without `hasNext()`, which throws if the key is removed meanwhile.
    fun homes(): Set<String> = byHomePath.keys.toHashSet()

    /**
     * Stored under both spellings so a reader holding either is answered without resolving one, which is I/O.
     *
     * @return whether any value changed.
     */
    fun record(
        canonicalHomePath: String,
        configuredHomePath: String,
        elixirVersions: ElixirVersions?,
        otpRelease: Release?,
    ): Boolean {
        val canonicalKey = installationKey(canonicalHomePath) ?: return false
        val keys = linkedSetOf(canonicalKey)
        installationKey(configuredHomePath)?.let(keys::add)
        val install = Install(canonicalKey, elixirVersions, otpRelease)
        var changed = false

        for (homeKey in keys) {
            if (byHomePath.put(homeKey, install) != install) changed = true
        }
        if (changed) publish(keys, canonicalKey)

        return changed
    }

    @TestOnly
    fun setElixirVersions(homePath: String, elixirVersions: ElixirVersions?) {
        update(homePath) { it.copy(elixirVersions = elixirVersions) }
    }

    @TestOnly
    fun setOtpVersion(homePath: String, otpVersion: String?) {
        update(homePath) { it.copy(otpRelease = otpVersion?.let { text -> Release.parse(text) ?: Release.of(text) }) }
    }

    /**
     * For an installation that is gone or that no SDK uses any more; recording every value null instead would read as
     * "not recorded" yet stay watched.
     *
     * @return whether anything was held.
     */
    fun forgetInstallation(canonicalHomePath: String, configuredHomePath: String = canonicalHomePath): Boolean {
        val canonicalKey = installationKey(canonicalHomePath) ?: return false
        val keys = linkedSetOf(canonicalKey)
        installationKey(configuredHomePath)?.let(keys::add)
        if (keys.none(byHomePath::containsKey)) return false

        keys.forEach(byHomePath::remove)
        // Every spelling: with the entries gone, a listener can no longer ask which installation a home belongs to.
        publish(keys, canonicalKey)

        return true
    }

    private fun update(homePath: String, mutate: (Install) -> Install) {
        val key = installationKey(homePath) ?: return
        val current = byHomePath[key]
        val updated = mutate(current ?: Install(key, null, null))
        if (current == updated) return

        byHomePath[key] = updated
        publish(setOf(key), updated.canonicalHome)
    }

    override fun getModificationCount(): Long = modificationCount.get()

    private fun publish(homeKeys: Set<String>, canonicalKey: String) {
        modificationCount.incrementAndGet()
        ApplicationManager.getApplication().messageBus.syncPublisher(SdkVersionsListener.TOPIC)
            .sdkVersionsChanged(homeKeys, canonicalKey)
    }

    private fun install(homePath: String?): Install? = installationKey(homePath)?.let(byHomePath::get)

    @TestOnly
    fun clearForTests() {
        byHomePath.clear()
        modificationCount.incrementAndGet()
    }

    companion object {
        fun getInstance(): SdkVersionsStore =
            ApplicationManager.getApplication().getService(SdkVersionsStore::class.java)
    }
}

/**
 * Lexical, never resolving symlinks. Anything comparing a path with a stored home must use this, or a watch never
 * fires.
 */
internal fun installationKey(homePath: String?): String? =
    homePath?.takeIf { it.isNotBlank() }
        ?.let { wslCompat.normalizeForComparison(it).trimEnd('/') }
        ?.let { if (foldsCase(it)) it.lowercase(Locale.ROOT) else it }

/**
 * [SystemInfoRt.isFileSystemCaseSensitive] answers for the host, which is wrong inside a WSL distro: folding there
 * would make two different directories one entry.
 */
internal fun foldsCase(normalizedPath: String): Boolean =
    !SystemInfoRt.isFileSystemCaseSensitive && !wslCompat.isWslUncPath(normalizedPath)
