/*
 * Copyright (c) 2013, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.impl;

import java.lang.reflect.Field;
import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

import sun.misc.Unsafe;

/**
 * More efficient implementation of the Truffle frame that has no safety checks for frame accesses
 * and therefore is much faster. Should not be used during debugging as potential misuses of the
 * frame object would show up very late and would be hard to identify.
 *
 * For host compilation all final instance fields of this case are treated as immutable in the
 * Compiler IR. This allows frame array reads to move out of loops even if there are side-effects in
 * them. In order to guarantee this, the frame must not escape a reference of <code>this</code> in
 * the constructor to any other call.
 */
public final class FrameWithoutBoxing implements VirtualFrame, MaterializedFrame {
    private static final String UNEXPECTED_STATIC_WRITE = "Unexpected static write of non-static frame slot";
    private static final String UNEXPECTED_NON_STATIC_READ = "Unexpected non-static read of static frame slot";
    private static final String UNEXPECTED_NON_STATIC_WRITE = "Unexpected non-static write of static frame slot";

    private static final boolean ASSERTIONS_ENABLED;

    private final FrameDescriptor descriptor;
    private final Object[] arguments;

    private final Object[] indexedLocals;
    private final long[] indexedPrimitiveLocals;
    private final byte[] indexedTags;

    private Object[] auxiliarySlots;

    private static final Object OBJECT_LOCATION = new Object();
    private static final Object PRIMITIVE_LOCATION = new Object();

    private static final long INT_MASK = 0xFFFFFFFFL;

    /*
     * Changing these constants implies changes in NewFrameNode.java as well:
     */
    public static final byte OBJECT_TAG = 0;
    public static final byte LONG_TAG = 1;
    public static final byte INT_TAG = 2;
    public static final byte DOUBLE_TAG = 3;
    public static final byte FLOAT_TAG = 4;
    public static final byte BOOLEAN_TAG = 5;
    public static final byte BYTE_TAG = 6;
    public static final byte ILLEGAL_TAG = 7;
    public static final byte STATIC_TAG = 8;

    private static final byte STATIC_OBJECT_TAG = STATIC_TAG | OBJECT_TAG;
    private static final byte STATIC_LONG_TAG = STATIC_TAG | LONG_TAG;
    private static final byte STATIC_INT_TAG = STATIC_TAG | INT_TAG;
    private static final byte STATIC_DOUBLE_TAG = STATIC_TAG | DOUBLE_TAG;
    private static final byte STATIC_FLOAT_TAG = STATIC_TAG | FLOAT_TAG;
    private static final byte STATIC_BOOLEAN_TAG = STATIC_TAG | BOOLEAN_TAG;
    private static final byte STATIC_BYTE_TAG = STATIC_TAG | BYTE_TAG;
    private static final byte STATIC_ILLEGAL_TAG = STATIC_TAG | ILLEGAL_TAG;

    private static final Object[] EMPTY_OBJECT_ARRAY = {};
    private static final long[] EMPTY_LONG_ARRAY = {};
    private static final byte[] EMPTY_BYTE_ARRAY = {};

    private static final Unsafe UNSAFE = initUnsafe();
    static {
        assert OBJECT_TAG == FrameSlotKind.Object.tag;
        assert ILLEGAL_TAG == FrameSlotKind.Illegal.tag;
        assert LONG_TAG == FrameSlotKind.Long.tag;
        assert INT_TAG == FrameSlotKind.Int.tag;
        assert DOUBLE_TAG == FrameSlotKind.Double.tag;
        assert FLOAT_TAG == FrameSlotKind.Float.tag;
        assert BOOLEAN_TAG == FrameSlotKind.Boolean.tag;
        assert BYTE_TAG == FrameSlotKind.Byte.tag;
        assert STATIC_TAG == FrameSlotKind.Static.tag;

        ASSERTIONS_ENABLED = areAssertionsEnabled();
    }

    private static final Object ILLEGAL_DEFAULT = ImplAccessor.frameSupportAccessor().getIllegalDefault();

    @SuppressWarnings("all")
    private static boolean areAssertionsEnabled() {
        boolean enabled = false;
        assert enabled = true;
        return enabled;
    }

    private static Unsafe initUnsafe() {
        try {
            // Fast path when we are trusted.
            return Unsafe.getUnsafe();
        } catch (SecurityException se) {
            // Slow path when we are not trusted.
            try {
                Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return (Unsafe) theUnsafe.get(Unsafe.class);
            } catch (Exception e) {
                throw new RuntimeException("exception while trying to get Unsafe", e);
            }
        }
    }

    public FrameWithoutBoxing(FrameDescriptor descriptor, Object[] arguments) {
        // Make sure the state of ASSERTIONS_ENABLED matches with
        // the state of assertions at runtime
        // This can be an issue since this class is initialized at build time in native-image
        assert ASSERTIONS_ENABLED;
        /*
         * Important note: Make sure this frame reference does not escape to any other method in
         * this constructor, otherwise the immutable invariant for frame final fields may not hold.
         * This may lead to very hard to debug bugs.
         */
        final int indexedSize = descriptor.getNumberOfSlots();
        final int auxiliarySize = descriptor.getNumberOfAuxiliarySlots();
        Object defaultValue = descriptor.getDefaultValue();
        final Object[] indexedLocalsArray;
        final long[] indexedPrimitiveLocalsArray;
        final byte[] indexedTagsArray;
        final Object[] auxiliarySlotsArray;
        if (indexedSize == 0) {
            indexedLocalsArray = EMPTY_OBJECT_ARRAY;
            indexedPrimitiveLocalsArray = EMPTY_LONG_ARRAY;
            indexedTagsArray = EMPTY_BYTE_ARRAY;
        } else {
            indexedLocalsArray = new Object[indexedSize];
            indexedPrimitiveLocalsArray = new long[indexedSize];
            // Do not initialize tags, even for static slots. In practice, this means that it is
            // possible to statically access uninitialized slots.
            indexedTagsArray = new byte[indexedSize];
            if (defaultValue == ILLEGAL_DEFAULT) {
                Arrays.fill(indexedTagsArray, ILLEGAL_TAG);
            } else if (defaultValue != null) {
                Arrays.fill(indexedLocalsArray, defaultValue);
            }
        }
        if (auxiliarySize == 0) {
            auxiliarySlotsArray = EMPTY_OBJECT_ARRAY;
        } else {
            auxiliarySlotsArray = new Object[auxiliarySize];
        }
        this.descriptor = descriptor;
        this.arguments = arguments;
        this.indexedLocals = indexedLocalsArray;
        this.indexedPrimitiveLocals = indexedPrimitiveLocalsArray;
        this.indexedTags = indexedTagsArray;
        this.auxiliarySlots = auxiliarySlotsArray;
    }

    /* Currently only used by the debugger to drop a frame. */
    void reset() {
        Object defaultValue = descriptor.getDefaultValue();
        byte defaultTag;
        if (defaultValue == ILLEGAL_DEFAULT) {
            defaultTag = ILLEGAL_TAG;
            defaultValue = null; // ILLEGAL_DEFAULT must never be written as default
        } else {
            defaultTag = OBJECT_TAG;
        }
        Arrays.fill(this.indexedTags, defaultTag);
        Arrays.fill(this.indexedLocals, defaultValue);
        Arrays.fill(this.indexedPrimitiveLocals, 0L);
        Arrays.fill(this.auxiliarySlots, null);
    }

    @Override
    public Object[] getArguments() {
        return unsafeCast(this.arguments, Object[].class, true, true, true);
    }

    @Override
    public FrameWithoutBoxing materialize() {
        ImplAccessor.frameSupportAccessor().markMaterializeCalled(descriptor);
        return this;
    }

    /** Intrinsic candidate. */
    private static long extend(int value) {
        return value & INT_MASK;
    }

    /** Intrinsic candidate. */
    private static int narrow(long value) {
        return (int) value;
    }

    @Override
    public FrameDescriptor getFrameDescriptor() {
        return unsafeCast(this.descriptor, FrameDescriptor.class, true, true, false);
    }

    private static FrameSlotTypeException frameSlotTypeException(int slot, byte expectedTag, byte actualTag) throws FrameSlotTypeException {
        throw FrameSlotTypeException.create(slot, FrameSlotKind.fromTag(expectedTag), FrameSlotKind.fromTag(actualTag));
    }

    private static long getObjectOffset(int slotIndex) {
        return Unsafe.ARRAY_OBJECT_BASE_OFFSET + slotIndex * (long) Unsafe.ARRAY_OBJECT_INDEX_SCALE;
    }

    private static long getPrimitiveOffset(int slotIndex) {
        return Unsafe.ARRAY_LONG_BASE_OFFSET + slotIndex * (long) Unsafe.ARRAY_LONG_INDEX_SCALE;
    }

    @Override
    public byte getTag(int slotIndex) {
        // this may raise an AIOOBE
        final byte tag = getIndexedTags()[slotIndex];
        return tag < STATIC_TAG ? tag : STATIC_TAG;
    }

    private boolean isNonStaticType(int slotIndex, byte tag) {
        assert !isStatic(slotIndex) : "Using isType on static slots is to be avoided.";
        return getIndexedTags()[slotIndex] == tag;
    }

    byte unsafeGetTag(int slotIndex) {
        return unsafeGetIndexedTag(slotIndex);
    }

    /**
     * Intrinsifiable compiler directive to tighten the type information for {@code value}. This
     * method is intrinsified for host and guest compilation. If used in the form
     * unsafeCast(this.field, ...) and the field is a final field, then the field will get
     * transformed into an immutable field read.
     *
     * @param type the type the compiler should assume for {@code value}
     * @param condition the condition that guards the assumptions expressed by this directive
     * @param nonNull the nullness info the compiler should assume for {@code value}
     * @param exact if {@code true}, the compiler should assume exact type info
     */
    @SuppressWarnings({"unchecked", "unused"})
    private static <T> T unsafeCast(Object value, Class<T> type, boolean condition, boolean nonNull, boolean exact) {
        return (T) value;
    }

    @SuppressWarnings("unused")
    private static long unsafeGetLong(Object receiver, long offset, boolean condition, Object locationIdentity) {
        return UNSAFE.getLong(receiver, offset);
    }

    @SuppressWarnings("unused")
    private static int unsafeGetLongAndNarrowInt(Object receiver, long offset, boolean condition, Object locationIdentity) {
        if (CompilerDirectives.inCompiledCode()) {
            /*
             * narrow is intrinsified in PE code and must be explicitly handled.
             */
            return narrow(unsafeGetLong(receiver, offset, condition, locationIdentity));
        } else {
            /*
             * In the interpreter we read directly with unsafe to ensure setInt(getInt()) does not
             * produce a narrow and zero extend node.
             */
            return UNSAFE.getInt(receiver, offset);
        }
    }

    @SuppressWarnings("unused")
    private static byte unsafeGetLongAndNarrowByte(Object receiver, long offset, boolean condition, Object locationIdentity) {
        if (CompilerDirectives.inCompiledCode()) {
            /*
             * narrow is intrinsified in PE code and must be explicitly handled.
             */
            return (byte) narrow(unsafeGetLong(receiver, offset, condition, locationIdentity));
        } else {
            /*
             * In the interpreter we read directly with unsafe to ensure setInt(getInt()) does not
             * produce a narrow and zero extend node.
             */
            return UNSAFE.getByte(receiver, offset);
        }
    }

    @SuppressWarnings("unused")
    private static Object unsafeGetObject(Object receiver, long offset, boolean condition, Object locationIdentity) {
        return UNSAFE.getObject(receiver, offset);
    }

    @SuppressWarnings("unused")
    private static void unsafePutLong(Object receiver, long offset, long value, Object locationIdentity) {
        UNSAFE.putLong(receiver, offset, value);
    }

    private static void unsafePutLongAndExtendByte(Object receiver, long offset, byte value, Object locationIdentity) {
        if (CompilerDirectives.inCompiledCode()) {
            unsafePutLong(receiver, offset, extend(value), locationIdentity);
        } else {
            UNSAFE.putByte(receiver, offset, value);
        }
    }

    private static void unsafePutLongAndExtendInt(Object receiver, long offset, int value, Object locationIdentity) {
        if (CompilerDirectives.inCompiledCode()) {
            unsafePutLong(receiver, offset, extend(value), locationIdentity);
        } else {
            UNSAFE.putInt(receiver, offset, value);
        }
    }

    @SuppressWarnings("unused")
    private static void unsafePutObject(Object receiver, long offset, Object value, Object locationIdentity) {
        UNSAFE.putObject(receiver, offset, value);
    }

    @Override
    public Object getValue(int slot) {
        byte tag = getTag(slot);
        assert !isStatic(slot) : UNEXPECTED_NON_STATIC_READ;
        switch (tag) {
            case BOOLEAN_TAG:
                return getBoolean(slot);
            case BYTE_TAG:
                return getByte(slot);
            case INT_TAG:
                return getInt(slot);
            case DOUBLE_TAG:
                return getDouble(slot);
            case LONG_TAG:
                return getLong(slot);
            case FLOAT_TAG:
                return getFloat(slot);
            case OBJECT_TAG:
                return getObject(slot);
            case ILLEGAL_TAG:
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw frameSlotTypeException(slot, OBJECT_TAG, tag);
            default:
                throw CompilerDirectives.shouldNotReachHere();
        }
    }

    private Object[] getIndexedLocals() {
        return unsafeCast(this.indexedLocals, Object[].class, true, true, true);
    }

    private long[] getIndexedPrimitiveLocals() {
        return unsafeCast(this.indexedPrimitiveLocals, long[].class, true, true, true);
    }

    private byte[] getIndexedTags() {
        return unsafeCast(this.indexedTags, byte[].class, true, true, true);
    }

    @Override
    public Object getObject(int slot) throws FrameSlotTypeException {
        boolean condition = verifyIndexedGet(slot, OBJECT_TAG);
        return unsafeGetObject(getIndexedLocals(), getObjectOffset(slot), condition, OBJECT_LOCATION);
    }

    @Override
    public Object expectObject(int slot) throws UnexpectedResultException {
        boolean condition = verifyIndexedGetUnexpected(slot, OBJECT_TAG);
        return unsafeGetObject(getIndexedLocals(), getObjectOffset(slot), condition, OBJECT_LOCATION);
    }

    Object unsafeGetObject(int slot) throws FrameSlotTypeException {
        boolean condition = unsafeVerifyIndexedGet(slot, OBJECT_TAG);
        return unsafeGetObject(getIndexedLocals(), getObjectOffset(slot), condition, OBJECT_LOCATION);
    }

    Object unsafeUncheckedGetObject(int slot) {
        assert getIndexedTagChecked(slot) == OBJECT_TAG;
        return unsafeGetObject(getIndexedLocals(), getObjectOffset(slot), true, OBJECT_LOCATION);
    }

    Object unsafeExpectObject(int slot) throws UnexpectedResultException {
        boolean condition = unsafeVerifyIndexedGetUnexpected(slot, OBJECT_TAG);
        return unsafeGetObject(getIndexedLocals(), getObjectOffset(slot), condition, OBJECT_LOCATION);
    }

    @Override
    public void setObject(int slot, Object value) {
        verifyIndexedSet(slot, OBJECT_TAG);
        unsafePutObject(getIndexedLocals(), getObjectOffset(slot), value, OBJECT_LOCATION);
    }

    void unsafeSetObject(int slot, Object value) throws FrameSlotTypeException {
        unsafeVerifyIndexedSet(slot, OBJECT_TAG);
        unsafePutObject(getIndexedLocals(), getObjectOffset(slot), value, OBJECT_LOCATION);
    }

    @Override
    public byte getByte(int slot) throws FrameSlotTypeException {
        boolean condition = verifyIndexedGet(slot, BYTE_TAG);
        return unsafeGetLongAndNarrowByte(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    @Override
    public byte expectByte(int slot) throws UnexpectedResultException {
        boolean condition = verifyIndexedGetUnexpected(slot, BYTE_TAG);
        return unsafeGetLongAndNarrowByte(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    byte unsafeGetByte(int slot) throws FrameSlotTypeException {
        boolean condition = unsafeVerifyIndexedGet(slot, BYTE_TAG);
        return unsafeGetLongAndNarrowByte(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    byte unsafeExpectByte(int slot) throws UnexpectedResultException {
        boolean condition = unsafeVerifyIndexedGetUnexpected(slot, BYTE_TAG);
        return unsafeGetLongAndNarrowByte(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    @Override
    public void setByte(int slot, byte value) {
        verifyIndexedSet(slot, BYTE_TAG);
        unsafePutLongAndExtendByte(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), value, PRIMITIVE_LOCATION);
    }

    void unsafeSetByte(int slot, byte value) {
        unsafeVerifyIndexedSet(slot, BYTE_TAG);
        unsafePutLongAndExtendByte(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), value, PRIMITIVE_LOCATION);
    }

    @Override
    public boolean getBoolean(int slot) throws FrameSlotTypeException {
        boolean condition = verifyIndexedGet(slot, BOOLEAN_TAG);
        return unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION) != 0;
    }

    public boolean expectBoolean(int slot) throws UnexpectedResultException {
        boolean condition = verifyIndexedGetUnexpected(slot, BOOLEAN_TAG);
        return unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION) != 0;
    }

    boolean unsafeExpectBoolean(int slot) throws UnexpectedResultException {
        boolean condition = unsafeVerifyIndexedGetUnexpected(slot, BOOLEAN_TAG);
        return unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION) != 0;
    }

    boolean unsafeGetBoolean(int slot) throws FrameSlotTypeException {
        boolean condition = unsafeVerifyIndexedGet(slot, BOOLEAN_TAG);
        return unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION) != 0;
    }

    @Override
    public void setBoolean(int slot, boolean value) {
        verifyIndexedSet(slot, BOOLEAN_TAG);
        unsafePutLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), extend(value ? 1 : 0), PRIMITIVE_LOCATION);
    }

    void unsafeSetBoolean(int slot, boolean value) {
        unsafeVerifyIndexedSet(slot, BOOLEAN_TAG);
        unsafePutLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), value ? 1L : 0L, PRIMITIVE_LOCATION);
    }

    @Override
    public float getFloat(int slot) throws FrameSlotTypeException {
        boolean condition = verifyIndexedGet(slot, FLOAT_TAG);
        return Float.intBitsToFloat(unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION));
    }

    @Override
    public float expectFloat(int slot) throws UnexpectedResultException {
        boolean condition = verifyIndexedGetUnexpected(slot, FLOAT_TAG);
        return Float.intBitsToFloat(unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION));
    }

    float unsafeExpectFloat(int slot) throws UnexpectedResultException {
        boolean condition = unsafeVerifyIndexedGetUnexpected(slot, FLOAT_TAG);
        return Float.intBitsToFloat(unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION));
    }

    float unsafeGetFloat(int slot) throws FrameSlotTypeException {
        boolean condition = unsafeVerifyIndexedGet(slot, FLOAT_TAG);
        return Float.intBitsToFloat(unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION));
    }

    @Override
    public void setFloat(int slot, float value) {
        verifyIndexedSet(slot, FLOAT_TAG);
        unsafePutLongAndExtendInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), Float.floatToRawIntBits(value), PRIMITIVE_LOCATION);
    }

    void unsafeSetFloat(int slot, float value) {
        unsafeVerifyIndexedSet(slot, FLOAT_TAG);
        unsafePutLongAndExtendInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), Float.floatToRawIntBits(value), PRIMITIVE_LOCATION);
    }

    @Override
    public long getLong(int slot) throws FrameSlotTypeException {
        boolean condition = verifyIndexedGet(slot, LONG_TAG);
        return unsafeGetLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    @Override
    public long expectLong(int slot) throws UnexpectedResultException {
        boolean condition = verifyIndexedGetUnexpected(slot, LONG_TAG);
        return unsafeGetLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    long unsafeGetLong(int slot) throws FrameSlotTypeException {
        boolean condition = unsafeVerifyIndexedGet(slot, LONG_TAG);
        return unsafeGetLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    long unsafeExpectLong(int slot) throws UnexpectedResultException {
        boolean condition = unsafeVerifyIndexedGetUnexpected(slot, LONG_TAG);
        return unsafeGetLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    @Override
    public void setLong(int slot, long value) {
        verifyIndexedSet(slot, LONG_TAG);
        unsafePutLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), value, PRIMITIVE_LOCATION);
    }

    void unsafeSetLong(int slot, long value) {
        unsafeVerifyIndexedSet(slot, LONG_TAG);
        unsafePutLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), value, PRIMITIVE_LOCATION);
    }

    @Override
    public int getInt(int slot) throws FrameSlotTypeException {
        boolean condition = verifyIndexedGet(slot, INT_TAG);
        return unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    @Override
    public int expectInt(int slot) throws UnexpectedResultException {
        boolean condition = verifyIndexedGetUnexpected(slot, INT_TAG);
        return unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    int unsafeGetInt(int slot) throws FrameSlotTypeException {
        boolean condition = unsafeVerifyIndexedGet(slot, INT_TAG);
        return unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    int unsafeExpectInt(int slot) throws UnexpectedResultException {
        boolean condition = unsafeVerifyIndexedGetUnexpected(slot, INT_TAG);
        return unsafeGetLongAndNarrowInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION);
    }

    @Override
    public void setInt(int slot, int value) {
        verifyIndexedSet(slot, INT_TAG);
        unsafePutLongAndExtendInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), value, PRIMITIVE_LOCATION);
    }

    void unsafeSetInt(int slot, int value) {
        unsafeVerifyIndexedSet(slot, INT_TAG);
        unsafePutLongAndExtendInt(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), value, PRIMITIVE_LOCATION);
    }

    @Override
    public double getDouble(int slot) throws FrameSlotTypeException {
        boolean condition = verifyIndexedGet(slot, DOUBLE_TAG);
        return Double.longBitsToDouble(unsafeGetLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION));
    }

    @Override
    public double expectDouble(int slot) throws UnexpectedResultException {
        boolean condition = verifyIndexedGetUnexpected(slot, DOUBLE_TAG);
        return Double.longBitsToDouble(unsafeGetLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION));
    }

    double unsafeGetDouble(int slot) throws FrameSlotTypeException {
        boolean condition = unsafeVerifyIndexedGet(slot, DOUBLE_TAG);
        return Double.longBitsToDouble(unsafeGetLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION));
    }

    double unsafeExpectDouble(int slot) throws UnexpectedResultException {
        boolean condition = unsafeVerifyIndexedGetUnexpected(slot, DOUBLE_TAG);
        return Double.longBitsToDouble(unsafeGetLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), condition, PRIMITIVE_LOCATION));
    }

    @Override
    public void setDouble(int slot, double value) {
        verifyIndexedSet(slot, DOUBLE_TAG);
        unsafePutLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), Double.doubleToRawLongBits(value), PRIMITIVE_LOCATION);
    }

    void unsafeSetDouble(int slot, double value) {
        unsafeVerifyIndexedSet(slot, DOUBLE_TAG);
        unsafePutLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), Double.doubleToRawLongBits(value), PRIMITIVE_LOCATION);
    }

    @Override
    public void copy(int srcSlot, int destSlot) {
        byte tag = getIndexedTagChecked(srcSlot);
        final Object[] referenceLocals = getIndexedLocals();
        final long[] primitiveLocals = getIndexedPrimitiveLocals();
        Object value = unsafeGetObject(referenceLocals, getObjectOffset(srcSlot), true, OBJECT_LOCATION);
        verifyIndexedSet(destSlot, tag);
        unsafePutObject(referenceLocals, getObjectOffset(destSlot), value, OBJECT_LOCATION);
        long primitiveValue = unsafeGetLong(primitiveLocals, getPrimitiveOffset(srcSlot), true, PRIMITIVE_LOCATION);
        unsafePutLong(primitiveLocals, getPrimitiveOffset(destSlot), primitiveValue, PRIMITIVE_LOCATION);
    }

    void unsafeCopy(int srcSlot, int destSlot) {
        byte tag = unsafeGetIndexedTag(srcSlot);
        final Object[] referenceLocals = getIndexedLocals();
        final long[] primitiveLocals = getIndexedPrimitiveLocals();
        Object value = unsafeGetObject(referenceLocals, getObjectOffset(srcSlot), true, OBJECT_LOCATION);
        unsafeVerifyIndexedSet(destSlot, tag);
        unsafePutObject(referenceLocals, getObjectOffset(destSlot), value, OBJECT_LOCATION);
        long primitiveValue = unsafeGetLong(primitiveLocals, getPrimitiveOffset(srcSlot), true, PRIMITIVE_LOCATION);
        unsafePutLong(primitiveLocals, getPrimitiveOffset(destSlot), primitiveValue, PRIMITIVE_LOCATION);
    }

    @Override
    public void swap(int first, int second) {
        final Object[] referenceLocals = getIndexedLocals();
        final long[] primitiveLocals = getIndexedPrimitiveLocals();

        byte firstTag = getIndexedTagChecked(first);
        Object firstValue = unsafeGetObject(referenceLocals, getObjectOffset(first), true, OBJECT_LOCATION);
        long firstPrimitiveValue = unsafeGetLong(primitiveLocals, getPrimitiveOffset(first), true, PRIMITIVE_LOCATION);

        byte secondTag = getIndexedTagChecked(second);
        Object secondValue = unsafeGetObject(referenceLocals, getObjectOffset(second), true, OBJECT_LOCATION);
        long secondPrimitiveValue = unsafeGetLong(primitiveLocals, getPrimitiveOffset(second), true, PRIMITIVE_LOCATION);

        verifyIndexedSet(first, secondTag);
        verifyIndexedSet(second, firstTag);
        unsafePutObject(referenceLocals, getObjectOffset(first), secondValue, OBJECT_LOCATION);
        unsafePutLong(primitiveLocals, getPrimitiveOffset(first), secondPrimitiveValue, PRIMITIVE_LOCATION);
        unsafePutObject(referenceLocals, getObjectOffset(second), firstValue, OBJECT_LOCATION);
        unsafePutLong(primitiveLocals, getPrimitiveOffset(second), firstPrimitiveValue, PRIMITIVE_LOCATION);
    }

    private void verifyIndexedSet(int slot, byte tag) {
        assert (getIndexedTags()[slot] & STATIC_TAG) == 0 : UNEXPECTED_NON_STATIC_WRITE;
        // this may raise an AIOOBE
        getIndexedTags()[slot] = tag;
    }

    private void unsafeVerifyIndexedSet(int slot, byte tag) {
        assert getIndexedTags()[slot] != STATIC_TAG : UNEXPECTED_NON_STATIC_WRITE;
        UNSAFE.putByte(getIndexedTags(), Unsafe.ARRAY_BYTE_BASE_OFFSET + slot * Unsafe.ARRAY_BYTE_INDEX_SCALE, tag);
    }

    private boolean verifyIndexedGetUnexpected(int slot, byte expectedTag) throws UnexpectedResultException {
        byte actualTag = getIndexedTagChecked(slot);
        boolean condition = actualTag == expectedTag;
        if (!condition) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw unexpectedValue(slot);
        }
        return condition;
    }

    private UnexpectedResultException unexpectedValue(int slot) throws UnexpectedResultException {
        throw new UnexpectedResultException(getValue(slot));
    }

    private boolean verifyIndexedGet(int slot, byte expectedTag) throws FrameSlotTypeException {
        byte actualTag = getIndexedTagChecked(slot);
        boolean condition = actualTag == expectedTag;
        if (!condition) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw frameSlotTypeException(slot, expectedTag, actualTag);
        }
        return condition;
    }

    private boolean unsafeVerifyIndexedGet(int slot, byte expectedTag) throws FrameSlotTypeException {
        byte actualTag = unsafeGetIndexedTag(slot);
        boolean condition = actualTag == expectedTag;
        if (!condition) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw frameSlotTypeException(slot, expectedTag, actualTag);
        }
        return condition;
    }

    private boolean unsafeVerifyIndexedGetUnexpected(int slot, byte expectedTag) throws UnexpectedResultException {
        byte actualTag = unsafeGetIndexedTag(slot);
        boolean condition = actualTag == expectedTag;
        if (!condition) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw unexpectedValue(slot);
        }
        return condition;
    }

    private byte getIndexedTagChecked(int slot) {
        // this may raise an AIOOBE
        byte tag = getIndexedTags()[slot];
        assert (tag & STATIC_TAG) == 0 : UNEXPECTED_NON_STATIC_READ;
        return tag;
    }

    private byte unsafeGetIndexedTag(int slot) {
        assert getIndexedTags()[slot] >= 0;
        byte tag = UNSAFE.getByte(getIndexedTags(), Unsafe.ARRAY_BYTE_BASE_OFFSET + slot * Unsafe.ARRAY_BYTE_INDEX_SCALE);
        assert (tag & STATIC_TAG) == 0 : UNEXPECTED_NON_STATIC_READ;
        return tag;
    }

    @Override
    public boolean isObject(int slot) {
        return isNonStaticType(slot, OBJECT_TAG);
    }

    @Override
    public boolean isByte(int slot) {
        return isNonStaticType(slot, BYTE_TAG);
    }

    @Override
    public boolean isBoolean(int slot) {
        return isNonStaticType(slot, BOOLEAN_TAG);
    }

    @Override
    public boolean isInt(int slot) {
        return isNonStaticType(slot, INT_TAG);
    }

    @Override
    public boolean isLong(int slot) {
        return isNonStaticType(slot, LONG_TAG);
    }

    @Override
    public boolean isFloat(int slot) {
        return isNonStaticType(slot, FLOAT_TAG);
    }

    @Override
    public boolean isDouble(int slot) {
        return isNonStaticType(slot, DOUBLE_TAG);
    }

    @Override
    public boolean isStatic(int slot) {
        // Frame descriptor holds the definitive answer.
        return getFrameDescriptor().getSlotKind(slot) == FrameSlotKind.Static;
    }

    @Override
    public void clear(int slot) {
        verifyIndexedSet(slot, ILLEGAL_TAG);
        unsafePutObject(getIndexedLocals(), getObjectOffset(slot), null, OBJECT_LOCATION);
        if (CompilerDirectives.inCompiledCode()) {
            unsafePutLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), 0L, PRIMITIVE_LOCATION);
        }
    }

    void unsafeClear(int slot) {
        unsafeVerifyIndexedSet(slot, ILLEGAL_TAG);
        unsafePutObject(getIndexedLocals(), getObjectOffset(slot), null, OBJECT_LOCATION);
        if (CompilerDirectives.inCompiledCode()) {
            unsafePutLong(getIndexedPrimitiveLocals(), getPrimitiveOffset(slot), 0L, PRIMITIVE_LOCATION);
        }
    }

    @Override
    public void setAuxiliarySlot(int slot, Object value) {
        if (auxiliarySlots.length <= slot) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            auxiliarySlots = Arrays.copyOf(auxiliarySlots, descriptor.getNumberOfAuxiliarySlots());
        }
        auxiliarySlots[slot] = value;
    }

    @Override
    public Object getAuxiliarySlot(int slot) {
        return slot < auxiliarySlots.length ? auxiliarySlots[slot] : null;
    }

    @Override
    public Object getObjectStatic(int slot) {
        assert checkStaticGet(slot, STATIC_OBJECT_TAG) : "Unexpected read of static object value";

        return getIndexedLocals()[slot];
    }

    @Override
    public void setObjectStatic(int slot, Object value) {
        assert checkStatic(slot) : UNEXPECTED_STATIC_WRITE;
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[slot] = STATIC_OBJECT_TAG;
        }

        getIndexedLocals()[slot] = value;
    }

    @Override
    public byte getByteStatic(int slot) {
        assert checkStaticGet(slot, STATIC_BYTE_TAG) : "Unexpected read of static byte value";

        return (byte) narrow(getIndexedPrimitiveLocals()[slot]);
    }

    @Override
    public void setByteStatic(int slot, byte value) {
        assert checkStatic(slot) : UNEXPECTED_STATIC_WRITE;
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[slot] = STATIC_BYTE_TAG;
        }

        getIndexedPrimitiveLocals()[slot] = extend(value);
    }

    @Override
    public boolean getBooleanStatic(int slot) {
        assert checkStaticGet(slot, STATIC_BOOLEAN_TAG) : "Unexpected read of static boolean value";

        return narrow(getIndexedPrimitiveLocals()[slot]) != 0;
    }

    @Override
    public void setBooleanStatic(int slot, boolean value) {
        assert checkStatic(slot) : UNEXPECTED_STATIC_WRITE;
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[slot] = STATIC_BOOLEAN_TAG;
        }

        getIndexedPrimitiveLocals()[slot] = extend(value ? 1 : 0);
    }

    @Override
    public int getIntStatic(int slot) {
        assert checkStaticGet(slot, STATIC_INT_TAG) : "Unexpected read of static int value";

        return narrow(getIndexedPrimitiveLocals()[slot]);
    }

    @Override
    public void setIntStatic(int slot, int value) {
        assert checkStatic(slot) : UNEXPECTED_STATIC_WRITE;
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[slot] = STATIC_INT_TAG;
        }

        getIndexedPrimitiveLocals()[slot] = extend(value);
    }

    @Override
    public long getLongStatic(int slot) {
        assert checkStaticGet(slot, STATIC_LONG_TAG) : "Unexpected read of static long value";

        return getIndexedPrimitiveLocals()[slot];
    }

    @Override
    public void setLongStatic(int slot, long value) {
        assert checkStatic(slot) : UNEXPECTED_STATIC_WRITE;
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[slot] = STATIC_LONG_TAG;
        }

        getIndexedPrimitiveLocals()[slot] = value;
    }

    @Override
    public float getFloatStatic(int slot) {
        assert checkStaticGet(slot, STATIC_FLOAT_TAG) : "Unexpected read of static float value";

        return Float.intBitsToFloat(narrow(getIndexedPrimitiveLocals()[slot]));
    }

    @Override
    public void setFloatStatic(int slot, float value) {
        assert checkStatic(slot) : UNEXPECTED_STATIC_WRITE;
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[slot] = STATIC_FLOAT_TAG;
        }

        getIndexedPrimitiveLocals()[slot] = extend(Float.floatToRawIntBits(value));
    }

    @Override
    public double getDoubleStatic(int slot) {
        assert checkStaticGet(slot, STATIC_DOUBLE_TAG) : "Unexpected read of static double value";

        return Double.longBitsToDouble(getIndexedPrimitiveLocals()[slot]);
    }

    @Override
    public void setDoubleStatic(int slot, double value) {
        assert checkStatic(slot) : UNEXPECTED_STATIC_WRITE;
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[slot] = STATIC_DOUBLE_TAG;
        }

        getIndexedPrimitiveLocals()[slot] = Double.doubleToRawLongBits(value);
    }

    @Override
    public void copyPrimitiveStatic(int srcSlot, int destSlot) {
        assert checkStaticPrimitive(srcSlot) && checkStatic(destSlot) : "Unexpected copy of static primitive value ";
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[destSlot] = indexedTags[srcSlot];
        }

        long[] primitiveLocals = getIndexedPrimitiveLocals();
        primitiveLocals[destSlot] = primitiveLocals[srcSlot];
    }

    @Override
    public void copyObjectStatic(int srcSlot, int destSlot) {
        assert checkStaticObject(srcSlot) && checkStatic(destSlot) : "Unexpected copy of static object value";
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[destSlot] = indexedTags[srcSlot];
        }

        Object[] referenceLocals = getIndexedLocals();
        referenceLocals[destSlot] = referenceLocals[srcSlot];
    }

    @Override
    public void copyStatic(int srcSlot, int destSlot) {
        assert checkStatic(srcSlot) && checkStatic(destSlot) : "Unexpected copy of static value";
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[destSlot] = indexedTags[srcSlot];
        }

        final Object[] referenceLocals = getIndexedLocals();
        final long[] primitiveLocals = getIndexedPrimitiveLocals();
        referenceLocals[destSlot] = referenceLocals[srcSlot];
        primitiveLocals[destSlot] = primitiveLocals[srcSlot];
    }

    @Override
    public void swapPrimitiveStatic(int first, int second) {
        assert checkStaticPrimitive(first) && checkStaticPrimitive(second) : "Unexpected swap of static primitive value";
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            final byte swapTag = indexedTags[first];
            indexedTags[first] = indexedTags[second];
            indexedTags[second] = swapTag;
        }

        final long[] primitiveLocals = getIndexedPrimitiveLocals();
        final long firstValue = primitiveLocals[first];
        final long secondValue = primitiveLocals[second];

        primitiveLocals[first] = secondValue;
        primitiveLocals[second] = firstValue;
    }

    @Override
    public void swapObjectStatic(int first, int second) {
        assert checkStaticObject(first) &&
                        checkStaticObject(second) : "Unexpected swap of static object value";
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            final byte swapTag = indexedTags[first];
            indexedTags[first] = indexedTags[second];
            indexedTags[second] = swapTag;
        }

        final Object[] referenceLocals = getIndexedLocals();
        final Object firstValue = referenceLocals[first];
        final Object secondValue = referenceLocals[second];

        referenceLocals[first] = secondValue;
        referenceLocals[second] = firstValue;
    }

    @Override
    public void swapStatic(int first, int second) {
        assert checkStatic(first) && checkStatic(second) : "Unexpected swap of static value";
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            final byte swapTag = indexedTags[first];
            indexedTags[first] = indexedTags[second];
            indexedTags[second] = swapTag;
        }

        final Object[] referenceLocals = getIndexedLocals();
        final long[] primitiveLocals = getIndexedPrimitiveLocals();
        final Object firstValue = referenceLocals[first];
        final Object secondValue = referenceLocals[second];
        final long firstPrimitiveValue = primitiveLocals[first];
        final long secondPrimitiveValue = primitiveLocals[second];

        referenceLocals[first] = secondValue;
        referenceLocals[second] = firstValue;
        primitiveLocals[first] = secondPrimitiveValue;
        primitiveLocals[second] = firstPrimitiveValue;
    }

    @Override
    public void clearPrimitiveStatic(int slot) {
        assert checkStaticPrimitive(slot) : "Unexpected clear of static primitive value";
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[slot] = STATIC_ILLEGAL_TAG;
        }

        if (CompilerDirectives.inCompiledCode()) {
            // Avoids keeping track of cleared frame slots in FrameStates
            getIndexedPrimitiveLocals()[slot] = 0L;
        }
    }

    @Override
    public void clearObjectStatic(int slot) {
        assert checkStaticObject(slot) : "Unexpected clear of static object value";
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[slot] = STATIC_ILLEGAL_TAG;
        }

        getIndexedLocals()[slot] = null;
    }

    @Override
    public void copyTo(int sourceOffset, Frame destination, int destinationOffset, int length) {
        FrameWithoutBoxing o = (FrameWithoutBoxing) destination;
        if (o.descriptor != descriptor) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalArgumentException("Invalid frame with wrong frame descriptor passed.");
        } else if (length < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IndexOutOfBoundsException("Illegal length passed.");
        } else if (sourceOffset < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IndexOutOfBoundsException("Illegal sourceOffset passed.");
        } else if (sourceOffset + length > getIndexedTags().length) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IndexOutOfBoundsException("Illegal sourceOffset or length passed.");
        } else if (destinationOffset < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IndexOutOfBoundsException("Illegal destinationOffset passed.");
        } else if (destinationOffset + length > o.getIndexedTags().length) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IndexOutOfBoundsException("Illegal destinationOffset or length passed.");
        }
        unsafeCopyTo(sourceOffset, o, destinationOffset, length);
    }

    void unsafeCopyTo(int srcOffset, FrameWithoutBoxing o, int dstOffset, int length) {
        if (length == 0) {
            return;
        }

        // eventually we might want to optimize this further using Unsafe.
        // for now System.arrayCopy is fast enough.
        System.arraycopy(getIndexedTags(), srcOffset, o.getIndexedTags(), dstOffset, length);
        System.arraycopy(getIndexedLocals(), srcOffset, o.getIndexedLocals(), dstOffset, length);
        System.arraycopy(getIndexedPrimitiveLocals(), srcOffset, o.getIndexedPrimitiveLocals(), dstOffset, length);
    }

    @Override
    public void clearStatic(int slot) {
        assert checkStatic(slot) : "Unexpected clear of static value";
        // We use this check instead of the assert keyword to update the tags in PE'd code.
        if (ASSERTIONS_ENABLED) {
            indexedTags[slot] = STATIC_ILLEGAL_TAG;
        }

        if (CompilerDirectives.inCompiledCode()) {
            // Avoid keeping track of cleared frame slots in FrameStates
            getIndexedPrimitiveLocals()[slot] = 0L;
        }
        getIndexedLocals()[slot] = null;
    }

    /*
     * Implementation details for static slots tag handling:
     *
     * Static slots tags are not initialized to the STATIC_TAG value, but are initially left to 0.
     * The first write to a static slot will (if checks are enabled) set the tag to its
     * corresponding static tag. Note that this means the tag value in the frame itself is not
     * reliable for determining if a slot is static, but instead the frame descriptor should be
     * queried.
     *
     * Much like regular slots can be read when not yet written to, static slots can be read when
     * not yet initialized (tag == 0), and will return the default value associated with the read
     * (frameDescriptor.defaultValue() for reading an object, 0 for reading a primitive).
     */

    private boolean checkStaticGet(int slot, byte tag) {
        byte frameTag = indexedTags[slot];
        if (frameTag == 0) {
            // Uninitialized tag, allow static reading iff frame descriptor declares it static.
            return isStatic(slot);
        }
        return frameTag == tag;
    }

    private boolean checkStatic(int slot) {
        byte frameTag = indexedTags[slot];
        if (frameTag == 0) {
            // Uninitialized tag, allow static writing iff frame descriptor declares it static.
            return isStatic(slot);
        }
        return frameTag >= STATIC_TAG;
    }

    private boolean checkStaticPrimitive(int slot) {
        byte frameTag = indexedTags[slot];
        if (frameTag == 0) {
            // Uninitialized tag, allow static reading iff frame descriptor declares it static.
            return isStatic(slot);
        }
        return frameTag > STATIC_TAG;
    }

    private boolean checkStaticObject(int slot) {
        byte frameTag = indexedTags[slot];
        if (frameTag == 0) {
            // Uninitialized tag, allow static reading iff frame descriptor declares it static.
            return isStatic(slot);
        }
        return frameTag == STATIC_OBJECT_TAG || frameTag == STATIC_ILLEGAL_TAG;
    }

    /**
     * Marker method to be called before performing a frame transfer.
     */
    void startOSRTransfer() {
    }

    /**
     * This method is used to transfer a static slot from a source frame to a target frame before or
     * after OSR. This method must exclusively be used inside
     * {@code BytecodeOSRMetadata#transferIndexedFrameSlot}. It is necessary to support static
     * assertions.
     *
     * @param target The target frame (The frame descriptor of this and the target frame must match)
     * @param slot The slot that should be transferred
     */
    void transferOSRStaticSlot(FrameWithoutBoxing target, int slot) {
        if (ASSERTIONS_ENABLED) {
            final byte tag = indexedTags[slot];
            indexedTags[slot] = STATIC_OBJECT_TAG;
            target.setObjectStatic(slot, getObjectStatic(slot));
            indexedTags[slot] = STATIC_LONG_TAG;
            target.setLongStatic(slot, getLongStatic(slot));
            indexedTags[slot] = tag;
            target.setStaticSlotTag(slot, tag);
        } else {
            target.setObjectStatic(slot, getObjectStatic(slot));
            target.setLongStatic(slot, getLongStatic(slot));
        }
    }

    private void setStaticSlotTag(int slot, byte tag) {
        indexedTags[slot] = tag;
    }

}
