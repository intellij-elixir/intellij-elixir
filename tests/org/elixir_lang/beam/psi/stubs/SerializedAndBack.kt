package org.elixir_lang.beam.psi.stubs

import com.intellij.psi.stubs.SerializationManagerEx
import com.intellij.psi.stubs.Stub
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** [stub]'s tree as the index reads it back after writing it. */
@Suppress("UnstableApiUsage")
fun serializedAndBack(stub: Stub): Stub {
    val output = ByteArrayOutputStream()
    SerializationManagerEx.getInstanceEx().serialize(stub, output)

    return SerializationManagerEx.getInstanceEx().deserialize(ByteArrayInputStream(output.toByteArray()))
}
