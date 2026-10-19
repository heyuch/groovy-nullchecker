package io.github.heyuch.groovy.nullchecker.gradle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Runs real Gradle builds against the plugin.
 *
 * <p>Unit tests would prove nothing here: the plugin's whole job is to assemble a task
 * that produces a particular exit code, and the failure that matters -- a task that looks
 * fine while the checker never ran -- is only observable in an actual build. So every case
 * below asserts on a real build's outcome, and each positive case is paired with a control.
 *
 * <p>The plugin is supplied to the build under test from source through {@code --include-build},
 * so these tests exercise it exactly as a consumer would. That matters: a test that silently
 * skipped itself when an artifact was missing would be the same class of "green means nothing"
 * bug the rest of this project exists to prevent. The checker is not resolved from source --
 * {@code writeSettings} records why, and what the build does about it instead.
 */
class NullCheckPluginFunctionalSpec extends Specification {

    @TempDir
    File testProjectDir

    /** The build to include, injected by the functionalTest task as plugin.build.dir. */
    private static final String PLUGIN_BUILD_PATH =
            System.getProperty('plugin.build.dir') ?: '../'

    private static final String PLUGIN_VERSION = '0.2.0'

    /** A @Nullable annotation, so a fixture needs no external dependency. */
    private void writeAnnotation() {
        File dir = new File(testProjectDir, 'src/main/java/demo')
        dir.mkdirs()
        new File(dir, 'Nullable.java').text = '''package demo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE, ElementType.TYPE})
public @interface Nullable {
}
'''
    }

    private void writeSettings() {
        // The plugin is supplied from source by --include-build (Gradle's plugin substitution).
        // The checker is NOT: the plugin asks for io.github.heyuch:groovy-nullchecker, while
        // dependency substitution matches on group + module name -- and this module is named
        // nullchecker, so nothing matches and the artifact is resolved from mavenLocal. The
        // functionalTest task publishes it there first, so a clean checkout still runs
        // `./gradlew check` in one go.
        //
        // That separation is what lets 'fails loudly ... when the tool cannot be resolved' work:
        // with nothing substituting the coordinate, a bogus toolVersion genuinely is unresolvable.
        // (Were it substituted, substitution would ignore the version and resolve it anyway.)
        new File(testProjectDir, 'settings.gradle').text = '''
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
rootProject.name = 'plugin-functional-test'
'''
    }

    private void writeBuild(String extraConfig = '') {
        new File(testProjectDir, 'build.gradle').text = """
plugins {
    id 'groovy'
    id 'io.github.heyuch.groovy-nullchecker' version '${PLUGIN_VERSION}'
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'org.apache.groovy:groovy:4.0.30'
}

${extraConfig}
"""
    }

    /** A @CompileStatic class that dereferences a @Nullable field with no guard. */
    private void writeViolation() {
        File dir = new File(testProjectDir, 'src/main/groovy/demo')
        dir.mkdirs()
        new File(dir, 'Violation.groovy').text = '''package demo

import groovy.transform.CompileStatic

@CompileStatic
class Violation {
    private @Nullable String value

    int bad() {
        return value.length()
    }
}
'''
    }

    /** The same shape, correctly guarded: the negative control. */
    private void writeClean() {
        File dir = new File(testProjectDir, 'src/main/groovy/demo')
        dir.mkdirs()
        new File(dir, 'Clean.groovy').text = '''package demo

import groovy.transform.CompileStatic

@CompileStatic
class Clean {
    private @Nullable String value

    int fine() {
        return value == null ? 0 : value.length()
    }
}
'''
    }

    private GradleRunner runner(String... args) {
        GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments((args as List<String>) +
                        ['--include-build', PLUGIN_BUILD_PATH, '--stacktrace'])
    }

    private BuildResult run(String... args) {
        runner(args).build()
    }

    private BuildResult runAndFail(String... args) {
        runner(args).buildAndFail()
    }

    def 'reports a violation in a @CompileStatic class and stays non-fatal by default'() {
        given: 'a statically compiled class with a real violation and no source annotation'
        writeSettings()
        writeBuild()
        writeAnnotation()
        writeViolation()

        when: 'the report task runs'
        BuildResult result = run('nullcheck')

        then: 'the task succeeds (report-only) but the violation is visible'
        result.task(':nullcheck').outcome == TaskOutcome.SUCCESS
        result.output.contains('null dereference')
        result.output.contains('Violation.groovy')

        and: 'the self-check and coverage are reported, so the number is interpretable'
        result.output.contains('canary')
        result.output.contains('coverage')
    }

    def 'gates the build when failOnError is enabled'() {
        given:
        writeSettings()
        writeBuild('nullcheck { failOnError = true }')
        writeAnnotation()
        writeViolation()

        when:
        BuildResult result = runAndFail('nullcheck')

        then:
        result.output.contains('findings reported')
    }

    def 'stays silent on correctly guarded @CompileStatic code'() {
        given: 'the negative control for the case above'
        writeSettings()
        writeBuild('nullcheck { failOnError = true }')
        writeAnnotation()
        writeClean()

        when:
        BuildResult result = run('nullcheck')

        then: 'a clean run must not fail, and must still show it checked something'
        result.task(':nullcheck').outcome == TaskOutcome.SUCCESS
        result.output.contains('no failing findings')

        and: 'coverage proves the statically compiled class was visited rather than skipped'
        result.output.contains('compileStatic=1')
    }

    def 'honours a package-level default, so package-info is read'() {
        given: 'a @NullMarked package whose class carries no annotation of its own'
        writeSettings()
        writeBuild('nullcheck { failOnError = true }')
        File pkgDir = new File(testProjectDir, 'src/main/java/demo/marked')
        pkgDir.mkdirs()
        new File(pkgDir, 'NullMarked.java').text = '''package demo.marked;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PACKAGE, ElementType.TYPE, ElementType.METHOD})
public @interface NullMarked {
}
'''
        new File(pkgDir, 'package-info.java').text = '''@demo.marked.NullMarked
package demo.marked;
'''
        File srcDir = new File(testProjectDir, 'src/main/groovy/demo/marked')
        srcDir.mkdirs()
        new File(srcDir, 'ByPackage.groovy').text = '''package demo.marked

class ByPackage {
    String returnsNull() {
        return null
    }
}
'''

        when: 'the default targets include src/main/java, where package-info.java lives'
        BuildResult result = runAndFail('nullcheck')

        then:
        result.output.contains('Cannot return null')
    }

    def 'a target root that does not exist is skipped with a warning, not a failure'() {
        given: 'a configured root that does not exist, alongside one that does'
        writeSettings()
        writeBuild("nullcheck { targets = ['src/main/groovy', 'src/main/absent'] }")
        writeAnnotation()
        writeViolation()

        when:
        BuildResult result = run('nullcheck')

        then: 'the default targets include src/main/java, but its absence must not be fatal'
        result.task(':nullcheck').outcome == TaskOutcome.SUCCESS
        result.output.contains('does not exist')
        result.output.contains('null dereference')
    }

    def 'fails when every configured root is missing, so a typo cannot silently check nothing'() {
        given: 'the default roots removed entirely'
        writeSettings()
        writeBuild()
        new File(testProjectDir, 'src/main/groovy').deleteDir()
        new File(testProjectDir, 'src/main/java').deleteDir()

        when:
        BuildResult result = runAndFail('nullcheck')

        then:
        result.output.contains('no .groovy inputs')
    }

    def 'reports even when the project does not compile'() {
        given: 'a broken source: the state a project adopting this checker is likely to be in'
        writeSettings()
        // failOnError proves the analysis genuinely ran: a silent no-op would exit 0.
        writeBuild('nullcheck { failOnError = true }')
        writeAnnotation()
        writeViolation()
        File brokenDir = new File(testProjectDir, 'src/main/groovy/demo')
        brokenDir.mkdirs()
        new File(brokenDir, 'Broken.groovy').text = '''package demo

class Broken {
    int bad() {
        return undefinedSymbol.length()
    }
}
'''

        when: 'the report task runs'
        BuildResult result = runAndFail('nullcheck')

        then: 'it must not require compileGroovy, or a broken build could never be measured'
        result.task(':compileGroovy') == null
        result.output.contains('canary')
        result.output.contains('coverage')

        and: 'the real violation was still found, proving the sources were analysed'
        result.output.contains('null dereference')
        result.output.contains('findings reported')
    }

    def 'fails loudly instead of reporting clean when the tool cannot be resolved'() {
        given: 'a tool version that exists nowhere'
        writeSettings()
        writeBuild('nullcheck { toolVersion = "9.9.9" }')
        writeAnnotation()
        writeClean()

        when:
        BuildResult result = runAndFail('nullcheck')

        then: 'an unresolvable tool must never look like a passing check'
        result.output.contains('9.9.9')
    }
}
