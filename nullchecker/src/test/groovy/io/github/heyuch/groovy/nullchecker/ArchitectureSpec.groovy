package io.github.heyuch.groovy.nullchecker

import java.util.regex.Pattern
import spock.lang.Specification

/** Executable ownership rules that are too project-specific for a stock CodeNarc rule. */
class ArchitectureSpec extends Specification {

    private static final Pattern CONTRACT_PARSING =
            ~/findAnnotation\([^)]*'Contract'|contractFromJetBrains|parseContract/

    private static final Pattern JAVA_STREAM =
            ~/\.(?:parallel)?stream\s*\(/

    def 'contract parsing has one owner'() {
        given:
        List<File> offenders = groovySources().findAll { File file ->
            file.name != 'ContractRegistry.groovy' && CONTRACT_PARSING.matcher(file.text).find()
        }

        expect:
        offenders.isEmpty()
    }

    def 'production code does not use Java streams'() {
        given:
        List<File> offenders = groovySources().findAll { File file ->
            JAVA_STREAM.matcher(file.text).find()
        }

        expect:
        offenders.isEmpty()
    }

    private static List<File> groovySources() {
        File root = new File('src/main/groovy')
        if (!root.isDirectory()) {
            root = new File(System.getProperty('user.dir'), 'nullchecker/src/main/groovy')
        }
        List<File> files = []
        collect(root, files)
        return files
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
