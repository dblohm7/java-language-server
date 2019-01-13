package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Check uses an existing JavacTask/Scope to typecheck simple expressions that weren't part of the original compilation.
 */
class Check {
    private final JavacTask task;
    private final Scope scope;
    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private Tree.Kind retainedPart; // TODO not coloring correctly
    private TypeMirror retainedType;

    Check(JavacTask task, Scope scope) {
        this.task = task;
        this.scope = scope;
        this.trees = Trees.instance(task);
        this.elements = task.getElements();
        this.types = task.getTypes();
    }

    Check withRetainedType(Tree.Kind retainedPart, TypeMirror retainedType) {
        this.retainedPart = retainedPart;
        this.retainedType = retainedType;
        return this;
    }

    private TypeMirror empty() {
        return elements.getTypeElement("java.lang.Void").asType();
    }

    private boolean isThisOrSuper(Name name) {
        return name.contentEquals("this") || name.contentEquals("super");
    }

    private List<TypeMirror> thisMembers(Element thisOrSuper, String identifier) {
        var list = new ArrayList<TypeMirror>();
        var thisType = thisOrSuper.asType();
        var thisEl = types.asElement(thisType);
        if (thisEl instanceof TypeElement) {
            var thisTypeEl = (TypeElement) thisEl;
            for (var m : elements.getAllMembers(thisTypeEl)) {
                if (m.getSimpleName().contentEquals(identifier)) {
                    list.add(m.asType());
                }
            }
        }
        return list;
    }

    private List<TypeMirror> members(Scope scope, String identifier) {
        var list = new ArrayList<TypeMirror>();
        for (var el : scope.getLocalElements()) {
            if (el.getSimpleName().contentEquals(identifier)) {
                list.add(el.asType());
            }
            if (isThisOrSuper(el.getSimpleName())) {
                list.addAll(thisMembers(el, identifier));
            }
        }
        return list;
    }

    private List<ExecutableType> envMethod(String identifier) {
        // TODO outermost scopes take forever to resolve, skip them like in CompileFocus?
        var matches = new ArrayList<ExecutableType>();
        for (var s = scope; s != null; s = s.getEnclosingScope()) {
            for (var el : members(s, identifier)) {
                if (el.getKind() == TypeKind.EXECUTABLE) {
                    matches.add((ExecutableType) el);
                }
            }
        }
        return matches;
    }

    private TypeMirror env(String identifier) {
        // TODO outermost scopes take forever to resolve, skip them like in CompileFocus?
        for (var s = scope; s != null; s = s.getEnclosingScope()) {
            for (var el : members(s, identifier)) {
                if (el.getKind() != TypeKind.EXECUTABLE) {
                    return el;
                }
            }
        }
        return empty();
    }

    private boolean isCompatible(ExecutableType method, List<TypeMirror> args) {
        var params = method.getParameterTypes();
        if (params.size() != args.size()) return false;
        for (var i = 0; i < params.size(); i++) {
            var p = params.get(i);
            var a = args.get(i);
            if (!types.isAssignable(a, p)) return false;
        }
        return true;
    }

    private List<TypeMirror> checkList(List<? extends ExpressionTree> ts) {
        var els = new ArrayList<TypeMirror>();
        for (var t : ts) {
            var e = check(t);
            els.add(e);
        }
        return els;
    }

    private List<ExecutableType> checkMethod(ExpressionTree t) {
        if (t instanceof IdentifierTree) {
            var id = (IdentifierTree) t;
            return envMethod(id.getName().toString());
        } else if (t instanceof MemberSelectTree) {
            var select = (MemberSelectTree) t;
            var expr = check(select.getExpression());
            var exprEl = types.asElement(expr);
            if (!(exprEl instanceof TypeElement)) return List.of();
            var members = elements.getAllMembers((TypeElement) exprEl);
            var name = select.getIdentifier();
            var matches = new ArrayList<ExecutableType>();
            for (var m : members) {
                if (m.getSimpleName().contentEquals(name) && m.getKind() == ElementKind.METHOD) {
                    matches.add((ExecutableType) m.asType());
                }
            }
            return matches;
        } else {
            return List.of();
        }
    }

    /**
     * Check the type of a tree without invoking the full Java compiler. Some expressions can't be checked, see
     * cantCheck(...) for how to handle those.
     */
    TypeMirror check(Tree t) {
        if (!canCheck(t)) {
            if (t.getKind() == retainedPart) {
                return retainedType;
            } else {
                return empty();
            }
        } else if (t instanceof ArrayAccessTree) {
            var access = (ArrayAccessTree) t;
            var expr = check(access.getExpression());
            if (!(expr instanceof ArrayType)) return empty();
            var array = (ArrayType) expr;
            return array.getComponentType();
        } else if (t instanceof ConditionalExpressionTree) {
            var cond = (ConditionalExpressionTree) t;
            return check(cond.getTrueExpression());
        } else if (t instanceof IdentifierTree) {
            var id = (IdentifierTree) t;
            return env(id.getName().toString());
        } else if (t instanceof MemberSelectTree) {
            var select = (MemberSelectTree) t;
            var expr = check(select.getExpression());
            var exprEl = types.asElement(expr);
            if (!(exprEl instanceof TypeElement)) return empty();
            var members = elements.getAllMembers((TypeElement) exprEl);
            var name = select.getIdentifier();
            for (var m : members) {
                if (m.getSimpleName().contentEquals(name) && m.getKind() != ElementKind.METHOD) {
                    return m.asType();
                }
            }
            return empty();
        } else if (t instanceof MethodInvocationTree) {
            var invoke = (MethodInvocationTree) t;
            var overloads = checkMethod(invoke.getMethodSelect());
            if (overloads.size() == 1) return overloads.get(0).getReturnType();
            var args = checkList(invoke.getArguments());
            for (var m : overloads) {
                if (isCompatible(m, args)) {
                    return m.getReturnType();
                }
            }
            return empty();
        } else if (t instanceof ParenthesizedTree) {
            var paren = (ParenthesizedTree) t;
            return check(paren.getExpression());
        } else {
            return empty();
        }
    }

    /**
     * cantCheck(_, root, line) finds the part of the expression to the left of the cursor that can't be checked by
     * `check(Tree)`. If this part of the expression has previously been typechecked by javac, the previous type can be
     * re-used by calling `withRetainedType(kind, type)`.
     */
    static Optional<TreePath> cantCheck(JavacTask task, CompilationUnitTree root, int line, int character) {
        var path = beforeCursor(task, root, line, character);
        return path.flatMap(Check::findCantCheck);
    }

    private static Optional<TreePath> findCantCheck(TreePath path) {
        var t = path.getLeaf();
        if (!canCheck(t)) {
            return Optional.of(path);
        } else if (t instanceof ArrayAccessTree) {
            var access = (ArrayAccessTree) t;
            return findCantCheck(new TreePath(path, access.getExpression()));
        } else if (t instanceof ConditionalExpressionTree) {
            var cond = (ConditionalExpressionTree) t;
            return findCantCheck(new TreePath(path, cond.getTrueExpression()));
        } else if (t instanceof IdentifierTree) {
            return Optional.empty();
        } else if (t instanceof MemberSelectTree) {
            var select = (MemberSelectTree) t;
            return findCantCheck(new TreePath(path, select.getExpression()));
        } else if (t instanceof MethodInvocationTree) {
            // If any part of the method call can't be checked, then the whole method can't be checked
            // TODO we could be more aggressive when there are no overloads
            var invoke = (MethodInvocationTree) t;
            if (!canCheck(invoke.getMethodSelect())) {
                return Optional.of(path);
            }
            for (var arg : invoke.getArguments()) {
                if (!canCheck(arg)) {
                    return Optional.of(path);
                }
            }
            return Optional.empty();
        } else if (t instanceof ParenthesizedTree) {
            var paren = (ParenthesizedTree) t;
            return findCantCheck(new TreePath(path, paren.getExpression()));
        } else {
            return Optional.of(path);
        }
    }

    private static boolean canCheck(Tree t) {
        switch (t.getKind()) {
            case ARRAY_ACCESS:
            case CONDITIONAL_EXPRESSION:
            case IDENTIFIER:
            case MEMBER_SELECT:
            case PARENTHESIZED:
                return true;
            case METHOD_INVOCATION:
                var invoke = (MethodInvocationTree) t;
                if (!canCheck(invoke.getMethodSelect())) return false;
                for (var arg : invoke.getArguments()) {
                    if (!canCheck(arg)) return false;
                }
                return true;
            default:
                return false;
        }
    }

    private static Optional<TreePath> beforeCursor(JavacTask task, CompilationUnitTree root, int line, int character) {
        var pos = Trees.instance(task).getSourcePositions();
        var lines = root.getLineMap();
        var cursor = lines.getPosition(line, character);

        // Find line
        var findLine =
                new TreePathScanner<Void, Void>() {
                    TreePath found;

                    boolean includesCursor(Tree t) {
                        var start = pos.getStartPosition(root, t);
                        var end = pos.getEndPosition(root, t);
                        return start <= cursor && cursor <= end;
                    }

                    void check(List<? extends Tree> lines) {
                        for (var s : lines) {
                            if (includesCursor(s)) {
                                found = new TreePath(getCurrentPath(), s);
                            }
                        }
                    }

                    @Override
                    public Void visitClass(ClassTree t, Void __) {
                        check(t.getMembers());
                        return super.visitClass(t, null);
                    }

                    @Override
                    public Void visitBlock(BlockTree t, Void __) {
                        check(t.getStatements());
                        return super.visitBlock(t, null);
                    }
                };
        findLine.scan(root, null);
        if (findLine.found == null) return Optional.empty();

        // Find part of expression to the left of cursor
        var findLeft =
                new TreePathScanner<Void, Void>() {
                    TreePath found;

                    boolean beforeCursor(Tree t) {
                        var start = pos.getStartPosition(root, t);
                        var end = pos.getEndPosition(root, t);
                        if (start == -1 || end == -1) return false;
                        return start <= cursor && end <= cursor;
                    }

                    @Override
                    public Void scan(Tree t, Void __) {
                        if (found == null && beforeCursor(t)) {
                            found = getCurrentPath();
                        }
                        return super.scan(t, null);
                    }
                };
        findLeft.scan(findLine.found, null);
        if (findLeft.found == null) return Optional.empty();

        return Optional.of(findLeft.found);
    }

    public List<Completion> completeIdentifiers(TreePath cursor) {
        var id = (IdentifierTree) cursor.getLeaf();
        var partialName = id.getName().toString();

        LOG.info(String.format("Completing identifiers starting with `%s`...", partialName));

        var result = new ArrayList<Completion>();

        // Add snippets
        if (!insideClass(cursor)) {
            // If no package declaration is present, suggest package [inferred name];
            if (cursor.getCompilationUnit().getPackage() == null) {
                var name = FileStore.suggestedPackageName(Paths.get(file));
                result.add(Completion.ofSnippet("package " + name, "package " + name + ";\n\n"));
            }
            // If no class declaration is present, suggest class [file name]
            var hasClassDeclaration = false;
            for (var t : root.getTypeDecls()) {
                if (!(t instanceof ErroneousTree)) {
                    hasClassDeclaration = true;
                }
            }
            if (!hasClassDeclaration) {
                var name = Paths.get(file).getFileName().toString();
                name = name.substring(0, name.length() - ".java".length());
                result.add(Completion.ofSnippet("class " + name, "class " + name + " {\n    $0\n}"));
            }
        }
        // Add identifiers
        completeScopeIdentifiers(partialName, result);
        // Add keywords
        if (!insideClass(cursor)) {
            addKeywords(TOP_LEVEL_KEYWORDS, partialName, result);
        } else if (!insideMethod(cursor)) {
            addKeywords(CLASS_BODY_KEYWORDS, partialName, result);
        } else {
            addKeywords(METHOD_BODY_KEYWORDS, partialName, result);
        }

        return result;
    }

    private boolean insideClass(TreePath cursor) {
        while (cursor != null) {
            if (cursor.getLeaf().getKind() == Tree.Kind.CLASS) {
                return true;
            }
            cursor = cursor.getParentPath();
        }
        return false;
    }

    private boolean insideMethod(TreePath cursor) {
        while (cursor != null) {
            if (cursor.getLeaf().getKind() == Tree.Kind.METHOD) {
                return true;
            }
            cursor = cursor.getParentPath();
        }
        return false;
    }

    public List<Completion> completeAnnotations(TreePath cursor) {
        var id = (IdentifierTree) cursor.getLeaf();
        var partialName = id.getName().toString();
        var result = new ArrayList<Completion>();
        // Add @Override ... snippet
        if ("Override".startsWith(partialName)) {
            // TODO filter out already-implemented methods using thisMethods
            var alreadyShown = new HashSet<String>();
            for (var method : superMethods()) {
                var mods = method.getModifiers();
                if (mods.contains(Modifier.STATIC) || mods.contains(Modifier.PRIVATE)) continue;

                var label = "@Override " + ShortTypePrinter.printMethod(method);
                var snippet = "Override\n" + new TemplatePrinter().printMethod(method) + " {\n    $0\n}";
                var override = Completion.ofSnippet(label, snippet);
                if (!alreadyShown.contains(label)) {
                    result.add(override);
                    alreadyShown.add(label);
                }
            }
        }
        // Add @Override, @Test, other simple class names
        completeScopeIdentifiers(partialName, result);
        return result;
    }

    /** Find all case options in the switch expression surrounding line:character */
    public List<Completion> completeCases(TreePath cursor) {
        LOG.info(String.format("Complete enum constants following `%s`...", cursor.getLeaf()));

        // Find surrounding switch
        var path = cursor;
        while (!(path.getLeaf() instanceof SwitchTree)) path = path.getParentPath();
        var leaf = (SwitchTree) path.getLeaf();
        path = new TreePath(path, leaf.getExpression());
        LOG.info(String.format("...found switch expression `%s`", leaf.getExpression()));

        // Get members of switched type
        var type = trees.getTypeMirror(path);
        LOG.info(String.format("...switched expression has type `%s`", type));
        var types = task.getTypes();
        var definition = types.asElement(type);
        if (definition == null) {
            LOG.info("...type has no definition, completing identifiers instead");
            return completeIdentifiers(cursor);
        }
        LOG.info(String.format("...switched expression has definition `%s`", definition));
        var result = new ArrayList<Completion>();
        for (var member : definition.getEnclosedElements()) {
            if (member.getKind() == ElementKind.ENUM_CONSTANT) result.add(Completion.ofElement(member));
        }

        return result;
    }

    /** Find all members of expression ending at line:character */
    public List<Completion> completeMembers(TreePath cursor, boolean isReference) {
        var types = task.getTypes();
        var scope = trees.getScope(path);
        var element = trees.getElement(path);

        if (element instanceof PackageElement) {
            var result = new ArrayList<Completion>();
            var p = (PackageElement) element;

            LOG.info(String.format("...completing members of package %s", p.getQualifiedName()));

            // Add class-names resolved as Element by javac
            for (var member : p.getEnclosedElements()) {
                // If the package member is a TypeElement, like a class or interface, check if it's accessible
                if (member instanceof TypeElement) {
                    if (trees.isAccessible(scope, (TypeElement) member)) {
                        result.add(Completion.ofElement(member));
                    }
                }
                // Otherwise, just assume it's accessible and add it to the list
                else result.add(Completion.ofElement(member));
            }
            // Add sub-package names resolved as String by guava ClassPath
            var parent = p.getQualifiedName().toString();
            var subs = subPackages(parent);
            for (var sub : subs) {
                result.add(Completion.ofPackagePart(sub, Parser.lastName(sub)));
            }

            return result;
        } else if (element instanceof TypeElement && isReference) {
            var result = new ArrayList<Completion>();
            var t = (TypeElement) element;

            LOG.info(String.format("...completing static methods of %s", t.getQualifiedName()));

            // Add members
            for (var member : t.getEnclosedElements()) {
                if (member.getKind() == ElementKind.METHOD
                        && trees.isAccessible(scope, member, (DeclaredType) t.asType())) {
                    result.add(Completion.ofElement(member));
                }
            }

            // Add ::new
            result.add(Completion.ofKeyword("new"));

            return result;
        } else if (element instanceof TypeElement && !isReference) {
            var result = new ArrayList<Completion>();
            var t = (TypeElement) element;

            LOG.info(String.format("...completing static members of %s", t.getQualifiedName()));

            // Add static members
            for (var member : t.getEnclosedElements()) {
                // TODO if this is a member reference :: then include non-statics
                if (member.getModifiers().contains(Modifier.STATIC)
                        && trees.isAccessible(scope, member, (DeclaredType) t.asType())) {
                    result.add(Completion.ofElement(member));
                }
            }

            // Add .class
            result.add(Completion.ofKeyword("class"));
            result.add(Completion.ofKeyword("this"));
            result.add(Completion.ofKeyword("super"));

            return result;
        } else {
            var type = trees.getTypeMirror(path);
            if (type == null) {
                LOG.warning(String.format("`...%s` has not type", path.getLeaf()));
                return List.of();
            }
            if (!hasMembers(type)) {
                LOG.warning("...don't know how to complete members of type " + type);
                return Collections.emptyList();
            }

            var result = new ArrayList<Completion>();
            var ts = supersWithSelf(type);
            var alreadyAdded = new HashSet<String>();
            LOG.info(String.format("...completing virtual members of %s and %d supers", type, ts.size()));
            for (var t : ts) {
                var e = types.asElement(t);
                if (e == null) {
                    LOG.warning(String.format("...can't convert supertype `%s` to element, skipping", t));
                    continue;
                }
                for (var member : e.getEnclosedElements()) {
                    // Don't add statics
                    if (member.getModifiers().contains(Modifier.STATIC)) continue;
                    // Don't add constructors
                    if (member.getSimpleName().contentEquals("<init>")) continue;
                    // Skip overridden members from superclass
                    if (alreadyAdded.contains(member.toString())) continue;

                    // If type is a DeclaredType, check accessibility of member
                    if (type instanceof DeclaredType) {
                        if (trees.isAccessible(scope, member, (DeclaredType) type)) {
                            result.add(Completion.ofElement(member));
                            alreadyAdded.add(member.toString());
                        }
                    }
                    // Otherwise, accessibility rules are very complicated
                    // Give up and just declare that everything is accessible
                    else {
                        result.add(Completion.ofElement(member));
                        alreadyAdded.add(member.toString());
                    }
                }
            }
            if (type instanceof ArrayType) {
                result.add(Completion.ofKeyword("length"));
            }
            return result;
        }
    }

    public static String[] TOP_LEVEL_KEYWORDS = {
        "package",
        "import",
        "public",
        "private",
        "protected",
        "abstract",
        "class",
        "interface",
        "extends",
        "implements",
    };

    private static String[] CLASS_BODY_KEYWORDS = {
        "public",
        "private",
        "protected",
        "static",
        "final",
        "native",
        "synchronized",
        "abstract",
        "default",
        "class",
        "interface",
        "void",
        "boolean",
        "int",
        "long",
        "float",
        "double",
    };

    private static String[] METHOD_BODY_KEYWORDS = {
        "new",
        "assert",
        "try",
        "catch",
        "finally",
        "throw",
        "return",
        "break",
        "case",
        "continue",
        "default",
        "do",
        "while",
        "for",
        "switch",
        "if",
        "else",
        "instanceof",
        "var",
        "final",
        "class",
        "void",
        "boolean",
        "int",
        "long",
        "float",
        "double",
    };

    private List<ExecutableElement> virtualMethods(DeclaredType type) {
        var result = new ArrayList<ExecutableElement>();
        for (var member : type.asElement().getEnclosedElements()) {
            if (member instanceof ExecutableElement) {
                var method = (ExecutableElement) member;
                if (!method.getSimpleName().contentEquals("<init>")
                        && !method.getModifiers().contains(Modifier.STATIC)) {
                    result.add(method);
                }
            }
        }
        return result;
    }

    private TypeMirror enclosingClass() {
        var path = this.path;
        while (!(path.getLeaf() instanceof ClassTree)) path = path.getParentPath();
        var enclosingClass = trees.getElement(path);

        return enclosingClass.asType();
    }

    private void collectSuperMethods(TypeMirror thisType, List<ExecutableElement> result) {
        var types = task.getTypes();

        for (var superType : types.directSupertypes(thisType)) {
            if (superType instanceof DeclaredType) {
                var type = (DeclaredType) superType;
                result.addAll(virtualMethods(type));
                collectSuperMethods(type, result);
            }
        }
    }

    private List<ExecutableElement> superMethods() {
        var thisType = enclosingClass();
        var result = new ArrayList<ExecutableElement>();

        collectSuperMethods(thisType, result);

        return result;
    }

    static boolean matchesPartialName(CharSequence candidate, CharSequence partialName) {
        if (candidate.length() < partialName.length()) return false;
        for (int i = 0; i < partialName.length(); i++) {
            if (candidate.charAt(i) != partialName.charAt(i)) return false;
        }
        return true;
    }

    private boolean isImported(String qualifiedName) {
        var packageName = Parser.mostName(qualifiedName);
        var className = Parser.lastName(qualifiedName);
        for (var i : root.getImports()) {
            var importName = i.getQualifiedIdentifier().toString();
            var importPackage = Parser.mostName(importName);
            var importClass = Parser.lastName(importName);
            if (importClass.equals("*") && importPackage.equals(packageName)) return true;
            if (importClass.equals(className) && importPackage.equals(packageName)) return true;
        }
        return false;
    }

    private Set<TypeMirror> supersWithSelf(TypeMirror t) {
        var types = new HashSet<TypeMirror>();
        collectSupers(t, types);
        // Object type is not included by default
        // We need to add it to get members like .equals(other) and .hashCode()
        types.add(task.getElements().getTypeElement("java.lang.Object").asType());
        return types;
    }

    private void collectSupers(TypeMirror t, Set<TypeMirror> supers) {
        supers.add(t);
        for (var s : types.directSupertypes(t)) {
            collectSupers(s, supers);
        }
    }

    private boolean hasMembers(TypeMirror type) {
        switch (type.getKind()) {
            case ARRAY:
            case DECLARED:
            case ERROR:
            case TYPEVAR:
            case WILDCARD:
            case UNION:
            case INTERSECTION:
                return true;
            case BOOLEAN:
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
            case CHAR:
            case FLOAT:
            case DOUBLE:
            case VOID:
            case NONE:
            case NULL:
            case PACKAGE:
            case EXECUTABLE:
            case OTHER:
            default:
                return false;
        }
    }

    /** Find all identifiers in scope at line:character */
    List<Element> scopeMembers(String partialName) {
        var types = task.getTypes();
        var start = trees.getScope(path);

        class Walk {
            List<Element> result = new ArrayList<>();

            boolean isStatic(Scope s) {
                var method = s.getEnclosingMethod();
                if (method != null) {
                    return method.getModifiers().contains(Modifier.STATIC);
                } else return false;
            }

            boolean isStatic(Element e) {
                return e.getModifiers().contains(Modifier.STATIC);
            }

            boolean isThisOrSuper(Element e) {
                var name = e.getSimpleName();
                return name.contentEquals("this") || name.contentEquals("super");
            }

            // Place each member of `this` or `super` directly into `results`
            void unwrapThisSuper(VariableElement ve) {
                var thisType = ve.asType();
                // `this` and `super` should always be instances of DeclaredType, which we'll use to check accessibility
                if (!(thisType instanceof DeclaredType)) {
                    LOG.warning(String.format("%s is not a DeclaredType", thisType));
                    return;
                }
                var thisDeclaredType = (DeclaredType) thisType;
                var thisElement = types.asElement(thisDeclaredType);
                for (var thisMember : thisElement.getEnclosedElements()) {
                    if (isStatic(start) && !isStatic(thisMember)) continue;
                    if (thisMember.getSimpleName().contentEquals("<init>")) continue;
                    if (!matchesPartialName(thisMember.getSimpleName(), partialName)) continue;

                    // Check if member is accessible from original scope
                    if (trees.isAccessible(start, thisMember, thisDeclaredType)) {
                        result.add(thisMember);
                    }
                }
            }

            // Place each member of `s` into results, and unwrap `this` and `super`
            void walkLocals(Scope s) {
                try {
                    for (var e : s.getLocalElements()) {
                        if (matchesPartialName(e.getSimpleName(), partialName)) {
                            if (e instanceof TypeElement) {
                                var te = (TypeElement) e;
                                if (trees.isAccessible(start, te)) result.add(te);
                            } else if (isThisOrSuper(e)) {
                                if (!isStatic(s)) result.add(e);
                            } else {
                                result.add(e);
                            }
                        }
                        if (isThisOrSuper(e)) {
                            unwrapThisSuper((VariableElement) e);
                        }
                        if (tooManyItems(result.size())) return;
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "error walking locals in scope", e);
                }
            }

            // Walk each enclosing scope, placing its members into `results`
            List<Element> walkScopes() {
                var scopes = new ArrayList<Scope>();
                for (var s = start; s != null; s = s.getEnclosingScope()) {
                    scopes.add(s);
                }
                // Scopes may be contained in an enclosing scope.
                // The outermost scope contains those elements available via "star import" declarations;
                // the scope within that contains the top level elements of the compilation unit, including any named
                // imports.
                // https://parent.docs.oracle.com/en/java/javase/11/docs/api/jdk.compiler/com/sun/source/tree/Scope.html
                for (var i = 0; i < scopes.size() - 2; i++) {
                    var s = scopes.get(i);
                    walkLocals(s);
                    // Return early?
                    if (tooManyItems(result.size())) {
                        return result;
                    }
                }

                return result;
            }
        }
        return new Walk().walkScopes();
    }

    private boolean tooManyItems(int count) {
        var test = count >= MAX_COMPLETION_ITEMS;
        if (test) LOG.warning(String.format("...# of items %d reached max %s", count, MAX_COMPLETION_ITEMS));
        return test;
    }

    private Set<String> subPackages(String parentPackage) {
        var result = new HashSet<String>();
        Consumer<String> checkClassName =
                name -> {
                    var packageName = Parser.mostName(name);
                    if (packageName.startsWith(parentPackage) && packageName.length() > parentPackage.length()) {
                        var start = parentPackage.length() + 1;
                        var end = packageName.indexOf('.', start);
                        if (end == -1) end = packageName.length();
                        var prefix = packageName.substring(0, end);
                        result.add(prefix);
                    }
                };
        for (var name : parent.jdkClasses) checkClassName.accept(name);
        for (var name : parent.classPathClasses) checkClassName.accept(name);
        return result;
    }

    private static void addKeywords(String[] keywords, String partialName, List<Completion> result) {
        for (var k : keywords) {
            if (matchesPartialName(k, partialName)) {
                result.add(Completion.ofKeyword(k));
            }
        }
    }

    private void completeScopeIdentifiers(String partialName, List<Completion> result) {
        // Add locals
        var locals = scopeMembers(partialName);
        for (var m : locals) {
            result.add(Completion.ofElement(m));
        }
        LOG.info(String.format("...found %d locals", locals.size()));

        // Add static imports
        var staticImports = staticImports(file, partialName);
        for (var m : staticImports) {
            result.add(Completion.ofElement(m));
        }
        LOG.info(String.format("...found %d static imports", staticImports.size()));

        // Add classes
        var startsWithUpperCase = partialName.length() > 0 && Character.isUpperCase(partialName.charAt(0));
        if (startsWithUpperCase) {
            var packageName = Objects.toString(root.getPackageName(), "");
            Predicate<String> matchesPartialName =
                    qualifiedName -> {
                        var className = Parser.lastName(qualifiedName);
                        return matchesPartialName(className, partialName);
                    };

            // Check JDK
            LOG.info("...checking JDK");
            for (var c : parent.jdkClasses) {
                if (tooManyItems(result.size())) return;
                if (!matchesPartialName.test(c)) continue;
                if (isSamePackage(c, packageName) || isPublicClassFile(c)) {
                    result.add(Completion.ofClassName(c, isImported(c)));
                }
            }

            // Check classpath
            LOG.info("...checking classpath");
            var classPathNames = new HashSet<String>();
            for (var c : parent.classPathClasses) {
                if (tooManyItems(result.size())) return;
                if (!matchesPartialName.test(c)) continue;
                if (isSamePackage(c, packageName) || isPublicClassFile(c)) {
                    result.add(Completion.ofClassName(c, isImported(c)));
                    classPathNames.add(c);
                }
            }

            // Check sourcepath
            LOG.info("...checking source path");
            for (var file : FileStore.all()) {
                if (tooManyItems(result.size())) return;
                // If file is in the same package, any class defined in the file is accessible
                var otherPackageName = FileStore.packageName(file);
                var samePackage = otherPackageName.equals(packageName) || otherPackageName.isEmpty();
                // If file is in a different package, only a public class with the same name as the file is accessible
                var maybePublic = matchesPartialName(file.getFileName().toString(), partialName);
                if (samePackage || maybePublic) {
                    result.addAll(accessibleClasses(file, partialName, packageName, classPathNames));
                }
            }
        }
    }

    private boolean isSamePackage(String className, String fromPackage) {
        return Parser.mostName(className).equals(fromPackage);
    }

    private boolean isPublicClassFile(String className) {
        try {
            var platform =
                    parent.fileManager.getJavaFileForInput(
                            StandardLocation.PLATFORM_CLASS_PATH, className, JavaFileObject.Kind.CLASS);
            if (platform != null) return isPublic(platform);
            var classpath =
                    parent.fileManager.getJavaFileForInput(
                            StandardLocation.CLASS_PATH, className, JavaFileObject.Kind.CLASS);
            if (classpath != null) return isPublic(classpath);
            return false;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isPublic(JavaFileObject classFile) {
        try (var in = classFile.openInputStream()) {
            var header = ClassHeader.of(in);
            return header.isPublic;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Completion> accessibleClasses(Path file, String partialName, String fromPackage, Set<String> skip) {
        var parse = Parser.parse(file);
        var toPackage = Objects.toString(parse.getPackageName(), "");
        var samePackage = fromPackage.equals(toPackage) || toPackage.isEmpty();
        var result = new ArrayList<Completion>();
        for (var t : parse.getTypeDecls()) {
            if (!(t instanceof ClassTree)) continue;
            var cls = (ClassTree) t;
            // If class is not accessible, skip it
            var isPublic = cls.getModifiers().getFlags().contains(Modifier.PUBLIC);
            if (!samePackage && !isPublic) continue;
            // If class doesn't match partialName, skip it
            var name = cls.getSimpleName().toString();
            if (!matchesPartialName(name, partialName)) continue;
            if (parse.getPackageName() != null) {
                name = parse.getPackageName() + "." + name;
            }
            // If class was already autocompleted using the classpath, skip it
            if (skip.contains(name)) continue;
            // Otherwise, add this name!
            result.add(Completion.ofClassName(name, isImported(name)));
        }
        return result;
    }

    private List<Element> staticImports(URI file, String partialName) {
        var result = new ArrayList<Element>();
        for (var i : root.getImports()) {
            if (!i.isStatic()) continue;
            var id = (MemberSelectTree) i.getQualifiedIdentifier();
            var path = trees.getPath(root, id.getExpression());
            var el = (TypeElement) trees.getElement(path);
            if (id.getIdentifier().contentEquals("*")) {
                for (var member : el.getEnclosedElements()) {
                    if (matchesPartialName(member.getSimpleName(), partialName)
                            && member.getModifiers().contains(Modifier.STATIC)) {
                        result.add(member);
                        if (tooManyItems(result.size())) return result;
                    }
                }
            } else {
                for (var member : el.getEnclosedElements()) {
                    if (matchesPartialName(member.getSimpleName(), partialName)
                            && member.getModifiers().contains(Modifier.STATIC)) {
                        result.add(member);
                        if (tooManyItems(result.size())) return result;
                    }
                }
            }
        }
        return result;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
