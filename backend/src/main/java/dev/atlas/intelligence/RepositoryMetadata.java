package dev.atlas.intelligence;

import dev.atlas.platform.ApiException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RepositoryMetadata {

    static final int MAX_DEPENDENCIES = 2000;
    static final int MAX_NAME_LENGTH = 500,
        MAX_VERSION_LENGTH = 2000;
    static final int MAX_DEPENDENCY_BYTES = 512000;

    private static final class Dependencies extends ArrayList<Dependency> {

        private int bytes;
        private final List<String> limitations;
        private boolean byteLimitReached;

        Dependencies(List<String> limitations) {
            this.limitations = limitations;
        }

        @Override
        public boolean add(Dependency dependency) {
            int size = (
                dependency.ecosystem() +
                dependency.name() +
                dependency.declaredVersion() +
                dependency.path()
            )
                .getBytes(StandardCharsets.UTF_8)
                .length;
            if (bytes + size > MAX_DEPENDENCY_BYTES) {
                if (!byteLimitReached) limitations.add(
                    "The dependency text budget of " +
                        MAX_DEPENDENCY_BYTES +
                        " UTF-8 bytes was reached. Declarations exceeding the remaining budget were omitted without truncating their values; inventory still covers all indexed files."
                );
                byteLimitReached = true;
                return false;
            }
            bytes += size;
            return super.add(dependency);
        }
    }

    public record Dependency(
        String ecosystem,
        String name,
        String declaredVersion,
        String path,
        int line
    ) {
        public Dependency(
            String ecosystem,
            String name,
            String declaredVersion,
            String path
        ) {
            this(ecosystem, name, declaredVersion, path, 0);
        }
    }

    public record Language(
        String language,
        int files,
        long lines,
        long contentBytes,
        List<String> paths
    ) {
        public Language {
            paths = List.copyOf(paths);
        }
    }

    public record Report(
        List<String> modules,
        List<Dependency> dependencies,
        List<String> signals,
        List<String> limitations,
        UUID snapshotId,
        List<Language> languages
    ) {}

    private static final Pattern PYTHON_NAME = Pattern.compile(
        "^([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)(.*)$"
    );
    private static final String PYTHON_VERSION =
        "(?:===|~=|==|!=|<=|>=|<|>)\\s*[A-Za-z0-9*+!_.-]+";
    private static final Pattern PYTHON_SPEC = Pattern.compile(
        "(?:\\[[A-Za-z0-9_-]+(?:\\s*,\\s*[A-Za-z0-9_-]+)*\\])?\\s*(?:" +
            PYTHON_VERSION +
            "(?:\\s*,\\s*" +
            PYTHON_VERSION +
            ")*)?"
    );
    private static final String MARKER_VARIABLE =
        "(?:python_version|python_full_version|os_name|sys_platform|platform_release|platform_system|platform_version|platform_machine|platform_python_implementation|implementation_name|implementation_version|extra)";
    private static final String MARKER_LITERAL =
        "(?:\"[A-Za-z0-9 ._*+/-]*\"|'[A-Za-z0-9 ._*+/-]*')";
    private static final String MARKER_CLAUSE =
        MARKER_VARIABLE +
        "\\s*(?:===|~=|==|!=|<=|>=|<|>|not\\s+in|in)\\s*" +
        MARKER_LITERAL;
    private static final Pattern PYTHON_MARKER = Pattern.compile(
        MARKER_CLAUSE + "(?:\\s+(?:and|or)\\s+" + MARKER_CLAUSE + ")*"
    );
    private static final Pattern GO_REQUIRE = Pattern.compile(
        "([A-Za-z0-9][A-Za-z0-9._~/-]*)\\s+(v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?)"
    );

    private final RepositoryIndex index;
    private final JsonMapper json;

    public RepositoryMetadata(RepositoryIndex index, JsonMapper json) {
        this.index = index;
        this.json = json;
    }

    public Report report() {
        return report(null);
    }

    public Report report(UUID expectedSnapshot) {
        var snapshot = index.current();
        if (
            expectedSnapshot != null && !snapshot.id().equals(expectedSnapshot)
        ) throw new ApiException(
            HttpStatus.CONFLICT,
            "STALE_SNAPSHOT",
            "The approved snapshot changed. Refresh it before reviewing repository metadata."
        );
        var modules = new TreeSet<String>();
        var signals = new TreeSet<String>();
        var limitations = new ArrayList<String>();
        var dependencies = new Dependencies(limitations);
        var inventory = new TreeMap<String, List<RepositoryIndex.Source>>();
        for (var file : snapshot
            .files()
            .stream()
            .sorted(Comparator.comparing(RepositoryIndex.Source::path))
            .toList()) {
            inventory
                .computeIfAbsent(file.language(), ignored -> new ArrayList<>())
                .add(file);
            String basename = file
                .path()
                .substring(file.path().lastIndexOf('/') + 1);
            if (file.path().contains("/")) modules.add(
                file.path().substring(0, file.path().indexOf('/'))
            );
            try {
                if (dependencies.size() >= MAX_DEPENDENCIES) continue;
                if (basename.equals("package.json")) {
                    var manifest = json.readTree(file.content());
                    for (String group : List.of(
                        "dependencies",
                        "devDependencies"
                    )) {
                        var entries = manifest.get(group);
                        if (entries == null || !entries.isObject()) continue;
                        for (var entry : entries.properties()) {
                            if (dependencies.size() >= MAX_DEPENDENCIES) break;
                            if (
                                entry.getValue().isString() &&
                                bounded(
                                    entry.getKey(),
                                    entry.getValue().asText(),
                                    file.path(),
                                    limitations
                                )
                            ) dependencies.add(
                                new Dependency(
                                    "npm " + group,
                                    entry.getKey(),
                                    entry.getValue().asText(),
                                    file.path()
                                )
                            );
                        }
                    }
                }
                if (basename.equals("pom.xml")) {
                    var factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature(
                        XMLConstants.FEATURE_SECURE_PROCESSING,
                        true
                    );
                    factory.setFeature(
                        "http://apache.org/xml/features/disallow-doctype-decl",
                        true
                    );
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    factory.setAttribute(
                        XMLConstants.ACCESS_EXTERNAL_SCHEMA,
                        ""
                    );
                    var builder = factory.newDocumentBuilder();
                    builder.setErrorHandler(
                        new org.xml.sax.helpers.DefaultHandler()
                    );
                    var document = builder.parse(
                        new InputSource(new StringReader(file.content()))
                    );
                    var elements = document.getElementsByTagName("dependency");
                    for (int i = 0; i < elements.getLength(); i++) {
                        if (dependencies.size() >= MAX_DEPENDENCIES) break;
                        var element = (org.w3c.dom.Element) elements.item(i);
                        String group = directText(element, "groupId");
                        String artifact = directText(element, "artifactId");
                        String version = directText(element, "version");
                        String name =
                            group == null || artifact == null
                                ? null
                                : group + ":" + artifact;
                        String declaredVersion =
                            version == null
                                ? "Inherited/managed; not resolved"
                                : version;
                        if (
                            name != null &&
                            bounded(
                                name,
                                declaredVersion,
                                file.path(),
                                limitations
                            )
                        ) dependencies.add(
                            new Dependency(
                                "Maven",
                                name,
                                declaredVersion,
                                file.path()
                            )
                        );
                    }
                }
                if (
                    basename.equals("requirements.txt") ||
                    basename.matches("requirements-[A-Za-z0-9._-]+\\.txt")
                ) {
                    python(file, dependencies, limitations);
                }
                if (basename.equals("go.mod")) go(
                    file,
                    dependencies,
                    limitations
                );
            } catch (Exception e) {
                limitations.add(
                    file.path() +
                        ": manifest could not be parsed safely; no dependency resolution attempted."
                );
            }
        }
        for (var dependency : dependencies) {
            if (
                dependency.ecosystem().equals("Maven") &&
                dependency
                    .name()
                    .startsWith("org.springframework.boot:spring-boot")
            ) signals.add("Spring Boot dependency declaration");
            if (
                dependency.ecosystem().startsWith("npm ") &&
                dependency.name().equals("react")
            ) signals.add("React dependency declaration");
            if (
                dependency.ecosystem().equals("Maven") &&
                dependency.name().equals("org.postgresql:postgresql")
            ) signals.add("PostgreSQL driver dependency declaration");
            if (
                (dependency.ecosystem().equals("Maven") &&
                    dependency.name().startsWith("io.opentelemetry:")) ||
                (dependency.ecosystem().startsWith("npm ") &&
                    dependency.name().startsWith("@opentelemetry/"))
            ) signals.add("OpenTelemetry dependency declaration");
        }
        dependencies.sort(
            Comparator.comparing(Dependency::path)
                .thenComparing(Dependency::name)
                .thenComparingInt(Dependency::line)
                .thenComparing(Dependency::ecosystem)
                .thenComparing(Dependency::declaredVersion)
        );
        limitations.add(
            "Top-level folders are structural groupings, not proven deployment modules. Manifest versions are declarations, not resolved dependency graphs or vulnerability assessments. No network or dependency installation occurs."
        );
        limitations.add(
            "Language labels are indexed filename extensions, not semantic language detection. Counts cover only approved, supported, redacted snapshot text; excluded, oversized, binary and unsupported files are absent. Content bytes are UTF-8 bytes after redaction, and line counts use the index's stored line convention. Structured declaration and impact analysis remains Java-only."
        );
        limitations.add(
            "npm and Maven dependency evidence is file-level (line 0); Python and Go dependency evidence identifies the exact indexed declaration line."
        );
        if (dependencies.size() >= MAX_DEPENDENCIES) limitations.add(
            "The dependency declaration limit of " +
                MAX_DEPENDENCIES +
                " was reached. Additional declarations or manifests may be omitted; narrow the approved scope to inspect them. Inventory counts still cover all indexed files."
        );
        var languages = new ArrayList<Language>();
        inventory.forEach((language, files) ->
            languages.add(
                new Language(
                    language,
                    files.size(),
                    files
                        .stream()
                        .mapToLong(RepositoryIndex.Source::lines)
                        .sum(),
                    files
                        .stream()
                        .mapToLong(
                            file ->
                                file
                                    .content()
                                    .getBytes(StandardCharsets.UTF_8)
                                    .length
                        )
                        .sum(),
                    files
                        .stream()
                        .map(RepositoryIndex.Source::path)
                        .sorted()
                        .toList()
                )
            )
        );
        return new Report(
            List.copyOf(modules),
            List.copyOf(dependencies),
            List.copyOf(signals),
            List.copyOf(limitations),
            snapshot.id(),
            List.copyOf(languages)
        );
    }

    private static void python(
        RepositoryIndex.Source file,
        List<Dependency> dependencies,
        List<String> limitations
    ) {
        String[] lines = file.content().split("\\R", -1);
        int skipped = 0;
        boolean continuation = false;
        for (int i = 0; i < lines.length; i++) {
            if (dependencies.size() >= MAX_DEPENDENCIES) break;
            String declaration = lines[i].strip();
            if (declaration.isEmpty() || declaration.startsWith("#")) continue;
            int comment = declaration.indexOf(" #");
            if (comment >= 0) declaration = declaration
                .substring(0, comment)
                .stripTrailing();
            boolean continues = declaration.endsWith("\\");
            if (continuation || continues) {
                continuation = continues;
                skipped++;
                continue;
            }
            var named = PYTHON_NAME.matcher(declaration);
            if (declaration.length() > 2000 || !named.matches()) {
                skipped++;
                continue;
            }
            if (
                named
                    .group(1)
                    .matches(
                        "(?i).*(?:\\.whl|\\.zip|\\.tgz|\\.tar(?:\\.gz|\\.bz2|\\.xz)?)$"
                    )
            ) {
                skipped++;
                continue;
            }
            String tail = named.group(2).strip();
            String[] sections = tail.split(";", -1);
            if (
                sections.length > 2 ||
                !PYTHON_SPEC.matcher(sections[0].strip()).matches() ||
                (sections.length == 2 &&
                    !PYTHON_MARKER.matcher(sections[1].strip()).matches())
            ) {
                skipped++;
                continue;
            }
            String version = tail.isEmpty() ? "Not specified" : tail;
            if (
                bounded(named.group(1), version, file.path(), limitations)
            ) dependencies.add(
                new Dependency(
                    "Python requirements",
                    named.group(1),
                    version,
                    file.path(),
                    i + 1
                )
            );
        }
        limitations.add(
            file.path() +
                ": conservative named requirements only; extras, version specifiers and simple environment markers are literal declarations. Includes, options, editable, local, URL/VCS requirements, continuations and unsupported syntax are not followed or resolved; " +
                skipped +
                " non-comment line(s) skipped."
        );
    }

    private static void go(
        RepositoryIndex.Source file,
        List<Dependency> dependencies,
        List<String> limitations
    ) {
        String[] lines = file.content().split("\\R", -1);
        boolean requireBlock = false;
        boolean otherBlock = false;
        int skipped = 0;
        if (file.content().contains("/*") || file.content().contains("*/")) {
            limitations.add(
                file.path() +
                    ": unsupported block-comment syntax; no Go require declarations were extracted from this file. Go replace, exclude, retract and dependency resolution are not applied."
            );
            return;
        }
        for (int i = 0; i < lines.length; i++) {
            if (dependencies.size() >= MAX_DEPENDENCIES) break;
            String declaration = lines[i].strip();
            if (declaration.length() > 2000) {
                skipped++;
                continue;
            }
            int comment = declaration.indexOf("//");
            if (comment >= 0) declaration = declaration
                .substring(0, comment)
                .stripTrailing();
            if (declaration.isEmpty()) continue;
            if (otherBlock) {
                if (declaration.equals(")")) otherBlock = false;
                continue;
            }
            if (declaration.matches("(?:replace|exclude|retract)\\s*\\(")) {
                otherBlock = true;
                continue;
            }
            if (declaration.matches("require\\s*\\(")) {
                if (requireBlock) skipped++;
                requireBlock = true;
                continue;
            }
            if (declaration.equals(")") && requireBlock) {
                requireBlock = false;
                continue;
            }
            String candidate;
            if (requireBlock) candidate = declaration;
            else if (
                declaration.startsWith("require ") ||
                declaration.startsWith("require\t")
            ) candidate = declaration.substring(7).strip();
            else continue;
            var match = GO_REQUIRE.matcher(candidate);
            if (
                !match.matches() ||
                Arrays.stream(match.group(1).split("/", -1)).anyMatch(
                    part ->
                        part.isEmpty() || part.equals(".") || part.equals("..")
                )
            ) {
                skipped++;
                continue;
            }
            if (
                bounded(
                    match.group(1),
                    match.group(2),
                    file.path(),
                    limitations
                )
            ) dependencies.add(
                new Dependency(
                    "Go modules",
                    match.group(1),
                    match.group(2),
                    file.path(),
                    i + 1
                )
            );
        }
        limitations.add(
            file.path() +
                ": conservative go.mod require declarations only. Versions remain literal; replace, exclude, retract, workspace/vendor configuration, transitive resolution and module downloads are not applied. " +
                skipped +
                " unsupported require line(s) skipped" +
                (requireBlock || otherBlock
                    ? "; an unterminated block was present."
                    : ".")
        );
    }

    private static boolean bounded(
        String name,
        String version,
        String path,
        List<String> limitations
    ) {
        if (
            name.length() <= MAX_NAME_LENGTH &&
            version.length() <= MAX_VERSION_LENGTH
        ) return true;
        String warning =
            path +
            ": dependency declarations exceeding the " +
            MAX_NAME_LENGTH +
            "-character name or " +
            MAX_VERSION_LENGTH +
            "-character version bound were omitted; values were not truncated or resolved.";
        if (!limitations.contains(warning)) limitations.add(warning);
        return false;
    }

    private static String directText(org.w3c.dom.Element element, String tag) {
        String value = null;
        for (
            var child = element.getFirstChild();
            child != null;
            child = child.getNextSibling()
        ) {
            if (
                !(child instanceof org.w3c.dom.Element field) ||
                !field.getTagName().equals(tag)
            ) continue;
            if (value != null) return null;
            for (
                var nested = field.getFirstChild();
                nested != null;
                nested = nested.getNextSibling()
            ) {
                if (nested instanceof org.w3c.dom.Element) return null;
            }
            value = field.getTextContent().strip();
        }
        return value;
    }
}
