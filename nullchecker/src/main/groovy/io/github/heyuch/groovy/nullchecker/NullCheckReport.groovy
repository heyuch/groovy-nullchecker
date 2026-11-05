package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic

/**
 * One report: metadata plus structured findings. Keeping findings structured lets the writer
 * group them by file and emit the IDE-compatible {@code Loc=.(name:line)} form.
 */
@CompileStatic
class NullCheckReport {

    String groovyVersion = ''

    String extension = ''

    boolean strict

    int files

    String coverage = ''

    List<String> failOn = []

    List<NullCheckFinding> findings = []

    String raw = ''

    String note = ''

}
