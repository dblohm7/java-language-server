package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.javacs.lsp.*;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class JavaCompilerServiceTest {
    static {
        Main.setRootFormat();
    }

    private JavaCompilerService compiler =
            new JavaCompilerService(Collections.emptySet(), Collections.emptySet(), Collections.emptySet());

    static Path simpleProjectSrc() {
        return Paths.get("src/test/examples/simple-project").normalize();
    }

    @Before
    public void setWorkspaceRoot() {
        FileStore.setWorkspaceRoots(Set.of(simpleProjectSrc()));
    }

    static String contents(String resourceFile) {
        var root = JavaCompilerServiceTest.simpleProjectSrc();
        var file = root.resolve(resourceFile);
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var join = new StringJoiner("\n");
        for (var l : lines) join.add(l);
        return join.toString();
    }

    static URI resourceUri(String resourceFile) {
        var root = JavaCompilerServiceTest.simpleProjectSrc();
        var file = root.resolve(resourceFile);
        return file.toUri();
    }

    @Test
    public void element() {
        var uri = resourceUri("HelloWorld.java");
        var found = compiler.compileFocus(uri, 3, 24).element(uri, 3, 24).get();

        assertThat(found.getSimpleName(), hasToString(containsString("println")));
    }

    @Test
    public void elementWithError() {
        var uri = resourceUri("CompleteMembers.java");
        var found = compiler.compileFocus(uri, 3, 12).element(uri, 3, 12);

        assertThat(found, notNullValue());
    }

    private List<String> filterText(List<CompletionItem> found) {
        var result = new ArrayList<String>();
        for (var c : found) {
            if (c.filterText != null) {
                result.add(c.filterText);
            } else {
                result.add(c.label);
            }
        }
        return result;
    }

    @Test
    public void identifiers() {
        var uri = resourceUri("CompleteIdentifiers.java");
        var ctx = Parser.parseFile(uri).completionContext(13, 21);
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found =
                focus.completeIdentifiers(
                        uri,
                        ctx.line,
                        ctx.character,
                        ctx.inClass,
                        ctx.inMethod,
                        ctx.partialName,
                        ctx.addParens,
                        ctx.addSemi);
        var names = filterText(found);
        assertThat(names, hasItem("completeLocal"));
        assertThat(names, hasItem("completeParam"));
        //        assertThat(names, hasItem("super"));
        //        assertThat(names, hasItem("this"));
        assertThat(names, hasItem("completeOtherMethod"));
        assertThat(names, hasItem("completeInnerField"));
        assertThat(names, hasItem("completeOuterField"));
        assertThat(names, hasItem("completeOuterStatic"));
        // assertThat(names, hasItem("CompleteIdentifiers"));
    }

    @Test
    public void identifiersInMiddle() {
        var uri = resourceUri("CompleteInMiddle.java");
        var ctx = Parser.parseFile(uri).completionContext(13, 21);
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found =
                focus.completeIdentifiers(
                        uri,
                        ctx.line,
                        ctx.character,
                        ctx.inClass,
                        ctx.inMethod,
                        ctx.partialName,
                        ctx.addParens,
                        ctx.addSemi);
        var names = filterText(found);
        assertThat(names, hasItem("completeLocal"));
        assertThat(names, hasItem("completeParam"));
        //        assertThat(names, hasItem("super"));
        //        assertThat(names, hasItem("this"));
        assertThat(names, hasItem("completeOtherMethod"));
        assertThat(names, hasItem("completeInnerField"));
        assertThat(names, hasItem("completeOuterField"));
        assertThat(names, hasItem("completeOuterStatic"));
        // assertThat(names, hasItem("CompleteInMiddle"));
    }

    @Test
    public void completeIdentifiers() {
        var uri = resourceUri("CompleteIdentifiers.java");
        var ctx = Parser.parseFile(uri).completionContext(13, 21);
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found =
                focus.completeIdentifiers(
                        uri,
                        ctx.line,
                        ctx.character,
                        ctx.inClass,
                        ctx.inMethod,
                        ctx.partialName,
                        ctx.addParens,
                        ctx.addSemi);
        var names = filterText(found);
        assertThat(names, hasItem("completeLocal"));
        assertThat(names, hasItem("completeParam"));
        //        assertThat(names, hasItem("super"));
        //        assertThat(names, hasItem("this"));
        assertThat(names, hasItem("completeOtherMethod"));
        assertThat(names, hasItem("completeInnerField"));
        assertThat(names, hasItem("completeOuterField"));
        assertThat(names, hasItem("completeOuterStatic"));
        //        assertThat(names, hasItem("CompleteIdentifiers"));
    }

    @Test
    public void members() {
        var uri = resourceUri("CompleteMembers.java");
        var focus = compiler.compileFocus(uri, 3, 14);
        var found = focus.completeMembers(uri, 3, 14, true, true);
        var names = filterText(found);
        assertThat(names, hasItem("subMethod"));
        assertThat(names, hasItem("superMethod"));
        assertThat(names, hasItem("equals"));
    }

    @Test
    public void completeMembers() {
        var uri = resourceUri("CompleteMembers.java");
        var ctx = Parser.parseFile(uri).completionContext(3, 15);
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found = focus.completeMembers(uri, ctx.line, ctx.character, ctx.addParens, ctx.addSemi);
        var names = filterText(found);
        assertThat(names, hasItem("subMethod"));
        assertThat(names, hasItem("superMethod"));
        assertThat(names, hasItem("equals"));
    }

    @Test
    public void completeExpression() {
        var uri = resourceUri("CompleteExpression.java");
        var ctx = Parser.parseFile(uri).completionContext(3, 37);
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found = focus.completeMembers(uri, ctx.line, ctx.character, ctx.addParens, ctx.addSemi);
        var names = filterText(found);
        assertThat(names, hasItem("instanceMethod"));
        assertThat(names, not(hasItem("create")));
        assertThat(names, hasItem("equals"));
    }

    @Test
    public void completeClass() {
        var uri = resourceUri("CompleteClass.java");
        var ctx = Parser.parseFile(uri).completionContext(3, 23);
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found = focus.completeMembers(uri, ctx.line, ctx.character, ctx.addParens, ctx.addSemi);
        var names = filterText(found);
        assertThat(names, hasItems("staticMethod", "staticField"));
        assertThat(names, hasItems("class"));
        assertThat(names, not(hasItem("instanceMethod")));
        assertThat(names, not(hasItem("instanceField")));
    }

    @Test
    public void completeImports() {
        var uri = resourceUri("CompleteImports.java");
        var ctx = Parser.parseFile(uri).completionContext(1, 18);
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found = focus.completeMembers(uri, ctx.line, ctx.character, ctx.addParens, ctx.addSemi);
        var names = filterText(found);
        assertThat(names, hasItem("List"));
        assertThat(names, hasItem("concurrent"));
    }

    @Test
    public void overloads() {
        var uri = resourceUri("Overloads.java");
        var found = compiler.compileFocus(uri, 3, 15).signatureHelp(uri, 3, 15).get();
        var strings = found.signatures.stream().map(s -> s.label).collect(Collectors.toList());

        assertThat(strings, hasItem(containsString("print(int i)")));
        assertThat(strings, hasItem(containsString("print(String s)")));
    }

    @Test
    public void reportErrors() {
        var uri = resourceUri("HasError.java");
        var files = Collections.singleton(new SourceFileObject(uri));
        var diags = compiler.compileBatch(files).reportErrors();
        assertThat(diags, not(empty()));
    }

    private static List<String> errorStrings(Collection<PublishDiagnosticsParams> list) {
        var strings = new ArrayList<String>();
        for (var group : list) {
            for (var d : group.diagnostics) {
                var file = StringSearch.fileName(group.uri);
                var line = d.range.start.line;
                var msg = d.message;
                var string = String.format("%s(%d): %s", file, line, msg);
                strings.add(string);
            }
        }
        return strings;
    }

    // TODO get these back somehow
    @Test
    @Ignore
    public void errorProne() {
        var uri = resourceUri("ErrorProne.java");
        var files = Collections.singleton(new SourceFileObject(uri));
        var diags = compiler.compileBatch(files).reportErrors();
        var strings = errorStrings(diags);
        assertThat(strings, hasItem(containsString("ErrorProne.java(7): [CollectionIncompatibleType]")));
    }

    // TODO get these back somehow
    @Test
    @Ignore
    public void unusedVar() {
        var uri = resourceUri("UnusedVar.java");
        var files = Collections.singleton(new SourceFileObject(uri));
        var diags = compiler.compileBatch(files).reportErrors();
        var strings = errorStrings(diags);
        assertThat(strings, hasItem(containsString("UnusedVar.java(3): [Unused]")));
    }

    @Test
    public void localDoc() {
        var uri = resourceUri("LocalMethodDoc.java");
        var invocation = compiler.compileFocus(uri, 3, 21).signatureHelp(uri, 3, 21).get();
        var method = invocation.signatures.get(invocation.activeSignature);
        assertThat(method.documentation.value, containsString("A great method"));
    }

    @Test
    public void fixImports() {
        var uri = resourceUri("MissingImport.java");
        var qualifiedNames = compiler.compileBatch(Set.of(new SourceFileObject(uri))).fixImports(uri);
        assertThat(qualifiedNames, hasItem("java.util.List"));
    }

    @Test
    public void dontImportEnum() {
        var uri = resourceUri("DontImportEnum.java");
        var qualifiedNames = compiler.compileBatch(Set.of(new SourceFileObject(uri))).fixImports(uri);
        assertThat(qualifiedNames, contains("java.nio.file.AccessMode", "java.util.ArrayList"));
    }

    @Test
    public void matchesPartialName() {
        assertTrue(StringSearch.matchesPartialName("foobar", "foo"));
        assertFalse(StringSearch.matchesPartialName("foo", "foobar"));
    }

    @Test
    public void pruneMethods() {
        var actual = Parser.parseFile(resourceUri("PruneMethods.java")).prune(6, 19);
        var expected = contents("PruneMethods_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneToEndOfBlock() {
        var actual = Parser.parseFile(resourceUri("PruneToEndOfBlock.java")).prune(4, 18);
        var expected = contents("PruneToEndOfBlock_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneMiddle() {
        var actual = Parser.parseFile(resourceUri("PruneMiddle.java")).prune(4, 12);
        var expected = contents("PruneMiddle_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneDot() {
        var actual = Parser.parseFile(resourceUri("PruneDot.java")).prune(3, 11);
        var expected = contents("PruneDot_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneWords() {
        var actual = Parser.parseFile(resourceUri("PruneWords.java")).prune("word");
        var expected = contents("PruneWords_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }
}
