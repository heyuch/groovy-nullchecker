package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic

/**
 * Fallback category mapping for messages that cannot be structured at their creation site,
 * primarily Groovy compiler messages. Checker-created findings pass an explicit category.
 */
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
            'Cannot unbox @Nullable value to primitive',
            'Cannot assign @Nullable value to @NonNull variable',
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
