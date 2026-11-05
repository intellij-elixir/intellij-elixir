package org.elixir_lang.tool_manager

import com.intellij.openapi.project.Project
import org.elixir_lang.PlatformTestCase
import org.mockito.Mockito.mock
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Unit tests for the pure (Phase 2/3) methods of [ToolManagerSdkChecker]:
 * [ToolManagerSdkChecker.detectMismatchIssues], [ToolManagerSdkChecker.buildAssignments],
 * and [ToolManagerSdkChecker.collectErrors].
 *
 * These methods perform no platform I/O and require no read lock; all inputs are
 * plain Kotlin data objects.  A [Project] mock is passed for construction only -
 * none of the methods under test call through to it.
 */
class ToolManagerSdkCheckerTest : PlatformTestCase() {

    private lateinit var checker: ToolManagerSdkChecker

    override fun setUp() {
        super.setUp()
        checker = ToolManagerSdkChecker(
            project = mock(Project::class.java),
            toolManagers = emptyList(),
            settings = ToolManagerSettings(),
        )
    }

    // -------------------------------------------------------------------------
    // Test-local helpers
    // -------------------------------------------------------------------------

    private fun elixirEntry(
        version: String,
        installPath: String,
        installed: Boolean = true,
    ) = ToolEntry(version, installPath, installed)

    private fun erlangEntry(
        version: String,
        installPath: String = "/mise/installs/erlang/$version",
        installed: Boolean = true,
    ) = ToolEntry(version, installPath, installed)

    private fun moduleData(
        moduleName: String,
        elixirHome: String? = null,
        elixirVersion: String? = null,
        elixirVersionString: String? = elixirVersion,
        erlangHome: String? = null,
        erlangVersion: String? = null,
        erlangVersionString: String? = erlangVersion,
        contentRoot: Path? = Paths.get("/project"),
    ) = ToolManagerSdkChecker.ModuleCheckData(
        moduleName = moduleName,
        elixirSdkHomePath = elixirHome,
        elixirSdkVersion = elixirVersion,
        elixirSdkVersionString = elixirVersionString,
        erlangSdkHomePath = erlangHome,
        erlangSdkVersion = erlangVersion,
        erlangSdkVersionString = erlangVersionString,
        contentRoot = contentRoot,
    )

    fun testAnSdkWithNoRecordedVersionStillNamesBothPaths() {
        val contentRoot = Paths.get("/project")
        val data = moduleData(
            moduleName = "app",
            elixirHome = "/home/user/.elixir/1.20.5",
            // Not recorded yet, so only the SDK's version string is known.
            elixirVersion = null,
            elixirVersionString = "mise Elixir 1.20.5 (OTP 27)",
            contentRoot = contentRoot,
        )

        val (issues, tables) = checker.detectMismatchIssues(
            listOf(data),
            mapOf(contentRoot to success(versions(elixir = elixirEntry("1.20.5", "/mise/installs/elixir/1.20.5-otp-28")))),
            emptyMap(),
        )

        assertEquals(1, issues.size)
        assertTrue(
            "the issue must name both paths; got '${issues.single().issue}'",
            issues.single().issue.contains("/home/user/.elixir/1.20.5") &&
                issues.single().issue.contains("/mise/installs/elixir/1.20.5-otp-28"),
        )
        assertEquals(
            "the table still shows what the SDK reports",
            "mise Elixir 1.20.5 (OTP 27)",
            tables.values.single().rows.single().configuredVersion,
        )
    }

    private fun success(versions: ToolManagerVersions): ToolManagerResult.Success =
        ToolManagerResult.Success(versions)

    private fun error(
        description: String = "an error",
        toolManagerName: String = "mise",
    ): ToolManagerResult.Error = ToolManagerResult.Error(toolManagerName, description)

    private fun detect(
        vararg moduleCheckData: ToolManagerSdkChecker.ModuleCheckData,
        results: Map<Path, ToolManagerResult?>,
        canonicalPathByPath: Map<String, String> = emptyMap(),
    ) = checker.detectMismatchIssues(moduleCheckData.toList(), results, canonicalPathByPath)

    // -------------------------------------------------------------------------
    // collectErrors
    // -------------------------------------------------------------------------

    fun testCollectErrors_emptyInput_returnsEmpty() {
        assertTrue(checker.collectErrors(emptyMap()).isEmpty())
    }

    fun testCollectErrors_onlySuccessAndNull_returnsEmpty() {
        val roots = mapOf<Path, ToolManagerResult?>(
            Paths.get("/a") to success(versions()),
            Paths.get("/b") to null,
        )
        assertTrue(checker.collectErrors(roots).isEmpty())
    }

    fun testCollectErrors_mixedResults_returnsOnlyErrors() {
        val roots = mapOf<Path, ToolManagerResult?>(
            Paths.get("/a") to error("err1"),
            Paths.get("/b") to success(versions()),
            Paths.get("/c") to null,
            Paths.get("/d") to error("err2"),
        )
        val errors = checker.collectErrors(roots)
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.description == "err1" })
        assertTrue(errors.any { it.description == "err2" })
    }

    fun testCollectErrors_deduplicatesByDescription() {
        val roots = mapOf<Path, ToolManagerResult?>(
            Paths.get("/a") to error("same error"),
            Paths.get("/b") to error("same error"),  // duplicate
            Paths.get("/c") to error("different error"),
        )
        val errors = checker.collectErrors(roots)
        assertEquals(2, errors.size)
        assertEquals(1, errors.count { it.description == "same error" })
        assertEquals(1, errors.count { it.description == "different error" })
    }

    // -------------------------------------------------------------------------
    // buildAssignments
    // -------------------------------------------------------------------------

    fun testBuildAssignments_elixirInstalled_included() {
        val path = Paths.get("/project")
        val v = versions(elixir = elixirEntry("1.17.3", "/elixir-1.17", installed = true))
        val assignments = checker.buildAssignments(
            listOf(moduleData("mod", contentRoot = path)),
            mapOf(path to success(v)),
        )
        assertEquals(1, assignments.size)
        assertNotNull(assignments["mod"])
    }

    fun testBuildAssignments_elixirNotInstalled_excluded() {
        val path = Paths.get("/project")
        val v = versions(elixir = elixirEntry("1.17.3", "/elixir-1.17", installed = false))
        assertTrue(
            checker.buildAssignments(
                listOf(moduleData("mod", contentRoot = path)),
                mapOf(path to success(v)),
            ).isEmpty()
        )
    }

    fun testBuildAssignments_pinnedErlangNotInstalled_excluded() {
        val path = Paths.get("/project")
        val v = versions(
            elixir = elixirEntry("1.19.5-otp-28", "/elixir-1.19.5-otp-28"),
            erlang = erlangEntry("28.1.2", installed = false),
        )
        assertTrue(
            "configuring now would register the Elixir SDK with no Erlang; mise install comes first",
            checker.buildAssignments(listOf(moduleData("mod", contentRoot = path)), mapOf(path to success(v))).isEmpty(),
        )
    }

    fun testBuildAssignments_pinnedErlangInstalled_included() {
        val path = Paths.get("/project")
        val v = versions(
            elixir = elixirEntry("1.19.5-otp-28", "/elixir-1.19.5-otp-28"),
            erlang = erlangEntry("28.1.2"),
        )
        assertEquals(
            setOf("mod"),
            checker.buildAssignments(listOf(moduleData("mod", contentRoot = path)), mapOf(path to success(v))).keys,
        )
    }

    fun testDetectMismatch_anUninstalledPinNamesItselfWhenTheModuleHasNoSdk() {
        val path = Paths.get("/project")
        val (issues, _) = detect(
            moduleData("my_app", contentRoot = path),
            results = mapOf(
                path to success(
                    versions(
                        elixir = elixirEntry("1.19.5-otp-28", "/elixir-1.19.5-otp-28"),
                        erlang = erlangEntry("28.1.2", installed = false),
                    )
                )
            ),
        )

        assertEquals(
            "a module with no SDK has no table row to carry it",
            listOf(NotInstalled("mise", "Erlang", "28.1.2")),
            issues.mapNotNull { it.notInstalled },
        )
    }

    fun testBuildAssignments_noElixirEntry_excluded() {
        val path = Paths.get("/project")
        val v = versions(elixir = null, erlang = erlangEntry("27.3"))
        assertTrue(
            checker.buildAssignments(
                listOf(moduleData("mod", contentRoot = path)),
                mapOf(path to success(v)),
            ).isEmpty()
        )
    }

    fun testBuildAssignments_errorResult_excluded() {
        val path = Paths.get("/project")
        assertTrue(
            checker.buildAssignments(
                listOf(moduleData("mod", contentRoot = path)),
                mapOf(path to error()),
            ).isEmpty()
        )
    }

    fun testBuildAssignments_nullResult_excluded() {
        val path = Paths.get("/project")
        assertTrue(
            checker.buildAssignments(
                listOf(moduleData("mod", contentRoot = path)),
                mapOf(path to null),
            ).isEmpty()
        )
    }

    fun testBuildAssignments_nullContentRoot_excluded() {
        assertTrue(
            checker.buildAssignments(
                listOf(moduleData("mod", contentRoot = null)),
                emptyMap(),
            ).isEmpty()
        )
    }

    fun testBuildAssignments_preservesInsertionOrder() {
        val pathA = Paths.get("/a")
        val pathB = Paths.get("/b")
        val pathC = Paths.get("/c")
        val installed = { versions(elixir = elixirEntry("1.17", "/elixir", installed = true)) }
        val assignments = checker.buildAssignments(
            listOf(
                moduleData("alpha", contentRoot = pathA),
                moduleData("beta",  contentRoot = pathB),
                moduleData("gamma", contentRoot = pathC),
            ),
            mapOf(
                pathA to success(installed()),
                pathB to success(installed()),
                pathC to success(installed()),
            ),
        )
        assertEquals(listOf("alpha", "beta", "gamma"), assignments.keys.toList())
    }

    // -------------------------------------------------------------------------
    // detectMismatchIssues - Elixir, matched by install path
    // -------------------------------------------------------------------------

    fun testDetectMismatch_sameElixirVersionBuiltForAnotherOtp_isAMismatch() {
        val path = Paths.get("/project")
        val (issues, tables) = detect(
            moduleData(
                "myModule",
                elixirHome = "/mise/installs/elixir/1.20.5-otp-27",
                elixirVersion = "1.20.5-otp-27",
                contentRoot = path,
            ),
            results = mapOf(
                path to success(versions(elixir = elixirEntry("1.20.5-otp-28", "/mise/installs/elixir/1.20.5-otp-28")))
            ),
        )

        assertEquals(1, issues.size)
        assertEquals("myModule", issues[0].moduleName)
        assertFalse("Elixir mismatch is not a dangling reference", issues[0].isDangling)
        val row = tables["myModule"]!!.rows.single()
        assertEquals("Elixir", row.label)
        assertTrue("Row marked as mismatch", row.isMismatch)
        assertEquals("1.20.5-otp-27", row.configuredVersion)
        assertEquals("1.20.5-otp-28", row.toolManagerVersion)
    }

    fun testDetectMismatch_sameVersionStringAtAnotherPath_issueNamesBothPaths() {
        val path = Paths.get("/project")
        val (issues, _) = detect(
            moduleData(
                "mod",
                elixirHome = "/usr/local/elixir",
                elixirVersion = "1.20.5-otp-28",
                erlangHome = "/usr/local/erlang",
                erlangVersion = "28.0",
                contentRoot = path,
            ),
            results = mapOf(
                path to success(versions(
                    elixir = elixirEntry("1.20.5-otp-28", "/mise/installs/elixir/1.20.5-otp-28"),
                    erlang = erlangEntry("28.0"),
                ))
            ),
        )

        assertEquals(2, issues.size)
        for ((issue, paths) in issues.zip(listOf(
            listOf("/usr/local/elixir", "/mise/installs/elixir/1.20.5-otp-28"),
            listOf("/usr/local/erlang", "/mise/installs/erlang/28.0"),
        ))) {
            paths.forEach { assertTrue("'${issue.issue}' must name $it", issue.issue.contains(it)) }
        }
    }

    fun testDetectMismatch_versionNotInstalled_issueSaysSo() {
        val path = Paths.get("/project")
        val (issues, _) = detect(
            moduleData(
                "mod",
                elixirHome = "/usr/local/elixir",
                elixirVersion = "1.20.5",
                erlangHome = "/usr/local/erlang",
                erlangVersion = "28.0",
                contentRoot = path,
            ),
            results = mapOf(
                path to success(versions(
                    elixir = elixirEntry("1.21.0", "/mise/installs/elixir/1.21.0", installed = false),
                    erlang = ToolEntry("29.0", "/mise/installs/erlang/29.0", installed = false),
                ))
            ),
        )

        assertEquals(2, issues.size)
        issues.forEach { assertTrue("'${it.issue}' must say the version is not installed", it.issue.contains("not installed")) }
    }

    fun testDetectMismatch_versionNotInstalled_issueTellsTheUserToInstallItInTheContentRoot() {
        val path = Paths.get("/project")
        val (issues, _) = detect(
            moduleData(
                "mod",
                elixirHome = "/usr/local/elixir",
                elixirVersion = "1.20.5",
                contentRoot = path,
            ),
            results = mapOf(
                path to success(versions(
                    elixir = elixirEntry("1.21.0", "/mise/installs/elixir/1.21.0", installed = false),
                ))
            ),
        )

        val issue = issues.single().issue
        assertTrue("'$issue' must name the command that fixes it", issue.contains("mise install"))
        assertTrue("'$issue' must name the directory to run it in", issue.contains("/project"))
    }

    fun testDetectMismatch_versionNotInstalled_rowSaysSoSoTheTableCanShowIt() {
        val path = Paths.get("/project")
        val (_, tables) = detect(
            moduleData(
                "mod",
                elixirHome = "/usr/local/elixir",
                elixirVersion = "1.20.5",
                contentRoot = path,
            ),
            results = mapOf(
                path to success(versions(
                    elixir = elixirEntry("1.21.0", "/mise/installs/elixir/1.21.0", installed = false),
                ))
            ),
        )

        assertFalse(
            "a single module renders the table and never the issue text, so the row carries the state",
            tables["mod"]!!.rows.single().isInstalled,
        )
    }

    fun testDetectMismatch_installedVersionAtAnotherPath_rowIsStillInstalled() {
        val path = Paths.get("/project")
        val (_, tables) = detect(
            moduleData(
                "mod",
                elixirHome = "/usr/local/elixir",
                elixirVersion = "1.20.5",
                contentRoot = path,
            ),
            results = mapOf(
                path to success(versions(
                    elixir = elixirEntry("1.21.0", "/mise/installs/elixir/1.21.0", installed = true),
                ))
            ),
        )

        assertTrue(
            "a mismatch at a different path is not a missing installation",
            tables["mod"]!!.rows.single().isInstalled,
        )
    }

    fun testDetectMismatch_elixirHomeIsTheInstallPath_noIssueNoTable() {
        val path = Paths.get("/project")
        val installPath = "/mise/installs/elixir/1.17.3-otp-27"
        val (issues, tables) = detect(
            moduleData("mod", elixirHome = installPath, elixirVersion = "1.17.3-otp-27", contentRoot = path),
            results = mapOf(path to success(versions(elixir = elixirEntry("1.17.3-otp-27", installPath)))),
        )

        assertTrue("No issues when the SDK home is the install path", issues.isEmpty())
        assertTrue("No table when the SDK home is the install path", tables.isEmpty())
    }

    fun testDetectMismatch_pathsDifferingOnlyInSeparators_match() {
        val path = Paths.get("/project")
        val (issues, tables) = detect(
            moduleData("mod", elixirHome = "C:\\mise\\installs\\elixir\\1.17.3", contentRoot = path),
            results = mapOf(path to success(versions(elixir = elixirEntry("1.17.3", "C:/mise/installs/elixir/1.17.3")))),
        )

        assertTrue(issues.isEmpty())
        assertTrue(tables.isEmpty())
    }

    fun testDetectMismatch_canonicalInstallPathMatchingTheHome_noIssue() {
        val path = Paths.get("/project")
        val (issues, _) = detect(
            moduleData("mod", elixirHome = "/real/elixir/1.17.3-otp-27", contentRoot = path),
            results = mapOf(path to success(versions(elixir = elixirEntry("1.17.3-otp-27", "/link/elixir/1.17.3-otp-27")))),
            canonicalPathByPath = mapOf("/link/elixir/1.17.3-otp-27" to "/real/elixir/1.17.3-otp-27"),
        )

        assertTrue(issues.isEmpty())
    }

    fun testDetectMismatch_canonicalSdkHomeMatchingTheInstallPath_noIssue() {
        // An SDK whose home was picked through a symlink, such as mise's installs/elixir/1.18.
        val path = Paths.get("/project")
        val (issues, _) = detect(
            moduleData("mod", elixirHome = "/link/elixir/1.18", contentRoot = path),
            results = mapOf(path to success(versions(elixir = elixirEntry("1.18.4-otp-27", "/real/elixir/1.18.4-otp-27")))),
            canonicalPathByPath = mapOf("/link/elixir/1.18" to "/real/elixir/1.18.4-otp-27"),
        )

        assertTrue("issues: $issues", issues.isEmpty())
    }

    fun testDetectMismatch_noConfiguredElixirSdk_noIssue() {
        val path = Paths.get("/project")
        val (issues, tables) = detect(
            moduleData("mod", elixirHome = null, contentRoot = path),
            results = mapOf(path to success(versions(elixir = elixirEntry("1.17.3", "/mise/installs/elixir/1.17.3")))),
        )

        assertTrue("No issue when no SDK is configured and the pinned version is installed", issues.isEmpty())
        assertTrue("No table when no issue", tables.isEmpty())
    }

    fun testDetectMismatch_noConfiguredElixirSdkAndThePinnedVersionIsNotInstalled_saysToInstallIt() {
        val path = Paths.get("/project")
        val (issues, tables) = detect(
            moduleData("mod", elixirHome = null, contentRoot = path),
            results = mapOf(
                path to success(versions(
                    elixir = elixirEntry("1.21.0", "/mise/installs/elixir/1.21.0", installed = false),
                ))
            ),
        )

        val issue = issues.single().issue
        assertTrue("'$issue' must name the version that is not installed", issue.contains("1.21.0"))
        assertTrue("'$issue' must name the command that fixes it", issue.contains("mise install"))
        assertFalse("'$issue' must not describe an SDK that is not configured", issue.contains("null"))
        assertTrue("a row compares against a configured version, and there is none", tables.isEmpty())
    }

    // -------------------------------------------------------------------------
    // detectMismatchIssues - Erlang, matched by install path
    // -------------------------------------------------------------------------

    fun testDetectMismatch_pairedErlangHomeIsNotTheInstallPath_isAMismatch() {
        val path = Paths.get("/project")
        val elixirInstall = "/mise/installs/elixir/1.18.4-otp-28"
        val (issues, tables) = detect(
            moduleData(
                "myModule",
                elixirHome = elixirInstall,
                erlangHome = "/mise/installs/erlang/27.3.4",
                erlangVersion = "27.3.4",
                contentRoot = path,
            ),
            results = mapOf(
                path to success(versions(elixir = elixirEntry("1.18.4-otp-28", elixirInstall), erlang = erlangEntry("28.0")))
            ),
        )

        assertEquals(1, issues.size)
        assertFalse("OTP mismatch is not a dangling reference", issues[0].isDangling)
        val row = tables["myModule"]!!.rows.single { it.isMismatch }
        assertEquals("Erlang", row.label)
        assertEquals("27.3.4", row.configuredVersion)
        assertEquals("28.0", row.toolManagerVersion)
    }

    fun testDetectMismatch_erlangOfTheSameMajorAtAnotherPath_isAMismatch() {
        val path = Paths.get("/project")
        val (issues, _) = detect(
            moduleData("mod", erlangHome = "/mise/installs/erlang/28.0", erlangVersion = "28.0", contentRoot = path),
            results = mapOf(path to success(versions(erlang = erlangEntry("28.1")))),
        )

        assertEquals(1, issues.size)
    }

    fun testDetectMismatch_pairedErlangHomeIsTheInstallPath_noIssue() {
        val path = Paths.get("/project")
        val (issues, tables) = detect(
            moduleData("mod", erlangHome = "/mise/installs/erlang/27.3.4", contentRoot = path),
            results = mapOf(path to success(versions(erlang = erlangEntry("27.3.4")))),
        )

        assertTrue(issues.isEmpty())
        assertTrue(tables.isEmpty())
    }

    fun testDetectMismatch_noErlangInToolManager_noErlangRow() {
        val path = Paths.get("/project")
        val (_, tables) = detect(
            moduleData(
                "mod",
                elixirHome = "/mise/installs/elixir/1.20.5-otp-27",
                erlangHome = "/mise/installs/erlang/27.3.4",
                contentRoot = path,
            ),
            results = mapOf(
                path to success(versions(elixir = elixirEntry("1.20.5-otp-28", "/mise/installs/elixir/1.20.5-otp-28")))
            ),
        )

        assertEquals(listOf("Elixir"), tables["mod"]!!.rows.map { it.label })
    }

    fun testDetectMismatch_noPairedErlangSdk_noErlangRow() {
        val path = Paths.get("/project")
        val (issues, tables) = detect(
            moduleData("mod", erlangHome = null, contentRoot = path),
            results = mapOf(path to success(versions(erlang = erlangEntry("28.0")))),
        )

        assertTrue(issues.isEmpty())
        assertTrue(tables.isEmpty())
    }

    fun testDetectMismatch_noPairedErlangSdkAndThePinnedVersionIsNotInstalled_saysToInstallIt() {
        val path = Paths.get("/project")
        val (issues, tables) = detect(
            moduleData("mod", erlangHome = null, contentRoot = path),
            results = mapOf(
                path to success(versions(
                    erlang = ToolEntry("29.0", "/mise/installs/erlang/29.0", installed = false),
                ))
            ),
        )

        val issue = issues.single().issue
        assertTrue("'$issue' must name the version that is not installed", issue.contains("29.0"))
        assertTrue("'$issue' must name the command that fixes it", issue.contains("mise install"))
        assertFalse("'$issue' must not describe an SDK that is not configured", issue.contains("null"))
        assertTrue("a row compares against a configured version, and there is none", tables.isEmpty())
    }

    // -------------------------------------------------------------------------
    // detectMismatchIssues - combined and edge cases
    // -------------------------------------------------------------------------

    fun testDetectMismatch_bothElixirAndErlangMismatch_twoIssuesTwoRowTable() {
        val path = Paths.get("/project")
        val (issues, tables) = detect(
            moduleData(
                "myModule",
                elixirHome = "/mise/installs/elixir/1.17.3-otp-26",
                erlangHome = "/mise/installs/erlang/26.2.5",
                contentRoot = path,
            ),
            results = mapOf(
                path to success(versions(
                    elixir = elixirEntry("1.18.0-otp-27", "/mise/installs/elixir/1.18.0-otp-27"),
                    erlang = erlangEntry("27.3.4"),
                ))
            ),
        )

        assertEquals("Two issues for two mismatches", 2, issues.size)
        val rows = tables["myModule"]!!.rows
        assertEquals("Two rows in table (one per tool)", 2, rows.size)
        assertTrue("All rows are mismatches", rows.all { it.isMismatch })
    }

    fun testDetectMismatch_nullContentRoot_moduleSkipped() {
        val (issues, tables) = detect(
            moduleData("mod", elixirHome = "/mise/installs/elixir/1.17.3", contentRoot = null),
            results = emptyMap(),
        )

        assertTrue("Module skipped when contentRoot is null", issues.isEmpty())
        assertTrue(tables.isEmpty())
    }

    fun testDetectMismatch_errorResult_moduleSkipped() {
        val path = Paths.get("/project")
        val (issues, tables) = detect(
            moduleData("mod", elixirHome = "/mise/installs/elixir/1.17.3", contentRoot = path),
            results = mapOf(path to error("config not trusted")),
        )

        assertTrue("Module skipped when result is Error", issues.isEmpty())
        assertTrue(tables.isEmpty())
    }

    fun testDetectMismatch_nullResult_moduleSkipped() {
        val path = Paths.get("/project")
        val (issues, tables) = detect(
            moduleData("mod", elixirHome = "/mise/installs/elixir/1.17.3", contentRoot = path),
            results = mapOf(path to null),
        )

        assertTrue("Module skipped when result is null", issues.isEmpty())
        assertTrue(tables.isEmpty())
    }

    fun testDetectMismatch_tableNameMatchesToolManagerName() {
        val path = Paths.get("/project")
        val (_, tables) = detect(
            moduleData("mod", elixirHome = "/asdf/installs/elixir/1.17.3", contentRoot = path),
            results = mapOf(
                path to success(versions(toolManagerName = "asdf", elixir = elixirEntry("1.18.0", "/asdf/installs/elixir/1.18.0")))
            ),
        )

        assertEquals("asdf", tables["mod"]?.toolManagerName)
    }

    fun testDetectMismatch_multipleModules_independentlyEvaluated() {
        val pathA = Paths.get("/a")
        val pathB = Paths.get("/b")
        val (issues, tables) = detect(
            moduleData("modA", elixirHome = "/mise/installs/elixir/1.17.3", contentRoot = pathA),
            moduleData("modB", elixirHome = "/mise/installs/elixir/1.17.3", contentRoot = pathB),
            results = mapOf(
                pathA to success(versions(elixir = elixirEntry("1.18.0", "/mise/installs/elixir/1.18.0"))),
                pathB to success(versions(elixir = elixirEntry("1.17.3", "/mise/installs/elixir/1.17.3"))),
            ),
        )

        assertEquals("Only module A has a mismatch issue", 1, issues.size)
        assertEquals("modA", issues[0].moduleName)
        assertNotNull("Table for mismatching module A", tables["modA"])
        assertFalse("No table for matching module B", tables.containsKey("modB"))
    }
}

/** Minimal [ToolManagerVersions] backed by plain values (no platform needed). */
internal fun versions(
    toolManagerName: String = "mise",
    elixir: ToolEntry? = null,
    erlang: ToolEntry? = null,
): ToolManagerVersions = object : ToolManagerVersions {
    override val toolManagerName = toolManagerName
    override val elixir = elixir
    override val erlang = erlang
}
