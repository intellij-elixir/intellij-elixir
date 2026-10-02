package org.elixir_lang

import com.ericsson.otp.erlang.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.elixir_lang.mail_box.BADRPC
import org.elixir_lang.mail_box.BadRPC
import org.elixir_lang.mail_box.WaitingMatcher
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val NIL = OtpErlangAtom("nil")

fun otpErlangTuple(elementList: List<OtpErlangObject>) = OtpErlangTuple(elementList.toTypedArray())
fun otpErlangTuple(vararg elements: OtpErlangObject) = OtpErlangTuple(elements)

/**
 * [OtpMbox] that supports selective receive.
 *
 * Messages are [OtpMbox.receive]d in a coroutine and stored until [selectiveReceive]
 */
class MailBox(private val otpNode: OtpNode, private val otpMbox: OtpMbox, private val scope: CoroutineScope) {
    init {
        scope.launch(Dispatchers.IO) {
            try {
                loop()
            } catch (exception: Exception) {
                while (!waitingMatchers.isEmpty()) {
                    waitingMatchers.poll()?.completeExceptionally(exception)
                }

                throw exception
            }
        }
    }

    fun genericServerCall(remote: Server, request: OtpErlangObject, timeout: Int): OtpErlangObject {
        val serverPid = processWhereIs(remote, timeout)
                ?: throw OtpErlangExit("Could not determine PID for $remote within $timeout")

        return genericServerCall(serverPid, request, timeout)
    }

    private fun genericServerCall(serverPid: OtpErlangPid, request: OtpErlangObject, timeout: Int): OtpErlangObject {
        otpMbox.link(serverPid)

        val response = genericServerUnmonitoredCall(serverPid, request, timeout)

        otpMbox.unlink(serverPid)

        return response
    }

    @Throws(BadRPC::class)
    private fun processWhereIs(remote: Server, timeout: Int): OtpErlangPid? {
        val response = rpcUnmonitoredCall(
                remote,
                OtpErlangAtom("Elixir.Process"),
                OtpErlangAtom("whereis"),
                OtpErlangList(arrayOf(OtpErlangAtom(remote.registeredNamed))),
                timeout
        )

        return when (response) {
            NIL -> null
            else -> response as OtpErlangPid
        }
    }

    @Throws(BadRPC::class)
    private fun rpcUnmonitoredCall(
            remote: Server,
            module: OtpErlangAtom,
            function: OtpErlangAtom,
            arguments: OtpErlangList,
            timeout: Int
    ): OtpErlangObject {
        val request = otpErlangTuple(
                OtpErlangAtom("call"),
                module,
                function,
                arguments,
                // group leader
                otpMbox.self()
        )

        val remoteRex = remote.copy(registeredNamed = "rex")
        val response = genericServerUnmonitoredCall(remoteRex, request, timeout)

        return if (response is OtpErlangTuple && response.arity() == 2 && response.elementAt(0) == BADRPC) {
            throw BadRPC(response.elementAt(1))
        } else {
            response
        }
    }

    private val GEN_CALL = OtpErlangAtom("\$gen_call")

    private fun genericServerUnmonitoredCall(remote: Server, request: OtpErlangObject, timeout: Int): OtpErlangObject =
            genericUnmonitoredCall(remote, GEN_CALL, request, timeout)

    private fun genericServerUnmonitoredCall(serverPid: OtpErlangPid, request: OtpErlangObject, timeout: Int): OtpErlangObject =
            genericUnmonitoredCall(serverPid, GEN_CALL, request, timeout)

    /**
     * Sends a generic message containing `request` with the given `label` to `remoteName` process on
     * `remoteNode` from `localMbox` on `localNode`.  Unlike, `gen:call`, a monitor is not setup prior
     * to the call.
     *
     * @link https://github.com/erlang/otp/blob/OTP_R16B03-1/lib/stdlib/src/gen.erl#L209
     */
    private fun genericUnmonitoredCall(remote: Server, label: OtpErlangAtom, request: OtpErlangObject, timeout: Int): OtpErlangObject =
            genericUnmonitoredCall(label, request, timeout) { message -> otpMbox.send(remote.registeredNamed, remote.nodeName, message) }

    private fun genericUnmonitoredCall(serverPid: OtpErlangPid, label: OtpErlangAtom, request: OtpErlangObject, timeout: Int): OtpErlangObject =
            genericUnmonitoredCall(label, request, timeout) { message -> otpMbox.send(serverPid, message) }

    private fun genericUnmonitoredCall(
            label: OtpErlangAtom,
            request: OtpErlangObject,
            timeout: Int,
            send: (message: OtpErlangObject) -> Unit
    ): OtpErlangObject {
        val ref = otpNode.createRef()
        val message = otpErlangTuple(label, returnAddress(otpMbox, ref), request)

        send(message)

        val received = receive(timeout) { receivedMessage ->
            if (receivedMessage is OtpErlangTuple &&
                    receivedMessage.arity() == 2 &&
                    receivedMessage.elementAt(0) == ref) {
                receivedMessage.elementAt(1)
            } else {
                null
            }
        }

        return received as OtpErlangObject
    }

    /**
     * Attempts to match already received messages using [matcher].
     *
     * Messages that match are removed from the mailbox and cannot match again.
     *
     * @return output of [matcher] if it found a match; otherwise, `null`.
     */
    fun <T> match(matcher: (message: OtpErlangObject) -> T): T? =
        synchronized(receivedMessages) {
            var matchedIndex: Int? = null
            var matched: T? = null

            for ((index, receivedMessage) in receivedMessages.withIndex()) {
                matched = matcher(receivedMessage)

                if (matched != null) {
                    matchedIndex = index
                    break
                }
            }

            if (matchedIndex != null) {
                receivedMessages.removeAt(matchedIndex)
            }

            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (receivedMessages as java.lang.Object).notifyAll()

            matched
        }

    /**
     * Waits to receive message matching [matcher] until [timeout]
     */
    @Throws(OtpErlangExit::class)
    fun receive(timeout: Int? = null, matcher: (message: OtpErlangObject) -> Any?): Any =
            match(matcher) ?:
            wait(timeout, matcher)

    fun waitFor(remote: Server) {
        while (true) {
            try {
                val pid = processWhereIs(remote, 100)

                if (pid != null) {
                    otpMbox.link(pid)
                    break
                }
            } catch (otpErlangExit: OtpErlangExit) {
                if (otpErlangExit.reason() != TIMEOUT)  {
                    throw otpErlangExit
                }
            }
        }
    }

    private fun wait(timeout: Int? = null, matcher: (message: OtpErlangObject) -> Any?): Any {
        val waitingMatcher = org.elixir_lang.mail_box.WaitingMatcher(matcher)

        waitingMatchers.add(waitingMatcher)

        return if (timeout != null) {
            try {
                waitingMatcher.get(timeout.toLong(), TimeUnit.MILLISECONDS)!!
            } catch (timeoutException: TimeoutException) {
                throw OtpErlangExit(TIMEOUT)
            }
        } else {
            try {
                waitingMatcher.get()
            } catch (executionException: ExecutionException) {
                throw executionException.cause!!
            }
        }
    }

    private val waitingMatchers = ConcurrentLinkedQueue<WaitingMatcher>()

    private suspend fun loop() {
        while (true) {
            val received = otpMbox.receive()

            if (received == CLOSE) {
                val exit = OtpErlangExit("normal")

                while (!waitingMatchers.isEmpty()) {
                    waitingMatchers.poll()?.completeExceptionally(exit)
                }

                otpMbox.close()
                otpNode.close()
                return
            }

            currentCoroutineContext().ensureActive()

            synchronized(receivedMessages) {
                receivedMessages.add(received)

                checkWaitingMatchers()
            }
        }
    }

    private fun checkWaitingMatchers() {
        val iterator = waitingMatchers.iterator()

        while (iterator.hasNext()) {
            val waitingMatcher = iterator.next()
            val matcher = waitingMatcher.matcher
            /* could call on only new message, but there is a potential race condition between when [match] failed and
               [wait] was called, so re-run on all messages to be safe. */
            val matched = match(matcher)

            if (matched != null) {
                iterator.remove()
                waitingMatcher.complete(matched)
                break
            }
        }
    }

    private val receivedMessages: MutableList<OtpErlangObject> = mutableListOf()

    private fun returnAddress(otpMbox: OtpMbox, otpErlangRef: OtpErlangRef): OtpErlangTuple = OtpErlangTuple(arrayOf(
            otpMbox.self(),
            otpErlangRef
    ))

    /**
     * Closes the mailbox and its underlying [OtpMbox]
     */
    fun close() {
        otpMbox.send(otpMbox.self(), CLOSE)
    }
}

private val CLOSE = OtpErlangAtom("CLOSE")
private val TIMEOUT = OtpErlangAtom("timeout")
