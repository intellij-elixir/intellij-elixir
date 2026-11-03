package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.ui.CollectionListModel
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.facet.SdksService
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

/**
 * The Erlang step. Its install scan and its dialog are the internal constructor's parameters, so a test builds the
 * step with its own; the IDE builds it with the defaults, through the no-argument constructor they give. A light
 * service's class must be final.
 */
@Service
class ErlangStep internal constructor(
    /** The Erlang installs found from the Elixir install at a home. Reads the filesystem: never on the EDT. */
    private val installHomes: (elixirHome: String) -> List<String> = {
        SdkHomeChooser.homesNewestFirst(ErlangSdkType.instance)(Path.of(it))
    },
    private val dialog: Dialog = Dialog(::showDialog),
) {
    /** Asks for one of the [state]'s entries; see [showDialog]. */
    internal fun interface Dialog {
        fun show(
            parent: Component?,
            state: ErlangStepState,
            browseFrom: String,
            chooseAnotherInstall: ChooseAnotherInstall?,
        ): ErlangStepAnswer
    }

    /**
     * Asks which Erlang the Elixir install at [elixirHome] runs on, from the Erlang SDKs in [sdkModel], or in the table
     * when it is null, and the installs found from it (see [showDialog]). Returns the pick's Erlang SDK, which [sdkFor]
     * registers, or null when nothing is picked. The scan and the version reads run under modal progress first.
     * [asking] is an Elixir SDK being re-paired. [chooseAnotherInstall], when given, is where a pick built for another
     * OTP can go back to, with the pick's OTP major.
     */
    @RequiresEdt
    fun ask(
        parent: Component?,
        elixirHome: String,
        sdkModel: SdkModel?,
        asking: Sdk? = null,
        chooseAnotherInstall: ChooseAnotherInstall? = null,
    ): Sdk? {
        val sdks = (sdkModel?.sdks ?: ProjectJdkTable.getInstance().allJdks).asList()
        var installs = emptyList<String>()
        val inEnvironment = SdkEnvironment.sameEnvironmentAs(elixirHome)
        val sdkHomes = listable(sdks, inEnvironment).mapNotNull(Sdk::getHomePath)
        // A scanned folder with no `erl` to run, such as an unfinished install, would register an SDK that can't run.
        val scan = { installHomes(elixirHome).filter(ErlangSdkType.instance::isValidSdkHome) }
        val scanned = SdkHomeChooser.scanUnderProgress("Looking for installed Erlangs", scan) { awaitScan ->
            // The known homes the step can list are read while the scan runs.
            SdkVersionsFiller.fillIfUnreadBlocking(listOf(elixirHome) + sdkHomes)
            installs = awaitScan()
            SdkVersionsFiller.fillIfUnreadBlocking(installs)
        }
        if (!scanned) return null

        val browseFrom = SdkHomeChooser.firstUnregistered(installs, sdkHomes) ?: elixirHome
        val answer = dialog.show(parent, state(elixirHome, sdks, installs, asking, inEnvironment), browseFrom, chooseAnotherInstall)
        return (answer as? ErlangStepAnswer.Picked)?.let { sdkFor(it.entry, sdkModel) }
    }

    /**
     * The picked entry's Erlang SDK. An install becomes an Erlang SDK of the plugin's type, set up and registered
     * through [SdksService.register], named apart from [sdkModel]'s SDKs and the table's.
     */
    @RequiresEdt
    private fun sdkFor(entry: ErlangStepEntry, sdkModel: SdkModel?): Sdk {
        entry.sdk?.let { return it }

        val taken = sdkModel?.sdks?.asList().orEmpty() + ProjectJdkTable.getInstance().allJdks
        val sdk = SdkConfigurationUtil.createSdk(taken, entry.home, ErlangSdkType.instance, null, null)
        ErlangSdkType.instance.setupSdkPaths(sdk)
        service<SdksService>().register(sdk, sdkModel)

        return sdk
    }

    companion object {
        fun getInstance(): ErlangStep = ApplicationManager.getApplication().service()

        /** Asks for one of the [state]'s entries (see [ErlangStepDialog]). Browse opens at [browseFrom]. */
        private fun showDialog(
            parent: Component?,
            state: ErlangStepState,
            browseFrom: String,
            chooseAnotherInstall: ChooseAnotherInstall?,
        ): ErlangStepAnswer =
            ErlangStepDialog(parent, state, chooseAnotherInstall) { browse(browseFrom) }.showAndPick()

        private fun browse(browseFrom: String): String? {
            var chosen: String? = null
            SdkHomeChooser.chooseSdkHome(ErlangSdkType.instance, SdkHomeChooser.folderAt(browseFrom)) { chosen = it }
            chosen?.let(SdkVersionsFiller::fillIfUnreadBlocking)
            return chosen
        }

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
