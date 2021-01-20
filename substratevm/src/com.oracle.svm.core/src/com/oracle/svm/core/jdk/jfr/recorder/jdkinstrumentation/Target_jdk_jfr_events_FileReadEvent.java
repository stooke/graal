package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import com.oracle.svm.core.jdk.jfr.JfrAvailability;

import jdk.jfr.internal.EventWriter;

//@TargetClass(className = "jdk.jfr.events.FileReadEvent", onlyWith = JfrAvailability.WithJfr.class)
final class Target_jdk_jfr_events_FileReadEvent {

    // added by JDK instrumentation

    @Alias
    private long startTime;

    @Alias
    private long duration;

    // event specific code

    @Alias
    public String path;

    @Alias
    public long bytesRead;

    @Alias
    public boolean endOfFile;


    @Alias
    final public void begin() {
        startTime = JdkInstrumentationUtil.timestamp();
    }

    @Alias
    final public void end() {
        duration = JdkInstrumentationUtil.duration(startTime);
    }

    @Alias
    final void reset() {}

    @Alias
    final public void commit() {
        if (!isEnabled()) {
            return;
        }
        if (startTime == 0) {
            startTime = JdkInstrumentationUtil.timestamp();
        } else if (duration == 0) {
            duration = JdkInstrumentationUtil.timestamp() - startTime;
        }
        if (shouldCommit()) {
            EventWriter ew = EventWriter.getEventWriter();
            ew.beginEvent(JdkInstrumentationUtil.FILE_READ_EVENT_TYPE);
            ew.putLong(startTime);
            ew.putLong(duration);
            ew.putEventThread();
            ew.putStackTrace();
            ew.putString(path, null);
            ew.putLong(bytesRead);
            ew.putBoolean(endOfFile);
            ew.endEvent();
        }
    }

    @Substitute
    final public boolean isEnabled() {
        return JdkInstrumentationUtil.eventIsEnabled(jdk.jfr.events.FileReadEvent.class);
    }

    @Substitute
    final public boolean shouldCommit() {
        return JdkInstrumentationUtil.shouldCommit(duration);
        /* The JDK also tests settingsInfo */
    }
}