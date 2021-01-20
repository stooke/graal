package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import com.oracle.svm.core.option.APIOption;
import jdk.jfr.events.FileForceEvent;
import jdk.jfr.events.FileReadEvent;
import jdk.jfr.events.FileWriteEvent;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.FileChannel;

//@TargetClass(className = "sun.nio.ch.FileChannelImpl", onlyWith = JfrAvailability.WithJfr.class)
final class Target_sun_nio_ch_FileChannelImpl {

    @Alias void ensureOpen() throws IOException { }
    @Alias void beginBlocking() { }
    @Alias void endBlocking(boolean completed) throws AsynchronousCloseException {}

    @Alias volatile boolean closed;

    @Alias Target_sun_nio_ch_FileDispatcher nd;
    @Alias FileDescriptor fd;
    @Alias Target_sun_nio_ch_NativeThreadSet threads = new Target_sun_nio_ch_NativeThreadSet(2);

    @Alias private String path;

    @TargetClass(className = "sun.nio.ch.FileDispatcher", onlyWith = JfrAvailability.WithJfr.class)
    final static class Target_sun_nio_ch_FileDispatcher {
        @Alias int force(FileDescriptor fd, boolean metaData) {
            return 0;
        }
    }

    @TargetClass(className = "sun.nio.ch.NativeThreadSet", onlyWith = JfrAvailability.WithJfr.class)
    final static class Target_sun_nio_ch_NativeThreadSet {
        @Alias Target_sun_nio_ch_NativeThreadSet(int n) {}
        @Alias int add() { return 0; }
        @Alias void remove(int i) {}
    }

    @Substitute
    public void force(boolean metaData) throws IOException {
        FileForceEvent event = FileForceEvent.EVENT.get();
        if (!event.isEnabled()) {
            Target_sun_nio_ch_FileChannelImpl_util.force(this, metaData);
            return;
        }
        try {
            event.begin();
            Target_sun_nio_ch_FileChannelImpl_util.force(this, metaData);
        } finally {
            event.path = path;
            event.metaData = metaData;
            event.commit();
            event.reset();
        }
    }

    @Substitute
    public int read(ByteBuffer dst) throws IOException {
        FileReadEvent event = FileReadEvent.EVENT.get();
        if (!event.isEnabled()) {
            return Target_sun_nio_ch_FileChannelImpl_util.read(this, dst);
        }
        int bytesRead = 0;
        try {
            event.begin();
            bytesRead = Target_sun_nio_ch_FileChannelImpl_util.read(this, dst);
        } finally {
            if (bytesRead < 0) {
                event.endOfFile = true;
            } else {
                event.bytesRead = bytesRead;
            }
            event.path = path;
            event.commit();
            event.reset();
        }
        return bytesRead;
    }

    @Substitute
    public int read(ByteBuffer dst, long position) throws IOException {
        FileReadEvent event = FileReadEvent.EVENT.get();
        if (!event.isEnabled()) {
            return Target_sun_nio_ch_FileChannelImpl_util.read(this, dst, position);
        }
        int bytesRead = 0;
        try {
            event.begin();
            bytesRead = read(dst, position);
        } finally {
            if (bytesRead < 0) {
                event.endOfFile = true;
            } else {
                event.bytesRead = bytesRead;
            }
            event.path = path;
            event.commit();
            event.reset();
        }
        return bytesRead;
    }

    @Substitute
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        FileReadEvent event = FileReadEvent.EVENT.get();
        if (!event.isEnabled()) {
            return Target_sun_nio_ch_FileChannelImpl_util.read(this, dsts, offset, length);
        }
        long bytesRead = 0;
        try {
            event.begin();
            bytesRead = Target_sun_nio_ch_FileChannelImpl_util.read(this, dsts, offset, length);
        } finally {
            if (bytesRead < 0) {
                event.endOfFile = true;
            } else {
                event.bytesRead = bytesRead;
            }
            event.path = path;
            event.commit();
            event.reset();
        }
        return bytesRead;
    }

    @Substitute
    public int write(ByteBuffer src) throws IOException {
        FileWriteEvent event = FileWriteEvent.EVENT.get();
        if (!event.isEnabled()) {
            return Target_sun_nio_ch_FileChannelImpl_util.write(this, src);
        }
        int bytesWritten = 0;
        try {
            event.begin();
            bytesWritten = Target_sun_nio_ch_FileChannelImpl_util.write(this, src);
        } finally {
            event.bytesWritten = bytesWritten > 0 ? bytesWritten : 0;
            event.path = path;
            event.commit();
            event.reset();
        }
        return bytesWritten;
    }

    @Substitute
    public int write(ByteBuffer src, long position) throws IOException {
        FileWriteEvent event = FileWriteEvent.EVENT.get();
        if (!event.isEnabled()) {
            return Target_sun_nio_ch_FileChannelImpl_util.write(this, src, position);
        }

        int bytesWritten = 0;
        try {
            event.begin();
            bytesWritten = Target_sun_nio_ch_FileChannelImpl_util.write(this, src, position);
        } finally {
            event.bytesWritten = bytesWritten > 0 ? bytesWritten : 0;
            event.path = path;
            event.commit();
            event.reset();
        }
        return bytesWritten;
    }

    @Substitute
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        FileWriteEvent event = FileWriteEvent.EVENT.get();
        if (!event.isEnabled()) {
            return Target_sun_nio_ch_FileChannelImpl_util.write(this, srcs, offset, length);
        }
        long bytesWritten = 0;
        try {
            event.begin();
            bytesWritten = Target_sun_nio_ch_FileChannelImpl_util.write(this, srcs, offset, length);
        } finally {
            event.bytesWritten = bytesWritten > 0 ? bytesWritten : 0;
            event.path = path;
            event.commit();
            event.reset();
        }
        return bytesWritten;
    }


    static final class Target_sun_nio_ch_FileChannelImpl_util {
        static void force(Target_sun_nio_ch_FileChannelImpl thiz, boolean metaData) throws IOException {
            thiz.ensureOpen();
            int rv = -1;
            int ti = -1;
            try {
                thiz.beginBlocking();
                ti = thiz.threads.add();
                if (!isOpen(thiz))
                    return;
                do {
                    rv = thiz.nd.force(thiz.fd, metaData);
                } while ((rv == Target_sun_nio_ch_IOStatus.INTERRUPTED) && isOpen(thiz));
            } finally {
                thiz.threads.remove(ti);
                thiz.endBlocking(rv > -1);
                assert Target_sun_nio_ch_IOStatus.check(rv);
            }
        }

        @Substitute
        static final boolean isOpen(Target_sun_nio_ch_FileChannelImpl thiz) {
            return !thiz.closed;
        }

        static int read(Target_sun_nio_ch_FileChannelImpl thiz, ByteBuffer dst) throws IOException {
            return 0;
        }

        static int read(Target_sun_nio_ch_FileChannelImpl thiz, ByteBuffer dst, long position) throws IOException {
            return 0;
        }

        static long read(Target_sun_nio_ch_FileChannelImpl thiz, ByteBuffer[] dsts, int offset, int length) throws IOException {
            return 0;
        }

        static int write(Target_sun_nio_ch_FileChannelImpl thiz, ByteBuffer src) throws IOException {
            return 0;
        }

        static int write(Target_sun_nio_ch_FileChannelImpl thiz, ByteBuffer src, long position) throws IOException {
            return 0;
        }


        static long write(Target_sun_nio_ch_FileChannelImpl thiz, ByteBuffer[] srcs, int offset, int length) throws IOException {
            return 0;
        }
    }

    @TargetClass(className = "sun.nio.ch.IOStatus", onlyWith = JfrAvailability.WithJfr.class)
    static final class Target_sun_nio_ch_IOStatus {

        @Alias static int INTERRUPTED;

        @Alias static boolean check(int n) {
            return false;
        }
    }
}
