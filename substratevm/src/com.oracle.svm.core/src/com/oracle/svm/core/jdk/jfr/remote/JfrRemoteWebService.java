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

import com.oracle.svm.core.jdk.jfr.logging.JfrLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
  simple web service, no authentication yet.
  requires --enable-all-security-services for https (currently added in NativeImageOptions)
 */
public class JfrRemoteWebService {

    enum ServerMode {
        STATUS_ONLY,
        FULL_CONTROL
    }

    static final int HTTP_OK = 200;
    @SuppressWarnings("unused") static final int HTTP_FORBIDDEN = 403;
    static final int HTTP_BAD_REQUEST = 400;
    @SuppressWarnings("unused") static final int HTTP_NOT_FOUND = 404;
    @SuppressWarnings("unused") static final int HTTP_INTERNAL_ERROR = 500;
    @SuppressWarnings("unused") static final int HTTP_NOT_IMPLEMENTED = 501;

    private MiniServer server;
    private boolean debug = true;
    private boolean useHttps = true;
    private int port = 0; // 0 for ephemeral port
    private ServerMode mode = ServerMode.FULL_CONTROL;
    private Validator validator = new InsecureValidatorImpl();
    //private Validator validator = new SecureValidatorImpl("secret");

    private JfrRemoteWebService() {
        JfrLogger.logInfo("JFR.remote: JFR REMOTE ACTIVE");
    }

    private void addContexts(HttpServer server) {

        if (getMode() == ServerMode.FULL_CONTROL) {
            server.createContext("/start").setHandler(exchange -> {
                JfrLogger.logInfo("JFR.remote: /start start");
                if (!validateAndReturnError(exchange)) {
                    return;
                }
                /* JFR-TODO: allow use to specify a filename */
                final String response = "JfrRecorder: starting recording";
                JfrLogger.logInfo("JFR.remote: /start 1");

                JfrAutoSessionManager.instance.startSession();
                JfrLogger.logInfo("JFR.remote: /start start 2");
                respond(exchange, HTTP_OK, response);
            });

            server.createContext("/stop").setHandler(exchange -> {
                if (!validateAndReturnError(exchange)) {
                    return;
                }

                /*
                r.stop();
                r.dump(Files.createTempFile("my-recording", ".jfr"));
                r.close();
                 */
                String response;
                JfrAutoSessionManager.instance.stopSession();
                response = "JfrRecorder: stopped recording";
                response = response + "\n";
                respond(exchange, HTTP_OK, response);
            });

            server.createContext("/exit").setHandler(exchange -> {
                if (!validateAndReturnError(exchange)) {
                    return;
                }
                final String response = "exiting remote control";
                /* HTTP_NOT_IMPLEMENTED is for unimplemented HTTP methods; not unimplemented API calls. */
                respond(exchange, HTTP_OK, response);
                JfrRemoteWebService thiz = this;
                // execute on another thread - otherwise deadlock since we're trying to stop this thread
                new Thread() {
                    @Override
                    public void run() {
                        thiz.stop();
                    }
                }.start();
            });
        }

        server.createContext("/status").setHandler(exchange -> {

            if (!validateAndReturnError(exchange)) {
                return;
            }

            if (debug) {
                JfrLogger.logInfo("JFR.remote: remote = " + exchange.getRemoteAddress() + " (" + exchange.getRemoteAddress().getHostName() + ")");
                for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
                    JfrLogger.logInfo("JFR.remote:  hdr " + entry.getKey() + " = " + entry.getValue());
                }
            }

            /* TODO
            final String created = JfrRecorder.isCreated() ? "created" : "not created";
            final String enabled = JfrRecorder.isEnabled() ? "enabled" : "not enabled";
            final String recording = JfrRecorder.isRecording() ? "recording" : "not recording";
            final String response = "JfrRecorder: status = " + created + ", " + enabled + ", " + recording + "\n";
            */
            final String response = "JFR Remote - status not available\n";
            respond(exchange, HTTP_OK, response);
        });
    }

    private void respond(HttpExchange exchange, int responseCode, String msg) throws IOException {
        final String response = msg + '\n';
        exchange.sendResponseHeaders(responseCode, response.getBytes().length);
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    private void error(HttpExchange exchange, int errorCode, String msg) throws IOException {
        JfrLogger.logWarning("JFR.remote: rejected call from " + exchange.getRemoteAddress() + ": " + msg);
        respond(exchange, errorCode, msg);
    }

    private boolean validateAndReturnError(HttpExchange exchange) throws IOException {
        if (isDebug()) {
            JfrLogger.logInfo("JFR.remote: remote = " + exchange.getRemoteAddress() + " (" + exchange.getRemoteAddress().getHostName() + ")");
            for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
                JfrLogger.logInfo("JFR.remote:  hdr " + entry.getKey() + " = " + entry.getValue());
            }
        }

        if (getValidator() != null && !getValidator().validate(exchange)) {
            error(exchange, HTTP_BAD_REQUEST, "bad request");
            return false;
        }
        return true;
    }

    @SuppressWarnings("unused")
    private Map<String, String> splitQuery(final String query) {
        if (query.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, String> map = new HashMap<>();
        final String[] params = query.split("&");
        for (final String param : params) {
            final int idx = param.indexOf("=");
            final String key = idx > 0 ? param.substring(0, idx) : param;
            final String value = idx > 0 && param.length() > idx + 1 ? param.substring(idx + 1) : null;
            map.put(key, value);
        }
        return Collections.unmodifiableMap(map);
    }

    private void dumpAddresses(MiniServer server) throws SocketException {
        final Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
        while (e.hasMoreElements()) {
            final NetworkInterface networkInterface = e.nextElement();
            if (/*networkInterface.isPointToPoint() ||*/ networkInterface.isLoopback() || !networkInterface.isUp()) {
                continue;
            }
            final Enumeration<InetAddress> ne = networkInterface.getInetAddresses();
             while (ne.hasMoreElements()) {
                final InetAddress addr = ne.nextElement();
                if (addr.getClass() == Inet6Address.class) {
                    continue;
                }
                final String protocol = server.isHttps() ? "https://" : "http://";
                final String ifaceName = " (" + networkInterface.getDisplayName() + ")";
                 JfrLogger.logInfo("JFR.remote: listening on " + protocol + addr.getHostName()  + ":" + server.getServer().getAddress().getPort() + " " + ifaceName);
            }
        }
    }

    public void start() throws IOException {
        JfrLogger.logInfo("JFR.remote: JFR REMOTE start on port " + port);
        server = new ServerFactory().createServer(port, useHttps);
        addContexts(server.getServer());
        /* note that the server thread is setDaemon(true) using JfrSubstitutions */
        server.start();
        dumpAddresses(server);
    }

    public void stop() {
        MiniServer srv = this.server;
        if (srv != null) {
            JfrLogger.logInfo("JFR.remote: JFR REMOTE stopping on port " + port);
            srv.stop();
            server = null;
        }
    }

    public static JfrRemoteWebService buildAndStart(int port, String protocol) throws IOException {
        JfrLogger.logInfo("JFR.remote: JFR REMOTE buildAndStart");
        JfrRemoteWebService remote = new JfrRemoteWebService();
        remote.setPort(port);
        remote.setUseHttps("https".equals(protocol));
        remote.start();
        return remote;
    }

    @SuppressWarnings("unused")
    private void setDebug(boolean d) {
        debug = d;
    }

    private boolean isDebug() {
        return debug;
    }

    private ServerMode getMode() {
        return mode;
    }

    @SuppressWarnings("unused")
    private void setMode(ServerMode mode) {
        this.mode = mode;
    }

    private void setUseHttps(boolean useHttps) {
        this.useHttps = useHttps;
    }

    @SuppressWarnings("unused")
    private int getPort() {
        return this.port;
    }

    private void setPort(int port) {
        this.port = port;
    }

    private Validator getValidator() {
        return validator;
    }

    @SuppressWarnings("unused")
    private void setValidator(Validator validator) {
        this.validator = validator;
    }
}

