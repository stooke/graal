package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.jfr.recorder.JfrRecorder;
import com.oracle.svm.core.jdk.jfr.support.JfrThreadLocal;
import com.oracle.svm.core.thread.JavaThreads;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.internal.PlatformEventType;

/*
NOTE: TODO:
- ensure this class doesn't exist in the native executable unless JFR is enabled
- get rid of need to add these to command line (prevents a misleading error
  because the compiler can't find some classes, probably SharedSecrets
  (should use substitutions to fix this)
    -J--add-exports -Jjdk.jfr/jdk.jfr.internal=ALL-UNNAMED
- see JfrFeature for code that initializes this at run time.
- initialize-at-run-time=com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation.JdkInstrumentationUtil
 by initializing the globals when required
 */
public final class JdkInstrumentationUtil {

    static long initialTimestamp = System.nanoTime();

    static boolean eventIsEnabled(Class<?> clazz) {
        // JFR-TODO check JFR configuration profile
        JfrThreadLocal jtl = JavaThreads.getThreadLocal(Thread.currentThread());
        assert (jtl != null);
        return !jtl.isExcluded();
    }

    static boolean shouldCommit(long duration) {
        return JfrRecorder.isRecording();
    }

    static long timestamp() {
        return System.nanoTime() - initialTimestamp;
    }

    static long duration(long startTime) {
        if (startTime == 0) {
            return 0;
        }
        return timestamp() - startTime;
    }

    /* TODO - initialize automatically *
    static PlatformEventType FILE_READ_EVENT_TYPE = null;
    static PlatformEventType FILE_WRITE_EVENT_TYPE = null;
    static PlatformEventType FILE_FORCE_EVENT_TYPE = null;
    static PlatformEventType SOCKET_READ_EVENT_TYPE = null;
    static PlatformEventType SOCKET_WRITE_EVENT_TYPE = null;
    /*/

    static PlatformEventType FILE_READ_EVENT_TYPE = getPlatformEventType(jdk.jfr.events.FileReadEvent.class);
    static PlatformEventType FILE_WRITE_EVENT_TYPE = getPlatformEventType(jdk.jfr.events.FileWriteEvent.class);
    static PlatformEventType FILE_FORCE_EVENT_TYPE = getPlatformEventType(jdk.jfr.events.FileForceEvent.class);
    static PlatformEventType SOCKET_READ_EVENT_TYPE = getPlatformEventType(jdk.jfr.events.SocketReadEvent.class);
    static PlatformEventType SOCKET_WRITE_EVENT_TYPE = getPlatformEventType(jdk.jfr.events.SocketWriteEvent.class);
/**/
    static boolean initialized = false;

    public static void initialize() {
        /* This must be called after the events have been registered, or EventType() will throw an error.
           Ideally, this could happen at build time */
        if ((!SubstrateUtil.HOSTED) && (!initialized)) {
            initialized = true;
            System.err.println("XXXX JdkInstrumentationUtil initialize()");
            new Exception("foo").printStackTrace();
            FILE_READ_EVENT_TYPE = getPlatformEventType(jdk.jfr.events.FileReadEvent.class);
            FILE_WRITE_EVENT_TYPE = getPlatformEventType(jdk.jfr.events.FileWriteEvent.class);
            FILE_FORCE_EVENT_TYPE = getPlatformEventType(jdk.jfr.events.FileForceEvent.class);
            SOCKET_READ_EVENT_TYPE = getPlatformEventType(jdk.jfr.events.SocketReadEvent.class);
            SOCKET_WRITE_EVENT_TYPE = getPlatformEventType(jdk.jfr.events.SocketWriteEvent.class);
        }
    }


    public static PlatformEventType getPlatformEventType(Class<? extends Event> clazz) {
        EventType eventType = EventType.getEventType(clazz);
        PlatformEventType pet = getPlatformEventType(eventType);
        if (pet == null) {
            System.err.println("XXXX could not get PET for " + clazz.getName());
        }
        return pet;
    }

    public static PlatformEventType getPlatformEventType(EventType type) {
        return SubstrateUtil.cast(type, Target_jdk_jfr_EventType.class).platformEventType;
    }

    @TargetClass(jdk.jfr.EventType.class)
    static final class Target_jdk_jfr_EventType {
        @Alias
        PlatformEventType platformEventType;
    }
}

