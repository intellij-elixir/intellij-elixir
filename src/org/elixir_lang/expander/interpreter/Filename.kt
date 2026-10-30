package org.elixir_lang.expander.interpreter

import com.intellij.util.system.OS
import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * `filename`'s functions of a binary, and `binary:split/3`, ported from OTP's `filename.erl`, which reads `os:type/0`
 * for the syntax a path has: [OS.Windows] is its `win32`, every other OS its unix. Names are bytes, as the binaries they
 * answer are.
 */
internal object Filename {
    private const val SLASH = '/'.code.toByte()
    private const val BACKSLASH = '\\'.code.toByte()
    private const val COLON = ':'.code.toByte()

    private val UNIX_SEPARATORS = listOf(byteArrayOf(SLASH))
    private val WINDOWS_SEPARATORS = listOf(byteArrayOf(SLASH), byteArrayOf(BACKSLASH))

    /** `os:type/0`: the OS family and name, `null` for an OS no clause of any function here names. */
    fun osType(os: OS): Pair<String, String>? =
        when (os) {
            OS.Windows -> "win32" to "nt"
            OS.macOS -> "unix" to "darwin"
            OS.Linux -> "unix" to "linux"
            OS.FreeBSD -> "unix" to "freebsd"
            OS.Other -> null
        }

    /** `filename:split/1` of a binary: `unix_splitb/1`, or `win32_splitb/1` with its lowercased drive letter. */
    fun split(name: ByteArray, os: OS): List<ByteArray> {
        if (os != OS.Windows) {
            val parts = binarySplit(name, UNIX_SEPARATORS, global = true)
            val rooted = if (parts[0].isEmpty() && parts.size > 1) listOf(byteArrayOf(SLASH)) + parts.drop(1) else parts

            return rooted.filter { it.isNotEmpty() }
        }

        fun names(from: Int) = binarySplit(name.copyOfRange(from, name.size), WINDOWS_SEPARATORS, global = true).filter { it.isNotEmpty() }

        return when {
            hasDrive(name) && name.size > 2 && isSlash(name[2]) -> listOf(driveRoot(name, withSlash = true)) + names(3)
            hasDrive(name) -> listOf(driveRoot(name, withSlash = false)) + names(2)
            name.size >= 2 && isSlash(name[0]) && name[0] == name[1] -> listOf("//".toByteArray()) + names(2)
            name.isNotEmpty() && isSlash(name[0]) -> listOf(byteArrayOf(SLASH)) + names(1)
            else -> names(0)
        }
    }

    /** [name] with the drive letter `win32_splitb/1` lowercases, where it has one. */
    fun lowercaseDrive(name: ByteArray): ByteArray =
        if (hasDrive(name) && isUpper(name[0])) name.copyOf().also { it[0] = lower(it[0]) } else name

    /** `filename:dirname/1` of a binary. */
    fun dirname(name: ByteArray, os: OS): ByteArray {
        val windows = os == OS.Windows
        val drive = if (windows && hasDrive(name)) name.copyOf(2) else ByteArray(0)
        val parts = binarySplit(name.copyOfRange(drive.size, name.size), separators(windows), global = true)
        // The last name goes, and the empty parts before it that a trailing slash leaves, all but the first.
        val before = parts.dropLast(1).reversed()
        val kept = before.dropWhile { it.isEmpty() }.ifEmpty { before.take(1) }.reversed()
        val start = if (kept.isEmpty() && drive.isEmpty()) ".".toByteArray() else drive

        return dirjoin(kept, start)
    }

    /** `filename:basename/1` of a binary: its last name, which a drive letter on Windows is not. */
    fun basename(name: ByteArray, os: OS): ByteArray {
        val windows = os == OS.Windows
        val rest = if (windows && hasDrive(name)) name.copyOfRange(2, name.size) else name

        return binarySplit(rest, separators(windows), global = true).lastOrNull { it.isNotEmpty() } ?: ByteArray(0)
    }

    /**
     * `filename:basename/2` of binaries. It measures from the end of the whole [name], not of its last name, so a
     * trailing slash is cut from `basename("/a/b/", "")` as `"/"`.
     */
    fun basename(name: ByteArray, extension: ByteArray, os: OS): ByteArray {
        val last = basename(name, os)
        val length = last.size - extension.size

        if (length < 0 || !matches(name, name.size - extension.size, extension)) return last

        val start = name.size - last.size

        return name.copyOfRange(start, start + length)
    }

    /**
     * `binary:split/3` of [subject] at [patterns], none of them empty: at its leftmost match, the longest of those
     * starting there, and then, when [global], again after it.
     */
    fun binarySplit(subject: ByteArray, patterns: List<ByteArray>, global: Boolean): List<ByteArray> {
        val parts = mutableListOf<ByteArray>()
        var from = 0
        var at = 0

        while (at < subject.size) {
            val length = patterns.filter { matches(subject, at, it) }.maxOfOrNull { it.size }

            if (length == null) {
                at++
                continue
            }

            parts.add(subject.copyOfRange(from, at))
            at += length
            from = at

            if (!global) break
        }

        parts.add(subject.copyOfRange(from, subject.size))

        return parts
    }

    private fun separators(windows: Boolean) = if (windows) WINDOWS_SEPARATORS else UNIX_SEPARATORS

    private fun isSlash(byte: Byte) = byte == SLASH || byte == BACKSLASH

    private fun isUpper(byte: Byte) = byte in 'A'.code.toByte()..'Z'.code.toByte()

    private fun hasDrive(name: ByteArray) =
        name.size >= 2 && (isUpper(name[0]) || name[0] in 'a'.code.toByte()..'z'.code.toByte()) && name[1] == COLON

    private fun lower(byte: Byte) = if (isUpper(byte)) (byte + ('a' - 'A')).toByte() else byte

    /** `win32_splitb/1`'s drive: its letter lowercased, then `:` and, [withSlash], `/`. */
    private fun driveRoot(name: ByteArray, withSlash: Boolean): ByteArray {
        val letter = lower(name[0])

        return if (withSlash) byteArrayOf(letter, COLON, SLASH) else byteArrayOf(letter, COLON)
    }

    /** `dirjoin/3`: [parts] after [start], each followed by a slash but the last, an empty first one only a slash. */
    private fun dirjoin(parts: List<ByteArray>, start: ByteArray): ByteArray {
        val joined = ByteArrayOutputStream()
        var next = 0

        joined.write(start)

        while (true) {
            if (next < parts.size && parts[next].isEmpty()) {
                joined.write(SLASH.toInt())
                next++
            }

            when (parts.size - next) {
                0 -> return joined.toByteArray()
                1 -> return joined.apply { write(parts[next]) }.toByteArray()
                else -> {
                    joined.write(parts[next])
                    joined.write(SLASH.toInt())
                    next++
                }
            }
        }
    }

    private fun matches(bytes: ByteArray, at: Int, pattern: ByteArray) =
        at >= 0 && at + pattern.size <= bytes.size && Arrays.equals(bytes, at, at + pattern.size, pattern, 0, pattern.size)
}
