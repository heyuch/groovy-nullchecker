package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import java.nio.file.Files
import java.nio.file.Path

/** The one formatter for report text; destinations only decide where the text goes. */
@CompileStatic
class NullCheckReportWriter {

    static String findingLine(String path, int line, String category, String message) {
        return "${path}:${line}: [${category}] ${message}".toString()
    }

    static String render(NullCheckReport report) {
        StringBuilder text = new StringBuilder()
        text.with {
            append("groovy=${report.groovyVersion}\n")
            append("extension=${report.extension}\n")
            append("strict=${report.strict}\n")
            append("files=${report.files}\n")
            append("findings=${report.findings.size()}\n")
            append("coverage=${report.coverage}\n")
            append("fail-on=${report.failOn.join(',')}\n")
            if (report.note) {
                append("note=${report.note}\n")
            }
            if (!report.findings.isEmpty()) {
                append('\n')
                for (String finding : report.findings) {
                    append(finding).append('\n')
                }
            }
            if (report.raw) {
                append('\n').append(report.raw)
                if (!report.raw.endsWith('\n')) {
                    append('\n')
                }
            }
        }
        return text.toString()
    }

    static void write(Writer out, NullCheckReport report) {
        out.write(render(report))
    }

    static void write(Path target, NullCheckReport report) {
        Path parent = target.getParent()
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.writeString(target, render(report))
    }

}
