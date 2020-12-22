package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import jdk.jfr.events.FileReadEvent;
import jdk.jfr.events.FileWriteEvent;

import java.io.IOException;

@TargetClass(className = "java.io.RandomAccessFile", onlyWith = JfrAvailability.WithJfr.class)
final class Target_java_io_RandomAccessFile {

    @Alias
    private String path;

    @Alias
    private native int read0() throws IOException;

    @Alias
    private native int readBytes(byte b[], int off, int len) throws IOException;

    @Alias
    private native void write0(int b) throws IOException;

    @Alias
    private native void writeBytes(byte b[], int off, int len) throws IOException;

    @Substitute
    public int read() throws IOException {
        FileReadEvent event = FileReadEvent.EVENT.get();
        if (!event.isEnabled()) {
            return read0();
        }
        int result = 0;
        try {
            event.begin();
            result = read0();
            if (result < 0) {
                event.endOfFile = true;
            } else {
                event.bytesRead = 1;
            }
        } finally {
            event.path = path;
            event.commit();
            event.reset();
        }
        return result;
    }

    @Substitute
    public int read(byte[] b) throws IOException {
        FileReadEvent event = FileReadEvent.EVENT.get();
        if (!event.isEnabled()) {
            return readBytes(b, 0, b.length);
        }
        int bytesRead = 0;
        try {
            event.begin();
            bytesRead = readBytes(b, 0, b.length);
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
    public int read(byte[] b, int off, int len) throws IOException {
        FileReadEvent event = FileReadEvent.EVENT.get();
        if (!event.isEnabled()) {
            return readBytes(b, off, len);
        }
        int bytesRead = 0;
        try {
            event.begin();
            bytesRead = readBytes(b, off, len);
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
    public void write(int b) throws IOException {
        FileWriteEvent event = FileWriteEvent.EVENT.get();
        if (!event.isEnabled()) {
            write0(b);
            return;
        }
        try {
            event.begin();
            write0(b);
            event.bytesWritten = 1;
        } finally {
            event.path = path;
            event.commit();
            event.reset();
        }
    }

    @Substitute
    public void write(byte[] b) throws IOException {
        FileWriteEvent event = FileWriteEvent.EVENT.get();
        if (!event.isEnabled()) {
            writeBytes(b, 0, b.length);
            return;
        }
        try {
            event.begin();
            writeBytes(b, 0, b.length);
            event.bytesWritten = b.length;
        } finally {
            event.path = path;
            event.commit();
            event.reset();
        }
    }

    @Substitute
    public void write(byte[] b, int off, int len) throws IOException {
        FileWriteEvent event = FileWriteEvent.EVENT.get();
        if (!event.isEnabled()) {
            writeBytes(b, off, len);
            return;
        }
        try {
            event.begin();
            writeBytes(b, off, len);
            event.bytesWritten = len;
        } finally {
            event.path = path;
            event.commit();
            event.reset();
        }
    }
}
