package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic

/** The one owner of the finding-category mapping shared by the core, CLI and harness. */
@CompileStatic
class NullCheckCategories {

    static final String NULL = 'null'

    static final String INITIALIZATION = 'initialization'

    static final String RESOLUTION = 'resolution'

    private static final List<String> NULL_FRAGMENTS = [
            'Potential null dereference:',
            'Cannot dereference null',
            'Cannot return null from @NonNull method',
            'Cannot return @Nullable value from @NonNull method',
            'Cannot assign null to @NonNull',
            'Cannot assign null to @MonotonicNonNull',
            'Cannot pass null to @NonNull parameter',
            'Cannot pass @Nullable value to @NonNull parameter',
    ] as List<String>

    static String classify(String message) {
        if (message == null) {
            return RESOLUTION
        }
        for (String fragment : NULL_FRAGMENTS) {
            if (message.contains(fragment)) {
                return NULL
            }
        }
        if (message.contains('@NonNull field')) {
            return INITIALIZATION
        }
        return RESOLUTION
    }

}
