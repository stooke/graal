/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, Red Hat Inc. All rights reserved.
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

package com.oracle.svm.core.jdk.jfr.remote;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

public class SecureValidatorImpl implements Validator {

    static String secret = null;

    SecureValidatorImpl(String secret) {
        this.secret = secret;
    }

    /* return false if not allowed */
    public boolean validate(HttpExchange exchange) throws IOException {

        final String apiKey = exchange.getRequestHeaders().getFirst("X-api-key");

        if (apiKey == null) {
            return false;
        }

        final String hostname = exchange.getRemoteAddress().getHostName();
        return validateHostAndApiKey(hostname, apiKey);
    }


    /* return false if not allowed */
    private boolean validateHostAndApiKey(String hostName, String apiKey) throws IOException {

        if (secret != null && !apiKey.equals(secret)) {
            /* secret does not match */
            return false;
        }

        /* JFR-TODO: validate host (is locahost or on list) */
        return true;
    }
}
