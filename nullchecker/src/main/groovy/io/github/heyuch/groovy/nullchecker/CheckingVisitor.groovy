/*
 * PORT NOTE ------------------------------------------------------------------
 * Back-ported to Groovy 4.0.x from Apache Groovy (tag GROOVY_6_0_0):
 *   subprojects/groovy-typecheckers/src/main/groovy/org/apache/groovy/typecheckers/CheckingVisitor.groovy
 *   sha256 99df010de442efd25184200a1bcdf8331b5e1e41a661580950057e53a30d6e8f
 *
 * Changes from upstream: PACKAGE RENAME ONLY.
 * The class cannot keep its upstream package: Groovy 4.0.x already ships
 * org.apache.groovy.typecheckers.CheckingVisitor, and Groovy 4's own
 * RegexChecker uses it. The two versions are NOT interchangeable -- upstream
 * Groovy 4 declares findTargetVariable as static, Groovy 6 as an instance
 * method -- so shadowing it would break RegexChecker.
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

import org.jspecify.annotations.Nullable

import groovy.transform.AutoFinal
import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.Variable
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.control.SourceUnit

/**
 * Base visitor support for type-checker extensions that need lightweight constant propagation
 * and access to the underlying variable represented by a {@link VariableExpression}.
 */
@AutoFinal
@CompileStatic
class CheckingVisitor extends ClassCodeVisitorSupport {

    /**
     * Returns no backing source unit because subclasses report errors through the enclosing
     * type-checking extension rather than this visitor.
     */
    @Override
    protected @Nullable SourceUnit getSourceUnit() {
        return null
    }

    /**
     * Tracks local variables whose values are known constant expressions during the current visit.
     */
    protected final Map<Expression, Expression> localConstVars = [:]

    /**
     * Resolves a constant expression of the requested type from an expression, following tracked
     * locals and field initializers when possible.
     *
     * @param exp expression to inspect
     * @param type required runtime type for the constant value
     * @return the matching constant expression, or {@code null} when none can be resolved
     */
    protected @Nullable Expression findConstExp(Expression exp, Class type) {
        if (exp instanceof ConstantExpression && type.isAssignableFrom(exp.value.getClass())) {
            return exp
        }
        if (exp instanceof VariableExpression) {
            Variable var = findTargetVariable(exp)
            if (var instanceof FieldNode && var.hasInitialExpression()) {
                return findConstExp(var.initialExpression, type)
            }
            if (localConstVars.containsKey(var)) {
                return findConstExp(localConstVars.get(var), type)
            }
        }
        return null
    }

    /**
     * Follows {@link VariableExpression#accessedVariable} links until the original variable or field is found.
     *
     * @param ve variable expression to resolve
     * @return the underlying target variable
     */
    protected Variable findTargetVariable(VariableExpression ve) {
        Variable accessedVariable = ve.accessedVariable
        if (accessedVariable != null && accessedVariable != ve) {
            if (accessedVariable instanceof VariableExpression) {
                return findTargetVariable(accessedVariable)
            }
            return accessedVariable
        }
        return ve
    }
}
