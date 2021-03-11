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

import com.oracle.objectfile.debugentry.ClassEntry;
import com.oracle.objectfile.debugentry.FieldEntry;
import com.oracle.objectfile.debugentry.PrimaryEntry;
import com.oracle.objectfile.debugentry.TypeEntry;
import org.graalvm.compiler.debug.DebugContext;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

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

class CVTypeSectionBuilder {

    private CVTypeSectionImpl typeSection;
    private DebugContext debugContext;

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


    int buildClass(ClassEntry classEntry) {

        CVTypeRecord classType = typeSection.getType(classEntry.getTypeName());
        int idx = classType != null ? classType.getSequenceNumber() : 0;
        if (classType == null) {
            log("classentry size=%d kind=%s %s", classEntry.getSize(), classEntry.typeKind().name(), classEntry.getTypeName());

            /* process super type */
            int superIdx = 0;
            if (classEntry.getSuperClass() != null) {
                if (typeSection.hasType(classEntry.getSuperClass().getTypeName())) {
                    idx = typeSection.getType(classEntry.getSuperClass().getTypeName()).getSequenceNumber();
                } else {
                    log("  building superclass ");
                    superIdx = buildClass(classEntry.getSuperClass());
                    log("  finished superclass");
                }
            }

            /* process fields */
            int fieldListIdx = 0;
            if (classEntry.fields().count() > 0) {
                log("  building fields ");
                /* No need to process interfaces? */
                ArrayList<Integer> fieldList = new ArrayList<>((int) classEntry.fields().count());
                classEntry.fields()/*.filter(fieldEntry -> !Modifier.isStatic(fieldEntry.getModifiers()))*/.forEach(f -> {
                    int fieldidx = buildField(f);
                    fieldList.add(fieldidx);
                });
                log("  finished building fields ");
            }

            /* build final class record */
            short count = 0; /* count of number of elements in class */
            short propertyAttributes = 0; /* property attribute field (prop_t) */
            int derivedFromIndex = 0; /* type index of derived from list if not zero */
            int vshapeIndex = 0; /* type index of vshape table for this class */
            CVTypeRecord.CVClassRecord classRecord = new CVTypeRecord.CVClassRecord(LF_CLASS, count, propertyAttributes, fieldListIdx, derivedFromIndex, vshapeIndex);
            idx = typeSection.defineType(classEntry.getTypeName(), classRecord).getSequenceNumber();
        } else if (classType.isIncomplete()) {
            log("build incomplete class size=%d kind=%s %s", classEntry.getSize(), classEntry.typeKind().name(), classEntry.getTypeName());
        }
        return idx;
    }

    private int buildType(TypeEntry typeEntry) {
        int idx = 0;
        switch (typeEntry.typeKind()) {
            case PRIMITIVE:
                idx = typeSection.getType(typeEntry.getTypeName()).getSequenceNumber();
                break;
            case ENUM:
                idx = typeSection.hasType(typeEntry.getTypeName()) ? typeSection.getType(typeEntry.getTypeName()).getSequenceNumber() : 0;
                break;
            case INSTANCE:
                idx = typeSection.hasType(typeEntry.getTypeName()) ? typeSection.getType(typeEntry.getTypeName()).getSequenceNumber() : 0;
                break;
            case INTERFACE:
                idx = typeSection.hasType(typeEntry.getTypeName()) ? typeSection.getType(typeEntry.getTypeName()).getSequenceNumber() : 0;
                break;
            case ARRAY:
                idx = typeSection.hasType(typeEntry.getTypeName()) ? typeSection.getType(typeEntry.getTypeName()).getSequenceNumber() : 0;
                break;
            case HEADER:
                idx = typeSection.hasType(typeEntry.getTypeName()) ? typeSection.getType(typeEntry.getTypeName()).getSequenceNumber() : 0;
                break;
        }
        log("      typeentry idx=0x%04x sz=0x%x %s %s", idx, typeEntry.getSize(), typeEntry.typeKind().name(), typeEntry.getTypeName());
        return idx;
    }

    private int buildField(FieldEntry fieldEntry) {
        TypeEntry valueType = fieldEntry.getValueType();
        int vtIndex = buildType(valueType);
        log("    fieldEntry offset=%02x size=%d mod=%s type=%s typeidx=0x%04x %s", fieldEntry.getOffset(), fieldEntry.getSize(), fieldEntry.getModifiersString(), fieldEntry.getValueType(), vtIndex, fieldEntry.fieldName());
        return 0;
    }

    /**
     * Add type records for function. (later add arglist, and return type and local types).
     *
     * @param entry primaryEntry containing entities whoses type records must be added
     * @return type index of function type
     */
    int buildFunction(PrimaryEntry entry) {
        log("build primary " + entry.getPrimary().getFullMethodNameWithParams());
        // TODO - CVTypeRecord returnType = findOrCreateType(entry.getPrimary().getMethodReturnTypeName());
        CVTypeRecord returnType = findOrCreateType("void");
        CVTypeRecord.CVTypeArglistRecord argListType = addTypeRecord(new CVTypeRecord.CVTypeArglistRecord().add(T_NOTYPE));
        CVTypeRecord funcType = addTypeRecord(new CVTypeRecord.CVTypeProcedureRecord().returnType(returnType).argList(argListType));
        return funcType.getSequenceNumber();
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
 //       System.out.format(fmt + "\n", args);
    }
}


