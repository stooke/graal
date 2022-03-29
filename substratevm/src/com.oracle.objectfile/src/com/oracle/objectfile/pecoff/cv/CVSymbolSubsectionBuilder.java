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
import com.oracle.objectfile.debugentry.MethodEntry;
import com.oracle.objectfile.debugentry.PrimaryEntry;
import com.oracle.objectfile.debugentry.Range;
import com.oracle.objectfile.debugentry.TypeEntry;
import org.graalvm.compiler.debug.GraalError;

import java.lang.reflect.Modifier;
import java.util.Vector;

import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_CL;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_CX;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_DI;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_DIL;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_DL;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_DX;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_ECX;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_EDI;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_EDX;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_ESI;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_R8;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_R8B;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_R8D;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_R8W;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_R9;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_R9B;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_R9D;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_R9W;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_RCX;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_RDI;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_RDX;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_RSI;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_SI;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_SIL;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_XMM0L;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_XMM0_0;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_XMM1L;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_XMM1_0;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_XMM2L;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_XMM2_0;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_XMM3L;
import static com.oracle.objectfile.pecoff.cv.CVConstants.CV_AMD64_XMM3_0;
import static com.oracle.objectfile.pecoff.cv.CVSymbolSubrecord.CVSymbolFrameProcRecord.FRAME_ASYNC_EH;
import static com.oracle.objectfile.pecoff.cv.CVSymbolSubrecord.CVSymbolFrameProcRecord.FRAME_LOCAL_BP;
import static com.oracle.objectfile.pecoff.cv.CVSymbolSubrecord.CVSymbolFrameProcRecord.FRAME_PARAM_BP;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL32;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL64;

final class CVSymbolSubsectionBuilder {

    private final CVDebugInfo cvDebugInfo;
    private final CVSymbolSubsection cvSymbolSubsection;
    private final CVLineRecordBuilder lineRecordBuilder;

    private boolean noMainFound = true;

    CVSymbolSubsectionBuilder(CVDebugInfo cvDebugInfo) {
        this.cvDebugInfo = cvDebugInfo;
        this.cvSymbolSubsection = new CVSymbolSubsection(cvDebugInfo);
        this.lineRecordBuilder = new CVLineRecordBuilder(cvDebugInfo);
    }

    /**
     * Build DEBUG_S_SYMBOLS record from all classEntries. (CodeView 4 format allows us to build one
     * per class or one per function or one big record - which is what we do here).
     *
     * The CodeView symbol section Prolog is also a CVSymbolSubsection, but it is not built in this
     * class.
     */
    void build() {
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
        addSymbolRecord(udtRecord);

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
                addSymbolRecord(new CVSymbolSubrecord.CVSymbolRegRel32Record(cvDebugInfo, displayName, typeIndex, f.getOffset(), (short) heapRegister));
            } else {
                /* Offset from heap begin. */
                String heapName = SectionName.SVM_HEAP.getFormatDependentName(cvDebugInfo.getCVSymbolSection().getOwner().getFormat());
                addSymbolRecord(new CVSymbolSubrecord.CVSymbolGData32Record(cvDebugInfo, displayName, heapName, typeIndex, f.getOffset(), (short) 0));
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
        final int functionTypeIndex = addTypeRecords(primaryEntry);
        final byte funcFlags = 0;
        final int procLen = primaryRange.getHi() - primaryRange.getLo();
        CVSymbolSubrecord.CVSymbolGProc32Record proc32 = new CVSymbolSubrecord.CVSymbolGProc32Record(cvDebugInfo, externalName, debuggerName, 0, 0, 0, procLen, 0,
                        0, functionTypeIndex, 0, (short) 0, funcFlags);
        addSymbolRecord(proc32);

        final int frameFlags = FRAME_ASYNC_EH + FRAME_LOCAL_BP + FRAME_PARAM_BP; /* NB: LLVM uses 0x14000. */
        addSymbolRecord(new CVSymbolSubrecord.CVSymbolFrameProcRecord(cvDebugInfo, primaryEntry.getFrameSize(), frameFlags));

        /* Add register parameters - only valid for the first instruction or two. */
        {
 //           addToSymbolSubsection(new CVSymbolSubrecord.CVSymbolBlock32Record(cvDebugInfo, externalName));

            short[] javaGP64registers = { CV_AMD64_RDX, CV_AMD64_R8, CV_AMD64_R9, CV_AMD64_RDI, CV_AMD64_RSI, CV_AMD64_RCX };
            short[] javaGP32registers = { CV_AMD64_EDX, CV_AMD64_R8D, CV_AMD64_R9D, CV_AMD64_EDI, CV_AMD64_ESI, CV_AMD64_ECX };
            short[] javaGP16registers = { CV_AMD64_DX, CV_AMD64_R8W, CV_AMD64_R9W, CV_AMD64_DI, CV_AMD64_SI, CV_AMD64_CX };
            short[] javaGP8registers = { CV_AMD64_DL, CV_AMD64_R8B, CV_AMD64_R9B, CV_AMD64_DIL, CV_AMD64_SIL, CV_AMD64_CL };
          //  short[] javaFP128registers = { CV_AMD64_XMM0, CV_AMD64_XMM1, CV_AMD64_XMM2, CV_AMD64_XMM3 };
            short[] javaFP64registers = { CV_AMD64_XMM0L, CV_AMD64_XMM1L, CV_AMD64_XMM2L, CV_AMD64_XMM3L };
            short[] javaFP32registers = { CV_AMD64_XMM0_0, CV_AMD64_XMM1_0, CV_AMD64_XMM2_0, CV_AMD64_XMM3_0 };

            MethodEntry method = primaryRange.getMethodEntry();
            Vector<CVSymbolSubrecord> regRelRecords = new Vector<>(method.getParamCount() + 1);
            int gpRegisterIndex = 0;
            int fpRegisterIndex = 0;
            if (!Modifier.isStatic(method.getModifiers())) {
                final TypeEntry paramType = primaryEntry.getClassEntry();
                if (false) {
                    // mode 1 - define as an offset from dx
                    // no gaps allowed!
                    final int typeIndex = cvDebugInfo.getCVTypeSection().addTypeRecords(paramType).getSequenceNumber();
                    addSymbolRecord(new CVSymbolSubrecord.CVSymbolRegRel32Record(cvDebugInfo, "this", typeIndex, 0, javaGP64registers[gpRegisterIndex]));
                } else {
                    // mode 2 define as a local just as we define other object pointers
                    final int typeIndex = cvDebugInfo.getCVTypeSection().getIndexForPointer(paramType);
                    //final int typeIndex = cvDebugInfo.getCVTypeSection().addTypeRecords(paramType).getSequenceNumber();
                    addSymbolRecord(new CVSymbolSubrecord.CVSymbolLocalRecord(cvDebugInfo, "this", typeIndex, 1));
                    addSymbolRecord(new CVSymbolSubrecord.CVSymbolDefRangeRegisterRecord(cvDebugInfo, javaGP64registers[gpRegisterIndex], externalName, 0, 8));
                    addSymbolRecord(new CVSymbolSubrecord.CVSymbolDefRangeFramepointerRelFullScope(cvDebugInfo, 0));
                }
                gpRegisterIndex++;
            }
            for (int i = 0; i < method.getParamCount(); i++) {
                final TypeEntry paramType = method.getParamType(i);
                final int typeIndex = cvDebugInfo.getCVTypeSection().addTypeRecords(paramType).getSequenceNumber();
                final String paramName = "p" + (i + 1);
                if (typeIndex == T_REAL64 || typeIndex == T_REAL32) {
                    /* floating point primitive */
                    if (fpRegisterIndex < javaFP64registers.length) {
                        final short register = typeIndex == T_REAL64 ? javaFP64registers[fpRegisterIndex] : javaFP32registers[fpRegisterIndex];
                        addSymbolRecord(new CVSymbolSubrecord.CVSymbolLocalRecord(cvDebugInfo, paramName, typeIndex, 1));
                        //addSymbolRecord(new CVSymbolSubrecord.CVSymbolRegisterRecord(cvDebugInfo, paramName, typeIndex, javaFPregisters[fpRegisterIndex]));
                        addSymbolRecord(new CVSymbolSubrecord.CVSymbolDefRangeRegisterRecord(cvDebugInfo, register, externalName, 0, 8));
                        //addSymbolRecord(new CVSymbolSubrecord.CVSymbolDefRangeFramepointerRel(cvDebugInfo, "main", 8, (short) 8, 32));
                        addSymbolRecord(new CVSymbolSubrecord.CVSymbolDefRangeFramepointerRelFullScope(cvDebugInfo, 0));
                        //regRelRecords.add(new CVSymbolSubrecord.CVSymbolRegRel32Record(cvDebugInfo, paramName, typeIndex, 0, javaFPregisters[fpRegisterIndex]));
                        fpRegisterIndex++;
                    } else {
                        // TODO: stack parameter; keep track of stack offset, etc.
                        break;
                    }
                } else if (paramType.isPrimitive()) {
                    /* simple primitive */
                    if (gpRegisterIndex < javaGP64registers.length) {
                        final short register;
                        if (paramType.getSize() == 8) {
                            register = javaGP64registers[gpRegisterIndex];
                        } else if (paramType.getSize() == 4) {
                            register = javaGP32registers[gpRegisterIndex];
                        } else if (paramType.getSize() == 2) {
                            register = javaGP16registers[gpRegisterIndex];
                        } else if (paramType.getSize() == 1) {
                            register = javaGP8registers[gpRegisterIndex];
                        } else {
                            register = 0; /* Avoid warning. */
                            GraalError.shouldNotReachHere("Unknown primitive (type" + paramType.getTypeName() + ") size:" + paramType.getSize());
                        }
                        addSymbolRecord(new CVSymbolSubrecord.CVSymbolLocalRecord(cvDebugInfo, paramName, typeIndex, 1));
                        // addSymbolRecord(new CVSymbolSubrecord.CVSymbolRegisterRecord(cvDebugInfo, paramName, typeIndex, javaGPregisters[gpRegisterIndex]));
                        addSymbolRecord(new CVSymbolSubrecord.CVSymbolDefRangeRegisterRecord(cvDebugInfo, register, externalName, 0, 8));
                       // addSymbolRecord(new CVSymbolSubrecord.CVSymbolDefRangeFramepointerRel(cvDebugInfo, "main", 8, (short) 8, 32));
                        addSymbolRecord(new CVSymbolSubrecord.CVSymbolDefRangeFramepointerRelFullScope(cvDebugInfo, 8));
                      //  regRelRecords.add(new CVSymbolSubrecord.CVSymbolRegRel32Record(cvDebugInfo, paramName, typeIndex, 0, javaGPregisters[gpRegisterIndex]));
                        gpRegisterIndex++;
                    } else {
                        // TODO: stack parameter; keep track of stack offset, etc.
                        break;
                    }
                } else {
                    /* Java object. */
                    if (gpRegisterIndex < javaGP64registers.length) {
                        //                         int pointerIndex = cvDebugInfo.getCVTypeSection().getIndexForPointer(paramType);
                        // define as offset from register addSymbolRecord(new CVSymbolSubrecord.CVSymbolRegRel32Record(cvDebugInfo, paramName, typeIndex, 0, javaGPregisters[gpRegisterIndex]));
                        addSymbolRecord(new CVSymbolSubrecord.CVSymbolLocalRecord(cvDebugInfo, paramName, typeIndex, 1));
                        addSymbolRecord(new CVSymbolSubrecord.CVSymbolDefRangeRegisterRecord(cvDebugInfo, javaGP64registers[gpRegisterIndex], externalName, 0, 8));
                        addSymbolRecord(new CVSymbolSubrecord.CVSymbolDefRangeFramepointerRelFullScope(cvDebugInfo, 0));
                       // regRelRecords.add(new CVSymbolSubrecord.CVSymbolRegRel32Record(cvDebugInfo, paramName, typeIndex, 0, javaGPregisters[gpRegisterIndex]));
                        gpRegisterIndex++;
                    } else {
                        // TODO: stack parameter; keep track of stack offset, etc.
                        break;
                    }
                }
            }
            for (CVSymbolSubrecord record : regRelRecords) {
                addSymbolRecord(record);
            }
            /* TODO: add entries for stack parameters. */
  //          addToSymbolSubsection(new CVSymbolSubrecord.CVSymbolEndRecord(cvDebugInfo));
        }

        /* TODO: add local variables, and their types. */
        /* TODO: add block definitions. */

        /* S_END add end record. */
        addSymbolRecord(new CVSymbolSubrecord.CVSymbolEndRecord(cvDebugInfo));

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
    private void addSymbolRecord(CVSymbolSubrecord record) {
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
