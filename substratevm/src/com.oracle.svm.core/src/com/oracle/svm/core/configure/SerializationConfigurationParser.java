/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2020, Alibaba Group Holding Limited. All rights reserved.
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
package com.oracle.svm.core.configure;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.graalvm.nativeimage.impl.ConfigurationCondition;
import org.graalvm.nativeimage.impl.RuntimeSerializationSupport;

import com.oracle.svm.core.util.json.JSONParserException;

public class SerializationConfigurationParser extends ConfigurationParser {

    public static final String NAME_KEY = "name";
    public static final String CUSTOM_TARGET_CONSTRUCTOR_CLASS_KEY = "customTargetConstructorClass";
    private static final String SERIALIZATION_TYPES_KEY = "types";
    private static final String LAMBDA_CAPTURING_SERIALIZATION_TYPES_KEY = "lambdaCapturingTypes";

    private final RuntimeSerializationSupport serializationSupport;

    public SerializationConfigurationParser(RuntimeSerializationSupport serializationSupport, boolean strictConfiguration) {
        super(strictConfiguration);
        this.serializationSupport = serializationSupport;
    }

    @Override
    public void parseAndRegister(Object json, URI origin) {
        if (json instanceof List) {
            parseOldConfiguration(asList(json, "first level of document must be an array of serialization lists"));
        } else if (json instanceof Map) {
            parseNewConfiguration(asMap(json, "first level of document must be a map of serialization types"));
        } else {
            throw new JSONParserException("first level of document must either be an array of serialization lists or a map of serialization types");
        }
    }

    private void parseOldConfiguration(List<Object> listOfSerializationConfigurationObjects) {
        parseSerializationTypes(asList(listOfSerializationConfigurationObjects, "second level of document must be serialization descriptor objects"), false);
    }

    private void parseNewConfiguration(Map<String, Object> listOfSerializationConfigurationObjects) {
        if (!listOfSerializationConfigurationObjects.containsKey(SERIALIZATION_TYPES_KEY) || !listOfSerializationConfigurationObjects.containsKey(LAMBDA_CAPTURING_SERIALIZATION_TYPES_KEY)) {
            throw new JSONParserException("second level of document must be arrays of serialization descriptor objects");
        }

        parseSerializationTypes(asList(listOfSerializationConfigurationObjects.get(SERIALIZATION_TYPES_KEY), "types must be an array of serialization descriptor objects"), false);
        parseSerializationTypes(
                        asList(listOfSerializationConfigurationObjects.get(LAMBDA_CAPTURING_SERIALIZATION_TYPES_KEY), "lambdaCapturingTypes must be an array of serialization descriptor objects"),
                        true);
    }

    private void parseSerializationTypes(List<Object> listOfSerializationTypes, boolean lambdaCapturingTypes) {
        for (Object serializationType : listOfSerializationTypes) {
            parseSerializationDescriptorObject(asMap(serializationType, "third level of document must be serialization descriptor objects"), lambdaCapturingTypes);
        }
    }

    private void parseSerializationDescriptorObject(Map<String, Object> data, boolean lambdaCapturingType) {
        if (lambdaCapturingType) {
            checkAttributes(data, "serialization descriptor object", Collections.singleton(NAME_KEY), Collections.singleton(CONDITIONAL_KEY));
        } else {
            checkAttributes(data, "serialization descriptor object", Collections.singleton(NAME_KEY), Arrays.asList(CUSTOM_TARGET_CONSTRUCTOR_CLASS_KEY, CONDITIONAL_KEY));
        }

        ConfigurationCondition unresolvedCondition = parseCondition(data);
        String targetSerializationClass = asString(data.get(NAME_KEY));

        if (lambdaCapturingType) {
            serializationSupport.registerLambdaCapturingClass(unresolvedCondition, targetSerializationClass);
        } else {
            Object optionalCustomCtorValue = data.get(CUSTOM_TARGET_CONSTRUCTOR_CLASS_KEY);
            String customTargetConstructorClass = optionalCustomCtorValue != null ? asString(optionalCustomCtorValue) : null;
            serializationSupport.registerWithTargetConstructorClass(unresolvedCondition, targetSerializationClass, customTargetConstructorClass);
        }
    }
}
