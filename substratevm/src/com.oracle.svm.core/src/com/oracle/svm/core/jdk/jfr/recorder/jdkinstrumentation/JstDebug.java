package com.oracle.svm.core.jdk.jfr.recorder.jdkinstrumentation;

import jdk.jfr.EventType;
import jdk.jfr.internal.MetadataRepository;

import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

public class JstDebug {


    /* use Graal instead
    static PlatformEventType getPlatformEventType0(Class<? extends Event> clazz) {
        EventType eventType = EventType.getEventType(clazz);
        PlatformEventType pet = null;
        try {
            // this hoop is to allow access to a package private method.
            Method gpe = EventType.class.getDeclaredMethod("getPlatformEventType");
            gpe.setAccessible(true);
            pet = (PlatformEventType) gpe.invoke(eventType);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return pet;
    }*/

    static void dumpEnv() {
        for (EventType eventType : MetadataRepository.getInstance().getRegisteredEventTypes()) {
            System.err.format("eventtype: %s enabled=%s\n", eventType.getName(), Boolean.toString(eventType.isEnabled()));
        }
        EventType eventType = EventType.getEventType(jdk.jfr.events.FileReadEvent.class);
        ClassDumper.dumpClass(System.err, eventType.getClass());
    }

    static class ClassDumper {

        public static final int ACC_PUBLIC = 0x0001; // class, inner, field, method
        public static final int ACC_PRIVATE = 0x0002; //        inner, field, method
        public static final int ACC_PROTECTED = 0x0004; //        inner, field, method
        public static final int ACC_STATIC = 0x0008; //        inner, field, method
        public static final int ACC_FINAL = 0x0010; // class, inner, field, method
        public static final int ACC_SUPER = 0x0020; // class
        public static final int ACC_SYNCHRONIZED = 0x0020; //                      method
        public static final int ACC_VOLATILE = 0x0040; //               field
        public static final int ACC_BRIDGE = 0x0040; //                      method
        public static final int ACC_TRANSIENT = 0x0080; //               field
        public static final int ACC_VARARGS = 0x0080; //                      method
        public static final int ACC_NATIVE = 0x0100; //                      method
        public static final int ACC_INTERFACE = 0x0200; // class, inner
        public static final int ACC_ABSTRACT = 0x0400; // class, inner,        method
        public static final int ACC_STRICT = 0x0800; //                      method
        public static final int ACC_SYNTHETIC = 0x1000; // class, inner, field, method
        public static final int ACC_ANNOTATION = 0x2000; // class, inner
        public static final int ACC_ENUM = 0x4000; // class, inner, field
        public static final int ACC_MANDATED = 0x8000; // class, inner, field, method

        private static String modifierToString(int n) {
            StringBuilder b = new StringBuilder();
            if ((n & ACC_PUBLIC) != 0) {
                b.append("public ");
            }
            if ((n & ACC_PRIVATE) != 0) {
                b.append("private ");
            }
            if ((n & ACC_PROTECTED) != 0) {
                b.append("protected ");
            }
            if ((n & ACC_STATIC) != 0) {
                b.append("static ");
            }
            if ((n & ACC_FINAL) != 0) {
                b.append("final ");
            }
            if ((n & ACC_SUPER) != 0) {
                b.append("super ");
            }
            if ((n & ACC_SYNCHRONIZED) != 0) {
                b.append("synchronized ");
            }
            if ((n & ACC_VOLATILE) != 0) {
                b.append("volatile ");
            }
            if ((n & ACC_BRIDGE) != 0) {
                b.append("bridge ");
            }
            if ((n & ACC_TRANSIENT) != 0) {
                b.append("transient ");
            }
            if ((n & ACC_VARARGS) != 0) {
                b.append("varargs ");
            }
            if ((n & ACC_NATIVE) != 0) {
                b.append("native ");
            }
            if ((n & ACC_INTERFACE) != 0) {
                b.append("interface ");
            }
            if ((n & ACC_ABSTRACT) != 0) {
                b.append("abstract ");
            }
            if ((n & ACC_STRICT) != 0) {
                b.append("strict ");
            }
            if ((n & ACC_SYNTHETIC) != 0) {
                b.append("synthetic ");
            }
            if ((n & ACC_ANNOTATION) != 0) {
                b.append("annotation ");
            }
            if ((n & ACC_ENUM) != 0) {
                b.append("enum ");
            }
            if ((n & ACC_MANDATED) != 0) {
                b.append("mandated ");
            }
            return b.toString();
        }

        public static void iwashere() {
            System.out.println("from hello world i was here");
        }

        public static void dumpClass(PrintStream out, String className) {
            try {
                Class<?> clazz = Class.forName(className);
                dumpClass(out, clazz);
            } catch (ClassNotFoundException e) {
                out.format("could not find class %s\n", className);
            }
        }

        public static void dumpClass(PrintStream out, Class<?> clazz) {
            out.format("%sclass %s extends %s", modifierToString(clazz.getModifiers()), clazz.getName(), clazz.getSuperclass().getName());
            if (clazz.getInterfaces().length > 0) {
                out.format(" implements");
                for (Class<?> c : clazz.getInterfaces()) {
                    out.format("%s ", c.getName());
                }
            }
            out.format(" {\n");
            for (Field f : clazz.getDeclaredFields()) {
                out.format("  %s%s %s;\n", modifierToString(f.getModifiers()), f.getType().getName(), f.getName());
            }
            for (Constructor<?> m : clazz.getDeclaredConstructors()) {
                out.format("  %s%s(", modifierToString(m.getModifiers()), m.getName());
                for (Parameter p : m.getParameters()) {
                    out.format("%s%s %s,", modifierToString(p.getModifiers()), p.getType().getName(), p.getName());
                }
                out.format(");\n");
            }
            for (Method m : clazz.getDeclaredMethods()) {
                out.format("  %s%s %s(", modifierToString(m.getModifiers()), m.getReturnType().getName(), m.getName());
                for (Parameter p : m.getParameters()) {
                    out.format("%s%s %s,", modifierToString(p.getModifiers()), p.getType().getName(), p.getName());
                }
                out.format(");\n");
            }
            out.format("};\n");
        }
    }
}
