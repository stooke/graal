package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import jdk.jfr.events.FileWriteEvent;

import java.io.FileDescriptor;
import java.io.IOException;

@TargetClass(className = "java.io.FileOutputStream", onlyWith = JfrAvailability.WithJfr.class)
public final class Target_java_io_FileOutputStream {

    @Alias
    private String path;

    @Alias
    public FileDescriptor fd;

    /* Setting an @Alias for fdaccess doesn't seem to work properly (no output),
     * ut accessing SharedSecrets directly works bur requires -J--add-exports -Jjava.base/jdk.internal.misc=ALL-UNNAMED)
     * using @Substitute works without using --add-exports
     */

    @Substitute
    public void write(int b) throws IOException {
        FileWriteEvent event = FileWriteEvent.EVENT.get();
        if (!event.isEnabled()) {
            write(b, Target_jdk_internal_misc_SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
            return;
        }
        try {
            event.begin();
            write(b, Target_jdk_internal_misc_SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
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
            writeBytes(b, 0, b.length, Target_jdk_internal_misc_SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
            return;
        }
        try {
            event.begin();
            writeBytes(b, 0, b.length, Target_jdk_internal_misc_SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
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
            writeBytes(b, off, len, Target_jdk_internal_misc_SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
            return;
        }
        try {
            event.begin();
            writeBytes(b, off, len, Target_jdk_internal_misc_SharedSecrets.getJavaIOFileDescriptorAccess().getAppend(fd));
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

    /* JFR-TODO - maybe we can just do an alias to FileDescriptoer.getAppend() ? */

    @TargetClass(className = "jdk.internal.misc.JavaIOFileDescriptorAccess", onlyWith = JfrAvailability.WithJfr.class)
    static final class Target_jdk_internal_misc_JavaIOFileDescriptorAccess {
        @Alias
        boolean getAppend(FileDescriptor fd) { return false; }
    }

    @TargetClass(className = "jdk.internal.misc.SharedSecrets", onlyWith = JfrAvailability.WithJfr.class)
    static final class Target_jdk_internal_misc_SharedSecrets {
        @Alias
        static Target_jdk_internal_misc_JavaIOFileDescriptorAccess getJavaIOFileDescriptorAccess() { return null; }
    }
}
