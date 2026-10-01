package org.elixir_lang;

import com.ericsson.otp.erlang.*;
import org.jetbrains.annotations.Nullable;

/**
 * Emulates the Process module in Elixir
 */
public class Process {
    private static final OtpErlangAtom NIL = new OtpErlangAtom("nil");

    @Nullable
    public static OtpErlangPid whereis(OtpMbox localMbox, OtpNode localNode, String remoteName, String remoteNode, int timeout) throws OtpErlangExit, OtpErlangDecodeException {
        OtpErlangObject response = RPC.INSTANCE.unmonitoredCall(
                remoteNode,
                localMbox,
                localNode,
                timeout,
                new OtpErlangAtom("Elixir.Process"),
                new OtpErlangAtom("whereis"),
                new OtpErlangAtom(remoteName)
        );

        OtpErlangPid pid;

        if (response instanceof OtpErlangAtom) {
            if (response.equals(NIL)) {
                pid = null;
            } else {
                throw new UnsupportedOperationException("Expected atoms to be nil, but got" + response);
            }
        } else {
            pid = (OtpErlangPid) response;
        }

        return pid;
    }
}
