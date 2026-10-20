package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import spock.lang.Specification

/**
 * Container lookups that can miss, and the switch that turns the rule off.
 *
 * <p>The element type answers "can a stored value be null"; these operations are partial
 * whatever the element type says. {@code containsKey} narrows the lookup only when the values
 * are non-null, because a present key may still hold an explicit null. Turning the family off
 * must leave the type-based findings untouched, which is what the last case pins.
 */
class ContainerLookupSpec extends Specification {

    static final String EXTENSION = 'io.github.heyuch.groovy.nullchecker.NullChecker'

    static final String NULLABLE = '''package fixtures

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE])
@interface Nullable {
}
'''

    static final String LOOKUPS = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class Lookups {
    static int unguarded(Map<String, String> values) {
        return values.get('k').length()
    }

    static int guarded(Map<String, String> values) {
        if (values.containsKey('k')) {
            return values.get('k').length()
        }
        return 0
    }

    static int nullableValues(Map<String, @Nullable String> values) {
        if (values.containsKey('k')) {
            return values.get('k').length()
        }
        return 0
    }

    static int defaulted(Map<String, String> values) {
        return values.getOrDefault('k', 'x').length()
    }

    static int notFound(List<String> values) {
        return values.find { it == 'a' }.length()
    }

    static int emptyMax(List<Integer> values) {
        return values.max().intValue()
    }

    static int firstThrows(List<String> values) {
        return values.first().length()
    }
}
'''

    File dir

    def setup() {
        dir = File.createTempDir('nullchecker-lookups', '')
        write('Nullable.groovy', NULLABLE)
        write('Lookups.groovy', LOOKUPS)
        System.clearProperty('groovy.nullchecker.containerlookups')
    }

    def cleanup() {
        System.clearProperty('groovy.nullchecker.containerlookups')
        dir.deleteDir()
    }

    def 'lookups that can miss are reported, guarded and defaulted ones are not'() {
        expect: 'unguarded get, nullable values, find and max; containsKey, getOrDefault and first() are silent'
        countNulls(check()) == 4
    }

    def 'turning the family off leaves only the type-based findings'() {
        given:
        System.setProperty('groovy.nullchecker.containerlookups', 'false')

        expect: 'the nullable value type still reports; the partial operations no longer do'
        countNulls(check()) == 1
    }

    private NullCheckHarness.Result check() {
        NullCheckHarness.check([new File(dir, 'Nullable.groovy'),
                                new File(dir, 'Lookups.groovy')], EXTENSION, true)
    }

    private static int countNulls(NullCheckHarness.Result result) {
        List<NullCheckHarness.Finding> nulls = NullCheckHarness.findingsIn(result.findings,
                [NullCheckHarness.Category.NULL.label])
        nulls.size()
    }

    private void write(String name, String content) {
        new File(dir, name).setText(content, 'UTF-8')
    }
}
