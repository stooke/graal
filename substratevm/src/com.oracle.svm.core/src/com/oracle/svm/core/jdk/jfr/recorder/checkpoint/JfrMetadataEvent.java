/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, Red Hat Inc. All rights reserved.
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

package com.oracle.svm.core.jdk.jfr.recorder.checkpoint;

import java.io.IOException;

import com.oracle.svm.core.jdk.jfr.recorder.repository.JfrChunkWriter;
import com.oracle.svm.core.jdk.jfr.utilities.JfrTicks;
import com.oracle.svm.core.jdk.jfr.utilities.JfrTypes;

public class JfrMetadataEvent {
    private static long metadataId = 0;
    private static long lastMetadataId = 0;
    private static byte[] metadata;

    public static void write(JfrChunkWriter writer) {
        assert (writer.isValid());

        if (JfrMetadataEvent.lastMetadataId == JfrMetadataEvent.metadataId && writer.hasMetadata()) {
            return;
        }

        try {
            long metadataOffset = writer.reserve(Integer.BYTES);
            writer.encoded().writeLong(JfrTypes.ReservedEvent.EVENT_METADATA.id);
            writer.encoded().writeLong(JfrTicks.now());
            // Duration, always 0
            writer.encoded().writeLong(0);
            writer.encoded().writeLong(metadataId);

            writeMetadata(writer, Thread.currentThread());

            int sizeWritten = (int) (writer.getCurrentOffset() - metadataOffset);
            writer.padded().writeInt(sizeWritten, metadataOffset);

            writer.setLastMetadataOffset(metadataOffset);
            lastMetadataId = metadataId;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void writeMetadata(JfrChunkWriter writer, Thread thread) {
        assert (writer.isValid());
        assert (thread != null);
        assert (metadata != null);
        if (metadata == null) {
            System.err.println("XXXXXXXXXXXXXXXXXXXXXXXX    MDE.writeMetadata(): matadata is null; returning - JFR file will be corrupt");
            try {
                writer.writeUnbuffered(new byte[0], 0);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        } else {
            System.err.println("MDE.writeMetadata(): matadata is " + metadata.length + " bytes long");
        }
        if (writer == null) System.err.println("MDE: writer is null");
        try {
            writer.writeUnbuffered(metadata, metadata.length);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Called from JFR Java code
    public static void update(byte[] metadata) {
        JfrMetadataEvent.metadata = metadata;
        /*
        at com.oracle.svm.core.jdk.jfr.recorder.checkpoint.JfrMetadataEvent.update(JfrMetadataEvent.java:97)
        at jdk.jfr.internal.JVM.storeMetadataDescriptor(JfrSubstitutions.java:731)
        at jdk.jfr.internal.MetadataRepository.storeDescriptorInJVM(MetadataRepository.java:219)
        at jdk.jfr.internal.MetadataRepository.setOutput(MetadataRepository.java:263)
        at jdk.jfr.internal.PlatformRecorder.start(PlatformRecorder.java:230)
        at jdk.jfr.internal.PlatformRecording.start(PlatformRecording.java:114)
        at jdk.jfr.Recording.start(Recording.java:169)
        at HelloWorld.main(HelloWorld.java:261)
MDE.writeMetadata(): matadata is 76039 bytes long


        System.err.println("MDE.update() called from");
        new Exception("foo").printStackTrace(); */
        metadataId++;
    }
}
