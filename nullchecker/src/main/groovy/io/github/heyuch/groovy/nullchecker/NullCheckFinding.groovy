package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/**
 * One finding in a form that can be collected, written and read back.
 *
 * <p>The checker normally reports through {@code addStaticTypeError}, which makes a finding a
 * compiler error. A build integration that wants to collect findings instead needs the same
 * information without touching the compiler's error collector: source, line, category and
 * message. Categories match the CLI's labels.
 */
@CompileStatic
@PackageScope
class NullCheckFinding {

    static final String NULL = 'null'

    static final String INITIALIZATION = 'initialization'

    static final String RESOLUTION = 'resolution'

    /** Selects every category when used in a fail-on list. */
    static final String ALL = 'all'

    /** Message fragments that identify a nullness finding; kept in step with the CLI. */
    static final List<String> NULL_FRAGMENTS = [
            'Potential null dereference:',
            'Cannot dereference null',
            'Cannot return null from @NonNull method',
            'Cannot return @Nullable value from @NonNull method',
            'Cannot assign null to @NonNull',
            'Cannot assign null to @MonotonicNonNull',
            'Cannot pass null to @NonNull parameter',
            'Cannot pass @Nullable value to @NonNull parameter',
    ] as List<String>

    String file = ''

    int line

    String category = RESOLUTION

    String message = ''

    static NullCheckFinding of(String file, int line, String message) {
        String text = message == null ? '' : message
        return new NullCheckFinding(file: file, line: line, category: classify(text), message: text)
    }

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

    String toTsv() {
        return [file ?: '', line, category ?: RESOLUTION, sanitise(message)].join('\t')
    }

    static NullCheckFinding fromTsv(String row) {
        String[] parts = row.split('\t', -1)
        if (parts.length < 4) {
            throw new IllegalArgumentException("not a finding row: ${row}")
        }
        return new NullCheckFinding(file: parts[0], line: Integer.parseInt(parts[1]),
                category: parts[2], message: parts[3])
    }

    /** Tabs and newlines would break the one-finding-per-line format. */
    private static String sanitise(String text) {
        if (text == null) {
            return ''
        }
        return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
    }

}
