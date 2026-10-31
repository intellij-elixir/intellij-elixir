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
                    else -> declared(appFile(app), key)
                }
            }

        /** What [appFile], an app's `.app` as read, `null` where it can't be read, answers for [key]. */
        fun declared(appFile: AppFile?, key: OtpErlangObject): AppEnvValue =
            appFile?.let { file -> file.env[key]?.let(AppEnvValue::Declared) ?: AppEnvValue.Unset } ?: AppEnvValue.Unreadable
    }
}

internal sealed class AppEnvValue {
    /**
     * The key may be set to a value not known here: the config table sets it to one, a config file sets it and no table
     * answers, or the read is made while config runs, or in a build that fails.
     */
    data object Configured : AppEnvValue()

    /** The key's value is [term], from the config table or the app's `.app`. */
    data class Declared(val term: OtpErlangObject) : AppEnvValue()

    /** Nothing sets the key. */
    data object Unset : AppEnvValue()

    /**
     * Whether the key is set isn't known: a config setting of the app, or the app's `.app`, can't be read, or a config read
     * wrote the app, whose env is what loading it makes of the write.
     */
    data object Unreadable : AppEnvValue()
}
