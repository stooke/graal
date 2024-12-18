/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.polyglot;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;

/**
 * A special implementation for polyglot bindings, exposed to the embedder. The difference to a
 * normal polyglot value is that it preserves the language information for each member.
 */
final class PolyglotBindingsValue extends PolyglotValueDispatch {

    final Map<String, Object> values;
    final PolyglotLanguageContext languageContext;
    final PolyglotBindings bindings;

    PolyglotBindingsValue(PolyglotLanguageContext context, PolyglotBindings bindings) {
        super(context.getImpl(), context.getLanguageInstance());
        this.values = context.context.polyglotBindings;
        this.languageContext = context;
        this.bindings = bindings;
    }

    @Override
    public Object getMember(Object context, Object receiver, String key) {
        return values.get(key);
    }

    @Override
    public Set<String> getMemberKeys(Object context, Object receiver) {
        return values.keySet();
    }

    @Override
    public boolean removeMember(Object context, Object receiver, String key) {
        Object result = values.remove(key);
        return result != null;
    }

    @Override
    public void putMember(Object context, Object receiver, String key, Object member) {
        values.put(key, ((PolyglotLanguageContext) context).context.asValue(member));
    }

    @Override
    public boolean hasMembers(Object context, Object receiver) {
        return true;
    }

    @Override
    public boolean hasMember(Object context, Object receiver, String key) {
        return values.containsKey(key);
    }

    /*
     * It would be very hard to implement the #as(Class) semantics again here. So we just delegate
     * to an interop value in such a case. This also means that we loose language information for
     * members.
     */
    @Override
    public <T> T asClass(Object context, Object receiver, Class<T> targetType) {
        return impl.getAPIAccess().callValueAs(languageContext.asValue(bindings), targetType);
    }

    @Override
    public <T> T asTypeLiteral(Object context, Object receiver, Class<T> rawType, Type type) {
        return impl.getAPIAccess().callValueAs(languageContext.asValue(bindings), rawType, type);
    }

    @Override
    public String toStringImpl(PolyglotLanguageContext context, Object receiver) {
        return languageContext.asValue(bindings).toString();
    }

    @Override
    public Object getMetaObjectImpl(PolyglotLanguageContext context, Object receiver) {
        return impl.getAPIAccess().callValueGetMetaObject(languageContext.asValue(bindings));
    }
}
