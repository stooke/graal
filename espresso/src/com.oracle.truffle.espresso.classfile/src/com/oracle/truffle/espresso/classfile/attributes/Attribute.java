/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.truffle.espresso.classfile.attributes;

import java.util.Arrays;
import java.util.Objects;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.espresso.classfile.ConstantPool;
import com.oracle.truffle.espresso.classfile.descriptors.Name;
import com.oracle.truffle.espresso.classfile.descriptors.Symbol;

public class Attribute {

    public static final Attribute[] EMPTY_ARRAY = new Attribute[0];

    private final Symbol<Name> name;

    @CompilationFinal(dimensions = 1) //
    private final byte[] data;

    public final Symbol<Name> getName() {
        return name;
    }

    /**
     * Attribute raw data. Known attributes that parse the raw data, can drop the raw data (return
     * null).
     */
    public final byte[] getData() {
        return data;
    }

    public Attribute(Symbol<Name> name, final byte[] data) {
        this.name = name;
        this.data = data;
    }

    /**
     * Used as a dedicated and specialized replacement for equals().
     *
     * @param other the object to compare 'this' to
     * @return true if objects are equal
     */
    @SuppressWarnings("unused")
    public boolean isSame(Attribute other, ConstantPool thisPool, ConstantPool otherPool) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return Objects.equals(name, other.name) && Arrays.equals(data, other.data);
    }
}
