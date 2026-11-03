package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.components.Service
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.ui.CollectionListModel
import org.elixir_lang.sdk.SdkEnvironment
import org.elixir_lang.sdk.SdkHomeChooser
import org.elixir_lang.sdk.SdkRegistrar
import org.elixir_lang.sdk.SdkVersionsFiller
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.ElixirSdkValidation
import org.elixir_lang.sdk.elixir.OtpMatch
import org.elixir_lang.sdk.wsl.wslCompat
import java.awt.Component
import java.nio.file.Path
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

/**
 * One Erlang the step offers for an Elixir install: a registered Erlang SDK, or an install no listed SDK has ([sdk]
 * null).
 */
data class ErlangStepEntry(
    val sdk: Sdk?,
    val home: String,
    val otpVersion: String?,
    val otpMatch: OtpMatch,
    /** Why it can't be picked, or null when it can. */
    val unpickable: Unpickable?,
) {
    val pickable: Boolean get() = unpickable == null
}

/** Why an [ErlangStepEntry] can't be picked. */
sealed interface Unpickable {
    /** The Elixir SDK named [elixirSdkName], on this install, is already paired with this Erlang; a second pairing would duplicate it. */
    data class PairedWith(val elixirSdkName: String) : Unpickable

    /** The Erlang is on another machine than the Elixir install, which can't run on it. */
    data object OnAnotherMachine : Unpickable
}

/** How the step was answered. */
sealed interface ErlangStepAnswer {
    data class Picked(val entry: ErlangStepEntry) : ErlangStepAnswer

    /** Nothing picked: cancelled, or gone back to choose another Elixir install. */
    data object Cancelled : ErlangStepAnswer
}

/** Where a pick built for another OTP goes back to, to choose another Elixir install, with the pick's OTP major. */
typealias ChooseAnotherInstall = (otpMajor: Int?) -> Unit

/**
 * What the step's dialog shows and has picked; nothing is picked until the user picks. [installEntry] is the entry for a
 * home no entry has.
 */
class ErlangStepState(entries: List<ErlangStepEntry>, private val installEntry: (home: String) -> ErlangStepEntry) {
    /** The entries, as the dialog's list shows them. */
    val listModel = CollectionListModel(entries)
    val entries: List<ErlangStepEntry> get() = listModel.items

    var selected: ErlangStepEntry? = null
        private set

    fun select(entry: ErlangStepEntry?) {
        selected = entry?.takeIf { it.pickable && listModel.contains(it) }
    }

    /** A home browsed to selects the entry already listed for it, else becomes an install entry; that entry. */
    fun browsed(home: String): ErlangStepEntry {
        val entry = entries.firstOrNull { wslCompat.pathsEqualWslAware(it.home, home) }
            ?: installEntry(home).also(listModel::add)
        select(entry)
        return entry
    }
}

@Service
class ErlangStep {
    /** Asks for one of the [state]'s entries for the Elixir install at [elixirHome] (see [ErlangStepDialog]). */
    fun choose(
        parent: Component?,
        elixirHome: String,
        state: ErlangStepState,
        chooseAnotherInstall: ChooseAnotherInstall? = null,
    ): ErlangStepAnswer =
        ErlangStepDialog(parent, state, chooseAnotherInstall) { browse(elixirHome, state.entries) }.showAndPick()

    private fun browse(elixirHome: String, entries: List<ErlangStepEntry>): String? {
        var chosen: String? = null
        SdkHomeChooser.selectSdkHome(ErlangSdkType.instance, Path.of(elixirHome), entries.map { it.home }) { chosen = it }
        chosen?.let(SdkVersionsFiller::fillIfUnreadBlocking)
        return chosen
    }

    companion object {
        /**
         * The step's state for the Elixir install at [elixirHome]: the registered Erlang SDKs in [sdks] it can depend on,
         * at a home [inEnvironment] keeps, then the [installHomes] none of them has, and an entry for a home browsed to
         * that no entry has. Reads only [SdkVersionsStore], so the homes are read first.
         *
         * An entry can't be picked when [inEnvironment] doesn't keep its home, or when an Elixir SDK on this install
         * other than [asking], the Elixir SDK being re-paired, is paired with it.
         */
        internal fun state(
            elixirHome: String,
            sdks: List<Sdk>,
            installHomes: List<String>,
            asking: Sdk? = null,
            inEnvironment: (home: String) -> Boolean = SdkEnvironment.sameEnvironmentAs(elixirHome),
        ): ErlangStepState {
            val pairedWith = pairedWith(elixirHome, sdks, asking)
            val row = { sdk: Sdk?, home: String ->
                entry(elixirHome, sdk, home, if (inEnvironment(home)) pairedWith(home, sdk?.name) else Unpickable.OnAnotherMachine)
            }
            val registered = listable(sdks, inEnvironment).sortedBy(Sdk::getName)
            val registeredEntries = registered.map { sdk -> row(sdk, sdk.homePath!!) }
            val installEntries = SdkHomeChooser.unregistered(installHomes, registered.mapNotNull(Sdk::getHomePath))
                .map { home -> row(null, home) }

            return ErlangStepState(registeredEntries + installEntries) { home -> row(null, home) }
        }

        /** The Erlang SDKs in [sdks] with a home [inInstallsEnvironment] keeps, which the step can list. */
        private fun listable(sdks: List<Sdk>, inInstallsEnvironment: (home: String) -> Boolean): List<Sdk> =
            sdks.filter { sdk ->
                Type.staticIsValidDependency(sdk) && sdk.homePath.let { !it.isNullOrBlank() && inInstallsEnvironment(it) }
            }

        /**
         * Why an Erlang at a home, named as an SDK or null for an install, can't be picked for the Elixir install at
         * [elixirHome]: the Elixir SDK in [sdks] other than [asking] already paired with it there, by the registrar's
         * variant rule.
         */
        private fun pairedWith(elixirHome: String, sdks: List<Sdk>, asking: Sdk?): (home: String, name: String?) -> Unpickable? {
            val otherSdks = sdks.filter { it !== asking }
            return { home, name ->
                SdkRegistrar.findElixirSdkByVariant(otherSdks, elixirHome, home, name)?.let { Unpickable.PairedWith(it.name) }
            }
        }

        private fun entry(elixirHome: String, sdk: Sdk?, home: String, unpickable: Unpickable?): ErlangStepEntry =
            ErlangStepEntry(
                sdk,
                home,
                SdkVersionsStore.getInstance().otpVersion(home),
                ElixirSdkValidation.otpMatch(elixirHome, home),
                unpickable,
            )
    }
}
