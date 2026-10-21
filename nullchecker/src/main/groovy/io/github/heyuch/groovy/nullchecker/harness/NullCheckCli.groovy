package io.github.heyuch.groovy.nullchecker.harness

import groovy.transform.CompileStatic
import io.github.heyuch.groovy.nullchecker.NullChecker

/**
 * Command-line front end for the null checker.
 *
 * <p>Unlike the Groovy scripts under {@code tools/}, this is a compiled class, so it
 * lives inside the published jar and can be used as a {@code mainClass} by any build
 * tool. Consumers who just want a report over an existing source tree can therefore
 * run it straight from the dependency, without checking this project out.
 *
 * <pre>
 * java -cp &lt;checker jar + groovy&gt; io.github.heyuch.groovy.nullchecker.harness.NullCheckCli \
 *      --strict src/main/groovy
 * </pre>
 *
 * <p>Exit codes: 0 = clean, 1 = findings, 2 = misuse.
 */
@CompileStatic
class NullCheckCli {

    static final String DEFAULT_EXTENSION = 'io.github.heyuch.groovy.nullchecker.NullChecker'

    static final String USAGE = '''usage: NullCheckCli [options] <files or dirs>
  --strict              enable flow-sensitive (strict) analysis
  --support <dir>       directory of shared stub sources
  --extension <fqcn>    type-checking extension to run
  --report <file>       write the raw compiler output here
  --fail-on <list>      categories that fail the run (default: null,initialization;
                        use "all" for every category, including resolution errors)
  --checks <list>       rule families to run: null, initialization, or all (default: all).
                        Disabling one stops it being reported, not just failing the run
  --guard-when-true <c#m>   method asserts its first argument non-null when it returns true
  --guard-when-false <c#m>  ... when it returns false (e.g. isNullOrEmpty); repeatable
  --never-returns <c#m>     method never returns normally (e.g. Panic#notExpectedResult);
                            repeatable, and makes the statements after a call unreachable
  --no-container-lookups    do not treat Map.get/find/max and friends as nullable; those
                            results are nullable because the lookup can miss
  --contract <c#m=spec>     declare a contract, e.g. "java.util.Map#get=-> !null";
                            repeatable, overrides annotations and built-ins
  --quiet               only print findings
  --no-canary           skip the pre-flight self-check (NOT recommended)
  --help                show this message

Before reporting anything, the tool checks a built-in, deliberately broken sample
(including a @CompileStatic class) and fails with exit 2 if that sample is not
reported. A silently broken checker would otherwise print "0 findings", which is
indistinguishable from clean code.

exit codes: 0 = nothing to fail on, 1 = a finding in a --fail-on category,
            2 = the checker is not working / misuse

Findings are categorised. A resolution error means the static checker could not
resolve something in code that is dynamically dispatched at runtime; it is reported
but does not fail the run by default, because it is not a nullness claim.'''

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
        List<String> failOn = new ArrayList<String>(NullCheckHarness.DEFAULT_FAIL_ON)
        List<String> guardsTrue = new ArrayList<String>()
        List<String> guardsFalse = new ArrayList<String>()
        List<String> neverReturns = new ArrayList<String>()
        boolean containerLookups = true
        List<String> contracts = new ArrayList<String>()
        List<String> checks = [NullChecker.CHECK_NULL, NullChecker.CHECK_INITIALIZATION]
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
                    if (support == null) {
                        return 2
                    }
                    break
                case '--extension':
                    extension = value(argv, ++i, arg, err)
                    if (extension == null) {
                        return 2
                    }
                    break
                case '--report':
                    report = value(argv, ++i, arg, err)
                    if (report == null) {
                        return 2
                    }
                    break
                case '--checks':
                    String checkSpec = value(argv, ++i, arg, err)
                    if (checkSpec == null) {
                        return 2
                    }
                    List<String> parsed = parseChecks(checkSpec)
                    if (parsed == null || parsed.isEmpty()) {
                        err.println("nullcheck: --checks needs at least one of: " +
                                'null, initialization, all')
                        return 2
                    }
                    checks = parsed
                    break
                case '--guard-when-true':
                    String guardTrue = value(argv, ++i, arg, err)
                    if (guardTrue == null) {
                        return 2
                    }
                    guardsTrue << guardTrue
                    break
                case '--guard-when-false':
                    String guardFalse = value(argv, ++i, arg, err)
                    if (guardFalse == null) {
                        return 2
                    }
                    guardsFalse << guardFalse
                    break
                case '--never-returns':
                    String neverReturn = value(argv, ++i, arg, err)
                    if (neverReturn == null) {
                        return 2
                    }
                    neverReturns << neverReturn
                    break
                case '--no-container-lookups':
                    containerLookups = false
                    break
                case '--contract':
                    String declaredContract = value(argv, ++i, arg, err)
                    if (declaredContract == null) {
                        return 2
                    }
                    contracts << declaredContract
                    break
                case '--fail-on':
                    String spec = value(argv, ++i, arg, err)
                    if (spec == null) {
                        return 2
                    }
                    List<String> parsed = parseFailOn(spec)
                    if (parsed == null) {
                        err.println("nullcheck: unknown category in --fail-on: ${spec}")
                        err.println('nullcheck: valid categories are null, initialization, resolution, all')
                        return 2
                    }
                    failOn = parsed
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

        applyGuards(guardsTrue, guardsFalse)
        System.setProperty('groovy.nullchecker.neverreturns', neverReturns.join(','))
        System.setProperty('groovy.nullchecker.containerlookups', String.valueOf(containerLookups))
        System.setProperty('groovy.nullchecker.contracts', contracts.join(';'))
        System.setProperty('groovy.nullchecker.checks', checks.join(','))

        List<File> files = []
        List<String> missing = []
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
                // A configured root that does not exist is normal -- a Groovy-only project
                // has no src/main/java -- so it is a warning, not an error, or every such
                // project would be unable to run the check at all. It is still reported,
                // because a misspelled root would otherwise silently shrink the analysed set.
                // If NOTHING usable remains, that is an error below.
                missing << path
            }
        }
        missing.each { String path ->
            err.println("nullcheck: warning: configured root does not exist, skipping: ${path}")
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
            out.println "checks     = ${checks.join(',')}"
            if (guardsTrue || guardsFalse) {
                out.println "guards     = true:[${guardsTrue.join(',')}] false:[${guardsFalse.join(',')}]"
            }
            if (neverReturns) {
                out.println "never      = [${neverReturns.join(',')}]"
            }
            if (!containerLookups) {
                out.println 'container  = lookups off'
            }
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
        List<NullCheckHarness.Finding> failing = NullCheckHarness.findingsIn(result.findings, failOn)
        if (!quiet) {
            Map<String, Integer> counts = NullCheckHarness.categoryCounts(result.findings)
            out.println "findings   = ${result.findings.size()} (" +
                    "null=${counts['null']}, initialization=${counts['initialization']}, " +
                    "resolution=${counts['resolution']})"
            // Coverage is what makes a zero meaningful: it shows how much was looked at.
            out.println "coverage   = ${result.coverage}"
            out.println "fail-on    = ${failOn.join(',')}"
            if (result.findings.size() > failing.size()) {
                out.println "note       = ${result.findings.size() - failing.size()} finding(s) outside " +
                        'the failing categories (reported, not failing)'
            }
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

        return failing ? 1 : 0
    }

    /**
     * Parses a {@code --fail-on} value into category labels.
     *
     * @param spec comma-separated labels, or {@code all}
     * @return labels, or {@code null} when the spec names an unknown category
     */
    private static List<String> parseFailOn(String spec) {
        List<String> labels = []
        for (NullCheckHarness.Category category : NullCheckHarness.Category.values()) {
            labels.add(category.label)
        }
        List<String> selected = []
        for (String raw : spec.split(',')) {
            String item = raw.trim().toLowerCase()
            if (item.isEmpty()) {
                continue
            }
            if (item == 'all') {
                return labels
            }
            if (!labels.contains(item)) {
                return null
            }
            selected.add(item)
        }
        return selected
    }

    /**
     * Publishes the guard lists where the checker reads them.
     *
     * <p>Separated from parsing so the same transport works for a caller that drives the
     * checker directly: setting the two system properties is the whole contract.
     */
    private static void applyGuards(List<String> guardsTrue, List<String> guardsFalse) {
        System.setProperty('groovy.nullchecker.guards.true', guardsTrue.join(','))
        System.setProperty('groovy.nullchecker.guards.false', guardsFalse.join(','))
    }

    /**
     * Parses {@code --checks}.
     *
     * @param spec comma-separated family names, or {@code all}
     * @return names, or {@code null} when one is unknown
     */
    private static List<String> parseChecks(String spec) {
        List<String> known = [NullChecker.CHECK_NULL, NullChecker.CHECK_INITIALIZATION]
        List<String> selected = []
        for (String raw : spec.split(',')) {
            String name = raw.trim().toLowerCase()
            if (name.isEmpty()) {
                continue
            }
            if (name == NullChecker.CHECKS_ALL) {
                return known
            }
            if (!known.contains(name)) {
                return null
            }
            selected.add(name)
        }
        return selected
    }

    private static String value(List<String> argv, int index, String option, PrintStream err) {
        if (index >= argv.size()) {
            err.println("nullcheck: option ${option} needs a value")
            return null
        }
        return argv[index]
    }
}
