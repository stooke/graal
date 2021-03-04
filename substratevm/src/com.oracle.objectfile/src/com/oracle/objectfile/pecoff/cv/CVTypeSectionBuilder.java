package com.oracle.objectfile.pecoff.cv;

import com.oracle.objectfile.debugentry.ClassEntry;
import com.oracle.objectfile.debugentry.FieldEntry;
import com.oracle.objectfile.debugentry.InterfaceClassEntry;
import com.oracle.objectfile.debugentry.PrimaryEntry;
import org.graalvm.compiler.debug.DebugContext;

import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_CHAR;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_CHAR16;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_LONG;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_NOTYPE;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_QUAD;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL32;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_REAL64;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_SHORT;
import static com.oracle.objectfile.pecoff.cv.CVTypeConstants.T_VOID;

public class CVTypeSectionBuilder {

    CVTypeSectionImpl typeSection;
    DebugContext debugContext;

    CVTypeSectionBuilder(DebugContext debugContext, CVTypeSectionImpl typeSection) {
        this.debugContext = debugContext;
        this.typeSection = typeSection;
        addPrimitiveTypes();
    }

    /**
     * Add type records for function. (later add arglist, and return type and local types).
     *
     * @param entry primaryEntry containing entities whoses type records must be added
     * @return type index of function type
     */
    int buildFunction(PrimaryEntry entry) {
        debugContext.log("build primary " + entry);
        CVTypeRecord returnType = findOrCreateType(entry.getPrimary().getMethodReturnTypeName());
        CVTypeRecord.CVTypeArglistRecord argListType = addTypeRecord(new CVTypeRecord.CVTypeArglistRecord().add(T_NOTYPE));
        CVTypeRecord funcType = addTypeRecord(new CVTypeRecord.CVTypeProcedureRecord().returnType(returnType).argList(argListType));

        buildClassIfNotKnown(entry.getClassEntry());

        return funcType.getSequenceNumber();
    }

    void buildClassIfNotKnown(ClassEntry classEntry) {
        if (!typeSection.hasType(classEntry.getTypeName())) {
            buildClass(classEntry);
        }
    }

    void buildField(FieldEntry fieldEntry) {
        debugContext.log("  build field" + fieldEntry);
    }

    int buildClass(ClassEntry classEntry) {
        debugContext.log("build class " + classEntry);
        if (classEntry.getSuperClass() != null) {
            buildClassIfNotKnown(classEntry.getSuperClass());
        }
        /* No need to process interfaces? */
        classEntry.fields().forEach(f -> buildField(f));
        return 0;
    }

    void addPrimitiveTypes() {
        typeSection.defineType("void", new CVTypeRecord.CVTypePrimitive(T_VOID));
        typeSection.defineType("byte", new CVTypeRecord.CVTypePrimitive(T_CHAR));
        typeSection.defineType("boolean", new CVTypeRecord.CVTypePrimitive(T_LONG)); // DANGER - this type is not actually defined
        typeSection.defineType("char", new CVTypeRecord.CVTypePrimitive(T_CHAR16));  // unsigned
        typeSection.defineType("short", new CVTypeRecord.CVTypePrimitive(T_SHORT));
        typeSection.defineType("int", new CVTypeRecord.CVTypePrimitive(T_LONG));
        typeSection.defineType("long", new CVTypeRecord.CVTypePrimitive(T_QUAD));
        typeSection.defineType("float", new CVTypeRecord.CVTypePrimitive(T_REAL32));
        typeSection.defineType("double", new CVTypeRecord.CVTypePrimitive(T_REAL64));
    }

    private <T extends CVTypeRecord> CVTypeRecord findOrCreateType(String typeName) {
        if (!typeSection.hasType(typeName)) {
            // TODO
            return null;
        } else {
            return typeSection.getType(typeName);
        }
    }

    private <T extends CVTypeRecord> T addTypeRecord(T record) {
        return typeSection.addOrReference(record);
    }
}


