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

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import sun.security.tools.keytool.CertAndKeyGen;
import sun.security.x509.X500Name;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

class MiniHttpsServerImpl implements MiniServer {

    private HttpsServer server = null;
    private ExecutorService threadPool = null;

    MiniHttpsServerImpl(int port) throws IOException {
        createServer(port);
        //addContexts(server);
    }

    /***
    // to create the keystore:
    // keytool -genkey -alias alias -keypass jstjst -keystore jst.keystore -storepass jstjst
    private KeyStore loadKeystore(String filename, String password) throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException {
        // initialise the keystore from a file
        char[] pwd = password.toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        FileInputStream fis = new FileInputStream(filename);
        ks.load(fis, pwd);
        return ks;
    }
     ****/

    private KeyStore createSelfSignedKeyStore() {
        try {
            CertAndKeyGen keyGen = new CertAndKeyGen("RSA","SHA1WithRSA",null);
            keyGen.generate(1024);
            PrivateKey topPrivateKey = keyGen.getPrivateKey();
            // Generate the self signed certificate
            X509Certificate cert = keyGen.getSelfCertificate(new X500Name("CN=ROOT"), (long)365*24*3600);

            KeyStore keyStore = KeyStore.getInstance("jks");
            keyStore.load(null,null);
            X509Certificate[] chain = new X509Certificate[1];
            chain[0] = cert;
            keyStore.setKeyEntry("jst", topPrivateKey, "jstjst".toCharArray(), chain);
            return keyStore;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    private void createServer(int listenPort) throws IOException{

        try {
            server = HttpsServer.create(new InetSocketAddress(listenPort), 0);
            ThreadFactory tf = new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread();
                    t.setDaemon(true);
                    return t;
                }
            };
            threadPool = Executors.newFixedThreadPool(5, tf);
            this.server.setExecutor(threadPool);

            SSLContext sslContext = SSLContext.getInstance("TLS");

            String password = "jstjst";
            //KeyStore ks = loadKeystore("jst.keystore", password);
            KeyStore ks = createSelfSignedKeyStore();

            // setup the key manager factory
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, password.toCharArray());

            // setup the trust manager factory
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ks);

            // setup the HTTPS context and parameters
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                public void configure(HttpsParameters params) {
                    try {
                        // initialise the SSL context
                        SSLContext c = SSLContext.getDefault();
                        SSLEngine engine = c.createSSLEngine();
                        params.setNeedClientAuth(false);
                        params.setCipherSuites(engine.getEnabledCipherSuites());
                        params.setProtocols(engine.getEnabledProtocols());

                        // get the default parameters
                        SSLParameters defaultSSLParameters = c.getDefaultSSLParameters();
                        params.setSSLParameters(defaultSSLParameters);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
        } catch ( Exception exception )  {
            exception.printStackTrace();
            throw new IOException(exception.getMessage());
        }

        server.setExecutor(null); // creates a default executor
    }

    /**
    void addContexts(HttpServer server) {
        server.createContext("/", httpExchange -> {
            String response = "This is the response";
            System.err.println("request from " + httpExchange.getRemoteAddress().getHostName());
            httpExchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = httpExchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        });
    }****/

    public void start() { server.start(); }

    public void stop() {
        server.stop(1);
        threadPool.shutdownNow();
        try {
            threadPool.awaitTermination(2, TimeUnit.MINUTES);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public HttpServer getServer() {
        return server;
    }

    @Override
    public boolean isHttps() { return true; }

    public static void main(String[] args) throws IOException {
        new MiniHttpsServerImpl(8443).start();
    }
}



