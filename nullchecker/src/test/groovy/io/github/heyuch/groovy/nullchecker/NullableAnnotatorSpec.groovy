package io.github.heyuch.groovy.nullchecker

import java.nio.file.Files
import spock.lang.Specification

/** High-confidence, opt-in source migration; dry-run must never modify the file. */
class NullableAnnotatorSpec extends Specification {

    File file

    def setup() {
        File dir = File.createTempDir('nullchecker-annotate', '')
        file = new File(dir, 'A.groovy')
        file.setText('''package demo

@NullMarked
class A {

    String field = null

    String find() {
        return null
    }

    void use(String value) {
        if (value == null) {
            return
        }
    }
}
''', 'UTF-8')
    }

    def cleanup() {
        file.parentFile.deleteDir()
    }

    def 'dry run reports high-confidence targets and leaves the file unchanged'() {
        given:
        String before = file.getText('UTF-8')
        ByteArrayOutputStream out = new ByteArrayOutputStream()

        when:
        NullableAnnotator.Result result = NullableAnnotator.run([file],
                NullableAnnotator.DEFAULT_ANNOTATION, true, new PrintStream(out))

        then:
        result.added == 3
        out.toString().contains("would add @Nullable to method 'demo.A#find'")
        out.toString().contains("would add @Nullable to field 'demo.A#field'")
        out.toString().contains("would add @Nullable to parameter 'value'")
        file.getText('UTF-8') == before
    }

    def 'dry run prints a line-level diff'() {
        given:
        ByteArrayOutputStream out = new ByteArrayOutputStream()

        when:
        NullableAnnotator.run([file], NullableAnnotator.DEFAULT_ANNOTATION, true, new PrintStream(out))

        then:
        String text = out.toString('UTF-8')
        text.contains('--- A.groovy:')
        text.contains('+     @Nullable String field')
        text.contains('+     @Nullable String find()')
    }

    def 'multiline declarations, tabs and CRLF preserve valid source'() {
        given:
        file.setText("""package demo\r\n\r\n@NullMarked\r\nclass B {\r\n\tString field =\r\n\t\t\tnull\r\n\r\n\tString find() {\r\n\t\treturn null\r\n\t}\r\n}\r\n""", 'UTF-8')
        ByteArrayOutputStream out = new ByteArrayOutputStream()

        when:
        NullableAnnotator.Result result = NullableAnnotator.run([file],
                NullableAnnotator.DEFAULT_ANNOTATION, false, new PrintStream(out))
        String migrated = file.getText('UTF-8')

        then:
        result.added == 2
        migrated.contains('@Nullable String field')
        migrated.contains('@Nullable String find()')
        migrated.contains('\r\n')
        !migrated.contains('String field = @Nullable')
    }

    def 'apply inserts annotations and is idempotent'() {
        when:
        NullableAnnotator.Result first = NullableAnnotator.run([file],
                NullableAnnotator.DEFAULT_ANNOTATION, false, new PrintStream(new ByteArrayOutputStream()))
        String migrated = file.getText('UTF-8')
        NullableAnnotator.Result second = NullableAnnotator.run([file],
                NullableAnnotator.DEFAULT_ANNOTATION, false, new PrintStream(new ByteArrayOutputStream()))

        then:
        first.added == 3
        migrated.contains('@Nullable String field')
        migrated.contains('@Nullable String find()')
        migrated.contains('void use(@Nullable String value)')
        migrated.contains('import org.jspecify.annotations.Nullable')
        second.added == 0

        cleanup:
        Files.deleteIfExists(file.toPath())
    }
}
