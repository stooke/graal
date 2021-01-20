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
package com.oracle.svm.hosted.image;

import static com.oracle.objectfile.debuginfo.DebugInfoProvider.DebugFrameSizeChange.Type.CONTRACT;
import static com.oracle.objectfile.debuginfo.DebugInfoProvider.DebugFrameSizeChange.Type.EXTEND;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.oracle.graal.pointsto.infrastructure.OriginalClassProvider;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.objectfile.debuginfo.DebugInfoProvider;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.hosted.image.sources.SourceManager;
import com.oracle.svm.hosted.meta.HostedArrayClass;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedInstanceClass;
import com.oracle.svm.hosted.meta.HostedInterface;
import com.oracle.svm.hosted.meta.HostedPrimitiveType;

import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.code.SourceMapping;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.NodeSourcePosition;
import org.graalvm.nativeimage.ImageSingletons;


import jdk.vm.ci.meta.LineNumberTable;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Implementation of the DebugInfoProvider API interface that allows type, code and heap data info
 * to be passed to an ObjectFile when generation of debug info is enabled.
 */
class NativeImageDebugInfoProvider implements DebugInfoProvider {
    private final DebugContext debugContext;
    private final NativeImageCodeCache codeCache;
    @SuppressWarnings("unused") private final NativeImageHeap heap;

    NativeImageDebugInfoProvider(DebugContext debugContext, NativeImageCodeCache codeCache, NativeImageHeap heap) {
        super();
        this.debugContext = debugContext;
        this.codeCache = codeCache;
        this.heap = heap;
    }

    @Override
    public Stream<DebugTypeInfo> typeInfoProvider() {
        return heap.getUniverse().getTypes().stream().map(this::createDebugTypeInfo);
    }

    @Override
    public Stream<DebugCodeInfo> codeInfoProvider() {
        return codeCache.compilations.entrySet().stream().map(entry -> new NativeImageDebugCodeInfo(entry.getKey(), entry.getValue()));
    }

    @Override
    public Stream<DebugDataInfo> dataInfoProvider() {
        return Stream.empty();
    }

    static ObjectLayout OBJECTLAYOUT = ConfigurationValues.getObjectLayout();

    private abstract class NativeImageDebugTypeInfo implements DebugTypeInfo {

        protected final HostedType hostedType;
        protected final ResolvedJavaType javaType;
        protected final Class<?> clazz;

        protected NativeImageDebugTypeInfo(HostedType hostedType) {
            this.hostedType = hostedType;
            this.javaType = hostedType.getWrapped();
            if (hostedType instanceof OriginalClassProvider) {
                clazz = ((OriginalClassProvider) hostedType).getJavaClass();
            } else {
                clazz = null;
            }
        }

        @SuppressWarnings("try")
        @Override
        public void debugContext(Consumer<DebugContext> action) {
            try (DebugContext.Scope s = debugContext.scope("DebugTypeInfo",  typeName())) {
                action.accept(debugContext);
            } catch (Throwable e) {
                throw debugContext.handle(e);
            }
        }

        @Override
        public String typeName() {
            return hostedType.toJavaName();
        }

        @Override
        public int size() {
            if (hostedType instanceof HostedInstanceClass) {
                return ((HostedInstanceClass) hostedType).getInstanceSize();
            } else {
                return hostedType.getStorageKind().getByteCount();
            }
        }
    }

    private class NativeImageDebugEnumTypeInfo extends NativeImageDebugInstanceTypeInfo implements DebugEnumTypeInfo {
        HostedInstanceClass enumClass;

        NativeImageDebugEnumTypeInfo(HostedInstanceClass enumClass) {
            super(enumClass);
            this.enumClass = enumClass;
        }

        public DebugTypeKind typeKind() {
            return DebugTypeKind.ENUM;
        }
    }

    private class NativeImageDebugInstanceTypeInfo extends NativeImageDebugTypeInfo implements DebugInstanceTypeInfo {
        NativeImageDebugInstanceTypeInfo(HostedType hostedType) {
            super(hostedType);
        }

        public DebugTypeKind typeKind() {
            return DebugTypeKind.INSTANCE;
        }

        @Override
        public int headerSize() {
            return OBJECTLAYOUT.getFirstFieldOffset();
        }
        @Override
        public Stream<DebugFieldInfo> fieldInfoProvider() {
            return Arrays.stream(hostedType.getInstanceFields(true)).map(this::createDebugFieldInfo);
        }

        protected NativeImageDebugFieldInfo createDebugFieldInfo(HostedField field) {
            return new NativeImageDebugFieldInfo(field);
        }

        protected class NativeImageDebugFieldInfo implements DebugFieldInfo {
            private final HostedField field;

            NativeImageDebugFieldInfo(HostedField field) {
                this.field = field;
            }

            @Override
            public String ownerType() {
                return typeName();
            }

            @Override
            public String valueType() {
                return field.getType().toJavaName();
            }

            @Override
            public int offset() {
                return field.getLocation();
            }

            @Override
            public int size() {
                return OBJECTLAYOUT.sizeInBytes(field.getType().getStorageKind());
            }
        }
    }

    private class NativeImageDebugInterfaceTypeInfo extends NativeImageDebugTypeInfo implements DebugInterfaceTypeInfo {
        HostedInterface interfaceClass;
        NativeImageDebugInterfaceTypeInfo(HostedInterface interfaceClass) {
            super(interfaceClass);
            this.interfaceClass = interfaceClass;
        }

        public DebugTypeKind typeKind() {
            return DebugTypeKind.INTERFACE;
        }
    }

    private class NativeImageDebugArrayTypeInfo extends NativeImageDebugTypeInfo implements DebugArrayTypeInfo {
        HostedArrayClass arrayClass;

        NativeImageDebugArrayTypeInfo(HostedArrayClass arrayClass) {
            super(arrayClass);
            this.arrayClass = arrayClass;
        }

        public DebugTypeKind typeKind() {
            return DebugTypeKind.ARRAY;
        }

        @Override
        public int headerSize() {
            return OBJECTLAYOUT.getArrayBaseOffset(arrayClass.getComponentType().getStorageKind());
        }

        @Override
        public int lengthOffset() {
            return OBJECTLAYOUT.getArrayLengthOffset();
        }

        @Override
        public String elementType() {
            return arrayClass.getComponentType().toJavaName();
        }
    }

    private class NativeImageDebugPrimitiveTypeInfo extends NativeImageDebugTypeInfo implements DebugPrimitiveTypeInfo {
        private final HostedPrimitiveType primitiveType;

        NativeImageDebugPrimitiveTypeInfo(HostedPrimitiveType primitiveType) {
            super(primitiveType);
            this.primitiveType = primitiveType;
        }

        public DebugTypeKind typeKind() {
            return DebugTypeKind.PRIMITIVE;
        }

        @Override
        public int bitCount() {
            return primitiveType.getStorageKind().getBitCount();
        }

        @Override
        public int flags() {
            char typeChar = primitiveType.getStorageKind().getTypeChar();
            switch (typeChar) {
                case 'B':
                case 'S':
                case 'I':
                case 'J':
                {
                    return FLAG_NUMERIC | FLAG_INTEGRAL | FLAG_SIGNED;
                }
                case 'C':
                {
                    return FLAG_NUMERIC | FLAG_INTEGRAL;
                }
                case 'F':
                case 'D':
                {
                    return FLAG_NUMERIC;
                }
                default:
                {
                    assert typeChar == 'V' || typeChar == 'Z';
                    return 0;
                }
            }
        }
    }

    private NativeImageDebugTypeInfo createDebugTypeInfo(HostedType hostedType) {
        if (hostedType.isEnum()) {
            return new NativeImageDebugEnumTypeInfo((HostedInstanceClass) hostedType);
        } else if (hostedType.isInstanceClass()) {
            return new NativeImageDebugInstanceTypeInfo((HostedInstanceClass) hostedType);
        } else if (hostedType.isInterface()) {
            return new NativeImageDebugInterfaceTypeInfo((HostedInterface) hostedType);
        } else if (hostedType.isArray()) {
            return new NativeImageDebugArrayTypeInfo((HostedArrayClass) hostedType);
        } else if (hostedType.isPrimitive()) {
            return new NativeImageDebugPrimitiveTypeInfo((HostedPrimitiveType) hostedType);
        } else {
            throw new RuntimeException("Unknown type kind " + hostedType.getName());
        }
    }

    /**
     * Implementation of the DebugCodeInfo API interface that allows code info to be passed to an
     * ObjectFile when generation of debug info is enabled.
     */
    private class NativeImageDebugCodeInfo implements DebugCodeInfo {
        private final HostedMethod method;
        private final ResolvedJavaType javaType;
        private final CompilationResult compilation;
        private Path fullFilePath;
        private final Path cachePath;

        @SuppressWarnings("try")
        NativeImageDebugCodeInfo(HostedMethod method, CompilationResult compilation) {
            this.method = method;
            HostedType declaringClass = method.getDeclaringClass();
            Class<?> clazz = declaringClass.getJavaClass();
            /*
             * HostedType wraps an AnalysisType and both HostedType and AnalysisType punt calls to
             * getSourceFilename to the wrapped class so for consistency we need to do the path
             * lookup relative to the doubly unwrapped HostedType.
             */
            this.javaType = declaringClass.getWrapped().getWrapped();
            this.compilation = compilation;
            this.cachePath = SubstrateOptions.getDebugInfoSourceCacheRoot();
            SourceManager sourceManager = ImageSingletons.lookup(SourceManager.class);
            try (DebugContext.Scope s = debugContext.scope("DebugCodeInfo", declaringClass)) {
                fullFilePath = sourceManager.findAndCacheSource(javaType, clazz, debugContext);
            } catch (Throwable e) {
                throw debugContext.handle(e);
            }
        }

        @SuppressWarnings("try")
        @Override
        public void debugContext(Consumer<DebugContext> action) {
            try (DebugContext.Scope s = debugContext.scope("DebugCodeInfo", method)) {
                action.accept(debugContext);
            } catch (Throwable e) {
                throw debugContext.handle(e);
            }
        }

        @Override
        public String fileName() {
            if (fullFilePath != null) {
                Path filename = fullFilePath.getFileName();
                if (filename != null) {
                    return filename.toString();
                }
            }
            return "";
        }

        @Override
        public Path filePath() {
            if (fullFilePath != null) {
                return fullFilePath.getParent();
            }
            return null;
        }

        @Override
        public Path cachePath() {
            return cachePath;
        }

        @Override
        public String className() {
            return javaType.toClassName();
        }

        @Override
        public String methodName() {
            return method.format("%n");
        }

        @Override
        public String symbolNameForMethod() {
            return NativeBootImage.localSymbolNameForMethod(method);
        }

        @Override
        public String paramNames() {
            return method.format("%P");
        }

        @Override
        public String returnTypeName() {
            return method.format("%R");
        }

        @Override
        public int addressLo() {
            return method.getCodeAddressOffset();
        }

        @Override
        public int addressHi() {
            return method.getCodeAddressOffset() + compilation.getTargetCodeSize();
        }

        @Override
        public int line() {
            LineNumberTable lineNumberTable = method.getLineNumberTable();
            if (lineNumberTable != null) {
                return lineNumberTable.getLineNumber(0);
            }
            return -1;
        }

        @Override
        public Stream<DebugLineInfo> lineInfoProvider() {
            if (fileName().length() == 0) {
                return Stream.empty();
            }
            return compilation.getSourceMappings().stream().map(sourceMapping -> new NativeImageDebugLineInfo(sourceMapping));
        }

        @Override
        public int getFrameSize() {
            return compilation.getTotalFrameSize();
        }

        @Override
        public List<DebugFrameSizeChange> getFrameSizeChanges() {
            List<DebugFrameSizeChange> frameSizeChanges = new LinkedList<>();
            for (CompilationResult.CodeMark mark : compilation.getMarks()) {
                // we only need to observe stack increment or decrement points
                if (mark.id.equals(SubstrateBackend.SubstrateMarkId.PROLOGUE_DECD_RSP)) {
                    NativeImageDebugFrameSizeChange sizeChange = new NativeImageDebugFrameSizeChange(mark.pcOffset, EXTEND);
                    frameSizeChanges.add(sizeChange);
                    // } else if (mark.id.equals("PROLOGUE_END")) {
                    // can ignore these
                    // } else if (mark.id.equals("EPILOGUE_START")) {
                    // can ignore these
                } else if (mark.id.equals(SubstrateBackend.SubstrateMarkId.EPILOGUE_INCD_RSP)) {
                    NativeImageDebugFrameSizeChange sizeChange = new NativeImageDebugFrameSizeChange(mark.pcOffset, CONTRACT);
                    frameSizeChanges.add(sizeChange);
                } else if (mark.id.equals(SubstrateBackend.SubstrateMarkId.EPILOGUE_END) && mark.pcOffset < compilation.getTargetCodeSize()) {
                    // there is code after this return point so notify stack extend again
                    NativeImageDebugFrameSizeChange sizeChange = new NativeImageDebugFrameSizeChange(mark.pcOffset, EXTEND);
                    frameSizeChanges.add(sizeChange);
                }
            }
            return frameSizeChanges;
        }

        @Override
        public boolean isDeoptTarget() {
            return methodName().endsWith(HostedMethod.METHOD_NAME_DEOPT_SUFFIX);
        }
    }

    /**
     * Implementation of the DebugLineInfo API interface that allows line number info to be passed
     * to an ObjectFile when generation of debug info is enabled.
     */
    private class NativeImageDebugLineInfo implements DebugLineInfo {
        private final int bci;
        private final ResolvedJavaMethod method;
        private final int lo;
        private final int hi;
        private Path cachePath;
        private Path fullFilePath;

        NativeImageDebugLineInfo(SourceMapping sourceMapping) {
            NodeSourcePosition position = sourceMapping.getSourcePosition();
            int posbci = position.getBCI();
            this.bci = (posbci >= 0 ? posbci : 0);
            this.method = position.getMethod();
            this.lo = sourceMapping.getStartOffset();
            this.hi = sourceMapping.getEndOffset();
            this.cachePath = SubstrateOptions.getDebugInfoSourceCacheRoot();
            computeFullFilePath();
        }

        @Override
        public String fileName() {
            if (fullFilePath != null) {
                Path fileName = fullFilePath.getFileName();
                if (fileName != null) {
                    return fileName.toString();
                }
            }
            return null;
        }

        @Override
        public Path filePath() {
            if (fullFilePath != null) {
                return fullFilePath.getParent();
            }
            return null;
        }

        @Override
        public Path cachePath() {
            return cachePath;
        }

        @Override
        public String className() {
            return method.format("%H");
        }

        @Override
        public String methodName() {
            return method.format("%n");
        }

        @Override
        public String symbolNameForMethod() {
            return NativeBootImage.localSymbolNameForMethod(method);
        }

        @Override
        public int addressLo() {
            return lo;
        }

        @Override
        public int addressHi() {
            return hi;
        }

        @Override
        public int line() {
            LineNumberTable lineNumberTable = method.getLineNumberTable();
            if (lineNumberTable != null) {
                return lineNumberTable.getLineNumber(bci);
            }
            return -1;
        }

        @SuppressWarnings("try")
        private void computeFullFilePath() {
            ResolvedJavaType declaringClass = method.getDeclaringClass();
            Class<?> clazz = null;
            if (declaringClass instanceof OriginalClassProvider) {
                clazz = ((OriginalClassProvider) declaringClass).getJavaClass();
            }
            /*
<<<<<<< HEAD
             * HostedType wraps an AnalysisType and both HostedType and AnalysisType punt calls to
             * getSourceFilename to the wrapped class so for consistency we need to do the path
             * lookup relative to the doubly unwrapped HostedType or singly unwrapped AnalysisType.
=======
             * HostedType and HostedType punt calls to getSourceFilename to the wrapped class so
             * for consistency we need to do the path lookup relative to the wrapped class.
>>>>>>> e5e46875909... basic cut of DebugTypeInfo interface and implementation
             */
            if (declaringClass instanceof HostedType) {
                declaringClass = ((HostedType) declaringClass).getWrapped();
            }
            if (declaringClass instanceof AnalysisType) {
                declaringClass = ((AnalysisType) declaringClass).getWrapped();
            }
            SourceManager sourceManager = ImageSingletons.lookup(SourceManager.class);
            try (DebugContext.Scope s = debugContext.scope("DebugCodeInfo", declaringClass)) {
                fullFilePath = sourceManager.findAndCacheSource(declaringClass, clazz, debugContext);
            } catch (Throwable e) {
                throw debugContext.handle(e);
            }
        }

    }

    /**
     * Implementation of the DebugFrameSizeChange API interface that allows stack frame size change
     * info to be passed to an ObjectFile when generation of debug info is enabled.
     */
    private class NativeImageDebugFrameSizeChange implements DebugFrameSizeChange {
        private int offset;
        private Type type;

        NativeImageDebugFrameSizeChange(int offset, Type type) {
            this.offset = offset;
            this.type = type;
        }

        @Override
        public int getOffset() {
            return offset;
        }

        @Override
        public Type getType() {
            return type;
        }
    }
}
