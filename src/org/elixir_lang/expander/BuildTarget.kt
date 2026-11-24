package org.elixir_lang.expander

/**
 * The build a run's file is compiled in, decided once for the run.
 *
 * @property configEnv the env whose config the build reads, `null` where it isn't known
 * @property mixEnv what `Mix.env/0` answers, `null` where it isn't known: Mix compiles a dependency under an env of its
 *   own, which needn't be the `_build` env holding it
 * @property script whether the file is a script a task runs, as a config file and an `.exs` are, rather than a file the
 *   build compiles
 * @property app the app whose compile the file is part of, `null` for a script or where the project doesn't write it
 */
internal data class BuildTarget(
    val configEnv: String?,
    val mixEnv: String?,
    val target: String = HOST,
    val script: Boolean,
    val app: String? = null,
) {
    companion object {
        /** Every build's target: the plugin takes no outside `MIX_TARGET`. */
        const val HOST = "host"
    }
}
