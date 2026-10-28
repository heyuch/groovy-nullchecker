package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic

/**
 * One report: metadata plus structured findings. Keeping findings structured lets the writer
 * group them by file and emit the IDE-compatible {@code Loc=.(name:line)} form.
 */
@CompileStatic
class NullCheckReport {

    static class Finding {

        String path = ''
        int line
        String category = ''
        String message = ''

    }

    String groovyVersion = ''

    String extension = ''

    boolean strict

    int files

    String coverage = ''

    List<String> failOn = []

    List<Finding> findings = []

    String raw = ''

    String note = ''

}
