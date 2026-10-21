package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/**
 * Appends findings to a TSV file so another process or classloader can read them.
 *
 * <p>A file is used instead of a shared object because the checker runs inside Gradle's
 * compiler worker; a static collector would only work if both sides happened to share a
 * classloader. The plugin clears the file before the compile and reads it afterwards.
 */
@CompileStatic
@PackageScope
class FileFindingsSink implements NullCheckSink {

    final File file

    FileFindingsSink(File file) {
        this.file = file
        File parent = file.getParentFile()
        if (parent != null) {
            parent.mkdirs()
        }
    }

    @Override
    void report(NullCheckFinding finding) {
        synchronized (this) {
            file.append(finding.toTsv() + '\n', 'UTF-8')
        }
    }
}
