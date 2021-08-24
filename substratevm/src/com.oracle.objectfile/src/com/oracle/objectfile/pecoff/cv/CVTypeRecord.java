/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2021, Red Hat Inc. All rights reserved.
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

package com.oracle.objectfile.pecoff.cv;

import org.graalvm.compiler.debug.GraalError;

import java.util.ArrayList;
import java.util.Arrays;

import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_ARGLIST;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_ARRAY;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_BCLASS;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_BINTERFACE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_BITFIELD;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_CLASS;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_ENUM;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_ENUMERATE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_FIELDLIST;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_INTERFACE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_MEMBER;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_METHOD;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_METHODLIST;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_MFUNCTION;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_MODIFIER;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_ONEMETHOD;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_PAD1;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_PAD2;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_PAD3;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_POINTER;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_PROCEDURE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_STMEMBER;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_STRING_ID;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_STRUCTURE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_TYPESERVER2;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_UDT_MOD_SRC_LINE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_UDT_SRC_LINE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_ABSTRACT;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_COMPGENX;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_FINAL_CLASS;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_FINAL_METHOD;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_IVIRTUAL;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_PPP_MASK;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_PSEUDO;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_STATIC;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_VIRTUAL;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_VSF_MASK;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_UINT8;

/*
 * CV Type Record format (little-endian):
 * uint16 length
 * uint16 leaf (a.k.a. record type)
 * (contents)
 */
abstract class CVTypeRecord {

    static int FIRST_TYPE_INDEX = 0x1000;

    protected final short type;
    private int startPosition;
    private int sequenceNumber; /* CodeView type records are numbered 1000 on up. */

    CVTypeRecord(short type) {
        this.type = type;
        this.startPosition = -1;
        this.sequenceNumber = -1;
    }

    int getSequenceNumber() {
        return sequenceNumber;
    }

    void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    int computeFullSize(int initialPos) {
        assert sequenceNumber >= FIRST_TYPE_INDEX;
        this.startPosition = initialPos;
        int pos = initialPos + Short.BYTES * 2; /* Save room for length and leaf type. */
        pos = computeSize(pos);
        pos = alignPadded4(null, pos);
        return pos;
    }

    int computeFullContents(byte[] buffer, int initialPos) {
        assert sequenceNumber >= FIRST_TYPE_INDEX;
        int pos = initialPos + Short.BYTES; /* Save room for length short. */
        pos = CVUtil.putShort(type, buffer, pos);
        pos = computeContents(buffer, pos);
        /* Length does not include record length (2 bytes)) but does include end padding. */
        pos = alignPadded4(buffer, pos);
        int length = (short) (pos - initialPos - Short.BYTES);
        CVUtil.putShort((short) length, buffer, initialPos);
        return pos;
    }

    public int computeSize(int initialPos) {
        return computeContents(null, initialPos);
    }

    protected abstract int computeContents(byte[] buffer, int initialPos);

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || obj.getClass() != this.getClass()) {
            return false;
        }
        return this.type == ((CVTypeRecord) obj).type;
    }

    @Override
    public abstract int hashCode();

    @Override
    public String toString() {
        return String.format("CVTypeRecord seq=0x%04x type=0x%04x pos=0x%04x ", sequenceNumber, type, startPosition);
    }

    private static int alignPadded4(byte[] buffer, int originalpos) {
        int pos = originalpos;
        int align = pos & 3;
        if (align == 1) {
            byte[] p3 = {LF_PAD3, LF_PAD2, LF_PAD1};
            pos = CVUtil.putBytes(p3, buffer, pos);
        } else if (align == 2) {
            pos = CVUtil.putByte(LF_PAD2, buffer, pos);
            pos = CVUtil.putByte(LF_PAD1, buffer, pos);
        } else if (align == 3) {
            pos = CVUtil.putByte(LF_PAD1, buffer, pos);
        }
        return pos;
    }

    static final class CVTypePrimitive extends CVTypeRecord {

        int length;

        CVTypePrimitive(short cvtype, int length) {
            super(cvtype);
            assert cvtype < FIRST_TYPE_INDEX;
            this.length = length;
            setSequenceNumber(cvtype);
        }

        @Override
        public int computeSize(int initialPos) {
            GraalError.shouldNotReachHere();
            return 0;
        }

        @Override
        protected int computeContents(byte[] buffer, int initialPos) {
            GraalError.shouldNotReachHere();
            return 0;
        }

        @Override
        public int hashCode() {
            GraalError.shouldNotReachHere();
            return 0;
        }

        @Override
        public String toString() {
            return String.format("PRIMITIVE 0x%04x (len=%d)", getSequenceNumber(), length);
        }
    }


    static final class CVTypePointerRecord extends CVTypeRecord {

        static final int KIND_64 = 0x0000c;
        static final int SIZE_8 = 8 << 13;

        /* Standard 64-bit absolute pointer type. */
        static final int NORMAL_64 = KIND_64 | SIZE_8;

        private final int pointsTo;

        /*
        int kind      =  attributes & 0x00001f;
        int mode      = (attributes & 0x0000e0) >> 5;
        int modifiers = (attributes & 0x001f00) >> 8;
        int size      = (attributes & 0x07e000) >> 13;
        int flags     = (attributes & 0x380000) >> 19;
        */
        private final int attrs;

        CVTypePointerRecord(int pointTo, int attrs) {
            super(LF_POINTER);
            this.pointsTo = pointTo;
            this.attrs = attrs;
        }

        int getPointsTo() {
            return pointsTo;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putInt(pointsTo, buffer, initialPos);
            return CVUtil.putInt(attrs, buffer, pos);
        }

        static String[] ptrType = { "near16", "far16", "huge", "base-seg", "base-val", "base-segval", "base-addr", "base-segaddr", "base-type", "base-self", "near32", "far32", "64"};
       // static String[] memStrs = { "(old)", "data-single", "data-multiple", "data-virtual", "data", "func-single", "mfunc-multiple", "mfunc-virtual", "mfunc"};
        static String[] modeStrs = {"normal", "lvalref", "datamem", "memfunc", "rvalref"};

        @Override
        public String toString() {
            int kind      =  attrs & 0x00001f;
            int mode      = (attrs & 0x0000e0) >> 5;
            int flags1    = (attrs & 0x001f00) >> 8;
            int size      = (attrs & 0x07e000) >> 13;
            int flags2     = (attrs & 0x380000) >> 19;
            StringBuilder sb = new StringBuilder();
            sb.append((flags1 & 1) != 0 ? "flat32" : "");
            sb.append((flags1 & 2) != 0 ? " volatile" : "");
            sb.append((flags1 & 4) != 0 ? " const" : "");
            sb.append((flags1 & 8) != 0 ? " unaligned" : "");
            sb.append((flags1 & 16) != 0 ? " restricted" : "");
            return String.format("LF_POINTER 0x%04x attrs=0x%x(kind=%d(%s) mode=%d(%s) flags1=0x%x(%s) size=%d flags2=0x%x) pointTo=0x%04x", getSequenceNumber(), attrs,  kind, ptrType[kind], mode, modeStrs[mode], flags1, sb.toString(), size, flags2, pointsTo);
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + pointsTo;
            h = 31 * h + attrs;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVTypePointerRecord other = (CVTypePointerRecord) obj;
            return this.pointsTo == other.pointsTo && this.attrs == other.attrs;
        }
    }

    static class CVUdtTypeLineRecord extends CVTypeRecord {

        int typeIndex;
        int fileIndex;
        int line;

        CVUdtTypeLineRecord(int typeIndex, int fileIndex, int line) {
            this(LF_UDT_SRC_LINE, typeIndex, fileIndex, line);
        }

        CVUdtTypeLineRecord(short t, int typeIndex, int fileIndex, int line) {
            super(t);
            this.typeIndex = typeIndex;
            this.fileIndex = fileIndex;
            this.line = line;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putInt(typeIndex, buffer, initialPos);
            pos = CVUtil.putInt(fileIndex, buffer, pos);
            return CVUtil.putInt(line, buffer, pos);
        }

        @Override
        public String toString() {
            return String.format("LF_UDT_SRC_LINE 0x%04x typeIdx=0x%x fileIdx=0x%x line=%d", getSequenceNumber(), typeIndex, fileIndex, line);
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + typeIndex;
            h = 31 * h + fileIndex;
            h = 31 * h + line;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVUdtTypeLineRecord other = (CVUdtTypeLineRecord) obj;
            /* NB: if the record has the same type but different file or line, it's probably an error. */
            return this.typeIndex == other.typeIndex && this.fileIndex == other.fileIndex && this.line == other.line;
        }
    }

    static final class CVUdtTypeLineModRecord extends CVUdtTypeLineRecord {

        final short mod;

        CVUdtTypeLineModRecord(int typeIndex, int fileIndex, int line, short mod) {
            super(LF_UDT_MOD_SRC_LINE, typeIndex, fileIndex, line);
            this.mod = mod;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = super.computeContents(buffer, initialPos);
            return CVUtil.putShort(mod, buffer, pos);
        }

        @Override
        public String toString() {
            return String.format("LF_UDT_MOD_SRC_LINE 0x%04x typeIdx=0x%x fileIdx=0x%x line=%d mod=%d", getSequenceNumber(), typeIndex, fileIndex, line, mod);
        }

        @Override
        public int hashCode() {
            int h = super.hashCode();
            h = 31 * h + mod;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVUdtTypeLineModRecord other = (CVUdtTypeLineModRecord) obj;
            /* NB: if the record has the same type but different file or line, it's probably an error. */
            return this.mod == other.mod;
        }
    }

    static final class CVTypeStringIdRecord extends CVTypeRecord {

        String string;
        int substringIdx;

        public CVTypeStringIdRecord(int substringIdx, String string) {
            super(LF_STRING_ID);
            this.substringIdx = substringIdx;
            this.string = string;
        }

        CVTypeStringIdRecord(String string) {
            super(LF_STRING_ID);
            this.substringIdx = 0;
            this.string = string;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putInt(substringIdx, buffer, initialPos);
            return CVUtil.putUTF8StringBytes(string, buffer, pos);
        }

        @Override
        public String toString() {
            return String.format("LF_STRING_ID 0x%04x substringIdx=0x%x %s", getSequenceNumber(), substringIdx, string);
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + substringIdx;
            h = 31 * h + string.hashCode();
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVTypeStringIdRecord other = (CVTypeStringIdRecord) obj;
            return this.string.equals(other.string);
        }
    }

    static final class CVTypeModifierRecord extends CVTypeRecord {

        private final int typeIndex;
        private final short attrs;

        CVTypeModifierRecord(int typeIndex, short attrs) {
            super(LF_MODIFIER);
            this.typeIndex = typeIndex;
            this.attrs = attrs;
        }

        public CVTypeModifierRecord(int typeIndex, boolean isConst, boolean isVolatile, boolean isUnaligned) {
            this(typeIndex, (short) ((isConst ? 1 : 0) + (isVolatile ? 2 : 0) + (isUnaligned ? 4 : 0)));
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putInt(typeIndex, buffer, initialPos);
            return CVUtil.putShort(attrs, buffer, pos);
        }

        @Override
        public String toString() {
            boolean isConst     = (attrs & 0x0001) == 0x0001;
            boolean isVolatile  = (attrs & 0x0002) == 0x0002;
            boolean isUnaligned = (attrs & 0x0004) == 0x0004;
            return String.format("LF_MODIFIER 0x%04x attr=0x%x%s%s%s pointTo=0x%04x", getSequenceNumber(), attrs, isConst ? " const" : "", isVolatile ? " volatile" : "", isUnaligned ? " unaligned" : "", typeIndex);
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + typeIndex;
            h = 31 * h + attrs;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVTypeModifierRecord other = (CVTypeModifierRecord) obj;
            return this.typeIndex == other.typeIndex && this.attrs == other.attrs;
        }
    }

    static final class CVTypeProcedureRecord extends CVTypeRecord {

        int returnType = -1;
        CVTypeArglistRecord argList = null;

        CVTypeProcedureRecord() {
            super(LF_PROCEDURE);
        }

        public CVTypeProcedureRecord returnType(int leaf) {
            this.returnType = leaf;
            return this;
        }

        public CVTypeProcedureRecord returnType(CVTypeRecord leaf) {
            this.returnType = leaf.getSequenceNumber();
            return this;
        }

        CVTypeProcedureRecord argList(CVTypeArglistRecord leaf) {
            this.argList = leaf;
            return this;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putInt(returnType, buffer, initialPos);
            pos = CVUtil.putByte((byte) 0, buffer, pos); /* TODO callType */
            pos = CVUtil.putByte((byte) 0, buffer, pos); /* TODO funcAttr */
            pos = CVUtil.putShort((short) argList.getSize(), buffer, pos);
            pos = CVUtil.putInt(argList.getSequenceNumber(), buffer, pos);
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_PROCEDURE 0x%04x ret=0x%04x arg=0x%04x ", getSequenceNumber(), returnType, argList.getSequenceNumber());
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + returnType;
            h = 31 * h + argList.hashCode();
            /* callType and funcAttr are always zero so do not add them to the hash */
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVTypeProcedureRecord other = (CVTypeProcedureRecord) obj;
            return this.returnType == other.returnType && this.argList == other.argList;
        }
    }

    static final class CVTypeArglistRecord extends CVTypeRecord {

        ArrayList<Integer> args = new ArrayList<>();

        CVTypeArglistRecord() {
            super(LF_ARGLIST);
        }

        CVTypeArglistRecord add(int argType) {
            args.add(argType);
            return this;
        }

        @Override
        public int computeSize(int initialPos) {
            return initialPos + Integer.BYTES + Integer.BYTES * args.size();
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putInt(args.size(), buffer, initialPos);
            for (Integer at : args) {
                pos = CVUtil.putInt(at, buffer, pos);
            }
            return pos;
        }

        int getSize() {
            return args.size();
        }

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder(String.format("LF_ARGLIST 0x%04x [", getSequenceNumber()));
            for (Integer at : args) {
                s.append(String.format(" 0x%04x", at));
            }
            s.append("])");
            return s.toString();
        }

        @Override
        public int hashCode() {
            return type * 31 + args.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVTypeArglistRecord other = (CVTypeArglistRecord) obj;
            return this.args.equals(other.args);
        }
    }

    static final class CVTypeMFunctionRecord extends CVTypeRecord {

        private int returnType = -1;
        private int classType = -1;
        private int thisType = -1;
        private byte callType = 0;
        private byte funcAttr = 0;
        private int thisAdjust = 0;

        private CVTypeArglistRecord argList = null;

        CVTypeMFunctionRecord() {
            super(LF_MFUNCTION);
        }

        public void setReturnType(int returnType) {
            this.returnType = returnType;
        }

        void setClassType(int classType) {
            this.classType = classType;
        }

        void setThisType(int thisType) {
            this.thisType = thisType;
        }

        void setCallType(byte callType) {
            this.callType = callType;
        }

        void setFuncAttr(byte funcAttr) {
            this.funcAttr = funcAttr;
        }

        public void setThisAdjust(int thisAdjust) {
            this.thisAdjust = thisAdjust;
        }

        void setArgList(CVTypeArglistRecord argList) {
            this.argList = argList;
        }

        /*
            int returnType = in.getInt();
            int classType = in.getInt();
            int thisType = in.getInt();
            byte callType = in.get();
            byte funcAttr = in.get();
            short paramCount = in.getShort();
            int argList = in.getInt();
            int thisAdjustment = in.getInt();
         */
        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putInt(returnType, buffer, initialPos);
            pos = CVUtil.putInt(classType, buffer, pos);
            pos = CVUtil.putInt(thisType, buffer, pos);
            pos = CVUtil.putByte(callType, buffer, pos);
            pos = CVUtil.putByte(funcAttr, buffer, pos);
            pos = CVUtil.putShort((short) argList.getSize(), buffer, pos);
            pos = CVUtil.putInt(argList.getSequenceNumber(), buffer, pos);
            pos = CVUtil.putInt(thisAdjust, buffer, pos);
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_MFUNCTION 0x%04x ret=0x%04x this=0x%04x *this=0x%04x+%d calltype=0x%x attr=0x%x(%s), argcount=0x%04x ", getSequenceNumber(), returnType, classType, thisType, thisAdjust, callType, funcAttr, attrString(funcAttr),  argList.getSequenceNumber());
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + returnType;
            h = 31 * h + callType;
            h = 31 * h + funcAttr;
            h = 31 * h + argList.getSequenceNumber();
            h = 31 * h + thisType;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVTypeMFunctionRecord other = (CVTypeMFunctionRecord) obj;
            return this.returnType == other.returnType
                    && this.argList.getSequenceNumber() == other.argList.getSequenceNumber()
                    && this.thisType == other.thisType
                    && this.funcAttr == other.funcAttr
                    && this.callType == other.callType;
        }
    }

    static final class CVTypeMethodListRecord extends CVTypeRecord {

        static class MDef {
            short d0;
            short d1;
            int idx;
            MDef(short d0, short d1, int idx) {
                this.d0 = d0;
                this.d1 = d1;
                this.idx = idx;
            }
        }
        ArrayList<MDef> methods = new ArrayList<>(10);

        CVTypeMethodListRecord() {
            super(LF_METHODLIST);
        }

        public void add(short d0, short d1, int idx) {
            methods.add(new MDef(d0, d1, idx));
        }

        public int count() {
            return methods.size();
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = initialPos;
            for (MDef f : methods) {
                pos = CVUtil.putShort(f.d0, buffer, pos);
                pos = CVUtil.putShort(f.d1, buffer, pos);
                pos = CVUtil.putInt(f.idx, buffer, pos);
            }
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_METHODLIST 0x%04x count=%d", getSequenceNumber(), methods.size());
        }

        @Override
        public int hashCode() {
            int h = type;
            for (MDef f : methods) {
                h = 31 * h + f.d0;
                h = 31 * h + f.d1;
                h = 31 * h + f.idx;
            }
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVTypeMethodListRecord other = (CVTypeMethodListRecord) obj;
            if (this.methods.size() != other.methods.size()) {
                return false;
            }
            for (int i=0; i < methods.size(); i++) {
                MDef m0 = methods.get(i);
                MDef o0 = other.methods.get(i);
                if (m0.idx != o0.idx) {
                    return false;
                }
                if (m0.d0 != o0.d0 || m0.d1 != o0.d1) {
                    return false;
                }
            }
            return true;
        }
    }


    static String attrString(short attrs) {
        StringBuilder sb = new StringBuilder();

        /* Low byte. */
        if ((attrs & MPROP_PPP_MASK) != 0) {
            String[] aStr = {"", "private", "protected", "public"};
            sb.append(aStr[attrs & MPROP_PPP_MASK]);
        }
        if ((attrs & MPROP_VSF_MASK) != 0) {
            int p = (attrs & MPROP_VSF_MASK) >> 2;
            String[] pStr = {"", " virtual", " static", " friend", " intro", " pure", " intro-pure", " (*7*)"};
            sb.append(pStr[p]);
        }
        if ((attrs & MPROP_PSEUDO) != 0) {
            sb.append(" pseudo");
        }
        if ((attrs & MPROP_FINAL_CLASS) != 0) {
            sb.append(" final-class");
        }
        if ((attrs & MPROP_ABSTRACT) != 0) {
            sb.append(" abstract");
        }
        if ((attrs & MPROP_COMPGENX) != 0) {
            sb.append(" compgenx");
        }
        if ((attrs & MPROP_FINAL_METHOD) != 0) {
            sb.append(" final-method");
        }
        return sb.toString();
    }

    static abstract class FieldRecord {

        final short type;
        final short attrs; /* property attribute field (prop_t) */
        final String name;

        FieldRecord(short leafType, short attrs, String name) {
            this.type = leafType;
            this.attrs = attrs;
            this.name = name;
        }

        abstract public int computeContents(byte[] buffer, int initialPos);

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + attrs;
            h = 31 * h + name.hashCode();
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            FieldRecord other = (FieldRecord) obj;
            return this.type == other.type && this.attrs == other.attrs && this.name.equals(other.name);
        }
    }

    static final class CVMemberMethodRecord extends FieldRecord {

        final int methodListIndex; /* index of method list record */

        CVMemberMethodRecord(short count, int methodListIndex, String methodName) {
            super(LF_METHOD, count, methodName);
            this.methodListIndex = methodListIndex;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putShort(type, buffer, initialPos);
            pos = CVUtil.putShort(attrs, buffer, pos); /* (count) */
            pos = CVUtil.putInt(methodListIndex, buffer, pos);
            pos = CVUtil.putUTF8StringBytes(name, buffer, pos);
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_METHOD(0x%04x) count=0x%x listIdx=0x%04x %s", type, attrs, methodListIndex, name);
        }

        @Override
        public int hashCode() {
            int h = super.hashCode();
            h = 31 * h + methodListIndex;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVMemberMethodRecord other = (CVMemberMethodRecord) obj;
            return this.methodListIndex == other.methodListIndex;
        }
    }

    static final class CVMemberRecord extends FieldRecord {

        final int underlyingTypeIndex; /* type index of member type */
        int offset;

        CVMemberRecord(short attrs, int underlyingTypeIndex, int offset, String name) {
            super(LF_MEMBER, attrs, name);
            this.underlyingTypeIndex = underlyingTypeIndex;
            this.offset = offset;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putShort(type, buffer, initialPos);
            pos = CVUtil.putShort(attrs, buffer, pos);
            pos = CVUtil.putInt(underlyingTypeIndex, buffer, pos);
            pos = CVUtil.putLfNumeric(offset, buffer, pos);
            pos = CVUtil.putUTF8StringBytes(name, buffer, pos);
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_MEMBER(0x%04x) attr=0x%x(%s) t=0x%x off=%d 0x%x %s", type, attrs, attrString(attrs), underlyingTypeIndex, offset, offset & 0xffff, name);
        }

        @Override
        public int hashCode() {
            int h = super.hashCode();
            h = 31 * h + underlyingTypeIndex;
            h = 31 * h + offset;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVMemberRecord other = (CVMemberRecord) obj;
            return this.offset == other.offset && this.underlyingTypeIndex == other.underlyingTypeIndex;
        }
    }


    static final class CVStaticMemberRecord extends FieldRecord {

        final int underlyingTypeIndex; /* type index of member type */;

        CVStaticMemberRecord(short attrs, int underlyingTypeIndex,  String name) {
            super(LF_STMEMBER, (short)(attrs + MPROP_STATIC), name);
            this.underlyingTypeIndex = underlyingTypeIndex;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putShort(type, buffer, initialPos);
            pos = CVUtil.putShort(attrs, buffer, pos);
            pos = CVUtil.putInt(underlyingTypeIndex, buffer, pos);
            pos = CVUtil.putUTF8StringBytes(name, buffer, pos);
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_STMEMBER(0x%04x) attr=0x%x(%s) t=0x%x %s", type, attrs, attrString(attrs), underlyingTypeIndex, name);
        }

        @Override
        public int hashCode() {
            int h = super.hashCode();
            h = 31 * h + underlyingTypeIndex;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVStaticMemberRecord other = (CVStaticMemberRecord) obj;
            return this.underlyingTypeIndex == other.underlyingTypeIndex;
        }
    }

    static final class CVOneMethodRecord extends FieldRecord {

        final int funcIdx; /* type index of member type */
        final int vtbleOffset;

        CVOneMethodRecord(short attrs, int funcIdx, int vtbleOffset, String name) {
            super(LF_ONEMETHOD, attrs, name);
            this.funcIdx = funcIdx;
            this.vtbleOffset = ((attrs & (MPROP_VIRTUAL | MPROP_IVIRTUAL)) != 0) ? vtbleOffset : 0;
            /* assert fails if caller tried to give an offset for a non-virtual function */
            assert this.vtbleOffset == vtbleOffset;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putShort(type, buffer, initialPos);
            pos = CVUtil.putShort(attrs, buffer, pos);
            pos = CVUtil.putInt(funcIdx, buffer, pos);
            /* TODO: there is some indication the offset is only present if attrs & (MPROP_VIRTUAL | MPROP_IVIRTUAL) != 0 */
            if ((attrs & (MPROP_VIRTUAL | MPROP_IVIRTUAL)) != 0) {
                pos = CVUtil.putInt(vtbleOffset, buffer, pos);
            }
            pos = CVUtil.putUTF8StringBytes(name, buffer, pos);
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_ONEMETHOD(0x%04x) attr=0x%x(%s) funcIdx=0x%x off=0x%x %s", type, attrs, attrString(attrs), funcIdx, vtbleOffset, name);
        }

        @Override
        public int hashCode() {
            int h = super.hashCode();
            h = 31 * h + funcIdx;
            h = 31 * h + vtbleOffset;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVOneMethodRecord other = (CVOneMethodRecord) obj;
            return this.vtbleOffset == other.vtbleOffset && this.funcIdx == other.funcIdx;
        }
    }

    static class CVBaseMemberRecord extends FieldRecord {

        int basetypeIndex; /* type index of member type */
        int offset; /* in java, usually 0 as there is no multiple inheritance. */

        CVBaseMemberRecord(short attrs, int basetypeIndex, int offset) {
            super(LF_BCLASS, attrs, "");
            this.basetypeIndex = basetypeIndex;
            this.offset = offset;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putShort(type, buffer, initialPos);
            pos = CVUtil.putShort(attrs, buffer, pos);
            pos = CVUtil.putInt(basetypeIndex, buffer, pos);
            pos = CVUtil.putLfNumeric(offset, buffer, pos);
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_BCLASS(0x%04x) attr=0x%04x(%s ?) baseIdx=0x%04x offset=0x%x", LF_BCLASS, attrs, attrString(attrs), basetypeIndex, offset);
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + attrs;
            h = 31 * h + basetypeIndex;
            h = 31 * h + offset;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVBaseMemberRecord other = (CVBaseMemberRecord) obj;
            return this.basetypeIndex == other.basetypeIndex && this.offset == other.offset;
        }
    }

    static class CVBaseClassRecord extends CVTypeRecord {

        short propertyAttributes; /* property attribute field (prop_t) */
        int fieldIndex; /* type index of member type */
        /* TODO data */

        CVBaseClassRecord(short ltype, short attrs, int fieldIndex) {
            super(ltype);
            this.propertyAttributes = attrs;
            this.fieldIndex = fieldIndex;
        }

        CVBaseClassRecord(short attrs, int fieldIndex) {
            this(LF_BCLASS, attrs, fieldIndex);
        }

        @Override
        public int computeSize(int initialPos) {
            return initialPos + Short.BYTES + Integer.BYTES; // + TODO
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putShort(propertyAttributes, buffer, initialPos);
            pos = CVUtil.putInt(fieldIndex, buffer, pos);
            // TODO4
            return pos;
        }

        protected String toString(String leafStr) {
            return String.format("%s 0x%04x attr=0x%04x(%s) fld=0x%x", leafStr, getSequenceNumber(), propertyAttributes, propertyString(propertyAttributes), fieldIndex);
        }

        @Override
        public String toString() {
            return toString("LF_BASECLASS");
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + propertyAttributes;
            h = 31 * h + fieldIndex;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVBaseClassRecord other = (CVBaseClassRecord) obj;
            return this.propertyAttributes == other.propertyAttributes && this.fieldIndex == other.fieldIndex;
        }
    }

    static class CVBaseInterfaceRecord extends CVBaseClassRecord {

        CVBaseInterfaceRecord(short attrs, int fieldIndex) {
            super(LF_BINTERFACE, attrs, fieldIndex);
        }

        @Override
        public String toString() {
            return toString("LF_BINTERFACE");
        }
    }

    static class CVClassRecord extends CVTypeRecord {

        static final int ATTR_FORWARD_REF = 0x0080;
        static final int ATTR_HAS_UNIQUENAME = 0x0200;

        /* Count of number of elements in class field list. */
        short count;

        /* Property attribute field (prop_t). */
        short propertyAttributes;

        /* Type index of LF_FIELDLIST descriptor list. */
        int fieldIndex;

        /* Type index of derived from list if not zero */
        /* For Java, there is only one class, so LF_BCLASS is in the meber list and derivedFromIndex is 0. */
        int derivedFromIndex;

        /* Type index of vshape table for this class. */
        int vshapeIndex;

        /* Size (in bytes) of an instance. */
        long size;

        /* Class name. */
        String className;

        /* Linker class name. */
        String uniqueName;

        CVClassRecord(short recType, short count, short attrs, int fieldIndex, int derivedFromIndex, int vshapeIndex, long size, String className, String uniqueName) {
            super(recType);
            this.count = count;
            this.propertyAttributes = (short) (attrs | (short) (uniqueName != null ? ATTR_HAS_UNIQUENAME : 0));
            this.fieldIndex = fieldIndex;
            this.derivedFromIndex = derivedFromIndex;
            this.vshapeIndex = vshapeIndex;
            this.size = size;
            this.className = className;
        }

        CVClassRecord(short count, short attrs, int fieldIndex, int derivedFromIndex, int vshapeIndex, long size, String className, String uniqueName) {
            this(LF_CLASS, count, attrs, fieldIndex, derivedFromIndex, vshapeIndex, size, className, uniqueName);
        }

        CVClassRecord(short attrs, String className, String uniqueName) {
            this(LF_CLASS, (short) 0, attrs, 0, 0, 0, 0, className, uniqueName);
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putShort(count, buffer, initialPos);
            pos = CVUtil.putShort((short) (propertyAttributes | ATTR_HAS_UNIQUENAME), buffer, pos);
            pos = CVUtil.putInt(fieldIndex, buffer, pos);
            pos = CVUtil.putInt(derivedFromIndex, buffer, pos);
            pos = CVUtil.putInt(vshapeIndex, buffer, pos);
            pos = CVUtil.putLfNumeric(size, buffer, pos);
            String fixedName = className.replace('.', '_').replace("[]", "_array").replace('$', '_');
            pos = CVUtil.putUTF8StringBytes(fixedName, buffer, pos);
            if (uniqueName != null) {
                pos = CVUtil.putUTF8StringBytes(uniqueName, buffer, pos);
            } else {
                pos = CVUtil.putUTF8StringBytes(fixedName, buffer, pos);
            }
            return pos;
        }

        boolean isForwardRef() {
            return (propertyAttributes & ATTR_FORWARD_REF) != 0;
        }

        public boolean hasUniqueName() {
            return (propertyAttributes & ATTR_HAS_UNIQUENAME) != 0;
        }

        protected String toString(String lfTypeStr) {
            return String.format("%s 0x%04x count=%d attr=0x%x(%s) fld=0x%x super=0x%x vshape=0x%x size=%d %s%s", lfTypeStr, getSequenceNumber(), count, propertyAttributes, propertyString(propertyAttributes), fieldIndex, derivedFromIndex,
                    vshapeIndex, size, className, uniqueName != null ? " (" + uniqueName + ")" : "");
        }

        @Override
        public String toString() {
            return toString("LF_CLASS");
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + count;
            h = 31 * h + propertyAttributes;
            h = 31 * h + fieldIndex;
            h = 31 * h + derivedFromIndex;
            h = 31 * h + (int) size;
            h = 31 * h + className.hashCode();
            if (uniqueName != null) {
                h = 31 * h + uniqueName.hashCode();
            }
            h = 31 * h + vshapeIndex;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVClassRecord other = (CVClassRecord) obj;
            return this.count == other.count
                    && this.propertyAttributes == other.propertyAttributes
                    && this.fieldIndex == other.fieldIndex
                    && this.derivedFromIndex == other.derivedFromIndex
                    && this.vshapeIndex == other.vshapeIndex;
        }
    }

    static final class CVStructRecord extends CVClassRecord {
        CVStructRecord(short count, short attrs, int fieldIndex, int derivedFromIndex, int vshape, long size, String name) {
            super(LF_STRUCTURE, count, attrs, fieldIndex, derivedFromIndex, vshape, size, name, null);
        }

        @Override
        public String toString() {
            return toString("LF_STRUCT");
        }
    }

    static final class CVFieldListRecord extends CVTypeRecord {

        static final int INITIAL_CAPACITY = 10;

        ArrayList<FieldRecord> members = new ArrayList<>(INITIAL_CAPACITY);

        CVFieldListRecord() {
            super(LF_FIELDLIST);
        }

        void add(FieldRecord m) {
            members.add(m);
        }

        int count() {
            return members.size();
        }

        @Override
        protected int computeContents(byte[] buffer, int initialPos) {
            int pos = initialPos;
            for (FieldRecord field : members) {
                pos = field.computeContents(buffer, pos);
                /* Align on 4-byte boundary. */
                pos = CVTypeRecord.alignPadded4(buffer, pos);
            }
            return pos;
        }

        @Override
        public int hashCode() {
            int hash = type;
            for (FieldRecord field : members) {
                hash = 31 * hash + field.hashCode();
            }
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVFieldListRecord other = (CVFieldListRecord) obj;
            if (other.members.size() != members.size()) {
                return false;
            }
            for (int i = 0; i < members.size(); i++) {
                if (! members.get(i).equals(other.members.get(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return String.format("LF_FIELDLIST idx=0x%x count=%d", getSequenceNumber(), count());
        }
    }

    /* Unused in Graal - enums are actually implemented as classes, and enumerations are static instances. */
    static final class CVEnumerateRecord extends FieldRecord {

        final long value;

        CVEnumerateRecord(short attrs, long value, String name) {
            super(LF_ENUMERATE, attrs, name);
            this.value = value;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putShort(type, buffer, initialPos);
            pos = CVUtil.putShort(attrs, buffer, pos);
            pos = CVUtil.putLfNumeric(value, buffer, pos);
            pos = CVUtil.putUTF8StringBytes(name, buffer, pos);
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_ENUMERATE 0x%04x attr=0x%x(%s) val=0x%x %s", type, attrs, attrString(attrs), value, name);
        }

        @Override
        public int hashCode() {
            int h = super.hashCode();
            h = 31 * h + (int)value;
            h = 31 * h + name.hashCode();
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVEnumerateRecord other = (CVEnumerateRecord) obj;
            return this.value == other.value;
        }
    }

    /* Unused in Graal - enums are actually implemented as classes, and enumerations are static instances. */
    static final class CVEnumRecord extends CVTypeRecord {

        String name;
        int attrs;
        int underlyingTypeIndex;
        CVFieldListRecord fieldRecord;

        CVEnumRecord(short attrs, int underlyingTypeIndex, CVFieldListRecord fieldRecord, String name) {
            super(LF_ENUM);
            this.attrs = attrs;
            this.underlyingTypeIndex = underlyingTypeIndex;
            this.fieldRecord = fieldRecord;
            this.name = name;
        }

        @Override
        protected int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putShort((short)attrs, buffer, initialPos);
            pos = CVUtil.putInt(underlyingTypeIndex, buffer, pos);
            pos = CVUtil.putInt(fieldRecord.getSequenceNumber(), buffer, pos);
            pos = CVUtil.putUTF8StringBytes(name, buffer, pos);
            return pos;
        }

        @Override
        public int hashCode() {
            int hash = type;
            hash = 31 * hash + name.hashCode();
            hash = 31 * hash + attrs;
            hash = 31 * hash + underlyingTypeIndex;
            hash = 31 * hash + fieldRecord.hashCode();
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVEnumRecord other = (CVEnumRecord) obj;
            return attrs == other.attrs && fieldRecord.equals(other.fieldRecord) && underlyingTypeIndex == other.underlyingTypeIndex && name.equals(other.name);
        }

        @Override
        public String toString() {
            return String.format("LF_ENUM attrs=0x%x(%s) count=%d %s", attrs, propertyString(attrs), fieldRecord.count(), name);
        }
    }

    static final class CVInterfaceRecord extends CVClassRecord {
        CVInterfaceRecord(short count, short attrs, int fieldIndex, int derivedFromIndex, int vshape, String name) {
            super(LF_INTERFACE, count, attrs, fieldIndex, derivedFromIndex, vshape, 0, name, null);
        }

        @Override
        public String toString() {
            return toString("LF_INTERFACE");
        }
    }

    static final class CVTypeBitfieldRecord extends CVTypeRecord {

        byte length;
        byte position;
        int typeIndex;

        CVTypeBitfieldRecord(int length, int position, int typeIndex) {
            super(LF_BITFIELD);
            this.length = (byte) length;
            this.position = (byte) position;
            this.typeIndex = typeIndex;
        }

        @Override
        public int computeSize(int initialPos) {
            return initialPos + Integer.BYTES + Byte.BYTES + Byte.BYTES;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putInt(typeIndex, buffer, initialPos);
            pos = CVUtil.putByte(length, buffer, pos);
            pos = CVUtil.putByte(position, buffer, pos);
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_BITFIELD 0x%04x, type=0x%04x len=%d pos=%d", getSequenceNumber(), typeIndex, length, position);
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + position;
            h = 31 * h + length;
            h = 31 * h + typeIndex;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVTypeBitfieldRecord other = (CVTypeBitfieldRecord) obj;
            return this.position == other.position
                    && this.length == other.length
                    && this.typeIndex == other.typeIndex;
        }
    }

    static final class CVTypeArrayRecord extends CVTypeRecord {

        int elementType = -1;
        int indexType = -1;
        int length = -1;

        CVTypeArrayRecord(int elementType, int indexType, int length) {
            super(LF_ARRAY);
            this.elementType = elementType;
            this.indexType = indexType;
            this.length = length;
        }

        CVTypeArrayRecord(int elementType, int length) {
            super(LF_ARRAY);
            this.elementType = elementType;
            this.indexType = T_UINT8;
            this.length = length;
        }

        CVTypeArrayRecord(CVTypeRecord elementType, int length) {
            super(LF_ARRAY);
            this.elementType = elementType.getSequenceNumber();
            this.indexType = T_UINT8;
            this.length = length;
        }

        @Override
        public int computeSize(int initialPos) {
            return initialPos + Integer.BYTES * 3;
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putInt(elementType, buffer, initialPos);
            pos = CVUtil.putInt(indexType, buffer, pos);
            pos = CVUtil.putInt(length, buffer, pos);
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_ARRAY 0x%04x type=0x%04x len=%d indexType=0x%04x", getSequenceNumber(), elementType, length, indexType);
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + elementType;
            h = 31 * h + indexType;
            h = 31 * h + length;
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVTypeArrayRecord other = (CVTypeArrayRecord) obj;
            return this.elementType == other.elementType
                    && this.indexType == other.indexType
                    && this.length == other.length;
        }
    }

    static final class CVTypeServer2Record extends CVTypeRecord {

        byte[] guid;
        int age;
        String fileName;

        CVTypeServer2Record(byte[] guid, int age, String fileName) {
            super(LF_TYPESERVER2);
            assert (guid.length == 16);
            this.guid = guid;
            this.age = age;
            this.fileName = fileName;

            /*-
              for some very odd reason GUID is stored like this:
                int guid1 = in.getInt();
                int guid2 = in.getShort();
                int guid3 = in.getShort();
                byte[] guid5[10]
            */
            swap(this.guid, 0, 3);
            swap(this.guid, 1, 2);
            swap(this.guid, 4, 5);
            swap(this.guid, 6, 7);
        }

        @Override
        public int computeContents(byte[] buffer, int initialPos) {
            int pos = CVUtil.putBytes(guid, buffer, initialPos);
            pos = CVUtil.putInt(age, buffer, pos);
            pos = CVUtil.putUTF8StringBytes(fileName, buffer, pos);
            return pos;
        }

        @Override
        public String toString() {
            return String.format("LF_TYPESERVER2 0x%04x '%s' age=%d", getSequenceNumber(), fileName, age);
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + Arrays.hashCode(guid);
            h = 31 * h + age;
            h = 31 * h + fileName.hashCode();
            return h;
        }

        @Override
        public boolean equals(Object obj) {
            if (!super.equals(obj)) {
                return false;
            }
            CVTypeServer2Record other = (CVTypeServer2Record) obj;
            return Arrays.hashCode(this.guid) == Arrays.hashCode(other.guid)
                    && this.age == other.age
                    && this.fileName.hashCode() == other.fileName.hashCode();
        }

        private static void swap(byte[] b, int idx1, int idx2) {
            byte tmp = b[idx1];
            b[idx1] = b[idx2];
            b[idx2] = tmp;
        }
    }

    static String propertyString(int properties) {
        StringBuilder sb = new StringBuilder();

        /* Low byte. */
        if ((properties & 0x0001) != 0) {
            sb.append(" packed");
        }
        if ((properties & 0x0002) != 0) {
            sb.append(" ctor");
        }
        if ((properties & 0x0004) != 0) {
            sb.append(" ovlops");
        }
        if ((properties & 0x0008) != 0) {
            sb.append(" isnested");
        }
        if ((properties & 0x0010) != 0) {
            sb.append(" cnested");
        }
        if ((properties & 0x0020) != 0) {
            sb.append(" opassign");
        }
        if ((properties & 0x0040) != 0) {
            sb.append(" opcast");
        }
        if ((properties & 0x0080) != 0) {
            sb.append(" forwardref");
        }

        /* High byte. */
        if ((properties & 0x0100) != 0) {
            sb.append(" scope");
        }
        if ((properties & 0x0200) != 0) {
            sb.append(" hasuniquename");
        }
        if ((properties & 0x0400) != 0) {
            sb.append(" sealed");
        }
        if ((properties & 0x1800) != 0) {
            sb.append(" hfa...");
        }
        if ((properties & 0x2000) != 0) {
            sb.append(" intrinsic");
        }
        if ((properties & 0xc000) != 0) {
            sb.append(" macom...");
        }
        return sb.toString();
    }
}
