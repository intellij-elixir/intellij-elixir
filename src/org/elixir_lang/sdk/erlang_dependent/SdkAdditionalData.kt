package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.ValidatableSdkAdditionalData
import com.intellij.openapi.util.InvalidDataException
import com.intellij.openapi.util.WriteExternalException
import org.elixir_lang.sdk.wsl.wslCompat
import org.jdom.Element

/** The pairing names no Erlang SDK: what the page asks about and the check refuses with [SdkAdditionalData.ERLANG_SDK_NOT_CHOSEN]. */
internal val ErlangSdkResult.erlangNotChosen: Boolean
    get() = this is ErlangSdkResult.Missing && reason == MissingErlangSdkReason.NOT_CONFIGURED

/**
 * Stores the reference from an Elixir SDK to its dependent Erlang SDK.
 *
 * ## Persistence Model
 *
 * Both the Erlang SDK **home path** (`erlang-sdk-home-path`) and **name** (`erlang-sdk-name`)
 * are persisted to disk. Home path is the primary stable identity - it survives SDK renames.
 * Name is retained as a display hint and legacy fallback for configs written before the
 * home-path field was added.
 *
 * ## Resolution Order
 *
 * 1. `erlangSdkHomePath` (stable, survives rename) - looked up by home path in ProjectJdkTable
 * 2. `erlangSdkName` (legacy fallback) - looked up by name; the resolver commits the resolved path back
 * 3. Both absent -> NOT_CONFIGURED
 *
 * ## Cache Behavior
 *
 * A transient cache ([cachedErlangSdk]) avoids repeated lookups. The cache is:
 * - Populated on first successful lookup
 * - Invalidated when the referenced SDK no longer exists in ProjectJdkTable
 * - Cleared on [readExternal] to force re-resolution after loading from disk
 *
 * Cache resolution is performed by [ErlangSdkResolver].
 */
class SdkAdditionalData :
    ValidatableSdkAdditionalData,
    Cloneable {
    private val elixirSdk: Sdk

    // Primary stable identity - survives SDK renames.
    @Volatile
    private var erlangSdkHomePath: String? = null

    // Display hint + legacy fallback for configs written before erlangSdkHomePath was added
    @Volatile
    private var erlangSdkName: String? = null

    // User acknowledged the OTP mismatch for this SDK - skip warnings until re-enabled.
    // Does NOT reset on Erlang SDK pairing change (the user said "I know what I'm doing").
    private var suppressOtpMismatchWarning: Boolean = false

    // Runtime cache - lazily populated, cleared when invalid
    // Not persisted, not cloned
    // Written by concurrent resolvers under the shared read lock, so only a hint: every read is revalidated against
    // the SDK table.
    @Transient
    private var cachedErlangSdk: Sdk? = null

    companion object {
        internal const val ERLANG_SDK_NOT_CHOSEN = "An Erlang SDK must be chosen."
        private const val ERLANG_SDK_HOME_PATH = "erlang-sdk-home-path"
        private const val ERLANG_SDK_NAME = "erlang-sdk-name"
        private const val SUPPRESS_OTP_MISMATCH = "suppress-otp-mismatch"
        private val LOG = Logger.getInstance(SdkAdditionalData::class.java)
    }

    // Primary constructor for readExternal
    constructor(elixirSdk: Sdk) {
        this.elixirSdk = elixirSdk
    }

    // Secondary constructor for creating new SDKs with a known Erlang SDK
    constructor(erlangSdk: Sdk?, elixirSdk: Sdk) : this(elixirSdk) {
        this.erlangSdkName = erlangSdk?.name
        this.erlangSdkHomePath = erlangSdk?.homePath
        this.cachedErlangSdk = erlangSdk
    }

    /**
     * Checks if the ERLANG_SDK_NAME properties are configured correctly and throws an exception
     * if they are not.
     *
     * @param sdkModel the model containing all configured SDKs.
     * @throws ConfigurationException if the ERLANG_SDK_NAME is not configured correctly.
     * @since 5.0.1
     */
    @Throws(ConfigurationException::class)
    override fun checkValid(sdkModel: SdkModel) {
        LOG.debug("checkValid called for Elixir SDK: ${elixirSdk.name} (homePath: ${elixirSdk.homePath})")

        val result = ErlangSdkResolver.getInstance().resolveErlangSdkResult(elixirSdk, sdkModel)

        if (result is ErlangSdkResult.Missing) {
            val available = ProjectJdkTable.getInstance().allJdks
                .filter { Type.staticIsValidDependency(it) }
                .joinToString(", ") { it.name }

            val message = when {
                result.erlangNotChosen -> ERLANG_SDK_NOT_CHOSEN
                available.isEmpty() -> "No Erlang SDK found. Please configure an Erlang SDK first."
                else -> "No valid Erlang SDK configured. Available: $available"
            }

            LOG.debug("Validation failed for ${elixirSdk.name}: $message")
            throw ConfigurationException(message)
        }

        LOG.debug("checkValid completed successfully for ${elixirSdk.name}")
    }

    @Throws(InvalidDataException::class)
    fun readExternal(element: Element) {
        erlangSdkHomePath = element.getAttributeValue(ERLANG_SDK_HOME_PATH)
        erlangSdkName = element.getAttributeValue(ERLANG_SDK_NAME)
        suppressOtpMismatchWarning = element.getAttributeValue(SUPPRESS_OTP_MISMATCH)?.toBooleanStrictOrNull() ?: false
        cachedErlangSdk = null  // Force re-lookup on next access
    }

    @Throws(WriteExternalException::class)
    fun writeExternal(element: Element) {
        val homePath = erlangSdkHomePath
        val name = erlangSdkName
        homePath?.let { element.setAttribute(ERLANG_SDK_HOME_PATH, it) }
        name?.let { element.setAttribute(ERLANG_SDK_NAME, it) }
        if (suppressOtpMismatchWarning) element.setAttribute(SUPPRESS_OTP_MISMATCH, "true")
    }

    public override fun clone(): SdkAdditionalData {
        val cloned = SdkAdditionalData(elixirSdk)
        cloned.erlangSdkHomePath = this.erlangSdkHomePath
        cloned.erlangSdkName = this.erlangSdkName
        cloned.suppressOtpMismatchWarning = this.suppressOtpMismatchWarning
        // Don't clone cachedErlangSdk - let it be lazily resolved
        return cloned
    }

    /**
     * Returns the configured Erlang SDK name without triggering a lookup.
     * Use this when you need to check the stored reference (e.g., in SDK removal listeners).
     */
    fun getErlangSdkName(): String? = erlangSdkName

    fun getErlangSdkHomePath(): String? = erlangSdkHomePath

    fun isSuppressOtpMismatchWarning(): Boolean = suppressOtpMismatchWarning

    fun setSuppressOtpMismatchWarning(value: Boolean) {
        suppressOtpMismatchWarning = value
    }

    fun setErlangSdk(sdk: Sdk?) {
        erlangSdkName = sdk?.name
        erlangSdkHomePath = sdk?.homePath
        cachedErlangSdk = sdk
    }

    /** Whether [sdk] has both the stored name and the stored home: the pick the user made among the SDKs at that home. */
    internal fun names(sdk: Sdk?): Boolean =
        sdk != null && sdk.name == erlangSdkName && wslCompat.pathsEqualWslAware(sdk.homePath, erlangSdkHomePath)

    internal fun getCachedErlangSdk(): Sdk? = cachedErlangSdk

    internal fun setCachedErlangSdk(sdk: Sdk?) {
        cachedErlangSdk = sdk
    }

    fun getErlangSdk(): Sdk? = getErlangSdk(sdkModel = null)

    /**
     * Gets the Erlang SDK, optionally searching in the provided SdkModel.
     * When sdkModel is provided, it searches there first (includes unsaved SDKs in Project Structure dialog).
     * Falls back to ProjectJdkTable for saved SDKs.
     *
     * @param sdkModel optional SdkModel to search in (for unsaved SDKs)
     * @return the Erlang SDK, or null if none found
     */
    fun getErlangSdk(sdkModel: SdkModel?): Sdk? {
        return ErlangSdkResolver.getInstance().resolveErlangSdk(elixirSdk, sdkModel)
    }
}
