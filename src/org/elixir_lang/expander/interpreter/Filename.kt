package org.elixir_lang.expander.interpreter

import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.system.OS
import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * Whether [code] is an ASCII character, which every native name encoding reads alike; a name holding one past it
 * declines, as that encoding isn't read.
 */
internal fun isAscii(code: Int): Boolean = code in 0 until 0x80

/** Whether each character of [text] is ASCII ([isAscii]). */
internal fun isAscii(text: String): Boolean = text.all { isAscii(it.code) }

/**
 * `filename`'s functions of a binary, and the walks some of them make of a list's characters, ported from OTP's
 * `filename.erl`, which reads `os:type/0` for the syntax a path has: [OS.Windows] is its `win32`, every other OS its
 * unix. A binary's names are bytes, as the binaries they answer are. Also `binary:split/3`, `match/2` and `matches/2`.
 */
internal object Filename {
    private const val SLASH = '/'.code.toByte()
    private const val BACKSLASH = '\\'.code.toByte()
    private const val COLON = ':'.code.toByte()
    private const val DOT = '.'.code.toByte()

    private val UNIX_SEPARATORS = listOf(byteArrayOf(SLASH))
    private val WINDOWS_SEPARATORS = listOf(byteArrayOf(SLASH), byteArrayOf(BACKSLASH))

    /** The names Windows takes as a device in any directory, a port's number a digit or a superscript 1, 2 or 3. */
    private val DEVICES = setOf("CON", "PRN", "AUX", "NUL") +
        ((0..9).map(Int::toString) + listOf("\u00B9", "\u00B2", "\u00B3")).flatMap { listOf("COM$it", "LPT$it") }

    /** `os:type/0`: the OS family and name, `null` for an OS no clause of any function here names. */
    fun osType(os: OS): Pair<String, String>? =
        when (os) {
            OS.Windows -> "win32" to "nt"
            OS.macOS -> "unix" to "darwin"
            OS.Linux -> "unix" to "linux"
            OS.FreeBSD -> "unix" to "freebsd"
            else -> null
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

    /** `filename:pathtype/1`'s answer. */
    enum class PathType { ABSOLUTE, RELATIVE, VOLUME_RELATIVE }

    /**
     * `filename:pathtype/1` of a binary: `unix_pathtype/1`, or `win32_pathtype/1`, where a drive or a root alone is
     * relative to a volume.
     */
    fun pathtype(name: ByteArray, os: OS): PathType =
        when {
            os != OS.Windows -> if (name.firstOrNull() == SLASH) PathType.ABSOLUTE else PathType.RELATIVE
            name.size >= 2 && isSlash(name[0]) && isSlash(name[1]) -> PathType.ABSOLUTE
            name.isNotEmpty() && isSlash(name[0]) -> PathType.VOLUME_RELATIVE
            name.size >= 3 && name[1] == COLON && isSlash(name[2]) -> PathType.ABSOLUTE
            name.size >= 2 && name[1] == COLON -> PathType.VOLUME_RELATIVE
            else -> PathType.RELATIVE
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
     * Where `filename:extension/1`'s walk of the characters [name] has its result begin, -1 for `[]`: at a `.` that
     * neither begins [name] nor follows a separator, while no separator comes after it.
     */
    fun extensionStart(name: IntArray, os: OS): Int {
        var start = -1

        for (at in name.indices) {
            val character = name[at]

            start = when {
                character == DOT.toInt() -> if (at == 0 || isSeparator(name[at - 1], os)) -1 else at
                isSeparator(character, os) -> -1
                else -> start
            }
        }

        return start
    }

    /**
     * `filename:rootname/1`'s walk of the characters [name] (`rootname/4`): the root so far, and the extension so far,
     * which a `.` begins and a separator ends, the root taking it back; both in order. A separator on Windows is kept
     * as `/`. The root is the answer.
     */
    fun rootname(name: IntArray, os: OS): Pair<IntArray, IntArray> {
        val root = mutableListOf<Int>()
        val extension = mutableListOf<Int>()

        for (character in name) {
            when {
                isSeparator(character, os) -> {
                    root += extension
                    root += SLASH.toInt()
                    extension.clear()
                }
                character == DOT.toInt() && extension.isEmpty() && root.lastOrNull() == SLASH.toInt() -> root += character
                character == DOT.toInt() -> {
                    root += extension
                    extension.clear()
                    extension += character
                }
                extension.isEmpty() -> root += character
                else -> extension += character
            }
        }

        return root.toIntArray() to extension.toIntArray()
    }

    /**
     * `filename:rootname/2`'s walk of the characters [name] (`rootname2/4`): [name] up to where the rest of it is
     * [extension], all of it where a separator comes before that or the rest never is.
     */
    fun rootname(name: IntArray, extension: IntArray, os: OS): IntArray {
        val at = name.size - extension.size

        if (at < 0 || !Arrays.equals(name, at, name.size, extension, 0, extension.size)) return name

        return if (at > 0 && isSeparator(name[at - 1], os)) name else name.copyOf(at)
    }

    /** `binary:split/3` of [subject] at [patterns], none of them empty: around each of [binaryMatches]' matches. */
    fun binarySplit(subject: ByteArray, patterns: List<ByteArray>, global: Boolean): List<ByteArray> {
        val parts = mutableListOf<ByteArray>()
        var from = 0

        for ((at, length) in binaryMatches(subject, patterns, global)) {
            parts.add(subject.copyOfRange(from, at))
            from = at + length
        }

        parts.add(subject.copyOfRange(from, subject.size))

        return parts
    }

    /**
     * `binary:matches/2` of [subject] at [patterns], none of them empty, each match as its position and length: the
     * leftmost, the longest of those starting there, and then, when [global], the next after it.
     */
    fun binaryMatches(subject: ByteArray, patterns: List<ByteArray>, global: Boolean): List<Pair<Int, Int>> {
        val found = mutableListOf<Pair<Int, Int>>()
        var at = 0

        while (at < subject.size) {
            val length = patterns.maxOfOrNull {
                ProgressManager.checkCanceled()

                if (matches(subject, at, it)) it.size else 0
            } ?: 0

            if (length == 0) {
                at++
                continue
            }

            found.add(at to length)
            at += length

            if (!global) break
        }

        return found
    }

    /**
     * `filename:join/2` of binaries: [name2] after [name1] where it is relative, else [name2] alone, joined as `join1b/4`
     * walks them: one slash between names, a `.` name dropped before a slash, and on Windows a backslash taken as a
     * slash, a leading pair of them kept and a drive letter lowercased. A trailing slash goes, but a root's.
     */
    fun join(name1: ByteArray, name2: ByteArray, os: OS): ByteArray {
        val windows = os == OS.Windows
        val joined = Joined()

        val names = when {
            pathtype(name2, os) != PathType.RELATIVE -> listOf(name2)
            name2.isEmpty() -> listOf(name1)
            else -> listOf(name1, name2)
        }

        for ((index, name) in names.withIndex()) {
            if (index > 0) {
                when {
                    windows && joined.endsWith(COLON) || joined.endsWith(SLASH) -> Unit
                    joined.endsWith(SLASH, DOT) -> joined.dropLast()
                    else -> joined.write(SLASH.toInt())
                }
            }

            var at = 0

            while (at < name.size) {
                val next = name.getOrNull(at + 1)

                when {
                    windows && joined.size() == 0 && isUpper(name[at]) && next == COLON -> {
                        joined.write(byteArrayOf(lower(name[at]), COLON))
                        at++
                    }
                    windows && joined.size() == 0 && isSlash(name[at]) && next != null && isSlash(next) &&
                        (name[at] == BACKSLASH || next == SLASH) -> {
                        joined.write(byteArrayOf(SLASH, SLASH))
                        at++
                    }
                    !isSeparator(name[at].toInt(), os) -> joined.write(name[at].toInt())
                    joined.endsWith(SLASH, DOT) -> joined.dropLast()
                    !joined.endsWith(SLASH) -> joined.write(SLASH.toInt())
                }

                at++
            }
        }

        val bytes = joined.toByteArray()
        val root = bytes.size == 1 || windows && (bytes.size == 2 && bytes[0] == SLASH || bytes.size == 3 && bytes[1] == COLON)

        return if (bytes.lastOrNull() == SLASH && !root) bytes.copyOf(bytes.size - 1) else bytes
    }

    /** [join] of text. */
    fun join(name1: String, name2: String, os: OS): String = String(join(name1.toByteArray(), name2.toByteArray(), os))

    /** [dirname] of text. */
    fun dirname(name: String, os: OS): String = String(dirname(name.toByteArray(), os))

    /** [split] of text. */
    fun split(name: String, os: OS): List<String> = split(name.toByteArray(), os).map(::String)

    /** [lowercaseDrive] of text, on Windows. */
    fun lowercaseDrive(name: String, os: OS?): String = if (os == OS.Windows) String(lowercaseDrive(name.toByteArray())) else name

    /** [pathtype] of text. */
    fun pathtype(name: String, os: OS): PathType = pathtype(name.toByteArray(), os)

    /** Where the OS resolves a path's `.` and `..` names ([resolved]). */
    sealed interface Resolved {
        /** At [path], which holds no `.` or `..`. */
        class At(val path: String) : Resolved

        /** Stopped at [prefix], which a `.` or `..` follows and which isn't a directory. */
        class NotADirectory(val prefix: String) : Resolved

        /** Not read: a part [isUnread], or on Windows the path names a namespace, `//./` or `//?/`. */
        data object Unread : Resolved
    }

    /**
     * The absolute [path] as the OS resolves it. Windows resolves the text (`GetFullPathNameW`), taking any two
     * separators in front as a share's root, which `split` keeps one of for a mixed pair: a `.` goes, and a `..` with
     * the name before it; a `.` or `?` after a leading `//`, which names a namespace, a device's or a long path's, is
     * [Resolved.Unread], as is any part [isUnread]. Elsewhere the kernel walks it: a `.` or `..` must follow a
     * directory, which [physical] gives the path of on disk, through any link, `null` where it isn't one, and a `..` goes
     * to that path's parent.
     */
    fun resolved(path: String, os: OS, physical: (String) -> String?): Resolved {
        val share = os == OS.Windows && path.length >= 2 && isSeparator(path[0].code, os) && isSeparator(path[1].code, os)
        val parts = split(if (share) "//" + path.substring(2) else path, os)
        if (share && parts.getOrNull(1).let { it == "." || it == "?" }) {
            return Resolved.Unread
        }
        var resolved = parts.first()

        for (part in parts.drop(1)) {
            ProgressManager.checkCanceled()

            resolved = when (part) {
                ".." -> parent(resolved, os, physical) ?: return Resolved.NotADirectory(resolved)
                "." -> if (os == OS.Windows || physical(resolved) != null) resolved else return Resolved.NotADirectory(resolved)
                else -> if (isUnread(part, os)) return Resolved.Unread else join(resolved, part, os)
            }
        }

        return Resolved.At(resolved)
    }

    /**
     * Where the OS takes a `..` after [path], which holds no `.` or `..`: on Windows the parent of its text, a share's
     * root (`//server/share`) staying where it is, elsewhere the parent of the directory's path on disk ([physical]),
     * `null` where it isn't a directory.
     */
    fun parent(path: String, os: OS, physical: (String) -> String?): String? =
        when {
            os != OS.Windows -> physical(path)?.let { dirname(it, os) }
            isShareRoot(path) -> path
            else -> dirname(path, os)
        }

    /**
     * Whether Windows ([os]) may read [part] as other than the name it is: one ending in a dot or a space, which it may
     * trim, one naming a device ([isDevice]), or one holding a `:`, which names a stream of a file, as `a.ex::$DATA` does.
     */
    private fun isUnread(part: String, os: OS): Boolean =
        os == OS.Windows && (part.endsWith('.') || part.endsWith(' ') || ':' in part || isDevice(part))

    /** Whether [part] names a device, as `NUL` or `COM1` do with or without an extension. */
    private fun isDevice(part: String): Boolean = part.substringBefore('.').trimEnd(' ').uppercase() in DEVICES

    /** Whether [path] is a share's root, `//server/share`, which a Windows `..` doesn't leave. */
    private fun isShareRoot(path: String): Boolean = split(path, OS.Windows).let { it.size == 3 && it[0] == "//" }

    /** A name being joined. */
    private class Joined : ByteArrayOutputStream() {
        fun endsWith(vararg bytes: Byte): Boolean = matches(buf, count - bytes.size, bytes)

        fun dropLast() {
            count--
        }
    }

    private fun separators(windows: Boolean) = if (windows) WINDOWS_SEPARATORS else UNIX_SEPARATORS

    private fun isSlash(byte: Byte) = byte == SLASH || byte == BACKSLASH

    /** Whether the character [character] separates names: `/`, and `\` on Windows. */
    fun isSeparator(character: Int, os: OS) =
        character == SLASH.toInt() || os == OS.Windows && character == BACKSLASH.toInt()

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
