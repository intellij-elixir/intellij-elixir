package org.elixir_lang.code_insight.matrix

/**
 * Where a definition lives, and so which decompiler clause source (if any) the plugin reads it through.
 *
 * [guardOnly] marks a backing that asks no behavioural question because it runs code another backing already
 * covers. It keeps a fixture so the reason can be pinned by a guard, and contributes no cells.
 */
enum class Backing(
    val id: String,
    val compiled: Boolean,
    val hasBodies: Boolean,
    val keepsEveryClause: Boolean,
    val guardOnly: Boolean = false,
) {
    SRC("src", compiled = false, hasBodies = true, keepsEveryClause = true),
    EX_DBGI("ex_dbgi", compiled = true, hasBodies = true, keepsEveryClause = true),
    EX_DOCS("ex_docs", compiled = true, hasBodies = false, keepsEveryClause = false),
    EX_GEN("ex_gen", compiled = true, hasBodies = false, keepsEveryClause = false),
    ERL_ABST("erl_abst", compiled = true, hasBodies = true, keepsEveryClause = true),

    /**
     * Stripped of its Docs chunk, nothing says the beam is Erlang, so the plugin falls back to the Elixir
     * decompiler and answers exactly as [EX_GEN] does - measured over 1,090 comparable cells with no
     * disagreement, which the code explains rather than coincidence.
     */
    ERL_GEN("erl_gen", compiled = true, hasBodies = false, keepsEveryClause = false, guardOnly = true);

    companion object {
        fun of(scenario: Scenario): Backing = entries.single { it.id == scenario.backing }
    }
}

/**
 * A user-facing feature, named as it appears first in each cell's name. A group asks them in this order, so the
 * features that edit the project come after the ones that only read it, and Rename, the widest of those, last.
 */
enum class Feature(val testName: String, val edits: Boolean = false) {
    GO_TO_DECLARATION("goToDeclaration"),
    FIND_USAGES("findUsages"),
    LABEL("label"),
    QUICK_DOCUMENTATION("quickDocumentation"),
    UNAVAILABLE_NOTICE("unavailableNotice"),
    PARAMETER_INFO("parameterInfo"),
    CTRL_CLICK("ctrlClick"),
    HIGHLIGHTING("highlighting"),
    DIAGNOSTIC("diagnostic"),
    STRUCTURE_VIEW("structureView"),
    BREADCRUMBS("breadcrumbs"),
    COMPLETION_OFFERED("completionOffered", edits = true),
    COMPLETION_INSERTED("completionInserted", edits = true),
    RENAME("rename", edits = true),
}

/**
 * The one phrase the "this source cannot tell you" notice must carry, so a developer reading `_` where they
 * expected a parameter name learns that the dependency was shipped stripped rather than that the IDE is broken -
 * and knows to look for a build of it that was not.
 *
 * Matched case-insensitively as a substring: the sentence around it is the implementation's to write, and this is
 * the only part of it the matrix holds to, so improving the wording does not redden every cell.
 */
const val UNAVAILABLE_PHRASE = "compiled without debug info"

/**
 * What the editor complains of at a call, as a category rather than a sentence.
 *
 * The distinction is the whole point: a developer who typed an arity that does not exist has asked a question the
 * editor can answer - *these are the arities there are* - while one who named a function that does not exist has
 * not. Collapsing both into "does not resolve to anything" answers neither, and is what the compiler itself
 * refuses to do, naming `snoc/2` in its own message.
 */
enum class Complaint { ARITY_MISMATCH, UNRESOLVED, UNRESOLVED_MODULE }

/**
 * The plugin's current wordings, in the one place a better wording has to be changed: improving a message is an
 * edit here and reddens nothing. A description this does not recognise is reported as itself, so a new message is
 * a named failure rather than a silent absence.
 */
fun complaintOf(description: String): String =
    when {
        description == "Does not resolve to anything" -> Complaint.UNRESOLVED.name
        description == "Only resolves to invalid results" -> Complaint.ARITY_MISMATCH.name
        description.startsWith("Module '") -> Complaint.UNRESOLVED_MODULE.name
        else -> description
    }

/** The sites whose text is a call a user could be typing; a capture, `apply`'s atom and a non-call are not. */
private val NOT_TYPED_CALLS = setOf("capture", "apply", "variable", "atom", "keyword")

/** The site written without an argument list, which is a different shape in the parser. */
const val NO_ARGUMENTS = "no_arguments"

/**
 * Whether [id] names a site written without an argument list.
 *
 * Matched as a suffix, not for equality: a world names such a site after what it calls, so `x_arity_absent` has both
 * `no_arguments` and `undeclared_no_arguments`. Comparing against the bare name missed the second, and the cells that
 * should have been not-applicable instead asked Parameter Info at a caret that had nowhere to go.
 */
fun writtenWithoutArguments(id: String): Boolean = id == NO_ARGUMENTS || id.endsWith("_$NO_ARGUMENTS")

/** The sites that are not calls of a definition at all. */
private val NOT_CALLS = setOf("variable", "atom", "keyword")

/** Forms whose calls a compiler expands away. */
val MACRO_FORMS = setOf("defmacro", "defmacrop", "defguard", "defguardp")

/** Where the caret is: on a clause head of the main module, or on a site the oracle marked. */
sealed interface Place {
    val id: String

    data class Head(val name: String, val arity: Int, val clause: Int) : Place {
        override val id: String get() = "declaration($name/$arity#$clause)"
    }

    data class Marked(override val id: String) : Place
}

/** The call inside the declaring module; the only site not in the caller. */
const val LOCAL = "local"

data class Cell(val scenario: Scenario, val feature: Feature, val place: Place) {
    val testName: String
        get() = "${feature.testName}[${scenario.backing},${scenario.form},${scenario.world},${place.id}]"
}

sealed interface Applicability {
    data object Applicable : Applicability
    data class NotApplicable(val reason: String) : Applicability
}

/** Which cells ask a question the scenario can answer. */
object Crossing {
    fun applicability(scenario: Scenario, feature: Feature, place: Place): Applicability {
        val backing = Backing.of(scenario)

        return when {
            place is Place.Marked && place.id == LOCAL && !backing.hasBodies ->
                Applicability.NotApplicable("the ${backing.id} mirror has no bodies, so no local call")
            place is Place.Marked && place.id == LOCAL && !hasLocalCall(scenario) ->
                Applicability.NotApplicable("a compiled body holds the macro's expansion, not a call to it")
            place is Place.Head && place.clause > 0 && !backing.keepsEveryClause ->
                Applicability.NotApplicable("the ${backing.id} mirror keeps only the first clause")
            feature == Feature.GO_TO_DECLARATION && place is Place.Head ->
                Applicability.NotApplicable("Ctrl+Click on a declaration is Show Usages, asked separately")
            feature == Feature.CTRL_CLICK && place !is Place.Head ->
                Applicability.NotApplicable("a call's Ctrl+Click is Go To Declaration, asked separately")
            feature == Feature.PARAMETER_INFO && (place is Place.Head || place.id in NOT_TYPED_CALLS) ->
                Applicability.NotApplicable("${place.id} has no argument list to show hints for")
            feature == Feature.PARAMETER_INFO && writtenWithoutArguments(place.id) ->
                Applicability.NotApplicable("a call written without an argument list has nowhere to put a hint")
            feature == Feature.DIAGNOSTIC && place is Place.Head ->
                Applicability.NotApplicable("a diagnostic answers a call; what a declaration's own text is marked with is the declaring file's business")
            feature == Feature.COMPLETION_INSERTED && place is Place.Marked && undeclared(scenario, place) ->
                Applicability.NotApplicable("no module declares that name, so there is nothing completion could insert; that it offers nothing is asked by completionOffered")
            feature == Feature.HIGHLIGHTING && place.id == "apply" ->
                Applicability.NotApplicable("`apply`'s argument is highlighted as an atom")
            (feature == Feature.STRUCTURE_VIEW || feature == Feature.BREADCRUMBS) && place !is Place.Head ->
                Applicability.NotApplicable("structure view and breadcrumbs describe declarations")
            feature == Feature.STRUCTURE_VIEW && place is Place.Head && place.clause > 0 ->
                Applicability.NotApplicable("a definition has one structure view entry, asked at its first clause")
            (feature == Feature.COMPLETION_OFFERED || feature == Feature.COMPLETION_INSERTED) && place is Place.Head ->
                Applicability.NotApplicable("nothing is typed at a declaration's name")
            (feature == Feature.COMPLETION_OFFERED || feature == Feature.COMPLETION_INSERTED) && place.id in NOT_TYPED_CALLS ->
                Applicability.NotApplicable("${place.id} is not a call being typed")
            (feature == Feature.COMPLETION_OFFERED || feature == Feature.COMPLETION_INSERTED) && place.id == LOCAL && backing.compiled ->
                Applicability.NotApplicable("the ${backing.id} mirror is read-only")
            (feature == Feature.COMPLETION_OFFERED || feature == Feature.COMPLETION_INSERTED) &&
                place.id == "lookalike_snoc_combining" && backing == Backing.ERL_ABST ->
                Applicability.NotApplicable("a decomposed Erlang name is called as a quoted atom, which is not typed as an identifier")
            feature == Feature.RENAME && place.id in NOT_CALLS ->
                Applicability.NotApplicable("${place.id} is not a use of the function")
            else -> Applicability.Applicable
        }
    }

    /**
     * Whether the scenario's declaring file holds a call of its own definition at all.
     *
     * Two ways it does not: a mirror decompiled without bodies has nowhere to put one, and a compiled macro's body
     * holds the expansion rather than a call to the macro. The [LOCAL] place and the expected usages of a definition
     * both turn on this, so it is asked in one place - they were separate predicates that had to be kept in step by
     * hand.
     */
    fun hasLocalCall(scenario: Scenario): Boolean {
        val backing = Backing.of(scenario)

        return backing.hasBodies && !(backing.compiled && scenario.form in MACRO_FORMS)
    }

    /** A site naming a function no module in the scenario declares at any arity - the control for "no such name". */
    private fun undeclared(scenario: Scenario, place: Place.Marked): Boolean {
        val site = scenario.sites.firstOrNull { it.id == place.id } ?: return false

        return scenario.modules.none { module -> module.definitions.any { nfc(it.name) == nfc(site.name) } }
    }

    fun places(scenario: Scenario): List<Place> =
        scenario.main.definitions.flatMap { definition ->
            (0 until definition.clauses).map { Place.Head(definition.name, definition.maxArity, it) }
        } + scenario.sites.map { Place.Marked(it.id) }
}
