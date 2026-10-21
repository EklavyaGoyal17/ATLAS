package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.atlas.platform.ApiException;
import dev.atlas.workspace.Documents;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

@Timeout(8)
class RepositoryLocalLifecycleTest {

    @TempDir
    Path root;

    Documents documents = mock(Documents.class);
    ExecutorService callers;
    final RepositoryIndex.Origin remoteOrigin = new RepositoryIndex.Origin(
        "GITHUB",
        "https://github.com/example/project",
        "a".repeat(40),
        ""
    );
    final long second = TimeUnit.SECONDS.toNanos(1);

    @BeforeEach
    void fixture() throws Exception {
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve("README.md"), "# Local source\n");
        callers = Executors.newCachedThreadPool();
    }

    @AfterEach
    void cleanup() throws Exception {
        MDC.clear();
        callers.shutdownNow();
        assertThat(callers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    RepositoryIndex index(AtomicLong clock, Consumer<String> stage) {
        return new RepositoryIndex(
            root.toString(),
            documents,
            clock::get,
            5 * second,
            stage
        );
    }

    RepositoryIndex.Snapshot remote(RepositoryIndex index, String text) {
        return index.indexRemote(
            index.importRevision(),
            List.of(new RepositoryIndex.RemoteFile("README.md", text)),
            remoteOrigin
        );
    }

    static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    // Models a parser or filesystem operation that does not honor cancellation.
    static void ignoreInterrupts(CountDownLatch release) {
        boolean interrupted = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    static void fails(Future<?> result, String code) {
        assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code).isEqualTo(code)
            );
    }

    @Test
    void approvalAndPathValidationDoNotStartWorkOrAudit() {
        var stages = new ArrayList<String>();
        var index = index(new AtomicLong(), stages::add);
        assertThatThrownBy(() -> index.index(".", false)).isInstanceOf(
            ApiException.class
        );
        assertThatThrownBy(() -> index.index("../outside", true)).isInstanceOf(
            ApiException.class
        );
        assertThat(stages).isEmpty();
        verifyNoInteractions(documents);
        assertThat(index.importRevision()).isZero();
    }

    @Test
    void invalidCallerCorrelationDoesNotStrandWorkerAdmission() {
        var index = index(new AtomicLong(), stage -> {});
        MDC.put("correlationId", "not-a-uuid");
        assertThatThrownBy(() -> index.index(".", true)).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThat(index.importRevision()).isZero();
        verifyNoInteractions(documents);
        MDC.put("correlationId", UUID.randomUUID().toString());
        assertThat(index.index(".", true).origin().kind()).isEqualTo("LOCAL");
        assertThat(index.importRevision()).isEqualTo(1);
    }

    @Test
    void clearAfterWorkerExitButBeforeCallerPublishIsFencedByTheFinalCas()
        throws Exception {
        var beforePublish = new CountDownLatch(1);
        var releasePublish = new CountDownLatch(1);
        var workerExited = new CountDownLatch(1);
        var index = index(new AtomicLong(), stage -> {
            if (stage.equals("worker-exit")) workerExited.countDown();
            if (stage.equals("publish")) {
                beforePublish.countDown();
                ignoreInterrupts(releasePublish);
            }
        });
        remote(index, "old");
        Future<?> request = callers.submit(() -> index.index(".", true));
        try {
            await(beforePublish);
            await(workerExited);
            callers.submit(index::clear).get(1, TimeUnit.SECONDS);
            assertThat(index.configuration().get("indexed")).isEqualTo(false);
            releasePublish.countDown();
            fails(request, "IMPORT_SUPERSEDED");
            assertThat(index.importRevision()).isEqualTo(2);
            assertThatThrownBy(index::current).isInstanceOf(ApiException.class);
        } finally {
            releasePublish.countDown();
        }
    }

    @Test
    void deadlineDuringScanningPreservesPreviousStateAndDoesNotAuditNewDraft() {
        var clock = new AtomicLong();
        var index = index(clock, stage -> {
            if (stage.equals("file")) clock.set(6 * second);
        });
        var old = remote(index, "old");
        long revision = index.importRevision();
        clearInvocations(documents);
        assertThatThrownBy(() -> index.index(".", true)).isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.code).isEqualTo("LOCAL_IMPORT_TIMEOUT")
        );
        assertThat(index.current()).isSameAs(old);
        assertThat(index.importRevision()).isEqualTo(revision);
        verifyNoInteractions(documents);
    }

    @Test
    void timeoutCannotPublishAndIgnoredCancellationKeepsTheOnlyWorkerSlot()
        throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var index = new RepositoryIndex(
            root.toString(),
            documents,
            System::nanoTime,
            second,
            stage -> {
                if (stage.equals("scan")) {
                    entered.countDown();
                    ignoreInterrupts(release);
                }
                if (stage.equals("worker-exit")) exited.countDown();
            }
        );
        var old = remote(index, "old");
        long revision = index.importRevision();
        Future<?> request = callers.submit(() -> index.index(".", true));
        try {
            await(entered);
            fails(request, "LOCAL_IMPORT_TIMEOUT");
            assertThat(index.current()).isSameAs(old);
            assertThat(index.importRevision()).isEqualTo(revision);
            assertThatThrownBy(() ->
                index.index(".", true)
            ).isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code).isEqualTo("LOCAL_IMPORT_BUSY")
            );
            index.clear();
            assertThat(index.configuration().get("indexed")).isEqualTo(false);
        } finally {
            release.countDown();
        }
        await(exited);
        assertThatThrownBy(index::current).isInstanceOf(ApiException.class);
        assertThat(index.importRevision()).isEqualTo(revision + 1);
        assertThat(index.index(".", true).origin().kind()).isEqualTo("LOCAL");
    }

    @Test
    void currentConfigurationRevisionAndClearRemainResponsiveDuringBlockedScan()
        throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var index = index(new AtomicLong(), stage -> {
            if (stage.equals("scan")) {
                entered.countDown();
                ignoreInterrupts(release);
            }
            if (stage.equals("worker-exit")) exited.countDown();
        });
        var old = remote(index, "old");
        Future<?> request = callers.submit(() -> index.index(".", true));
        try {
            await(entered);
            assertThat(
                callers.submit(index::current).get(1, TimeUnit.SECONDS)
            ).isSameAs(old);
            assertThat(
                callers
                    .submit(index::configuration)
                    .get(1, TimeUnit.SECONDS)
                    .get("indexed")
            ).isEqualTo(true);
            long before = callers
                .submit(index::importRevision)
                .get(1, TimeUnit.SECONDS);
            callers.submit(index::clear).get(1, TimeUnit.SECONDS);
            assertThat(index.importRevision()).isEqualTo(before + 1);
            fails(request, "IMPORT_SUPERSEDED");
        } finally {
            release.countDown();
        }
        await(exited);
        assertThatThrownBy(index::current).isInstanceOf(ApiException.class);
    }

    @Test
    void auditFailurePreservesOldStateAndCallerCorrelationReachesWorker() {
        var index = index(new AtomicLong(), stage -> {});
        var old = remote(index, "old");
        long revision = index.importRevision();
        String correlation = UUID.randomUUID().toString();
        MDC.put("correlationId", correlation);
        doAnswer(invocation -> {
            assertThat(MDC.get("correlationId")).isEqualTo(correlation);
            throw new IllegalStateException("synthetic audit failure");
        })
            .when(documents)
            .audit(eq("REPOSITORY_APPROVED"), anyString());
        assertThatThrownBy(() -> index.index(".", true)).hasMessage(
            "synthetic audit failure"
        );
        assertThat(index.current()).isSameAs(old);
        assertThat(index.importRevision()).isEqualTo(revision);
        assertThat(MDC.get("correlationId")).isEqualTo(correlation);
        doNothing()
            .when(documents)
            .audit(eq("REPOSITORY_APPROVED"), anyString());
        assertThat(index.index(".", true).origin().kind()).isEqualTo("LOCAL");
    }

    @Test
    void interruptedWaitingCallerRetainsItsInterruptAndCannotPublish()
        throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        var index = index(new AtomicLong(), stage -> {
            if (stage.equals("scan")) {
                entered.countDown();
                ignoreInterrupts(release);
            }
            if (stage.equals("worker-exit")) exited.countDown();
        });
        var old = remote(index, "old");
        Thread caller = Thread.ofPlatform()
            .daemon(true)
            .start(() -> {
                try {
                    index.index(".", true);
                } catch (Throwable error) {
                    failure.set(error);
                    interrupted.set(Thread.currentThread().isInterrupted());
                } finally {
                    returned.countDown();
                }
            });
        try {
            await(entered);
            caller.interrupt();
            await(returned);
            assertThat(failure.get()).isInstanceOfSatisfying(
                ApiException.class,
                error ->
                    assertThat(error.code).isEqualTo("LOCAL_IMPORT_INTERRUPTED")
            );
            assertThat(interrupted).isTrue();
            assertThat(index.current()).isSameAs(old);
            assertThatThrownBy(() ->
                index.index(".", true)
            ).isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code).isEqualTo("LOCAL_IMPORT_BUSY")
            );
        } finally {
            release.countDown();
        }
        await(exited);
        assertThat(index.current()).isSameAs(old);
    }

    @Test
    void callerDeadlineAlsoBoundsWaitingForAuditWithoutLatePublication()
        throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var index = new RepositoryIndex(
            root.toString(),
            documents,
            System::nanoTime,
            second,
            stage -> {
                if (stage.equals("worker-exit")) exited.countDown();
            }
        );
        var old = remote(index, "old");
        doAnswer(invocation -> {
            entered.countDown();
            ignoreInterrupts(release);
            return null;
        })
            .when(documents)
            .audit(eq("REPOSITORY_APPROVED"), anyString());
        Future<?> request = callers.submit(() -> index.index(".", true));
        try {
            await(entered);
            fails(request, "LOCAL_IMPORT_TIMEOUT");
            assertThat(index.current()).isSameAs(old);
            assertThat(index.importRevision()).isEqualTo(1);
            assertThatThrownBy(() ->
                index.index(".", true)
            ).isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code).isEqualTo("LOCAL_IMPORT_BUSY")
            );
        } finally {
            release.countDown();
        }
        await(exited);
        assertThat(index.current()).isSameAs(old);
    }

    @Test
    void elapsedDeadlineDuringAuditOrImmediatelyBeforePublishCannotPublish() {
        for (String delayedStage : List.of("complete", "publish")) {
            var clock = new AtomicLong();
            var index = index(clock, stage -> {
                if (stage.equals(delayedStage)) clock.set(6 * second);
            });
            var old = remote(index, "old");
            long revision = index.importRevision();
            assertThatThrownBy(() ->
                index.index(".", true)
            ).isInstanceOfSatisfying(ApiException.class, error ->
                assertThat(error.code).isEqualTo("LOCAL_IMPORT_TIMEOUT")
            );
            assertThat(index.current()).isSameAs(old);
            assertThat(index.importRevision()).isEqualTo(revision);
        }
    }

    @Test
    void clearDuringBlockedLocalAuditReturnsAndPreventsLatePublication()
        throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var index = index(new AtomicLong(), stage -> {
            if (stage.equals("worker-exit")) exited.countDown();
        });
        remote(index, "old");
        doAnswer(invocation -> {
            entered.countDown();
            ignoreInterrupts(release);
            return null;
        })
            .when(documents)
            .audit(eq("REPOSITORY_APPROVED"), anyString());
        Future<?> request = callers.submit(() -> index.index(".", true));
        try {
            await(entered);
            callers.submit(index::clear).get(1, TimeUnit.SECONDS);
            fails(request, "IMPORT_SUPERSEDED");
            assertThat(index.configuration().get("indexed")).isEqualTo(false);
        } finally {
            release.countDown();
        }
        await(exited);
        assertThatThrownBy(index::current).isInstanceOf(ApiException.class);
    }

    @Test
    void githubPublicationSupersedesAnInFlightLocalDraft() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var index = index(new AtomicLong(), stage -> {
            if (stage.equals("scan")) {
                entered.countDown();
                ignoreInterrupts(release);
            }
        });
        Future<?> request = callers.submit(() -> index.index(".", true));
        try {
            await(entered);
            var github = remote(index, "github replacement");
            release.countDown();
            fails(request, "IMPORT_SUPERSEDED");
            assertThat(index.current()).isSameAs(github);
            assertThat(index.importRevision()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void localPublicationSupersedesGithubBlockedInAuditWithoutBlockingReads()
        throws Exception {
        var index = index(new AtomicLong(), stage -> {});
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            ignoreInterrupts(release);
            return null;
        })
            .when(documents)
            .audit(eq("REPOSITORY_GITHUB_APPROVED"), anyString());
        Future<?> github = callers.submit(() -> remote(index, "github"));
        try {
            await(entered);
            var local = index.index(".", true);
            release.countDown();
            fails(github, "IMPORT_SUPERSEDED");
            assertThat(index.current()).isSameAs(local);
            assertThat(index.importRevision()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void clearSupersedesGithubBlockedInAuditAndDeletionAuditFailureStillClears()
        throws Exception {
        var index = index(new AtomicLong(), stage -> {});
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            ignoreInterrupts(release);
            return null;
        })
            .when(documents)
            .audit(eq("REPOSITORY_GITHUB_APPROVED"), anyString());
        Future<?> github = callers.submit(() -> remote(index, "github"));
        try {
            await(entered);
            doThrow(new IllegalStateException("delete audit unavailable"))
                .when(documents)
                .audit(eq("REPOSITORY_DELETED"), anyString());
            assertThatThrownBy(index::clear).hasMessage(
                "delete audit unavailable"
            );
            assertThat(index.importRevision()).isEqualTo(1);
            release.countDown();
            fails(github, "IMPORT_SUPERSEDED");
            assertThatThrownBy(index::current).isInstanceOf(ApiException.class);
        } finally {
            release.countDown();
        }
    }

    @Test
    void gitUsesOneMatchingSnapshotAndRootEvenWhenImportChangesBetweenCommands()
        throws Exception {
        for (String folder : List.of("a", "b")) {
            Files.createDirectories(root.resolve(folder).resolve(".git"));
            Files.writeString(
                root.resolve(folder).resolve("README.md"),
                folder
            );
        }
        var switched = new AtomicBoolean();
        var index = new RepositoryIndex(root.toString(), documents) {
            @Override
            String gitCommand(Path approved, List<String> args) {
                if (switched.compareAndSet(false, true)) index("b", true);
                return approved.getFileName().toString();
            }
        };
        index.index("a", true);
        assertThat(index.git("README.md"))
            .containsEntry("history", "a")
            .containsEntry("changes", "a");
        assertThat(index.current().files().getFirst().content()).isEqualTo("b");
    }
}
