package io.github.heyuch.groovy.nullchecker

import static io.github.heyuch.groovy.nullchecker.AstSupport.hasNullableAnno
import static io.github.heyuch.groovy.nullchecker.AstSupport.isNullExpr

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.syntax.Types
import org.jspecify.annotations.Nullable

/**
 * Opt-in source migration: propose and (unless dry-run) insert @Nullable on high-confidence
 * declarations. It never runs as part of the normal check.
 */
@CompileStatic
class NullableAnnotator {

    static final String DEFAULT_ANNOTATION = 'org.jspecify.annotations.Nullable'

    static class Result {

        int files
        int added
        int skipped
        List<String> messages = []

    }

    static Result run(List<File> files, String annotation, boolean dryRun, PrintStream out) {
        Result result = new Result(files: files.size())
        for (File file : files) {
            String source = file.getText('UTF-8')
            CompilationUnit unit = parse(file, out, result)
            if (unit == null) {
                continue
            }
            List<Target> targets = targets(unit)
            if (targets.isEmpty()) {
                continue
            }
            List<Edit> edits = edits(source, targets, annotation, result)
            if (edits.isEmpty()) {
                continue
            }
            result.added += edits.count { Edit edit -> edit.annotation }
            for (Edit edit : edits.findAll { Edit e -> e.annotation }) {
                result.messages.add(("${dryRun ? 'would add' : 'added'} @Nullable to ${edit.description} " +
                        "(${file.absolutePath}:${edit.line})").toString())
            }
            if (!dryRun) {
                apply(file, source, edits)
            }
        }
        for (String message : result.messages) {
            out.println message
        }
        out.println "nullable: ${dryRun ? 'would add' : 'added'} ${result.added}, " +
                "skipped ${result.skipped} declaration(s)"
        return result
    }

    @SuppressWarnings('ReturnNullFromCatchBlock')
    private static @Nullable CompilationUnit parse(File file, PrintStream out, Result result) {
        try {
            CompilationUnit unit = new CompilationUnit(new CompilerConfiguration())
            unit.addSource(file)
            unit.compile(Phases.CONVERSION)
            return unit
        } catch (Throwable failure) {
            result.skipped++
            out.println "nullable: skipped ${file.absolutePath}: ${failure.message}"
            return null
        }
    }

    private static List<Target> targets(CompilationUnit unit) {
        List<Target> targets = []
        PackageScopes scopes = new PackageScopes(unit, null)
        for (ClassNode cn : unit.AST.classes) {
            if (cn.name.endsWith('.package-info') || !scopes.isNullMarked(cn)) {
                continue
            }
            targets.addAll(methodTargets(cn))
            targets.addAll(fieldTargets(cn))
            targets.addAll(parameterTargets(cn))
        }
        return targets
    }

    private static List<Target> methodTargets(ClassNode cn) {
        List<Target> targets = []
        for (MethodNode method : cn.methods) {
            if (method.name == '<init>' || method.returnType == ClassHelper.VOID_TYPE ||
                    ClassHelper.isPrimitiveType(method.returnType) || hasNullableAnno(method)) {
                continue
            }
            if (method.code != null && returnsNull(method.code)) {
                targets.add(new Target(kind: 'method', name: method.name,
                        line: method.lineNumber, description: "method '${cn.name}#${method.name}'"))
            }
        }
        return targets
    }

    private static List<Target> fieldTargets(ClassNode cn) {
        List<Target> targets = []
        for (FieldNode field : cn.fields) {
            if (field.name.startsWith('$') || ClassHelper.isPrimitiveType(field.type) ||
                    hasNullableAnno(field)) {
                continue
            }
            if (isNullExpr(field.initialExpression) || isNullExpr(field.initialValueExpression) ||
                    assignedNullInClass(cn, field.name)) {
                targets.add(new Target(kind: 'field', name: field.name,
                        line: field.lineNumber, description: "field '${cn.name}#${field.name}'"))
            }
        }
        return targets
    }

    private static List<Target> parameterTargets(ClassNode cn) {
        List<Target> targets = []
        for (MethodNode method : cn.methods) {
            if (method.code == null) {
                continue
            }
            for (Parameter parameter : method.parameters) {
                if (ClassHelper.isPrimitiveType(parameter.type) || hasNullableAnno(parameter)) {
                    continue
                }
                if (comparedToNull(method.code, parameter.name)) {
                    targets.add(new Target(kind: 'parameter', name: parameter.name,
                            line: parameter.lineNumber,
                            description: "parameter '${parameter.name}' of '${cn.name}#${method.name}'"))
                }
            }
        }
        return targets
    }

    private static boolean returnsNull(org.codehaus.groovy.ast.stmt.Statement code) {
        boolean[] found = new boolean[1]
        CodeVisitorSupport visitor = new CodeVisitorSupport() {

            @Override
            void visitReturnStatement(ReturnStatement statement) {
                if (isNullExpr(statement.expression)) {
                    found[0] = true
                }
            }

            @Override
            void visitClosureExpression(org.codehaus.groovy.ast.expr.ClosureExpression expression) {
                // a nested closure return does not make this method nullable
            }

        }
        code.visit(visitor)
        return found[0]
    }

    private static boolean assignedNullInClass(ClassNode cn, String fieldName) {
        boolean[] found = new boolean[1]
        CodeVisitorSupport visitor = new CodeVisitorSupport() {

            @Override
            void visitBinaryExpression(BinaryExpression expression) {
                if (Types.isAssignment(expression.operation.type) &&
                        expression.leftExpression instanceof VariableExpression &&
                        ((VariableExpression) expression.leftExpression).name == fieldName &&
                        isNullExpr(expression.rightExpression)) {
                    found[0] = true
                }
                super.visitBinaryExpression(expression)
            }

        }
        for (MethodNode method : cn.methods) {
            method.code?.visit(visitor)
        }
        return found[0]
    }

    private static boolean comparedToNull(org.codehaus.groovy.ast.stmt.Statement code, String parameter) {
        boolean[] found = new boolean[1]
        CodeVisitorSupport visitor = new CodeVisitorSupport() {

            @Override
            void visitBinaryExpression(BinaryExpression expression) {
                int op = expression.operation.type
                if (op == Types.COMPARE_EQUAL || op == Types.COMPARE_NOT_EQUAL) {
                    if ((expression.leftExpression instanceof VariableExpression &&
                            ((VariableExpression) expression.leftExpression).name == parameter &&
                            isNullExpr(expression.rightExpression)) ||
                            (expression.rightExpression instanceof VariableExpression &&
                                    ((VariableExpression) expression.rightExpression).name == parameter &&
                                    isNullExpr(expression.leftExpression))) {
                        found[0] = true
                    }
                }
                super.visitBinaryExpression(expression)
            }

        }
        code.visit(visitor)
        return found[0]
    }

    private static List<Edit> edits(String source, List<Target> targets,
                                    String annotation, Result result) {
        List<String> lines = source.split('\n', -1) as List<String>
        boolean configuredImport = source.readLines().any { String line ->
            line.trim() == "import ${annotation}".toString()
        }
        boolean otherImport = !configuredImport && source.readLines().any { String line ->
            line.trim().startsWith('import ') && line.trim().endsWith('.Nullable')
        }
        boolean addImport = !configuredImport && !otherImport
        String text = otherImport ? "@${annotation} " : '@Nullable '
        List<Edit> edits = []
        for (Target target : targets) {
            int lineIndex = target.line - 1
            if (lineIndex < 0 || lineIndex >= lines.size()) {
                result.skipped++
                continue
            }
            String line = lines[lineIndex]
            int nameIndex = line.indexOf(target.name)
            if (nameIndex < 0) {
                result.skipped++
                continue
            }
            int typeIndex = typeIndex(line, nameIndex)
            if (typeIndex < 0) {
                result.skipped++
                continue
            }
            int offset = lineOffset(lines, lineIndex) + typeIndex
            edits.add(new Edit(offset: offset, line: target.line, text: text,
                    description: target.description, annotation: true))
        }
        if (!edits.isEmpty() && addImport) {
            int importOffset = importOffset(lines)
            edits.add(new Edit(offset: importOffset, line: 0,
                    text: "import ${annotation}\n", description: 'Nullable import',
                    annotation: false))
        }
        return edits
    }

    private static int typeIndex(String line, int nameIndex) {
        String before = line.substring(0, nameIndex)
        java.util.regex.Matcher matcher = (before =~ /([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:<[^>]+>)?)\s*$/)
        return matcher.find() ? matcher.start(1) : -1
    }

    private static int lineOffset(List<String> lines, int line) {
        int offset = 0
        for (int i = 0; i < line; i++) {
            offset += lines[i].length() + 1
        }
        return offset
    }

    private static int importOffset(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines[i].trim().startsWith('package ')) {
                return lineOffset(lines, i) + lines[i].length() + 1
            }
        }
        return 0
    }

    private static void apply(File file, String source, List<Edit> edits) {
        StringBuilder text = new StringBuilder(source)
        edits.sort { Edit left, Edit right -> Integer.compare(right.offset, left.offset) }
        int previous = Integer.MAX_VALUE
        for (Edit edit : edits) {
            if (edit.offset == previous) {
                continue
            }
            text.insert(edit.offset, edit.text)
            previous = edit.offset
        }
        file.setText(text.toString(), 'UTF-8')
    }

    private static class Target {

        String kind = ''
        String name = ''
        int line
        String description = ''

    }

    private static class Edit {

        int offset
        int line
        String text = ''
        String description = ''
        boolean annotation

    }

}
