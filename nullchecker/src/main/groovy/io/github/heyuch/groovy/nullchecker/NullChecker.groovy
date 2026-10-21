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

    static NullCheckCoverage getLastCoverage() {
        coverage.snapshot()
    }

    static void resetCoverage() {
        coverage.reset()
    }

    static List<String> getInternalErrors() {
        NullCheckReporter.getInternalErrors()
    }

    static void resetInternalErrors() {
        NullCheckReporter.resetInternalErrors()
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
            reporter.guard("coverage of ${cn.name}") {
                coverage.cover(cn, scopes)
            }
        }
        // Which rule families run is a caller decision, not a property of the code being
        // checked: a project that cannot satisfy a family yet should stop seeing it rather
        // than learn to ignore a wall of findings it has decided not to act on.
        if (isCheckEnabled(CHECK_NULL)) {
            afterVisitMethod { MethodNode method ->
                reporter.guard("method ${method.declaringClass?.name}#${method.name}") {
                    method.code?.visit(makeVisitor(strict, method))
                }
            }
        }
        if (strict && isCheckEnabled(CHECK_INITIALIZATION)) {
            afterVisitClass { ClassNode cn ->
                reporter.guard("initialization of ${cn.name}") {
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

    private static Coverage coverage = new Coverage()

    /** Reports into this DSL instance, so a finding lands on the current type-checking context. */
    private final NullCheckReporter reporter = new NullCheckReporter({ message, node ->
        addStaticTypeError(message, node)
    })

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

        new NullCheckVisitor(flowSensitive, method, methodNonNull, initialNullable, scopes, contracts, reporter, PORT_CONTAINER_LOOKUPS)
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

}
