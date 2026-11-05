package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import org.jspecify.annotations.Nullable

/**
 * Reads the findings file written by the compile-time collection mode.
 *
 * <p>Kept separate from {@code NullCheckCli}: that CLI owns a compilation of its own, while this
 * one only reports what the project's {@code compileGroovy} already collected. Exit codes match
 * the CLI so the Gradle plugin can keep one policy:
 * <ul>
 *   <li>0 -- findings exist only outside the failing categories, or there are none;</li>
 *   <li>1 -- at least one finding is in a failing category;</li>
 *   <li>2 -- the file is missing or unreadable, so the checker did not run.</li>
 * </ul>
 */
@CompileStatic
class NullCheckFindingsCli {

    static final String USAGE = '''usage: NullCheckFindingsCli --findings <file> [options]
  --findings <file>   TSV written by compile mode (required)
  --fail-on <list>    comma-separated categories; default: null,initialization
  --report <file>     write a report file
  --report-stdout     write the report to stdout
  --report-stderr     write the report to stderr
  --help              show this message
'''

    static void main(String[] args) {
        System.exit(run(args as List<String>, System.out, System.err))
    }

    static int run(List<String> argv, PrintStream out, PrintStream err) {
        String findingsPath = null
        String reportPath = null
        boolean reportStdout = false
        boolean reportStderr = false
        List<String> failOn = [NullCheckFinding.NULL, NullCheckFinding.INITIALIZATION]
        for (int i = 0; i < argv.size(); i++) {
            String argument = argv[i]
            if (argument == '--help' || argument == '-h') {
                out.println(USAGE)
                return 0
            }
            if (argument == '--findings') {
                String value = value(argv, ++i, argument, err)
                if (value == null) {
                    return 2
                }
                findingsPath = value
            } else if (argument == '--fail-on') {
                String value = value(argv, ++i, argument, err)
                if (value == null) {
                    return 2
                }
                failOn = value.split(',').collect { String part -> part.trim().toLowerCase(Locale.ROOT) }
            } else if (argument == '--report') {
                String value = value(argv, ++i, argument, err)
                if (value == null) {
                    return 2
                }
                reportPath = value
            } else if (argument == '--report-stdout') {
                reportStdout = true
            } else if (argument == '--report-stderr') {
                reportStderr = true
            } else {
                err.println("nullcheck: unknown argument ${argument}")
                err.println(USAGE)
                return 2
            }
        }
        if (findingsPath == null) {
            err.println('nullcheck: --findings is required')
            err.println(USAGE)
            return 2
        }

        File findingsFile = new File(findingsPath)
        if (!findingsFile.isFile()) {
            err.println("nullcheck: findings file is missing: ${findingsPath}. " +
                    'This is NOT a clean result: the checker did not run.')
            return 2
        }

        File errorsFile = new File(findingsPath + '.errors')
        if (errorsFile.isFile() && errorsFile.length() > 0) {
            err.println('nullcheck: the checker failed internally, so no result can be trusted:')
            err.println(errorsFile.getText('UTF-8'))
            return 2
        }
        File coverageFile = new File(findingsPath + '.coverage')
        if (!coverageFile.isFile() || coverageFile.length() == 0) {
            err.println('nullcheck: the checker visited no classes; this is NOT a clean result.')
            return 2
        }
        List<String> visited = coverageFile.readLines('UTF-8')
        out.println "nullcheck: coverage classes=${visited.size()}"

        List<NullCheckFinding> findings = new ArrayList<NullCheckFinding>()
        for (String row : findingsFile.readLines('UTF-8')) {
            if (!row.trim().isEmpty()) {
                findings.add(NullCheckFindingTsv.fromTsv(row))
            }
        }

        List<NullCheckFinding> reportFindings = findings.collect { NullCheckFinding finding ->
            NullCheckReportWriter.finding(finding.file, finding.line,
                    finding.category, finding.message)
        }
        if (!reportFindings.isEmpty()) {
            out.print(NullCheckReportWriter.renderFindings(reportFindings))
        }
        out.println "nullcheck: findings=${findings.size()}"

        if (reportPath != null || reportStdout || reportStderr) {
            NullCheckReport report = new NullCheckReport(
                    coverage: "classes=${visited.size()}",
                    failOn: failOn,
                    findings: reportFindings)
            if (reportPath != null) {
                NullCheckReportWriter.write(new File(reportPath).toPath(), report)
            }
            if (reportStdout) {
                out.print(NullCheckReportWriter.render(report))
            }
            if (reportStderr) {
                err.print(NullCheckReportWriter.render(report))
            }
        }

        boolean failing = findings.any { NullCheckFinding finding ->
            failOn.contains(NullCheckFinding.ALL) || failOn.contains(finding.category)
        }
        return failing ? 1 : 0
    }

    private static @Nullable String value(List<String> argv, int index, String option, PrintStream err) {
        if (index >= argv.size()) {
            err.println("nullcheck: option ${option} needs a value")
            return null
        }
        return argv[index]
    }

}
