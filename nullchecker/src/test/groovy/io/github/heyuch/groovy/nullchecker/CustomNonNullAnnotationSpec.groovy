package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import spock.lang.Specification

/** Project-specific @NonNull annotation names are opt-in configuration, not hard-coded. */
class CustomNonNullAnnotationSpec extends Specification {

    def 'a configured annotation is treated as non-null'() {
        given:
        File dir = File.createTempDir('nullchecker-custom-nonnull', '')
        new File(dir, 'MyNonnull.groovy').setText('''package demo

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.LOCAL_VARIABLE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER])
@interface MyNonnull {
}
''', 'UTF-8')
        new File(dir, 'Use.groovy').setText('''package demo

class Use {
    void run() {
        @MyNonnull String value = null
        value.length()
    }
}
''', 'UTF-8')
        System.setProperty(AstSupport.NONNULL_ANNOTATIONS_PROPERTY, 'com.example.MyNonnull')

        when:
        NullCheckHarness.Result result = NullCheckHarness.check(
                [new File(dir, 'MyNonnull.groovy'), new File(dir, 'Use.groovy')],
                NullChecker.name, false)

        then:
        result.findings.any { NullCheckHarness.Finding finding ->
            finding.message.contains("Cannot assign null to @NonNull variable 'value'")
        }

        cleanup:
        System.clearProperty(AstSupport.NONNULL_ANNOTATIONS_PROPERTY)
        dir.deleteDir()
    }
}
