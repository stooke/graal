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
import com.oracle.objectfile.debugentry.StructureTypeEntry;
import com.oracle.objectfile.debugentry.TypeEntry;

import org.graalvm.compiler.debug.GraalError;

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.ADDRESS_BITS;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.CV_CALL_NEAR_C;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_CLASS;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_IVIRTUAL;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_PRIVATE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_PROTECTED;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_PUBLIC;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_PURE_IVIRTUAL;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_PURE_VIRTUAL;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_STATIC;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_VANILLA;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_VIRTUAL;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.MPROP_VSF_MASK;
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
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_NOTYPE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL32;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL64;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_UINT1;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_UINT4;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_VOID;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_WCHAR;
import static com.oracle.objectfile.pecoff.cv.CVTypeRecord.CVClassRecord.ATTR_FORWARD_REF;

class CVTypeSectionBuilder {

    private static final String JAVA_LANG_OBJECT = "java.lang.Object";
    private static final int FUNC_IS_CONSTRUCTOR = 2;

    private int objectHeaderRecordIndex;
    private int javaLangObjectRecordIndex;

    private final CVTypeSectionImpl typeSection;
    private HeaderTypeEntry globalHeaderEntry;

    private final TypeTable types;

    CVTypeSectionBuilder(CVTypeSectionImpl typeSection) {
        this.typeSection = typeSection;
        this.types = new TypeTable(typeSection);
    }

    void buildRemainingRecords() {
        /* When this is called, all types should be seen already. */
        /* Just in case, check to see if we have some unresolved forward refs. */
        types.testForUndefinedClasses();
    }

    CVTypeRecord buildType(TypeEntry typeEntry) {
        CVTypeRecord typeRecord = types.getExistingType(typeEntry);
        /*
         * If we've never seen the class or only defined it as a forward reference, define it now.
         */
        if (typeRecord != null && typeRecord.type == LF_CLASS && !((CVTypeRecord.CVClassRecord) typeRecord).isForwardRef()) {
            log("buildType() type %s(%s) is known %s", typeEntry.getTypeName(), typeEntry.typeKind().name(), typeRecord);
        } else {
            log("buildType() %s %s size=%d - begin", typeEntry.typeKind().name(), typeEntry.getTypeName(), typeEntry.getSize());
            switch (typeEntry.typeKind()) {
                case PRIMITIVE: {
                    typeRecord = types.getExistingType(typeEntry);
                    break;
                }
                case ARRAY: {
                    typeRecord = buildArray((ArrayTypeEntry) typeEntry);
                    break;
                }
                case ENUM:
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
                         * Create a synthetic class for this object and make java.lang.Object derive
                         * from it.
                         */
                        log("Object header size=%d kind=%s %s", typeEntry.getSize(), typeEntry.typeKind().name(), typeEntry.getTypeName());
                        typeRecord = buildStructureTypeEntry(globalHeaderEntry, 0, null);
                        objectHeaderRecordIndex = typeRecord.getSequenceNumber();
                    } else {
                        log("More than one object header (%s) has been defined", typeEntry.getTypeName());
                        GraalError.shouldNotReachHere();
                    }
                    break;
                }
            }
        }
        log("buildType end: %s", typeRecord);
        return typeRecord;
    }

    /**
     * Add type records for function. In the future add local types when they become available.
     *
     * @param entry primaryEntry containing entities whose type records must be added
     * @return type record for this function (may return existing matching record)
     */
    CVTypeRecord buildFunction(PrimaryEntry entry) {
        return buildMemberFunction(entry.getClassEntry(), entry.getPrimary().getMethodEntry());
    }

    /**
     * Build a class or Enum.
     *
     * @param classEntry class to be defined
     * @return record of defined class
     */
    private CVTypeRecord buildClass(ClassEntry classEntry) {
        ClassEntry superClass = classEntry.getSuperClass();
        final int superTypeIndex;
        if (superClass != null) {
            superTypeIndex = types.getIndexForForwardRef(superClass);
        } else if (classEntry.getTypeName().equals(JAVA_LANG_OBJECT)) {
            superTypeIndex = objectHeaderRecordIndex;
        } else {
            superTypeIndex = javaLangObjectRecordIndex;
        }
        CVTypeRecord record = buildStructureTypeEntry(classEntry, superTypeIndex, null);
        types.typeNameMap.put(classEntry.getTypeName().hashCode(), record);
        return record;
    }

    private CVTypeRecord buildStructureTypeEntry(final StructureTypeEntry typeEntry, final int superTypeIndex, final CVTypeRecord.CVMemberRecord[] extraFields) {

        final List<MethodEntry> methods;
        final ClassEntry classEntry;

        if (typeEntry.isClass()) {
            classEntry = (ClassEntry) typeEntry;
            methods = classEntry.getMethods();
        } else {
            classEntry = null;
            methods = Collections.emptyList();
        }

        CVTypeRecord classRecord = types.getExistingType(typeEntry);
        if (classRecord == null || classRecord.type == LF_CLASS && ((CVTypeRecord.CVClassRecord) classRecord).isForwardRef()) {
            log("classentry size=%d kind=%s %s", typeEntry.getSize(), typeEntry.typeKind().name(), typeEntry.getTypeName());

            /* process fields */
            int fieldListIdx = 0;
            int fieldListCount = 0;

            if (typeEntry.fields().count() > 0 || methods.size() > 0 || superTypeIndex != 0) {
                CVTypeRecord.CVFieldListRecord fieldListRecord = new CVTypeRecord.CVFieldListRecord();
                log("building fields %s", fieldListRecord);

                if (superTypeIndex != 0) {
                    CVTypeRecord.CVBaseMemberRecord btype = new CVTypeRecord.CVBaseMemberRecord((short) 0x3, superTypeIndex, 0);
                    log("basetype %s", btype);
                    fieldListRecord.add(btype);
                }

                /* Only define manifested fields. */
                typeEntry.fields().filter(CVTypeSectionBuilder::isManifestedField).forEach(f -> {
                    log("field %s attr=(%s) offset=%d size=%d valuetype=%s", f.fieldName(), f.getModifiersString(), f.getOffset(), f.getSize(), f.getValueType().getTypeName());
                    CVTypeRecord.FieldRecord fieldRecord = buildField(f);
                    log("field %s", fieldRecord);
                    fieldListRecord.add(fieldRecord);
                });

                if (extraFields != null) {
                    for (CVTypeRecord.CVMemberRecord fieldRecord : extraFields) {
                        /* Ensure field begins on 8 byte boundary. */
                        /* TODO should this be only for non-static fields? */
                        fieldRecord.setOffset((typeEntry.getSize() + 7) & ~0x7);
                        log("synthetic field %s", fieldRecord);
                        fieldListRecord.add(fieldRecord);
                    }
                }

                /*
                 * Functions go into the main fieldList if they are not overloaded. Overloaded
                 * functions get a M_FUNCTION entry in the field list, and a LF_METHODLIST record
                 * pointing to M_MFUNCTION records for each overload.
                 */
                if (methods.size() > 0) {

                    log("building methods");

                    /* first build a list of all overloaded functions */
                    HashSet<String> overloaded = new HashSet<>(methods.size());
                    HashSet<String> allFunctions = new HashSet<>(methods.size());
                    methods.forEach(m -> {
                        if (allFunctions.contains(m.methodName())) {
                            overloaded.add(m.methodName());
                        } else {
                            allFunctions.add(m.methodName());
                        }
                    });

                    overloaded.forEach(mname -> {

                        /* LF_METHODLIST */
                        CVTypeRecord.CVTypeMethodListRecord mlist = new CVTypeRecord.CVTypeMethodListRecord();

                        /* LF_MFUNCTION records */
                        methods.stream().filter(methodEntry -> methodEntry.methodName().equals(mname)).forEach(m -> {
                            log("overloaded method %s(%s) attr=(%s) valuetype=%s", m.fieldName(), m.methodName(), m.getModifiersString(), m.getValueType().getTypeName());
                            CVTypeRecord.CVTypeMFunctionRecord mFunctionRecord = buildMemberFunction(classEntry, m);
                            addTypeRecord(mFunctionRecord);
                            short attr = modifiersToAttr(m);
                            log("    overloaded method %s", mFunctionRecord);
                            int vtbleOffset = m.getVtableOffset() >= 0 && ((attr & MPROP_VSF_MASK) == MPROP_IVIRTUAL || (attr & MPROP_VSF_MASK) == MPROP_PURE_IVIRTUAL) ? m.getVtableOffset() : 0;
                            mlist.add(attr, mFunctionRecord.getSequenceNumber(), vtbleOffset, m.methodName());
                        });

                        CVTypeRecord.CVTypeMethodListRecord nmlist = addTypeRecord(mlist);

                        /* LF_METHOD record */
                        CVTypeRecord.CVOverloadedMethodRecord methodRecord = new CVTypeRecord.CVOverloadedMethodRecord((short) nmlist.count(), nmlist.getSequenceNumber(), mname);
                        fieldListRecord.add(methodRecord);
                    });

                    methods.stream().filter(methodEntry -> !overloaded.contains(methodEntry.methodName())).forEach(m -> {
                        log("`unique method %s %s %s(...)", m.fieldName(), m.methodName(), m.getModifiersString(), m.getValueType().getTypeName(), m.methodName());
                        CVTypeRecord.CVOneMethodRecord method = buildMethod(classEntry, m);
                        log("    unique method %s", method);
                        fieldListRecord.add(method);
                    });
                }
                /* Build fieldlist record from manifested fields. */
                CVTypeRecord.CVFieldListRecord newfieldListRecord = addTypeRecord(fieldListRecord);
                fieldListIdx = newfieldListRecord.getSequenceNumber();
                fieldListCount = newfieldListRecord.count();
                log("finished building fields %s", newfieldListRecord);
            }

            /* Build final class record. */
            short attrs = 0; /* property attribute field (prop_t) */
            classRecord = new CVTypeRecord.CVClassRecord(LF_CLASS, (short) fieldListCount, attrs, fieldListIdx, 0, 0, typeEntry.getSize(), typeEntry.getTypeName(), null);
            classRecord = addTypeRecord(classRecord);

            /* Save this in case we find a class with no superclass */
            if (typeEntry.getTypeName().equals(JAVA_LANG_OBJECT)) {
                javaLangObjectRecordIndex = classRecord.getSequenceNumber();
            }

            if (classEntry != null) {
                /* Add a UDT record (if we have the information) */
                /* Try to find a line number - if none, don't bother to create the record. */
                int line = classEntry.getPrimaryEntries().isEmpty() ? 0 : classEntry.getPrimaryEntries().get(0).getPrimary().getLine();
                if (line > 0) {
                    int idIdx = typeSection.getStringId(classEntry.getFileName()).getSequenceNumber();
                    CVTypeRecord.CVUdtTypeLineRecord udt = new CVTypeRecord.CVUdtTypeLineRecord(classRecord.getSequenceNumber(), idIdx, line);
                    addTypeRecord(udt);
                }
            }

            /* CVSymbolSubsectionBuilder will add associated S_UDT record to symbol table. */
            log("  finished class %s", classRecord);
        }
        return classRecord;
    }

    private CVTypeRecord buildArray(ArrayTypeEntry typeEntry) {
        /* Model an array as a struct with a pointer, a length and then array of length 0 */
        /* String[] becomes struct String[] : Object { DynamicHub *; int length; String*[0]; } */

        /* Build 0 length array. */
        final TypeEntry elementType = typeEntry.getElementType();
        int elementTypeIndex = types.getIndexForPointerOrPrimitive(elementType);
        CVTypeRecord array0record = addTypeRecord(new CVTypeRecord.CVTypeArrayRecord(elementTypeIndex, T_UINT4, 0));

        /* Build a field for the 0 length array. */
        CVTypeRecord.CVMemberRecord dm = new CVTypeRecord.CVMemberRecord((short) 0x03, array0record.getSequenceNumber(), typeEntry.getSize(), "data");
        CVTypeRecord.CVMemberRecord[] fields = {dm};
        CVTypeRecord record = buildStructureTypeEntry(typeEntry, javaLangObjectRecordIndex, fields);
        log("build ARRAY: %s", record);
        types.typeNameMap.put(typeEntry.getTypeName().hashCode(), record);
        return record;
    }

    private static boolean isManifestedField(FieldEntry fieldEntry) {
        return fieldEntry.getOffset() >= 0;
    }

    private CVTypeRecord.FieldRecord buildField(FieldEntry fieldEntry) {
        TypeEntry valueType = fieldEntry.getValueType();
        int valueTypeIndex = types.getIndexForPointerOrPrimitive(valueType);
        short attr = modifiersToAttr(fieldEntry);
        if (Modifier.isStatic(fieldEntry.getModifiers())) {
            return new CVTypeRecord.CVStaticMemberRecord(attr, valueTypeIndex, fieldEntry.fieldName());
        } else {
            return new CVTypeRecord.CVMemberRecord(attr, valueTypeIndex, fieldEntry.getOffset(), fieldEntry.fieldName());
        }
    }

    private static short modifiersToAttr(MethodEntry member) {
        short attr = Modifier.isPublic(member.getModifiers()) ? MPROP_PUBLIC : (Modifier.isPrivate(member.getModifiers())) ? MPROP_PRIVATE : MPROP_PROTECTED;
        boolean isStatic = Modifier.isStatic(member.getModifiers());
        /* TODO take abstract (= pure) and vtableOffset into account */
        if (isStatic) {
            attr += MPROP_STATIC;
        } else if (member.getVtableOffset() < 0) {
            attr += MPROP_VANILLA;
        } else if (Modifier.isAbstract(member.getModifiers())) {
            attr += member.isOverride() ? MPROP_PURE_VIRTUAL : MPROP_PURE_IVIRTUAL;
        } else {
            attr += member.isOverride() ? MPROP_VIRTUAL : MPROP_IVIRTUAL;
        }
        return attr;
    }

    private static short modifiersToAttr(FieldEntry member) {
        short attr = Modifier.isPublic(member.getModifiers()) ? MPROP_PUBLIC : (Modifier.isPrivate(member.getModifiers())) ? MPROP_PRIVATE : MPROP_PROTECTED;
        attr += Modifier.isStatic(member.getModifiers()) ? MPROP_STATIC : 0;
        return attr;
    }

    private CVTypeRecord.CVOneMethodRecord buildMethod(ClassEntry classEntry, MethodEntry methodEntry) {
        CVTypeRecord.CVTypeMFunctionRecord funcRecord = buildMemberFunction(classEntry, methodEntry);
        short attr = modifiersToAttr(methodEntry);
        return new CVTypeRecord.CVOneMethodRecord(attr, funcRecord.getSequenceNumber(), methodEntry.getVtableOffset(), methodEntry.methodName());
    }

    CVTypeRecord.CVTypeMFunctionRecord buildMemberFunction(ClassEntry classEntry, MethodEntry methodEntry) {
        CVTypeRecord.CVTypeMFunctionRecord mFunctionRecord = new CVTypeRecord.CVTypeMFunctionRecord();
        mFunctionRecord.setClassType(types.getIndexForForwardRef(classEntry));
        mFunctionRecord.setCallType((byte) (CV_CALL_NEAR_C));
        mFunctionRecord.setThisType(Modifier.isStatic(methodEntry.getModifiers()) ? T_NOTYPE : types.getIndexForPointerOrPrimitive(classEntry));
        /* 'attr' is CV_funcattr_t and if set to 2 indicates a constructor function. */
        byte attr = methodEntry.isConstructor() ? (byte) FUNC_IS_CONSTRUCTOR : 0;
        mFunctionRecord.setFuncAttr(attr);
        mFunctionRecord.setReturnType(types.getIndexForPointerOrPrimitive(methodEntry.getValueType()));
        CVTypeRecord.CVTypeArglistRecord argListType = new CVTypeRecord.CVTypeArglistRecord();
        for (int i = 0; i < methodEntry.getParamCount(); i++) {
            argListType.add(types.getIndexForPointerOrPrimitive(methodEntry.getParamType(i)));
        }
        argListType = addTypeRecord(argListType);
        mFunctionRecord.setArgList(argListType);
        return addTypeRecord(mFunctionRecord);
    }

    private <T extends CVTypeRecord> T addTypeRecord(T record) {
        return types.addTypeRecord(record);
    }

    int getIndexForPointerOrPrimitive(TypeEntry entry) {
        return types.getIndexForPointerOrPrimitive(entry);
    }

    private void log(String fmt, Object... args) {
        if (typeSection.getDebugContext() != null) {
            typeSection.log(fmt, args);
        }
    }

    static class TypeTable {

        private static final int CV_TYPENAME_INITIAL_CAPACITY = 20000;

        /* A map of typename.hashcode() to type records, */
        private final Map<Integer, CVTypeRecord> typeNameMap = new HashMap<>(CV_TYPENAME_INITIAL_CAPACITY);

        /* For convenience, quick lookup of pointer type indices given class type index */
        /* Could have saved this in typeNameMap. */
        /* maps type index to pointer to forward ref record */
        private final Map<Integer, CVTypeRecord> typePointerMap = new HashMap<>(CV_TYPENAME_INITIAL_CAPACITY);

        /*
         * A map of type names to type records. Only forward references are stored here, and only
         * until they are defined.
         */
        private final Map<String, CVTypeRecord> forwardRefMap = new HashMap<>(CV_TYPENAME_INITIAL_CAPACITY);

        private final CVTypeSectionImpl typeSection;

        TypeTable(CVTypeSectionImpl typeSection) {
            this.typeSection = typeSection;
            addPrimitiveTypes();
        }

        void testForUndefinedClasses() {
            /* Currently java.lang.Class should be the only type undefined at this point. */
            for (CVTypeRecord record : forwardRefMap.values()) {
                CVTypeRecord.CVClassRecord classRecord = (CVTypeRecord.CVClassRecord) record;
                if (!typeNameMap.containsKey(classRecord.getClassName().hashCode())) {
                    GraalError.shouldNotReachHere("no typeentry for " + classRecord.getClassName() + "; type remains incomplete");
                }
            }
        }

        private <T extends CVTypeRecord> T addTypeRecord(T record) {
            return typeSection.addOrReference(record);
        }

        /**
         * Return a CV type index for a pointer to a java type, or the type itself if a primitive.
         *
         * @param entry The java type to return a typeindex for. If the type has not been seen, a
         *            forward reference is generated.
         * @return The index for the typeentry for a pointer to the type. If the type is a primitive
         *         type, the index returned is for the type, not a pointer to the type.
         */
        int getIndexForPointerOrPrimitive(TypeEntry entry) {
            if (entry.isPrimitive()) {
                CVTypeRecord record = getExistingType(entry);
                assert record != null;
                return record.getSequenceNumber();
            }
            CVTypeRecord forwardRefRecord = getExistingForwardReference(entry);
            if (forwardRefRecord == null) {
                forwardRefRecord = addTypeRecord(new CVTypeRecord.CVClassRecord((short) ATTR_FORWARD_REF, entry.getTypeName(), null));
                forwardRefMap.put(entry.getTypeName(), forwardRefRecord);
            }
            /* We now have a class record but must create a pointer record. */
            CVTypeRecord ptrRecord = typePointerMap.get(forwardRefRecord.getSequenceNumber());
            if (ptrRecord == null) {
                ptrRecord = addTypeRecord(new CVTypeRecord.CVTypePointerRecord(forwardRefRecord.getSequenceNumber(), CVTypeRecord.CVTypePointerRecord.NORMAL_64));
                typePointerMap.put(forwardRefRecord.getSequenceNumber(), ptrRecord);
            }
            return ptrRecord.getSequenceNumber();
        }

        private int getIndexForForwardRef(TypeEntry entry) {
            CVTypeRecord clsRecord = forwardRefMap.get(entry.getTypeName());
            if (clsRecord == null) {
                clsRecord = addTypeRecord(new CVTypeRecord.CVClassRecord((short) ATTR_FORWARD_REF, entry.getTypeName(), null));
                forwardRefMap.put(entry.getTypeName(), clsRecord);
            }
            return clsRecord.getSequenceNumber();
        }

        CVTypeRecord getExistingType(TypeEntry typeEntry) {
            return typeNameMap.get(typeEntry.getTypeName().hashCode());
        }

        CVTypeRecord getExistingForwardReference(TypeEntry typeEntry) {
            return forwardRefMap.get(typeEntry.getTypeName());
        }

        void definePrimitiveType(String typename, short typeId, int length, short pointerTypeId) {
            CVTypeRecord record = new CVTypeRecord.CVTypePrimitive(typeId, length);
            typeNameMap.put(typename.hashCode(), record);
            if (pointerTypeId != 0) {
                CVTypeRecord pointerRecord = new CVTypeRecord.CVTypePrimitive(pointerTypeId, ADDRESS_BITS);
                typePointerMap.put((int) typeId, pointerRecord);
            }
        }

        private void addPrimitiveTypes() {
            /*
             * Primitive types are pre-defined and do not get written out to the typeInfo section.
             */
            definePrimitiveType("void", T_VOID, 0, T_64PVOID);
            definePrimitiveType("byte", T_INT1, Byte.BYTES, T_64PINT1);
            definePrimitiveType("boolean", T_UINT1, 1, T_64PUINT1);
            definePrimitiveType("char", T_WCHAR, Character.BYTES, T_64PWCHAR);
            definePrimitiveType("short", T_INT2, Short.BYTES, T_64PINT2);
            definePrimitiveType("int", T_INT4, Integer.BYTES, T_64PINT4);
            definePrimitiveType("long", T_INT8, Long.BYTES, T_64PINT8);
            definePrimitiveType("float", T_REAL32, Float.BYTES, T_64PREAL32);
            definePrimitiveType("double", T_REAL64, Double.BYTES, T_64PREAL64);
        }
    }
}
