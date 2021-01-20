package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import jdk.jfr.events.SocketReadEvent;
import jdk.jfr.events.SocketWriteEvent;
import sun.nio.ch.IOStatus;
import sun.nio.ch.IOUtil;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
/***
//@TargetClass(className = "sun.nio.ch.SocketChannelImpl", onlyWith = JfrAvailability.WithJfr.class)
final class Target_sun_nio_ch_SocketChannelImpl {

    @Alias
    private final FileDescriptor fd = null;

    @Alias
    private static Target_sun_nio_ch_NativeDispatcher nd = null;

    @Alias
    private InetSocketAddress remoteAddress;

    @Alias
    private ReentrantLock readLock;

    @Alias
    private ReentrantLock writeLock;

    @Alias
    private volatile boolean isInputClosed;

    @Alias
    private volatile boolean isOutputClosed;

    @Alias
    private volatile boolean nonBlocking;

    // JFR-TODO: isBlocking() is in a parent class; how to deal with this?
    @Alias
    public final boolean isBlocking() { return true; }

    @Alias
    public final boolean isOpen() { return true; }

    @Alias
    private void beginRead(boolean blocking) throws ClosedChannelException {}

    @Alias
    private void endRead(boolean blocking, boolean completed) throws AsynchronousCloseException {}

    @Alias
    private void beginWrite(boolean blocking) throws ClosedChannelException {}

    @Alias
    private void endWrite(boolean blocking, boolean completed) throws AsynchronousCloseException {}

    @Substitute
    public int read(ByteBuffer dst) throws IOException {
        SocketReadEvent event = SocketReadEvent.EVENT.get();
        if (!event.isEnabled()) {
            return Target_sun_nio_ch_SocketChannelImpl_util.read(this, dst);
        }
        int bytesRead = 0;
        try {
            event.begin();
            bytesRead = Target_sun_nio_ch_SocketChannelImpl_util.read(this, dst);
        } finally {
            event.end();
            if (event.shouldCommit())  {
                String hostString  = remoteAddress.getAddress().toString();
                int delimiterIndex = hostString.lastIndexOf('/');

                event.host      = hostString.substring(0, delimiterIndex);
                event.address   = hostString.substring(delimiterIndex + 1);
                event.port      = remoteAddress.getPort();
                if (bytesRead < 0) {
                    event.endOfStream = true;
                } else {
                    event.bytesRead = bytesRead;
                }
                event.timeout   = 0;

                event.commit();
                event.reset();
            }
        }
        return bytesRead;
    }

    @Substitute
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        SocketReadEvent event = SocketReadEvent.EVENT.get();
        if(!event.isEnabled()) {
            return Target_sun_nio_ch_SocketChannelImpl_util.read(this, dsts, offset, length);
        }
        long bytesRead = 0;
        try {
            event.begin();
            bytesRead = Target_sun_nio_ch_SocketChannelImpl_util.read(this, dsts, offset, length);
        } finally {
            event.end();
            if (event.shouldCommit()) {
                String hostString  = remoteAddress.getAddress().toString();
                int delimiterIndex = hostString.lastIndexOf('/');

                event.host      = hostString.substring(0, delimiterIndex);
                event.address   = hostString.substring(delimiterIndex + 1);
                event.port      = remoteAddress.getPort();
                if (bytesRead < 0) {
                    event.endOfStream = true;
                } else {
                    event.bytesRead = bytesRead;
                }
                event.timeout   = 0;

                event.commit();
                event.reset();
            }
        }
        return bytesRead;
    }

    @Substitute
    public int write(ByteBuffer buf) throws IOException {
        SocketWriteEvent event = SocketWriteEvent.EVENT.get();
        if (!event.isEnabled()) {
            return Target_sun_nio_ch_SocketChannelImpl_util.write(this, buf);
        }
        int bytesWritten = 0;
        try {
            event.begin();
            bytesWritten = Target_sun_nio_ch_SocketChannelImpl_util.write(this, buf);
        } finally {
            event.end();
            if (event.shouldCommit()) {
                String hostString  = remoteAddress.getAddress().toString();
                int delimiterIndex = hostString.lastIndexOf('/');

                event.host         = hostString.substring(0, delimiterIndex);
                event.address      = hostString.substring(delimiterIndex + 1);
                event.port         = remoteAddress.getPort();
                event.bytesWritten = bytesWritten < 0 ? 0 : bytesWritten;

                event.commit();
                event.reset();
            }
        }
        return bytesWritten;
    }

    @Substitute
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        SocketWriteEvent event = SocketWriteEvent.EVENT.get();
        if (!event.isEnabled()) {
            return Target_sun_nio_ch_SocketChannelImpl_util.write(this, srcs, offset, length);
        }
        long bytesWritten = 0;
        try {
            event.begin();
            bytesWritten = Target_sun_nio_ch_SocketChannelImpl_util.write(this, srcs, offset, length);
        } finally {
            event.end();
            if (event.shouldCommit()) {
                String hostString  = remoteAddress.getAddress().toString();
                int delimiterIndex = hostString.lastIndexOf('/');
                event.host         = hostString.substring(0, delimiterIndex);
                event.address      = hostString.substring(delimiterIndex + 1);
                event.port         = remoteAddress.getPort();
                event.bytesWritten = bytesWritten < 0 ? 0 : bytesWritten;

                event.commit();
                event.reset();
            }
        }
        return bytesWritten;
    }

    @TargetClass(className = "sun.nio.ch.NativeDispatcher", onlyWith = JfrAvailability.WithJfr.class)
    static final class Target_sun_nio_ch_NativeDispatcher {
    }

    @TargetClass(className = "sun.nio.ch.IOUtil", onlyWith = JfrAvailability.WithJfr.class)
    static final class Target_sun_nio_ch_IOUtil {
        @Alias
        static int read(FileDescriptor fd, ByteBuffer b, int pos, Target_sun_nio_ch_NativeDispatcher nd) {
            return 0;
        }
    }

    static final class Target_sun_nio_ch_SocketChannelImpl_util {
        static int read(Target_sun_nio_ch_SocketChannelImpl thiz, ByteBuffer buf) throws IOException {
            Objects.requireNonNull(buf);

            thiz.readLock.lock();
            try {
                boolean blocking = thiz.isBlocking();
                int n = 0;
                try {
                    thiz.beginRead(blocking);

                    // check if input is shutdown
                    if (thiz.isInputClosed)
                        return IOStatus.EOF;

                    if (blocking) {
                        do {
                            n = Target_sun_nio_ch_IOUtil.read(thiz.fd, buf, -1, thiz.nd);
                        } while (n == IOStatus.INTERRUPTED && thiz.isOpen());
                    } else {
                        n = Target_sun_nio_ch_IOUtil.read(thiz.fd, buf, -1, thiz.nd);
                    }
                } finally {
                    thiz.endRead(blocking, n > 0);
                    if (n <= 0 && thiz.isInputClosed)
                        return IOStatus.EOF;
                }
                return IOStatus.normalize(n);
            } finally {
                thiz.readLock.unlock();
            }
        }

        static long read(Target_sun_nio_ch_SocketChannelImpl thiz, ByteBuffer[] dsts, int offset, int length) throws IOException {
            return 0;
        }

        static int write(Target_sun_nio_ch_SocketChannelImpl thiz, ByteBuffer buf) throws IOException {
            return 0;
        }

        static long write(Target_sun_nio_ch_SocketChannelImpl thiz, ByteBuffer[] srcs, int offset, int length) throws IOException {
            return 0;
        }
    }

    //@TargetClass(className = "sun.nio.ch.IOStatus", onlyWith = JfrAvailability.WithJfr.class)
    static final class Target_sun_nio_ch_IOStatus {

        @Alias static int EOF;
        @Alias static int INTERRUPTED;

        @Alias static boolean check(int n) {
            return false;
        }
        @Alias static int normalize(int n) { return n; }
    }
}
**/