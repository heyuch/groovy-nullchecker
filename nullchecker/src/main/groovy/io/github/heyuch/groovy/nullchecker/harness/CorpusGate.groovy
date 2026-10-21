package io.github.heyuch.groovy.nullchecker.harness

import groovy.transform.CompileStatic

/**
 * The corpus gate: the thing that decides whether the port is allowed to ship.
 *
 * <p>It exists because the interesting failure of a null checker is not a wrong
 * message but <em>silence</em>. Three separate times during development a run
 * reported "no findings" while checking nothing at all. The gate therefore pairs
 * every positive fixture (which <em>must</em> be reported) with negative fixtures
 * (which must stay silent), runs each fixture in both modes, and refuses to accept a
 * rule that no fixture exercises.
 *
 * <p>Deliberately independent of any test framework so the same logic drives
 * {@code ./gradlew test} and the Groovy 6 {@code dualRun} comparison.
 */
@CompileStatic
class CorpusGate {

    /** A fixture together with what the manifest demands of it. */
    static class Fixture {
        String relativePath = ''
        String rule = ''
        String strict = 'any'
        String expect = 'some'
        String note = ''
    }

    /** Outcome of checking one fixture in one mode. */
    static class ModeResult {
        String mode = ''
        int findings
        List<String> messages = []
        /** Findings beyond the declared rule; informational, not a failure. */
        List<String> extraRules = []
        /** Non-empty when the count did not match the expectation. */
        List<String> failures = []
    }

    /** Outcome of checking one fixture in every applicable mode. */
    static class FixtureResult {
        Fixture fixture = new Fixture()
        List<ModeResult> modes = []

        List<String> failures() {
            return modes.collectMany { ModeResult m -> m.failures }
        }
    }

    /** The whole run. */
    static class Report {
        List<FixtureResult> results = []
        List<String> failures = []
        Map<String, Integer> ruleCoverage = [:]
        Set<String> uncoveredRules = []

        boolean isOk() {
            return failures.isEmpty() && uncoveredRules.isEmpty()
        }
    }

    /**
     * Reads the manifest.
     *
     * @param manifest the MANIFEST.txt file
     * @return fixtures in file order
     */
    static List<Fixture> parseManifest(File manifest) {
        if (!manifest.isFile()) {
            throw new IllegalStateException("corpus manifest not found: ${manifest.absolutePath}")
        }
        List<Fixture> fixtures = []
        Fixture current = null
        for (String rawLine : manifest.readLines()) {
            String line = rawLine.trim()
            if (!line || line.startsWith('#')) {
                continue
            }
            if (!line.contains('=')) {
                current = new Fixture(relativePath: line)
                fixtures << current
                continue
            }
            if (current == null) {
                throw new IllegalStateException("manifest key before any fixture: ${line}")
            }
            int split = line.indexOf('=')
            String key = line.substring(0, split).trim()
            String value = line.substring(split + 1).trim()
            switch (key) {
                case 'rule': current.rule = value; break
                case 'strict': current.strict = value; break
                case 'expect': current.expect = value; break
                case 'note': current.note = value; break
                default: throw new IllegalStateException("unknown manifest key '${key}'")
            }
        }
        return fixtures
    }

    /**
     * Runs every fixture through every mode the manifest allows.
     *
     * @param corpusDir   corpus root
     * @param extension   type-checking extension class name
     * @param supportDir  directory of shared stub sources
     * @return a report; check {@link Report#isOk()}
     */
    static Report run(File corpusDir, String extension, File supportDir) {
        List<Fixture> fixtures = parseManifest(new File(corpusDir, 'MANIFEST.txt'))
        Report report = new Report()

        // Every fixture on disk must be declared, and every declaration must exist.
        //
        // package-info files are exempt: they are the mechanism a fixture uses to declare
        // package-level defaults, not fixtures themselves. They carry no extending
        // annotation and no expectation, and the gate attaches each one automatically to
        // the fixtures in its package (see packageInfoFor).
        Set<String> declared = fixtures.collect { Fixture f -> f.relativePath } as Set<String>
        Set<String> onDisk = []
        corpusDir.eachFileRecurse { File f ->
            if (NullCheckHarness.isCheckable(f) && f.parentFile != supportDir && !isPackageInfo(f)) {
                onDisk << corpusDir.toPath().relativize(f.toPath()).toString().replace(File.separator, '/')
            }
        }
        (onDisk - declared).each { String orphan ->
            report.failures << ("corpus/${orphan}: fixture exists but is not declared in MANIFEST.txt").toString()
        }
        (declared - onDisk).each { String ghost ->
            report.failures << ("corpus/${ghost}: declared in MANIFEST.txt but does not exist").toString()
        }

        List<File> support = []
        supportDir.eachFileRecurse { File f ->
            if (NullCheckHarness.isCheckable(f)) {
                support << f
            }
        }

        for (Fixture fixture : fixtures) {
            File fixtureFile = new File(corpusDir, fixture.relativePath)
            if (!fixtureFile.isFile()) {
                continue // already reported as a ghost
            }
            if (!CorpusRules.isKnown(fixture.rule)) {
                report.failures << ("corpus/${fixture.relativePath}: unknown rule id '${fixture.rule}'").toString()
            }
            if (!(fixture.strict in ['any', 'only', 'never'])) {
                report.failures << ("corpus/${fixture.relativePath}: bad strict='${fixture.strict}'").toString()
            }

            FixtureResult result = new FixtureResult(fixture: fixture)
            // A fixture's package-level defaults live in a package-info file that is a
            // sibling of the fixture, so it is not itself a declared fixture. Include the
            // one belonging to this fixture's own package (walking up to the corpus root,
            // so nested packages see their ancestors too) and nothing else: including
            // every package-info in the corpus would leak one fixture's defaults into
            // another's package and make the negative fixtures meaningless.
            List<File> packageInfo = packageInfoFor(corpusDir, fixtureFile)
            // Every declared fixture runs in BOTH modes. What differs per declaration is
            // the expectation, not the invocation:
            //   any   -> same expectation in both modes
            //   only  -> must fire with strict, must be silent without it
            //   never -> must be silent in both modes
            List<Boolean> modes = [false, true]
            modes.each { Boolean strictMode ->
                ModeResult mode = new ModeResult(mode: strictMode ? 'strict' : 'annotations')
                NullCheckHarness.Result harness =
                        NullCheckHarness.check(packageInfo + [fixtureFile] + support, extension, strictMode)
                mode.findings = harness.findings.size()
                mode.messages = harness.findings.collect { NullCheckHarness.Finding f -> f.message }

                // expectations
                String expected
                switch (fixture.strict) {
                    case 'only':
                        expected = strictMode ? fixture.expect : 'none'
                        break
                    case 'never':
                        expected = 'none'
                        break
                    default:
                        expected = fixture.expect
                }
                int wanted = expected == 'none' ? 0 : expected == 'some' ? -1 : Integer.parseInt(expected)

                if (wanted == 0 && mode.findings != 0) {
                    mode.failures << ("${mode.mode}: expected no findings, got ${mode.findings}: " +
                            mode.messages.join(' | ')).toString()
                } else if (wanted > 0 && mode.findings != wanted) {
                    mode.failures << ("${mode.mode}: expected ${wanted} findings, got ${mode.findings}").toString()
                } else if (wanted == -1 && mode.findings == 0) {
                    mode.failures << ("${mode.mode}: expected at least one finding, got none " +
                            "(a rule that never fires is worse than no rule)").toString()
                }

                // At least one finding must be attributable to the declared rule, so a
                // fixture cannot pass on the back of some unrelated error. Further
                // findings are recorded, not rejected: a fixture is allowed to trip
                // more than one rule (a null assigned to an @NonNull local is also a
                // null dereference under strict analysis).
                String fragment = CorpusRules.fragmentFor(fixture.rule)
                if (fragment && mode.findings > 0) {
                    if (!mode.messages.any { String m -> m.contains(fragment) }) {
                        mode.failures << ("${mode.mode}: no finding matches rule ${fixture.rule} " +
                                "(expected '${fragment}', got ${mode.messages.join(' | ')})").toString()
                    }
                    List<String> extra = mode.messages.findAll { String m -> !m.contains(fragment) }
                    if (extra) {
                        mode.extraRules = extra
                    }
                }
                result.modes << mode
            }
            report.results << result
        }

        report.results.each { FixtureResult fr ->
            report.failures.addAll(fr.failures())
            if (!fr.failures()) {
                report.ruleCoverage[fr.fixture.rule] =
                        (report.ruleCoverage[fr.fixture.rule] ?: 0) + 1
            }
        }
        report.uncoveredRules = (CorpusRules.RULES.keySet() + CorpusRules.GUARD_RULES.keySet()) -
                report.ruleCoverage.keySet()
        return report
    }

    /** Whether a file is a package-level declaration rather than a fixture. */
    static boolean isPackageInfo(File file) {
        return file.name == 'package-info.java' || file.name == 'package-info.groovy'
    }

    /**
     * Finds the {@code package-info} files that govern a fixture's own package.
     *
     * <p>Both spellings are honoured, {@code package-info.java} and
     * {@code package-info.groovy}, because both work and a corpus (or a user's project)
     * may use either.
     *
     * @param corpusDir  corpus root; the upward search stops here
     * @param fixture    the fixture being checked
     * @return governing package-info files, empty when there are none
     */
    static List<File> packageInfoFor(File corpusDir, File fixture) {
        List<File> found = []
        File dir = fixture.parentFile
        while (dir != null && dir.canonicalPath.startsWith(corpusDir.canonicalPath)) {
            ['package-info.java', 'package-info.groovy'].each { String name ->
                File candidate = new File(dir, name)
                if (candidate.isFile()) {
                    found << candidate
                }
            }
            if (dir.canonicalPath == corpusDir.canonicalPath) {
                break
            }
            dir = dir.parentFile
        }
        return found
    }

    /** Human-readable one-line-per-fixture rendering. */
    static String render(Report report) {
        StringBuilder out = new StringBuilder()
        report.results.each { FixtureResult fr ->
            String counts = fr.modes.collect { ModeResult m -> "${m.mode}=${m.findings}" }.join(' ')
            String mark = fr.failures() ? 'FAIL' : 'ok  '
            out.append(String.format('  %s %-8s %-24s %s%n',
                    mark, fr.fixture.rule, counts, fr.fixture.relativePath))
        }
        out.append(String.format('%n  rules covered: %d%n', report.ruleCoverage.size()))
        if (report.uncoveredRules) {
            out.append("  UNCOVERED: ${report.uncoveredRules.sort().join(', ')}\n")
        }
        report.failures.each { String f -> out.append("  FAILURE: ${f}\n") }
        return out.toString()
    }
}
