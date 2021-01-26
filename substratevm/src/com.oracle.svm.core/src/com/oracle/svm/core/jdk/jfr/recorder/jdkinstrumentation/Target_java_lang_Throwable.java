package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.NeverInline;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.StackTraceUtils;
import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import com.oracle.svm.core.snippets.KnownIntrinsics;

import java.util.List;

import static com.oracle.svm.core.annotate.RecomputeFieldValue.Kind.Reset;

/*
 * JFR:TODO - Graal substitutions require final classes that inherit only from Object,
 *    therefore no way to instrument java.lang.Error.
 *    Here, we have to do a roundabout way of 'isinstanceof()'.
 */
/*
 * JFR:TODO - Make this optional based on Flight Recorder configuration.
 */
@SuppressWarnings("ConstantConditions")
@TargetClass(value = java.lang.Throwable.class, onlyWith = JfrAvailability.WithJfr.class)
public final class Target_java_lang_Throwable {

    @Alias private Throwable cause;
    @Alias private List<Throwable> suppressedExceptions;

    @Alias @RecomputeFieldValue(kind = Reset)//
    private Object backtrace;

    @Alias @RecomputeFieldValue(kind = Reset)//
            StackTraceElement[] stackTrace;

    @Alias String detailMessage;

    @Substitute
    @NeverInline("Starting a stack walk in the caller frame")
    private Object fillInStackTrace() {
        stackTrace = StackTraceUtils.getStackTrace(true, KnownIntrinsics.readCallerStackPointer());
        return this;
    }

    @Substitute
    private StackTraceElement[] getOurStackTrace() {
        if (stackTrace != null) {
            return stackTrace;
        } else {
            return new StackTraceElement[0];
        }
    }

    @Substitute
    public Target_java_lang_Throwable() {
        fillInStackTrace();
        Target_jdk_jfr_internal_instrument_ThrowableTracer.traceThrowable(this, null);
        if (Error.class.isAssignableFrom(this.getClass())) {
            Error thiz = SubstrateUtil.cast(this, Error.class);
            Target_jdk_jfr_internal_instrument_ThrowableTracer.traceError(thiz, detailMessage);
        }
    }

    @Substitute
    public Target_java_lang_Throwable(String message) {
        fillInStackTrace();
        detailMessage = message;
        Target_jdk_jfr_internal_instrument_ThrowableTracer.traceThrowable(this, message);
        if (Error.class.isAssignableFrom(this.getClass())) {
            Error thiz = SubstrateUtil.cast(this, Error.class);
            Target_jdk_jfr_internal_instrument_ThrowableTracer.traceError(thiz, detailMessage);
        }
    }

    @Substitute
    public Target_java_lang_Throwable(String message, Throwable cause) {
        fillInStackTrace();
        detailMessage = message;
        this.cause = cause;
        Target_jdk_jfr_internal_instrument_ThrowableTracer.traceThrowable(this, message);
        if (Error.class.isAssignableFrom(this.getClass())) {
            Error thiz = SubstrateUtil.cast(this, Error.class);
            Target_jdk_jfr_internal_instrument_ThrowableTracer.traceError(thiz, detailMessage);
        }
    }

    @Substitute
    public Target_java_lang_Throwable(Throwable cause) {
        fillInStackTrace();
        detailMessage = (cause==null ? null : cause.toString());
        this.cause = cause;
        Target_jdk_jfr_internal_instrument_ThrowableTracer.traceThrowable(this, detailMessage);
        if (Error.class.isAssignableFrom(this.getClass())) {
            Error thiz = SubstrateUtil.cast(this, Error.class);
            Target_jdk_jfr_internal_instrument_ThrowableTracer.traceError(thiz, detailMessage);
        }
    }

    @Substitute
    protected Target_java_lang_Throwable(String message, Throwable cause,
                                         boolean enableSuppression,
                                         boolean writableStackTrace) {
        if (writableStackTrace) {
            fillInStackTrace();
        } else {
            stackTrace = null;
        }
        detailMessage = message;
        this.cause = cause;
        if (!enableSuppression) {
            suppressedExceptions = null;
        }
        Target_jdk_jfr_internal_instrument_ThrowableTracer.traceThrowable(this, message);
        if (Error.class.isAssignableFrom(this.getClass())) {
            Error thiz = SubstrateUtil.cast(this, Error.class);
            Target_jdk_jfr_internal_instrument_ThrowableTracer.traceError(thiz, detailMessage);
        }
    }

    @TargetClass(className = "jdk.jfr.internal.instrument.ThrowableTracer", onlyWith = JfrAvailability.WithJfr.class)
    static final class Target_jdk_jfr_internal_instrument_ThrowableTracer {
        @Alias public static void traceError(Error e, String message) {}
        @Alias public static void traceThrowable(Target_java_lang_Throwable t, String message) {}
    }
}
