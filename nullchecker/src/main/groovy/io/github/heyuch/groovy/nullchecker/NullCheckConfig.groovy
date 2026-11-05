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

    private static final Set<String> KNOWN_CHECKS =
            [CHECK_NULL, CHECK_INITIALIZATION, CHECKS_ALL] as Set<String>

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

    static final String GUARDS_TRUE_PROPERTY = 'groovy.nullchecker.guards.true'
    static final String GUARDS_FALSE_PROPERTY = 'groovy.nullchecker.guards.false'
    static final String NEVER_RETURNS_PROPERTY = 'groovy.nullchecker.neverreturns'
    static final String CONTRACTS_PROPERTY = 'groovy.nullchecker.contracts'
    static final String ASSERTS_TRUE_PROPERTY = 'groovy.nullchecker.assertsTrue'
    static final String CLASSPATH_PROPERTY = 'groovy.nullchecker.classpath'

    final Set<String> nonNullAnnotations
    final Set<String> guardsTrue
    final Set<String> guardsFalse
    final Set<String> neverReturns
    final Set<String> assertsTrue
    final Map<String, String> contracts
    final String classpath

    NullCheckConfig(boolean strict, boolean nullnessCheck, boolean initializationCheck,
                    boolean containerLookups, boolean markedOnly) {
        this(strict, nullnessCheck, initializationCheck, containerLookups, markedOnly,
                new HashSet<String>(AstSupport.NONNULL_ANNOS), Collections.<String> emptySet(),
                Collections.<String> emptySet(), Collections.<String> emptySet(),
                Collections.<String> emptySet(), Collections.<String, String> emptyMap(), '')
    }

    NullCheckConfig(boolean strict, boolean nullnessCheck, boolean initializationCheck,
                    boolean containerLookups, boolean markedOnly, Set<String> nonNullAnnotations,
                    Set<String> guardsTrue, Set<String> guardsFalse, Set<String> neverReturns,
                    Set<String> assertsTrue, Map<String, String> contracts, String classpath) {
        this.strict = strict
        this.nullnessCheck = nullnessCheck
        this.initializationCheck = initializationCheck
        this.containerLookups = containerLookups
        this.markedOnly = markedOnly
        this.nonNullAnnotations = Collections.unmodifiableSet(new HashSet<String>(nonNullAnnotations))
        this.guardsTrue = Collections.unmodifiableSet(new HashSet<String>(guardsTrue))
        this.guardsFalse = Collections.unmodifiableSet(new HashSet<String>(guardsFalse))
        this.neverReturns = Collections.unmodifiableSet(new HashSet<String>(neverReturns))
        this.assertsTrue = Collections.unmodifiableSet(new HashSet<String>(assertsTrue))
        this.contracts = Collections.unmodifiableMap(new LinkedHashMap<String, String>(contracts))
        this.classpath = classpath ?: ''
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
                checks.contains(CHECK_INITIALIZATION), containerLookups, markedOnly,
                readNonNullAnnotations(), readGuardEntries(GUARDS_TRUE_PROPERTY),
                readGuardEntries(GUARDS_FALSE_PROPERTY), readGuardEntries(NEVER_RETURNS_PROPERTY),
                readGuardEntries(ASSERTS_TRUE_PROPERTY), readContractEntries(),
                System.getProperty(CLASSPATH_PROPERTY, ''))
    }

    /**
     * Both families are on by default; {@code all} means neither is filtered out. Unknown
     * names are rejected here, including the bare-checker path that does not pass through the
     * CLI/plugin's validation.
     */
    private static Set<String> readNonNullAnnotations() {
        Set<String> names = new HashSet<String>(AstSupport.NONNULL_ANNOS)
        String extra = System.getProperty(AstSupport.NONNULL_ANNOTATIONS_PROPERTY)
        if (extra) {
            for (String part : extra.split(',')) {
                String value = part.trim()
                if (value) {
                    names.add(value.substring(value.lastIndexOf('.') + 1))
                }
            }
        }
        return names
    }

    private static Set<String> readGuardEntries(String property) {
        String raw = System.getProperty(property)
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.<String> emptySet()
        }
        Set<String> entries = new HashSet<String>()
        for (String part : raw.split(',')) {
            String entry = part.trim()
            if (!entry.isEmpty()) {
                entries.add(entry)
            }
        }
        return entries
    }

    private static Map<String, String> readContractEntries() {
        Map<String, String> entries = new LinkedHashMap<String, String>()
        String raw = System.getProperty(CONTRACTS_PROPERTY)
        if (raw == null) {
            return entries
        }
        for (String part : raw.split(';')) {
            String entry = part.trim()
            if (entry.isEmpty()) {
                continue
            }
            int separator = entry.indexOf('=')
            if (separator <= 0) {
                continue
            }
            entries.put(entry.substring(0, separator).trim(), entry.substring(separator + 1).trim())
        }
        return entries
    }

    private static Set<String> readChecks() {
        String raw = System.getProperty(CHECKS_PROPERTY)
        if (raw == null || raw.trim().isEmpty()) {
            return [CHECK_NULL, CHECK_INITIALIZATION] as Set<String>
        }
        Set<String> enabled = new HashSet<String>()
        for (String part : raw.split(',')) {
            String name = part.trim().toLowerCase(Locale.ROOT)
            if (name.isEmpty()) {
                continue
            }
            if (!KNOWN_CHECKS.contains(name)) {
                throw new IllegalArgumentException(
                        "unknown value in ${CHECKS_PROPERTY}: '${name}'; expected " +
                                'null, initialization or all')
            }
            enabled.add(name)
        }
        if (enabled.isEmpty() || enabled.contains(CHECKS_ALL)) {
            return [CHECK_NULL, CHECK_INITIALIZATION] as Set<String>
        }
        return enabled
    }

}
