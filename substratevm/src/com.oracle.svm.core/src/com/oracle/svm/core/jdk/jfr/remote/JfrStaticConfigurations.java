package com.oracle.svm.core.jdk.jfr.remote;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.jdk.jfr.JfrAvailability;
import jdk.jfr.Configuration;

import java.io.IOException;
import java.text.ParseException;

import static jdk.jfr.Configuration.getConfiguration;

public final class JfrStaticConfigurations {

    public static Configuration DEFAULT;
    public static Configuration PROFILE;

    /* initialized at build time */
    /* See JfrFeature: RuntimeClassInitialization.initializeAtBuildTime(JfrStaticConfigurations.class); */
    static {
        try {
            DEFAULT = getConfiguration("default");
            PROFILE = getConfiguration("profile");
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }
}

@TargetClass(className = "jdk.jfr.Configuration", onlyWith = JfrAvailability.WithJfr.class)
final class Target_jdk_jfr_Configuration {
    @Substitute
    static Configuration getConfiguration(String name) throws IOException, ParseException {
        if ("default".equals(name)) {
            return JfrStaticConfigurations.DEFAULT;
        } else if ("profile".equals(name)) {
            return JfrStaticConfigurations.PROFILE;
        } else {
            throw new IOException("unknown JFR configuration " + name);
        }
    }
}