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
    INCOMPLETE_RESOLUTION("incompleteResolution"),
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
        // Alone, or followed by the suggestions: `Does not resolve to anything. Did you mean: snoc/2, snoc/3?`
        description.startsWith("Does not resolve to anything") -> Complaint.UNRESOLVED.name
        // Alone, or followed by the arities: `Only resolves to invalid results; defined as snoc/2`
        description.startsWith("Only resolves to invalid results") -> Complaint.ARITY_MISMATCH.name
        // `Will not compile: snoc is not defined for 1 argument; defined as snoc/2`
        description.startsWith("Will not compile: ") && "; defined as " in description -> Complaint.ARITY_MISMATCH.name
        description.startsWith("Module '") -> Complaint.UNRESOLVED_MODULE.name
        else -> description
    }

/** A `name/arity`, or a `name/arity+` for an arity with no upper bound, as a message writes one. */
private val NAME_ARITY = Regex("""[\p{L}\p{N}_\p{Mn}\p{Mc}]+[?!]?/\d+\+?""")

/** One entry of the compiler's `Did you mean:` list, which it writes as `    * name/arity` on a line of its own. */
private val SUGGESTION = Regex("""^\s*\* (\S+/\d+)\s*$""", RegexOption.MULTILINE)

/**
 * The `name/arity`s the compiler suggests in [message], sorted: empty where it suggests nothing, as for an
 * unqualified call, which it reports as `undefined function` with no list.
 */
fun suggestionsOf(message: String): List<String> =
    SUGGESTION.findAll(message).map { nfc(it.groupValues[1]) }.distinct().sorted().toList()

/** The `name/arity`s [description] names, sorted; like [complaintOf], the one place a wording is read. */
fun namedArities(description: String): List<String> =
    NAME_ARITY.findAll(description).map { nfc(it.value) }.distinct().sorted().toList()

/** The sites whose text is a call a user could be typing; a capture, `apply`'s atom and a non-call are not. */
private val NOT_TYPED_CALLS = setOf("capture", "apply", "apply_quoted", "variable", "atom", "keyword", "capture_1", "apply_1", "mfa_1")

/** The sites that name the function with an atom: `apply`'s argument and an MFA tuple's middle element. */
private val ATOM_NAMED = setOf("apply", "apply_quoted", "apply_1", "mfa_1")

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

/**
 * The world putting `w3`'s lookalikes beside arities nothing defines, so the compiler's "Did you mean" has both a
 * wrong arity and a similar name to choose from. Only its rejected calls ask anything `w3` does not.
 */
const val LOOKALIKE_ABSENT = "x_lookalike_absent"

/** Whether [id] is the name in a `@spec`, which `generate.exs` marks `spec_<arity>`. */
fun specName(id: String): Boolean = id.startsWith("spec_")

/** Whether [id] is the key of an `import`'s `only:`/`except:` list, which `generate.exs` marks `<owner>_key`. */
fun importKey(id: String): Boolean = id.endsWith("_key")

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

/**
 * A call a private form's module makes only so the compiler does not call an arity unused, marked `uses_<n>`. It is a
 * use like any other, which Find Usages and rename must reach, but asks nothing [LOCAL] does not.
 */
fun privateUse(id: String): Boolean = id.startsWith("uses_")

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
            feature == Feature.INCOMPLETE_RESOLUTION && !(place is Place.Marked && rejectedByName(scenario, place)) ->
                Applicability.NotApplicable("asked only where the compiler rejected the name itself, which no amount of typing makes valid")
            place is Place.Marked && privateUse(place.id) ->
                Applicability.NotApplicable("a call made only to keep an arity used; it is a use to find and rename, but asks nothing `local` does not")
            scenario.world == LOOKALIKE_ABSENT && !rejected(scenario, place) ->
                Applicability.NotApplicable("$LOOKALIKE_ABSENT asks only where the compiler rejected a call; everywhere else it is w3's question again")
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
            place is Place.Marked && importKey(place.id) &&
                feature in setOf(Feature.HIGHLIGHTING, Feature.PARAMETER_INFO, Feature.COMPLETION_OFFERED, Feature.COMPLETION_INSERTED) ->
                Applicability.NotApplicable("an `only:`/`except:` key names a function by name and arity; it is not a call")
            place is Place.Marked && specName(place.id) &&
                feature in setOf(Feature.HIGHLIGHTING, Feature.PARAMETER_INFO, Feature.COMPLETION_OFFERED, Feature.COMPLETION_INSERTED) ->
                Applicability.NotApplicable("a `@spec` names a function in a type; it is not a call")
            feature == Feature.COMPLETION_INSERTED && place is Place.Marked && undeclared(scenario, place) ->
                Applicability.NotApplicable("no module declares that name, so there is nothing completion could insert; that it offers nothing is asked by completionOffered")
            feature == Feature.COMPLETION_INSERTED && place is Place.Marked && importsNothing(scenario, place) ->
                Applicability.NotApplicable("the caller's directives bring no function in, so there is nothing completion could insert; that it offers nothing is asked by completionOffered")
            feature == Feature.HIGHLIGHTING && place.id in ATOM_NAMED ->
                Applicability.NotApplicable("a function named by an atom is highlighted as an atom")
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

    /** A call the compiler rejected, which is where it says what it thinks was meant. */
    private fun rejected(scenario: Scenario, place: Place): Boolean =
        place is Place.Marked && scenario.sites.firstOrNull { it.id == place.id }?.diagnostic != null

    /**
     * A call the compiler rejected for its name rather than its arity: a name nothing declares, or one the call cannot
     * see at any arity - private from outside, or not brought in by the caller's directives.
     */
    private fun rejectedByName(scenario: Scenario, place: Place.Marked): Boolean =
        rejected(scenario, place) && (undeclared(scenario, place) || importsNothing(scenario, place))

    /** A bare call under directives that bring nothing of the declaring module in: a `require`, or a transitive import. */
    private fun importsNothing(scenario: Scenario, place: Place.Marked): Boolean =
        scenario.sites.firstOrNull { it.id == place.id }?.visible?.isEmpty() == true

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
