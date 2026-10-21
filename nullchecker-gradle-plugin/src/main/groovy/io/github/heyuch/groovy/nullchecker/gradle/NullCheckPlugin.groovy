package io.github.heyuch.groovy.nullchecker.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.GroovyCompile

/**
 * Adds a {@code nullcheck} task that runs the Groovy 4 null checker over a project's
 * sources without modifying them.
 *
 * <p>The default is a separate task with the CLI underneath:
 * <ul>
 *   <li>A separate task cannot break {@code compileGroovy}, so an existing codebase can be
 *       measured before anything is gated on the result -- including code that does not
 *       compile.</li>
 *   <li>The CLI already carries the two things that are easy to get wrong: it registers the
 *       checker so {@code @CompileStatic} classes are actually visited, and it proves it can
 *       still see a violation before reporting any number.</li>
 *   <li>No source changes and no compiler configuration script, so the checker never
 *       becomes a dependency of the application itself.</li>
 * </ul>
 *
 * <p>Setting {@code nullcheck { compileCheck = true }} opts into the other mode: the checker
 * is added to the compiler worker's classpath, a generated configuration script registers it,
 * and findings are collected into a file instead of compiler errors. That removes the second
 * compilation, at the cost of losing the report for code that does not compile and the canary;
 * the default stays standalone for those reasons.
 *
 * <p>The checker is resolved from the project's own repositories, so {@code mavenLocal()}
 * must be present until the artifact is published publicly. If it cannot be resolved the
 * task fails saying so -- it must never look like a clean run.
 */
class NullCheckPlugin implements Plugin<Project> {

    static final String DEFAULT_EXTENSION = 'io.github.heyuch.groovy.nullchecker.NullChecker'

    /**
     * The checker version resolved when the extension does not set one.
     *
     * <p>Baked into {@code nullchecker.properties} at build time from this plugin's own version
     * (see the processResources block in build.gradle), not written here as a literal. Both
     * modules ship from one build and share a version, so this cannot drift from the checker
     * actually being released -- which is the point: a plugin carrying a stale literal would send
     * every consumer after a version that does not exist.
     */
    static final String DEFAULT_TOOL_VERSION = loadDefaultToolVersion()

    static final String TOOL_GROUP = 'io.github.heyuch'
    static final String TOOL_NAME = 'groovy-nullchecker'
    static final String CLI_CLASS = 'io.github.heyuch.groovy.nullchecker.harness.NullCheckCli'

    /** Reads the findings file produced by compile mode; no compilation of its own. */
    static final String FINDINGS_CLI_CLASS = 'io.github.heyuch.groovy.nullchecker.NullCheckFindingsCli'
    static final String TASK_NAME = 'nullcheck'

    /** Groovy the tool itself runs on; must match the checker's major version. */
    static final String TOOL_GROOVY_VERSION = '4.0.30'

    @Override
    void apply(Project project) {
        NullCheckExtension extension =
                project.extensions.create('nullcheck', NullCheckExtension, project)

        TaskProvider<JavaExec> task = project.tasks.register(TASK_NAME, JavaExec) { JavaExec t ->
            t.group = 'verification'
            t.description = 'Report potential null-safety problems without failing the build ' +
                    '(set nullcheck.failOnError to gate on it)'
            t.mainClass.set(CLI_CLASS)
            t.ignoreExitValue = true
            t.outputs.upToDateWhen { false }
        }

        project.afterEvaluate {
            Configuration tool = resolvedToolConfiguration(project, extension)
            JavaExec javaExec = task.get()

            if (extension.compileCheck.get()) {
                configureCompileMode(project, extension, javaExec, tool)
            } else {
                // The project's own compile classpath is included so the checker can resolve the
                // annotations and types the sources actually import. Without it, every
                // unresolvable import is reported as a finding and buries the real ones.
                //
                // The main output is included as a FILE collection rather than via
                // sourceSets.main.output, because the latter carries a task dependency on
                // compileGroovy. That would make `nullcheck` refuse to run whenever the project
                // does not compile -- exactly when a report is most useful, and exactly the
                // state a project adopting this checker is likely to be in.
                // The run needs three things on its classpath: the tool, the project's compile
                // classpath (so annotations and types resolve), and compileJava's output (so
                // annotation classes and package-info.class are readable from this compilation).
                //
                // Deliberately NOT sourceSets.main.output: that belongs to compileGroovy too, so
                // referencing it would both force a dependency on compiling the code under
                // analysis and trip Gradle's implicit-dependency validation. compileJava's own
                // output directory is the narrow thing that is actually needed, and compileJava
                // is already declared as the dependency.
                javaExec.classpath = project.files(tool, compileClasspathOf(project), compileJavaOutputOf(project))

                // compileJava only, never compileGroovy. Java sources are where @Nullable-style
                // annotations usually live and the checker needs them compiled; but making the
                // report depend on compiling the Groovy code under analysis would mean a project
                // that does not compile can never be measured -- exactly when a report is most
                // useful. compileJava and compileGroovy are independent, so a broken Groovy
                // source does not block the former.
                project.tasks.findByName('compileJava')?.with { compileJava ->
                    javaExec.dependsOn(compileJava)
                }

                javaExec.args = buildArgs(project, extension)
            }

            javaExec.doLast {
                int code = javaExec.executionResult.get().exitValue
                if (code == 0) {
                    javaExec.logger.lifecycle('nullcheck: no failing findings ' +
                            "(failOn=${extension.failOn.get().join(',')})")
                } else if (code == 1) {
                    javaExec.logger.lifecycle('nullcheck: findings reported (exit 1)')
                    if (extension.failOnError.get()) {
                        throw new GradleException('nullcheck: findings reported')
                    }
                } else {
                    // Exit 2 means the checker never ran: a mistyped extension, an
                    // unresolvable tool, a failed self-check. Reading that as "passed"
                    // would turn "nothing was checked" into a clean bill of health.
                    throw new GradleException("nullcheck: the checker did not run (exit ${code}). " +
                            'This is NOT a clean result. See the output above.')
                }
            }
        }
    }

    /**
     * Runs the checker in the project's own {@code compileGroovy}.
     *
     * <p>The checker is put on the compiler's Groovy classpath, never on the application's
     * compile/runtime classpath. A generated configuration script adds the checker's
     * customizer and points it at a findings file; {@code nullcheck} then only reads that file.
     */
    private static void configureCompileMode(Project project, NullCheckExtension extension,
                                             JavaExec javaExec, Configuration tool) {
        if (project.tasks.findByName('compileGroovy') == null) {
            throw new GradleException('nullcheck: compileCheck requires the Groovy plugin ' +
                    '(no compileGroovy task in this project)')
        }
        TaskProvider<GroovyCompile> compile = project.tasks.named('compileGroovy', GroovyCompile)
        File buildDir = project.layout.buildDirectory.get().asFile
        File findingsFile = new File(buildDir, 'nullcheck/findings.tsv')
        File scriptFile = new File(project.projectDir, '.gradle/nullcheck/compile-check.groovy')
        File reportFile = new File(extension.reportFile.getOrElse(
                new File(buildDir, 'reports/nullcheck/nullcheck.txt').absolutePath))
        Configuration checker = project.configurations.detachedConfiguration(
                project.dependencies.create(
                        "${TOOL_GROUP}:${TOOL_NAME}:${extension.toolVersion.get()}".toString()))

        scriptFile.parentFile.mkdirs()
        File existingScript = compile.get().groovyOptions.configurationScript
        String userScript = ''
        if (existingScript != null && existingScript.absolutePath != scriptFile.absolutePath) {
            // The property holds one file. Wrap the project's own script instead of replacing
            // it: evaluate() runs it with the same binding, so it still sees `configuration`.
            userScript = "evaluate(new File('${escape(existingScript.absolutePath)}'))\n"
        }
        scriptFile.setText(userScript + compileScript(extension, findingsFile), 'UTF-8')

        compile.configure { GroovyCompile task ->
            task.classpath = project.files(task.classpath, checker)
            task.groovyClasspath = project.files(task.groovyClasspath, checker)
            task.groovyOptions.configurationScript = scriptFile
            task.outputs.file(findingsFile)
            task.doFirst {
                findingsFile.parentFile.mkdirs()
                findingsFile.text = ''
                new File(findingsFile.absolutePath + '.errors').text = ''
                new File(findingsFile.absolutePath + '.coverage').text = ''
            }
        }

        javaExec.mainClass.set(FINDINGS_CLI_CLASS)
        javaExec.classpath = project.files(tool)
        javaExec.dependsOn(compile)
        List<String> args = ['--findings', findingsFile.absolutePath]
        if (!extension.failOn.get().isEmpty()) {
            args.addAll(['--fail-on', extension.failOn.get().join(',')])
        }
        args.addAll(['--report', reportFile.absolutePath])
        javaExec.args = args

        if (extension.canary.get()) {
            project.logger.warn('nullcheck: compileCheck mode does not run the canary; ' +
                    'set nullcheck.canary = false to silence this warning')
        }
    }

    /** The configuration script the compiler worker runs before compiling. */
    private static String compileScript(NullCheckExtension extension, File findingsFile) {
        List<String> checks = enabledChecks(extension)
        if (checks.isEmpty()) {
            throw new GradleException('nullcheck: nullnessCheck and initializationCheck are both ' +
                    'off, which would check nothing. Enable at least one.')
        }
        String contracts = extension.contracts.get().collect { String owner, String spec ->
            "${owner}=${spec}".toString()
        }.join(';')
        return """import io.github.heyuch.groovy.nullchecker.NullCheckCustomizer

System.setProperty('groovy.nullchecker.findingsFile', '${escape(findingsFile.absolutePath)}')
System.setProperty('groovy.nullchecker.strict', '${extension.strict.get()}')
System.setProperty('groovy.nullchecker.checks', '${escape(checks.join(','))}')
System.setProperty('groovy.nullchecker.containerlookups', '${extension.containerLookups.get()}')
System.setProperty('groovy.nullchecker.guards.true', '${escape(extension.guardNotNullWhenTrue.get().join(','))}')
System.setProperty('groovy.nullchecker.guards.false', '${escape(extension.guardNotNullWhenFalse.get().join(','))}')
System.setProperty('groovy.nullchecker.neverreturns', '${escape(extension.neverReturns.get().join(','))}')
System.setProperty('groovy.nullchecker.contracts', '${escape(contracts)}')
configuration.addCompilationCustomizers(new NullCheckCustomizer('${escape(extension.extension.get())}', 'groovy.transform.TypeChecked', true))
"""
    }

    private static String escape(String value) {
        return value == null ? '' : value.replace('\\', '\\\\').replace("'", "\\'")
    }

    /**
     * Reads the baked-in default checker version (see {@link #DEFAULT_TOOL_VERSION}).
     *
     * <p>Throws rather than falling back to a placeholder: a plugin jar without this resource is
     * a broken build, and a silent fallback would surface only as a consumer's task resolving the
     * wrong artifact.
     */
    private static String loadDefaultToolVersion() {
        Properties props = new Properties()
        InputStream stream = NullCheckPlugin.getResourceAsStream('nullchecker.properties')
        if (stream == null) {
            throw new IllegalStateException(
                    'nullchecker.properties is missing from the plugin jar -- the build is incomplete')
        }
        try {
            props.load(stream)
        } finally {
            stream.close()
        }
        String version = props.getProperty('toolVersion')
        if (!version) {
            throw new IllegalStateException("nullchecker.properties has no 'toolVersion' entry")
        }
        version
    }

    /**
     * Builds the tool classpath: the checker plus a Groovy for it to run on.
     *
     * <p>Resolved eagerly in {@code afterEvaluate} so that an unresolvable version fails
     * with Gradle's own dependency message rather than at task execution.
     */
    private static Configuration resolvedToolConfiguration(Project project, NullCheckExtension extension) {
        Configuration tool = project.configurations.detachedConfiguration(
                project.dependencies.create(
                        "${TOOL_GROUP}:${TOOL_NAME}:${extension.toolVersion.get()}".toString()),
                project.dependencies.create(
                        "org.apache.groovy:groovy:${TOOL_GROOVY_VERSION}".toString()))
        tool
    }

    /** Command line for the CLI, derived from the extension. */
    private static List<String> buildArgs(Project project, NullCheckExtension extension) {
        List<String> args = []
        if (extension.strict.get()) {
            args << '--strict'
        }
        if (!extension.canary.get()) {
            args << '--no-canary'
        }
        List<String> checks = enabledChecks(extension)
        if (checks.isEmpty()) {
            throw new GradleException('nullcheck: nullnessCheck and initializationCheck are both ' +
                    'off, which would check nothing. Enable at least one.')
        }
        args << '--checks' << checks.join(',')
        if (!extension.failOn.get().isEmpty()) {
            args << '--fail-on' << extension.failOn.get().join(',')
        }
        for (String guard : extension.guardNotNullWhenTrue.get()) {
            args << '--guard-when-true' << guard
        }
        for (String guard : extension.guardNotNullWhenFalse.get()) {
            args << '--guard-when-false' << guard
        }
        for (String neverReturn : extension.neverReturns.get()) {
            args << '--never-returns' << neverReturn
        }
        if (!extension.containerLookups.get()) {
            args << '--no-container-lookups'
        }
        extension.contracts.get().each { String owner, String contract ->
            args << '--contract' << "${owner}=${contract}".toString()
        }
        File defaultReport = new File(project.layout.buildDirectory.get().asFile,
                'reports/nullcheck/nullcheck.txt')
        args << '--report' << extension.reportFile.getOrElse(defaultReport.absolutePath)
        args.addAll(extension.targets.get())
        args
    }

    /**
     * The rule families enabled by the extension, in the CLI's vocabulary.
     *
     * <p>Derived from the named booleans so the DSL offers completion instead of asking the
     * user to remember a set of strings.
     */
    private static List<String> enabledChecks(NullCheckExtension extension) {
        List<String> checks = []
        if (extension.nullnessCheck.get()) {
            checks << 'null'
        }
        if (extension.initializationCheck.get()) {
            checks << 'initialization'
        }
        checks
    }

    /**
     * compileJava's output directory, or nothing when there is no such task.
     *
     * <p>Narrower than the source set's output on purpose: it holds the annotation classes
     * and any {@code package-info.class}, which is what this run needs, without reaching
     * into the Groovy output that belongs to the task we must not depend on.
     */
    private static FileCollection compileJavaOutputOf(Project project) {
        def compileJava = project.tasks.findByName('compileJava')
        if (compileJava == null) {
            return project.files()
        }
        project.files(project.provider {
            compileJava.destinationDirectory.asFile.get()
        })
    }

    /** The main source set's compile classpath, or nothing when no Java/Groovy plugin is applied. */
    private static FileCollection compileClasspathOf(Project project) {
        SourceSet main = mainSourceSet(project)
        main == null ? project.files() : main.compileClasspath
    }

    private static SourceSet mainSourceSet(Project project) {
        SourceSetContainer sets = project.extensions.findByType(SourceSetContainer)
        sets == null ? null : sets.findByName('main')
    }
}
