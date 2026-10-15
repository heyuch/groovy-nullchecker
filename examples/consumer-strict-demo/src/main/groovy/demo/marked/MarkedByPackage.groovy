package demo.marked

import demo.Nullable

/**
 * No annotation on this class: the package's package-info.java makes it
 * non-null-by-default. Compiled with ./gradlew compileGroovy, which injects the checker
 * globally, so this class is checked without carrying @TypeChecked either.
 *
 * Deliberately correct, so the example's own build stays green. The failing counterpart
 * lives in samples/broken/package-level/, which the report task targets explicitly.
 */
class MarkedByPackage {

    /** Not reported: the package default applies, and this honours it. */
    String returnsValue() {
        return 'ok'
    }

    /** Not reported: an explicit @Nullable overrides the package default. */
    @Nullable
    String allowedToReturnNull() {
        return null
    }
}
