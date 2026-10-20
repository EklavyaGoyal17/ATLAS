package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.atlas.platform.ApiException;
import dev.atlas.workspace.Documents;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class RepositoryMetadataTest {

    @TempDir
    Path root;

    RepositoryIndex index() throws Exception {
        Files.createDirectories(root.resolve(".git"));
        return new RepositoryIndex(root.toString(), mock(Documents.class));
    }

    RepositoryIndex.Snapshot remote(
        RepositoryIndex index,
        RepositoryIndex.RemoteFile... files
    ) {
        return index.indexRemote(
            index.importRevision(),
            List.of(files),
            new RepositoryIndex.Origin(
                "GITHUB",
                "https://github.com/example/repository",
                "a".repeat(40),
                ""
            )
        );
    }

    @Test
    void inventoryCountsOnlyRetainedRedactedSnapshotTextInDeterministicOrder()
        throws Exception {
        var index = index();
        var snapshot = remote(
            index,
            new RepositoryIndex.RemoteFile("z/b.py", "print('€')\n"),
            new RepositoryIndex.RemoteFile(
                "a/a.py",
                "api_key = 'this-is-not-public'\nprint('α')"
            ),
            new RepositoryIndex.RemoteFile("README.md", "# 中文\n")
        );
        var report = new RepositoryMetadata(index, new JsonMapper()).report(
            snapshot.id()
        );
        assertThat(report.snapshotId()).isEqualTo(snapshot.id());
        assertThat(report.languages())
            .extracting(RepositoryMetadata.Language::language)
            .containsExactly("md", "py");
        var python = report.languages().get(1);
        assertThat(python.paths()).containsExactly("a/a.py", "z/b.py");
        assertThat(python.files()).isEqualTo(2);
        assertThat(python.lines()).isEqualTo(
            snapshot
                .files()
                .stream()
                .filter(f -> f.language().equals("py"))
                .mapToLong(RepositoryIndex.Source::lines)
                .sum()
        );
        assertThat(python.contentBytes()).isEqualTo(
            snapshot
                .files()
                .stream()
                .filter(f -> f.language().equals("py"))
                .mapToLong(
                    f -> f.content().getBytes(StandardCharsets.UTF_8).length
                )
                .sum()
        );
        assertThat(index.file("a/a.py").content())
            .contains("[REDACTED]")
            .doesNotContain("this-is-not-public");
        assertThat(report.modules()).containsExactly("a", "z");
        assertThat(report.limitations()).anyMatch(l ->
            l.contains("after redaction")
        );
        assertThat(report.limitations()).anyMatch(l -> l.contains("Java-only"));
        assertThat(
            new RepositoryMetadata(index, new JsonMapper()).report()
        ).isEqualTo(report);
    }

    @Test
    void metadataReadsOneSnapshotAndDoesNotRefreshFilesOrReuseReplacedDeletedSnapshots()
        throws Exception {
        var index = index();
        Files.writeString(
            root.resolve("requirements.txt"),
            "requests==2.32.0\n"
        );
        var first = index.index(".", true);
        var metadata = new RepositoryMetadata(index, new JsonMapper());
        Files.writeString(root.resolve("requirements.txt"), "changed==9.9.9\n");
        assertThat(metadata.report(first.id()).dependencies())
            .extracting(RepositoryMetadata.Dependency::name)
            .containsExactly("requests");
        var second = index.index(".", true);
        assertThat(metadata.report(second.id()).dependencies())
            .extracting(RepositoryMetadata.Dependency::name)
            .containsExactly("changed");
        assertThatThrownBy(() ->
            metadata.report(first.id())
        ).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status.value()).isEqualTo(409);
            assertThat(e.code).isEqualTo("STALE_SNAPSHOT");
        });
        index.clear();
        assertThatThrownBy(() -> metadata.report(second.id())).isInstanceOf(
            ApiException.class
        );
        assertThatThrownBy(metadata::report).isInstanceOf(ApiException.class);
    }

    @Test
    void reportUsesTheCapturedSnapshotEvenIfCurrentChangesAfterItsRead() {
        var index = mock(RepositoryIndex.class);
        UUID id = UUID.randomUUID();
        var captured = new RepositoryIndex.Snapshot(
            id,
            List.of(
                new RepositoryIndex.Source("a.py", "py", 1, "α", List.of())
            ),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of("py", 1L),
            2
        );
        when(index.current())
            .thenReturn(captured)
            .thenThrow(new IllegalStateException("Second read forbidden"));
        var report = new RepositoryMetadata(index, new JsonMapper()).report(id);
        assertThat(report.snapshotId()).isEqualTo(id);
        assertThat(report.languages().getFirst().contentBytes()).isEqualTo(2);
        verify(index, times(1)).current();
    }

    @Test
    void parsesConservativePythonRequirementsAndPreservesLiteralDeclarationsAndExactLines()
        throws Exception {
        var index = index();
        remote(
            index,
            new RepositoryIndex.RemoteFile(
                "python/requirements-dev.txt",
                """
                # comments carry no dependencies
                requests>=2.31,<3
                fastapi[standard]>=0.115 ; python_version >= "3.10" and sys_platform == 'linux'
                django
                urllib3==2.5.0 # retained exact declaration
                """
            )
        );
        var report = new RepositoryMetadata(index, new JsonMapper()).report();
        assertThat(report.dependencies())
            .extracting(RepositoryMetadata.Dependency::name)
            .containsExactly("django", "fastapi", "requests", "urllib3");
        assertThat(report.dependencies()).allMatch(
            d ->
                d.ecosystem().equals("Python requirements") &&
                d.path().equals("python/requirements-dev.txt")
        );
        assertThat(report.dependencies().get(1).declaredVersion()).isEqualTo(
            "[standard]>=0.115 ; python_version >= \"3.10\" and sys_platform == 'linux'"
        );
        assertThat(report.dependencies().get(1).line()).isEqualTo(3);
        assertThat(report.dependencies().get(0).declaredVersion()).isEqualTo(
            "Not specified"
        );
        assertThat(report.dependencies().get(3).declaredVersion()).isEqualTo(
            "==2.5.0"
        );
        assertThat(report.limitations()).anyMatch(l ->
            l.contains("0 non-comment line(s) skipped")
        );
    }

    @Test
    void skipsPythonOptionsIncludesUrlsVcsEditableLocalAndMalformedSyntaxWithoutFollowingThem()
        throws Exception {
        var index = index();
        remote(
            index,
            new RepositoryIndex.RemoteFile(
                "requirements.txt",
                """
                -r secrets.txt
                --requirement https://example.com/remote.txt
                -c constraints.txt
                --index-url https://example.com
                -e git+https://example.com/a.git#egg=evil
                evil @ https://example.com/evil.whl
                git+ssh://example.com/repository
                ./local-package
                /absolute/path
                archive.whl
                invalid==1 \\
                    --hash=sha256:abc
                invalid>=
                bad; python_version > __import__('os')
                accepted==1.2.3
                """
            )
        );
        var report = new RepositoryMetadata(index, new JsonMapper()).report();
        assertThat(report.dependencies())
            .extracting(RepositoryMetadata.Dependency::name)
            .containsExactly("accepted");
        assertThat(report.limitations()).anyMatch(l ->
            l.contains("14 non-comment line(s) skipped")
        );
    }

    @Test
    void goRequireSingleAndBlockHaveExactLinesAndDoNotResolveOverridesOrIndirectVersions()
        throws Exception {
        var index = index();
        remote(
            index,
            new RepositoryIndex.RemoteFile(
                "service/go.mod",
                """
                module example.com/service
                go 1.24
                require example.com/a v1.2.3
                require (
                    example.com/b/v2 v2.1.0 // indirect
                    golang.org/x/sys v0.0.0-20260101000000-abcdefabcdef
                )
                replace example.com/a => ../local
                exclude example.com/a v1.2.3
                retract (
                    v1.2.0
                )
                """
            )
        );
        var report = new RepositoryMetadata(index, new JsonMapper()).report();
        assertThat(report.dependencies())
            .extracting(RepositoryMetadata.Dependency::name)
            .containsExactly(
                "example.com/a",
                "example.com/b/v2",
                "golang.org/x/sys"
            );
        assertThat(report.dependencies())
            .extracting(RepositoryMetadata.Dependency::line)
            .containsExactly(3, 5, 6);
        assertThat(report.dependencies())
            .extracting(RepositoryMetadata.Dependency::declaredVersion)
            .containsExactly(
                "v1.2.3",
                "v2.1.0",
                "v0.0.0-20260101000000-abcdefabcdef"
            );
        assertThat(report.limitations()).anyMatch(
            l ->
                l.contains("replace, exclude, retract") &&
                l.contains("not applied")
        );
    }

    @Test
    void skipsUnsupportedGoDeclarationsAndNeverTreatsOtherBlocksAsRequires()
        throws Exception {
        var index = index();
        remote(
            index,
            new RepositoryIndex.RemoteFile(
                "go.mod",
                """
                replace (
                    require example.com/wrong v1.2.3
                )
                require (
                    "example.com/quoted" v1.2.3
                    example.com/missing
                    example.com/garbage main
                    example.com/../../escape v1.2.3
                    example.com/good v1.2.3
                """
            )
        );
        var report = new RepositoryMetadata(index, new JsonMapper()).report();
        assertThat(report.dependencies())
            .extracting(RepositoryMetadata.Dependency::name)
            .containsExactly("example.com/good");
        assertThat(report.limitations()).anyMatch(
            l ->
                l.contains("4 unsupported require line(s) skipped") &&
                l.contains("unterminated block")
        );
    }

    @Test
    void exactBasenamesAndEcosystemSignalsAvoidFalseManifestAndFrameworkClaims()
        throws Exception {
        var index = index();
        remote(
            index,
            new RepositoryIndex.RemoteFile(
                "notpackage.json",
                "{\"dependencies\":{\"react\":\"19\"}}"
            ),
            new RepositoryIndex.RemoteFile(
                "notpom.xml",
                "<project><dependency><groupId>x</groupId><artifactId>x</artifactId></dependency></project>"
            ),
            new RepositoryIndex.RemoteFile("notrequirements.txt", "bad==1"),
            new RepositoryIndex.RemoteFile(
                "requirements.txt",
                "react==1\nspring-boot==1\npostgresql==1\nopentelemetry==1"
            ),
            new RepositoryIndex.RemoteFile("a/requirements.txt", "react==2")
        );
        var report = new RepositoryMetadata(index, new JsonMapper()).report();
        assertThat(report.dependencies()).hasSize(5);
        assertThat(report.dependencies())
            .extracting(RepositoryMetadata.Dependency::path)
            .containsExactly(
                "a/requirements.txt",
                "requirements.txt",
                "requirements.txt",
                "requirements.txt",
                "requirements.txt"
            );
        assertThat(report.signals()).isEmpty();
        assertThat(report.languages())
            .extracting(RepositoryMetadata.Language::language)
            .containsExactly("json", "txt", "xml");
    }

    @Test
    void exactGoManifestIsImportedLocallyAndRemotelyWithoutAllowingOtherModFilesOrExcludedFolders()
        throws Exception {
        var index = index();
        Files.writeString(
            root.resolve("go.mod"),
            "module example.com/local\nrequire example.com/a v1.2.3\n"
        );
        Files.writeString(root.resolve("other.mod"), "unsupported");
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(
            root.resolve("node_modules/go.mod"),
            "module forbidden"
        );
        var local = index.index(".", true);
        assertThat(local.files())
            .extracting(RepositoryIndex.Source::path)
            .containsExactly("go.mod");
        assertThat(local.files().getFirst().language()).isEqualTo("mod");
        assertThat(RepositoryIndex.supportedPath("src/go.mod")).isTrue();
        assertThat(RepositoryIndex.supportedPath("src/notgo.mod")).isFalse();
        assertThat(
            RepositoryIndex.supportedPath("node_modules/go.mod")
        ).isFalse();
        var snapshot = remote(
            index,
            new RepositoryIndex.RemoteFile(
                "nested/go.mod",
                "module example.com/remote\nrequire example.com/b v1.2.3\n"
            )
        );
        var report = new RepositoryMetadata(index, new JsonMapper()).report(
            snapshot.id()
        );
        assertThat(report.dependencies())
            .extracting(RepositoryMetadata.Dependency::name)
            .containsExactly("example.com/b");
        assertThat(report.languages().getFirst().paths()).containsExactly(
            "nested/go.mod"
        );
    }

    @Test
    void skipsEntirePythonContinuationChainsAndKeepsPhysicalCrLfAndCrEvidenceLines()
        throws Exception {
        var index = index();
        remote(
            index,
            new RepositoryIndex.RemoteFile(
                "requirements.txt",
                "foo \\\r\nbar \\\r\nbaz\r\naccepted==1\rnext==2\n"
            ),
            new RepositoryIndex.RemoteFile(
                "go.mod",
                "module example.com/a\r\nrequire example.com/b v1.2.3\rrequire example.com/c v1.2.4\n"
            )
        );
        var report = new RepositoryMetadata(index, new JsonMapper()).report();
        assertThat(report.dependencies())
            .extracting(RepositoryMetadata.Dependency::name)
            .containsExactly(
                "example.com/b",
                "example.com/c",
                "accepted",
                "next"
            );
        assertThat(report.dependencies())
            .extracting(RepositoryMetadata.Dependency::line)
            .containsExactly(2, 3, 4, 5);
        assertThat(report.limitations()).anyMatch(l ->
            l.contains("3 non-comment line(s) skipped")
        );
    }

    @Test
    void boundsDeclarationCountsForEveryFormatBeforeBuildingAnUnboundedReport()
        throws Exception {
        var index = index();
        var requirements = new StringBuilder();
        var go = new StringBuilder("module example.com/test\nrequire (\n");
        var npm = new StringBuilder("{\"dependencies\":{");
        var pom = new StringBuilder("<project><dependencies>");
        for (int i = 0; i < 2100; i++) {
            requirements.append("p").append(i).append("==1\n");
            go.append("example.com/p").append(i).append(" v1.2.3\n");
            if (i > 0) npm.append(',');
            npm.append('"').append("p").append(i).append("\":\"1\"");
            pom.append("<dependency><groupId>g</groupId><artifactId>p")
                .append(i)
                .append("</artifactId></dependency>");
        }
        go.append(")\n");
        npm.append("}}");
        pom.append("</dependencies></project>");
        for (var fixture : Map.of(
            "requirements.txt",
            requirements.toString(),
            "go.mod",
            go.toString(),
            "package.json",
            npm.toString(),
            "pom.xml",
            pom.toString()
        ).entrySet()) {
            remote(
                index,
                new RepositoryIndex.RemoteFile(
                    fixture.getKey(),
                    fixture.getValue()
                ),
                new RepositoryIndex.RemoteFile(
                    "z/other.py",
                    "print('count me')"
                )
            );
            var report = new RepositoryMetadata(
                index,
                new JsonMapper()
            ).report();
            assertThat(report.dependencies())
                .as(fixture.getKey())
                .hasSize(RepositoryMetadata.MAX_DEPENDENCIES);
            assertThat(report.limitations()).anyMatch(l ->
                l.contains("declaration limit of 2000")
            );
            assertThat(
                report
                    .languages()
                    .stream()
                    .mapToInt(RepositoryMetadata.Language::files)
                    .sum()
            ).isEqualTo(2);
            assertThat(
                new RepositoryMetadata(index, new JsonMapper()).report()
            ).isEqualTo(report);
        }
    }

    @Test
    void boundsFieldsAndOverallDependencyTextWithoutTruncatingValuesOrAmplifyingNestedXml()
        throws Exception {
        var index = index();
        String huge = "x".repeat(3000);
        remote(
            index,
            new RepositoryIndex.RemoteFile(
                "pom.xml",
                "<project><dependency><groupId>outer<dependency><groupId>g</groupId><artifactId>inner</artifactId></dependency></groupId><artifactId>outer</artifactId></dependency><dependency><groupId>g</groupId><artifactId>" +
                    huge +
                    "</artifactId></dependency></project>"
            ),
            new RepositoryIndex.RemoteFile(
                "package.json",
                "{\"dependencies\":{\"too-long\":\"" +
                    huge +
                    "\",\"accepted\":\"1\"}}"
            ),
            new RepositoryIndex.RemoteFile(
                "requirements.txt",
                "long" + "a".repeat(3000) + "==1\naccepted==1\n"
            ),
            new RepositoryIndex.RemoteFile(
                "go.mod",
                "require example.com/" +
                    "a".repeat(3000) +
                    " v1.2.3\nrequire example.com/accepted v1.2.3\n"
            )
        );
        var report = new RepositoryMetadata(index, new JsonMapper()).report();
        assertThat(report.dependencies())
            .extracting(RepositoryMetadata.Dependency::name)
            .containsExactly(
                "example.com/accepted",
                "accepted",
                "g:inner",
                "accepted"
            );
        assertThat(report.limitations()).anyMatch(l ->
            l.contains("values were not truncated")
        );
        var files = new ArrayList<RepositoryIndex.RemoteFile>();
        String version = "x ".repeat(950);
        for (int n = 0; n < 6; n++) {
            var content = new StringBuilder("{\"dependencies\":{");
            for (int i = 0; i < 75; i++) {
                if (i > 0) content.append(',');
                content
                    .append('"')
                    .append("p")
                    .append(i)
                    .append("\":\"")
                    .append(version)
                    .append('"');
            }
            files.add(
                new RepositoryIndex.RemoteFile(
                    "m" + n + "/package.json",
                    content.append("}}").toString()
                )
            );
        }
        remote(index, files.toArray(RepositoryIndex.RemoteFile[]::new));
        var bounded = new RepositoryMetadata(index, new JsonMapper()).report();
        int bytes = bounded
            .dependencies()
            .stream()
            .mapToInt(
                d ->
                    (d.ecosystem() + d.name() + d.declaredVersion() + d.path())
                        .getBytes(StandardCharsets.UTF_8)
                        .length
            )
            .sum();
        assertThat(bytes).isLessThanOrEqualTo(
            RepositoryMetadata.MAX_DEPENDENCY_BYTES
        );
        assertThat(bounded.dependencies())
            .hasSizeLessThan(450)
            .allMatch(d -> d.declaredVersion().equals(version));
        assertThat(bounded.limitations()).anyMatch(l ->
            l.contains("text budget of 512000")
        );
    }

    @Test
    void goBlockCommentsAndDotSegmentsCannotBecomeDependencyEvidence()
        throws Exception {
        var index = index();
        remote(
            index,
            new RepositoryIndex.RemoteFile(
                "go.mod",
                "/*\nrequire example.com/not-real v1.2.3\n*/\n"
            )
        );
        var report = new RepositoryMetadata(index, new JsonMapper()).report();
        assertThat(report.dependencies()).isEmpty();
        assertThat(report.limitations()).anyMatch(l ->
            l.contains("unsupported block-comment syntax")
        );
        remote(
            index,
            new RepositoryIndex.RemoteFile(
                "go.mod",
                "require example.com/.. v1.2.3\nrequire example.com/./foo v1.2.3\nrequire example.com/good v1.2.3\n"
            )
        );
        assertThat(
            new RepositoryMetadata(index, new JsonMapper())
                .report()
                .dependencies()
        )
            .extracting(RepositoryMetadata.Dependency::name)
            .containsExactly("example.com/good");
    }

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
