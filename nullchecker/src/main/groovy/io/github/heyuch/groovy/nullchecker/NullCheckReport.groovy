package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic

/**
 * One rendered report: stable metadata plus already-formatted finding lines.
 *
 * <p>Both the standalone CLI and the compile-mode findings CLI build this object, so a file,
 * stdout and stderr can carry exactly the same text.
 */
@CompileStatic
class NullCheckReport {

    String groovyVersion = ''

    String extension = ''

    boolean strict

    int files

    String coverage = ''

    List<String> failOn = []

    List<String> findings = []

    String raw = ''

    String note = ''

}
