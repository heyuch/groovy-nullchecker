package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic

/** Serialization boundary for one-finding-per-line TSV; the finding itself stays data-only. */
@CompileStatic
class NullCheckFindingTsv {

    static String toTsv(NullCheckFinding finding) {
        return [finding.file ?: '', finding.line, finding.category ?: NullCheckFinding.RESOLUTION,
                sanitise(finding.message)].join('\t')
    }

    static NullCheckFinding fromTsv(String row) {
        String[] parts = row.split('\t', -1)
        if (parts.length < 4) {
            throw new IllegalArgumentException("not a finding row: ${row}")
        }
        return new NullCheckFinding(file: parts[0], line: Integer.parseInt(parts[1]),
                category: parts[2], message: parts[3])
    }

    private static String sanitise(String text) {
        if (text == null) {
            return ''
        }
        return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
    }

}
