package io.github.heyuch.groovy.nullchecker.harness

import groovy.transform.CompileStatic
import io.github.heyuch.groovy.nullchecker.NullableAnnotator

/** Report-driven, opt-in source fixes; separate from the checking task. */
@CompileStatic
class NullCheckAutofixCli {

    static final String USAGE = '''usage: NullCheckAutofixCli [options] <files or dirs>
  --dry-run              print planned fixes without changing files
  --strict               use strict checking before proposing fixes
  --annotation <fqcn>    annotation to add (default: org.jspecify.annotations.Nullable)
  --help                 show this message

Rules:
  Cannot return @Nullable value from @NonNull method -> add @Nullable to the method
  Cannot pass @Nullable value to @NonNull parameter  -> add @Nullable to the parameter
                                                       only when its body checks for null
'''

    static void main(String[] args) {
        System.exit(run(args as List<String>, System.out, System.err))
    }

    static int run(List<String> argv, PrintStream out, PrintStream err) {
        boolean dryRun = false
        boolean strict = false
        String annotation = NullableAnnotator.DEFAULT_ANNOTATION
        List<String> paths = []
        for (int i = 0; i < argv.size(); i++) {
            String argument = argv[i]
            switch (argument) {
                case '--help':
                case '-h':
                    out.println(USAGE)
                    return 0
                case '--dry-run':
                    dryRun = true
                    break
                case '--strict':
                    strict = true
                    break
                case '--annotation':
                    if (i + 1 >= argv.size()) {
                        err.println('autofix: --annotation needs a value')
                        return 2
                    }
                    annotation = argv[++i]
                    break
                default:
                    if (argument.startsWith('-')) {
                        err.println("autofix: unknown option: ${argument}")
                        err.println(USAGE)
                        return 2
                    }
                    paths.add(argument)
            }
        }
        List<File> files = []
        for (String path : paths) {
            File input = new File(path)
            if (input.directory) {
                collect(input, files)
            } else if (input.isFile() && input.name.endsWith('.groovy')) {
                files.add(input)
            }
        }
        if (files.isEmpty()) {
            err.println('autofix: no .groovy inputs')
            err.println(USAGE)
            return 2
        }
        files = files.unique().sort { File file -> file.absolutePath }
        try {
            NullCheckHarness.Result result = NullCheckHarness.check(files, 'io.github.heyuch.groovy.nullchecker.NullChecker', strict)
            out.println "autofix: checked ${files.size()} file(s), ${result.findings.size()} finding(s)"
            List<NullableAnnotator.FixFinding> findings = result.findings.collect { NullCheckHarness.Finding f ->
                new NullableAnnotator.FixFinding(path: f.path, line: f.line,
                        category: f.category.label, message: f.message)
            }
            NullableAnnotator.runFromFindings(files, findings, annotation, dryRun, out)
            return 0
        } catch (Throwable failure) {
            err.println("autofix: ${failure.message}")
            return 2
        }
    }

    private static void collect(File directory, List<File> files) {
        File[] entries = directory.listFiles()
        if (entries == null) {
            return
        }
        for (File entry : entries) {
            if (entry.directory) {
                collect(entry, files)
            } else if (entry.name.endsWith('.groovy')) {
                files.add(entry)
            }
        }
    }

}
