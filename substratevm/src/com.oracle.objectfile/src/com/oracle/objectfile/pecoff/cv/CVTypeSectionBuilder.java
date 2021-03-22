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
import com.oracle.objectfile.debugentry.EnumClassEntry;
import com.oracle.objectfile.debugentry.FieldEntry;
import com.oracle.objectfile.debugentry.HeaderTypeEntry;
import com.oracle.objectfile.debugentry.InterfaceClassEntry;
import com.oracle.objectfile.debugentry.PrimaryEntry;
import com.oracle.objectfile.debugentry.PrimitiveTypeEntry;
import com.oracle.objectfile.debugentry.TypeEntry;
import org.graalvm.compiler.debug.DebugContext;

import java.lang.reflect.Modifier;

import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.LF_CLASS;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_CHAR;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_CHAR16;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_LONG;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_NOTYPE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_QUAD;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL32;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL64;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_SHORT;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_VOID;
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

    private CVTypeRecord buildClass(ClassEntry classEntry) {

        CVTypeRecord classType = typeSection.getType(classEntry.getTypeName());
        int idx = classType != null ? classType.getSequenceNumber() : UNKNOWN_TYPE_INDEX;
        if (classType == null) {
            log("classentry size=%d kind=%s %s", classEntry.getSize(), classEntry.typeKind().name(), classEntry.getTypeName());
            depth++;
            typeSection.defineIncompleteType(classEntry.getTypeName());
            /* process super type */
            int superIdx = 0;
            ClassEntry superClass = classEntry.getSuperClass();
            if (superClass != null) {
                if (typeSection.hasType(superClass.getTypeName())) {
                    superIdx = typeSection.getType(superClass.getTypeName()).getSequenceNumber();
                } else {
                    log("  building superclass %s", superClass.getTypeName());
                    superIdx = buildClass(superClass).getSequenceNumber();
                    log("  finished superclass %s", superClass.getTypeName());
                }
            }

            /* process fields */
            int fieldListIdx = 0;
            int fieldListCount = 0;
            if (classEntry.fields().count() > 0) {
                CVTypeRecord.CVFieldListRecord fieldListRecord = new CVTypeRecord.CVFieldListRecord();
                log("  building fields %s", fieldListRecord.toString());
                /* No need to process interfaces? */
                classEntry.fields()/*.filter(fieldEntry -> !Modifier.isStatic(fieldEntry.getModifiers()))*/.forEach(f -> {
                    CVTypeRecord.CVMemberRecord fieldRecord = buildField(f);
                    fieldListRecord.addMember(fieldRecord);
                });
                CVTypeRecord.CVFieldListRecord newfieldListRecord = addTypeRecord(fieldListRecord);
                fieldListIdx = newfieldListRecord.getSequenceNumber();
                fieldListCount = newfieldListRecord.count();
                log("  finished building fields %s", newfieldListRecord.toString());
            }

            /* build final class record */
            short count = (short)fieldListCount; /* count of number of elements in class */
            short attrs = 0; /* property attribute field (prop_t) */
            int derivedFromIndex = 0; /* type index of derived from list if not zero */
            int vshapeIndex = 0; /* type index of vshape table for this class */
            // TODO would Visual Studio understand this better as a structure record?
            classType = new CVTypeRecord.CVClassRecord(LF_CLASS, count, attrs, fieldListIdx, derivedFromIndex, vshapeIndex, classEntry.getSize(), classEntry.getTypeName());
            classType = typeSection.redefineType(classEntry.getTypeName(), classType);
            depth--;
            log("  finished class %s", classType);
        } else if (classType.isIncomplete()) {
            log("build incomplete class size=%d kind=%s %s", classEntry.getSize(), classEntry.typeKind().name(), classEntry.getTypeName());
        }
        CVTypeRecord.CVTypePointerRecord pointerType = new CVTypeRecord.CVTypePointerRecord(classType.getSequenceNumber(), CVTypeRecord.CVTypePointerRecord.NORMAL_64);
        pointerType = addTypeRecord(pointerType);
        /* This is Java; we define a pointer type and return it instead of the class */
        return classType;
    }

    private CVTypeRecord buildEnum(EnumClassEntry type) {
        depth++;
 /*
    0x100f 0x1203 len=186 LF_FIELDLIST:
      field LF_ENUMERATE type=0x1502 attr=0x3 l=1 JOB_OBJECT_NET_RATE_CONTROL_ENABLE
      field LF_ENUMERATE type=0x1502 attr=0x3 l=2 JOB_OBJECT_NET_RATE_CONTROL_MAX_BANDWIDTH
      field LF_ENUMERATE type=0x1502 attr=0x3 l=4 JOB_OBJECT_NET_RATE_CONTROL_DSCP_TAG
      field LF_ENUMERATE type=0x1502 attr=0x3 l=7 JOB_OBJECT_NET_RATE_CONTROL_VALID_FLAGS
    0x1010 0x1507 len=90 LF_ENUM count=4 properties=0x0200 fieldListIndex=0x100f underLyingTypeIndex=0x74 JOB_OBJECT_NET_RATE_CONTROL_FLAGS
*/
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

    CVTypeRecord buildType(TypeEntry typeEntry) {
        depth++;
        CVTypeRecord typeRecord = null;
        switch (typeEntry.typeKind()) {
            case PRIMITIVE: {
                PrimitiveTypeEntry type = (PrimitiveTypeEntry) typeEntry;
                //int attr = type.getFlags();
                typeRecord = typeSection.getType(typeEntry.getTypeName());
                //log("      typeentry idx=0x%04x sz=0x%x attr=0x%x primitive %s", typeRecord.getSequenceNumber(), typeEntry.getSize(), attr, typeEntry.getTypeName());
                break;
            }
            case ENUM: {
                typeRecord = buildEnum((EnumClassEntry) typeEntry);
                break;
            }
            case ARRAY: {
                ArrayTypeEntry type = (ArrayTypeEntry) typeEntry;
                final TypeEntry elementType = type.getElementType();
                int elementIndex = buildType(elementType).getSequenceNumber();
                // aseSize, lengthOffset
                CVTypeRecord.CVTypeArrayRecord record = new CVTypeRecord.CVTypeArrayRecord(elementIndex, T_QUAD, type.getSize());
                typeRecord = addTypeRecord(record);
                log("      LF_ARRAY idx=0x%04x sz=0x%x baseIndex=0x%04x [%s]", typeRecord.getSequenceNumber(), type.getSize(), elementIndex, elementType.getTypeName());
                break;
            }
            case INSTANCE: {
                ClassEntry type = (ClassEntry) typeEntry;
                typeRecord = buildClass(type);
                log("      LF_CLASS idx=0x%04x sz=0x%x %s", typeRecord.getSequenceNumber(), typeEntry.getSize(),  typeEntry.getTypeName());
                break;
            }
            case INTERFACE: {
                InterfaceClassEntry type = (InterfaceClassEntry) typeEntry;
                typeRecord = buildClass(type);
                log("      LF_INTERFACE idx=0x%04x sz=0x%x %s", typeRecord.getSequenceNumber(), typeEntry.getSize(),  typeEntry.getTypeName());
                break;
            }
            case HEADER: {
                // TODO probably a class entry?
                HeaderTypeEntry type = (HeaderTypeEntry) typeEntry;
                typeRecord = typeSection.getType(typeEntry.getTypeName());
                if (typeRecord != null) {
                    log("      header idx=0x%04x sz=0x%x %s", typeRecord.getSequenceNumber(), typeEntry.getSize(), typeEntry.getTypeName());
                } else {
                    typeRecord = typeSection.defineIncompleteType(typeEntry.getTypeName());
                    log("      header NULL idx=0x%04x sz=0x%x %s", UNKNOWN_TYPE_INDEX, typeEntry.getSize(), typeEntry.getTypeName());
                }
                break;
            }
        }
        depth--;
        log("      aka %s", typeRecord.toString());
        return typeRecord;
    }

    private CVTypeRecord.CVMemberRecord buildField(FieldEntry fieldEntry) {
        TypeEntry valueType = fieldEntry.getValueType();
        CVTypeRecord valueTypeRecord = buildType(valueType);
        int vtIndex = valueTypeRecord != null ? valueTypeRecord.getSequenceNumber() : UNKNOWN_TYPE_INDEX;
        short attr = (short) (Modifier.isPublic(fieldEntry.getModifiers()) ? 0x03 : (Modifier.isPrivate(fieldEntry.getModifiers())) ? 0x01 : 0x02);
        CVTypeRecord.CVMemberRecord record = new CVTypeRecord.CVMemberRecord(attr, vtIndex, fieldEntry.getOffset(), fieldEntry.fieldName());
        log("      LF_MEMBER offset=%02x size=%d mod=%s type=%s typeidx=0x%04x %s", fieldEntry.getOffset(), fieldEntry.getSize(), fieldEntry.getModifiersString(), valueType.getTypeName(), vtIndex, fieldEntry.fieldName());
        log("        aka %s", record.toString());
        return record;
    }

    /**
     * Add type records for function. (later add arglist, and return type and local types).
     *
     * @param entry primaryEntry containing entities whoses type records must be added
     * @return type index of function type
     */
    CVTypeRecord buildFunction(PrimaryEntry entry) {
        log("build primary " + entry.getPrimary().getFullMethodNameWithParams());

        /* Build return type records. */
        //CVTypeRecord returnType = findOrCreateType(entry.getPrimary().getMethodReturnTypeName());
        CVTypeRecord returnType = typeSection.getType("void");
        assert returnType != null;

        /* TODO - Build param type records. */
        CVTypeRecord.CVTypeArglistRecord argListType = addTypeRecord(new CVTypeRecord.CVTypeArglistRecord().add(T_NOTYPE));

        /* Build actual type record */
        /* TODO - build member function records as required. */
        CVTypeRecord funcType = addTypeRecord(new CVTypeRecord.CVTypeProcedureRecord().returnType(returnType).argList(argListType));
        return funcType;
    }

    private  void addPrimitiveTypes() {
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

    private <T extends CVTypeRecord> CVTypeRecord findOrCreateType(String typeName) {
        if (!typeSection.hasType(typeName)) {
            // TODO
            return typeSection.getType("void");
        } else {
            return typeSection.getType(typeName);
        }
    }

    private <T extends CVTypeRecord> T addTypeRecord(T record) {
        return typeSection.addOrReference(record);
    }

    void setDebugContext(DebugContext debugContext) {
        this.debugContext = debugContext;
    }

    private void log(String fmt, Object... args) {
        System.out.format(fmt + "\n", args);
    }
}


