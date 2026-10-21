/*
 * PORT NOTE ------------------------------------------------------------------
 * Back-ported to Groovy 4.0.x from Apache Groovy (tag GROOVY_6_0_0):
 *   subprojects/groovy-typecheckers/src/main/groovy/groovy/typecheckers/NullChecker.groovy
 *   sha256 0ed46916b516157883d1fb593eb1891f66c7839a926966f3904ef8e5837c3e0b
 *
 * Deviation from upstream is confined to the points marked "PORT" below; no
 * analysis logic was rewritten. See PORT.md for the full diff and rationale.
 * ---------------------------------------------------------------------------
 */
/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package io.github.heyuch.groovy.nullchecker

import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BooleanExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NotExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.AssertStatement
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.SynchronizedStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.BreakStatement
import org.codehaus.groovy.ast.stmt.ContinueStatement
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.ast.stmt.ThrowStatement
import org.codehaus.groovy.ast.stmt.WhileStatement
import org.codehaus.groovy.syntax.Types
import org.codehaus.groovy.transform.stc.GroovyTypeCheckingExtensionSupport
import org.codehaus.groovy.transform.stc.StaticTypesMarker

import static io.github.heyuch.groovy.nullchecker.AstSupport.*
import static org.codehaus.groovy.ast.ClassHelper.VOID_TYPE
import static org.codehaus.groovy.ast.ClassHelper.isPrimitiveType
import static org.codehaus.groovy.syntax.Types.isAssignment

/**
 * A compile-time type checker that detects potential null dereferences and null-safety violations
 * in code annotated with {@code @Nullable}, {@code @NonNull}, and {@code @MonotonicNonNull} annotations.
 * <p>
 * By default, this checker performs annotation-based null checking only. For additional flow-sensitive
 * analysis that tracks nullability through assignments and control flow (even in unannotated code),
 * enable the {@code strict} option:
 * <pre>
 * {@code @TypeChecked(extensions = 'io.github.heyuch.groovy.nullchecker.NullChecker')}
 * </pre>
 * <p>
 * Supported annotations are recognized by simple name from any package:
 * <ul>
 *     <li>Nullable: {@code @Nullable}, {@code @CheckForNull}, {@code @MonotonicNonNull}</li>
 *     <li>Non-null: {@code @NonNull}, {@code @NotNull}, {@code @Nonnull}</li>
 * </ul>
 * <p>
 * Detected errors include:
 * <ul>
 *     <li>Assigning {@code null} to a {@code @NonNull} variable</li>
 *     <li>Passing {@code null} or a {@code @Nullable} value to a {@code @NonNull} parameter</li>
 *     <li>Returning {@code null} or a {@code @Nullable} value from a {@code @NonNull} method</li>
 *     <li>Dereferencing a {@code @Nullable} variable without a null check or safe navigation ({@code ?.})</li>
 *     <li>Dereferencing the result of a {@code @Nullable}-returning method without a null check,
 *         whether called explicitly ({@code x.getA().b}) or via property syntax ({@code x.a.b})</li>
 *     <li>Dereferencing a result whose inferred type carries a {@code @Nullable} type
 *         argument reaching it through a class or method level type variable, e.g.
 *         {@code get(0)}, the idiomatic {@code xs[0]} subscript, or {@code head()} on a
 *         {@code List<@Nullable String>}, and {@code get(key)} or {@code m[key]} on a
 *         {@code Map<String, @Nullable Integer>}</li>
 *     <li>Dereferencing a safe-navigation result without a further guard ({@code a?.b.c};
 *         use {@code a?.b?.c} instead)</li>
 *     <li>Passing other nullable expressions (safe-navigation results, {@code @Nullable}-returning
 *         calls, ternaries with a nullable branch) to {@code @NonNull} parameters, or returning
 *         them from {@code @NonNull} methods</li>
 *     <li>Re-assigning {@code null} to a {@code @MonotonicNonNull} field after initialization</li>
 *     <li>Dereferencing a variable known to be null through flow analysis ({@code strict} mode only)</li>
 *     <li>An explicitly-annotated {@code @NonNull} instance field that is not definitely
 *         initialized — at its declaration, in an instance initializer block, or by every
 *         declared constructor ({@code strict} mode only)</li>
 * </ul>
 * <p>
 * The checker recognizes a range of null-guard patterns:
 * <ul>
 *     <li>Null comparisons: {@code if (x != null)}, {@code if (x == null)}</li>
 *     <li>Early exit patterns: {@code if (x == null) return/throw}</li>
 *     <li>Safe navigation: {@code ?.}</li>
 *     <li>Groovy-truth guards: {@code if (x)}, {@code if (!x) return}</li>
 *     <li>Boolean conjunctions and disjunctions with short-circuit semantics:
 *         {@code if (x != null && x.length() > 0)}, {@code if (x == null || x.isEmpty()) return}</li>
 *     <li>Type checks: {@code if (x instanceof Foo)}, {@code if (x !instanceof Foo) return}</li>
 *     <li>Utility methods: {@code Objects.nonNull(x)}, {@code Objects.isNull(x)}</li>
 *     <li>Assert statements: {@code assert x}, {@code assert x != null}</li>
 *     <li>Guard conditions of while loops and ternary expressions</li>
 *     <li>Validator methods which throw on null, so their argument is non-null
 *         afterwards: {@code Objects.requireNonNull(x)} and Guava-style {@code checkNotNull(x)}</li>
 *     <li>Test assertions: {@code assertNotNull(x)} (JUnit 4/5, TestNG, or similar —
 *         message parameters are recognized in any position) and fluent
 *         {@code assertThat(x).isNotNull()} chains (AssertJ, Truth, or similar)</li>
 * </ul>
 * Like annotations, validator and assertion methods are matched by simple name,
 * so any library following the common naming conventions is recognized.
 *
 * <pre>
 * {@code @TypeChecked(extensions = 'io.github.heyuch.groovy.nullchecker.NullChecker')}
 * void process(@Nullable String input) {
 *     // input.length()     // error: potential null dereference
 *     input?.length()       // ok: safe navigation
 *     if (input != null) {
 *         input.length()    // ok: null guard
 *     }
 * }
 * </pre>
 *
 * Over time, the idea would be to support more cases as per:
 * https://checkerframework.org/manual/#nullness-checker
 *
 * @since 6.0.0
 */
class NullChecker extends GroovyTypeCheckingExtensionSupport.TypeCheckingDSL {

    /** Static-compilation markers, counted for coverage only. */
    private static final Set<String> COMPILE_STATIC_ANNOS = Collections.unmodifiableSet(['CompileStatic', 'CompileDynamic'] as Set)
    /** Rule family: nullness of values (dereference, return, assignment, argument). */
    static final String CHECK_NULL = 'null'

    /** Rule family: an explicitly non-null field no constructor assigns. */
    static final String CHECK_INITIALIZATION = 'initialization'

    /** The value of {@link #CHECKS_PROPERTY} that enables every family. */
    static final String CHECKS_ALL = 'all'

    /** Comma-separated rule families to run; absent means {@link #CHECKS_ALL}. */
    static final String CHECKS_PROPERTY = 'groovy.nullchecker.checks'

    private static Set<String> PORT_GUARDS_TRUE = Collections.emptySet()

    private static Set<String> PORT_GUARDS_FALSE = Collections.emptySet()

    private static Set<String> PORT_NEVER_RETURNS = Collections.emptySet()

    /** Whether container lookups that can miss are nullable. */
    private static final String CONTAINER_LOOKUPS_PROPERTY = 'groovy.nullchecker.containerlookups'

    private static Map<String, Contract> PORT_CONFIG_CONTRACTS = [:]

    private static final Map<MethodNode, Contract> PORT_CONTRACT_CACHE = new IdentityHashMap<>()

    private static boolean PORT_CONTAINER_LOOKUPS = true

    /** Packages whose constraints imply non-null but not "assigned in source". */
    private static final Set<String> VALIDATION_ANNO_PACKAGES = Collections.unmodifiableSet([
            'jakarta.validation.constraints',
            'javax.validation.constraints',
            'org.hibernate.validator.constraints'] as Set)

    /**
     * PORT: back-port of Groovy 6's extension parameters ({@code getOptions()}).
     * <p>
     * Groovy 4 cannot pass {@code 'Ext(strict: true)'} through
     * {@code @TypeChecked(extensions = ...)}: its
     * {@code GroovyTypeCheckingExtensionSupport.setup()} calls {@code loadClass()}
     * on the WHOLE string, so a parameterised name is never found. Groovy 6 added
     * {@code parseParameters()}/{@code getOptions()}/{@code resolvedPath} for this;
     * Groovy 4 has none of them and hard-codes the support class, so a shim
     * subclass is never consulted either. Options are therefore supplied out of
     * band, via {@link #setOptions(Map)} or the strict system property.
     */
    private static final Map<String, Object> PORT_OPTIONS = [:]

    /** System property that enables {@code strict} mode process-wide. */
    static final String STRICT_PROPERTY = 'groovy.nullchecker.strict'

    /**
     * Environment variable that enables {@code strict} mode process-wide.
     *
     * <p>Exists because a Gradle build cannot always pass a system property to the
     * compiler JVM, while the environment reaches it either way. That makes this the
     * practical channel for a CI job: turn strict on for the whole build without
     * editing any build file.
     */
    static final String STRICT_ENV = 'GROOVY_NULLCHECKER_STRICT'

    /**
     * Replaces the extension options, mirroring Groovy 6's options map.
     * Pass {@code [strict: true]} to enable flow-sensitive analysis.
     */
    static void setOptions(Map<String, Object> options) {
        PORT_OPTIONS.clear()
        if (options) {
            PORT_OPTIONS.putAll(options)
        }
    }

    /** Mirrors Groovy 6's {@code getOptions()}. */
    Map<String, Object> getOptions() {
        PORT_OPTIONS
    }

    /** Drops any options set by a previous compilation. */
    static void clearOptions() {
        PORT_OPTIONS.clear()
    }

    /** Drops per-compilation caches, so a new compilation cannot see a stale answer. */
    static void resetCaches() {
        PORT_CACHE_UNIT = null
        scopes = null
        AstSupport.resetCaches()
        contracts = new ContractRegistry()
    }

    /**
     * Reads the {@code strict} flag the way Groovy 6's {@code options?.strict} would.
     *
     * <p>Because Groovy 4 cannot pass {@code 'Ext(strict: true)'}, three out-of-band
     * channels are accepted, in order of specificity: {@link #setOptions(Map)} for
     * programmatic use, then the {@link #STRICT_PROPERTY} system property, then the
     * {@link #STRICT_ENV} environment variable for build/CI-wide switches.
     */
    private static boolean isStrictEnabled() {
        if (PORT_OPTIONS.strict == true) {
            return true
        }
        if (Boolean.getBoolean(STRICT_PROPERTY)) {
            return true
        }
        'true'.equalsIgnoreCase(java.lang.System.getenv(STRICT_ENV))
    }

    /**
     * Registers null-safety checks for each visited method body and, in strict mode,
     * definite-initialization checks for {@code @NonNull} fields of each visited class.
     */
    @Override
    Object run() {
        boolean strict = isStrictEnabled()
        PORT_CONTAINER_LOOKUPS = !'false'.equalsIgnoreCase(
                System.getProperty(CONTAINER_LOOKUPS_PROPERTY, 'true'))
        def unit = context?.compilationUnit
        if (unit != null && !unit.is(PORT_CACHE_UNIT)) {
            PORT_CACHE_UNIT = unit
            scopes = new PackageScopes(unit, context?.source?.classLoader)
            AstSupport.resetCaches()
            contracts = new ContractRegistry()
        } else if (unit == null) {
            scopes = new PackageScopes(null, context?.source?.classLoader)
            contracts = new ContractRegistry()
        }
        // Coverage is deliberately NOT reset here. Groovy instantiates this DSL once per
        // checked class -- measured: 50 run() calls for 43 sources -- so resetting in
        // run() would leave the counters describing only the last class seen (observed
        // as "classes=1" for a 43-file project). The caller resets once per compilation
        // via resetCoverage() instead.
        afterVisitClass { ClassNode cn ->
            guarded("coverage of ${cn.name}") {
                countCovered(cn)
            }
        }
        // Which rule families run is a caller decision, not a property of the code being
        // checked: a project that cannot satisfy a family yet should stop seeing it rather
        // than learn to ignore a wall of findings it has decided not to act on.
        if (isCheckEnabled(CHECK_NULL)) {
            afterVisitMethod { MethodNode method ->
                guarded("method ${method.declaringClass?.name}#${method.name}") {
                    method.code?.visit(makeVisitor(strict, method))
                }
            }
        }
        if (strict && isCheckEnabled(CHECK_INITIALIZATION)) {
            afterVisitClass { ClassNode cn ->
                guarded("initialization of ${cn.name}") {
                    checkNonNullFieldInitialization(cn)
                }
            }
        }
    }

    /**
     * Whether one rule family is enabled.
     *
     * <p>Both are on unless the caller says otherwise. An unknown name is treated as
     * enabled by the checker; the CLI validates the value and rejects a typo, because a
     * silently disabled check produces a zero that looks like a clean result.
     */
    private static boolean isCheckEnabled(String check) {
        String raw = System.getProperty(CHECKS_PROPERTY)
        if (raw == null || raw.trim().isEmpty()) {
            return true
        }
        Set<String> enabled = new HashSet<String>()
        for (String part : raw.split(',')) {
            String name = part.trim().toLowerCase()
            if (!name.isEmpty()) {
                enabled.add(name)
            }
        }
        enabled.isEmpty() || enabled.contains(check) || enabled.contains(CHECKS_ALL)
    }

    /**
     * Runs one unit of checking, recording a failure instead of letting it escape.
     *
     * <p>An exception thrown from inside the static type checker does not reach the caller:
     * Groovy catches it per class, so the compilation completes and the tool reports the
     * findings it happened to collect -- typically none. Observed directly: a checker bug
     * turned a class that should report into {@code findings = 0} with a green build, and
     * even the canary passed, because the canary's own sample never took the failing path.
     *
     * <p>The failure is therefore recorded and the rest of the classes are still checked;
     * the harness turns any recorded failure into a hard error. Continuing keeps the report
     * useful for diagnosis, while failing closed keeps a partial result from being read as
     * a clean one.
     */
    private void guarded(String where, Closure body) {
        try {
            body.call()
        } catch (Throwable failure) {
            recordInternalError(where, failure)
        }
    }

    /** Internal failures recorded for the current thread, oldest first. */
    private static final ThreadLocal<List<String>> INTERNAL_ERRORS = new ThreadLocal<List<String>>()

    /** Cap on recorded failures: the first few explain the problem, the rest do not. */
    private static final int MAX_INTERNAL_ERRORS = 10

    /** Records a failure of the checker itself; see {@link #guarded}. */
    private static void recordInternalError(String where, Throwable failure) {
        List<String> errors = INTERNAL_ERRORS.get()
        if (errors == null) {
            errors = new ArrayList<String>()
            INTERNAL_ERRORS.set(errors)
        }
        if (errors.size() < MAX_INTERNAL_ERRORS) {
            errors.add(where + ': ' + failure.getClass().name + ': ' + failure.message)
        }
    }

    /**
     * Failures recorded since the last {@link #resetInternalErrors}.
     *
     * <p>A non-empty list means the analysis was incomplete, so its finding count must not
     * be trusted; callers are expected to fail rather than report.
     */
    static List<String> getInternalErrors() {
        List<String> errors = INTERNAL_ERRORS.get()
        if (errors == null) {
            return new ArrayList<String>()
        }
        return new ArrayList<String>(errors)
    }

    /** Clears recorded failures so one run cannot inherit another's. */
    static void resetInternalErrors() {
        INTERNAL_ERRORS.set(new ArrayList<String>())
    }

    /** Coverage observed by the most recent run, so {@code findings = 0} stays readable. */
    private static NullCheckCoverage LAST_COVERAGE = new NullCheckCoverage()

    /**
     * Records what this run actually looked at.
     *
     * <p>The {@code @CompileStatic} count matters most: those are the classes that were
     * silently skipped before {@code NullCheckCustomizer} declared a registration phase,
     * so a non-zero count is the cheapest evidence that they are now reached.
     */
    private void countCovered(ClassNode cn) {
        if (cn.name.endsWith('.package-info')) {
            return // a package declaration, not a class anyone reads for nullness
        }
        LAST_COVERAGE.classes++
        if (hasAnno(cn, COMPILE_STATIC_ANNOS)) {
            LAST_COVERAGE.compileStaticClasses++
        }
        if (isAnyNonNullByDefault(cn)) {
            LAST_COVERAGE.nonNullByDefaultClasses++
        }
        LAST_COVERAGE.methodsWithReturnType += cn.methods.count { MethodNode m ->
            m.returnType != VOID_TYPE
        }
    }

    /** Coverage from the most recent run. */
    static NullCheckCoverage getLastCoverage() {
        LAST_COVERAGE
    }

    /** Clears coverage so a caller can tell "this run saw nothing" from "no run happened". */
    static void resetCoverage() {
        LAST_COVERAGE = new NullCheckCoverage()
    }

    /**
     * Package-level annotations collected up front, keyed by package name.
     *
     * <p>Needed because Groovy 4 gives every <em>source</em> class its own
     * {@code PackageNode}: the annotations from a source {@code package-info.java} or
     * {@code package-info.groovy} land on the package node of the {@code package-info}
     * class only, and the {@code PackageNode} of the classes in that package comes back
     * with an empty annotation list. Reading {@code classNode.package.annotations} --
     * which is correct for precompiled {@code package-info.class} on the classpath --
     * therefore finds nothing for sources in the same compilation.
     *
     * <p>Populated before any class is visited, so the answer does not depend on whether
     * {@code package-info} happens to be compiled before or after its package's classes.
     */
    /**
     * Per-compilation caches for package-annotation lookups.
     *
     * <p>{@link #lookupPackageAnnotations} walks up to four classloaders with
     * {@code Class.forName}, which resolves {@code package-info.class} entries from the
     * classpath. It is called for every scope decision of every class, so without a cache the
     * same package is resolved thousands of times -- measured as the largest single cost in
     * the checker. Both maps are cleared when a new compilation unit is seen.
     */

    private static Object PORT_CACHE_UNIT

    private static ContractRegistry contracts = new ContractRegistry()

    private static PackageScopes scopes

    private CheckingVisitor makeVisitor(boolean flowSensitive, MethodNode method) {
        boolean classNonNullByDefault = method.declaringClass != null &&
            isReturnValueNonNullByDefault(method.declaringClass)
        boolean methodNonNull = method.returnType != VOID_TYPE && (hasNonNullAnno(method) || (classNonNullByDefault && !hasNullableAnno(method)))
        if (methodNonNull) {
            def stash = method.getNodeMetaData('INFERRED_NON_NULL_RETURN_VIOLATIONS' /* PORT: marker absent in Groovy 4 */)
            if (stash instanceof List) {
                stash.each { node ->
                    addStaticTypeError("Cannot return null from @NonNull method '${method.name}'", node)
                }
            }
        }
        def initialNullable = method.parameters.findAll { hasNullableAnno(it) } as Set<Variable>

        new CheckingVisitor() {
            private final Set<Variable> nullableVars = new HashSet<>(initialNullable)
            private final Set<Variable> monotonicInitialized = new HashSet<>()
            private final Set<Variable> guardedVars = new HashSet<>()

            @Override
            void visitDeclarationExpression(DeclarationExpression decl) {
                super.visitDeclarationExpression(decl)
                def ve = decl.variableExpression
                if (ve == null) return
                if (decl.rightExpression instanceof ConstantExpression) {
                    localConstVars.put(ve, decl.rightExpression)
                }
                // PORT: locally declared annotations sit on a different node per Groovy
                // version (see declAnnoCarrier), so query both rather than only 've'.
                def annotated = declAnnoCarrier(decl, ve)
                if (hasNonNullAnno(annotated) && isNullExpr(decl.rightExpression)) {
                    addStaticTypeError("Cannot assign null to @NonNull variable '${ve.name}'", decl)
                }
                // Uninitialized non-primitive declaration (e.g. "String x") is implicitly null
                boolean implicitlyNull = decl.rightExpression instanceof EmptyExpression && !isPrimitiveType(ve.type)
                if (hasNullableAnno(annotated) || isNullExpr(decl.rightExpression) || (flowSensitive && (implicitlyNull || canBeNull(decl.rightExpression) || isKnownNullable(decl.rightExpression)))) {
                    nullableVars.add(ve)
                }
            }

            @Override
            void visitBinaryExpression(BinaryExpression expression) {
                int op = expression.operation.type
                if (op == Types.LOGICAL_AND || op == Types.LOGICAL_OR) {
                    // apply short-circuit guard semantics wherever the expression appears
                    visitCondition(expression)
                    return
                }
                super.visitBinaryExpression(expression)
                if (isAssignment(expression.operation.type) && expression.leftExpression instanceof VariableExpression) {
                    def target = findTargetVariable(expression.leftExpression)
                    boolean fieldNonNull = target instanceof AnnotatedNode && hasNonNullAnno(target)
                    if (!fieldNonNull && target instanceof FieldNode && !hasNullableAnno(target)) {
                        fieldNonNull = target.declaringClass != null &&
                            isFieldNonNullByDefault(target.declaringClass)
                    }
                    if (fieldNonNull && isNullExpr(expression.rightExpression)) {
                        addStaticTypeError("Cannot assign null to @NonNull variable '${expression.leftExpression.name}'", expression)
                    }
                    // @MonotonicNonNull: once initialized with non-null, cannot assign null again
                    if (target instanceof AnnotatedNode && hasMonotonicAnno(target)) {
                        if (!isNullExpr(expression.rightExpression)) {
                            monotonicInitialized.add(target)
                        } else if (monotonicInitialized.contains(target)) {
                            addStaticTypeError("Cannot assign null to @MonotonicNonNull variable '${expression.leftExpression.name}' after non-null assignment", expression)
                        }
                    }
                    if (isNullExpr(expression.rightExpression)) {
                        nullableVars.add(target)
                        guardedVars.remove(target)
                    } else if (flowSensitive && (canBeNull(expression.rightExpression) || isKnownNullable(expression.rightExpression))) {
                        nullableVars.add(target)
                        guardedVars.remove(target)
                    } else {
                        nullableVars.remove(target)
                    }
                }
            }

            @Override
            void visitMethodCallExpression(MethodCallExpression call) {
                // A closure passed to an iteration method receives the collection's element,
                // so when that element is nullable the closure's parameter is too. The hint is
                // pushed before visiting the arguments, because that is when the closure body
                // is walked.
                closureHints.add(closureHint(call))
                try {
                    super.visitMethodCallExpression(call)
                } finally {
                    closureHints.remove(closureHints.size() - 1)
                }
                if (!call.safe && !call.implicitThis) {
                    checkDereference(call.objectExpression, call)
                }
                checkMethodArguments(call)
                applyCallNarrowing(call)
            }

            /**
             * Tracks the values an iteration hands out: a {@code for} loop variable, or a
             * closure parameter of an iteration method.
             *
             * <p>The annotation lives on the collection's type argument, which Groovy does not
             * copy onto the variable it hands out. Recording the variable is therefore the only
             * way to make the existing dereference rules see it, and it keeps the rules in one
             * place instead of adding a parallel check.
             *
             * <p>Iterating a map yields {@code Map.Entry}, so the key and the value are tracked
             * separately: the entry itself is never null, but either half may be.
             */
            private final Set<Variable> nullableElements = new HashSet<>()

            private final Map<Variable, MapEntryTypes> entryVariables = new LinkedHashMap<>()

            /** What the call being visited passes to its closure; innermost last. */
            private final List<ClosureHint> closureHints = new ArrayList<>()

            /** What the closure body currently being visited receives; innermost last. */
            private final List<ClosureHint> closureContexts = new ArrayList<>()

            /** Iteration methods whose first closure parameter is the element. */
            private static final Set<String> ELEMENT_ITERATION_METHODS = [
                    'each', 'collect', 'collectMany', 'collectEntries', 'find', 'findAll', 'findResult',
                    'any', 'every', 'eachWithIndex', 'count', 'sum', 'groupBy', 'unique',
                    'takeWhile', 'dropWhile', 'sort'] as Set<String>

            @Override
            void visitForLoop(ForStatement statement) {
                Parameter loopVar = statement.variable
                ClassNode collectionType = typeOfExpression(statement.collectionExpression)
                MapEntryTypes entry = mapEntryTypes(collectionType)
                boolean elementNullable = entry == null && hasNullableElementType(collectionType)
                if (loopVar != null && entry != null) {
                    entryVariables.put(loopVar, entry)
                }
                if (loopVar != null && elementNullable) {
                    nullableElements.add(loopVar)
                }
                try {
                    super.visitForLoop(statement)
                } finally {
                    if (loopVar != null) {
                        entryVariables.remove(loopVar)
                        nullableElements.remove(loopVar)
                    }
                }
            }

            @Override
            void visitClosureExpression(ClosureExpression expression) {
                ClosureHint hint = currentHint(closureHints)
                Set<Variable> savedElements = new HashSet<>(nullableElements)
                Map<Variable, MapEntryTypes> savedEntries = new LinkedHashMap<>(entryVariables)
                Parameter[] parameters = expression.parameters
                int count = parameters == null ? 0 : parameters.length
                if (hint != null && hint.entryTypes != null) {
                    if (hint.pairwise) {
                        // each / eachWithIndex hand out (key, value[, index])
                        if (count > 0 && hasNullableTypeUse(hint.entryTypes.keyType)) {
                            nullableElements.add(parameters[0])
                        }
                        if (count > 1 && hasNullableTypeUse(hint.entryTypes.valueType)) {
                            nullableElements.add(parameters[1])
                        }
                    } else if (count > 0) {
                        // collect / find / findAll hand out the entry itself
                        entryVariables.put(parameters[0], hint.entryTypes)
                    }
                } else if (hint != null && hint.elementNullable && count > 0) {
                    nullableElements.add(parameters[0])
                    // sort's comparator receives two elements, both of them elements
                    if (count > 1) {
                        nullableElements.add(parameters[1])
                    }
                }
                closureContexts.add(hint)
                try {
                    super.visitClosureExpression(expression)
                } finally {
                    closureContexts.remove(closureContexts.size() - 1)
                    nullableElements.clear()
                    nullableElements.addAll(savedElements)
                    entryVariables.clear()
                    entryVariables.putAll(savedEntries)
                }
            }

            /** What the call hands to its closure, if anything. */
            private ClosureHint closureHint(MethodCallExpression call) {
                ClosureHint hint = new ClosureHint()
                ClassNode receiver = typeOfExpression(call.objectExpression)
                MapEntryTypes entry = mapEntryTypes(receiver)
                if (entry != null) {
                    String name = call.methodAsString
                    if (name in ['each', 'eachWithIndex']) {
                        hint.entryTypes = entry
                        hint.pairwise = true
                    } else if (name in ELEMENT_ITERATION_METHODS) {
                        hint.entryTypes = entry
                    }
                    return hint
                }
                if (call.methodAsString in ELEMENT_ITERATION_METHODS && hasNullableElementType(receiver)) {
                    hint.elementNullable = true
                }
                hint
            }

            private static ClosureHint currentHint(List<ClosureHint> stack) {
                stack.isEmpty() ? null : stack.get(stack.size() - 1)
            }

            /** Whether a variable is an element of a collection whose element type is nullable. */
            private boolean isNullableElement(VariableExpression receiver, Variable target) {
                if (nullableElements.contains(target)) return true
                ClosureHint hint = currentHint(closureContexts)
                receiver.name == 'it' && hint != null && hint.elementNullable
            }

            /** The declared type of {@code entry.key} / {@code entry.value}, when known. */
            private ClassNode entryMemberType(Expression receiver, String member) {
                MapEntryTypes types = null
                if (receiver instanceof VariableExpression) {
                    VariableExpression variable = (VariableExpression) receiver
                    if (variable.name == 'it') {
                        ClosureHint hint = currentHint(closureContexts)
                        if (hint != null && hint.entryTypes != null && !hint.pairwise) {
                            types = hint.entryTypes
                        }
                    } else {
                        types = entryVariables.get(findTargetVariable(variable))
                    }
                }
                if (types == null) return null
                if (member == 'key') return types.keyType
                if (member == 'value') return types.valueType
                null
            }

            private boolean isNullableEntryProperty(PropertyExpression expression) {
                hasNullableTypeUse(entryMemberType(expression.objectExpression, expression.propertyAsString))
            }

            /** Whether a call is {@code entry.getKey()} / {@code entry.getValue()} on a nullable half. */
            private boolean isNullableEntryCall(Expression call) {
                if (!(call instanceof MethodCallExpression)) return false
                MethodCallExpression entryCall = (MethodCallExpression) call
                if (entryCall.methodAsString == 'getKey') {
                    return hasNullableTypeUse(entryMemberType(entryCall.objectExpression, 'key'))
                }
                if (entryCall.methodAsString == 'getValue') {
                    return hasNullableTypeUse(entryMemberType(entryCall.objectExpression, 'value'))
                }
                false
            }

            @Override
            void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                super.visitStaticMethodCallExpression(call)
                checkMethodArguments(call)
                applyCallNarrowing(call)
            }

            @Override
            void visitPropertyExpression(PropertyExpression expression) {
                super.visitPropertyExpression(expression)
                if (!expression.safe) {
                    checkDereference(expression.objectExpression, expression)
                }
            }

            @Override
            /**
             * Stops walking a block once a statement cannot complete normally: what follows is
             * unreachable, and reporting it would be reporting code that never runs.
             */
            @Override
            void visitBlockStatement(BlockStatement block) {
                for (Statement statement : block.statements) {
                    statement.visit(this)
                    if (cannotCompleteNormally(statement)) return
                }
            }

            void visitReturnStatement(ReturnStatement statement) {
                super.visitReturnStatement(statement)
                if (methodNonNull) {
                    if (isNullExpr(statement.expression)) {
                        addStaticTypeError("Cannot return null from @NonNull method '${method.name}'", statement)
                    } else if (isKnownNullable(statement.expression)) {
                        addStaticTypeError("Cannot return @Nullable value from @NonNull method '${method.name}'", statement)
                    }
                }
            }

            @Override
            void visitIfElse(IfStatement ifElse) {
                def facts = visitCondition(ifElse.booleanExpression.expression)
                withGuards(facts.whenTrue) {
                    withLookupGuards(facts.lookupsTrue) { ifElse.ifBlock.visit(this) }
                }
                withGuards(facts.whenFalse) {
                    withLookupGuards(facts.lookupsFalse) { ifElse.elseBlock.visit(this) }
                }
                // Early exit: if (x == null) return/throw → x is non-null after (and vice versa)
                if (isEarlyExit(ifElse.ifBlock)) {
                    applyFacts(facts.whenFalse)
                }
                if (isEarlyExit(ifElse.elseBlock)) {
                    applyFacts(facts.whenTrue)
                }
            }

            @Override
            void visitWhileLoop(WhileStatement loop) {
                def facts = visitCondition(loop.booleanExpression.expression)
                withGuards(facts.whenTrue) {
                    withLookupGuards(facts.lookupsTrue) { loop.loopBlock.visit(this) }
                }
            }

            @Override
            void visitTernaryExpression(TernaryExpression expression) {
                def condition = expression.booleanExpression.expression
                def facts = visitCondition(condition)
                // for elvis, the true expression is the (already visited) condition
                if (!condition.is(expression.trueExpression) && !expression.booleanExpression.is(expression.trueExpression)) {
                    withGuards(facts.whenTrue) {
                        withLookupGuards(facts.lookupsTrue) { expression.trueExpression.visit(this) }
                    }
                }
                withGuards(facts.whenFalse) {
                    withLookupGuards(facts.lookupsFalse) { expression.falseExpression.visit(this) }
                }
            }

            @Override
            void visitAssertStatement(AssertStatement statement) {
                def facts = visitCondition(statement.booleanExpression.expression)
                statement.messageExpression?.visit(this)
                // assert x/assert x != null → x is non-null for the rest of the block
                applyFacts(facts.whenTrue)
            }

            //------------------------------------------------------------------

            /**
             * Visits a condition applying short-circuit guard semantics: in
             * {@code x != null && x.foo()} the right operand is protected by the
             * left operand's null check (and similarly after {@code x == null ||}).
             * Returns the sets of variables known to be non-null when the condition
             * evaluates true and when it evaluates false.
             */
            private GuardFacts visitCondition(Expression condition) {
                analyzeCondition(condition, true)
            }

            /**
             * Analyzes a condition into guard facts, optionally visiting its nodes
             * along the way ({@code visiting: false} re-analyzes an already visited
             * condition, e.g. when judging ternary branch nullness).
             */
            private GuardFacts analyzeCondition(Expression condition, boolean visiting) {
                def facts = new GuardFacts()
                if (condition instanceof NotExpression) {
                    def inner = analyzeCondition(condition.expression, visiting)
                    facts.whenTrue = inner.whenFalse
                    facts.whenFalse = inner.whenTrue
                } else if (condition instanceof BooleanExpression) {
                    facts = analyzeCondition(condition.expression, visiting)
                } else if (condition instanceof BinaryExpression && condition.operation.type == Types.LOGICAL_AND) {
                    def left = analyzeCondition(condition.leftExpression, visiting)
                    def right = visiting
                        ? withGuards(left.whenTrue) {
                            withLookupGuards(left.lookupsTrue) {
                                analyzeCondition(condition.rightExpression, true)
                            }
                        }
                        : analyzeCondition(condition.rightExpression, false)
                    facts.whenTrue = left.whenTrue + right.whenTrue
                    facts.whenFalse = left.whenFalse.intersect(right.whenFalse)
                    facts.lookupsTrue = left.lookupsTrue + right.lookupsTrue
                    facts.lookupsFalse = left.lookupsFalse.intersect(right.lookupsFalse)
                } else if (condition instanceof BinaryExpression && condition.operation.type == Types.LOGICAL_OR) {
                    def left = analyzeCondition(condition.leftExpression, visiting)
                    def right = visiting
                        ? withGuards(left.whenFalse) {
                            withLookupGuards(left.lookupsFalse) {
                                analyzeCondition(condition.rightExpression, true)
                            }
                        }
                        : analyzeCondition(condition.rightExpression, false)
                    facts.whenTrue = left.whenTrue.intersect(right.whenTrue)
                    facts.whenFalse = left.whenFalse + right.whenFalse
                    facts.lookupsTrue = left.lookupsTrue.intersect(right.lookupsTrue)
                    facts.lookupsFalse = left.lookupsFalse + right.lookupsFalse
                } else {
                    if (visiting) {
                        condition.visit(this)
                    }
                    facts = analyzeLeafCondition(condition)
                }
                facts
            }

            /**
             * Analyzes a leaf condition (null comparison, instanceof check, Groovy-truth
             * variable, or {@code Objects.nonNull/isNull} call) into guard facts.
             */
            private GuardFacts analyzeLeafCondition(Expression condition) {
                def facts = new GuardFacts()
                if (condition instanceof BinaryExpression) {
                    int op = condition.operation.type
                    if (op == Types.KEYWORD_INSTANCEOF) {
                        def var = guardableVariable(condition.leftExpression)
                        if (var != null) facts.whenTrue.add(var)
                    } else if (op == Types.COMPARE_NOT_INSTANCEOF) {
                        def var = guardableVariable(condition.leftExpression)
                        if (var != null) facts.whenFalse.add(var)
                    } else {
                        boolean isNotEqual = (op == Types.COMPARE_NOT_EQUAL || op == Types.COMPARE_NOT_IDENTICAL)
                        boolean isEqual = (op == Types.COMPARE_EQUAL || op == Types.COMPARE_IDENTICAL)
                        if (isNotEqual || isEqual) {
                            Variable var = null
                            if (isNullExpr(condition.rightExpression)) {
                                var = guardableVariable(condition.leftExpression)
                            } else if (isNullExpr(condition.leftExpression)) {
                                var = guardableVariable(condition.rightExpression)
                            }
                            if (var != null) (isNotEqual ? facts.whenTrue : facts.whenFalse).add(var)
                        }
                    }
                } else if (condition instanceof VariableExpression) {
                    // Groovy truth: a truthy reference is non-null
                    def var = guardableVariable(condition)
                    if (var != null) facts.whenTrue.add(var)
                } else if (condition instanceof MethodCallExpression || condition instanceof StaticMethodCallExpression) {
                    def target = condition.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
                    if (target instanceof MethodNode) {
                        applyContractGuard(target, condition, facts)
                        applyContainsKeyGuard(condition, facts)
                    }
                }
                facts
            }

            /**
             * Applies a contract to a condition: the arguments it proves non-null in one of the
             * two branches.
             *
             * <p>{@code @EnsuresNonNullIf}, JetBrains' {@code @Contract("null -> true|false")},
             * the project's {@code guardNotNullWhenTrue/False} lists and the built-in
             * {@code Objects.nonNull/isNull} all resolve to the same model, so they narrow
             * identically. References the contract cannot interpret are ignored rather than
             * guessed at.
             */
            private void applyContractGuard(MethodNode target, Expression condition, GuardFacts facts) {
                def callArgs = condition.arguments
                if (!(callArgs instanceof TupleExpression) || !callArgs.expressions) return
                Contract contract = contracts.contractFor(condition, target)
                if (contract == null) return
                for (int index : contract.nonNullWhenTrue) {
                    if (index >= callArgs.expressions.size()) continue
                    addArgumentFact(callArgs.getExpression(index), true, facts)
                }
                for (int index : contract.nonNullWhenFalse) {
                    if (index >= callArgs.expressions.size()) continue
                    addArgumentFact(callArgs.getExpression(index), false, facts)
                }
            }

            private void addArgumentFact(Expression argument, boolean whenTrue, GuardFacts facts) {
                Variable var = guardableVariable(argument)
                if (var == null) return
                if (whenTrue) {
                    facts.whenTrue.add(var)
                } else {
                    facts.whenFalse.add(var)
                }
            }

            private Variable guardableVariable(Expression expr) {
                if (expr instanceof VariableExpression && !expr.isThisExpression() && !expr.isSuperExpression()) {
                    return findTargetVariable(expr)
                }
                null
            }

            private <T> T withGuards(Set<Variable> vars, Closure<T> body) {
                if (!vars) {
                    return body()
                }
                def saved = new HashSet<>(guardedVars)
                guardedVars.addAll(vars)
                def result = body()
                guardedVars.clear()
                guardedVars.addAll(saved)
                result
            }

            private void applyFacts(Set<Variable> vars) {
                for (var in vars) {
                    nullableVars.remove(var)
                    guardedVars.add(var)
                }
            }

            /** Lookup facts proved by {@code containsKey}, scoped to the branch that proved them. */
            private final Deque<Set<String>> lookupGuards = new ArrayDeque<>()

            private <T> T withLookupGuards(Set<String> keys, Closure<T> body) {
                if (!keys) {
                    return body()
                }
                lookupGuards.push(new HashSet<String>(keys))
                try {
                    return body()
                } finally {
                    lookupGuards.pop()
                }
            }

            private boolean isLookupGuarded(Expression container, Expression key) {
                if (lookupGuards.isEmpty()) return false
                String expected = lookupKey(container, key)
                if (expected == null) return false
                for (Set<String> frame : lookupGuards) {
                    if (frame.contains(expected)) return true
                }
                false
            }

            /**
             * Recognises {@code map.containsKey(key)}: a true result proves the key is
             * present, which makes a later {@code get(key)} non-null -- but only when the
             * map's values cannot themselves be null. With nullable values, a present key
             * may still hold an explicit null.
             */
            private void applyContainsKeyGuard(Expression condition, GuardFacts facts) {
                if (!(condition instanceof MethodCallExpression)) return
                if (condition.methodAsString != 'containsKey') return
                if (!isMapLike(typeOfExpression(condition.objectExpression))) return
                def args = condition.arguments
                if (!(args instanceof TupleExpression) || args.expressions.size() != 1) return
                String key = lookupKey(condition.objectExpression, args.getExpression(0))
                if (key != null) {
                    facts.lookupsTrue.add(key)
                }
            }

            /**
             * Whether a call returns null because a lookup can miss: {@code Map.get} and
             * friends, and the collection methods that return no result ({@code find}) or
             * cannot answer for an empty collection ({@code max}, {@code min}).
             *
             * <p>The element type says whether a <em>stored</em> value may be null. These
             * methods are partial regardless of that, which is why the rule lives here rather
             * than being folded into the type argument's default.
             */
            private boolean isNullableContainerLookup(Expression expression) {
                if (!(expression instanceof MethodCallExpression)) return false
                MethodCallExpression call = (MethodCallExpression) expression
                def target = call.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
                if (!(target instanceof MethodNode)) return false
                Contract contract = contracts.contractFor(call, (MethodNode) target)
                if (contract == null) return false
                // The switch turns off the built-in family; an explicit contract still applies.
                if (contract.builtIn && !PORT_CONTAINER_LOOKUPS) return false
                if (contract.result == ResultNullness.NON_NULL) return false
                List<Expression> args = call.arguments instanceof TupleExpression
                        ? ((TupleExpression) call.arguments).expressions
                        : Collections.emptyList()
                if (contract.result == ResultNullness.NULLABLE_IF_ARG_NULLABLE) {
                    int index = contract.resultArgument
                    return index >= 0 && index < args.size() && isKnownNullable(args.get(index))
                }
                if (contract.result == ResultNullness.NULLABLE && contract.presence != null) {
                    int index = contract.presence.argument
                    ClassNode receiver = typeOfExpression(call.objectExpression)
                    if (index < args.size() && hasNonNullMapValue(receiver)
                            && isLookupGuarded(call.objectExpression, args.get(index))) {
                        return false
                    }
                }
                true
            }

            private boolean isNullableMapSubscript(Expression expression) {
                if (!PORT_CONTAINER_LOOKUPS) return false
                if (!isSubscript(expression)) return false
                BinaryExpression subscript = (BinaryExpression) expression
                ClassNode receiver = typeOfExpression(subscript.leftExpression)
                if (!isMapLike(receiver)) return false
                if (!hasNonNullMapValue(receiver)) return true
                !isLookupGuarded(subscript.leftExpression, subscript.rightExpression)
            }

            /**
             * Applies narrowing for calls that guarantee an argument is non-null when
             * they complete normally: {@code Objects.requireNonNull} and Guava-style
             * {@code checkNotNull} throw for a null argument, {@code assertNotNull}
             * fails for one, and a fluent {@code assertThat(x).isNotNull()} chain
             * fails when {@code x} is null. Like annotations, the methods are matched
             * by simple name, so any library following the conventions is recognized.
             */
            private void applyCallNarrowing(call) {
                if (call instanceof MethodCallExpression && call.methodAsString == 'isNotNull') {
                    applyFluentNarrowing(call)
                    return
                }
                def target = call.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
                def args = call.arguments
                if (!(target instanceof MethodNode) || !(args instanceof TupleExpression) || !args.expressions) {
                    return
                }
                // JUnit 4 takes (message, actual) while JUnit 5 and TestNG take (actual, message),
                // so narrow the arguments matching non-message parameters rather than assuming a position
                if (target.name == 'assertNotNull') {
                    def params = target.parameters
                    int limit = Math.min(args.expressions.size(), params.length)
                    for (int i = 0; i < limit; i++) {
                        if (!(params[i].type.name in ASSERTION_MESSAGE_TYPES)) {
                            narrowToNonNull(args.getExpression(i))
                        }
                    }
                    return
                }
                Contract contract = contracts.contractFor(call, (MethodNode) target)
                if (contract == null) return
                for (int index : contract.nonNullAfterReturn) {
                    if (index >= args.expressions.size()) continue
                    narrowToNonNull(args.getExpression(index))
                }
            }

            /**
             * Narrows the value asserted by a fluent {@code assertThat(x)...isNotNull()}
             * chain, looking through any intermediate calls ({@code describedAs}, etc.).
             */
            private void applyFluentNarrowing(MethodCallExpression call) {
                def receiver = call.objectExpression
                while (receiver instanceof MethodCallExpression && receiver.methodAsString != 'assertThat') {
                    receiver = receiver.objectExpression
                }
                boolean assertThat = (receiver instanceof MethodCallExpression && receiver.methodAsString == 'assertThat')
                    || (receiver instanceof StaticMethodCallExpression && receiver.method == 'assertThat')
                if (!assertThat) return
                def args = receiver.arguments
                if (args instanceof TupleExpression && args.expressions.size() == 1) {
                    narrowToNonNull(args.getExpression(0))
                }
            }

            private void narrowToNonNull(Expression expr) {
                def var = guardableVariable(expr)
                if (var != null) {
                    // PORT: upstream uses Set.of(var), which is Java 9+. See the constant block
                    // above for why the Java 8 equivalent is used here anyway. The null case is
                    // already excluded by the guard.
                    applyFacts(Collections.singleton(var))
                }
            }


            private void checkDereference(Expression receiver, Expression context) {
                if (isNullExpr(receiver)) {
                    addStaticTypeError('Cannot dereference null', context)
                    return
                }
                if (receiver instanceof VariableExpression) {
                    if (receiver.isThisExpression() || receiver.isSuperExpression()) return
                    def target = findTargetVariable(receiver)
                    boolean isMonotonicAndInitialized = target instanceof AnnotatedNode && hasMonotonicAnno(target) && monotonicInitialized.contains(target)
                    if (target instanceof AnnotatedNode && hasNullableAnno(target) && !guardedVars.contains(target) && !isMonotonicAndInitialized) {
                        addStaticTypeError("Potential null dereference: '${receiver.name}' is @Nullable", context)
                    } else if (isNullableElement(receiver, target) && !guardedVars.contains(target)) {
                        addStaticTypeError("Potential null dereference: '${receiver.name}' may be null", context)
                    } else if (flowSensitive && nullableVars.contains(target) && !guardedVars.contains(target)) {
                        addStaticTypeError("Potential null dereference: '${receiver.name}' may be null", context)
                    }
                } else if (isSafeNavResult(receiver)) {
                    addStaticTypeError("Potential null dereference: '${receiver.text}' may be null", context)
                } else if (isNullableEntryCall(receiver)) {
                    addStaticTypeError("Potential null dereference: '${receiver.text}' may be null", context)
                } else if (receiver instanceof MethodCallExpression && isNullableContainerLookup(receiver)) {
                    addStaticTypeError("Potential null dereference: '${receiver.methodAsString}()' may return null", context)
                } else if (receiver instanceof MethodCallExpression || receiver instanceof StaticMethodCallExpression) {
                    def targetMethod = receiver.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
                    if (targetMethod instanceof MethodNode && (hasNullableAnno(targetMethod) || inferredNullableTypeUse(receiver))) {
                        addStaticTypeError("Potential null dereference: '${targetMethod.name}()' may return null", context)
                    }
                } else if (isSubscript(receiver)
                        && (inferredNullableTypeUse(receiver) || isNullableMapSubscript(receiver))) {
                    addStaticTypeError("Potential null dereference: '${receiver.text}' may be null", context)
                } else if (receiver instanceof PropertyExpression) {
                    if (nullableProperty(receiver) || isNullableEntryProperty(receiver)) {
                        addStaticTypeError("Potential null dereference: '${receiver.propertyAsString}' may be null", context)
                    }
                } else if (receiver instanceof CastExpression) {
                    // casts are transparent to nullness
                    checkDereference(receiver.expression, context)
                }
            }

            private void checkMethodArguments(call) {
                def target = call.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
                if (!(target instanceof MethodNode)) return
                def args = call.arguments
                if (!(args instanceof TupleExpression)) return
                def params = target.parameters
                // @NullCheck/@ParametersAreNonnullByDefault/@NonNullByDefault on method or class makes non-primitive params effectively @NonNull
                def declaringClass = target.declaringClass
                boolean nullChecked = hasNullCheckAnno(target) || hasNonNullByDefaultAnno(target) ||
                    (declaringClass != null && (hasNullCheckAnno(declaringClass) ||
                            isParameterNonNullByDefault(declaringClass)))
                int limit = Math.min(args.expressions.size(), params.length)
                for (int i = 0; i < limit; i++) {
                    def arg = args.getExpression(i)
                    boolean paramIsNonNull = hasNonNullAnno(params[i]) || (nullChecked && !isPrimitiveType(params[i].type) && !hasNullableAnno(params[i]))
                    if (paramIsNonNull) {
                        if (isNullExpr(arg)) {
                            addStaticTypeError("Cannot pass null to @NonNull parameter '${params[i].name}' of '${target.name}'", call)
                        } else if (isKnownNullable(arg)) {
                            addStaticTypeError("Cannot pass @Nullable value to @NonNull parameter '${params[i].name}' of '${target.name}'", call)
                        }
                    }
                }
            }

            /**
             * Determines whether an expression is known to produce a possibly-null value:
             * a {@code @Nullable} (or flow-inferred nullable) unguarded variable, a safe-navigation
             * result, a {@code @Nullable}-returning method call or property read, or a
             * ternary/elvis with a nullable branch. Casts are transparent to nullness.
             */
            private boolean isKnownNullable(Expression expr) {
                if (expr instanceof VariableExpression) {
                    def target = findTargetVariable(expr)
                    if (guardedVars.contains(target)) return false
                    if (isNullableElement(expr, target)) return true
                    if (target instanceof AnnotatedNode && hasNullableAnno(target)) return true
                    return nullableVars.contains(target)
                }
                if (expr instanceof CastExpression) {
                    return isKnownNullable(expr.expression)
                }
                if (isSafeNavResult(expr)) return true
                if (expr instanceof MethodCallExpression || expr instanceof StaticMethodCallExpression) {
                    if (isNullableEntryCall(expr)) return true
                    if (expr instanceof MethodCallExpression && isNullableContainerLookup(expr)) return true
                    def target = expr.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
                    if (!(target instanceof MethodNode)) return false
                    return hasNullableAnno(target) || inferredNullableTypeUse(expr)
                }
                if (isSubscript(expr)) {
                    return inferredNullableTypeUse(expr) || isNullableMapSubscript(expr)
                }
                if (expr instanceof PropertyExpression) {
                    return nullableProperty(expr) || isNullableEntryProperty(expr)
                }
                if (expr instanceof TernaryExpression) {
                    // judge each branch under the condition's guard facts, so that
                    // e.g. "s != null ? s : 'default'" is not considered nullable
                    def facts = analyzeCondition(expr.booleanExpression.expression, false)
                    // elvis (x ?: y) is null only if its fallback is: a truthy x is non-null
                    boolean trueNullable = !(expr instanceof ElvisOperatorExpression) &&
                        withGuards(facts.whenTrue) { isNullExpr(expr.trueExpression) || isKnownNullable(expr.trueExpression) }
                    boolean falseNullable =
                        withGuards(facts.whenFalse) { isNullExpr(expr.falseExpression) || isKnownNullable(expr.falseExpression) }
                    return trueNullable || falseNullable
                }
                false
            }

        }
    }

    //--------------------------------------------------------------------------

    /**
     * Checks that each explicitly-annotated {@code @NonNull} instance field is definitely
     * initialized: at its declaration, in an instance initializer block, or by every
     * declared constructor (a constructor delegating via {@code this(...)} relies on its
     * delegate). Only performed in {@code strict} mode. Fields that are merely non-null
     * by default (e.g. under {@code @NullMarked} without an explicit annotation) are not
     * checked, so idiomatic Groovy property classes constructed via named arguments stay
     * noise-free. Assignment detection is not path-sensitive: an assignment anywhere in
     * a constructor counts.
     */
    private void checkNonNullFieldInitialization(ClassNode cn) {
        if (cn.interface) return
        // A Bean Validation constraint promises the value is not null *when validation
        // runs*; it does not promise the constructor assigned it, and beans populated by an
        // ORM or a deserializer legitimately start empty. A @NullMarked class is different:
        // its marking is a language-level contract, so the requirement stands there.
        boolean markedScope = isFieldNonNullByDefault(cn)
        List<FieldNode> candidates = cn.fields.findAll { field ->
            !field.static && !isPrimitiveType(field.type) && field.initialValueExpression == null &&
                isNonNullAnnotated(field) && !hasNullableAnno(field) && !hasMonotonicAnno(field) &&
                !(isValidationConstraint(field) && !markedScope)
        }
        if (!candidates) return
        Set<String> initBlockAssigned = assignedFieldNames(cn.objectInitializerStatements)
        List<ConstructorNode> ctors = cn.declaredConstructors
        List<Set<String>> ctorAssigned = ctors.findAll { !delegatesToThis(it) }
                                              .collect { assignedFieldNames([it.code]) }
        for (field in candidates) {
            if (field.name in initBlockAssigned) continue
            if (!ctors) {
                addStaticTypeError("@NonNull field '${field.name}' is not initialized", field)
            } else if (ctorAssigned.any { !(field.name in it) }) {
                addStaticTypeError("@NonNull field '${field.name}' is not initialized by all constructors", field)
            }
        }
    }

    private static boolean delegatesToThis(ConstructorNode ctor) {
        def first = ctor.firstStatement
        first instanceof ExpressionStatement && first.expression instanceof ConstructorCallExpression &&
            ((ConstructorCallExpression) first.expression).thisCall
    }

    /**
     * Collects the names of fields assigned (by simple name or via {@code this.name})
     * anywhere within the given statements.
     */
    private static Set<String> assignedFieldNames(List<Statement> statements) {
        Set<String> names = []
        def collector = new CodeVisitorSupport() {
            @Override
            void visitBinaryExpression(BinaryExpression expression) {
                if (isAssignment(expression.operation.type)) {
                    def left = expression.leftExpression
                    if (left instanceof VariableExpression) {
                        names.add(left.name)
                    } else if (left instanceof PropertyExpression && left.objectExpression instanceof VariableExpression
                            && ((VariableExpression) left.objectExpression).thisExpression) {
                        def name = left.propertyAsString
                        if (name != null) names.add(name)
                    }
                }
                super.visitBinaryExpression(expression)
            }
        }
        statements.each { it?.visit(collector) }
        names
    }

    /**
     * Whether a member counts as non-null: explicitly annotated, or inheriting a
     * package/class-level {@code @NullMarked} default.
     *
     * <p>The definite-initialisation check needs this rather than
     * {@link #hasNonNullAnno} alone, otherwise a package marked {@code @NullMarked}
     * would demand non-null returns and parameters but silently not require its fields
     * to be initialised -- the same marking meaning two different things depending on
     * which check happened to look.
     */
    private boolean isNonNullAnnotated(AnnotatedNode node) {
        if (hasNonNullAnno(node)) return true
        ClassNode owner = node instanceof FieldNode ? ((FieldNode) node).declaringClass : null
        owner != null && isFieldNonNullByDefault(owner)
    }

    /**
     * Checks the node's declaration annotations and, for methods, parameters and fields,
     * also the type-use annotations of the declared type. Type-use only annotations
     * (e.g. JSpecify's) appear on the type rather than the declaration, in particular
     * for classes read from bytecode or via reflection.
     */
    /**
     * Annotation simple names per node, cached for the compilation.
     *
     * <p>{@link #hasAnno} is asked several times for the same node while deciding a scope
     * (parameter default, return default, field default), and each answer walked both the
     * declaration and the type annotations. The sets are small, but the walk and the closure
     * allocation were not free on a module with thousands of methods.
     */
    private static final Map<AnnotatedNode, Set<String>> PORT_DECL_ANNO_NAMES = new IdentityHashMap<>()

    private static final Map<AnnotatedNode, Set<String>> PORT_TYPE_ANNO_NAMES = new IdentityHashMap<>()

    private boolean isNonNullByDefault(ClassNode cn) {
        scopes.isNonNullByDefault(cn)
    }

    private boolean isParameterNonNullByDefault(ClassNode cn) {
        scopes.isParameterNonNullByDefault(cn)
    }

    private boolean isReturnValueNonNullByDefault(ClassNode cn) {
        scopes.isReturnValueNonNullByDefault(cn)
    }

    private boolean isFieldNonNullByDefault(ClassNode cn) {
        scopes.isFieldNonNullByDefault(cn)
    }

    private boolean isAnyNonNullByDefault(ClassNode cn) {
        scopes.isAnyNonNullByDefault(cn)
    }

    /**
     * Whether the node carries a Bean Validation constraint rather than a nullness
     * annotation. Matched by package, because the constraint's simple name is exactly
     * what the annotation lookup deliberately does not distinguish.
     */
    private static boolean isValidationConstraint(AnnotatedNode node) {
        if (node == null) return false
        node.annotations?.any { it.classNode?.packageName in VALIDATION_ANNO_PACKAGES } ?: false
    }

    private static boolean isEarlyExit(Statement stmt) {
        cannotCompleteNormally(stmt)
    }

    /**
     * Whether a statement can never complete normally, which makes everything after it in the
     * same block unreachable.
     *
     * <p>This exists for one real shape: a helper that always throws being called at the end of
     * a catch block, followed by a fallback {@code return null}. Without it the fallback looks
     * reachable and is reported as returning null from a non-null method, even though it can
     * never run. {@code break} and {@code continue} count here for the same reason they count
     * as early exits in guard analysis: control leaves the current statement sequence.
     */
    private static boolean cannotCompleteNormally(Statement statement) {
        if (statement == null) return false
        if (statement instanceof ReturnStatement || statement instanceof ThrowStatement) return true
        if (statement instanceof BreakStatement || statement instanceof ContinueStatement) return true
        if (statement instanceof ExpressionStatement) return contracts.isNoReturnCall(statement.expression)
        if (statement instanceof BlockStatement) {
            for (Statement child : statement.statements) {
                if (cannotCompleteNormally(child)) return true
            }
            return false
        }
        if (statement instanceof IfStatement) {
            return statement.elseBlock != null
                    && cannotCompleteNormally(statement.ifBlock)
                    && cannotCompleteNormally(statement.elseBlock)
        }
        if (statement instanceof TryCatchStatement) {
            if (!cannotCompleteNormally(statement.tryStatement)) return false
            for (CatchStatement clause : statement.catchStatements) {
                if (!cannotCompleteNormally(clause.code)) return false
            }
            if (statement.finallyStatement != null && cannotCompleteNormally(statement.finallyStatement)) {
                return true
            }
            return true
        }
        if (statement instanceof SynchronizedStatement) {
            return cannotCompleteNormally(statement.code)
        }
        false
    }

    /** Key and value types of a map being iterated. */
    /** What a call hands to its closure: an element, a map entry, or a pair of key and value. */
    private static class ClosureHint {
        boolean elementNullable
        /** True for each/eachWithIndex, which pass (key, value) rather than the entry. */
        boolean pairwise
        MapEntryTypes entryTypes
    }

    /**
     * The variables known to be non-null when a condition evaluates true and when it evaluates false.
     */
    private static class GuardFacts {
        Set<Variable> whenTrue = new HashSet<>()
        Set<Variable> whenFalse = new HashSet<>()
        /** Lookup keys (container text|key text) proved present in that branch. */
        Set<String> lookupsTrue = new HashSet<>()
        Set<String> lookupsFalse = new HashSet<>()
    }
}
