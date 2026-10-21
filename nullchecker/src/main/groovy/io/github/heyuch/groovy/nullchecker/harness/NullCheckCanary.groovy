package io.github.heyuch.groovy.nullchecker.harness

import groovy.transform.CompileStatic

/**
 * A deliberately broken sample the tool checks itself against before reporting.
 *
 * <p>A null checker's dangerous failure is silence, and silence is indistinguishable
 * from success. So the tool never reports a result without first proving it can still
 * see a violation: this sample must be reported, and if it is not, the run is declared
 * broken rather than clean.
 *
 * <p>It deliberately contains BOTH a plain class and a {@code @CompileStatic} one. The
 * statically compiled class is the shape that was silently skipped before
 * {@link io.github.heyuch.groovy.nullchecker.NullCheckCustomizer} declared a
 * registration phase, and it is the shape most real Groovy code has -- a canary without
 * it would have kept passing while checking nothing.
 */
@CompileStatic
class NullCheckCanary {

    /**
     * Sources for the canary, keyed by a name suitable for reporting.
     *
     * <p>The extension is spelled with the same placeholder the corpus uses, so the
     * canary is exercised through the ordinary path rather than a special case.
     */
    static final Map<String, String> SAMPLES = [
            'canary/DynamicCanary.groovy': '''package canary

import corpus.canary.Nullable

class DynamicCanary {
    private @Nullable String value
    int bad() { value.length() }
}
''',
            'canary/StaticCanary.groovy': '''package canary

import groovy.transform.CompileStatic
import corpus.canary.Nullable

@CompileStatic
class StaticCanary {
    private @Nullable String value
    int bad() { value.length() }
}
''',
            // Shapes the checker has crashed on, or could. They are expected to report
            // nothing; their purpose is to be *analysed*, so an internal failure shows up
            // here instead of in a consumer's first run.
            // A static method call in a condition: containsKey narrowing only applies to
            // instance calls, and treating every condition as a MethodCallExpression crashed
            // here (StaticMethodCallExpression cast to MethodCallExpression).
            'canary/StaticCallCanary.groovy': '''package canary

import groovy.transform.CompileStatic

@CompileStatic
class StaticCallCanary {
    static boolean isPresent(String value) {
        return value != null
    }

    static int size(String value) {
        if (isPresent(value)) {
            return value.length()
        }
        return 0
    }
}
''',
            'canary/SpreadCanary.groovy': '''package canary

import groovy.transform.CompileStatic
import corpus.canary.Nullable

@CompileStatic
class SpreadCanary {
    static class Item {
        @Nullable String code
    }

    static List<String> codes(List<Item> items) {
        return items*.code.findAll { String value -> value != null }
    }
}
''',
            'canary/SafeNavCanary.groovy': '''package canary

import groovy.transform.CompileStatic

@CompileStatic
class SafeNavCanary {
    static String trimmed(List<String> values) {
        return values?.first()?.trim() ?: ''
    }
}
''',
    ] as Map<String, String>

    /** The annotation the canary samples rely on; provided in memory, not on the classpath. */
    static final String NULLABLE_SOURCE = '''package corpus.canary

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE, ElementType.TYPE])
@interface Nullable {
}
'''

    /** A finding is expected from the statically compiled sample specifically. */
    static final String REQUIRED_SAMPLE = 'canary/StaticCanary.groovy'

    /** Line in the canary where a violation is expected. */
    static final int REQUIRED_LINE = 7

}
