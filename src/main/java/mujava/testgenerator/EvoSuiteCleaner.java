package mujava.testgenerator;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.nodeTypes.NodeWithBody;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class EvoSuiteCleaner {

    // Naming rule: _ESTest -> Test
    private static String renameTestClass(String oldName) {
        if (oldName.endsWith("_ESTest")) {
            return oldName.substring(0, oldName.length() - "_ESTest".length()) + "Test";
        }
        return oldName + "Test";
    }

    public static void main(String[] args) throws Exception {
        String projectName = "commons-lang3-3.17.0";
        String subProjectName = "";  // Fill in the name for multi-module projects; leave empty for single-module projects

        Path base = Paths.get(System.getProperty("user.dir"))
                .resolve("..").resolve("Programs").resolve(projectName);

        base = base.resolve(subProjectName);

        String inp  = base.resolve("evoSuite").resolve("evoSuite").resolve("tests").toString();
        String outp = base.resolve("evoSuite").resolve("clean").toString();
        Path inputDir = Paths.get(inp).toAbsolutePath().normalize();
        Path outputDir = Paths.get(outp).toAbsolutePath().normalize();

        List<Path> javaFiles = Files.walk(inputDir)
                .filter(p -> p.toString().endsWith("_ESTest.java"))
                .collect(Collectors.toList());

        for (Path inFile : javaFiles) {
            transformOne(inFile, inputDir, outputDir);
            System.out.println(inFile);
        }

        System.out.println("Done. Converted files: " + javaFiles.size());
    }

    private static void inlineExecutorSubmitFutureGet(BlockStmt body) {
        NodeList<Statement> stmts = body.getStatements();
        for (int i = 0; i < stmts.size() - 1; i++) {
            Statement s1 = stmts.get(i);
            Statement s2 = stmts.get(i + 1);

            // 1) Match: Future<?> future = executor.submit(new Runnable(){...});
            if (!s1.isExpressionStmt()) continue;
            ExpressionStmt es1 = s1.asExpressionStmt();
            if (!es1.getExpression().isVariableDeclarationExpr()) continue;

            VariableDeclarationExpr vde = es1.getExpression().asVariableDeclarationExpr();
            if (vde.getVariables().size() != 1) continue;

            String futureVar = vde.getVariable(0).getNameAsString();
            if (!vde.getVariable(0).getInitializer().isPresent()) continue;

            if (!vde.getVariable(0).getInitializer().get().isMethodCallExpr()) continue;
            MethodCallExpr submitCall = vde.getVariable(0).getInitializer().get().asMethodCallExpr();

            if (!submitCall.getNameAsString().equals("submit")) continue;
            if (!submitCall.getScope().isPresent()) continue;
            // executor.submit(...)
            if (!submitCall.getScope().get().toString().equals("executor")) continue;
            if (submitCall.getArguments().size() < 1) continue;

            // The first argument of submit: new Runnable(){ public void run(){...}}
            if (!submitCall.getArgument(0).isObjectCreationExpr()) continue;
            ObjectCreationExpr oce = submitCall.getArgument(0).asObjectCreationExpr();
            if (!oce.getAnonymousClassBody().isPresent()) continue;

            // Find the run() method body inside the anonymous class
            Optional<BlockStmt> runBodyOpt = oce.getAnonymousClassBody().get().stream()
                    .filter(BodyDeclaration::isMethodDeclaration)
                    .map(BodyDeclaration::asMethodDeclaration)
                    .filter(md -> md.getNameAsString().equals("run"))
                    .flatMap(md -> md.getBody().isPresent() ? java.util.stream.Stream.of(md.getBody().get()) : java.util.stream.Stream.empty())
                    .findFirst();

            if (!runBodyOpt.isPresent()) continue;

            // 2) Match the next statement: future.get(..., TimeUnit.MILLISECONDS);
            if (!s2.isExpressionStmt()) continue;
            ExpressionStmt es2 = s2.asExpressionStmt();
            if (!es2.getExpression().isMethodCallExpr()) continue;

            MethodCallExpr getCall = es2.getExpression().asMethodCallExpr();
            if (!getCall.getNameAsString().equals("get")) continue;
            if (!getCall.getScope().isPresent()) continue;
            if (!getCall.getScope().get().toString().equals(futureVar)) continue;

            // 3) Perform replacement: replace s1+s2 with the statement list from run()
            BlockStmt runBody = runBodyOpt.get();

            // Delete first, then insert: remove get, then remove the submit declaration
            stmts.remove(i + 1);
            stmts.remove(i);

            // Insert the statements from run back at the current position i
            NodeList<Statement> runStmts = runBody.getStatements();
            for (int k = 0; k < runStmts.size(); k++) {
                stmts.add(i + k, runStmts.get(k).clone());
            }

            // Move i back by one step and continue scanning (to avoid skipping)
            i = Math.max(-1, i - 1);
        }
    }

    private static void transformOne(Path inFile, Path inputRoot, Path outputRoot) throws IOException {

        byte[] bytes = Files.readAllBytes(inFile);
        String src = new String(bytes, StandardCharsets.UTF_8);
        CompilationUnit cu = StaticJavaParser.parse(src);

        String fileBase = inFile.getFileName().toString().replace(".java", "");

        Optional<ClassOrInterfaceDeclaration> primaryOpt =
                cu.findFirst(ClassOrInterfaceDeclaration.class,
                        c -> c.isPublic() && !c.isInterface() && c.getNameAsString().equals(fileBase));

        if (!primaryOpt.isPresent()) {
            throw new IllegalStateException("No matching public class '" + fileBase + "' in: " + inFile);
        }

        ClassOrInterfaceDeclaration clazz = primaryOpt.get();

        String oldName = clazz.getNameAsString();
        String newName = renameTestClass(oldName);

        // 1) Rename the class
        clazz.setName(newName);

        // 1.1) Rename constructor names as well (otherwise compilation will fail)
        for (ConstructorDeclaration cd : clazz.getConstructors()) {
            cd.setName(newName);
        }

        // 2) Remove extends xxx_ESTest_scaffolding
        removeScaffoldingExtends(clazz);

        // 2.1) Remove EvoSuite Runner annotations: @RunWith(EvoRunner.class) / @EvoRunnerParameters(...)
        removeEvoSuiteRunnerAnnotations(clazz);

        // 2.2) Quickly extract "EvoSuite-related symbol names" from imports for later locating/triggering cleanup
        //     For example: EvoRunner, EvoRunnerParameters, ViolatedAssumptionAnswer, MockFileInputStream, EvoAssertions, etc.
        java.util.Set<String> evosuiteImportSymbols = cu.getImports().stream()
                .map(NodeWithName::getNameAsString)
                .filter(n -> n.startsWith("org.evosuite"))
                .map(n -> {
                    int k = n.lastIndexOf('.');
                    return k >= 0 ? n.substring(k + 1) : n;
                })
                .collect(java.util.stream.Collectors.toSet());

        // 3) Clean imports (scaffolding / all org.evosuite.*)
        cu.getImports().removeIf(imp -> {
            String n = imp.getNameAsString();
            return n.endsWith("_ESTest_scaffolding") || n.startsWith("org.evosuite");
        });

        // 3.1) If the file imports static members from org.evosuite in forms other than EvoRunnerParameters / EvoRunner, remove them as well
        cu.getImports().removeIf(imp -> imp.isStatic() && imp.getNameAsString().startsWith("org.evosuite"));

        // 4) Handle "Undeclared exception!": wrap the statement with try/catch + fail
        for (MethodDeclaration md : clazz.getMethods()) {
            if (!isJUnit4Test(md)) continue;
            md.getBody().ifPresent(body -> {
                wrapUndeclaredExceptionStatements(body);
                removeVerifyExceptionCalls(body);   // <--- newly added
                // 4.1) Recursive cleanup: starting from statements that contain EvoSuite dependencies, delete related statements along the "recursive chain/dependency chain"
                // Focus on: ViolatedAssumptionAnswer, MockFileInputStream (and remaining org.evosuite.runtime.*)
                removeEvoSuiteTaintedChainsRecursively(body, evosuiteImportSymbols);
                inlineExecutorSubmitFutureGet(body);
            });
        }

        // 5) Some files may internally reference the old class name (rare); do one replacement here: SimpleName == oldName -> newName
        cu.findAll(SimpleName.class).forEach(sn -> {
            if (sn.getIdentifier().equals(oldName)) sn.setIdentifier(newName);
        });

        // The output file name must also be changed
        String outFileName = newName + ".java";
        writeOut(inFile, inputRoot, outputRoot, outFileName, cu.toString());
    }

    private static void removeEvoSuiteRunnerAnnotations(ClassOrInterfaceDeclaration clazz) {
        clazz.getAnnotations().removeIf(a -> {
            String annName = a.getNameAsString();

            // @EvoRunnerParameters(...)
            if (annName.equals("EvoRunnerParameters") || annName.endsWith(".EvoRunnerParameters")) {
                return true;
            }

            // @RunWith(EvoRunner.class) — only remove RunWith when its arguments contain EvoRunner
            if (annName.equals("RunWith") || annName.endsWith(".RunWith")) {
                String asText = a.toString();
                return asText.contains("EvoRunner");
            }
            return false;
        });
    }

    private static boolean isJUnit4Test(MethodDeclaration md) {
        return md.getAnnotations().stream().anyMatch(a -> {
            if (a.isMarkerAnnotationExpr()) {
                MarkerAnnotationExpr ma = a.asMarkerAnnotationExpr();
                return ma.getNameAsString().equals("Test") || ma.getNameAsString().endsWith(".Test");
            }
            return a.getNameAsString().equals("Test") || a.getNameAsString().endsWith(".Test");
        });
    }

    private static void removeScaffoldingExtends(ClassOrInterfaceDeclaration clazz) {
        NodeList<ClassOrInterfaceType> exts = clazz.getExtendedTypes();
        if (exts.isEmpty()) return;

        clazz.setExtendedTypes(new NodeList<>(
                exts.stream()
                        .filter(t -> !t.getNameAsString().endsWith("_ESTest_scaffolding"))
                        .collect(Collectors.toList())
        ));
    }

    private static void wrapUndeclaredExceptionStatements(BlockStmt body) {
        NodeList<Statement> stmts = body.getStatements();
        if (stmts.isEmpty()) return;

        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);

            // The comment is attached to this statement
            if (hasUndeclaredExceptionComment(s.getComment().orElse(null))) {
                stmts.set(i, wrapAsExpectedException(s));
                continue;
            }

            // If the comment is attached to an empty statement, wrap the next statement
            if (s.isEmptyStmt() && hasUndeclaredExceptionComment(s.getComment().orElse(null))) {
                if (i + 1 < stmts.size()) {
                    Statement target = stmts.get(i + 1);
                    stmts.set(i + 1, wrapAsExpectedException(target));
                }
            }
        }
    }

    private static boolean hasUndeclaredExceptionComment(Comment c) {
        if (c == null) return false;
        String txt = c.getContent();
        return txt != null && txt.contains("Undeclared exception");
    }

    private static void removeVerifyExceptionCalls(BlockStmt body) {
        // Delete statements like: verifyException(...); or SomeClass.verifyException(...);
        body.findAll(ExpressionStmt.class).forEach(es -> {
            if (!es.getExpression().isMethodCallExpr()) return;

            MethodCallExpr mc = es.getExpression().asMethodCallExpr();
            String methodName = mc.getNameAsString();
            if (methodName.equals("verifyException") || methodName.equals("verifyNoException")) {
                es.remove();
            }
        });
    }

    /**
     * Recursive cleanup:
     * 1) First perform "in-place fixes" (fix whatever can be fixed), e.g. mock(xxx, new ViolatedAssumptionAnswer()) -> mock(xxx)
     * 2) If EvoSuite runtime dependencies still exist, start from the triggering statement and clean related statements along the "dependency chain/recursive chain":
     *    - Trigger condition: the statement text contains MockFileInputStream / ViolatedAssumptionAnswer / org.evosuite.runtime.* etc.
     *    - Dependency propagation: if later statements use variables defined/assigned by the triggering statement, delete them as well; if they define new variables, continue the taint propagation.
     *
     * Note: this strategy is conservative (prefer deleting more rather than less); the goal is to ensure the cleaned tests can compile/run stably.
     */
    private static void removeEvoSuiteTaintedChainsRecursively(BlockStmt body, java.util.Set<String> evosuiteImportSymbols) {
        // (A) Do not try "fix-and-keep"; instead, treat the trigger point as the start of chain deletion (more robust without the EvoSuite runtime environment)

        // (B) Recursively clean by "trigger statement -> dependency chain"
        java.util.Set<String> tainted = new java.util.HashSet<>();
        removeTaintedStatementsInBlock(body, tainted, evosuiteImportSymbols);
    }

    private static void removeTaintedStatementsInBlock(BlockStmt block, java.util.Set<String> tainted, java.util.Set<String> evosuiteImportSymbols) {
        NodeList<Statement> stmts = block.getStatements();
        for (int i = 0; i < stmts.size(); ) {
            Statement st = stmts.get(i);

            // 1) Recursively process nested blocks (note: recursion is done before the deletion check to avoid missing deeper triggers)
            //    But if the current statement itself will be deleted, processing its nested blocks is meaningless.

            boolean triggered = containsEvoSuiteArtifact(st, evosuiteImportSymbols);
            boolean usesTainted = usesAnyTaintedVar(st, tainted);

            if (triggered || usesTainted) {
                // 2) Record variables "defined/assigned" by this statement and add them to the taint set (for later dependency-chain deletion)
                tainted.addAll(extractDefinedOrAssignedVars(st));

                // 3) Delete the statement directly
                stmts.remove(i);
                continue; // do not increment i
            }

            // 4) If the current statement is kept, recursively clean its inner blocks
            recursivelyCleanInnerBlocks(st, tainted, evosuiteImportSymbols);

            // 5) Next statement
            i++;
        }
    }

    private static void recursivelyCleanInnerBlocks(Statement st, java.util.Set<String> tainted, java.util.Set<String> evosuiteImportSymbols) {
        if (st.isBlockStmt()) {
            removeTaintedStatementsInBlock(st.asBlockStmt(), tainted, evosuiteImportSymbols);
            return;
        }
        if (st.isIfStmt()) {
            IfStmt is = st.asIfStmt();
            if (is.getThenStmt().isBlockStmt()) removeTaintedStatementsInBlock(is.getThenStmt().asBlockStmt(), tainted, evosuiteImportSymbols);
            else {
                // The then branch is a single statement: wrap it into a block for unified processing
                BlockStmt b = new BlockStmt(new NodeList<>(is.getThenStmt().clone()));
                removeTaintedStatementsInBlock(b, tainted, evosuiteImportSymbols);
                if (b.getStatements().isEmpty()) is.setThenStmt(new EmptyStmt());
                else is.setThenStmt(b);
            }
            is.getElseStmt().ifPresent(es -> {
                if (es.isBlockStmt()) removeTaintedStatementsInBlock(es.asBlockStmt(), tainted, evosuiteImportSymbols);
                else {
                    BlockStmt b = new BlockStmt(new NodeList<>(es.clone()));
                    removeTaintedStatementsInBlock(b, tainted, evosuiteImportSymbols);
                    if (b.getStatements().isEmpty()) is.setElseStmt(new EmptyStmt());
                    else is.setElseStmt(b);
                }
            });
            return;
        }
        if (st.isForStmt()) {
            Statement body = st.asForStmt().getBody();
            wrapAndCleanLoopBody(st.asForStmt(), body, tainted, evosuiteImportSymbols);
            return;
        }
        if (st.isForEachStmt()) {
            Statement body = st.asForEachStmt().getBody();
            wrapAndCleanLoopBody(st.asForEachStmt(), body, tainted, evosuiteImportSymbols);
            return;
        }
        if (st.isWhileStmt()) {
            Statement body = st.asWhileStmt().getBody();
            wrapAndCleanLoopBody(st.asWhileStmt(), body, tainted, evosuiteImportSymbols);
            return;
        }
        if (st.isDoStmt()) {
            Statement body = st.asDoStmt().getBody();
            wrapAndCleanLoopBody(st.asDoStmt(), body, tainted, evosuiteImportSymbols);
            return;
        }
        if (st.isTryStmt()) {
            TryStmt ts = st.asTryStmt();
            removeTaintedStatementsInBlock(ts.getTryBlock(), tainted, evosuiteImportSymbols);
            ts.getCatchClauses().forEach(cc -> removeTaintedStatementsInBlock(cc.getBody(), tainted, evosuiteImportSymbols));
            ts.getFinallyBlock().ifPresent(fb -> removeTaintedStatementsInBlock(fb, tainted, evosuiteImportSymbols));
        }
    }

    private static void wrapAndCleanLoopBody(NodeWithBody<?> loop, Statement body, java.util.Set<String> tainted, java.util.Set<String> evosuiteImportSymbols) {
        if (body.isBlockStmt()) {
            removeTaintedStatementsInBlock(body.asBlockStmt(), tainted, evosuiteImportSymbols);
        } else {
            BlockStmt b = new BlockStmt(new NodeList<>(body.clone()));
            removeTaintedStatementsInBlock(b, tainted, evosuiteImportSymbols);
            if (b.getStatements().isEmpty()) loop.setBody(new EmptyStmt());
            else loop.setBody(b);
        }
    }

    private static boolean containsEvoSuiteArtifact(Statement st, java.util.Set<String> evosuiteImportSymbols) {
        String txt = st.toString();
        // (0) Symbol names extracted from imports: used for quick locating (e.g. EvoAssertions, ViolatedAssumptionAnswer, MockFileInputStream, etc.)
        if (evosuiteImportSymbols != null && !evosuiteImportSymbols.isEmpty()) {
            for (String sym : evosuiteImportSymbols) {
                if (sym != null && !sym.isEmpty() && txt.contains(sym)) return true;
            }
        }
        // (0.5) If EvoSuite shaded Mockito is used (Mockito usually appears in imports),
        //       then treat mock/doReturn/when, etc. as trigger points as well, so cleanup can start chain deletion from the first Mockito statement.
        if (evosuiteImportSymbols != null && evosuiteImportSymbols.contains("Mockito")) {
            for (MethodCallExpr mc : st.findAll(MethodCallExpr.class)) {
                String n = mc.getNameAsString();
                if (n.equals("mock") || n.equals("doReturn") || n.equals("doThrow") || n.equals("doAnswer")
                        || n.equals("when") || n.equals("verify") || n.equals("reset")
                        || n.equals("verifyNoMoreInteractions") || n.equals("verifyNoInteractions")) {
                    return true;
                }
            }
        }

        // The two categories you explicitly reported:
        if (txt.contains("ViolatedAssumptionAnswer")) return true;
        if (txt.contains("MockFileInputStream")) return true;

        // Fallback: org.evosuite.runtime.* still remains
        if (txt.contains("org.evosuite.runtime")) return true;
        if (txt.contains("org.evosuite.runtime.mock")) return true;
        if (txt.contains("EvoAssertions")) return true;
        return false;
    }

    private static boolean usesAnyTaintedVar(Statement st, java.util.Set<String> tainted) {
        if (tainted.isEmpty()) return false;
        // Determine usage only by AST NameExpr (more reliable than plain string contains)
        for (NameExpr ne : st.findAll(NameExpr.class)) {
            if (tainted.contains(ne.getNameAsString())) return true;
        }
        // The scope of a MethodCall may be a FieldAccess like this.xxx; handle that here as well
        for (FieldAccessExpr fa : st.findAll(FieldAccessExpr.class)) {
            Expression scope = fa.getScope();
            if (scope.isNameExpr() && tainted.contains(scope.asNameExpr().getNameAsString())) return true;
        }
        return false;
    }

    private static java.util.Set<String> extractDefinedOrAssignedVars(Statement st) {
        java.util.Set<String> defs = new java.util.HashSet<>();

        // a) Variable declaration: T x = ...;
        st.findAll(VariableDeclarationExpr.class).forEach(vde ->
                vde.getVariables().forEach(v -> defs.add(v.getNameAsString()))
        );

        // b) Assignment: x = ...; or this.x = ...; (only simple NameExpr is taken)
        st.findAll(AssignExpr.class).forEach(ae -> {
            Expression target = ae.getTarget();
            if (target.isNameExpr()) defs.add(target.asNameExpr().getNameAsString());
        });

        return defs;
    }

    private static Statement wrapAsExpectedException(Statement original) {
        // try { original; org.junit.Assert.fail("Expected exception"); } catch(Throwable t) {}
        BlockStmt tryBlock = new BlockStmt();
        tryBlock.addStatement(original.clone());
        tryBlock.addStatement(new ExpressionStmt(
                new MethodCallExpr(
                        new NameExpr("org.junit.Assert"),
                        "fail",
                        NodeList.nodeList(new StringLiteralExpr("Expected exception"))
                )
        ));

        CatchClause cc = new CatchClause();
        cc.setParameter(new com.github.javaparser.ast.body.Parameter(
                StaticJavaParser.parseType("Throwable"), "t"
        ));
        cc.setBody(new BlockStmt()); // empty

        TryStmt ts = new TryStmt();
        ts.setTryBlock(tryBlock);
        ts.setCatchClauses(NodeList.nodeList(cc));
        return ts;
    }

    private static void writeOut(Path inFile, Path inputRoot, Path outputRoot,
                                 String outFileName, String content) throws IOException {
        Path relative = inputRoot.relativize(inFile.getParent());
        Path outDir = outputRoot.resolve(relative);
        Files.createDirectories(outDir);

        Path outFile = outDir.resolve(outFileName);
        Files.write(outFile, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
