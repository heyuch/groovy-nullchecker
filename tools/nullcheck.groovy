/*
 * CLI driver for the Groovy 4 null checker.
 *
 * All the compiler-invocation subtleties live in
 * io.github.heyuch.groovy4.nullchecker.harness.NullCheckHarness; this script only
 * handles arguments, output and the exit code, so there is exactly one place where
 * the check is actually wired up.
 *
 * Usage:
 *   groovy nullcheck.groovy [--strict] [--support <dir>] [--extension <fqcn>]
 *                           [--report <file>] [--quiet] <path>...
 *
 * Exit codes: 0 = clean, 1 = findings, 2 = harness misuse.
 *
 * Note: argument parsing is hand-rolled on purpose. Groovy 4's CliBuilder lives in
 * the separate groovy-cli-picocli artifact, and a developer tool that needs an extra
 * jar to start is a tool people stop running.
 */

import io.github.heyuch.groovy4.nullchecker.harness.NullCheckHarness

String usage = '''usage: nullcheck.groovy [options] <files or dirs>
  --strict              enable flow-sensitive (strict) analysis
  --support <dir>       directory of shared stub sources
  --extension <fqcn>    type-checking extension to run
  --report <file>       write the raw compiler output here
  --quiet               only print findings
  --help                show this message'''

/** Reads an option's value, failing loudly when it is missing. */
String requireValue(List<String> argv, int index, String option) {
    if (index >= argv.size()) {
        System.err.println("nullcheck: option ${option} needs a value")
        System.exit(2)
    }
    argv[index]
}

String extension = System.getProperty('nullchecker.extension') ?:
        'io.github.heyuch.groovy4.nullchecker.NullChecker'
boolean strict = false
boolean quiet = false
String support = null
String report = null
List<String> paths = []

List<String> argv = args as List<String>
for (int i = 0; i < argv.size(); i++) {
    String arg = argv[i]
    switch (arg) {
        case '--help':
        case '-h':
            println usage
            System.exit(0)
            break
        case '--strict':
            strict = true
            break
        case '--quiet':
        case '-q':
            quiet = true
            break
        case '--support':
            support = requireValue(argv, ++i, arg)
            break
        case '--extension':
            extension = requireValue(argv, ++i, arg)
            break
        case '--report':
            report = requireValue(argv, ++i, arg)
            break
        default:
            if (arg.startsWith('-')) {
                System.err.println("nullcheck: unknown option: ${arg}")
                System.err.println(usage)
                System.exit(2)
            }
            paths << arg
    }
}

List<File> files = []
paths.each { String path ->
    File input = new File(path)
    if (input.directory) {
        input.eachFileRecurse { File f ->
            if (f.isFile() && f.name.endsWith('.groovy')) {
                files << f
            }
        }
    } else if (input.isFile() && input.name.endsWith('.groovy')) {
        files << input
    } else {
        System.err.println("nullcheck: no such file or directory: ${input}")
        System.exit(2)
    }
}

// Shared stubs are compiled in the same unit as the fixture, so a fixture can never
// be checked against a stale copy of a helper class.
if (support) {
    File supportDir = new File(support)
    if (!supportDir.directory) {
        System.err.println("nullcheck: support dir not found: ${supportDir}")
        System.exit(2)
    }
    List<File> supportFiles = []
    supportDir.eachFileRecurse { File f ->
        if (f.isFile() && f.name.endsWith('.groovy')) {
            supportFiles << f
        }
    }
    files = supportFiles + files
}

files = files.unique().sort { it.absolutePath }

if (!files) {
    System.err.println('nullcheck: no .groovy inputs')
    System.err.println(usage)
    System.exit(2)
}

if (!quiet) {
    println "groovy     = ${GroovySystem.version}"
    println "extension  = ${extension}"
    println "strict     = ${strict}"
    println "files      = ${files.size()}"
}

NullCheckHarness.Result result
try {
    result = NullCheckHarness.check(files, extension, strict)
} catch (IllegalStateException e) {
    System.err.println("nullcheck: ${e.message}")
    System.exit(2)
    return
}

result.findings.each { NullCheckHarness.Finding f -> println "FINDING ${f}" }

if (!quiet) {
    println "findings   = ${result.findings.size()}"
    if (result.collectedOnly) {
        println 'note       = compiler returned normally but had collected errors'
    }
    if (result.threw) {
        println 'note       = findings came from a thrown MultipleCompilationErrorsException'
    }
}

if (report) {
    File out = new File(report)
    out.parentFile?.mkdirs()
    out.text = """groovy=${GroovySystem.version}
extension=${extension}
strict=${strict}
files=${files.size()}
findings=${result.findings.size()}

${result.raw}
"""
    if (!quiet) {
        println "report     = ${out.absolutePath}"
    }
}

System.exit(result.findings ? 1 : 0)
