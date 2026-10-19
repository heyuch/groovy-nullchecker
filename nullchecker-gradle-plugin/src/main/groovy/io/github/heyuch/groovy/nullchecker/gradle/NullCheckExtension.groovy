package io.github.heyuch.groovy.nullchecker.gradle

import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the {@code nullcheck} task.
 *
 * <p>Defaults are chosen so the common case needs no configuration at all:
 * <pre>
 * plugins { id 'io.github.heyuch.groovy-nullchecker' }
 * </pre>
 * then {@code ./gradlew nullcheck}.
 *
 * <p>The run is report-only unless {@link #failOnError} is enabled. That is deliberate:
 * the first thing you want on an existing codebase is the size of the problem, not a
 * broken build. Findings still make the task exit non-zero, and {@code failOnError}
 * decides whether that fails the build.
 */
class NullCheckExtension {

    /** Type-checking extension to run. */
    final Property<String> extension

    /** Enable flow-sensitive analysis (also reports unannotated code). */
    final Property<Boolean> strict

    /** Roots to analyse, relative to the project directory. */
    final ListProperty<String> targets

    /** Where to write the raw report; unset means the default under build/reports. */
    final Property<String> reportFile

    /**
     * Whether the pre-flight self-check runs.
     *
     * <p>Leave this on. The self-check compiles a deliberately broken sample and refuses
     * to report a result if the checker cannot see it, which is what stops a silently
     * broken invocation from being read as "no problems found".
     */
    final Property<Boolean> canary

    /** Whether findings should fail the build. */
    final Property<Boolean> failOnError

    /**
     * Finding categories that make {@code nullcheck} fail, as a non-zero exit.
     *
     * <p>Defaults to {@code ['null', 'initialization']}. A {@code resolution} finding means
     * Groovy's static checker could not resolve something in code that is dispatched
     * dynamically at runtime; it is reported in the output but does not fail the run,
     * because it says nothing about nullness. Use {@code ['all']} for the strictest
     * behaviour, or {@code ['null']} to ignore uninitialised-field reports too.
     */
    final ListProperty<String> failOn

    /**
     * Guard methods that assert their first argument is non-null when they return true,
     * as {@code com.acme.Utils#isNotEmpty}.
     *
     * <p>Only the first argument is considered, and the class must match exactly. The
     * polarity is part of the declaration because it cannot be read off the name; getting
     * it wrong would hide a real finding instead of adding noise.
     */
    /**
     * Whether the nullness rules run: dereference, return, assignment, argument.
     *
     * <p>On by default. Turning it off leaves only the initialisation rule, which is useful
     * when auditing that one rule on its own and almost never otherwise.
     */
    final Property<Boolean> nullnessCheck

    /**
     * Whether the definite-initialisation rule runs: an explicitly non-null field that no
     * constructor assigns.
     *
     * <p>On by default. Turning it off stops those findings being reported at all, which is
     * different from {@link #failOn} deciding what fails. A project whose fields are assigned
     * by a container -- Spring's {@code @Autowired}, an ORM, a mapper -- has decided not to
     * satisfy this rule yet; before this switch it had to learn to ignore the wall of
     * findings instead.
     */
    final Property<Boolean> initializationCheck

    final ListProperty<String> guardNotNullWhenTrue

    /**
     * Guard methods that assert their first argument is non-null when they return false,
     * as {@code com.acme.MyCollectionUtils#isNullOrEmpty}.
     */
    final ListProperty<String> guardNotNullWhenFalse

    /**
     * The checker version to use.
     *
     * <p>Resolved from the project's repositories, so {@code mavenLocal()} must be among
     * them until the artifact is on a public repository.
     */
    final Property<String> toolVersion

    NullCheckExtension(Project project) {
        this.extension = project.objects.property(String)
        this.strict = project.objects.property(Boolean)
        this.targets = project.objects.listProperty(String)
        this.reportFile = project.objects.property(String)
        this.canary = project.objects.property(Boolean)
        this.failOnError = project.objects.property(Boolean)
        this.failOn = project.objects.listProperty(String)
        this.nullnessCheck = project.objects.property(Boolean)
        this.initializationCheck = project.objects.property(Boolean)
        this.guardNotNullWhenTrue = project.objects.listProperty(String)
        this.guardNotNullWhenFalse = project.objects.listProperty(String)
        this.toolVersion = project.objects.property(String)

        extension.convention(NullCheckPlugin.DEFAULT_EXTENSION)
        strict.convention(false)
        // Both source roots by default: package-info.java lives in the Java one, and a
        // package-level default is invisible without it.
        targets.convention(['src/main/groovy', 'src/main/java'])
        canary.convention(true)
        failOnError.convention(false)
        failOn.convention(['null', 'initialization'])
        nullnessCheck.convention(true)
        initializationCheck.convention(true)
        guardNotNullWhenTrue.convention([])
        guardNotNullWhenFalse.convention([])
        toolVersion.convention(NullCheckPlugin.DEFAULT_TOOL_VERSION)
    }
}
