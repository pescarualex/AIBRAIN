package com.aibrain.desktop;

/**
 * Entry point for a classpath launch (the shaded fat jar and the jpackage bundle).
 *
 * <p>When a subclass of {@link javafx.application.Application} is launched as the main
 * class from the classpath (not the module path), the JVM aborts with "JavaFX runtime
 * components are missing". Routing {@code main} through this non-Application class avoids
 * that, so the fat jar and the native package start without the JavaFX module path.
 * {@code mvn javafx:run} keeps using {@link App} directly.
 */
public final class Launcher {

    public static void main(String[] args) {
        App.main(args);
    }

    private Launcher() {}
}
