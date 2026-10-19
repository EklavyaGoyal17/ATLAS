package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.atlas.workspace.Documents;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class RepositoryMetadataTest {

    @TempDir
    Path root;

    @Test
    void parsesDeclarationsWithoutInstallingDependenciesOrResolvingExternalXmlEntities()
        throws Exception {
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(
            root.resolve("pom.xml"),
            "<project><dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency></dependencies></project>"
        );
        Files.writeString(
            root.resolve("package.json"),
            "{\"dependencies\":{\"react\":\"19.3.1\"}}"
        );
        var index = new RepositoryIndex(root.toString(), mock(Documents.class));
        index.index(".", true);
        var metadata = new RepositoryMetadata(index, new JsonMapper());
        assertThat(metadata.report().dependencies()).hasSize(2);
        assertThat(metadata.report().signals()).contains(
            "React dependency declaration",
            "Spring Boot dependency declaration"
        );
        Files.writeString(
            root.resolve("pom.xml"),
            "<!DOCTYPE project [<!ENTITY attack SYSTEM 'file:///etc/passwd'>]><project>&attack;</project>"
        );
        index.index(".", true);
        assertThat(metadata.report().limitations()).anyMatch(l ->
            l.contains("could not be parsed safely")
        );
    }
}
