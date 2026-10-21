package io.github.heyuch.groovy.nullchecker

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.jspecify.annotations.Nullable

/** A finite field path such as {@code this.value} or {@code holder.value} (depth <= 2). */
@CompileStatic
@PackageScope
class AccessPath {

    final String text

    final String root

    private AccessPath(String text, String root) {
        this.text = text
        this.root = root
    }

    static @Nullable AccessPath from(@Nullable Expression expression) {
        if (expression == null) {
            return null
        }
        if (expression instanceof CastExpression) {
            return from(((CastExpression) expression).expression)
        }
        List<String> fields = new ArrayList<String>()
        Expression current = expression
        while (current instanceof PropertyExpression) {
            PropertyExpression property = (PropertyExpression) current
            if (property.safe) {
                return null
            }
            fields.add(0, property.propertyAsString)
            current = property.objectExpression
        }
        if (!(current instanceof VariableExpression) || fields.isEmpty() || fields.size() > 2) {
            return null
        }
        VariableExpression variable = (VariableExpression) current
        if (variable.isSuperExpression()) {
            return null
        }
        String root = variable.isThisExpression() ? 'this' : variable.name
        return new AccessPath(root + '.' + fields.join('.'), root)
    }

    boolean isRootedAt(String prefix) {
        return text == prefix || text.startsWith(prefix + '.')
    }

    @Override
    String toString() {
        return text
    }

    @Override
    boolean equals(Object other) {
        return other instanceof AccessPath && ((AccessPath) other).text == text
    }

    @Override
    int hashCode() {
        return text.hashCode()
    }

}
