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

import com.oracle.objectfile.debugentry.ArrayTypeEntry;
import com.oracle.objectfile.debugentry.ClassEntry;
import com.oracle.objectfile.debugentry.FieldEntry;
import com.oracle.objectfile.debugentry.HeaderTypeEntry;
import com.oracle.objectfile.debugentry.InterfaceClassEntry;
import com.oracle.objectfile.debugentry.MethodEntry;
import com.oracle.objectfile.debugentry.PrimaryEntry;
import com.oracle.objectfile.debugentry.PrimitiveTypeEntry;
import com.oracle.objectfile.debugentry.Range;
import com.oracle.objectfile.debugentry.TypeEntry;
import org.graalvm.compiler.debug.DebugContext;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_CLASS;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MAX_PRIMITIVE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_CHAR;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_CHAR16;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_LONG;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_QUAD;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL32;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL64;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_SHORT;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_VOID;
import static com.oracle.objectfile.pecoff.cv.CVTypeRecord.CVClassRecord.ATTR_FORWARD_REF;
import static com.oracle.objectfile.pecoff.cv.CVTypeRecord.UNKNOWN_TYPE_INDEX;

class CVTypeSectionBuilder {

    private CVTypeSectionImpl typeSection;
    private DebugContext debugContext;
    private int depth = 0;

    CVTypeSectionBuilder(CVTypeSectionImpl typeSection) {
        this.typeSection = typeSection;
        addPrimitiveTypes();
    }

    /*
    0x106e 0x1505 len=46 LF_STRUCTURE count=0 properties=0x0280 fieldListIndex=0x0 derivedFrom=0x0 vshape=0x0
    0x106f 0x1002 len=10 LF_POINTER refType=0x00106e attrib=0x01000c kind=12 mode=0 modifiers=0 size=8 flags=0
0x1081 0x1203 len=238 LF_FIELDLIST:
    field LF_MEMBER type=0x150d attr=0x3 typeidx=0x0022 offset=0x0000 Version
    field LF_MEMBER type=0x150d attr=0x3 typeidx=0x106f offset=0x0008 Pool
    field LF_MEMBER type=0x150d attr=0x3 typeidx=0x1063 offset=0x0010 CleanupGroup
    field LF_MEMBER type=0x150d attr=0x3 typeidx=0x1072 offset=0x0018 CleanupGroupCancelCallback
    field LF_MEMBER type=0x150d attr=0x3 typeidx=0x0603 offset=0x0020 RaceDll
    field LF_MEMBER type=0x150d attr=0x3 typeidx=0x1074 offset=0x0028 ActivationContext
    field LF_MEMBER type=0x150d attr=0x3 typeidx=0x1079 offset=0x0030 FinalizationCallback
    field LF_MEMBER type=0x150d attr=0x3 typeidx=0x1080 offset=0x0038 u
    field LF_MEMBER type=0x150d attr=0x3 typeidx=0x103a offset=0x003c CallbackPriority
    field LF_MEMBER type=0x150d attr=0x3 typeidx=0x0022 offset=0x0040 Size
0x1082 0x1505 len=74 LF_STRUCTURE count=10 properties=0x0200 fieldListIndex=0x1081 derivedFrom=0x0 vshape=0x0
*/

    private static final int IN_PROCESS_DEPTH = 10;

    /* if a typename appears in this map, it is currently being constructed.
     * if it is referenced elsewhere while being constructed, a LF_CLASS with a
     * forward ref and a LF_POINTER are emitted, and the forward ref is used.
     */
    private Map<String, ClassEntry> inProcessMap = new HashMap<>(IN_PROCESS_DEPTH);

    CVTypeRecord buildType(TypeEntry typeEntry) {
        depth++;
        CVTypeRecord typeRecord = null;
        TypeEntry inProcessType = inProcessMap.get(typeEntry.getTypeName());
        if (inProcessType != null) {
            typeRecord = buildForwardReference(typeEntry);
        } else {
            typeRecord = typeSection.getType(typeEntry.getTypeName());
            if (typeRecord != null) {
                log("buildType type %s(%s) is known %s", typeEntry.getTypeName(), typeEntry.typeKind().name(), typeRecord);
            } else {
                log("buildType %s %s - begin", typeEntry.typeKind().name(), typeEntry.getTypeName());
                switch (typeEntry.typeKind()) {
                    case PRIMITIVE: {
                        PrimitiveTypeEntry type = (PrimitiveTypeEntry) typeEntry;
                        //int attr = type.getFlags();
                        typeRecord = typeSection.getType(typeEntry.getTypeName());
                        //log("      typeentry idx=0x%04x sz=0x%x attr=0x%x primitive %s", typeRecord.getSequenceNumber(), typeEntry.getSize(), attr, typeEntry.getTypeName());
                        break;
                    }
                    case ARRAY: {
                        ArrayTypeEntry type = (ArrayTypeEntry) typeEntry;
                        final TypeEntry elementType = type.getElementType();
                        int elementIndex = buildType(elementType).getSequenceNumber();
                        // aseSize, lengthOffset
                        CVTypeRecord.CVTypeArrayRecord record = new CVTypeRecord.CVTypeArrayRecord(elementIndex, T_QUAD, type.getSize());
                        typeRecord = addTypeRecord(record);
                        log("LF_ARRAY idx=0x%04x sz=0x%x baseIndex=0x%04x [%s]", typeRecord.getSequenceNumber(), type.getSize(), elementIndex, elementType.getTypeName());
                        break;
                    }
                    case ENUM:
                        /* EnumClassEntry returns INSTANCE for typeKind() so this code is never called. */
                        /* This is fine, as a Java enum is basically a class, and so we treat it as such. */
                    case INSTANCE: {
                        ClassEntry type = (ClassEntry) typeEntry;
                        typeRecord = buildClass(type);
                        log("LF_CLASS idx=0x%04x sz=0x%x %s", typeRecord.getSequenceNumber(), typeEntry.getSize(), typeEntry.getTypeName());
                        break;
                    }
                    case INTERFACE: {
                        InterfaceClassEntry type = (InterfaceClassEntry) typeEntry;
                        typeRecord = buildClass(type);
                        log("LF_INTERFACE idx=0x%04x sz=0x%x %s", typeRecord.getSequenceNumber(), typeEntry.getSize(), typeEntry.getTypeName());
                        break;
                    }
                    case HEADER: {
                        // TODO probably a class entry?
                        HeaderTypeEntry type = (HeaderTypeEntry) typeEntry;
                        typeRecord = typeSection.getType(typeEntry.getTypeName());
                        if (typeRecord != null) {
                            log("header idx=0x%04x sz=0x%x %s", typeRecord.getSequenceNumber(), typeEntry.getSize(), typeEntry.getTypeName());
                        } else {
                            typeRecord = typeSection.defineIncompleteType(typeEntry.getTypeName());
                            log("header NULL idx=0x%04x sz=0x%x %s", UNKNOWN_TYPE_INDEX, typeEntry.getSize(), typeEntry.getTypeName());
                        }
                        break;
                    }
                }
            }
        }
        log("buildType end: %s", typeRecord);
        depth--;
        return typeRecord;
    }

    private CVTypeRecord buildForwardReference(TypeEntry typeEntry) {
        CVTypeRecord fref = typeSection.getType(typeEntry.getTypeName());
        if (fref == null) {
            fref = addTypeRecord(new CVTypeRecord.CVClassRecord((short) ATTR_FORWARD_REF, typeEntry.getTypeName(), null));
            log("buildForwardReference: type %s (%s): added %s", typeEntry.getTypeName(), typeEntry.typeKind().name(), fref);
        }
        return fref;
    }

    private int getIndexForPointer(ClassEntry entry) {
        CVTypeRecord ptrRecord = typeSection.getPointerRecordForType(entry.getTypeName());
        if (ptrRecord == null) {
            CVTypeRecord clsRecord = typeSection.getType(entry.getTypeName());
            if (clsRecord == null) {
                /* we've never heard of this class (but it may be in process) */
                clsRecord = buildType(entry);
            } else if (clsRecord.getSequenceNumber() <= MAX_PRIMITIVE) {
                return clsRecord.getSequenceNumber();
            }
            /* We now have a class record but must create a pointer record. */
            ptrRecord = addTypeRecord(new CVTypeRecord.CVTypePointerRecord(clsRecord.getSequenceNumber(), CVTypeRecord.CVTypePointerRecord.NORMAL_64));
        }
        return ptrRecord.getSequenceNumber();
    }

    /**
     * If the type of the pointee is a primitive type, return it directly.
     * Otherwise, create (if needed) and return a record representing a pointer to class.
     */
    private int getIndexForPointer(String typeName) {
        CVTypeRecord ptrRecord = typeSection.getPointerRecordForType(typeName);
        if (ptrRecord == null) {
            CVTypeRecord clsRecord = typeSection.getType(typeName);
            if (clsRecord == null) {
                /* we've never heard of this class (but it may be in process) */
                clsRecord = addTypeRecord(new CVTypeRecord.CVClassRecord((short) ATTR_FORWARD_REF, typeName, null));
            } else if (clsRecord.getSequenceNumber() <= MAX_PRIMITIVE) {
                return clsRecord.getSequenceNumber();
            }
            /* We now have a class record but must create a pointer record. */
            ptrRecord = addTypeRecord(new CVTypeRecord.CVTypePointerRecord(clsRecord.getSequenceNumber(), CVTypeRecord.CVTypePointerRecord.NORMAL_64));
        }
        return ptrRecord.getSequenceNumber();
    }

    private int getIndexForType(TypeEntry entry) {
        CVTypeRecord clsRecord = typeSection.getType(entry.getTypeName());
        if (clsRecord == null) {
            clsRecord = buildType(entry);
        }
        return clsRecord.getSequenceNumber();
    }

    /* Build a class or Enum */
    private CVTypeRecord buildClass(ClassEntry classEntry) {

        CVTypeRecord classRecord = typeSection.getType(classEntry.getTypeName());
        if (classRecord == null) {
            log("classentry size=%d kind=%s %s", classEntry.getSize(), classEntry.typeKind().name(), classEntry.getTypeName());
            depth++;
            inProcessMap.put(classEntry.getTypeName(), classEntry);
            /* process super type */
            int superIdx = 0;
            ClassEntry superClass = classEntry.getSuperClass();
            if (superClass != null) {
                if (typeSection.hasType(superClass.getTypeName())) {
                    superIdx = typeSection.getType(superClass.getTypeName()).getSequenceNumber();
                } else {
                    log("building superclass %s", superClass.getTypeName());
                    superIdx = getIndexForType(superClass);
                    log("finished superclass %s idx=0x%x", superClass.getTypeName(), superIdx);
                }
            }

            /* process fields */
            int fieldListIdx = 0;
            int fieldListCount = 0;
            if (classEntry.fields().count() > 0 || classEntry.methods().count() > 0) {
                depth++;
                CVTypeRecord.CVFieldListRecord fieldListRecord = new CVTypeRecord.CVFieldListRecord();
                log("building fields %s", fieldListRecord);

                /* Skip over unmanfested fields. */
                classEntry.fields().filter(CVTypeSectionBuilder::isManifestedField).forEach(f -> {
                    log("field %s attr=(%s) offset=%d size=%d valuetype=%s", f.fieldName(), f.getModifiersString(), f.getOffset(), f.getSize(), f.getValueType().getTypeName());
                    CVTypeRecord.CVMemberRecord fieldRecord = buildField(f);
                    log("field %s", fieldRecord);
                    fieldListRecord.add(fieldRecord);
                });

                log("building methods");
                /*
                 * Functions go into the main fieldList if they are not overloaded.
                 * Overloaded functions get a M_FUNCTION entry in the field list,
                 * and a LF_METHODLIST record pointing to M_MFUNCTION records for each overload.
                 */
                if (classEntry.methods().count() > 0) {

                    /* first build a list of all overloaded functions */
                    HashSet<String> overloaded = new HashSet<>((int) classEntry.methods().count());
                    HashSet<String> allFunctions = new HashSet<>((int) classEntry.methods().count());
                    classEntry.methods().forEach(m -> {
                        if (allFunctions.contains(m.methodName())) {
                            overloaded.add(m.methodName());
                        } else {
                            allFunctions.add(m.methodName());
                        }
                    });

                    overloaded.forEach(mname -> {
                        depth++;

                        /* LF_METHODLIST */
                        CVTypeRecord.CVTypeMethodListRecord mlist = new CVTypeRecord.CVTypeMethodListRecord();

                        /* LF_MFUNCTION records */
                        classEntry.methods().filter(methodEntry -> methodEntry.methodName().equals(mname)).forEach(m -> {
                            log("overloaded method %s(%s) attr=(%s) valuetype=%s", m.fieldName(), m.methodName(), m.getModifiersString(), m.getValueType().getTypeName());
                            CVTypeRecord.CVTypeMFunctionRecord mFunctionRecord = buildMemberFunction(classEntry, m);
                            addTypeRecord(mFunctionRecord);
                            log("    overloaded method %s", mFunctionRecord);
                            mlist.add((short) 0, (short) 0, mFunctionRecord.getSequenceNumber());
                        });

                        CVTypeRecord.CVTypeMethodListRecord nmlist = addTypeRecord(mlist);

                        // LF_METHOD record
                        CVTypeRecord.CVMemberMethodRecord methodRecord = new CVTypeRecord.CVMemberMethodRecord((short) nmlist.count(), nmlist.getSequenceNumber(), mname);
                        fieldListRecord.add(methodRecord);
                        depth--;
                    });

                    classEntry.methods().filter(methodEntry -> !overloaded.contains(methodEntry.methodName())).forEach(m -> {
                        log("unique method %s(%s) attr=(%s) valuetype=%s", m.fieldName(), m.methodName(), m.getModifiersString(), m.getValueType().getTypeName());
                        CVTypeRecord.CVOneMethodRecord method = buildMethod(classEntry, m);
                        log("    unique method %s", method);
                        fieldListRecord.add(method);
                    });
                }
                /* Only bother to build fieldlist record is there are actually manifested fields. */
                if (fieldListRecord.count() > 0) {
                    CVTypeRecord.CVFieldListRecord newfieldListRecord = addTypeRecord(fieldListRecord);
                    fieldListIdx = newfieldListRecord.getSequenceNumber();
                    fieldListCount = newfieldListRecord.count();
                    log("finished building fields %s", newfieldListRecord);
                } else {
                    log("finished building fields - no manifested fields found in %s (%d declared)", classEntry.getTypeName(), classEntry.fields().count());
                }
                depth--;
            }

            /* Build final class record. */
            short count = (short)fieldListCount; /* count of number of elements in class */
            short attrs = 0; /* property attribute field (prop_t) */
            int vshapeIndex = 0; /* type index of vshape table for this class */
            classRecord = new CVTypeRecord.CVClassRecord(LF_CLASS, count, attrs, fieldListIdx, superIdx, vshapeIndex, classEntry.getSize(), classEntry.getTypeName(), null);
            classRecord = addTypeRecord(classRecord);
            depth--;
            /* we've added the complete class record now, */
            inProcessMap.remove(classEntry.getTypeName());
            log("  finished class %s", classRecord);
        }
       // CVTypeRecord.CVTypePointerRecord pointerType = new CVTypeRecord.CVTypePointerRecord(classRecord.getSequenceNumber(), CVTypeRecord.CVTypePointerRecord.NORMAL_64);
       // pointerType = addTypeRecord(pointerType);
        /* This is Java; we define a pointer type and return it instead of the class */
        return classRecord;
    }

    private static boolean isManifestedField(FieldEntry fieldEntry) {
        return fieldEntry.getOffset() >= 0;
    }

    /*
   0x100f 0x1203 len=186 LF_FIELDLIST:
     field LF_ENUMERATE type=0x1502 attr=0x3 l=1 JOB_OBJECT_NET_RATE_CONTROL_ENABLE
     field LF_ENUMERATE type=0x1502 attr=0x3 l=2 JOB_OBJECT_NET_RATE_CONTROL_MAX_BANDWIDTH
     field LF_ENUMERATE type=0x1502 attr=0x3 l=4 JOB_OBJECT_NET_RATE_CONTROL_DSCP_TAG
     field LF_ENUMERATE type=0x1502 attr=0x3 l=7 JOB_OBJECT_NET_RATE_CONTROL_VALID_FLAGS
   0x1010 0x1507 len=90 LF_ENUM count=4 properties=0x0200 fieldListIndex=0x100f underLyingTypeIndex=0x74 JOB_OBJECT_NET_RATE_CONTROL_FLAGS
*/
    /* use the standard class builder for enums
    private CVTypeRecord buildEnum(EnumClassEntry type) {
        depth++;
        log("in buildenum %s\n", type.getTypeName());
        CVTypeRecord.CVFieldListRecord fields = new CVTypeRecord.CVFieldListRecord();
        type.fields().forEach(f -> {
            log("        LF_ENUMERATE offset=%d %s %s\n", f.getOffset(), f.getModifiersString(), f.fieldName());
            short attr = (short) (Modifier.isPublic(f.getModifiers()) ? 0x03 : (Modifier.isPrivate(f.getModifiers())) ? 0x01 : 0x02);
            CVTypeRecord.CVEnumerateRecord er = new CVTypeRecord.CVEnumerateRecord(attr, f.getOffset(), f.fieldName());
            fields.addMember(er);
        });
        CVTypeRecord.CVFieldListRecord nfields = addTypeRecord(fields);
        CVTypeRecord.CVEnumRecord enumRecord = new CVTypeRecord.CVEnumRecord((short)0x3, T_LONG, nfields, type.getTypeName());
        // TODO: are enum names unique (i.e. qualifed by class) or not?
        CVTypeRecord.CVEnumRecord nenumRecord = typeSection.defineType(type.getTypeName(), enumRecord);
        log("      LF_ENUM idx=0x%04x sz=0x%x enum %s", nenumRecord.getSequenceNumber(), type.getSize(), type.getTypeName());
        depth--;
        return nenumRecord;
    }
     */

    private CVTypeRecord.CVMemberRecord buildField(FieldEntry fieldEntry) {
        TypeEntry valueType = fieldEntry.getValueType();
        CVTypeRecord valueTypeRecord = buildType(valueType);
        int vtIndex = valueTypeRecord.getSequenceNumber();
        short attr = (short) (Modifier.isPublic(fieldEntry.getModifiers()) ? 0x03 : (Modifier.isPrivate(fieldEntry.getModifiers())) ? 0x01 : 0x02);
        return new CVTypeRecord.CVMemberRecord(attr, vtIndex, fieldEntry.getOffset(), fieldEntry.fieldName());
    }

    private CVTypeRecord.CVOneMethodRecord buildMethod(ClassEntry classEntry, MethodEntry methodEntry) {
        short attr = (short) (Modifier.isPublic(methodEntry.getModifiers()) ? 0x03 : (Modifier.isPrivate(methodEntry.getModifiers())) ? 0x01 : 0x02);
        int offset = 0x999999; /* TODO */
        CVTypeRecord.CVTypeMFunctionRecord funcRecord = buildMemberFunction(classEntry, methodEntry);
        return new CVTypeRecord.CVOneMethodRecord(attr, funcRecord.getSequenceNumber(), offset, methodEntry.methodName());
    }

    private CVTypeRecord.CVTypeMFunctionRecord buildMemberFunction(ClassEntry classEntry, MethodEntry methodEntry) {
        CVTypeRecord.CVTypeMFunctionRecord mFunctionRecord = new CVTypeRecord.CVTypeMFunctionRecord();
        mFunctionRecord.setClassType(getIndexForType(classEntry));
        mFunctionRecord.setThisType(getIndexForPointer(classEntry));
        mFunctionRecord.setCallType((byte) 0);
        short attr = (short) (Modifier.isPublic(methodEntry.getModifiers()) ? 0x03 : (Modifier.isPrivate(methodEntry.getModifiers())) ? 0x01 : 0x02);
        mFunctionRecord.setFuncAttr((byte) attr);
        mFunctionRecord.setReturnType(getIndexForPointer(methodEntry.getValueType().getTypeName()));
        CVTypeRecord.CVTypeArglistRecord argListType = new CVTypeRecord.CVTypeArglistRecord();
        for (int i = 0; i < methodEntry.getParamCount(); i++) {
            argListType.add(getIndexForPointer(methodEntry.getParamType(i).getTypeName()));
        }
        argListType = addTypeRecord(argListType);
        mFunctionRecord.setArgList(argListType);
        return addTypeRecord(mFunctionRecord);
    }
    /**
     * Add type records for function. (later add arglist and local types).
     * This works better if we had ClassEntries instead of strings.
     *
     * @param entry primaryEntry containing entities whoses type records must be added
     * @return type index of function type
     */
    CVTypeRecord buildFunction(PrimaryEntry entry) {
        Range primary = entry.getPrimary();

        log("build primary start:" + primary.getFullMethodNameWithParams());
        depth++;

        /* Build return type records. */
        int returnTypeIndex = getIndexForPointer(primary.getMethodReturnTypeName());

        /* Build arglist type record. */
        CVTypeRecord.CVTypeArglistRecord argListType = new CVTypeRecord.CVTypeArglistRecord();
        argListType.add(T_VOID);
        argListType = addTypeRecord(argListType);

        /* Build actual type record */
        CVTypeRecord funcType = addTypeRecord(new CVTypeRecord.CVTypeProcedureRecord().returnType(returnTypeIndex).argList(argListType));
        depth--;
        log("build prinmary end:" + primary.getFullMethodNameWithParams());
        return funcType;
    }

    private void addPrimitiveTypes() {
        typeSection.definePrimitiveType("void", T_VOID);
        typeSection.definePrimitiveType("byte", T_CHAR);
        typeSection.definePrimitiveType("boolean", T_CHAR);
        typeSection.definePrimitiveType("char", T_CHAR16);  // unsigned
        typeSection.definePrimitiveType("short", T_SHORT);
        typeSection.definePrimitiveType("int", T_LONG);
        typeSection.definePrimitiveType("long", T_QUAD);
        typeSection.definePrimitiveType("float", T_REAL32);
        typeSection.definePrimitiveType("double", T_REAL64);
    }

    private <T extends CVTypeRecord> T addTypeRecord(T record) {
        return typeSection.addOrReference(record);
    }

    void setDebugContext(DebugContext debugContext) {
        this.debugContext = debugContext;
    }

    private void log(String fmt, Object... args) {
        char[] blanks = new char[depth];
        Arrays.fill(blanks, ' ');
        String indent = new String(blanks);
        System.out.format(indent + fmt + "\n", args);
    }
}


