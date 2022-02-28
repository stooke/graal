/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2020, Red Hat Inc. All rights reserved.
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

import com.oracle.objectfile.SectionName;
import com.oracle.objectfile.debugentry.ClassEntry;
import com.oracle.objectfile.debugentry.FieldEntry;
import com.oracle.objectfile.debugentry.PrimaryEntry;
import com.oracle.objectfile.debugentry.Range;
import com.oracle.objectfile.debugentry.TypeEntry;

import java.lang.reflect.Modifier;

import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_R8;

final class CVSymbolSubsectionBuilder {

    private final CVDebugInfo cvDebugInfo;
    private final CVSymbolSubsection cvSymbolSubsection;
    private CVLineRecordBuilder lineRecordBuilder;

    private boolean noMainFound = true;

    CVSymbolSubsectionBuilder(CVDebugInfo cvDebugInfo) {
        this.cvSymbolSubsection = new CVSymbolSubsection(cvDebugInfo);
        this.cvDebugInfo = cvDebugInfo;
    }

    /**
     * Build DEBUG_S_SYMBOLS record from all classEntries. (CodeView 4 format allows us to build one
     * per class or one per function or one big record - which is what we do here).
     *
     * The CodeView symbol section Prolog is also a CVSymbolSubsection, but it is not built in this
     * class.
     */
    void build() {
        this.lineRecordBuilder = new CVLineRecordBuilder(cvDebugInfo);

        /* Loop over all classes defined in this module. */
        for (TypeEntry typeEntry : cvDebugInfo.getTypes()) {
            /* Add type record for this entry. */
            if (typeEntry.isClass()) {
                buildClass((ClassEntry) typeEntry);
            } else {
                addTypeRecords(typeEntry);
            }
        }
        cvDebugInfo.getCVSymbolSection().addRecord(cvSymbolSubsection);
    }

    /**
     * Build all debug info for a classEntry.
     *
     * @param classEntry current class
     */
    private void buildClass(ClassEntry classEntry) {

        /*
         * Define the MFUNCTION records first and then the class itself. If the class is defined
         * first, MFUNCTION records that reference a forwardRef are generated, and then later (after
         * the class is defined) duplicate MFUCTINO records that reference the class itself will be
         * generated in buildFunction().
         */
        /* Loop over all functions defined in this class. */
        for (PrimaryEntry primaryEntry : classEntry.getPrimaryEntries()) {
            buildFunction(primaryEntry);
        }
        int classIndex = addTypeRecords(classEntry);

        /*
         * Adding an S_UDT (User Defined Type) record ensures the linker doesn't throw away the
         * class definition.
         */
        CVSymbolSubrecord.CVSymbolUDTRecord udtRecord = new CVSymbolSubrecord.CVSymbolUDTRecord(cvDebugInfo, classIndex, CVNames.typeNameToCodeViewName(classEntry.getTypeName()));
        addToSymbolSubsection(udtRecord);

        /* Add manifested static fields as S_GDATA32 records. */
        classEntry.fields().filter(CVSymbolSubsectionBuilder::isManifestedStaticField).forEach(f -> {
            int typeIndex = cvDebugInfo.getCVTypeSection().getIndexForPointer(f.getValueType());
            String displayName = CVNames.fieldNameToCodeViewName(f);
            if (cvDebugInfo.useHeapBase()) {
                /*
                 * REL32 offset from heap base register. Graal currently uses r14, this code will
                 * handle r8-r15.
                 */
                assert 8 <= cvDebugInfo.getHeapbaseRegister() && cvDebugInfo.getHeapbaseRegister() <= 15;
                int heapRegister = CV_AMD64_R8 + cvDebugInfo.getHeapbaseRegister() - 8;
                addToSymbolSubsection(new CVSymbolSubrecord.CVSymbolRegRel32Record(cvDebugInfo, displayName, typeIndex, f.getOffset(), (short) heapRegister));
            } else {
                /* Offset from heap begin. */
                String heapName = SectionName.SVM_HEAP.getFormatDependentName(cvDebugInfo.getCVSymbolSection().getOwner().getFormat());
                addToSymbolSubsection(new CVSymbolSubrecord.CVSymbolGData32Record(cvDebugInfo, displayName, heapName, typeIndex, f.getOffset(), (short) 0));
            }
        });
    }

    private static boolean isManifestedStaticField(FieldEntry fieldEntry) {
        return Modifier.isStatic(fieldEntry.getModifiers()) && fieldEntry.getOffset() >= 0;
    }

    /**
     * Emit records for each function: PROC32 S_FRAMEPROC S_END and line number records. (later:
     * type records as required).
     *
     * @param primaryEntry primary entry for this function
     */
    private void buildFunction(PrimaryEntry primaryEntry) {
        final Range primaryRange = primaryEntry.getPrimary();

        /* The name as it will appear in the debugger. */
        final String debuggerName = getDebuggerName(primaryRange);

        /* The name as exposed to the linker. */
        final String externalName = primaryRange.getSymbolName();

        /* S_PROC32 add function definition. */
        int functionTypeIndex = addTypeRecords(primaryEntry);
        byte funcFlags = 0;
        CVSymbolSubrecord.CVSymbolGProc32Record proc32 = new CVSymbolSubrecord.CVSymbolGProc32Record(cvDebugInfo, externalName, debuggerName, 0, 0, 0, primaryRange.getHi() - primaryRange.getLo(), 0,
                        0, functionTypeIndex, primaryRange.getLo(), (short) 0, funcFlags);
        addToSymbolSubsection(proc32);

        /* S_FRAMEPROC add frame definitions. */
        int asynceh = 1 << 9; /* Async exception handling (vc++ uses 1, clang uses 0). */
        /* TODO: This may change in the presence of isolates. */
        int localBP = 1 << 14; /* Local base pointer = SP (0=none, 1=sp, 2=bp 3=r13). */
        int paramBP = 1 << 16; /* Param base pointer = SP. */
        int frameFlags = asynceh + localBP + paramBP; /* NB: LLVM uses 0x14000. */
        addToSymbolSubsection(new CVSymbolSubrecord.CVSymbolFrameProcRecord(cvDebugInfo, primaryEntry.getFrameSize(), frameFlags));

        /* TODO: add parameter definitions (types have been added already). */
        /* TODO: add local variables, and their types. */
        /* TODO: add block definitions. */

        /* S_END add end record. */
        addToSymbolSubsection(new CVSymbolSubrecord.CVSymbolEndRecord(cvDebugInfo));

        /* Add line number records. */
        addLineNumberRecords(primaryEntry);
    }

    /**
     * Rename function names for usability or functionality.
     *
     * First encountered static main function becomes class_main. This is for usability. All other
     * functions become package_class_function_arglist. This does not affect external symbols used
     * by linker.
     *
     * @param range Range contained in the method of interest
     * @return user debugger friendly method name
     */
    private String getDebuggerName(Range range) {
        final String methodName;
        if (noMainFound && Modifier.isStatic(range.getMethodEntry().getModifiers()) && range.getMethodName().equals("main")) {
            noMainFound = false;
            methodName = CVNames.functionNameToCodeViewName(range.getMethodEntry());
        } else {
            /* Use a more user-friendly name instead of a hash function. */
            methodName = CVNames.functionNameAndArgsToCodeViewName(range.getMethodEntry());
        }
        return methodName;
    }

    private void addLineNumberRecords(PrimaryEntry primaryEntry) {
        CVLineRecord record = lineRecordBuilder.build(primaryEntry);
        /*
         * If there are no file entries (perhaps for a synthetic function?), we don't add this
         * record.
         */
        if (!record.isEmpty()) {
            cvDebugInfo.getCVSymbolSection().addRecord(record);
        }
    }

    /**
     * Add a record to the symbol subsection. A symbol subsection is contained within the top level
     * .debug$S symbol section.
     *
     * @param record the symbol subrecord to add.
     */
    private void addToSymbolSubsection(CVSymbolSubrecord record) {
        cvSymbolSubsection.addRecord(record);
    }

    /**
     * Add type records for a class and all its members.
     *
     * @param typeEntry class to add records for.
     * @return type index of class (or forward ref) type
     */
    private int addTypeRecords(TypeEntry typeEntry) {
        return cvDebugInfo.getCVTypeSection().addTypeRecords(typeEntry).getSequenceNumber();
    }

    /**
     * Add type records for function.
     *
     * @param entry primaryEntry containing entities whose type records must be added
     * @return type index of function type
     */
    private int addTypeRecords(PrimaryEntry entry) {
        return cvDebugInfo.getCVTypeSection().addTypeRecords(entry).getSequenceNumber();
    }
}
