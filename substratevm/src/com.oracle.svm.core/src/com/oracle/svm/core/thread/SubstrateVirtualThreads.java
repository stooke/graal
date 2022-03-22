/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.thread;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.graalvm.compiler.api.replacements.Fold;

import com.oracle.svm.core.RuntimeAssertionsSupport;
import com.oracle.svm.core.util.VMError;

/** Our own implementation of virtual threads that does not need Project Loom. */
final class SubstrateVirtualThreads implements VirtualThreads {
    @Fold
    static boolean haveAssertions() {
        return RuntimeAssertionsSupport.singleton().desiredAssertionStatus(SubstrateVirtualThreads.class);
    }

    private static final class CarrierThread extends ForkJoinWorkerThread {
        CarrierThread(ForkJoinPool pool) {
            super(pool);
        }

        /**
         * Ignore any handlers that other code tries to install. {@link #UNCAUGHT_EXCEPTION_HANDLER}
         * will still be installed through other means.
         */
        @Override
        public void setUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        }

        @Override
        protected void onTermination(Throwable exception) {
            if (exception != null) {
                /*
                 * Exceptions thrown in virtual threads are caught, but an exception thrown in the
                 * continuation or virtual thread mechanisms themselves can propagate further. On
                 * JDK 11, such exceptions terminate a worker thread, cancelling all tasks in its
                 * queue, and therefore cause liveness problems, so we fail below.
                 *
                 * On JDK 17, a worker thread continues executing tasks after catching an exception,
                 * so we can reach here only if there is an internal exception in ForkJoinPool.
                 */
                throw VMError.shouldNotReachHere("Carrier thread must not terminate abnormally because it cancels pending tasks " +
                                "which can result in virtual threads never being scheduled again.", exception);
            }
        }
    }

    /**
     * Exceptions thrown in virtual threads are caught, so we reach here only for exceptions thrown
     * in the continuation or virtual thread mechanisms themselves. Project Loom does nothing in
     * this handler, so we fail only if (system) assertions are on.
     *
     * NOTE: the caller silently ignores any exception thrown by this method.
     *
     * @see CarrierThread#onTermination
     */
    private static final Thread.UncaughtExceptionHandler UNCAUGHT_EXCEPTION_HANDLER = (t, e) -> {
        if (haveAssertions()) {
            throw VMError.shouldNotReachHere("Exception in continuation or virtual thread code", e);
        }
    };

    /**
     * A pool with the maximum number of threads so we can likely start a platform thread for each
     * virtual thread, which we might need when blocking I/O does not yield.
     */
    static final ForkJoinPool SCHEDULER = new ForkJoinPool(32767, CarrierThread::new, UNCAUGHT_EXCEPTION_HANDLER, true);

    private static SubstrateVirtualThread cast(Thread thread) {
        return (SubstrateVirtualThread) thread;
    }

    private static SubstrateVirtualThread current() {
        return (SubstrateVirtualThread) Thread.currentThread();
    }

    @Override
    public ThreadFactory createFactory() {
        return task -> new SubstrateVirtualThread(null, task);
    }

    @Override
    public boolean isVirtual(Thread thread) {
        return thread instanceof SubstrateVirtualThread;
    }

    @Override
    public boolean getAndClearInterrupt(Thread thread) {
        return cast(thread).getAndClearInterrupt();
    }

    @Override
    public void join(Thread thread, long millis) throws InterruptedException {
        if (thread.isAlive()) {
            long nanos = MILLISECONDS.toNanos(millis);
            ((SubstrateVirtualThread) thread).joinNanos(nanos);
        }
    }

    @Override
    public void yield() {
        current().tryYield();
    }

    @Override
    public void sleepMillis(long millis) throws InterruptedException {
        long nanos = TimeUnit.NANOSECONDS.convert(millis, TimeUnit.MILLISECONDS);
        current().sleepNanos(nanos);
    }

    @Override
    public boolean isAlive(Thread thread) {
        Thread.State state = thread.getState();
        return !(state == Thread.State.NEW || state == Thread.State.TERMINATED);
    }

    @Override
    public void unpark(Thread thread) {
        cast(thread).unpark(); // can throw RejectedExecutionException
    }

    @Override
    public void park() {
        current().park();
    }

    @Override
    public void parkNanos(long nanos) {
        current().parkNanos(nanos);
    }

    @Override
    public void parkUntil(long deadline) {
        current().parkUntil(deadline);
    }

    @Override
    public void pinCurrent() {
        current().pin();
    }

    @Override
    public void unpinCurrent() {
        current().unpin();
    }

    @Override
    public Executor getScheduler(Thread thread) {
        return cast(thread).getScheduler();
    }
}
