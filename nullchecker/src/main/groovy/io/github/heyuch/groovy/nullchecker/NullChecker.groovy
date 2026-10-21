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
import org.codehaus.groovy.control.CompilationUnit
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
    static final String CHECK_NULL = NullCheckConfig.CHECK_NULL

    /** Rule family: an explicitly non-null field no constructor assigns. */
    static final String CHECK_INITIALIZATION = NullCheckConfig.CHECK_INITIALIZATION

    /** The value of {@link #CHECKS_PROPERTY} that enables every family. */
    static final String CHECKS_ALL = NullCheckConfig.CHECKS_ALL

    /** Comma-separated rule families to run; absent means {@link #CHECKS_ALL}. */
    static final String CHECKS_PROPERTY = NullCheckConfig.CHECKS_PROPERTY

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
    static final String STRICT_PROPERTY = NullCheckConfig.STRICT_PROPERTY

    /**
     * Environment variable that enables {@code strict} mode process-wide.
     *
     * <p>Exists because a Gradle build cannot always pass a system property to the
     * compiler JVM, while the environment reaches it either way. That makes this the
     * practical channel for a CI job: turn strict on for the whole build without
     * editing any build file.
     */
    static final String STRICT_ENV = NullCheckConfig.STRICT_ENV

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
        lastCoverage.snapshot()
    }

    static void resetCoverage() {
        lastCoverage = new Coverage()
    }

    static List<String> getInternalErrors() {
        NullCheckReporter.getInternalErrors()
    }

    static void resetInternalErrors() {
        NullCheckReporter.resetInternalErrors()
    }

    /** Drops per-compilation caches, so a new compilation cannot see a stale answer. */
    static void resetCaches() {
        SESSIONS.clear()
        AstSupport.resetCaches()
    }

    /**
     * Registers null-safety checks for each visited method body and, in strict mode,
     * definite-initialization checks for {@code @NonNull} fields of each visited class.
     */
    @Override
    Object run() {
        NullCheckConfig config = NullCheckConfig.read(PORT_OPTIONS)
        CompilationUnit unit = context?.compilationUnit
        NullCheckSession session = sessionFor(unit, config, context?.source?.classLoader)
        lastCoverage = session.coverage
        // Coverage is deliberately NOT reset here. Groovy instantiates this DSL once per
        // checked class -- measured: 50 run() calls for 43 sources -- so resetting in
        // run() would leave the counters describing only the last class seen (observed
        // as "classes=1" for a 43-file project). The caller resets once per compilation
        // via resetCoverage() instead.
        afterVisitClass { ClassNode cn ->
            session.cover(cn, reporter)
        }
        // Which rule families run is a caller decision, not a property of the code being
        // checked: a project that cannot satisfy a family yet should stop seeing it rather
        // than learn to ignore a wall of findings it has decided not to act on.
        if (config.nullnessCheck) {
            afterVisitMethod { MethodNode method ->
                session.check(method, reporter)
            }
        }
        if (config.strict && config.initializationCheck) {
            afterVisitClass { ClassNode cn ->
                session.checkInitialization(cn, reporter)
            }
        }
    }

    private static NullCheckSession sessionFor(CompilationUnit unit, NullCheckConfig config, ClassLoader sourceLoader) {
        if (unit == null) {
            return new NullCheckSession(config, null, sourceLoader)
        }
        synchronized (SESSIONS) {
            NullCheckSession session = SESSIONS.get(unit)
            if (session == null) {
                session = new NullCheckSession(config, unit, sourceLoader)
                SESSIONS.put(unit, session)
            }
            return session
        }
    }

    /** Reports into this DSL instance, so a finding lands on the current type-checking context. */
    private final NullCheckReporter reporter = new NullCheckReporter({ message, node ->
        addStaticTypeError(message, node)
    })

    private static final Map<CompilationUnit, NullCheckSession> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<CompilationUnit, NullCheckSession>())

    private static Coverage lastCoverage = new Coverage()
}
