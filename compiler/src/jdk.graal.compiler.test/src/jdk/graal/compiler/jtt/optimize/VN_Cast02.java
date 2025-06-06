/*
 * Copyright (c) 2009, 2018, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.jtt.optimize;

import jdk.graal.compiler.jtt.JTTTest;
import org.junit.Test;

/*
 * Tests constant folding of integer operations.
 */
public class VN_Cast02 extends JTTTest {

    private static final class TestClass {
        int field = 9;
    }

    private static boolean cond = true;
    static final Object object = new TestClass();

    public static int test(int arg) {
        if (arg == 0) {
            return test1();
        }
        if (arg == 1) {
            return test2();
        }
        if (arg == 2) {
            return test3();
        }
        return 0;
    }

    private static int test1() {
        Object o = object;
        TestClass a = (TestClass) o;
        if (cond) {
            TestClass b = (TestClass) o;
            return a.field + b.field;
        }
        return 0;
    }

    private static int test2() {
        Object obj = new TestClass();
        TestClass a = (TestClass) obj;
        if (cond) {
            TestClass b = (TestClass) obj;
            return a.field + b.field;
        }
        return 0;
    }

    @SuppressWarnings("all")
    private static int test3() {
        Object o = null;
        TestClass a = (TestClass) o;
        if (cond) {
            TestClass b = (TestClass) o;
            return a.field + b.field;
        }
        return 0;
    }

    @Test
    public void run0() throws Throwable {
        runTest("test", 0);
    }

    @Test
    public void run1() throws Throwable {
        runTest("test", 1);
    }

    @Test
    public void run2() throws Throwable {
        runTest("test", 2);
    }

}
