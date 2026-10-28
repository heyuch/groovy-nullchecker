package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one formatter for report text; destinations only decide where the text goes.
 *
 * <p>Findings use CodeNarc's IDE text convention:
 * <pre>
 * File: src/Example.groovy
 *     Loc=.(Example.groovy:12) [null] Potential null dereference: ...
 * </pre>
 * IntelliJ can turn that location into a source hyperlink.
 */
@CompileStatic
class NullCheckReportWriter {

    static NullCheckReport.Finding finding(String path, int line, String category, String message) {
        return new NullCheckReport.Finding(path: path, line: line, category: category, message: message)
    }

    static String renderFindings(List<NullCheckReport.Finding> findings) {
        StringBuilder text = new StringBuilder()
        Map<String, List<NullCheckReport.Finding>> byPath = new LinkedHashMap<>()
        for (NullCheckReport.Finding finding : findings) {
            String path = finding.path ?: '(unknown)'
            List<NullCheckReport.Finding> group = byPath.get(path)
            if (group == null) {
                group = new ArrayList<NullCheckReport.Finding>()
                byPath.put(path, group)
            }
            group.add(finding)
        }
        for (Map.Entry<String, List<NullCheckReport.Finding>> entry : byPath.entrySet()) {
            text.append("File: ${entry.key}\n")
            for (NullCheckReport.Finding finding : entry.value) {
                text.append("    Loc=.(${basename(entry.key)}:${finding.line}) " +
                        "[${finding.category}] ${finding.message}\n")
            }
            text.append('\n')
        }
        return text.toString()
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
                append(renderFindings(report.findings))
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

    private static String basename(String path) {
        int slash = path.lastIndexOf('/')
        int backslash = path.lastIndexOf('\\')
        int index = Math.max(slash, backslash)
        return index < 0 ? path : path.substring(index + 1)
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
