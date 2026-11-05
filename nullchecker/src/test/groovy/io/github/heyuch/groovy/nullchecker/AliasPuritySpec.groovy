package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import spock.lang.Specification

/** D6: a non-pure call drops field facts; a pure call preserves them. */
class AliasPuritySpec extends Specification {

    static final String SOURCE = '''package demo

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import groovy.transform.CompileStatic

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.METHOD])
@interface Pure {
}

@CompileStatic
class Holder {
    @Nullable String value

    void clear() {
        value = null
    }

    @Pure
    static String read(Holder holder) {
        return holder.value
    }
}

@CompileStatic
class Use {
    int nonPure(Holder a, Holder b) {
        if (a.value != null) {
            b.clear()
            return a.value.length()
        }
        return 0
    }

    int pure(Holder a) {
        if (a.value != null) {
            Holder.read(a)
            return a.value.length()
        }
        return 0
    }

    int localAlias(Holder a, Holder b) {
        if (a.value != null) {
            b = new Holder()
            return a.value.length()
        }
        return 0
    }
}
'''

    def 'a non-pure call invalidates field facts and a pure call preserves them'() {
        given:
        File dir = File.createTempDir('nullcheck-alias', '')
        File annotation = new File(dir, 'Nullable.groovy')
        annotation.setText('''package demo

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE])
@interface Nullable {
}
''', 'UTF-8')
        File source = new File(dir, 'Use.groovy')
        source.setText(SOURCE, 'UTF-8')

        when:
        NullCheckHarness.Result result = NullCheckHarness.check([annotation, source],
                'io.github.heyuch.groovy.nullchecker.NullChecker', true)
        List<String> messages = NullCheckHarness.findingsIn(result.findings,
                [NullCheckHarness.Category.NULL.label]).collect { NullCheckHarness.Finding f -> f.message }

        then:
        messages.size() == 2
        messages.every { String message -> message.contains("'value' may be null") }

        cleanup:
        dir.deleteDir()
    }
}
