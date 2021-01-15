package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Inject;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import jdk.internal.misc.SharedSecrets;
import jdk.jfr.EventType;
import jdk.jfr.events.FileWriteEvent;

import java.io.FileDescriptor;
import java.io.IOException;

//@TargetClass(className = "java.io.FileOutputStream", onlyWith = JfrAvailability.WithJfr.class)
public final class Target_java_io_FileOutputStream {

    @Alias
    private String path;

    @Alias
    public FileDescriptor fd;

    // setting an @Alias for fdaccess doesn't seem to work properly (no output)
    // but accessing SharedSecrets directly does work
    // (requires -J--add-exports -Jjava.base/jdk.internal.misc=ALL-UNNAMED)

    @Substitute
    public void write(int b) throws IOException {
        FileWriteEvent event = FileWriteEvent.EVENT.get();
        if (!event.isEnabled()) {
            write(b, SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
            return;
        }
        try {
            event.begin();
            write(b, SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
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
            writeBytes(b, 0, b.length, SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
            return;
        }
        try {
            event.begin();
            writeBytes(b, 0, b.length, SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
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
            writeBytes(b, off, len, SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
            return;
        }
        try {
            event.begin();
            writeBytes(b, off, len, SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
            event.bytesWritten = len;
        } finally {
            event.path = path;
            event.commit();
            event.reset();
        }
    }

    @Alias
    private native void writeBytes(byte[] b, int off, int len, boolean append) throws IOException;

    @Alias
    private native void write(int b, boolean append) throws IOException;
}
