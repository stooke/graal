package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

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

//@TargetClass(className = "sun.nio.ch.SocketChanelImpl", onlyWith = JfrAvailability.WithJfr.class)
final class Target_sun_nio_ch_SocketChannelImpl {

    @Alias
    private final FileDescriptor fd = null;

    @Alias
    private static NativeDispatcher nd = null;

    @Alias
    private InetSocketAddress remoteAddress;

    @Alias
    private final ReentrantLock readLock = new ReentrantLock();

    @Alias
    private final ReentrantLock writeLock = new ReentrantLock();

    @Alias
    private volatile boolean isInputClosed;

    @Alias
    private volatile boolean isOutputClosed;

    @Alias
    private volatile boolean nonBlocking;

    @Alias
    public final boolean isBlocking() { return true; }

    @Alias
    public final boolean isOpen() { return true; }

    public int read0(ByteBuffer buf) throws IOException {
        Objects.requireNonNull(buf);

        readLock.lock();
        try {
            boolean blocking = isBlocking();
            int n = 0;
            try {
                beginRead(blocking);

                // check if input is shutdown
                if (isInputClosed)
                    return IOStatus.EOF;
/******************************************************************************************************************
                if (blocking) {
                    do {
                        n = IOUtil.read(fd, buf, -1, nd);
                    } while (n == IOStatus.INTERRUPTED && isOpen());
                } else {
                    n = IOUtil.read(fd, buf, -1, nd);
                }
 */
            } finally {
                endRead(blocking, n > 0);
                if (n <= 0 && isInputClosed)
                    return IOStatus.EOF;
            }
            return IOStatus.normalize(n);
        } finally {
            readLock.unlock();
        }
    }

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
            return read0(dst);
        }
        int bytesRead = 0;
        try {
            event.begin();
            bytesRead = read0(dst);
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
            return read(dsts, offset, length);
        }

        long bytesRead = 0;
        try {
            event.begin();
            bytesRead = read(dsts, offset, length);
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
            return write(buf);
        }

        int bytesWritten = 0;
        try {
            event.begin();
            bytesWritten = write(buf);
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
            return write(srcs, offset, length);
        }
        long bytesWritten = 0;
        try {
            event.begin();
            bytesWritten = write(srcs, offset, length);
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

    static class NativeDispatcher {
        // really
    }
}
