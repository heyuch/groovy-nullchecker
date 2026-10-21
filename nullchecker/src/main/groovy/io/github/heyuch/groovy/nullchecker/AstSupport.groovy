package io.github.heyuch.groovy.nullchecker

import groovy.transform.PackageScope
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.syntax.Types
import org.codehaus.groovy.transform.stc.StaticTypesMarker
/**
 * Pure AST and type helpers shared by the checker, its visitor and the contract registry.
 *
 * <p>These used to live on {@link NullChecker}, which made the contract registry depend on the
 * checker while the checker depended on the registry. They read a node and nothing else, so
 * they live here instead; only state that belongs to a compilation (package scopes,
 * configuration) stays on the checker.
 */
@PackageScope
class AstSupport {

    private static final Set<String> NULLABLE_ANNOS = Collections.unmodifiableSet(['Nullable', 'CheckForNull', 'MonotonicNonNull'] as Set)

    private static final Set<String> NONNULL_ANNOS = Collections.unmodifiableSet([
            'NonNull', 'NotNull', 'Nonnull', 'NotEmpty', 'NotBlank'] as Set)

    private static final Set<String> MONOTONIC_ANNOS = Collections.unmodifiableSet(['MonotonicNonNull', 'Lazy'] as Set)

    private static final Set<String> NULLCHECK_ANNOS = Collections.unmodifiableSet(['NullCheck', 'ParametersAreNonnullByDefault', 'ParametersAreNonNullByDefault'] as Set)

    private static final Set<String> NONNULL_BY_DEFAULT_ANNOS = Collections.unmodifiableSet(['NonNullByDefault', 'NonnullByDefault', 'NullMarked'] as Set)

    private static final Set<String> NULL_UNMARKED_ANNOS = Collections.unmodifiableSet(['NullUnmarked'] as Set)

    static final Set<String> ASSERTION_MESSAGE_TYPES = Collections.unmodifiableSet(['java.lang.String', 'java.util.function.Supplier'] as Set)

    private static final ClassNode ITERABLE_TYPE = ClassHelper.make(Iterable)

    private static final Map<AnnotatedNode, Set<String>> DECL_ANNO_NAMES = new IdentityHashMap<>()

    private static final Map<AnnotatedNode, Set<String>> TYPE_ANNO_NAMES = new IdentityHashMap<>()

    /** Drops the per-node annotation caches; call between compilations. */
    static void resetCaches() {
        DECL_ANNO_NAMES.clear()
        TYPE_ANNO_NAMES.clear()
    }

    /**
     * PORT: picks the node that actually carries a local declaration's annotations.
     * <p>
     * For {@code @Nullable String x} Groovy 6 attaches the annotation to
     * {@code decl.variableExpression}, but Groovy 4 attaches it to the
     * {@code DeclarationExpression} itself, leaving the variable expression bare.
     * Checking only the variable expression (as upstream does) makes every
     * annotation-based local rule silently skip on Groovy 4. Either node is
     * returned, so the same code is correct on both versions.
     */
    @PackageScope
    static AnnotatedNode declAnnoCarrier(DeclarationExpression decl, VariableExpression ve) {
        if (!ve.annotations.isEmpty() || !ve.type.annotations.isEmpty()) {
            return ve
        }
        decl
    }

    @PackageScope
    static boolean hasNullableAnno(AnnotatedNode node) {
        hasAnno(node, NULLABLE_ANNOS)
    }

    @PackageScope
    static boolean hasNonNullAnno(AnnotatedNode node) {
        if (node.getNodeMetaData('INFERRED_NON_NULL' /* PORT: marker absent in Groovy 4 */) == Boolean.TRUE) return true
        hasAnno(node, NONNULL_ANNOS)
    }

    @PackageScope
    static boolean hasMonotonicAnno(AnnotatedNode node) {
        hasAnno(node, MONOTONIC_ANNOS)
    }

    @PackageScope
    static boolean hasNullCheckAnno(AnnotatedNode node) {
        node.annotations?.any { it.classNode?.nameWithoutPackage in NULLCHECK_ANNOS } ?: false
    }

    @PackageScope
    static boolean hasNonNullByDefaultAnno(AnnotatedNode node) {
        if (hasNullUnmarkedAnno(node)) return false
        declaresNonNullByDefault(node)
    }

    private static boolean hasAnno(AnnotatedNode node, Set<String> annoNames) {
        if (node == null) return false
        Set<String> declared = DECL_ANNO_NAMES.get(node)
        if (declared == null) {
            declared = new HashSet<String>()
            node.annotations?.each { AnnotationNode annotation ->
                String name = annotation.classNode?.nameWithoutPackage
                if (name != null) declared.add(name)
            }
            DECL_ANNO_NAMES.put(node, declared)
        }
        if (!Collections.disjoint(declared, annoNames)) return true
        ClassNode type = typeOf(node)
        if (type == null) return false
        Set<String> typeAnnotations = TYPE_ANNO_NAMES.get(node)
        if (typeAnnotations == null) {
            typeAnnotations = new HashSet<String>()
            type.typeAnnotations?.each { AnnotationNode annotation ->
                String name = annotation.classNode?.nameWithoutPackage
                if (name != null) typeAnnotations.add(name)
            }
            TYPE_ANNO_NAMES.put(node, typeAnnotations)
        }
        !Collections.disjoint(typeAnnotations, annoNames)
    }

    private static ClassNode typeOf(AnnotatedNode node) {
        switch (node) {
            case MethodNode: return ((MethodNode) node).returnType
            case Parameter: return ((Parameter) node).type
            case FieldNode: return ((FieldNode) node).type
            default: return null
        }
    }

    /** The resolved type of an expression, falling back to its declared type. */
    @PackageScope
    static ClassNode typeOfExpression(Expression expression) {
        if (expression == null) return null
        def inferred = expression.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE)
        inferred instanceof ClassNode ? (ClassNode) inferred : expression.type
    }

    @PackageScope
    static boolean hasNullableTypeUse(ClassNode type) {
        type?.typeAnnotations?.any { it.classNode?.nameWithoutPackage in NULLABLE_ANNOS } ?: false
    }

    /**
     * Whether a collection type's elements are nullable.
     *
     * <p>For {@code List<@Nullable String>} the annotation sits on the type argument, which is
     * where JSpecify puts it and where Groovy keeps it; arrays carry it on the component type.
     */
    @PackageScope
    static boolean hasNullableElementType(ClassNode type) {
        if (type == null) return false
        if (type.array) return hasNullableTypeUse(type.componentType)
        // Iterating a Map yields Map.Entry, which is not the key: the annotation on the first
        // type argument says nothing about the entry. Treating it as the element would report
        // every entry dereference in a map typed with nullable keys.
        if (isMapLike(type)) return false
        def generics = type.genericsTypes
        generics != null && generics.length > 0 && hasNullableTypeUse(generics[0].type)
    }

    @PackageScope
    static boolean isMapLike(ClassNode type) {
        type.name == 'java.util.Map' || type.isDerivedFrom(ClassHelper.MAP_TYPE)
    }

    /** Key and value types of a map type, or {@code null} when the type is not a map. */
    @PackageScope
    static MapEntryTypes mapEntryTypes(ClassNode type) {
        if (type == null || !isMapLike(type)) return null
        def generics = type.genericsTypes
        if (generics == null || generics.length < 2) return null
        new MapEntryTypes(keyType: generics[0].type, valueType: generics[1].type)
    }

    /** Whether a map's values are non-null, so {@code containsKey} can prove a lookup. */
    @PackageScope
    static boolean hasNonNullMapValue(ClassNode mapType) {
        MapEntryTypes types = mapEntryTypes(mapType)
        types != null && !hasNullableTypeUse(types.valueType)
    }

    /**
     * Determines whether an expression's inferred type carries a {@code @Nullable}
     * type-use annotation. The static type checker's generics inference preserves type
     * annotations when instantiating class and method level type variables, so this
     * recognizes a {@code @Nullable} type argument reaching a result: {@code get(0)},
     * the idiomatic {@code xs[0]} subscript, or DGM calls like {@code head()} on a
     * {@code List<@Nullable String>}, whether the annotated type appears in source or
     * comes from a compiled dependency (GROOVY-12206).
     */
    @PackageScope
    static boolean inferredNullableTypeUse(Expression expr) {
        def inferred = expr.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE)
        inferred instanceof ClassNode &&
            inferred.typeAnnotations?.any { it.classNode?.nameWithoutPackage in NULLABLE_ANNOS }
    }

    /**
     * Determines whether a property read produces a {@code @Nullable} value, consulting the
     * accessor resolved during type checking and falling back to the property or field
     * declaration (where the annotations reside for Groovy properties, whose resolved
     * accessor is a synthetic node).
     */
    @PackageScope
    static boolean nullableProperty(PropertyExpression pexp) {
        def target = pexp.getNodeMetaData(StaticTypesMarker.DIRECT_METHOD_CALL_TARGET)
        if (target instanceof MethodNode && hasNullableAnno(target)) return true
        def name = pexp.propertyAsString
        if (name == null) return false
        def receiverType = pexp.objectExpression.getNodeMetaData(StaticTypesMarker.INFERRED_TYPE) ?: pexp.objectExpression.type
        def prop = receiverType?.getProperty(name)
        if (prop != null && (hasNullableAnno(prop) || (prop.field != null && hasNullableAnno(prop.field)))) return true
        def field = receiverType?.getField(name)
        field != null && hasNullableAnno(field)
    }

    @PackageScope
    static boolean canBeNull(Expression expr) {
        if (isNullExpr(expr)) return true
        if (expr instanceof TernaryExpression) {
            return canBeNull(expr.trueExpression) || canBeNull(expr.falseExpression)
        }
        false
    }

    /**
     * A safe-navigation result ({@code a?.b} or {@code a?.b()}) may be null: at minimum
     * whenever its receiver is, but also if the accessed property or method yields null.
     */
    @PackageScope
    static boolean isSafeNavResult(Expression expr) {
        // A spread expression is flagged safe as well -- it tolerates a null receiver and
        // yields an empty list instead. But that makes its *result* a List, never null,
        // so treating it as a safe-navigation result reported every "xs*.prop" and its
        // dereference. Spread method calls (xs*.trim()) do not carry the flag.
        if (expr instanceof PropertyExpression) {
            // isSpreadSafe, not "spread": Groovy's AST flag is named isSpreadSafe(), and
            // reading a non-existent property throws inside the checker -- which the harness
            // now turns into a hard failure rather than a clean-looking zero.
            return expr.safe && !expr.spreadSafe
        }
        if (expr instanceof MethodCallExpression) {
            return expr.safe && !expr.spreadSafe
        }
        false
    }

    @PackageScope
    static boolean isSubscript(Expression expr) {
        expr instanceof BinaryExpression && expr.operation.type == Types.LEFT_SQUARE_BRACKET
    }

    @PackageScope
    static boolean isNullExpr(Expression expr) {
        if (expr instanceof ConstantExpression) return ((ConstantExpression) expr).isNullExpression()
        if (expr instanceof CastExpression) return isNullExpr(((CastExpression) expr).expression)
        false
    }

    @PackageScope
    static String lookupKey(Expression container, Expression key) {
        if (container == null || key == null) return null
        if (container instanceof VariableExpression && container.isThisExpression()) return null
        container.text + '|' + key.text
    }

    /** A single string annotation member, or {@code null}. */
    private static String stringMember(AnnotationNode annotation, String name) {
        def member = annotation.getMember(name)
        member instanceof ConstantExpression && member.value instanceof String ? (String) member.value : null
    }

    /** A boolean annotation member, or {@code null} when absent or not a constant. */
    private static Boolean booleanMember(AnnotationNode annotation, String name) {
        def member = annotation.getMember(name)
        member instanceof ConstantExpression && member.value instanceof Boolean ? (Boolean) member.value : null
    }

    /** A string-array annotation member, accepting a single value written without braces. */
    private static List<String> stringMembers(AnnotationNode annotation, String name) {
        List<String> values = new ArrayList<String>()
        def member = annotation.getMember(name)
        if (member instanceof ConstantExpression) {
            def value = ((ConstantExpression) member).value
            if (value instanceof String) {
                values.add((String) value)
            } else if (value instanceof String[]) {
                values.addAll(Arrays.asList((String[]) value))
            }
        } else if (member instanceof ListExpression) {
            for (Expression element : ((ListExpression) member).expressions) {
                if (element instanceof ConstantExpression && element.value instanceof String) {
                    values.add((String) element.value)
                }
            }
        }
        values
    }

    /** Zero-based argument index for a {@code #1}-style reference, or -1 when unsupported. */
    private static int argumentIndex(String reference) {
        if (reference == null) return -1
        def matcher = reference =~ /^#(\d+)$/
        if (!matcher.matches()) return -1
        Integer.parseInt((String) matcher.group(1)) - 1
    }

    /** The annotation with the given simple name on a node, or {@code null}. */
    private static AnnotationNode findAnnotation(AnnotatedNode node, String simpleName) {
        if (node == null) return null
        node.annotations?.find { it.classNode?.nameWithoutPackage == simpleName }
    }

    private static boolean isCollectionLike(ClassNode type) {
        if (type == null) return false
        if (type.isArray()) return true
        type.name in ['java.lang.Iterable', 'java.util.Collection', 'java.util.List', 'java.util.Set']
                || type.isDerivedFrom(ITERABLE_TYPE)
    }

    private static boolean hasNullUnmarkedAnno(AnnotatedNode node) {
        node.annotations?.any { it.classNode?.nameWithoutPackage in NULL_UNMARKED_ANNOS } ?: false
    }

    private static boolean declaresNonNullByDefault(AnnotatedNode node) {
        declaresAny(node, NONNULL_BY_DEFAULT_ANNOS)
    }

    private static boolean declaresAny(AnnotatedNode node, Set<String> names) {
        node.annotations?.any { it.classNode?.nameWithoutPackage in names } ?: false
    }
}
