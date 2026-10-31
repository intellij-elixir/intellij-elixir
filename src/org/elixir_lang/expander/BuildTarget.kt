package org.elixir_lang.expander

/**
 * The build a run's file is compiled in, decided once for the run.
 *
 * @property configEnv the env whose config the build reads, `null` where it isn't known
 * @property mixEnv what `Mix.env/0` answers, `null` where it isn't known: Mix compiles a dependency under an env of its
 *   own, which needn't be the `_build` env holding it
 */
internal data class BuildTarget(val configEnv: String?, val mixEnv: String?, val target: String = HOST) {
    companion object {
        /** Every build's target: the plugin takes no outside `MIX_TARGET`. */
        const val HOST = "host"
    }
}
