package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import jdk.jfr.internal.EventWriter;

//@TargetClass(className = "jdk.jfr.events.FileWriteEvent", onlyWith = JfrAvailability.WithJfr.class)
final class Target_jdk_jfr_events_FileWriteEvent {

    // added by JDK instrumentation

    @Alias
    private long startTime;

    @Alias
    private long duration;

    @Alias
    public String path;

    @Alias
    public long bytesWritten;

    @Substitute
    final public void begin() {
        startTime = JdkInstrumentationUtil.timestamp();
    }

    @Substitute
    final public void end() {
        duration = JdkInstrumentationUtil.duration(startTime);
    }

    @Substitute
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
            ew.beginEvent(JdkInstrumentationUtil.FILE_WRITE_EVENT_TYPE);
            ew.putLong(startTime);
            ew.putLong(duration);
            ew.putEventThread();
            ew.putStackTrace();
            ew.putString(path, null);
            /* JFR-TODO: uncommenting this line and commenting th next leads to NPE (getAddress() returns null)
            Caused by: java.lang.NullPointerException
                at jdk.internal.vm.compiler/org.graalvm.compiler.nodes.memory.FloatingReadNode.canonical(FloatingReadNode.java:101)
                at jdk.internal.vm.compiler/org.graalvm.compiler.phases.common.CanonicalizerPhase$Instance.tryCanonicalize(CanonicalizerPhase.java:407)
             */
            //ew.putLong(bytesWritten);
            ew.putLong(0);
            ew.endEvent();
        }
    }

    @Substitute
    final public boolean isEnabled() {
        return JdkInstrumentationUtil.eventIsEnabled(jdk.jfr.events.FileWriteEvent.class);
    }

    @Substitute
    final public boolean shouldCommit() {
        return JdkInstrumentationUtil.shouldCommit(duration);
        /* The JDK also tests settingsInfo */
    }
}