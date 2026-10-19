package dev.atlas.intelligence;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Anonymous bounded HTTP transport. It never consults Git or workstation credentials. */
final class PublicGitHubClient implements GitHubRepositoryImport.Fetcher {

    static final class ResponseTooLargeException extends IOException {

        ResponseTooLargeException() {
            super("Public repository response exceeds its limit");
        }
    }

    private final HttpClient client;

    PublicGitHubClient() {
        this(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        );
    }

    PublicGitHubClient(HttpClient client) {
        if (
            client.followRedirects() != HttpClient.Redirect.NEVER ||
            client.authenticator().isPresent() ||
            client.cookieHandler().isPresent()
        ) throw new IllegalArgumentException(
            "Anonymous transport must disable redirects, authentication and cookies."
        );
        this.client = client;
    }

    public GitHubRepositoryImport.Reply fetch(URI uri, int maxBytes)
        throws Exception {
        if (
            maxBytes < 1 || maxBytes > 16 * 1024 * 1024
        ) throw new IllegalArgumentException("Invalid response limit");
        var request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "ATLAS-public-repository-import")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .GET()
            .build();
        var response = client.send(
            request,
            HttpResponse.BodyHandlers.ofInputStream()
        );
        try (var input = response.body()) {
            // HttpRequest.timeout bounds headers; streaming bodies need their own deadline.
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var pending = executor.submit(() -> input.readNBytes(maxBytes + 1));
            byte[] bytes;
            try {
                bytes = pending.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                pending.cancel(true);
                input.close();
                Thread.currentThread().interrupt();
                throw e;
            } catch (ExecutionException | TimeoutException e) {
                pending.cancel(true);
                input.close();
                throw new IOException(
                    "Public repository response unavailable",
                    e
                );
            } finally {
                executor.shutdownNow();
            }
            if (bytes.length > maxBytes) throw new ResponseTooLargeException();
            return new GitHubRepositoryImport.Reply(
                response.statusCode(),
                response.headers().firstValue("Location").orElse(null),
                bytes
            );
        }
    }
}
