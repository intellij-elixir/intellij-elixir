package org.elixir_lang.psi.stub.call;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.stubs.StubInputStream;
import com.intellij.psi.stubs.StubOutputStream;
import com.intellij.util.io.StringRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Deserialized {
    private static final byte[] BEGIN;
    private static final byte[] END;
    /* Increase from `0` to enable guards to check for mismatches between reads and writes, such as in
       https://github.com/intellij-elixir/intellij-elixir/issues/767 */
    private static final int GUARD_LENGTH = 0;
    private static final Logger LOGGER = Logger.getInstance(Deserialized.class);
    /* Set > than experimentally observed valid values.  >= 15 is needed to accommodate `geo`'s 15 Protocol `impl`s for
       `String.Chars` in https://github.com/intellij-elixir/intellij-elixir/issues/2698.
       >= 25 is needed to accommodate `OpenApiSpex`'s 25 Protocol `impl`s for `Extendable` in
        https://github.com/intellij-elixir/intellij-elixir/pull/3717#issuecomment-3627833524 */
    private static final int INFO_NAME_SET_SIZE = 30;
    private static final int WARN_NAME_SET_SIZE = 60;
    private static final int ERROR_NAME_SET_SIZE = 100;

    static {
        int i;

        BEGIN = new byte[GUARD_LENGTH];

        // Dead while GUARD_LENGTH is 0.
        //noinspection ConstantValue
        for (i = 0; i < BEGIN.length; i++) {
            BEGIN[i] = (byte) i;
        }

        END = new byte[BEGIN.length];

        //noinspection ConstantValue
        for (i = 0; i < END.length; i++) {
            END[i] = (byte) (END.length - i);
        }
    }

    @NotNull
    public final Set<StringRef> canonicalNameSet;
    public final boolean hasDoBlockOrKeyword;
    @NotNull
    public final StringRef name;
    public final int resolvedFinalArity;
    @Nullable
    public final StringRef resolvedFunctionName;
    @Nullable
    public final StringRef resolvedModuleName;
    @Nullable
    public final StringRef implementedProtocolName;

    public Deserialized(@Nullable StringRef resolvedModuleName,
                        @Nullable StringRef resolvedFunctionName,
                        int resolvedFinalArity,
                        boolean hasDoBlockOrKeyword,
                        @NotNull StringRef name,
                        @NotNull Set<StringRef> canonicalNameSet,
                        @Nullable StringRef implementedProtocolName) {
        this.resolvedModuleName = resolvedModuleName;
        this.resolvedFunctionName = resolvedFunctionName;
        this.resolvedFinalArity = resolvedFinalArity;
        this.hasDoBlockOrKeyword = hasDoBlockOrKeyword;
        this.name = name;
        this.canonicalNameSet = canonicalNameSet;
        this.implementedProtocolName = implementedProtocolName;
    }

    public <T extends Stubbic> Deserialized(@NotNull T stubbic) {
        this(
                StringRef.fromNullableString(stubbic.resolvedModuleName()),
                StringRef.fromNullableString(stubbic.resolvedFunctionName()),
                stubbic.resolvedFinalArity(),
                stubbic.hasDoBlockOrKeyword(),
                StringRef.fromNullableString(stubbic.getName()),
                stringRefSet(stubbic.canonicalNameSet()),
                StringRef.fromNullableString(stubbic.getImplementedProtocolName())
        );
    }

    public static void assertGuard(@NotNull StubInputStream stubInputStream, byte[] expectedGuard) {
        try {
            byte[] actualGuard = new byte[expectedGuard.length];
            //noinspection ResultOfMethodCallIgnored
            stubInputStream.read(actualGuard);

            assert Arrays.equals(actualGuard, expectedGuard) :
                    "Expected `" + hexString(expectedGuard) + "` tag in StubInputSteam, but got `" +
                            hexString(actualGuard) + "`";
        } catch (IOException e) {
            LOGGER.error(e);
        }
    }

    public static Deserialized deserialize(@NotNull StubInputStream stubInputStream) throws IOException {
        assertGuard(stubInputStream, BEGIN);

        StringRef resolvedModuleName = deserializeResolvedModuleName(stubInputStream);
        StringRef resolvedFunctionName = deserializeResolvedFunctionName(stubInputStream);
        int resolvedFinalArity = deserializeResolvedFinalArity(stubInputStream);
        boolean hasDoBlockOrKeyword = deserializeHasDoBlockOrKeyword(stubInputStream);
        StringRef name = deserializeName(stubInputStream);
        Set<StringRef> canonicalNameSet = deserializeCanonicalNameSet(stubInputStream);
        StringRef iplementedProtocolName = deserializeName(stubInputStream);

        assertGuard(stubInputStream, END);

        return new Deserialized(
                resolvedModuleName,
                resolvedFunctionName,
                resolvedFinalArity,
                hasDoBlockOrKeyword,
                name,
                canonicalNameSet,
                iplementedProtocolName
        );
    }

    private static Set<StringRef> deserializeCanonicalNameSet(@NotNull StubInputStream stubInputStream)
            throws IOException {
        return readGuarded(stubInputStream, Deserialized::readNameSet);
    }

    private static boolean deserializeHasDoBlockOrKeyword(@NotNull StubInputStream stubInputStream) throws IOException {
        return readGuarded(stubInputStream, StubInputStream::readBoolean);
    }

    private static StringRef deserializeName(@NotNull StubInputStream stubInputStream) throws IOException {
        return readGuardedName(stubInputStream);
    }

    private static int deserializeResolvedFinalArity(@NotNull StubInputStream stubInputStream) throws IOException {
        return readGuarded(stubInputStream, StubInputStream::readVarInt);
    }

    private static StringRef deserializeResolvedFunctionName(@NotNull StubInputStream stubInputStream)
            throws IOException {
        return readGuardedName(stubInputStream);
    }

    private static StringRef deserializeResolvedModuleName(@NotNull StubInputStream stubInputStream)
            throws IOException {
        return readGuardedName(stubInputStream);
    }

    @NotNull
    private static String hexString(byte[] bytes) {
        final StringBuilder builder = new StringBuilder();
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }

        return builder.toString();
    }

    public static <T> T readGuarded(@NotNull StubInputStream stubInputStream,
                                    @NotNull Reader<T> reader) throws IOException {
        assertGuard(stubInputStream, BEGIN);
        T read = reader.read(stubInputStream);
        assertGuard(stubInputStream, END);

        return read;
    }

    private static StringRef readGuardedName(@NotNull StubInputStream stubInputStream) throws IOException {
        return readGuarded(stubInputStream, StubInputStream::readName);
    }

    private static Set<StringRef> readNameSet(@NotNull StubInputStream dataStream) throws IOException {
        assertGuard(dataStream, BEGIN);

        assertGuard(dataStream, BEGIN);
        int nameSetSize = dataStream.readVarInt();
        assertGuard(dataStream, END);

        if (nameSetSize > ERROR_NAME_SET_SIZE) {
            int readAheadLength = BEGIN.length;
            StringBuilder stringBuilder = new StringBuilder("readNameSet nameSetSize (")
                    .append(nameSetSize)
                    .append(") is suspect (> ")
                    .append(ERROR_NAME_SET_SIZE)
                    .append(").");

            if (readAheadLength == 0) {
                stringBuilder.append("StubIndex may be corrupt.");
                LOGGER.error(stringBuilder.toString());
            } else {
                byte[] readAhead = new byte[readAheadLength];
                int bytesRead = dataStream.read(readAhead);
                stringBuilder
                        .append("Read ahead read ")
                        .append(bytesRead)
                        .append(" of ")
                        .append(readAheadLength)
                        .append(" unexpected bytes `")
                        .append(hexString(readAhead))
                        .append("`");
                LOGGER.error(stringBuilder.toString());
            }
        }
        Set<StringRef> nameSet = HashSet.newHashSet(nameSetSize);

        if (nameSetSize > INFO_NAME_SET_SIZE) {
            StringBuilder stringBuilder = new StringBuilder("readNameSet nameSet of size ")
                    .append(nameSetSize).append(":\n");

            for (int i = 0; i < nameSetSize; i++) {
                StringRef name = dataStream.readName();
                nameSet.add(name);

                String nameString;
                if (name != null) {
                    nameString = name.toString();
                } else {
                    nameString = "(null)";
                }

                stringBuilder.append(i + 1).append(". ").append(nameString).append('\n');
            }

            if (nameSetSize > ERROR_NAME_SET_SIZE) {
                LOGGER.error(stringBuilder.toString());
            } else if (nameSetSize > WARN_NAME_SET_SIZE) {
                LOGGER.warn(stringBuilder.toString());
            } else {
                LOGGER.info(stringBuilder.toString());
            }
        } else {
            for (int i = 0; i < nameSetSize; i++) {
                StringRef name = dataStream.readName();
                nameSet.add(name);
            }
        }

        assertGuard(dataStream, END);

        return nameSet;
    }

    public static <T extends Stubbic> void serialize(@NotNull StubOutputStream stubOutputStream, @NotNull T stubbic)
            throws IOException {
        new Deserialized(stubbic).serialize(stubOutputStream);
    }

    @NotNull
    private static Set<StringRef> stringRefSet(@NotNull Set<String> stringSet) {
        Set<StringRef> stringRefSet = new HashSet<>();

        for (String string : stringSet) {
            stringRefSet.add(StringRef.fromNullableString(string));
        }

        return stringRefSet;
    }

    private static void writeGuard(@NotNull StubOutputStream stubOutputStream, byte[] guard) throws IOException {
        stubOutputStream.write(guard);
    }

    public static void writeGuarded(@NotNull StubOutputStream stubOutputStream, @NotNull Writer writer)
            throws IOException {
        writeGuard(stubOutputStream, BEGIN);
        writer.write(stubOutputStream);
        writeGuard(stubOutputStream, END);
    }

    private static void writeGuardedName(@NotNull StubOutputStream stubOutputStream, @Nullable StringRef name)
            throws IOException {
        writeGuarded(
                stubOutputStream,
                guardedStubOutputStream -> guardedStubOutputStream.writeName(StringRef.toString(name))
        );
    }

    private void serialize(@NotNull StubOutputStream stubOutputStream) throws IOException {
        writeGuarded(
                stubOutputStream,
                guardedStubOutputStream -> {
                    serializeResolvedModuleName(guardedStubOutputStream);
                    serializeResolvedFunctionName(guardedStubOutputStream);
                    serializeResolvedFinalArity(guardedStubOutputStream);
                    serializeHasDoBlockOrKeyword(guardedStubOutputStream);
                    serializeName(guardedStubOutputStream);
                    serializeCanonicalNameSet(guardedStubOutputStream);
                    serializeImplementedProtocolName(guardedStubOutputStream);
                }
        );
    }

    private void serializeCanonicalNameSet(@NotNull StubOutputStream stubOutputStream) throws IOException {
        writeGuarded(
                stubOutputStream,
                guardedOutputStream -> writeNameSet(guardedOutputStream, canonicalNameSet)
        );
    }

    private void serializeHasDoBlockOrKeyword(@NotNull StubOutputStream stubOutputStream) throws IOException {
        writeGuarded(
                stubOutputStream,
                guardedStubOutputStream -> guardedStubOutputStream.writeBoolean(hasDoBlockOrKeyword)
        );
    }

    private void serializeName(@NotNull StubOutputStream stubOutputStream) throws IOException {
        writeGuardedName(stubOutputStream, name);
    }

    private void serializeResolvedFinalArity(@NotNull StubOutputStream stubOutputStream) throws IOException {
        writeGuarded(
                stubOutputStream,
                guardedStubOutputStream -> guardedStubOutputStream.writeVarInt(resolvedFinalArity)
        );
    }

    private void serializeResolvedFunctionName(@NotNull StubOutputStream stubOutputStream) throws IOException {
        writeGuardedName(stubOutputStream, resolvedFunctionName);
    }

    private void serializeResolvedModuleName(@NotNull StubOutputStream stubOutputStream) throws IOException {
        writeGuardedName(stubOutputStream, resolvedModuleName);
    }

    private void serializeImplementedProtocolName(@NotNull StubOutputStream stubOutputStream) throws IOException {
        writeGuardedName(stubOutputStream, implementedProtocolName);
    }

    private void writeNameSet(@NotNull StubOutputStream stubOutputStream, @NotNull Set<StringRef> nameSet) throws IOException {
        writeGuarded(
                stubOutputStream,
                guardedStubOutputStream -> {
                    writeGuarded(
                            guardedStubOutputStream,
                            sizeStubOutputStream -> sizeStubOutputStream.writeVarInt(nameSet.size())
                    );

                    for (StringRef name : nameSet) {
                        writeGuardedName(guardedStubOutputStream, name);
                    }
                }
        );
    }
}
