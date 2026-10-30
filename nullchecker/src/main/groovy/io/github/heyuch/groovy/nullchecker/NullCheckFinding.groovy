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

    static final String NULL = NullCheckCategories.NULL

    static final String INITIALIZATION = NullCheckCategories.INITIALIZATION

    static final String RESOLUTION = NullCheckCategories.RESOLUTION

    /** Selects every category when used in a fail-on list. */
    static final String ALL = 'all'

    String file = ''

    int line

    String category = RESOLUTION

    String message = ''

    /**
     * Compiler-message fallback: use only when the producer cannot carry a category, such as
     * messages extracted from Groovy's own error collector.
     */
    static NullCheckFinding of(String file, int line, String message) {
        String text = message == null ? '' : message
        return of(file, line, classify(text), text)
    }

    /** Explicit category from the finding's creation site; never reclassifies the message. */
    static NullCheckFinding of(String file, int line, String category, String message) {
        String text = message == null ? '' : message
        String label = category ?: RESOLUTION
        return new NullCheckFinding(file: file, line: line, category: label, message: text)
    }

    static String classify(String message) {
        return NullCheckCategories.classify(message)
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
