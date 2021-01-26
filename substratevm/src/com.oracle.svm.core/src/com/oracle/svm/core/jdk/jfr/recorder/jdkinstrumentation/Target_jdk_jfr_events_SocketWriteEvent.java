package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import jdk.jfr.internal.EventWriter;
import jdk.jfr.internal.PlatformEventType;

// unused
//@TargetClass(className = "jdk.jfr.events.SocketWriteEvent", onlyWith = JfrAvailability.WithJfr.class)
final class Target_jdk_jfr_events_SocketWriteEvent {

        // added by JDK instrumentation

        @Alias private long startTime;

        @Alias private long duration;

        // event specific code

        @Alias public String host;

        @Alias public String address;

        @Alias public int port;

        @Alias public long bytesWritten;


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
                PlatformEventType et = JdkInstrumentationUtil.SOCKET_WRITE_EVENT_TYPE;
                if (et == null) {
                    et = JdkInstrumentationUtil.getPlatformEventType(jdk.jfr.events.SocketWriteEvent.class);
                }
                System.err.println("XXX swe et = " + et);
                ew.beginEvent(et);
                ew.putLong(startTime);
                ew.putLong(duration);
                ew.putEventThread();
                ew.putStackTrace();
                ew.putString(host, null);
                ew.putString(address, null);
                ew.putInt(port);
                ew.putLong(bytesWritten);
                ew.endEvent();
            }
        }

        @Substitute
        final public boolean isEnabled() {
            return JdkInstrumentationUtil.eventIsEnabled(jdk.jfr.events.SocketWriteEvent.class);
        }

        @Substitute
        final public boolean shouldCommit() {
            return JdkInstrumentationUtil.shouldCommit(duration);
            /* The JDK also tests settingsInfo */
        }
    }