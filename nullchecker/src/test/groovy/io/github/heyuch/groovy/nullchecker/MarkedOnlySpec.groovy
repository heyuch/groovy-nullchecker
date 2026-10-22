package io.github.heyuch.groovy.nullchecker

import io.github.heyuch.groovy.nullchecker.harness.NullCheckHarness
import spock.lang.Specification

/** Progressive adoption: only @NullMarked classes or exact packages are checked. */
class MarkedOnlySpec extends Specification {

    static final String EXTENSION = 'io.github.heyuch.groovy.nullchecker.NullChecker'

    static final String MARKED = '''package fixtures

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.TYPE, ElementType.PACKAGE])
@interface NullMarked {
}
'''

    static final String UNMARKED = '''package fixtures

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Retention(RetentionPolicy.RUNTIME)
@Target([ElementType.TYPE])
@interface NullUnmarked {
}
'''

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

    static final String MARKED_CLASS = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
@NullMarked
class MarkedClass {
    private @Nullable String value

    int bad() {
        return value.length()
    }
}
'''

    static final String UNMARKED_CLASS = '''package fixtures

import groovy.transform.CompileStatic

@CompileStatic
class UnmarkedClass {
    private @Nullable String value

    int bad() {
        return value.length()
    }
}
'''

    static final String PACKAGE_INFO = '''@fixtures.NullMarked
package fixtures.pkg
'''

    static final String PACKAGE_CLASS = '''package fixtures.pkg

import fixtures.Nullable
import groovy.transform.CompileStatic

@CompileStatic
class PackageClass {
    private @Nullable String value

    int bad() {
        return value.length()
    }
}
'''

    static final String PACKAGE_UNMARKED = '''package fixtures.pkg

import fixtures.NullMarked
import fixtures.NullUnmarked
import fixtures.Nullable
import groovy.transform.CompileStatic

@NullMarked
@NullUnmarked
@CompileStatic
class PackageUnmarked {
    private @Nullable String value

    int bad() {
        return value.length()
    }
}
'''

    File dir

    def setup() {
        dir = File.createTempDir('nullchecker-marked', '')
        write('NullMarked.groovy', MARKED)
        write('NullUnmarked.groovy', UNMARKED)
        write('Nullable.groovy', NULLABLE)
        write('MarkedClass.groovy', MARKED_CLASS)
        write('UnmarkedClass.groovy', UNMARKED_CLASS)
        write('pkg/package-info.groovy', PACKAGE_INFO)
        write('pkg/PackageClass.groovy', PACKAGE_CLASS)
        write('pkg/PackageUnmarked.groovy', PACKAGE_UNMARKED)
        System.clearProperty(NullChecker.MARKED_ONLY_PROPERTY)
    }

    def cleanup() {
        System.clearProperty(NullChecker.MARKED_ONLY_PROPERTY)
        dir.deleteDir()
    }

    def 'markedOnly checks class-level and package-level markings, and skips the rest'() {
        given:
        System.setProperty(NullChecker.MARKED_ONLY_PROPERTY, 'true')

        when:
        NullCheckHarness.Result result = check()

        then:
        countNulls(result) == 2
    }

    def 'without markedOnly the same sources are all checked'() {
        when:
        NullCheckHarness.Result result = check()

        then:
        countNulls(result) > 2
    }

    private NullCheckHarness.Result check() {
        List<File> files = [
                new File(dir, 'NullMarked.groovy'),
                new File(dir, 'NullUnmarked.groovy'),
                new File(dir, 'Nullable.groovy'),
                new File(dir, 'MarkedClass.groovy'),
                new File(dir, 'UnmarkedClass.groovy'),
                new File(dir, 'pkg/package-info.groovy'),
                new File(dir, 'pkg/PackageClass.groovy'),
                new File(dir, 'pkg/PackageUnmarked.groovy'),
        ]
        return NullCheckHarness.check(files, EXTENSION, true)
    }

    private static int countNulls(NullCheckHarness.Result result) {
        return NullCheckHarness.findingsIn(result.findings,
                [NullCheckHarness.Category.NULL.label]).size()
    }

    private void write(String name, String content) {
        File file = new File(dir, name)
        file.parentFile.mkdirs()
        file.setText(content, 'UTF-8')
    }
}
