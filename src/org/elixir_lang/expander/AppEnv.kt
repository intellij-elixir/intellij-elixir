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
         * key is unset, where [declaration] says what the project declares of the app.
         */
        fun of(
            configured: (app: String, key: OtpErlangObject) -> Boolean,
            unread: (app: String) -> Boolean,
            declaration: (app: String) -> AppDeclaration,
        ): AppEnv =
            AppEnv { app, key ->
                when {
                    configured(app, key) -> AppEnvValue.Configured
                    unread(app) -> AppEnvValue.Unreadable
                    else -> declared(declaration(app), key)
                }
            }

        /** What [declaration] answers for [key]. */
        fun declared(declaration: AppDeclaration, key: OtpErlangObject): AppEnvValue =
            when (declaration) {
                is AppDeclaration.Read -> declaration.appFile.env[key]?.let(AppEnvValue::Declared) ?: AppEnvValue.Unset
                AppDeclaration.Absent -> AppEnvValue.Unset
                AppDeclaration.Unknown -> AppEnvValue.Unreadable
            }
    }
}

/** What the project declares of an app: the `.app` file Elixir loads for it, that it can't have the app, or neither is known. */
internal sealed interface AppDeclaration {
    /** The app's `.app` file, as every copy Elixir may load agrees. */
    data class Read(val appFile: AppFile) : AppDeclaration

    /** No `.app`, folder, library or `mix.exs` of the project's has the app, so Elixir never loads it. */
    data object Absent : AppDeclaration

    /** A copy can't be read or names another app, copies differ, or the project may have the app without a copy yet. */
    data object Unknown : AppDeclaration
}

internal sealed class AppEnvValue {
    /**
     * The key may be set to a value not known here: the config table sets it to one, a config file sets it and no table
     * answers, or the read is made while config runs, or in a build that fails.
     */
    data object Configured : AppEnvValue()

    /** The key's value is [term], from the config table or the app's `.app`. */
    data class Declared(val term: OtpErlangObject) : AppEnvValue()

    /** Nothing sets the key, or the project can't have the app, so Elixir never loaded it. */
    data object Unset : AppEnvValue()

    /**
     * Whether the key is set isn't known: a config setting of the app, or the app's `.app`, can't be read, or a config read
     * wrote the app, whose env is what loading it makes of the write.
     */
    data object Unreadable : AppEnvValue()
}
