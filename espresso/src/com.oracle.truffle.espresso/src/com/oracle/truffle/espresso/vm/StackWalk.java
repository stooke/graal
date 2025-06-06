/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.truffle.espresso.vm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.espresso.classfile.descriptors.Signature;
import com.oracle.truffle.espresso.classfile.descriptors.Symbol;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols.Names;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols.Signatures;
import com.oracle.truffle.espresso.descriptors.EspressoSymbols.Types;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.nodes.EspressoRootNode;
import com.oracle.truffle.espresso.runtime.staticobject.StaticObject;
import com.oracle.truffle.espresso.substitutions.JavaType;
import com.oracle.truffle.espresso.substitutions.standard.Target_java_lang_invoke_MethodHandleNatives;

public final class StackWalk {
    // -1 and 0 are reserved values.
    private final AtomicLong walkerIds = new AtomicLong(1);

    /**
     * Contains frame walkers that are currently anchored (ie: the call to callStackWalk has not yet
     * returned).
     */
    private final Map<Long, FrameWalker> walkers = new ConcurrentHashMap<>();

    private static final long DEFAULT_MODE = 0x0;
    private static final long FILL_CLASS_REFS_ONLY = 0x2;
    private static final long GET_CALLER_CLASS = 0x04;
    private static final long SHOW_HIDDEN_FRAMES = 0x20;
    private static final long FILL_LIVE_STACK_FRAMES = 0x100;

    static boolean getCallerClass(long mode) {
        return (mode & GET_CALLER_CLASS) != 0;
    }

    static boolean skipHiddenFrames(long mode) {
        return (mode & SHOW_HIDDEN_FRAMES) == 0;
    }

    static boolean liveFrameInfo(long mode) {
        return (mode & FILL_LIVE_STACK_FRAMES) != 0;
    }

    static boolean needMethodInfo(long mode) {
        return (mode & FILL_CLASS_REFS_ONLY) == 0;
    }

    private static boolean synchronizedConstants(Meta meta) {
        Klass stackStreamFactory = meta.java_lang_StackStreamFactory;
        StaticObject statics = stackStreamFactory.tryInitializeAndGetStatics();
        assert DEFAULT_MODE == getConstantField(stackStreamFactory, statics, "DEFAULT_MODE", meta);
        if (meta.getJavaVersion().java21OrEarlier()) {
            assert FILL_CLASS_REFS_ONLY == getConstantField(stackStreamFactory, statics, "FILL_CLASS_REFS_ONLY", meta);
            assert GET_CALLER_CLASS == getConstantField(stackStreamFactory, statics, "GET_CALLER_CLASS", meta);
        } else {
            assert FILL_CLASS_REFS_ONLY == getConstantField(stackStreamFactory, statics, "CLASS_INFO_ONLY", meta);
        }
        assert SHOW_HIDDEN_FRAMES == getConstantField(stackStreamFactory, statics, "SHOW_HIDDEN_FRAMES", meta);
        assert FILL_LIVE_STACK_FRAMES == getConstantField(stackStreamFactory, statics, "FILL_LIVE_STACK_FRAMES", meta);
        return true;
    }

    private static int getConstantField(Klass stackStreamFactory, StaticObject statics, String name, Meta meta) {
        return stackStreamFactory.lookupDeclaredField(meta.getNames().getOrCreate(name), Types._int).getInt(statics);
    }

    public StackWalk() {
    }

    /**
     * initializes the stack walking, and anchors the Frame Walker instance to a particular frame
     * and fetches the first batch of frames requested by guest.
     * 
     * Upon return, unanchors the Frame Walker, and it is then not possible to continue walking for
     * this walker anymore.
     * 
     * @return The result of invoking guest
     *         {@code java.lang.StackStreamFactory.AbstractStackWalker#doStackWalk(long, int, int, int,
     *         int)} .
     */
    public StaticObject fetchFirstBatch(@JavaType(internalName = "Ljava/lang/StackStreamFactory;") StaticObject stackStream, long mode, int skipframes,
                    int batchSize, int startIndex,
                    @JavaType(Object[].class) StaticObject frames,
                    Meta meta) {
        assert synchronizedConstants(meta);
        FrameWalker fw = new FrameWalker(meta, mode);
        fw.init(skipframes, batchSize, startIndex);
        Integer decodedOrNull = fw.doStackWalk(frames);
        int decoded = decodedOrNull == null ? fw.decoded() : decodedOrNull;
        if (decoded < 1) {
            throw meta.throwException(meta.java_lang_InternalError);
        }
        register(fw);
        Object result = meta.java_lang_StackStreamFactory_AbstractStackWalker_doStackWalk.invokeDirectSpecial(
                        stackStream,
                        fw.anchor,
                        skipframes,
                        meta.getJavaVersion().java22OrLater() ? decoded : batchSize,
                        startIndex,
                        startIndex + decoded);
        unAnchor(fw);
        return (StaticObject) result;
    }

    /**
     * After {@link #fetchFirstBatch(StaticObject, long, int, int, int, StaticObject, Meta)}, this
     * method allows to continue frame walking, starting from where the previous calls left off.
     * 
     * @return
     *         <ul>
     *         <li>In Java < 22: The position in the buffer at the end of fetching.</li>
     *         <li>In Java >= 22: The number of fetched frames.</li>
     *         </ul>
     */
    public int fetchNextBatch(
                    @SuppressWarnings("unused") @JavaType(internalName = "Ljava/lang/StackStreamFactory;") StaticObject stackStream,
                    long mode, long anchor,
                    int batchSize, int startIndex,
                    @JavaType(Object[].class) StaticObject frames,
                    Meta meta) {
        assert synchronizedConstants(meta);
        FrameWalker fw = getAnchored(anchor);
        if (fw == null) {
            throw meta.throwExceptionWithMessage(meta.java_lang_InternalError, "doStackWalk: corrupted buffers");
        }
        if (batchSize <= 0) {
            return startIndex;
        }
        fw.next(batchSize, startIndex);
        fw.mode(mode);
        Integer decodedOrNull = fw.doStackWalk(frames);
        int decoded = decodedOrNull == null ? fw.decoded() : decodedOrNull;
        if (meta.getJavaVersion().java22OrLater()) {
            return decoded;
        } else {
            return startIndex + decoded;
        }
    }

    private void register(FrameWalker fw) {
        walkers.put(fw.anchor, fw);
    }

    private FrameWalker getAnchored(long id) {
        return walkers.get(id);
    }

    private void unAnchor(FrameWalker fw) {
        walkers.remove(fw.anchor);
    }

    class FrameWalker implements FrameInstanceVisitor<Integer> {

        protected final Meta meta;
        protected long mode;

        private volatile long anchor = -1;

        private int state = 0;
        private int from = 0;
        private int batchSize = 0;
        private int startIndex = 0;

        private StaticObject frames = StaticObject.NULL;
        private int depth = 0;
        private int decoded = 0;

        private static final int LOCATE_CALLSTACKWALK = 0;
        private static final int LOCATE_STACK_BEGIN = 1;
        private static final int LOCATE_FROM = 2;
        private static final int PROCESS = 3;
        private static final int HALT = 4;

        FrameWalker(Meta meta, long mode) {
            this.meta = meta;
            this.mode = mode;
        }

        public void anchor() {
            assert !isAnchored();
            anchor = walkerIds.getAndIncrement();
        }

        public boolean isAnchored() {
            return anchor > 0;
        }

        public int decoded() {
            return decoded;
        }

        public void clear() {
            state = LOCATE_CALLSTACKWALK;
            depth = 0;
            decoded = 0;
        }

        public void init(int skipFrames, int firstBatchSize, int firstStartIndex) {
            this.from = skipFrames;
            this.batchSize = firstBatchSize;
            this.startIndex = firstStartIndex;
        }

        public void next(int newBatchSize, int newStartIndex) {
            this.from = depth;
            this.batchSize = newBatchSize;
            this.startIndex = newStartIndex;
        }

        public void mode(long toSet) {
            this.mode = toSet;
        }

        /**
         * Since we restart the frame walking even when java requests a "continue", we need to
         * somehow keep around where in the last stack traversal we stopped. This is done in 3
         * steps:
         *
         * <li>Since the frames are anchored at the point where callStackWalk is called, we first
         * set the "frame iterator" at this particular point.
         *
         * <li>Once the frame iterator is set at callStackWalk, we unwind the stack until we find
         * the requester of the stack walking (in practice, that means skipping all methods from the
         * StackWalker API)
         *
         * <li>Once we found the caller, we then need to unwind the frames until where we left off
         * previously.
         */
        public Integer doStackWalk(StaticObject usedFrames) {
            clear();
            this.frames = usedFrames;
            Integer res = Truffle.getRuntime().iterateFrames(this);
            this.frames = StaticObject.NULL;
            return res;
        }

        private boolean isFromStackWalkingAPI(Method m) {
            return m.getDeclaringKlass() == meta.java_lang_StackWalker ||
                            m.getDeclaringKlass() == meta.java_lang_StackStreamFactory_AbstractStackWalker ||
                            m.getDeclaringKlass().getSuperKlass() == meta.java_lang_StackStreamFactory_AbstractStackWalker;
        }

        private boolean isCallStackWalk(Method m) {
            return m.getDeclaringKlass() == meta.java_lang_StackStreamFactory_AbstractStackWalker &&
                            Names.callStackWalk.equals(m.getName()) &&
                            getCallStackWalkSignature().equals(m.getRawSignature());
        }

        private Symbol<Signature> getCallStackWalkSignature() {
            if (meta.getJavaVersion().java22OrLater()) {
                return Signatures.Object_int_int_ContinuationScope_Continuation_int_int_Object_array;
            } else if (meta.getJavaVersion().java19OrLater()) {
                return Signatures.Object_long_int_ContinuationScope_Continuation_int_int_Object_array;
            } else {
                return Signatures.Object_long_int_int_int_Object_array;
            }
        }

        @SuppressWarnings("fallthrough")
        @Override
        public Integer visitFrame(FrameInstance frameInstance) {
            EspressoRootNode root = VM.getEspressoRootFromFrame(frameInstance, meta.getContext());
            Method m = root == null ? null : root.getMethod();
            if (m != null) {
                switch (state) {
                    case LOCATE_CALLSTACKWALK:
                        if (!isCallStackWalk(m)) {
                            break;
                        }
                        if (isAnchored()) {
                            if (root.readStackAnchorOrZero(frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY)) != anchor) {
                                break;
                            }
                        } else {
                            anchor();
                            root.setStackWalkAnchor(frameInstance.getFrame(FrameInstance.FrameAccess.READ_WRITE), anchor);
                        }
                        // Found callStackWalk: start unwinding StackWalker API
                        state = LOCATE_STACK_BEGIN;
                        // fallthrough
                    case LOCATE_STACK_BEGIN:
                        if (isFromStackWalkingAPI(m)) {
                            break;
                        }
                        // Found Caller: find where we left off.
                        state = LOCATE_FROM;
                        // fallthrough
                    case LOCATE_FROM:
                        if (depth < from) {
                            depth++;
                            break;
                        }
                        // Found where we left off: start processing.
                        state = PROCESS;
                        // fallthrough
                    case PROCESS:
                        if (decoded >= batchSize) {
                            // Done
                            state = HALT;
                            return decoded;
                        }
                        tryProcessFrame(frameInstance, m, startIndex + decoded);
                        depth++;
                        if (decoded >= batchSize) {
                            // Done
                            state = HALT;
                            return decoded;
                        }
                        break;
                    case HALT:
                    default:
                        throw EspressoError.shouldNotReachHere();
                }
            }
            return null;
        }

        private void tryProcessFrame(FrameInstance frameInstance, Method m, int index) {
            if (getCallerClass(mode) || skipHiddenFrames(mode)) {
                if (m.isHidden()) {
                    // Skip hidden frames.
                    return;
                }
            }
            if (!needMethodInfo(mode) && getCallerClass(mode) && (index == startIndex) && m.isCallerSensitive()) {
                throw meta.throwExceptionWithMessage(meta.java_lang_UnsupportedOperationException, "StackWalker::getCallerClass called from @CallerSensitive " + m.getNameAsString() + " method");
            }
            processFrame(frameInstance, m, index);
            decoded++;
        }

        private void processFrame(FrameInstance frameInstance, Method m, int index) {
            if (meta.getJavaVersion().java22OrLater()) {
                StaticObject info = frames.get(meta.getLanguage(), index);
                if (StaticObject.isNull(info) || !meta.java_lang_ClassFrameInfo.isAssignableFrom(info.getKlass())) {
                    throw meta.throwException(meta.java_lang_InternalError);
                }
                int flags = getFlags(meta.java_lang_ClassFrameInfo_flags.getInt(info), m);
                meta.java_lang_ClassFrameInfo_flags.setInt(info, flags);
                meta.java_lang_ClassFrameInfo_classOrMemberName.setObject(info, m.getDeclaringKlass().mirror());
                if (needMethodInfo(mode)) {
                    // Will override classOrMemberName
                    fillFrame(frameInstance, m, info);
                }
                if (liveFrameInfo(mode)) {
                    // TODO: extract stack, locals and monitors from the frame.
                    throw EspressoError.unimplemented("Live frame info for stack walk");
                }
            } else {
                if (liveFrameInfo(mode)) {
                    fillFrame(frameInstance, m, frames.get(meta.getLanguage(), index));
                    // TODO: extract stack, locals and monitors from the frame.
                    throw EspressoError.unimplemented("Live frame info for stack walk");
                } else if (needMethodInfo(mode)) {
                    fillFrame(frameInstance, m, frames.get(meta.getLanguage(), index));
                } else {
                    // Only class info is needed.
                    Klass klass = m.getDeclaringKlass();
                    meta.getInterpreterToVM().setArrayObject(meta.getLanguage(), klass.mirror(), index, frames);
                }
            }
        }

        /**
         * Initializes the {@code java.lang.StackFrameInfo} in the {@link #frames} array at index
         * {@code index}. This means initializing the associated {@code java.lang.invoke.MemberName}
         * , and injecting a BCI.
         */
        private void fillFrame(FrameInstance frameInstance, Method m, StaticObject frame) {
            if (StaticObject.isNull(frame) || !meta.java_lang_StackFrameInfo.isAssignableFrom(frame.getKlass())) {
                throw meta.throwException(meta.java_lang_InternalError);
            }
            StaticObject memberName;
            if (meta.getJavaVersion().java22OrLater()) {
                memberName = meta.java_lang_invoke_ResolvedMethodName.allocateInstance(meta.getContext());
                meta.HIDDEN_VM_METHOD.setHiddenObject(memberName, m);
                meta.java_lang_invoke_ResolvedMethodName_vmholder.setObject(memberName, m.getDeclaringKlass().mirror());
                meta.java_lang_ClassFrameInfo_classOrMemberName.setObject(frame, memberName);
            } else {
                memberName = meta.java_lang_StackFrameInfo_memberName.getObject(frame);
                Target_java_lang_invoke_MethodHandleNatives.plantResolvedMethod(memberName, m, m.getRefKind(), meta);
                meta.java_lang_invoke_MemberName_clazz.setObject(memberName, m.getDeclaringKlass().mirror());
            }
            EspressoRootNode rootNode = VM.getEspressoRootFromFrame(frameInstance, meta.getContext());
            meta.java_lang_StackFrameInfo_bci.setInt(frame, rootNode.readBCI(frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY)));
        }
    }

    private static int getFlags(int baseFlags, Method m) {
        int flags = baseFlags;
        if (m.isConstructor()) {
            flags |= Target_java_lang_invoke_MethodHandleNatives.Constants.MN_IS_CONSTRUCTOR;
        } else {
            flags |= Target_java_lang_invoke_MethodHandleNatives.Constants.MN_IS_METHOD;
        }
        if (m.isHidden()) {
            flags |= Target_java_lang_invoke_MethodHandleNatives.Constants.MN_HIDDEN_MEMBER;
        }
        if (m.isCallerSensitive()) {
            flags |= Target_java_lang_invoke_MethodHandleNatives.Constants.MN_CALLER_SENSITIVE;
        }
        return flags;
    }
}
