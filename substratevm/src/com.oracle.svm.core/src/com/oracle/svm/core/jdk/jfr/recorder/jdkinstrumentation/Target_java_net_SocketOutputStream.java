package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import jdk.jfr.events.SocketWriteEvent;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;

@TargetClass(className = "java.net.SocketOutputStream", onlyWith = JfrAvailability.WithJfr.class)
final class Target_java_net_SocketOutputStream {

    @Alias
    private Target_java_net_AbstractPlainSocketImpl impl = null;

    @Substitute
    private void socketWrite(byte[] b, int off, int len) throws IOException {
        SocketWriteEvent event = SocketWriteEvent.EVENT.get();
        if (!event.isEnabled()) {
            socketWrite(b, off, len);
            // body of orignal method
            if (len <= 0 || off < 0 || len > b.length - off) {
                if (len == 0) {
                    return;
                }
                throw new ArrayIndexOutOfBoundsException("len == " + len
                        + " off == " + off + " buffer length == " + b.length);
            }
            FileDescriptor fd = impl.acquireFD();
            try {
                socketWrite0(fd, b, off, len);
            } catch (SocketException se) {
                if (impl.isClosedOrPending()) {
                    throw new SocketException("Socket closed");
                } else {
                    throw se;
                }
            } finally {
                impl.releaseFD();
            }
            // end of original method
            return;
        }
        int bytesWritten = 0;
        try {
            event.begin();
            // socketWrite(b, off, len);
            if (len <= 0 || off < 0 || len > b.length - off) {
                if (len == 0) {
                    return;
                }
                throw new ArrayIndexOutOfBoundsException("len == " + len
                        + " off == " + off + " buffer length == " + b.length);
            }
            FileDescriptor fd = impl.acquireFD();
            try {
                socketWrite0(fd, b, off, len);
            } catch (SocketException se) {
                if (impl.isClosedOrPending()) {
                    throw new SocketException("Socket closed");
                } else {
                    throw se;
                }
            } finally {
                impl.releaseFD();
            }
            //
            bytesWritten = len;
        } finally {
            event.end() ;
            if (event.shouldCommit()) {
                String hostString  = impl.address.toString();
                int delimiterIndex = hostString.lastIndexOf('/');

                event.host         = hostString.substring(0, delimiterIndex);
                event.address      = hostString.substring(delimiterIndex + 1);
                event.port         = impl.port;
                event.bytesWritten = bytesWritten < 0 ? 0 : bytesWritten;

                event.commit();
                event.reset();
            }
        }
    }

    @Alias
    private native void socketWrite0(FileDescriptor fd, byte[] b, int off, int len) throws SocketException;

    @TargetClass(className = "java.net.AbstractPlainSocketImpl")
    static final class Target_java_net_AbstractPlainSocketImpl {

        @Alias
        int port;

        @Alias
        InetAddress address;

        @Alias
        FileDescriptor acquireFD() { return null; }

        @Alias
        void releaseFD() {}

        @Alias
        boolean isClosedOrPending() { return false; }
    }
}
