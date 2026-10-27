package io.github.heyuch.groovy.nullchecker.harness

import groovy.transform.CompileStatic
import io.github.heyuch.groovy.nullchecker.NullableAnnotator

/** Opt-in source migration command; separate from the checking CLI. */
@CompileStatic
class NullableAnnotatorCli {

    static final String USAGE = '''usage: NullableAnnotatorCli [options] <files or dirs>
  --dry-run              print the planned annotations without changing files
  --annotation <fqcn>    annotation to add (default: org.jspecify.annotations.Nullable)
  --help                 show this message

Only high-confidence declarations in @NullMarked scopes are changed: explicit null returns,
null-initialised/assigned fields, and parameters compared with null.
'''

    static void main(String[] args) {
        System.exit(run(args as List<String>, System.out, System.err))
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

    static int run(List<String> argv, PrintStream out, PrintStream err) {
        boolean dryRun = false
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
                case '--annotation':
                    if (i + 1 >= argv.size()) {
                        err.println('nullable: --annotation needs a value')
                        return 2
                    }
                    annotation = argv[++i]
                    break
                default:
                    if (argument.startsWith('-')) {
                        err.println("nullable: unknown option: ${argument}")
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
            err.println('nullable: no .groovy inputs')
            err.println(USAGE)
            return 2
        }
        files = files.unique().sort { File file -> file.absolutePath }
        try {
            NullableAnnotator.run(files, annotation, dryRun, out)
            return 0
        } catch (Throwable failure) {
            err.println("nullable: ${failure.message}")
            return 2
        }
    }

}
