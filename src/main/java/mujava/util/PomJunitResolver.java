package mujava.util;

import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;

public class PomJunitResolver {

    private static final String POM_NS = "http://maven.apache.org/POM/4.0.0";
    public static void main(String[] args) throws Exception {
        String pomPath = "E:\\PHD\\testJava\\Programs\\commons-csv-1.2\\commons-csv-1.2\\pom.xml";
        Path pom = Paths.get(pomPath);
        Result r = resolveJUnit(pom);

        if (r == null || r.version == null) {
            System.out.println("NOT_FOUND");
            System.out.println("EvoSuite: (unknown) -> try -Dtest_format=JUNIT4");
            return;
        }

        JUnitFlavor flavor = detectFlavor(r.coord, r.version);
        String evoFormat = recommendEvoSuiteFormat(flavor);

        System.out.println("JUnit: " + flavor + "  " + r.coord + ":" + r.version + "  (from " + r.fromPom + ")");
        System.out.println("EvoSuite: -Dtest_format=" + evoFormat);

        if (flavor == JUnitFlavor.JUNIT5) {
            System.out.println("Note: EvoSuite usually generates JUNIT3/JUNIT4 tests. If the project uses JUnit5, you need to enable Vintage in the project or additionally introduce junit4 to compile/run JUnit4 tests.");
        }
    }

    static class Result {
        final String coord;     // e.g., junit:junit or org.junit.jupiter:junit-jupiter-api
        final String version;   // resolved version
        final Path fromPom;     // where it was found
        Result(String coord, String version, Path fromPom) {
            this.coord = coord; this.version = version; this.fromPom = fromPom;
        }
    }

    public static Result resolveJUnit(Path pomXml) throws Exception {
        return resolveJUnit(pomXml, new HashSet<Path>());
    }

    private static Result resolveJUnit(Path pomXml, Set<Path> seen) throws Exception {
        pomXml = pomXml.toAbsolutePath().normalize();
        if (!Files.exists(pomXml) || !seen.add(pomXml)) return null;

        Document doc = parseXml(pomXml);

        // 1) properties (used to substitute ${...})
        Map<String, String> props = readProperties(doc);

        // 2) Check dependencies first (highest priority)
        Result r = findJUnitInSection(doc, props, pomXml, "//m:dependencies/m:dependency");
        if (r != null) return r;

        // 3) Then check dependencyManagement
        r = findJUnitInSection(doc, props, pomXml, "//m:dependencyManagement//m:dependency");
        if (r != null) return r;

        // 4) Check imported BOMs (type=pom and scope=import in dependencyManagement)
        List<GAV> boms = readImportedBoms(doc, props);
        for (GAV bom : boms) {
            Path bomPom = locateInLocalRepo(bom);
            if (bomPom != null) {
                Result rb = resolveJUnit(bomPom, seen); // Continue searching in the BOM (it will also recurse into parent)
                if (rb != null) return rb;
            }
        }

        // 5) Recurse into parent
        GAV parent = readParent(doc, props);
        if (parent != null) {
            Path parentPom = locateInLocalRepo(parent);
            if (parentPom != null) {
                Result rp = resolveJUnit(parentPom, seen);
                if (rp != null) return rp;
            }
        }

        return null;
    }

    // ---------------- helpers ----------------

    static class GAV {
        final String g, a, v;
        GAV(String g, String a, String v) { this.g=g; this.a=a; this.v=v; }
    }

    private static Document parseXml(Path pom) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return dbf.newDocumentBuilder().parse(pom.toFile());
    }

    private static XPath newXPath() {
        XPath xp = XPathFactory.newInstance().newXPath();
        xp.setNamespaceContext(new NamespaceContext() {
            @Override public String getNamespaceURI(String prefix) {
                return "m".equals(prefix) ? POM_NS : XMLConstants.NULL_NS_URI;
            }
            @Override public String getPrefix(String namespaceURI) { return null; }
            @Override public Iterator<String> getPrefixes(String namespaceURI) { return null; }
        });
        return xp;
    }

    private static Map<String, String> readProperties(Document doc) throws Exception {
        XPath xp = newXPath();
        Node props = (Node) xp.evaluate("//m:properties", doc, XPathConstants.NODE);
        Map<String, String> map = new HashMap<>();
        if (props == null) return map;

        NodeList kids = props.getChildNodes();
        for (int i=0;i<kids.getLength();i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                map.put(n.getLocalName(), n.getTextContent().trim());
            }
        }
        return map;
    }

    private static String resolveProps(String s, Map<String, String> props) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("${") && s.endsWith("}")) {
            String key = s.substring(2, s.length()-1);
            String v = props.get(key);
            return v != null ? v.trim() : s;
        }
        return s;
    }

    private static Result findJUnitInSection(Document doc, Map<String, String> props, Path fromPom, String depXPath) throws Exception {
        XPath xp = newXPath();
        NodeList deps = (NodeList) xp.evaluate(depXPath, doc, XPathConstants.NODESET);

        // Match by priority: JUnit4/3 (junit:junit) -> JUnit5 (org.junit.jupiter:*)
        Result best = null;

        for (int i=0;i<deps.getLength();i++) {
            Node d = deps.item(i);
            String gid = text(xp, d, "m:groupId");
            String aid = text(xp, d, "m:artifactId");
            String ver = text(xp, d, "m:version");

            gid = resolveProps(gid, props);
            aid = resolveProps(aid, props);
            ver = resolveProps(ver, props);

            if (gid == null || aid == null) continue;

            // JUnit 3/4
            if ("junit".equals(gid) && "junit".equals(aid)) {
                if (ver != null && !ver.isEmpty()) return new Result("junit:junit", ver, fromPom);
                // If version is not specified, it means the version comes from dependencyManagement/parent/BOM; continue searching.
                best = new Result("junit:junit", null, fromPom);
            }

            // JUnit 5 Jupiter (common case)
            if ("org.junit.jupiter".equals(gid) && (aid.startsWith("junit-jupiter"))) {
                if (ver != null && !ver.isEmpty()) return new Result(gid + ":" + aid, ver, fromPom);
                if (best == null) best = new Result(gid + ":" + aid, null, fromPom);
            }
        }

        // If a JUnit dependency is found but the version is empty, return null so the upper level can continue resolving via dependencyManagement/parent/BOM.
        return (best != null && best.version != null) ? best : null;
    }

    private static List<GAV> readImportedBoms(Document doc, Map<String, String> props) throws Exception {
        XPath xp = newXPath();
        NodeList deps = (NodeList) xp.evaluate("//m:dependencyManagement//m:dependency", doc, XPathConstants.NODESET);
        List<GAV> list = new ArrayList<>();
        for (int i=0;i<deps.getLength();i++) {
            Node d = deps.item(i);
            String type = resolveProps(text(xp, d, "m:type"), props);
            String scope = resolveProps(text(xp, d, "m:scope"), props);
            if (!"pom".equals(type) || !"import".equals(scope)) continue;

            String gid = resolveProps(text(xp, d, "m:groupId"), props);
            String aid = resolveProps(text(xp, d, "m:artifactId"), props);
            String ver = resolveProps(text(xp, d, "m:version"), props);
            if (gid != null && aid != null && ver != null) list.add(new GAV(gid, aid, ver));
        }
        return list;
    }

    private static GAV readParent(Document doc, Map<String, String> props) throws Exception {
        XPath xp = newXPath();
        Node p = (Node) xp.evaluate("//m:parent", doc, XPathConstants.NODE);
        if (p == null) return null;
        String gid = resolveProps(text(xp, p, "m:groupId"), props);
        String aid = resolveProps(text(xp, p, "m:artifactId"), props);
        String ver = resolveProps(text(xp, p, "m:version"), props);
        if (gid == null || aid == null || ver == null) return null;
        return new GAV(gid, aid, ver);
    }

    private static Path locateInLocalRepo(GAV gav) {
        // Compatible with both Windows and Linux
        String home = System.getProperty("user.home");
        Path repo = Paths.get(home, ".m2", "repository");
        Path pom = repo
                .resolve(gav.g.replace('.', File.separatorChar))
                .resolve(gav.a)
                .resolve(gav.v)
                .resolve(gav.a + "-" + gav.v + ".pom");
        return Files.exists(pom) ? pom : null;
    }

    private static String text(XPath xp, Node base, String relExpr) throws Exception {
        String s = (String) xp.evaluate("string(" + relExpr + ")", base, XPathConstants.STRING);
        s = (s == null) ? null : s.trim();
        return (s == null || s.isEmpty()) ? null : s;
    }

    enum JUnitFlavor { JUNIT3, JUNIT4, JUNIT5, UNKNOWN }

    private static JUnitFlavor detectFlavor(String coord, String version) {
        if (coord == null || version == null) return JUnitFlavor.UNKNOWN;

        if ("junit:junit".equals(coord)) {
            int major = parseMajor(version);
            // 3.x -> JUnit3; 4.x -> JUnit4
            if (major > 0 && major < 4) return JUnitFlavor.JUNIT3;
            if (major == 4) return JUnitFlavor.JUNIT4;
            return JUnitFlavor.UNKNOWN;
        }

        // As long as org.junit.jupiter appears, it is JUnit5
        if (coord.startsWith("org.junit.jupiter:")) return JUnitFlavor.JUNIT5;

        return JUnitFlavor.UNKNOWN;
    }

    private static String recommendEvoSuiteFormat(JUnitFlavor flavor) {
        // EvoSuite mainly outputs JUNIT3 / JUNIT4 formats
        if (flavor == JUnitFlavor.JUNIT3) return "JUNIT3";
        // JUnit4 / JUnit5 / UNKNOWN: default to JUNIT4 (for JUnit5 projects, configure Vintage)
        return "JUNIT4";
    }

    private static int parseMajor(String version) {
        // e.g., "5.10.2" -> 5; "4.13.2" -> 4; "3.8.1" -> 3
        String v = version.trim();
        int i = v.indexOf('.');
        String majorStr = (i >= 0) ? v.substring(0, i) : v;
        try { return Integer.parseInt(majorStr); } catch (Exception e) { return -1; }
    }

}
