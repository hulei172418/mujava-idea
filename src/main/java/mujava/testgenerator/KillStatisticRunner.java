package mujava.testgenerator;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;
import java.util.logging.Formatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static mujava.util.ExcelUtils.readExcel;
import static mujava.util.LocalJarFinder.buildProjectClasspath;


/**
 * Mutant kill statistics runner (JUnit4, forked JVM).
 *
 * Output format matches the user's sample:
 *   test_results={test0=..., test1=...};
 *   mutant_results={MUT_1=test0, test3, ...}
 *   killed_mutants=[...];
 *   live_mutants=[...];
 *   mutant_score=...
 *
 * Key design: run each TEST (test0/test1/...) individually on ORIGINAL and on each MUTANT,
 * so we can attribute which test kills which mutant.
 */
public class KillStatisticRunner {
    private static final Logger logger = Logger.getLogger(EvoSuiteTestGenerator.class.getName());
    private static String operatorName;
    private static String lineNo;
    private static String methodName;
    private static String className;
    private static String className_F;
    private static String mutationStatement;
    private static String packageName;
    private static String projectName;
    private static String filepath;
    private static final Set<String> classSet = new HashSet<>();
    private static final Map<String, String> projectKV = new HashMap<>();
    private static String workDir;
    private static String cp;
    private static String targetCls;

    private static boolean logAppend = false;

    public static void main(String[] args) throws Exception {
        String mutantPath = "../MutantParse/data/mutant_statistic_1.xlsx";
        dataIterator(mutantPath);
    }

    private static List<List<Object>> readExcelFile(String filePath) {
        try {
            return readExcel(filePath, 0);
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private static void dataIterator(String filePath) throws Exception {
        String fileName = new File(filePath).getName();
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;
        String logFile = "logs/" + baseName + ".log";
        setupLogger(logFile);
        List<List<Object>> excelData = readExcelFile(filePath);
        for (int i = 1; i < excelData.size(); i++) {
            operatorName = (String) excelData.get(i).get(0);
            lineNo = (String) excelData.get(i).get(1);
            methodName = (String) excelData.get(i).get(2);
            className = (String) excelData.get(i).get(3);
            className_F = (String) excelData.get(i).get(4);
            mutationStatement = (String) excelData.get(i).get(5);
            packageName = (String) excelData.get(i).get(6);
            projectName = (String) excelData.get(i).get(7);
            filepath = new File((String) excelData.get(i).get(8)).getParent().replace("\\", "/");
            filepath = filepath.replace("//?/", "");
            workDir = getCurr(filepath);
            targetCls = packageName.replaceFirst("^(?:main(?:\\.java)?|java)\\.", "");
            String tag = workDir+"/result/"+packageName;
            if (classSet.contains(tag)){
                continue;
            }else {
                classSet.add(tag);
            }
            if (projectKV.containsKey(projectName)) {
                cp = projectKV.get(projectName);
            } else {
                cp = buildProjectClasspath(workDir+"/"+projectName);
                projectKV.put(projectName, cp);
            }
            System.out.println(workDir+"/src/"+packageName.replace(".", "/"));
            runKill();
        }
    }

    private static void setupLogger(String logFile) throws IOException {
        Files.createDirectories(Paths.get("logs"));

        FileHandler fileHandler = new FileHandler(logFile, logAppend);
        fileHandler.setFormatter(new KillStatisticRunner.SimpleFormatterWithoutPrefix());

        Logger rootLogger = Logger.getLogger("");
        rootLogger.addHandler(fileHandler);
        rootLogger.setLevel(Level.SEVERE);

        // Remove the default console output
        Handler[] handlers = rootLogger.getHandlers();
        for (Handler handler : handlers) {
            if (handler instanceof java.util.logging.ConsoleHandler) {
                rootLogger.removeHandler(handler);
            }
        }

        logger.info("Logger initialized.");
    }

    private static class SimpleFormatterWithoutPrefix extends Formatter {
        @Override
        public String format(LogRecord record) {
            return record.getLevel() + ": " + record.getMessage() + "\n";
        }
    }

    public static String getCurr(String workDir) {

        Pattern p = Pattern.compile("(?i)^(.*?)(?=[\\\\/]+result(?:[\\\\/]+|$))");
        Matcher m = p.matcher(workDir);

        if (m.find()) {
            String root = m.group(1);
            // System.out.println(root);
            return root;
        } else {
            throw new IllegalArgumentException("Failed to match result in path: " + workDir);
        }
    }

    public static void runKill() throws Exception {

        String reDot = (projectName.contains("commons-math") || projectName.contains("commons-numbers")) ? "/../../" : "/../";
        String testDir = workDir+"/evoSuite/classes";
        String subDir = packageName.replaceFirst("^(?:main(?:\\.java)?|java)\\.", "").replace(".", "/");

        String javaExe = "java.exe";
        // String testClassesDir = testDir+"/"+subDir+"Test.class";
        String testClassesDir = "E:/PHD/testJava/Programs/commons-csv-1.2/evoSuite/classes/org/apache/commons/csv";
        String origClassesDir = workDir+"/result/"+packageName+"/original/"+className_F+".class";
        String mutantsRootDir = workDir+"/result/"+packageName+"/traditional_mutants";
        String depsCp = cp;
        int timeoutSec = 60;
        String outFile = workDir+"/result/"+packageName+"/traditional_mutants/"+"kill_statistic.txt";

        // collect test class FQNs
        List<String> testClasses = listTestClassFQNs(Paths.get(testClassesDir));
        if (testClasses.isEmpty()) throw new FileNotFoundException("No test .class under: " + testClassesDir);

        // label them as test0, test1, ...
        List<String> testLabels = new ArrayList<>();
        for (int i = 0; i < testClasses.size(); i++) testLabels.add("test" + i);

        // discover mutants as leaf dirs containing .class
        List<Path> mutantDirs = discoverMutantClassRoots(Paths.get(mutantsRootDir));
        if (mutantDirs.isEmpty()) throw new FileNotFoundException("No mutant class directories under: " + mutantsRootDir);

        // runner cp entry so child can invoke this class
        String runnerCpEntry = getSelfCpEntry();

        // Build per-test baseline on ORIGINAL:
        // We only consider a test valid if it passes on original (exit=0).
        Map<String, Boolean> testValid = new LinkedHashMap<>();
        for (int i = 0; i < testClasses.size(); i++) {
            String testFqn = testClasses.get(i);
            String cpOriginal = joinCp(runnerCpEntry, testClassesDir, origClassesDir, depsCp);
            RunOutcome base = forkRunSingle(javaExe, cpOriginal, testFqn, timeoutSec);
            testValid.put(testLabels.get(i), base.exitCode == 0 && !base.timedOut);
        }

        // test_results: testLabel -> mutants killed by this test
        Map<String, Set<String>> testResults = new LinkedHashMap<>();
        for (String tl : testLabels) testResults.put(tl, new TreeSet<>());

        // mutant_results: mutantId -> tests that kill it
        Map<String, Set<String>> mutantResults = new LinkedHashMap<>();

        // run each mutant against each test (only if test is valid on original)
        for (Path mdir : mutantDirs) {
            String mutantId = mdir.getFileName().toString(); // e.g., AOIS_10
            mutantResults.putIfAbsent(mutantId, new TreeSet<>());

            String cpMutant = joinCp(runnerCpEntry, testClassesDir, mdir.toString(), depsCp, origClassesDir);

            for (int i = 0; i < testClasses.size(); i++) {
                String tl = testLabels.get(i);
                if (!Boolean.TRUE.equals(testValid.get(tl))) continue;

                String testFqn = testClasses.get(i);
                RunOutcome mut = forkRunSingle(javaExe, cpMutant, testFqn, timeoutSec);

                if (mut.timedOut) continue; // treat timeout as "no kill" for attribution
                if (mut.exitCode != 0) {
                    testResults.get(tl).add(mutantId);
                    mutantResults.get(mutantId).add(tl);
                }
            }
        }

        // killed/live lists based on whether any test kills the mutant
        List<String> killed = new ArrayList<>();
        List<String> live = new ArrayList<>();
        for (String mid : mutantResults.keySet()) {
            if (!mutantResults.get(mid).isEmpty()) killed.add(mid);
            else live.add(mid);
        }

        double score = (killed.size() + live.size()) == 0 ? 0.0 : (killed.size() * 100.0) / (killed.size() + live.size());

        // write output with style like sample
        writeKillStatistic(Paths.get(outFile), testResults, mutantResults, killed, live, score);
        System.out.println("[OK] kill_statistic written to: " + outFile);
    }

    // ---------------- child mode: run ONE test class ----------------
    private static void childRunSingleTest(String testClass, String resultFile) throws IOException {
        Result res;
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> c = Class.forName(testClass, true, cl);
            JUnitCore core = new JUnitCore();
            res = core.run(c);
        } catch (Throwable e) {
            writeResult(Paths.get(resultFile), 1, 0, 1,
                    Collections.singletonList("BOOTSTRAP_ERROR: " + e.getClass().getName() + ": " + safeMsg(e)));
            System.exit(1);
            return;
        }

        List<String> failures = new ArrayList<>();
        for (Failure f : res.getFailures()) failures.add(f.getTestHeader() + " :: " + safeMsg(f.getException()));
        int exit = res.wasSuccessful() ? 0 : 1;
        writeResult(Paths.get(resultFile), exit, res.getRunCount(), res.getFailureCount(), failures);
        System.exit(exit);
    }

    private static void writeResult(Path file, int exit, int runCount, int failureCount, List<String> failures) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("exit=" + exit);
        lines.add("runCount=" + runCount);
        lines.add("failureCount=" + failureCount);
        for (String f : failures) lines.add("failure=" + f);
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    // ---------------- fork runner (single test) ----------------
    private static RunOutcome forkRunSingle(String javaExe, String classpath, String testFqn, int timeoutSec) throws Exception {
        Path tmpDir = Files.createTempDirectory("killrun_");
        Path resultFile = tmpDir.resolve("result.txt");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-cp"); cmd.add(classpath);
        cmd.add(KillStatisticRunner.class.getName());
        cmd.add("__run__");
        cmd.add("--testClass");  cmd.add(testFqn);
        cmd.add("--resultFile"); cmd.add(resultFile.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        RunOutcome out = new RunOutcome();

        boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            out.timedOut = true;
            p.destroyForcibly();
            out.exitCode = 124;
            return out;
        }
        out.exitCode = p.exitValue();

        if (Files.exists(resultFile)) parseResultFile(resultFile, out);
        return out;
    }

    private static void parseResultFile(Path resultFile, RunOutcome out) throws IOException {
        List<String> lines = Files.readAllLines(resultFile, StandardCharsets.UTF_8);
        for (String ln : lines) {
            if (ln.startsWith("runCount=")) out.runCount = safeInt(ln.substring("runCount=".length()), 0);
            else if (ln.startsWith("failureCount=")) out.failureCount = safeInt(ln.substring("failureCount=".length()), 0);
        }
    }

    // ---------------- output (match sample) ----------------
    private static void writeKillStatistic(
            Path outFile,
            Map<String, Set<String>> testResults,
            Map<String, Set<String>> mutantResults,
            List<String> killed,
            List<String> live,
            double score
    ) throws IOException {

        List<String> lines = new ArrayList<>();
        lines.add("test_results=" + mapToSampleStyle(testResults) + ";");
        lines.add("mutant_results=" + mapToSampleStyle(mutantResults));
        lines.add("killed_mutants=" + listToSampleStyle(killed) + ";");
        lines.add("live_mutants=" + listToSampleStyle(live) + ";");
        lines.add("mutant_score=" + String.format(Locale.ROOT, "%.5f", score));

        Files.createDirectories(outFile.getParent());
        Files.write(outFile, lines, StandardCharsets.UTF_8);
    }

    private static String listToSampleStyle(List<String> items) {
        return "[" + String.join(", ", items) + "]";
    }

    private static String mapToSampleStyle(Map<String, ? extends Set<String>> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, ? extends Set<String>> e : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append("=");
            sb.append(String.join(", ", e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    // ---------------- scanning helpers ----------------
    public static List<String> listTestClassFQNs(Path testClassesDir) throws IOException {
        if (!Files.isDirectory(testClassesDir)) return Collections.emptyList();
        try (Stream<Path> s = Files.walk(testClassesDir)) {
            return s.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .filter(p -> !p.getFileName().toString().endsWith("_scaffolding.class"))
                    .map(p -> toFqn(testClassesDir, p))
                    .filter(fqn -> fqn.endsWith("ESTest") || fqn.endsWith("Test") || fqn.endsWith("Tests"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String toFqn(Path root, Path classFile) {
        Path rel = root.relativize(classFile);
        String s = rel.toString().replace('\\', '.').replace('/', '.');
        if (s.endsWith(".class")) s = s.substring(0, s.length() - ".class".length());
        return s;
    }

    public static List<Path> discoverMutantClassRoots(Path mutantsRootDir) throws IOException {
        if (!Files.isDirectory(mutantsRootDir)) return Collections.emptyList();
        Set<Path> classDirs = new HashSet<>();
        try (Stream<Path> s = Files.walk(mutantsRootDir)) {
            s.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> classDirs.add(p.getParent()));
        }
        if (classDirs.isEmpty()) return Collections.emptyList();

        List<Path> sorted = classDirs.stream()
                .sorted(Comparator.comparingInt(p -> p.toString().length()))
                .collect(Collectors.toList());

        List<Path> leaf = new ArrayList<>();
        for (Path d : sorted) {
            boolean hasChild = false;
            for (Path other : sorted) {
                if (!other.equals(d) && other.startsWith(d)) { hasChild = true; break; }
            }
            if (!hasChild) leaf.add(d);
        }
        leaf.sort(Comparator.comparing(Path::toString));
        return leaf;
    }

    // ---------------- utils ----------------
    private static String joinCp(String... parts) {
        String sep = System.getProperty("path.separator");
        return Arrays.stream(parts).filter(p -> p != null && !p.trim().isEmpty()).collect(Collectors.joining(sep));
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String v = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                m.put(a, v);
            }
        }
        return m;
    }

    private static String require(Map<String, String> cli, String k) {
        String v = cli.get(k);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("Missing argument: " + k);
        return v;
    }

    private static int safeInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "";
        String msg = t.getMessage();
        if (msg == null) msg = "";
        msg = msg.replace("\r", " ").replace("\n", " ");
        if (msg.length() > 240) msg = msg.substring(0, 240) + "...";
        return msg;
    }

    private static String getSelfCpEntry() {
        try {
            return new File(KillStatisticRunner.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getPath();
        } catch (URISyntaxException e) {
            return ".";
        }
    }

    private static class RunOutcome {
        int exitCode;
        int runCount;
        int failureCount;
        boolean timedOut;
    }
}
