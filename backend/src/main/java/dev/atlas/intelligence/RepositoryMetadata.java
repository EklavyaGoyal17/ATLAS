package dev.atlas.intelligence;

import java.io.StringReader;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RepositoryMetadata {

    public record Dependency(
        String ecosystem,
        String name,
        String declaredVersion,
        String path
    ) {}

    public record Report(
        List<String> modules,
        List<Dependency> dependencies,
        List<String> signals,
        List<String> limitations
    ) {}

    private final RepositoryIndex index;
    private final JsonMapper json;

    public RepositoryMetadata(RepositoryIndex index, JsonMapper json) {
        this.index = index;
        this.json = json;
    }

    public Report report() {
        var snapshot = index.current();
        var modules = new TreeSet<String>();
        var dependencies = new ArrayList<Dependency>();
        var signals = new TreeSet<String>();
        var limitations = new ArrayList<String>();
        for (var file : snapshot.files()) {
            if (file.path().contains("/")) modules.add(
                file.path().substring(0, file.path().indexOf('/'))
            );
            try {
                if (file.path().endsWith("package.json")) {
                    var manifest = json.readTree(file.content());
                    for (String group : List.of(
                        "dependencies",
                        "devDependencies"
                    )) {
                        var entries = manifest.get(group);
                        if (entries == null || !entries.isObject()) continue;
                        for (var entry : entries.properties())
                            if (entry.getValue().isString()) dependencies.add(
                                new Dependency(
                                    "npm " + group,
                                    entry.getKey(),
                                    entry.getValue().asText(),
                                    file.path()
                                )
                            );
                    }
                }
                if (file.path().endsWith("pom.xml")) {
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
                        var element = (org.w3c.dom.Element) elements.item(i);
                        var group = element.getElementsByTagName("groupId");
                        var artifact = element.getElementsByTagName(
                            "artifactId"
                        );
                        var version = element.getElementsByTagName("version");
                        if (
                            group.getLength() == 1 && artifact.getLength() == 1
                        ) dependencies.add(
                            new Dependency(
                                "Maven",
                                group.item(0).getTextContent() +
                                    ":" +
                                    artifact.item(0).getTextContent(),
                                version.getLength() == 1
                                    ? version.item(0).getTextContent()
                                    : "Inherited/managed; not resolved",
                                file.path()
                            )
                        );
                    }
                }
            } catch (Exception e) {
                limitations.add(
                    file.path() +
                        ": manifest could not be parsed safely; no dependency resolution attempted."
                );
            }
        }
        for (var dependency : dependencies) {
            if (dependency.name().contains("spring-boot")) signals.add(
                "Spring Boot dependency declaration"
            );
            if (dependency.name().equals("react")) signals.add(
                "React dependency declaration"
            );
            if (dependency.name().contains("postgresql")) signals.add(
                "PostgreSQL driver dependency declaration"
            );
            if (dependency.name().contains("opentelemetry")) signals.add(
                "OpenTelemetry dependency declaration"
            );
        }
        dependencies.sort(
            Comparator.comparing(Dependency::path).thenComparing(
                Dependency::name
            )
        );
        limitations.add(
            "Top-level folders are structural groupings, not proven deployment modules. Manifest versions are declarations, not resolved dependency graphs or vulnerability assessments. No network or dependency installation occurs."
        );
        return new Report(
            List.copyOf(modules),
            List.copyOf(dependencies),
            List.copyOf(signals),
            List.copyOf(limitations)
        );
    }
}
