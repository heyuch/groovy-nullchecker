package demo.marked

import groovy.transform.TypeChecked

/**
 * Reported because the PACKAGE is non-null-by-default: nothing on this class says so.
 *
 * Uses @TypeChecked so the file is self-contained when passed to the report task; the
 * package default itself comes from src/main/java/demo/marked/package-info.java, which
 * is why that file has to be part of the compilation (walking its directory includes it).
 */
@TypeChecked(extensions = 'io.github.heyuch.groovy.nullchecker.NullChecker')
class FailsByPackageDefault {
    String mustNotReturnNull() {
        return null
    }
}
