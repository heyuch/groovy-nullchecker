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

/**
 * Adds a {@code nullcheck} task that runs the Groovy 4 null checker over a project's
 * sources without modifying them.
 *
 * <p>Why a separate task, and why the CLI underneath:
 * <ul>
 *   <li>A separate task cannot break {@code compileGroovy}, so an existing codebase can be
 *       measured before anything is gated on the result.</li>
 *   <li>The CLI already carries the two things that are easy to get wrong: it registers the
 *       checker so {@code @CompileStatic} classes are actually visited, and it proves it can
 *       still see a violation before reporting any number.</li>
 *   <li>No source changes and no compiler configuration script, so the checker never
 *       becomes a dependency of the application itself.</li>
 * </ul>
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

            javaExec.doLast {
                int code = javaExec.executionResult.get().exitValue
                if (code == 0) {
                    javaExec.logger.lifecycle('nullcheck: no findings')
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
        File defaultReport = new File(project.layout.buildDirectory.get().asFile,
                'reports/nullcheck/nullcheck.txt')
        args << '--report' << extension.reportFile.getOrElse(defaultReport.absolutePath)
        args.addAll(extension.targets.get())
        args
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
