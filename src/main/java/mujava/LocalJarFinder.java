package mujava;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;


public class LocalJarFinder {

    public static void main(String[] args) {
        String maven = MutationSystem.SYSTEM_HOME + "/mujava.config";
        String jsonResult = getFileDir(maven);
    }

    public static String getFileDir(String maven){
        // 本地 Maven 仓库的路径
        try
        {
            String mavenRepositoryPath = null;

            BufferedReader reader = new BufferedReader(
                    new FileReader(maven));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MAVEN_HOME=")) {
                    mavenRepositoryPath = line.split("=", 2)[1].trim();
                    break;
                }
            }

            assert mavenRepositoryPath != null;

            // JSON 格式
            String librariesJson = new String(Files.readAllBytes(Paths.get("libraries.json")), StandardCharsets.UTF_8);

            // 转换 JSON 字符串为 JSONArray
            JSONArray libraries = new JSONArray(librariesJson);

            // 查找所有匹配的 .jar 文件路径并合并
            String jsonResult = findJarFiles(mavenRepositoryPath, libraries);

            // 输出 JSON 结果
            // System.out.println(jsonResult);
            return jsonResult;
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

    // 查找多个库的 .jar 文件并返回 JSON
    private static String findJarFiles(String repositoryPath, JSONArray libraries) throws Exception {
        JSONArray jarArray = new JSONArray();

        for (int i = 0; i < libraries.length(); i++) {
            JSONObject library = libraries.getJSONObject(i);
            String groupId = library.getString("groupId");
            String artifactId = library.getString("artifactId");
            String version = library.getString("version");

            // 构建 .jar 文件的路径
            String jarFilePath = getJarFilePath(repositoryPath, groupId, artifactId, version);
            File jarFile = new File(jarFilePath);

            // 构建 JSON 对象
            JSONObject jarInfo = new JSONObject();
            jarInfo.put("groupId", groupId);
            jarInfo.put("artifactId", artifactId);
            jarInfo.put("version", version);
            if (jarFile.exists()) {
                jarInfo.put("jarPath", jarFile.getAbsolutePath());
            } else {
                jarInfo.put("jarPath", "[未找到]");
            }

            // 添加到数组
            jarArray.put(jarInfo);
        }

        // 返回 JSON 字符串
        return jarArray.toString(4);  // 4 表示缩进空格数
    }

    // 构建 .jar 文件的完整路径
    private static String getJarFilePath(String repositoryPath, String groupId, String artifactId, String version) {
        // 将 groupId 替换为路径格式（例如：org.apache.commons -> org/apache/commons）
        String groupPath = groupId.replace('.', '/');
        return repositoryPath + "/" + groupPath + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
    }

    public static String getJarFilesAsString(String targetDirectory) throws Exception {
        Pattern EXCLUDE_PATTERN = Pattern.compile(".*(sources|tests|test-sources|javadoc)\\.jar");
        StringBuilder jarFilesString = new StringBuilder();
        Path targetPath = Paths.get(targetDirectory);

        Files.walkFileTree(targetPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // 检查文件扩展名是否为 .jar 且文件名不匹配排除正则表达式
                if (file.toString().endsWith(".jar") && !EXCLUDE_PATTERN.matcher(file.getFileName().toString()).matches()) {
                    if (jarFilesString.length() > 0) {
                        jarFilesString.append(";");
                    }
                    jarFilesString.append(file.normalize().toAbsolutePath().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return jarFilesString.toString(); // 返回拼接后的字符串
    }

    public static void moveSrcClass(String javaFilePath, String targetDir) {

        File srcFolder = new File(javaFilePath);
        // 定义源文件路径和目标文件路径
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
        // 定义源文件路径和目标文件路径
        targetFile = new File(targetDir, srcFolder.getName());

        try {
            // 复制文件
            Files.copy(srcFolder.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Copied: " + srcFolder.getName());
        } catch (IOException e) {
            System.err.println("Error copying file " + srcFolder.getName() + ": " + e.getMessage());
        }
    }

    public static String classFind(String javaFilePath) {
        // 你的项目的 target/classes 根目录
        String targetClassesDir = "target/classes";

        // 转换为相应的 class 文件路径
        String classFilePath = javaFilePath.replace("src/main/java", targetClassesDir)
                .replace(".java", ".class");

        // 输出对应的 .class 文件路径
        System.out.println("对应的 .class 文件路径: " + classFilePath);

        // 检查 .class 文件是否存在
        File classFile = new File(classFilePath);
        if (classFile.exists()) {
            System.out.println(".class 文件已生成： " + classFile.getAbsolutePath());
            return classFile.getAbsolutePath();
        } else {
            System.out.println(".class 文件未生成");
            return null;
        }
    }
}

