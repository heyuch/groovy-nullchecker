package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

/**
 * The checker's settings for one compile: strict mode, which rule families run, and whether
 * container lookups are nullable.
 *
 * <p>Groovy 4 cannot pass parameters through a type-checking extension name, so the values come
 * from three out-of-band channels: the programmatic options map, a system property, and (for
 * strict) an environment variable. Reading them once produces an immutable object, so the
 * checker and its collaborators do not each reinterpret the properties.
 */
@CompileStatic
@PackageScope
class NullCheckConfig {

    static final String CHECK_NULL = 'null'

    static final String CHECK_INITIALIZATION = 'initialization'

    static final String CHECKS_ALL = 'all'

    static final String CHECKS_PROPERTY = 'groovy.nullchecker.checks'

    static final String STRICT_PROPERTY = 'groovy.nullchecker.strict'

    static final String STRICT_ENV = 'GROOVY_NULLCHECKER_STRICT'

    static final String CONTAINER_LOOKUPS_PROPERTY = 'groovy.nullchecker.containerlookups'

    static final String MARKED_ONLY_PROPERTY = 'groovy.nullchecker.markedonly'

    final boolean strict

    final boolean nullnessCheck

    final boolean initializationCheck

    final boolean containerLookups

    /** When true, only @NullMarked classes/packages are analysed. */
    final boolean markedOnly

    NullCheckConfig(boolean strict, boolean nullnessCheck, boolean initializationCheck,
                    boolean containerLookups, boolean markedOnly) {
        this.strict = strict
        this.nullnessCheck = nullnessCheck
        this.initializationCheck = initializationCheck
        this.containerLookups = containerLookups
        this.markedOnly = markedOnly
    }

    static NullCheckConfig read(Map<String, Object> options) {
        boolean strict = options?.strict == true
                || 'true'.equalsIgnoreCase(System.getProperty(STRICT_PROPERTY))
                || 'true'.equalsIgnoreCase(System.getenv(STRICT_ENV))
        Set<String> checks = readChecks()
        boolean containerLookups = !'false'.equalsIgnoreCase(
                System.getProperty(CONTAINER_LOOKUPS_PROPERTY, 'true'))
        boolean markedOnly = options?.markedOnly == true ||
                'true'.equalsIgnoreCase(System.getProperty(MARKED_ONLY_PROPERTY))
        return new NullCheckConfig(strict, checks.contains(CHECK_NULL),
                checks.contains(CHECK_INITIALIZATION), containerLookups, markedOnly)
    }

    /**
     * Both families are on by default; {@code all} means neither is filtered out. An unknown
     * name disables the known ones, which the CLI rejects before it gets here.
     */
    private static Set<String> readChecks() {
        String raw = System.getProperty(CHECKS_PROPERTY)
        if (raw == null || raw.trim().isEmpty()) {
            return [CHECK_NULL, CHECK_INITIALIZATION] as Set<String>
        }
        Set<String> enabled = new HashSet<String>()
        for (String part : raw.split(',')) {
            String name = part.trim().toLowerCase(Locale.ROOT)
            if (!name.isEmpty()) {
                enabled.add(name)
            }
        }
        if (enabled.isEmpty() || enabled.contains(CHECKS_ALL)) {
            return [CHECK_NULL, CHECK_INITIALIZATION] as Set<String>
        }
        return enabled
    }

}
