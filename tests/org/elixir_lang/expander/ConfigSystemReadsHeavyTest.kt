package org.elixir_lang.expander

/**
 * A read of the environment or the machine, which another machine or run may answer differently, is unknown, and the
 * keys beside it stay known. Nothing is taken from the machine running the IDE.
 */
class ConfigSystemReadsHeavyTest : ConfigHeavyTestCase() {
    fun testAnEnvVariable() = assertEquals("k=Configured k1=1", keyBeside("System.get_env(\"X\")"))

    fun testAnEnvVariableWithADefault() = assertEquals("k=Configured k1=1", keyBeside("System.get_env(\"X\", \"d\")"))

    fun testAFetchedEnvVariable() = assertConfigured("System.fetch_env(\"X\")")

    fun testTheProcess() = assertConfigured("System.get_pid()")

    fun testTheSchedulers() = assertConfigured("System.schedulers()")

    fun testTheSchedulersOnline() = assertConfigured("System.schedulers_online()")

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

    /** The config sets `k` alone to [value], which isn't known. */
    private fun assertConfigured(value: String) = assertEquals("k=Configured", keys("config :app, k: $value\n", "k"))
}
