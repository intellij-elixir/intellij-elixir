package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangObject

/** The application environment a default build of the project sees: its own configuration, and no extra flags. */
internal fun interface AppEnv {
    /** [app]'s [key]; [app] is atom text. */
    fun of(app: String, key: OtpErlangObject): AppEnvValue

    companion object {
        /**
         * The env in which a key a counted config file sets ([configured]) isn't known, nor is any other key of an app
         * a setting that can't be read may set ([unread]), else a key the app's `.app` declares is its term, else the
         * key is unset, where [appFile] gives the app's `.app` as read, `null` where it can't be read.
         */
        fun of(
            configured: (app: String, key: OtpErlangObject) -> Boolean,
            unread: (app: String) -> Boolean,
            appFile: (app: String) -> AppFile?,
        ): AppEnv =
            AppEnv { app, key ->
                when {
                    configured(app, key) -> AppEnvValue.Configured
                    unread(app) -> AppEnvValue.Unreadable
                    else ->
                        appFile(app)
                            ?.let { file -> file.env[key]?.let(AppEnvValue::Declared) ?: AppEnvValue.Unset }
                            ?: AppEnvValue.Unreadable
                }
            }
    }
}

internal sealed class AppEnvValue {
    /**
     * A config file the build counts has a setting of the key, which a default build may not run: neither the value nor
     * whether the key is set is known here.
     */
    data object Configured : AppEnvValue()

    /** The app's `.app` declares the key, as [term]. */
    data class Declared(val term: OtpErlangObject) : AppEnvValue()

    /** Nothing sets the key. */
    data object Unset : AppEnvValue()

    /** A config setting of the app, or the app's `.app`, can't be read, so whether the key is set isn't known. */
    data object Unreadable : AppEnvValue()
}
