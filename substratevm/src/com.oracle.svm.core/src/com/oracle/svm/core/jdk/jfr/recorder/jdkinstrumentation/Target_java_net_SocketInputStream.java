package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import jdk.jfr.events.SocketReadEvent;
import sun.net.ConnectionResetException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;

@TargetClass(className = "java.net.SocketInputStream", onlyWith = JfrAvailability.WithJfr.class)
final class Target_java_net_SocketInputStream {

    @Alias Target_java_net_AbstractPlainSocketImpl impl = null;

    @Alias boolean eof;

    @Alias int socketRead(FileDescriptor fd,
                   byte b[], int off, int len,
                   int timeout) throws ConnectionResetException {
        return 0;
    }

    @Substitute
    int read(byte[] b, int off, int length, int timeout) throws IOException {
        SocketReadEvent event = SocketReadEvent.EVENT.get();
        if (!event.isEnabled()) {
            return SocketInputStreamUtil.read0(this, b, off, length, timeout);
        }
        int bytesRead = 0;
        try {
            event.begin();
            bytesRead = SocketInputStreamUtil.read0(this, b, off, length, timeout);
        } finally {
            event.end();
            if (event.shouldCommit()) {
                String hostString = impl.address.toString();
                int delimiterIndex = hostString.lastIndexOf('/');

                event.host = hostString.substring(0, delimiterIndex);
                event.address = hostString.substring(delimiterIndex + 1);
                event.port = impl.port;
                if (bytesRead < 0) {
                    event.endOfStream = true;
                } else {
                    event.bytesRead = bytesRead;
                }
                event.timeout = timeout;

                event.commit();
                event.reset();
            }
        }
        return bytesRead;
    }

    @TargetClass(className = "java.net.AbstractPlainSocketImpl", onlyWith = JfrAvailability.WithJfr.class)
    static final class Target_java_net_AbstractPlainSocketImpl {

        @Alias int port;

        @Alias InetAddress address;

        @Alias
        FileDescriptor acquireFD() { return null; }

        @Alias
        void releaseFD() {}

        @Alias
        boolean isClosedOrPending() { return false; }

        @Alias
        boolean isConnectionReset() {  return false; }

        @Alias
        void setConnectionReset() {}
    }

    static final class SocketInputStreamUtil {

        static int read0(Target_java_net_SocketInputStream thiz, byte b[], int off, int length, int timeout) throws IOException {
            int n;

            // EOF already encountered
            if (thiz.eof) {
                return -1;
            }

            // connection reset
            if (thiz.impl.isConnectionReset()) {
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
            FileDescriptor fd = thiz.impl.acquireFD();
            try {
                n = thiz.socketRead(fd, b, off, length, timeout);
                if (n > 0) {
                    return n;
                }
            } catch (ConnectionResetException rstExc) {
                thiz.impl.setConnectionReset();
            } finally {
                thiz.impl.releaseFD();
            }

            /*
             * If we get here we are at EOF, the socket has been closed,
             * or the connection has been reset.
             */
            if (thiz.impl.isClosedOrPending()) {
                throw new SocketException("Socket closed");
            }
            if (thiz.impl.isConnectionReset()) {
                throw new SocketException("Connection reset");
            }
            thiz.eof = true;
            return -1;
        }
    }
}

