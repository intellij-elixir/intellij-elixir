package org.elixir_lang

/** The x64 default thread stack, fixed so what overflows on it doesn't move with the platform or `-Xss`. */
internal const val FIXED_STACK = 1L shl 20

/** A fixed small stack, so an overflow depends on neither the platform's default thread stack, `-Xss`, nor what the JIT inlined. */
internal const val SMALL_STACK = 512L * 1024

/**
 * What [block] gave or threw on a new thread of [stackSize] bytes, `0` being the platform's default. Where [boundMs] is
 * given and the thread runs past it, [cancel] runs and the thread is waited for, and the answer is `null`.
 */
internal fun <T> onAStack(stackSize: Long, boundMs: Long = 0, cancel: () -> Unit = {}, block: () -> T): Result<T>? {
    var result: Result<T>? = null
    val thread = Thread(null, { result = runCatching(block) }, "stack of $stackSize", stackSize)

    thread.start()
    thread.join(boundMs)

    if (thread.isAlive) {
        cancel()
        thread.join()

        return null
    }

    return result
}

/** What [block] gave on a thread of [SMALL_STACK] bytes, or what it threw, rethrown. */
internal fun <T> onSmallStack(block: () -> T): T = onAStack(SMALL_STACK, block = block)!!.getOrThrow()
