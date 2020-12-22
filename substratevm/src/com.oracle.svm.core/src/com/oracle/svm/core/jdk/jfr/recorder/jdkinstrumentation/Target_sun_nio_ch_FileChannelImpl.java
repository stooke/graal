package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import jdk.jfr.events.FileForceEvent;
import jdk.jfr.events.FileReadEvent;
import jdk.jfr.events.FileWriteEvent;

import java.io.IOException;
import java.nio.ByteBuffer;

//@TargetClass(className = "sun.nio.ch.FileChanelImpl", onlyWith = JfrAvailability.WithJfr.class)
final class Target_sun_nio_ch_FileChannelImpl {


    @Alias
    private String path;

    @Substitute
    public void force(boolean metaData) throws IOException {
        FileForceEvent event = FileForceEvent.EVENT.get();
        if (!event.isEnabled()) {
            force(metaData);
            return;
        }
        try {
            event.begin();
            force(metaData);
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
            return read(dst);
        }
        int bytesRead = 0;
        try {
            event.begin();
            bytesRead = read(dst);
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
            return read(dst, position);
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
            return read(dsts, offset, length);
        }
        long bytesRead = 0;
        try {
            event.begin();
            bytesRead = read(dsts, offset, length);
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
            return write(src);
        }
        int bytesWritten = 0;
        try {
            event.begin();
            bytesWritten = write(src);
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
            return write(src, position);
        }

        int bytesWritten = 0;
        try {
            event.begin();
            bytesWritten = write(src, position);
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
            return write(srcs, offset, length);
        }
        long bytesWritten = 0;
        try {
            event.begin();
            bytesWritten = write(srcs, offset, length);
        } finally {
            event.bytesWritten = bytesWritten > 0 ? bytesWritten : 0;
            event.path = path;
            event.commit();
            event.reset();
        }
        return bytesWritten;
    }
}
