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
import com.oracle.objectfile.debugentry.MemberEntry;
import com.oracle.objectfile.debugentry.MethodEntry;
import com.oracle.objectfile.debugentry.PrimaryEntry;
import com.oracle.objectfile.debugentry.Range;
import com.oracle.objectfile.debugentry.StructureTypeEntry;
import com.oracle.objectfile.debugentry.TypeEntry;
import com.oracle.objectfile.debuginfo.DebugInfoProvider;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.CV_CALL_TYPE_C;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.CV_CALL_TYPE_THISCALL;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_CLASS;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MAX_PRIMITIVE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_PRIVATE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_PROTECTED;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_PUBLIC;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_STATIC;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_VIRTUAL;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_64PINT1;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_64PINT2;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_64PINT4;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_64PINT8;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_64PREAL32;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_64PREAL64;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_64PUINT1;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_64PVOID;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_64PWCHAR;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_INT1;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_INT2;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_INT4;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_INT8;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL32;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL64;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_UINT1;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_UINT4;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_VOID;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_WCHAR;
import static com.oracle.objectfile.pecoff.cv.CVTypeRecord.CVClassRecord.ATTR_FORWARD_REF;

class CVTypeSectionBuilder {

    // private static final String MAGIC_OBJECT_HEADER_TYPE = "_objhdr";
    // private static final String JAVA_LANG_CLASS = "java.lang.Class";
    private static final String JAVA_LANG_OBJECT = "java.lang.Object";

    private int objectHeaderRecordIndex;
    private int javaLangObjectRecordIndex;

    private final CVTypeSectionImpl typeSection;
    private DebugContext debugContext;
    private HeaderTypeEntry globalHeaderEntry;
    private int depth = 0;

    static class TypeInfo {
        CVTypeRecord record;
        TypeEntry typeEntry;

        TypeInfo(CVTypeRecord record, TypeEntry typeEntry) {
            this.record = record;
            this.typeEntry = typeEntry;
        }
    }

    /*
     * A map of type names to type records. Only forward references are stored here, and only until
     * they are defined.
     */
    private static final int MAX_FORWARD_REFS = 100;
    private final Map<String, TypeInfo> typeInfoMap = new HashMap<>(MAX_FORWARD_REFS);

    CVTypeSectionBuilder(CVTypeSectionImpl typeSection) {
        this.typeSection = typeSection;
        addPrimitiveTypes();
    }

    void buildRemainingRecords() {
        /* Currently java.lang.Class is the only type undefined at this point. */
        for (TypeInfo ti : typeInfoMap.values()) {
            if (ti.record.type == LF_CLASS && ((CVTypeRecord.CVClassRecord) ti.record).isForwardRef()) {
                assert ti.typeEntry.isClass();
                if (ti.typeEntry == null) {
                    // int idx = getUnderlyingType(((CVTypeRecord.CVClassRecord)
                    // ti.record).getClassName());
                    log("no typeentry for %s; type remains incomplete", ((CVTypeRecord.CVClassRecord) ti.record).getClassName());
                    GraalError.shouldNotReachHere();
                } else {
                    buildType(ti.typeEntry);
                }
            }
        }
        /* Sanity check. */
        for (TypeInfo ti : typeInfoMap.values()) {
            if (ti.record.type == LF_CLASS && ((CVTypeRecord.CVClassRecord) ti.record).isForwardRef()) {
                log("still have undefined type %s", ti.record);
                GraalError.shouldNotReachHere();
            }
        }
    }

    @SuppressWarnings("unused")
    private int getUnderlyingType(String typeName) {
        final int idx;
        if (typeName.endsWith("[]")) {
            idx = getUnderlyingType(typeName.substring(0, typeName.length() - 2));
        } else {
            /* A non-array typename. Find the record or pointer. */
            CVTypeRecord clsRecord = typeSection.getType(typeName);
            idx = clsRecord != null ? clsRecord.getSequenceNumber() : 0;
        }
        return idx;
    }

    private static final int IN_PROCESS_DEPTH = 10;

    /*
     * If a typename appears in inProcessMap, it is currently being constructed. if it is referenced
     * elsewhere while being constructed, a LF_CLASS with a forward ref and a LF_POINTER are
     * emitted, and the index to the forward ref is used.
     */
    private final Map<String, TypeEntry> inProcessMap = new HashMap<>(IN_PROCESS_DEPTH);

    CVTypeRecord buildType(TypeEntry typeEntry) {
        depth++;
        CVTypeRecord typeRecord;
        TypeEntry inProcessType = inProcessMap.get(typeEntry.getTypeName());
        if (typeEntry.getTypeName().contains("[]") && typeEntry.typeKind() != DebugInfoProvider.DebugTypeInfo.DebugTypeKind.ARRAY) {
            log("Rogue array found: %s %s", typeEntry.typeKind().name(), typeEntry.getTypeName());
            GraalError.shouldNotReachHere();
        }
        if (inProcessType != null) {
            typeRecord = buildForwardReference(typeEntry);
        } else {
            typeRecord = typeSection.getType(typeEntry.getTypeName());
            /*
             * If we've never seen the class or only defined it as a forward reference, define it
             * now.
             */
            if (typeRecord != null && typeRecord.type == LF_CLASS && !((CVTypeRecord.CVClassRecord) typeRecord).isForwardRef()) {
                log("buildType() type %s(%s) is known %s", typeEntry.getTypeName(), typeEntry.typeKind().name(), typeRecord);
            } else {
                log("buildType() %s %s size=%d - begin", typeEntry.typeKind().name(), typeEntry.getTypeName(), typeEntry.getSize());
                switch (typeEntry.typeKind()) {
                    case PRIMITIVE: {
                        typeRecord = typeSection.getType(typeEntry.getTypeName());
                        break;
                    }
                    case ARRAY: {
                        typeRecord = buildArray((ArrayTypeEntry) typeEntry);
                        break;
                    }
                    case ENUM:
                        /*
                         * EnumClassEntry returns INSTANCE for typeKind() so ENUM switch case is
                         * never called.
                         */
                    case INSTANCE: {
                        typeRecord = buildClass((ClassEntry) typeEntry);
                        break;
                    }
                    case INTERFACE: {
                        typeRecord = buildClass((InterfaceClassEntry) typeEntry);
                        break;
                    }
                    case HEADER: {
                        /*
                         * The bits at the beginning of an Object: contains pointer to DynamicHub.
                         */
                        if (globalHeaderEntry == null) {
                            globalHeaderEntry = (HeaderTypeEntry) typeEntry;
                            /*
                             * create a synthetic class for this object and make java.lang.Object
                             * derive from it
                             */
                            log("Object header size=%d kind=%s %s", typeEntry.getSize(), typeEntry.typeKind().name(), typeEntry.getTypeName());
                            typeRecord = buildStruct((HeaderTypeEntry) typeEntry, 0, null, null);
                            objectHeaderRecordIndex = typeRecord.getSequenceNumber();
                        } else {
                            log("More than one object header (%s) has been defined", typeEntry.getTypeName());
                            GraalError.shouldNotReachHere();
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

    private CVTypeRecord buildForwardReference(TypeEntry entry) {
        CVTypeRecord record = typeSection.getType(entry.getTypeName());
        if (record == null) {
            record = addTypeRecord(new CVTypeRecord.CVClassRecord((short) ATTR_FORWARD_REF, entry.getTypeName(), null));
            typeInfoMap.put(entry.getTypeName(), new TypeInfo(record, entry));
            log("buildForwardReference: type %s (%s): added %s", entry.getTypeName(), entry.typeKind().name(), record);
        }
        return record;
    }

    /**
     * Return a CV type index for a pointer to a java type, or the type itself if a primitive.
     *
     * @param entry The java type to return a typeindex for.
     * @param onlyForwardReference If true, and the type has not previously been seen, do not
     *            attempt to generate a record for the typeitself, only generate a forward reference
     *            at this time. This is used to avoid infinite recursion when processing
     *            self-referencing types.
     * @return The index for the typeentry for a pointer to the type.
     */
    int getIndexForPointerOrPrimitive(TypeEntry entry, boolean onlyForwardReference) {
        if (entry.isPrimitive()) {
            CVTypeRecord record = typeSection.getType(entry.getTypeName());
            assert record != null;
            return record.getSequenceNumber();
        }
        CVTypeRecord ptrRecord = typeSection.getPointerRecordForType(debugContext, entry.getTypeName());
        if (ptrRecord == null) {
            CVTypeRecord record = typeSection.getType(entry.getTypeName());
            if (record == null) {
                /* we've never heard of this class (but it may be in process) */
                if (onlyForwardReference) {
                    record = addTypeRecord(new CVTypeRecord.CVClassRecord((short) ATTR_FORWARD_REF, entry.getTypeName(), null));
                    typeInfoMap.put(entry.getTypeName(), new TypeInfo(record, entry));
                    log("getIndexForPointerOrPrimitive: type %s (%s): added %s", entry.getTypeName(), entry.typeKind().name(), record);
                } else {
                    record = buildType(entry);
                }
            } else if (record.getSequenceNumber() <= MAX_PRIMITIVE) {
                /* Primitive types are referenced directly */
                return record.getSequenceNumber();
            }
            /* We now have a class record but must create a pointer record. */
            ptrRecord = addTypeRecord(new CVTypeRecord.CVTypePointerRecord(record.getSequenceNumber(), CVTypeRecord.CVTypePointerRecord.NORMAL_64));
        }
        return ptrRecord.getSequenceNumber();
    }

    /**
     * If the type of the pointee is a primitive type, return it directly. Otherwise, create (if
     * needed) and return a record representing a pointer to class.
     *
     * private int getIndexForPointer(String typeName) { CVTypeRecord ptrRecord =
     * typeSection.getPointerRecordForType(typeName); if (ptrRecord == null) { CVTypeRecord
     * clsRecord = typeSection.getType(typeName); if (clsRecord == null) { /* we've never heard of
     * this class (but it may be in process) * clsRecord = addTypeRecord(new
     * CVTypeRecord.CVClassRecord((short) ATTR_FORWARD_REF, typeName, null));
     * typeInfoMap.put(typeName, new TypeInfo(clsRecord, null)); } else if
     * (clsRecord.getSequenceNumber() <= MAX_PRIMITIVE) { return clsRecord.getSequenceNumber(); } /*
     * We now have a class record but must create a pointer record. * ptrRecord = addTypeRecord(new
     * CVTypeRecord.CVTypePointerRecord(clsRecord.getSequenceNumber(),
     * CVTypeRecord.CVTypePointerRecord.NORMAL_64)); } return ptrRecord.getSequenceNumber(); }
     */

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
        if (classRecord == null || classRecord.type == LF_CLASS && ((CVTypeRecord.CVClassRecord) classRecord).isForwardRef()) {
            log("classentry size=%d kind=%s %s", classEntry.getSize(), classEntry.typeKind().name(), classEntry.getTypeName());
            depth++;
            inProcessMap.put(classEntry.getTypeName(), classEntry);
            /* Process super type. */
            final int superIdx;
            ClassEntry superClass = classEntry.getSuperClass();
            if (superClass != null) {
                if (typeSection.hasType(superClass.getTypeName())) {
                    superIdx = typeSection.getType(superClass.getTypeName()).getSequenceNumber();
                } else {
                    log("building superclass %s", superClass.getTypeName());
                    superIdx = getIndexForType(superClass);
                    log("finished superclass %s idx=0x%x", superClass.getTypeName(), superIdx);
                }
            } else if (classEntry.getTypeName().equals(JAVA_LANG_OBJECT)) {
                superIdx = objectHeaderRecordIndex;
            } else {
                superIdx = javaLangObjectRecordIndex;
            }

            /* process fields */
            int fieldListIdx = 0;
            int fieldListCount = 0;
            if (classEntry.fields().count() > 0 || classEntry.methods().count() > 0 || superIdx != 0) {
                depth++;
                CVTypeRecord.CVFieldListRecord fieldListRecord = new CVTypeRecord.CVFieldListRecord();
                log("building fields %s", fieldListRecord);

                if (superIdx != 0) {
                    CVTypeRecord.CVBaseMemberRecord btype = new CVTypeRecord.CVBaseMemberRecord((short) 0x3, superIdx, 0);
                    log("basetype %s", btype);
                    fieldListRecord.add(btype);
                }

                /* Only define manifested fields. */
                classEntry.fields().filter(CVTypeSectionBuilder::isManifestedField).forEach(f -> {
                    log("field %s attr=(%s) offset=%d size=%d valuetype=%s", f.fieldName(), f.getModifiersString(), f.getOffset(), f.getSize(), f.getValueType().getTypeName());
                    CVTypeRecord.FieldRecord fieldRecord = buildField(f, false);
                    log("field %s", fieldRecord);
                    fieldListRecord.add(fieldRecord);
                });

                log("building methods");
                /*
                 * Functions go into the main fieldList if they are not overloaded. Overloaded
                 * functions get a M_FUNCTION entry in the field list, and a LF_METHODLIST record
                 * pointing to M_MFUNCTION records for each overload.
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

                        /* LF_METHOD record */
                        CVTypeRecord.CVMemberMethodRecord methodRecord = new CVTypeRecord.CVMemberMethodRecord((short) nmlist.count(), nmlist.getSequenceNumber(), mname);
                        fieldListRecord.add(methodRecord);
                        depth--;
                    });

                    classEntry.methods().filter(methodEntry -> !overloaded.contains(methodEntry.methodName())).forEach(m -> {
                        log("`unique method %s %s %s(...)", m.fieldName(), m.methodName(), m.getModifiersString(), m.getValueType().getTypeName(), m.methodName());
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
            short count = (short) fieldListCount; /* count of number of elements in class */
            short attrs = 0; /* property attribute field (prop_t) */
            int vshapeIndex = 0; /* type index of vshape table for this class */
            classRecord = new CVTypeRecord.CVClassRecord(LF_CLASS, count, attrs, fieldListIdx, 0, vshapeIndex, classEntry.getSize(), classEntry.getTypeName(), null);
            classRecord = addTypeRecord(classRecord);
            /* remove any forward refs from the to do list */
            typeInfoMap.remove(classEntry.getTypeName());

            /* Save this in case we find a class with no superclass */
            if (classEntry.getTypeName().equals(JAVA_LANG_OBJECT)) {
                javaLangObjectRecordIndex = classRecord.getSequenceNumber();
            }

            /* Add a UDT record (if we have the information) */
            /* Try to find a line number - if none, don't bother to create the record. */
            int line = classEntry.getPrimaryEntries().isEmpty() ? 0 : classEntry.getPrimaryEntries().get(0).getPrimary().getLine();
            if (line > 0) {
                int idIdx = typeSection.getStringId(classEntry.getFileName()).getSequenceNumber();
                CVTypeRecord.CVUdtTypeLineRecord udt = new CVTypeRecord.CVUdtTypeLineRecord(classRecord.getSequenceNumber(), idIdx, line);
                addTypeRecord(udt);
            }

            /* TODO: May need to add S_UDT record to symbol table. */

            depth--;
            /* we've added the complete class record now, */
            inProcessMap.remove(classEntry.getTypeName());
            log("  finished class %s", classRecord);
        }
        return classRecord;
    }

    private CVTypeRecord buildArray(ArrayTypeEntry typeEntry) {
        /* Model an array as a struct with a pointer, a length and then array of length 0 */
        /* String[] becomes struct String[] : Object { DynamicHub *; int length; String*[0]; } */

        /* Build 0 length array. */
        final TypeEntry elementType = typeEntry.getElementType();
        int elementTypeIndex = getIndexForPointerOrPrimitive(elementType, false);
        CVTypeRecord array0record = addTypeRecord(new CVTypeRecord.CVTypeArrayRecord(elementTypeIndex, T_UINT4, 0));

        /* Build a field for the 0 length array. */
        CVTypeRecord.CVMemberRecord dm = new CVTypeRecord.CVMemberRecord((short) 0x03, array0record.getSequenceNumber(), 0, "data");

        CVTypeRecord.CVMemberRecord[] fields = {dm};
        CVTypeRecord record = buildStruct(typeEntry, javaLangObjectRecordIndex, typeEntry.getTypeName(), fields);
        log("build ARRAY: %s", record);
        return record;
    }

    private CVTypeRecord buildStruct(StructureTypeEntry typeEntry, int superTypeIndex, String actualTypeName, CVTypeRecord.CVMemberRecord[] extraFields) {
        /* Create a synthetic class for this object and make java.lang.Object derive from it. */
        /* Used for arrays, objhdr_ (and perhaps synthetic structs later on). */
        depth++;
        String typeName = actualTypeName != null ? actualTypeName : typeEntry.getTypeName();
        inProcessMap.put(typeName, typeEntry);

        int fieldListIdx = 0;
        int fieldListCount = 0;
        int totalHeaderFieldSize = 0;

        if (typeEntry.fields().count() > 0 || extraFields != null || superTypeIndex != 0) {
            depth++;
            CVTypeRecord.CVFieldListRecord fieldListRecord = new CVTypeRecord.CVFieldListRecord();
            log("building fields %s", fieldListRecord);

            if (superTypeIndex != 0) {
                CVTypeRecord.CVBaseMemberRecord btype = new CVTypeRecord.CVBaseMemberRecord((short) 0x3, superTypeIndex, 0);
                log("basetype %s", btype);
                fieldListRecord.add(btype);
                /* TODO - the size is unknown here unless we refactor a bunch. */
                totalHeaderFieldSize = Math.max(totalHeaderFieldSize, 0);
            }

            for (Object field : typeEntry.fields().toArray()) {
                FieldEntry f = (FieldEntry) field;
                if (isManifestedField(f)) {
                    log("field %s attr=(%s) offset=%d size=%d valuetype=%s", f.fieldName(), f.getModifiersString(), f.getOffset(), f.getSize(), f.getValueType().getTypeName());
                    CVTypeRecord.FieldRecord fieldRecord = buildField(f, true);
                    log("field %s", fieldRecord);
                    fieldListRecord.add(fieldRecord);
                    totalHeaderFieldSize = Math.max(totalHeaderFieldSize, f.getOffset() + f.getSize());
                }
            }

            if (extraFields != null) {
                for (CVTypeRecord.CVMemberRecord fieldRecord : extraFields) {
                    fieldRecord.setOffset((typeEntry.getSize() + 7) & ~0x7);
                    log("synthetic field %s", fieldRecord);
                    fieldListRecord.add(fieldRecord);
                    /*
                     * TODO don't know size of element totalHeaderFieldSize =
                     * Math.max(totalHeaderFieldSize, fieldRecord.offset));
                     */
                }
            }

            /* Only bother to build fieldlist record is there are actually manifested fields. */
            if (fieldListRecord.count() > 0) {
                CVTypeRecord.CVFieldListRecord newfieldListRecord = addTypeRecord(fieldListRecord);
                fieldListIdx = newfieldListRecord.getSequenceNumber();
                fieldListCount = newfieldListRecord.count();
                log("finished building fields %s", newfieldListRecord);
            } else {
                log("finished building fields - no manifested fields found in %s (%d declared)", typeName, typeEntry.fields().count());
            }
            depth--;
        }
        /* Build final class record. */
        short attrs = 0; /* property attribute field (prop_t) */
        CVTypeRecord typeRecord = new CVTypeRecord.CVClassRecord(LF_CLASS, (short) fieldListCount, attrs, fieldListIdx, 0, 0, totalHeaderFieldSize, typeName, null);
        typeRecord = addTypeRecord(typeRecord);

        /* May need to add LF_UDT_SRC_LINE to type table once we have source info. */
        /* May need to add S_UDT record to symbol table once we have source info. */

        depth--;

        /* We've added the complete class record now. */
        inProcessMap.remove(typeEntry.getTypeName());
        return typeRecord;
    }

    private static boolean isManifestedField(FieldEntry fieldEntry) {
        return fieldEntry.getOffset() >= 0;
    }

    private CVTypeRecord.FieldRecord buildField(FieldEntry fieldEntry, boolean onlyForwardReference) {
        TypeEntry valueType = fieldEntry.getValueType();
        int vtIndex = getIndexForPointerOrPrimitive(valueType, onlyForwardReference);
        short attr = modifiersToAttr(fieldEntry);
        if (Modifier.isStatic(fieldEntry.getModifiers())) {
            return new CVTypeRecord.CVStaticMemberRecord(attr, vtIndex, fieldEntry.fieldName());
        } else {
            return new CVTypeRecord.CVMemberRecord(attr, vtIndex, fieldEntry.getOffset(), fieldEntry.fieldName());
        }
    }

    private static short modifiersToAttr(MemberEntry member) {
        short attr = Modifier.isPublic(member.getModifiers()) ? MPROP_PUBLIC : (Modifier.isPrivate(member.getModifiers())) ? MPROP_PRIVATE : MPROP_PROTECTED;
        attr += Modifier.isStatic(member.getModifiers()) ? MPROP_STATIC : MPROP_VIRTUAL; // TODO_
                                                                                         // this may
                                                                                         // need to
                                                                                         // be
                                                                                         // IVIRTUAL
                                                                                         // if this
                                                                                         // is
                                                                                         // initial
        return attr;
    }

    private CVTypeRecord.CVOneMethodRecord buildMethod(ClassEntry classEntry, MethodEntry methodEntry) {
        CVTypeRecord.CVTypeMFunctionRecord funcRecord = buildMemberFunction(classEntry, methodEntry);
        short attr = modifiersToAttr(methodEntry);
        int offset = 0; /* TODO - calculate vtable offset if required */
        return new CVTypeRecord.CVOneMethodRecord(attr, funcRecord.getSequenceNumber(), offset, methodEntry.methodName());
    }

    private CVTypeRecord.CVTypeMFunctionRecord buildMemberFunction(ClassEntry classEntry, MethodEntry methodEntry) {
        CVTypeRecord.CVTypeMFunctionRecord mFunctionRecord = new CVTypeRecord.CVTypeMFunctionRecord();
        mFunctionRecord.setClassType(getIndexForType(classEntry));
        mFunctionRecord.setThisType(getIndexForPointerOrPrimitive(classEntry, false));
        mFunctionRecord.setCallType((byte) (Modifier.isStatic(methodEntry.getModifiers()) ? CV_CALL_TYPE_C : CV_CALL_TYPE_THISCALL));
        short attr = modifiersToAttr(methodEntry);
        mFunctionRecord.setFuncAttr((byte) attr);
        mFunctionRecord.setReturnType(getIndexForPointerOrPrimitive(methodEntry.getValueType(), false));
        CVTypeRecord.CVTypeArglistRecord argListType = new CVTypeRecord.CVTypeArglistRecord();
        for (int i = 0; i < methodEntry.getParamCount(); i++) {
            argListType.add(getIndexForPointerOrPrimitive(methodEntry.getParamType(i), false));
        }
        argListType = addTypeRecord(argListType);
        mFunctionRecord.setArgList(argListType);
        return addTypeRecord(mFunctionRecord);
    }

    /**
     * Add type records for function. (later add arglist and local types). This works better if we
     * had ClassEntries instead of strings.
     *
     * @param entry primaryEntry containing entities whoses type records must be added
     * @return type index of function type
     */
    CVTypeRecord buildFunction(PrimaryEntry entry) {
        Range primary = entry.getPrimary();

        log("build primary start:" + primary.getFullMethodNameWithParams());
        depth++;

        /* Build return type records. */
        /* TODO - build from proper type instead of name */
        /* currently this builds ugly class records for arrays, so cheat by returning Object */
        // TypeEntry foo = primary.getMethodEntry().getValueType();
        // int returnTypeIndex =
        // primary.getMethodEntry().getValueType().getTypeName().endsWith("[]") ?
        // getIndexForPointer(JAVA_LANG_OBJECT) :
        // getIndexForPointer(primary.getMethodEntry().getValueType().getTypeName());
        int returnTypeIndex = getIndexForPointerOrPrimitive(primary.getMethodEntry().getValueType(), false);

        /* Build arglist record. */
        CVTypeRecord.CVTypeArglistRecord argListType = new CVTypeRecord.CVTypeArglistRecord();
        for (TypeEntry paramType : primary.getMethodEntry().getParamTypes()) {
            argListType.add(getIndexForPointerOrPrimitive(paramType, false));
        }
        argListType = addTypeRecord(argListType);

        /* Build actual type record */
        CVTypeRecord funcType = addTypeRecord(new CVTypeRecord.CVTypeProcedureRecord().returnType(returnTypeIndex).argList(argListType));
        depth--;
        log("build primary end:" + primary.getFullMethodNameWithParams());
        return funcType;
    }

    private void addPrimitiveTypes() {
        /* Primitive types are pre-defined and do not get written out to the typeInfo section. */
        typeSection.definePrimitiveType("void", T_VOID, 0, T_64PVOID);
        typeSection.definePrimitiveType("byte", T_INT1, Byte.BYTES, T_64PINT1);
        typeSection.definePrimitiveType("boolean", T_UINT1, 1, T_64PUINT1);
        typeSection.definePrimitiveType("char", T_WCHAR, Character.BYTES, T_64PWCHAR);
        typeSection.definePrimitiveType("short", T_INT2, Short.BYTES, T_64PINT2);
        typeSection.definePrimitiveType("int", T_INT4, Integer.BYTES, T_64PINT4);
        typeSection.definePrimitiveType("long", T_INT8, Long.BYTES, T_64PINT8);
        typeSection.definePrimitiveType("float", T_REAL32, Float.BYTES, T_64PREAL32);
        typeSection.definePrimitiveType("double", T_REAL64, Double.BYTES, T_64PREAL64);
    }

    private <T extends CVTypeRecord> T addTypeRecord(T record) {
        return typeSection.addOrReference(record);
    }

    void setDebugContext(DebugContext debugContext) {
        this.debugContext = debugContext;
    }

    private void log(String fmt, Object... args) {
        if (debugContext != null) {
            debugContext.logv(DebugContext.INFO_LEVEL, fmt, args);
        } else {
            System.out.format(fmt + "\n", args);
        }
    }
}
