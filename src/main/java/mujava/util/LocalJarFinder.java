package mujava.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class LocalJarFinder {
    static final String config = resolveConfigPath();

    public static void main(String[] args) {
        String config = System.getProperty("user.dir") + "/config.config";
        String key = "MAVEN_REPOSITORY_HOME";
        String jsonResult = getFileDir(config, key);
    }

    private static String resolveConfigPath() {
        String p = System.getProperty("mujava.config.path");
        if (p != null && !p.trim().isEmpty()) {
            return Paths.get(p).toAbsolutePath().normalize().toString();
        }

        p = System.getenv("MUJAVA_CONFIG");
        if (p != null && !p.trim().isEmpty()) {
            return Paths.get(p).toAbsolutePath().normalize().toString();
        }

        return Paths.get(System.getProperty("user.dir"), "mujava.config")
                .toAbsolutePath().normalize().toString();
    }

    public static String getMaven(String maven, String key) {
        // Path to the local Maven repository
        String mavenRepositoryPath = "";
        try {
            BufferedReader reader = new BufferedReader(
                    new FileReader(maven));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(key+"=")) {
                    mavenRepositoryPath = line.split("=", 2)[1].trim();
                    break;
                }
            }
        } catch (FileNotFoundException e1)
        {
            System.err.println("[ERROR] Can't find mujava.config file");
            e1.printStackTrace();
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        return mavenRepositoryPath;
    }

    public static String getFileDir(String config, String key){
        // Path to the local Maven repository
        try
        {
            String mavenRepositoryPath = getMaven(config, key);

            assert mavenRepositoryPath != null;

            // JSON format
            String librariesJson = new String(Files.readAllBytes(Paths.get("libraries.json")), StandardCharsets.UTF_8);

            // Convert the JSON string to a JSONArray
            JSONArray libraries = new JSONArray(librariesJson);

            // Output the JSON result
            // System.out.println(jsonResult);
            return findJarFiles(mavenRepositoryPath, libraries);
        } catch (FileNotFoundException e1)
        {
            System.err.println("[ERROR] Can't find mujava.config file");
            e1.printStackTrace();
        } catch (Exception e)
        {
            e.printStackTrace();
        }
        return null;
    }

    // Find .jar files for multiple libraries and return the result as JSON
    private static String findJarFiles(String repositoryPath, JSONArray libraries) throws Exception {
        JSONArray jarArray = new JSONArray();

        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.getJSONObject(i);
            String groupId = library.getString("groupId");
            String artifactId = library.getString("artifactId");
            String version = library.getString("version");

            // Build the path to the .jar file
            String jarFilePath = getJarFilePath(repositoryPath, groupId, artifactId, version);
            File jarFile = new File(jarFilePath);

            // Build the JSON object
            JSONObject jarInfo = new JSONObject();
            jarInfo.put("groupId", groupId);
            jarInfo.put("artifactId", artifactId);
            jarInfo.put("version", version);
            if (jarFile.exists()) {
                jarInfo.put("jarPath", jarFile.getAbsolutePath());
            } else {
                jarInfo.put("jarPath", "[Not Found]");
            }

            // Add to the array
            jarArray.put(jarInfo);
        }

        // Return the JSON string
        return jarArray.toString(4);  // 4 indicates the number of spaces used for indentation
    }

    // Build the full path to the .jar file
    private static String getJarFilePath(String repositoryPath, String groupId, String artifactId, String version) {
        // Replace groupId with path format (for example: org.apache.commons -> org/apache/commons)
        String groupPath = groupId.replace('.', '/');
        return repositoryPath + "/" + groupPath + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
    }

    public static String buildMavenClasspathFromProject(String projectDir, String mvnCmd)
            throws IOException, InterruptedException {

        File dir = new File(projectDir);
        File tmp = File.createTempFile("maven_cp_", ".txt", dir);
        tmp.deleteOnExit();
        Path tmpPath = tmp.toPath();

        try {
            List<String> cmd = Arrays.asList(
                    mvnCmd,
                    "-DskipTests",
                    "dependency:build-classpath",
                    "-Dmdep.includeScope=test",
                    "-Dmdep.pathSeparator=" + File.pathSeparator,
                    "-Dmdep.outputFile=" + tmp.getAbsolutePath()
            );

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir);
            pb.redirectErrorStream(true);

            Process p = pb.start();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                while (br.readLine() != null) {
                    // ignore
                }
            }

            int code = p.waitFor();

            if (code != 0) {
                throw new IOException("mvn failed, exit=" + code);
            }

            return new String(Files.readAllBytes(tmpPath), StandardCharsets.UTF_8).trim();
        } finally {
            Files.deleteIfExists(tmpPath);
        }
    }

    public static String buildProjectClasspath(String systemHome) throws IOException {
        final String sep = File.pathSeparator;

        Path home = Paths.get(systemHome).normalize();
        Path target = home.resolve("target");

        Path classesPath = target.resolve("classes");
        String classesDir = classesPath.toString();

        Path depDir = target.resolve("dependency");
        String depsWildcard = depDir.toString() + File.separator + "*";

        String jarsInTarget = getJarFilesAsString(target.toString() + File.separator);

        String mavenCp;
        try {
            String mavenHome = getMaven(config, "MAVEN_HOME");
            if (mavenHome == null || mavenHome.trim().isEmpty()) {
                throw new IOException("MAVEN_HOME not found in config: " + config);
            }

            String mvnCmd = Paths.get(mavenHome, "bin", "mvn.cmd").toString();
            mavenCp = buildMavenClasspathFromProject(systemHome, mvnCmd);
        } catch (Exception e) {
            System.err.println("[WARN] buildProjectClasspath: failed to resolve Maven classpath, systemHome="
                    + systemHome + ", config=" + config + ", reason=" + e.getMessage());
            mavenCp = "";
        }

        StringBuilder cp = new StringBuilder();

        if (Files.isDirectory(classesPath)) {
            cp.append(classesDir);
        }

        if (Files.isDirectory(depDir)) {
            if (cp.length() > 0) cp.append(sep);
            cp.append(depsWildcard);
        }

        String t = jarsInTarget == null ? "" : jarsInTarget.trim();
        if (!t.isEmpty()) {
            if (cp.length() > 0) cp.append(sep);
            cp.append(t);
        }

        String t1 = mavenCp == null ? "" : mavenCp.trim();
        if (!t1.isEmpty()) {
            if (cp.length() > 0) cp.append(sep);
            cp.append(t1);
        }

        return cp.toString();
    }

    // Read cp.txt: in some cases it may be a single line (separated by ;), or it may have been printed as multiple lines
    private static String readClasspathFile(Path cpTxt) throws IOException {
        if (!Files.exists(cpTxt)) return "";
        List<String> lines = Files.readAllLines(cpTxt, StandardCharsets.UTF_8);

        // Join all lines together, then normalize by separators/newlines
        String raw = String.join("\n", lines).trim();
        if (raw.isEmpty()) return "";

        // On Windows, Maven usually outputs with ';', but what you showed looks like the result after printing line by line
        // Handle it uniformly here: treat newlines as separators, then join them back using the system path separator
        String sep = File.pathSeparator;

        // First replace \r\n / \n with sep
        raw = raw.replace("\r\n", "\n").replace("\n", sep);

        // Then merge any duplicate separators that may appear
        raw = raw.replace(sep + sep, sep);

        // Remove extra separators at the beginning/end
        while (raw.startsWith(sep)) raw = raw.substring(1);
        while (raw.endsWith(sep)) raw = raw.substring(0, raw.length() - 1);

        return raw;
    }

    // Your current implementation also works; it is just safer to replace ";" with File.pathSeparator
    public static String getJarFilesAsString(String targetDirectory) throws IOException {
        Pattern EXCLUDE_PATTERN = Pattern.compile(".*(sources|tests|test-sources|javadoc)\\.jar");
        StringBuilder jarFilesString = new StringBuilder();
        Path targetPath = Paths.get(targetDirectory).normalize();
        if (!Files.exists(targetPath)) {
            return "";
        }
        String sep = File.pathSeparator;

        Files.walkFileTree(targetPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fn = file.getFileName().toString();
                if (fn.endsWith(".jar") && !EXCLUDE_PATTERN.matcher(fn).matches()) {
                    if (jarFilesString.length() > 0) jarFilesString.append(sep);
                    jarFilesString.append(file.toAbsolutePath().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return jarFilesString.toString();
    }
    public static String getJarFilesAsString1(String targetDirectory) throws IOException {
        Pattern EXCLUDE_PATTERN = Pattern.compile(".*(sources|tests|test-sources|javadoc)\\.jar");
        String sep = File.pathSeparator;

        Path targetPath = Paths.get(targetDirectory).normalize();
        if (!Files.exists(targetPath)) {
            return "";
        }
        List<String> jars = new ArrayList<>();

        Files.walkFileTree(targetPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fn = file.getFileName().toString();
                if (fn.endsWith(".jar") && !EXCLUDE_PATTERN.matcher(fn).matches()) {
                    jars.add(file.toAbsolutePath().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        jars.sort(Comparator.naturalOrder());

        return String.join(sep, jars);
    }


    public static void collectMultiModuleOutputs(String sourceRootHome,
                                                 String currentModuleHome,
                                                 Set<String> classDirs,
                                                 Set<String> jarFiles,
                                                 Set<String> jarDirs) throws IOException {
        if (sourceRootHome == null || sourceRootHome.trim().isEmpty()) {
            return;
        }

        final Path root = Paths.get(sourceRootHome).normalize();
        if (!Files.exists(root)) {
            return;
        }

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (".git".equals(name) || ".idea".equals(name) || "result".equals(name)
                        || "evoSuite".equals(name) || "worker_runs".equals(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                String normalized = dir.toString().replace('\\', '/');
                if (normalized.endsWith("/target/classes")) {
                    classDirs.add(dir.toAbsolutePath().toString());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (normalized.endsWith("/target/test-classes")) {
                    classDirs.add(dir.toAbsolutePath().toString());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (normalized.endsWith("/target/dependency")) {
                    jarDirs.add(dir.toAbsolutePath().toString());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString().toLowerCase();
                String normalized = file.toAbsolutePath().toString().replace('\\', '/');
                if (fileName.endsWith(".jar")
                        && !fileName.endsWith("-sources.jar")
                        && !fileName.endsWith("-javadoc.jar")
                        && !fileName.endsWith("-tests.jar")
                        && !fileName.endsWith("-test-sources.jar")
                        && normalized.contains("/target/")) {
                    jarFiles.add(file.toAbsolutePath().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        if (currentModuleHome != null && !currentModuleHome.trim().isEmpty()) {
            Path currentClasses = Paths.get(currentModuleHome).normalize()
                    .resolve("target")
                    .resolve("classes");
            if (Files.isDirectory(currentClasses)) {
                String currentClassesPath = currentClasses.toAbsolutePath().toString();
                if (classDirs.remove(currentClassesPath)) {
                    // re-insert to keep current module classes near the front
                    java.util.LinkedHashSet<String> reordered = new java.util.LinkedHashSet<String>();
                    reordered.add(currentClassesPath);
                    reordered.addAll(classDirs);
                    classDirs.clear();
                    classDirs.addAll(reordered);
                }
            }
        }
    }

    public static void moveSrcClass(String javaFilePath, String targetDir) {

        File srcFolder = new File(javaFilePath);
        // Define the source file path and target file path
        File targetFile = new File(targetDir, srcFolder.getName());

        try {
            Files.copy(srcFolder.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Copied: " + srcFolder.getName());
        } catch (IOException e) {
            System.err.println("Error copying file " + srcFolder.getName() + ": " + e.getMessage());
        }

        String classFilePath = classFind(javaFilePath);
        assert classFilePath != null;
        srcFolder = new File(classFilePath);
        // Define the source file path and target file path
        targetFile = new File(targetDir, srcFolder.getName());

        try {
            // Copy the file
            Files.copy(srcFolder.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Copied: " + srcFolder.getName());
        } catch (IOException e) {
            System.err.println("Error copying file " + srcFolder.getName() + ": " + e.getMessage());
        }
    }

    public static String classFind(String javaFilePath) {
        // The root target/classes directory of your project
        String targetClassesDir = "target/classes";

        // Convert to the corresponding .class file path
        String classFilePath = javaFilePath.replace("src/main/java", targetClassesDir)
                .replace(".java", ".class");

        // Output the corresponding .class file path
        System.out.println("Corresponding .class file path: " + classFilePath);

        // Check whether the .class file exists
        File classFile = new File(classFilePath);
        if (classFile.exists()) {
            System.out.println(".class file has been generated: " + classFile.getAbsolutePath());
            return classFile.getAbsolutePath();
        } else {
            System.out.println(".class file has not been generated");
            return null;
        }
    }
}

