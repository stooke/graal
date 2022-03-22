/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.oracle.truffle.api.strings.test;

import static com.oracle.truffle.api.strings.TruffleString.Encoding.UTF_8;

import java.nio.charset.StandardCharsets;

import org.junit.Assert;
import org.junit.Test;

import com.oracle.truffle.api.strings.TruffleString;

public class TStringUTF8Tests extends TStringTestBase {

    private static final byte[][] VALID = {
                    utf8Encode(0x00),
                    utf8Encode(0x7f),
                    utf8Encode(0x80),
                    utf8Encode(0x7ff),
                    utf8Encode(0x800),
                    utf8Encode(Character.MIN_SURROGATE - 1),
                    utf8Encode(Character.MAX_SURROGATE + 1),
                    utf8Encode(0xffff),
                    utf8Encode(0x10000),
                    utf8Encode(Character.MAX_CODE_POINT),
    };

    private static final byte[][] INVALID = {
                    TStringTestUtil.byteArray(0x80),
                    TStringTestUtil.byteArray(0xc0, 0x80),
                    TStringTestUtil.byteArray(0b11000000),
                    TStringTestUtil.byteArray(0b11000000, 0x80, 0x80),
                    TStringTestUtil.byteArray(0b11100000),
                    TStringTestUtil.byteArray(0b11100000, 0x80),
                    TStringTestUtil.byteArray(0b11100000, 0x80, 0x80, 0x80),
                    TStringTestUtil.byteArray(0b11110000),
                    TStringTestUtil.byteArray(0b11110000, 0x80, 0x80),
                    TStringTestUtil.byteArray(0b11110000, 0x80, 0x80, 0x80, 0x80),
                    TStringTestUtil.byteArray(0b11111000),
                    TStringTestUtil.byteArray(0b11111000, 0x80, 0x80, 0x80, 0x80),
                    TStringTestUtil.byteArray(0b11111100),
                    TStringTestUtil.byteArray(0b11111100, 0x80, 0x80, 0x80, 0x80, 0x80),
                    TStringTestUtil.byteArray(0b11111110),
                    TStringTestUtil.byteArray(0b11111111),
                    TStringTestUtil.byteArray(0xf4, 0x90, 0x80, 0x80),
                    TStringTestUtil.byteArray(0xed, 0xb0, 0x80),
                    TStringTestUtil.byteArray(0xed, 0xbf, 0xbf),
                    TStringTestUtil.byteArray(0xed, 0xa0, 0x80),
                    TStringTestUtil.byteArray(0xed, 0xaf, 0xbf),
                    TStringTestUtil.byteArray(0xc0, 0xbf),
    };

    private static byte[] utf8Encode(int codepoint) {
        return new StringBuilder().appendCodePoint(codepoint).toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void testValid() {
        for (byte[] arr : VALID) {
            Assert.assertTrue(TStringTestUtil.hex(arr), TruffleString.fromByteArrayUncached(arr, 0, arr.length, UTF_8, false).isValidUncached(UTF_8));
        }
    }

    @Test
    public void testInvalid() {
        for (byte[] arr : INVALID) {
            Assert.assertFalse(TStringTestUtil.hex(arr), TruffleString.fromByteArrayUncached(arr, 0, arr.length, UTF_8, false).isValidUncached(UTF_8));
        }
    }

    @Test
    public void testCodePointLength1() {
        byte[] arr = TStringTestUtil.byteArray(0xf4, 0x90, 0x80, 0x80, 0x7f, 0x7f);
        TruffleString a = TruffleString.fromByteArrayUncached(arr, 0, arr.length, UTF_8, false);
        a.toString();
        Assert.assertEquals(6, a.codePointLengthUncached(UTF_8));
    }

    @Test
    public void testCodePointLength2() {
        byte[] arr = TStringTestUtil.byteArray(0, 0, 0xc0, 0xbf);
        TruffleString a = TruffleString.fromByteArrayUncached(arr, 0, arr.length, UTF_8, false);
        Assert.assertEquals(4, a.codePointLengthUncached(UTF_8));
    }

    @Test
    public void testIndexOf() {
        TruffleString s1 = TruffleString.fromJavaStringUncached("aaa", UTF_8);
        TruffleString s2 = TruffleString.fromJavaStringUncached("a", UTF_8);
        Assert.assertEquals(-1, s1.byteIndexOfStringUncached(s2, 1, 1, UTF_8));
    }

    @Test
    public void testIndexOf2() {
        TruffleString a = TruffleString.fromCodePointUncached(0x102, UTF_8);
        TruffleString b = TruffleString.fromCodePointUncached(0x10_0304, UTF_8);
        TruffleString s1 = a.repeatUncached(10, UTF_8);
        TruffleString s2 = a.concatUncached(b, UTF_8, false);
        Assert.assertEquals(-1, s1.byteIndexOfStringUncached(s2, 0, s1.byteLength(UTF_8), UTF_8));
        Assert.assertEquals(-1, s1.indexOfStringUncached(s2, 0, s1.codePointLengthUncached(UTF_8), UTF_8));
    }

    @Test
    public void testIndexOf3() {
        TruffleString a = TruffleString.fromJavaStringUncached("aaa", UTF_8);
        TruffleString b = TruffleString.fromJavaStringUncached("baa", UTF_8);
        Assert.assertEquals(-1, a.lastIndexOfStringUncached(b, 3, 0, UTF_8));
    }

    @Test
    public void testIndexOf4() {
        TruffleString a = TruffleString.fromJavaStringUncached("defghiabc", UTF_8);
        TruffleString b = TruffleString.fromJavaStringUncached("def", UTF_8);
        Assert.assertEquals(-1, a.lastIndexOfStringUncached(b, 9, 1, UTF_8));
    }

    @Test
    public void testIndexOf5() {
        TruffleString ts1 = TruffleString.fromJavaStringUncached("a\u00A3b\u00A3", UTF_8);
        TruffleString ts2 = TruffleString.fromJavaStringUncached("a\u00A3", UTF_8);
        Assert.assertEquals(-1, ts1.lastIndexOfStringUncached(ts2, 4, 1, UTF_8));
        Assert.assertEquals(-1, ts1.lastByteIndexOfStringUncached(ts2, 6, 1, UTF_8));
    }

    @Test
    public void testIndexOf6() {
        TruffleString ts1 = TruffleString.fromJavaStringUncached("<......\u043c...", UTF_8);
        TruffleString ts2 = TruffleString.fromJavaStringUncached("<", UTF_8);
        Assert.assertEquals(0, ts1.lastIndexOfStringUncached(ts2, ts1.codePointLengthUncached(UTF_8), 0, UTF_8));
    }
}
