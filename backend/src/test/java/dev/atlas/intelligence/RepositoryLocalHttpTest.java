package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import dev.atlas.platform.ApiErrors;
import dev.atlas.workspace.Documents;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/** Servlet/controller error boundary only. Authentication is covered by the real-server integration suite. */
@Timeout(10)
class RepositoryLocalHttpTest {

    @TempDir
    Path root;

    @Test
    void busyAndTimedOutImportsKeepTheirHttpContractsAndCannotReplaceTheSnapshot()
        throws Exception {
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve("README.md"), "new local source\n");
        var documents = mock(Documents.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var index = new RepositoryIndex(
            root.toString(),
            documents,
            System::nanoTime,
            TimeUnit.SECONDS.toNanos(1),
            stage -> {
                if (stage.equals("scan")) {
                    entered.countDown();
                    boolean interrupted = false;
                    while (true) {
                        try {
                            release.await();
                            break;
                        } catch (InterruptedException error) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
                if (stage.equals("worker-exit")) exited.countDown();
            }
        );
        var original = index.indexRemote(
            0,
            List.of(
                new RepositoryIndex.RemoteFile(
                    "README.md",
                    "original approved source\n"
                )
            ),
            new RepositoryIndex.Origin(
                "GITHUB",
                "https://github.com/example/project",
                "a".repeat(40),
                ""
            )
        );
        var controller = new RepositoryController(
            index,
            null,
            null,
            null,
            null
        );
        var mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiErrors())
            .build();
        var json = JsonMapper.builder().build();
        var callers = Executors.newSingleThreadExecutor();
        String timeoutCorrelation = UUID.randomUUID().toString();
        String busyCorrelation = UUID.randomUUID().toString();
        try {
            Future<MvcResult> first = callers.submit(() -> {
                MDC.put("correlationId", timeoutCorrelation);
                try {
                    return mvc
                        .perform(
                            post("/api/repository/import")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"path\":\".\",\"approved\":true}")
                        )
                        .andReturn();
                } finally {
                    MDC.clear();
                }
            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            MDC.put("correlationId", busyCorrelation);
            var busy = mvc
                .perform(
                    post("/api/repository/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\".\",\"approved\":true}")
                )
                .andReturn()
                .getResponse();
            assertThat(busy.getStatus()).isEqualTo(429);
            assertThat(busy.getHeader("Retry-After")).isNull();
            var busyError = json.readTree(busy.getContentAsString());
            assertThat(busyError.get("code").asString()).isEqualTo(
                "LOCAL_IMPORT_BUSY"
            );
            assertThat(busyError.get("correlationId").asString()).isEqualTo(
                busyCorrelation
            );

            var timedOut = first.get(3, TimeUnit.SECONDS).getResponse();
            assertThat(timedOut.getStatus()).isEqualTo(503);
            var timeoutError = json.readTree(timedOut.getContentAsString());
            assertThat(timeoutError.get("code").asString()).isEqualTo(
                "LOCAL_IMPORT_TIMEOUT"
            );
            assertThat(timeoutError.get("correlationId").asString()).isEqualTo(
                timeoutCorrelation
            );
            assertThat(
                UUID.fromString(timeoutError.get("correlationId").asString())
            ).isEqualTo(UUID.fromString(timeoutCorrelation));
            var beforeRelease = mvc
                .perform(get("/api/repository"))
                .andReturn()
                .getResponse();
            assertThat(beforeRelease.getStatus()).isEqualTo(200);
            assertThat(
                json
                    .readTree(beforeRelease.getContentAsString())
                    .get("id")
                    .asString()
            ).isEqualTo(original.id().toString());

            release.countDown();
            assertThat(exited.await(3, TimeUnit.SECONDS)).isTrue();
            var afterRelease = mvc
                .perform(get("/api/repository"))
                .andReturn()
                .getResponse();
            assertThat(afterRelease.getStatus()).isEqualTo(200);
            assertThat(
                json
                    .readTree(afterRelease.getContentAsString())
                    .get("id")
                    .asString()
            ).isEqualTo(original.id().toString());
            assertThat(index.importRevision()).isEqualTo(1);
            verify(documents, never()).audit(
                eq("REPOSITORY_APPROVED"),
                anyString()
            );
        } finally {
            release.countDown();
            MDC.clear();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }
}
