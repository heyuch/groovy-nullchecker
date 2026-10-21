package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic

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

    static void main(String[] args) {
        String findingsPath = null
        String reportPath = null
        List<String> failOn = [NullCheckFinding.NULL, NullCheckFinding.INITIALIZATION]
        for (int i = 0; i < args.length; i++) {
            String argument = args[i]
            if (argument == '--findings' && i + 1 < args.length) {
                findingsPath = args[++i]
            } else if (argument == '--fail-on' && i + 1 < args.length) {
                failOn = args[++i].split(',').collect { String part -> part.trim().toLowerCase() }
            } else if (argument == '--report' && i + 1 < args.length) {
                reportPath = args[++i]
            } else {
                System.err.println("nullcheck: unknown argument ${argument}")
                System.exit(2)
            }
        }
        if (findingsPath == null) {
            System.err.println('nullcheck: --findings is required')
            System.exit(2)
        }

        File findingsFile = new File(findingsPath)
        if (!findingsFile.isFile()) {
            System.err.println("nullcheck: findings file is missing: ${findingsPath}. " +
                    'This is NOT a clean result: the checker did not run.')
            System.exit(2)
        }

        File errorsFile = new File(findingsPath + '.errors')
        if (errorsFile.isFile() && errorsFile.length() > 0) {
            System.err.println('nullcheck: the checker failed internally, so no result can be trusted:')
            System.err.println(errorsFile.getText('UTF-8'))
            System.exit(2)
        }
        File coverageFile = new File(findingsPath + '.coverage')
        if (!coverageFile.isFile() || coverageFile.length() == 0) {
            System.err.println('nullcheck: the checker visited no classes; this is NOT a clean result.')
            System.exit(2)
        }
        List<String> visited = coverageFile.readLines('UTF-8')
        println "nullcheck: coverage classes=${visited.size()}"

        List<NullCheckFinding> findings = new ArrayList<NullCheckFinding>()
        for (String row : findingsFile.readLines('UTF-8')) {
            if (!row.trim().isEmpty()) {
                findings.add(NullCheckFinding.fromTsv(row))
            }
        }

        for (NullCheckFinding finding : findings) {
            println "${finding.file}:${finding.line}: [${finding.category}] ${finding.message}"
        }
        println "nullcheck: findings=${findings.size()}"

        if (reportPath != null) {
            File report = new File(reportPath)
            File parent = report.getParentFile()
            if (parent != null) {
                parent.mkdirs()
            }
            StringBuilder text = new StringBuilder()
            for (NullCheckFinding finding : findings) {
                text.append("${finding.file}:${finding.line}: [${finding.category}] ${finding.message}\n")
            }
            report.setText(text.toString(), 'UTF-8')
        }

        boolean failing = findings.any { NullCheckFinding finding ->
            failOn.contains(NullCheckFinding.ALL) || failOn.contains(finding.category)
        }
        System.exit(failing ? 1 : 0)
    }
}
