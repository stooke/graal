package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import jdk.jfr.events.SocketReadEvent;
import sun.net.ConnectionResetException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;

//@TargetClass(className = "java.io.SocketInputStream", onlyWith = JfrAvailability.WithJfr.class)
final class Target_java_net_SocketInputStream {

    @Alias
    private AbstractPlainSocketImpl impl = null;

    @Alias
    private boolean eof;

    @Alias
    private int socketRead(FileDescriptor fd,
                           byte b[], int off, int len,
                           int timeout) throws ConnectionResetException { return 0; }

    int read0(byte b[], int off, int length, int timeout) throws IOException {
        int n;

        // EOF already encountered
        if (eof) {
            return -1;
        }

        // connection reset
        if (impl.isConnectionReset()) {
            throw new SocketException("Connection reset");
        }

        // bounds check
        if (length <= 0 || off < 0 || length > b.length - off) {
            if (length == 0) {
                return 0;
            }
            throw new ArrayIndexOutOfBoundsException("length == " + length
                    + " off == " + off + " buffer length == " + b.length);
        }

        // acquire file descriptor and do the read
        FileDescriptor fd = impl.acquireFD();
        try {
            n = socketRead(fd, b, off, length, timeout);
            if (n > 0) {
                return n;
            }
        } catch (ConnectionResetException rstExc) {
            impl.setConnectionReset();
        } finally {
            impl.releaseFD();
        }

        /*
         * If we get here we are at EOF, the socket has been closed,
         * or the connection has been reset.
         */
        if (impl.isClosedOrPending()) {
            throw new SocketException("Socket closed");
        }
        if (impl.isConnectionReset()) {
            throw new SocketException("Connection reset");
        }
        eof = true;
        return -1;
    }

    @Substitute
    int read(byte[] b, int off, int length, int timeout) throws IOException {
        SocketReadEvent event = SocketReadEvent.EVENT.get();
        if (!event.isEnabled()) {
            return read0(b, off, length, timeout);
        }
        int bytesRead = 0;
        try {
            event.begin();
            bytesRead = read0(b, off, length, timeout);
        } finally {
            event.end();
            if (event.shouldCommit()) {
                String hostString  = impl.address.toString();
                int delimiterIndex = hostString.lastIndexOf('/');

                event.host      = hostString.substring(0, delimiterIndex);
                event.address   = hostString.substring(delimiterIndex + 1);
                event.port      = impl.port;
                if (bytesRead < 0) {
                    event.endOfStream = true;
                } else {
                    event.bytesRead = bytesRead;
                }
                event.timeout   = timeout;

                event.commit();
                event.reset();
            }
        }
        return bytesRead;
    }

    static class AbstractPlainSocketImpl {
        InetAddress address;
        int port;
        boolean isConnectionReset() {return false;}
        void setConnectionReset() {}
        boolean isClosedOrPending() { return false; }
        void releaseFD() {}
        FileDescriptor acquireFD() { return null; }
    }
}
