package org.elixir_lang.parser_definition;

import com.intellij.util.ThrowableRunnable;
import org.elixir_lang.junit.SharedFixture;
import org.elixir_lang.junit.SharedFixtureHost;
import org.elixir_lang.junit.logs.UnexpectedLogs;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A generated suite whose cases share one {@link SharedFixture}.
 *
 * <p>From IntelliJ 2026.2 {@code MockApplication.setUp}, which every {@code ParsingTestCase.setUp} calls, sleeps
 * 50 ms, while parsing one snippet takes about 1 ms.
 */
@SuppressWarnings("JUnitMalformedDeclaration") // T is the self type of a generated suite, built only by suite().
abstract class SharedFixtureParsingTestCase<T extends SharedFixtureParsingTestCase<T>> extends ParsingTestCase
        implements SharedFixtureHost<T> {
    private final @Nullable SharedFixture<T> fixture;

    /** The host, which is set up once and checks every case. */
    protected SharedFixtureParsingTestCase() {
        fixture = null;
        setName("shared fixture");
    }

    /** A case named {@code name}, checked on {@code fixture}'s host. */
    protected SharedFixtureParsingTestCase(@NotNull SharedFixture<T> fixture, @NotNull String name) {
        this.fixture = fixture;
        setName(name);
        fixture.add(self());
    }

    @Override
    public final void runShared(@NotNull ThrowableRunnable<Throwable> serve) throws Throwable {
        runBare(serve);
    }

    @Override
    protected final void runBare(@NotNull ThrowableRunnable<Throwable> testRunnable) throws Throwable {
        if (fixture == null) {
            super.runBare(testRunnable);
        } else {
            // Checked on the shared host; logs during the check are this case's.
            UnexpectedLogs.failOnUnexpectedLogs(() -> fixture.check(self()));
        }
    }

    @SuppressWarnings("unchecked")
    private T self() {
        return (T) this;
    }
}
