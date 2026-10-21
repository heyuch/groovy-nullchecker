package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.jspecify.annotations.Nullable

/**
 * The sink installed for the current thread, if any.
 *
 * <p>A thread-local rather than a global: one Gradle worker can compile several tasks, and a
 * sink must never leak into the CLI path, which still reports through the compiler.
 */
@CompileStatic
@PackageScope
class NullCheckSinks {

    private static final ThreadLocal<NullCheckSink> CURRENT = new ThreadLocal<NullCheckSink>()

    private static final String FINDINGS_PROPERTY = 'groovy.nullchecker.findingsFile'

    static void install(NullCheckSink sink) {
        CURRENT.set(sink)
    }

    static void clear() {
        CURRENT.remove()
    }

    static @Nullable NullCheckSink currentSink() {
        NullCheckSink sink = CURRENT.get()
        if (sink != null) {
            return sink
        }
        // The Gradle integration sets this property in the compiler worker. Reading it here,
        // rather than relying on the customizer to install a sink, matters because the
        // customizer and the extension can be loaded by different classloaders: a static
        // installed by one copy is invisible to the other, while a system property is not.
        String findingsFile = System.getProperty(FINDINGS_PROPERTY)
        if (findingsFile == null || findingsFile.isEmpty()) {
            return null
        }
        File target = new File(findingsFile)
        if (sink instanceof FileFindingsSink
                && ((FileFindingsSink) sink).file.absolutePath == target.absolutePath) {
            return sink
        }
        // A worker can be reused for another task with a different findings file; the cached
        // sink must not keep writing to the previous one.
        NullCheckSink fileSink = new FileFindingsSink(target)
        CURRENT.set(fileSink)
        return fileSink
    }

}
