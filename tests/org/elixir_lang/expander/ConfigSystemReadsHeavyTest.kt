package org.elixir_lang.expander

/**
 * A read of the environment or the machine, which another machine or run may answer differently, is unknown, and the
 * keys beside it stay known. Nothing is taken from the machine running the IDE.
 */
class ConfigSystemReadsHeavyTest : ConfigHeavyTestCase() {
    fun testAnEnvVariable() = assertConfiguredBeside("System.get_env(\"X\")")

    fun testAnEnvVariableWithADefault() = assertConfiguredBeside("System.get_env(\"X\", \"d\")")

    fun testAFetchedEnvVariable() = assertConfigured("System.fetch_env(\"X\")")

    fun testTheWholeEnv() = assertConfigured("System.get_env() |> map_size()")

    fun testTheProcess() = assertConfigured("System.get_pid()")

    fun testTheSchedulers() = assertConfigured("System.schedulers()")

    fun testTheSchedulersOnline() = assertConfigured("System.schedulers_online()")

    fun testArithmeticOnTheSchedulersOnline() = assertConfiguredBeside("System.schedulers_online() * 2")

    fun testTheOtpRelease() = assertConfigured("System.otp_release()")

    fun testTheUserHome() = assertConfigured("System.user_home()")

    fun testTheUserHomeThatMayRaise() = assertConfigured("System.user_home!()")

    fun testTheTemporaryDirectory() = assertConfigured("System.tmp_dir()")

    fun testTheTemporaryDirectoryThatMayRaise() = assertConfigured("System.tmp_dir!()")

    fun testTheOsTime() = assertConfigured("System.os_time()")

    fun testTheSystemTime() = assertConfigured("System.system_time()")

    fun testTheSystemTimeInAUnit() = assertConfigured("System.system_time(:second)")

    fun testTheMonotonicTime() = assertConfigured("System.monotonic_time()")

    fun testAUniqueInteger() = assertConfigured("System.unique_integer()")

    fun testARandomNumber() = assertConfigured(":rand.uniform(10)")

    fun testRandomBytes() = assertConfigured(":crypto.strong_rand_bytes(8)")

    fun testTheNode() = assertConfigured("Node.self()")

    /** Two reads of one variable are two reads, which another process may answer apart. */
    fun testTwoReadsOfAVariableCompared() = assertConfigured("System.get_env(\"X\") == System.get_env(\"X\")")

    fun testTwoReadsOfAVariableComparedApart() = assertConfigured("System.get_env(\"X\") != System.get_env(\"X\")")
}
