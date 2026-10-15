package io.github.heyuch.groovy4.nullchecker.harness

import groovy.transform.CompileStatic

/**
 * Command-line front end for the null checker.
 *
 * <p>Unlike the Groovy scripts under {@code tools/}, this is a compiled class, so it
 * lives inside the published jar and can be used as a {@code mainClass} by any build
 * tool. Consumers who just want a report over an existing source tree can therefore
 * run it straight from the dependency, without checking this project out.
 *
 * <pre>
 * java -cp &lt;checker jar + groovy&gt; io.github.heyuch.groovy4.nullchecker.harness.NullCheckCli \
 *      --strict src/main/groovy
 * </pre>
 *
 * <p>Exit codes: 0 = clean, 1 = findings, 2 = misuse.
 */
@CompileStatic
class NullCheckCli {

    static final String DEFAULT_EXTENSION = 'io.github.heyuch.groovy4.nullchecker.NullChecker'

    static final String USAGE = '''usage: NullCheckCli [options] <files or dirs>
  --strict              enable flow-sensitive (strict) analysis
  --support <dir>       directory of shared stub sources
  --extension <fqcn>    type-checking extension to run
  --report <file>       write the raw compiler output here
  --quiet               only print findings
  --no-canary           skip the pre-flight self-check (NOT recommended)
  --help                show this message

Before reporting anything, the tool checks a built-in, deliberately broken sample
(including a @CompileStatic class) and fails with exit 2 if that sample is not
reported. A silently broken checker would otherwise print "0 findings", which is
indistinguishable from clean code.

exit codes: 0 = no findings, 1 = findings, 2 = the checker is not working / misuse'''

    /**
     * Runs the tool.
     *
     * @param args command line
     */
    static void main(String[] args) {
        int status = run(args as List<String>, System.out, System.err)
        System.exit(status)
    }

    /**
     * Separated from {@link #main} so tests can invoke it without exiting the JVM.
     *
     * @param argv   command line
     * @param out    standard output
     * @param err    error output
     * @return the exit code main should use
     */
    static int run(List<String> argv, PrintStream out, PrintStream err) {
        boolean strict = false
        boolean quiet = false
        boolean canary = true
        String support = null
        String report = null
        String extension = System.getProperty('nullchecker.extension') ?: DEFAULT_EXTENSION
        List<String> paths = []

        for (int i = 0; i < argv.size(); i++) {
            String arg = argv[i]
            switch (arg) {
                case '--help':
                case '-h':
                    out.println(USAGE)
                    return 0
                case '--strict':
                    strict = true
                    break
                case '--quiet':
                case '-q':
                    quiet = true
                    break
                case '--no-canary':
                    canary = false
                    break
                case '--support':
                    support = value(argv, ++i, arg, err)
                    if (support == null) return 2
                    break
                case '--extension':
                    extension = value(argv, ++i, arg, err)
                    if (extension == null) return 2
                    break
                case '--report':
                    report = value(argv, ++i, arg, err)
                    if (report == null) return 2
                    break
                default:
                    if (arg.startsWith('-')) {
                        err.println("nullcheck: unknown option: ${arg}")
                        err.println(USAGE)
                        return 2
                    }
                    paths << arg
            }
        }

        List<File> files = []
        for (String path : paths) {
            File input = new File(path)
            if (input.directory) {
                input.eachFileRecurse { File f ->
                    if (NullCheckHarness.isCheckable(f)) {
                        files << f
                    }
                }
            } else if (NullCheckHarness.isCheckable(input)) {
                files << input
            } else {
                err.println("nullcheck: no such file or directory: ${input}")
                return 2
            }
        }

        // Shared stubs are compiled in the same unit as the fixture, so a fixture can
        // never be checked against a stale copy of a helper class.
        if (support) {
            File supportDir = new File(support)
            if (!supportDir.directory) {
                err.println("nullcheck: support dir not found: ${supportDir}")
                return 2
            }
            List<File> supportFiles = []
            supportDir.eachFileRecurse { File f ->
                if (NullCheckHarness.isCheckable(f)) {
                    supportFiles << f
                }
            }
            files = supportFiles + files
        }

        files = files.unique().sort { File f -> f.absolutePath }

        if (!files) {
            err.println('nullcheck: no .groovy inputs')
            err.println(USAGE)
            return 2
        }

        if (!quiet) {
            out.println "groovy     = ${GroovySystem.version}"
            out.println "extension  = ${extension}"
            out.println "strict     = ${strict}"
            out.println "files      = ${files.size()}"
        }

        // Self-check first. Reporting a result before this passes would mean printing a
        // number nobody can interpret: "0 findings" from a checker that cannot see
        // violations is not a clean bill of health.
        if (canary) {
            NullCheckHarness.CanaryResult probe = NullCheckHarness.runCanary(extension)
            if (!probe.healthy) {
                err.println("nullcheck: self-check FAILED -- ${probe.detail}")
                err.println('nullcheck: refusing to report a result that cannot be trusted')
                err.println('nullcheck: (use --no-canary to skip this, at your own risk)')
                return 2
            }
            if (!quiet) {
                out.println "canary     = ok (${probe.findings.size()} findings on the built-in sample, " +
                        'including its @CompileStatic class)'
            }
        } else if (!quiet) {
            out.println 'canary     = SKIPPED (--no-canary): results below are unverified'
        }

        NullCheckHarness.Result result
        try {
            result = NullCheckHarness.check(files, extension, strict)
        } catch (IllegalStateException e) {
            err.println("nullcheck: ${e.message ?: e.toString()}")
            return 2
        }

        result.findings.each { NullCheckHarness.Finding f -> out.println "FINDING ${f}" }
        if (!quiet) {
            out.println "findings   = ${result.findings.size()}"
            // Coverage is what makes a zero meaningful: it shows how much was looked at.
            out.println "coverage   = ${result.coverage}"
            if (result.collectedOnly) {
                out.println 'note       = compiler returned normally but had collected errors'
            }
        }

        if (report) {
            File target = new File(report)
            target.parentFile?.mkdirs()
            target.setText("""groovy=${GroovySystem.version}
extension=${extension}
strict=${strict}
files=${files.size()}
findings=${result.findings.size()}
coverage=${result.coverage}

${result.raw}
""", 'UTF-8')
            if (!quiet) {
                out.println "report     = ${target.absolutePath}"
            }
        }

        result.findings ? 1 : 0
    }

    private static String value(List<String> argv, int index, String option, PrintStream err) {
        if (index >= argv.size()) {
            err.println("nullcheck: option ${option} needs a value")
            return null
        }
        argv[index]
    }
}
