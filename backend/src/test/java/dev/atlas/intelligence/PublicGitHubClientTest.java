package dev.atlas.intelligence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

class PublicGitHubClientTest {

    private static final URI URI_EXAMPLE = URI.create(
        "https://api.github.com/repos/example/project/commits?per_page=1"
    );

    private HttpClient client() {
        var client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(client.authenticator()).thenReturn(Optional.empty());
        when(client.cookieHandler()).thenReturn(Optional.empty());
        return client;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> response(
        HttpClient client,
        int status,
        InputStream body,
        String location
    ) throws Exception {
        var response = (HttpResponse<InputStream>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(
            HttpHeaders.of(
                location == null
                    ? Map.of()
                    : Map.of("location", List.of(location)),
                (name, value) -> true
            )
        );
        when(
            client.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
            )
        ).thenReturn(response);
        return response;
    }

    @Test
    void anonymousRequestHasDeadlineAndDoesNotFollowOrAttachCredentials()
        throws Exception {
        var client = client();
        var closed = new AtomicBoolean();
        var input = new ByteArrayInputStream(
            "[]".getBytes(StandardCharsets.UTF_8)
        ) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        response(
            client,
            302,
            input,
            "https://codeload.github.com/example/project/legacy.zip/commit"
        );
        var result = new PublicGitHubClient(client).fetch(URI_EXAMPLE, 100);
        assertThat(result.status()).isEqualTo(302);
        assertThat(result.location()).startsWith(
            "https://codeload.github.com/"
        );
        assertThat(result.body()).isEqualTo(
            "[]".getBytes(StandardCharsets.UTF_8)
        );
        assertThat(closed).isTrue();
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(1)).send(request.capture(), any());
        assertThat(request.getValue().uri()).isEqualTo(URI_EXAMPLE);
        assertThat(request.getValue().method()).isEqualTo("GET");
        assertThat(request.getValue().timeout()).contains(
            Duration.ofSeconds(10)
        );
        assertThat(
            request.getValue().headers().firstValue("Authorization")
        ).isEmpty();
        assertThat(request.getValue().headers().firstValue("Cookie")).isEmpty();
        assertThat(
            request.getValue().headers().firstValue("User-Agent")
        ).contains("ATLAS-public-repository-import");
        assertThat(request.getValue().bodyPublisher()).isEmpty();
    }

    @Test
    void rejectsCredentialOrRedirectEnabledClientsBeforeAnySend() {
        for (String setting : List.of("redirect", "auth", "cookie")) {
            var client = client();
            if (setting.equals("redirect")) when(
                client.followRedirects()
            ).thenReturn(HttpClient.Redirect.ALWAYS);
            if (setting.equals("auth")) when(client.authenticator()).thenReturn(
                Optional.of(new Authenticator() {})
            );
            if (setting.equals("cookie")) when(
                client.cookieHandler()
            ).thenReturn(Optional.of(new CookieManager()));
            assertThatThrownBy(() ->
                new PublicGitHubClient(client)
            ).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void responseLimitsApplyToFailureBodiesAndCloseTheStream()
        throws Exception {
        var client = client();
        var closed = new AtomicBoolean();
        var input = new ByteArrayInputStream(new byte[101]) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        response(client, 500, input, null);
        assertThatThrownBy(() ->
            new PublicGitHubClient(client).fetch(URI_EXAMPLE, 100)
        )
            .isInstanceOf(IOException.class)
            .hasMessageContaining("limit");
        assertThat(closed).isTrue();
        assertThatThrownBy(() ->
            new PublicGitHubClient(client).fetch(URI_EXAMPLE, 0)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new PublicGitHubClient(client).fetch(
                URI_EXAMPLE,
                16 * 1024 * 1024 + 1
            )
        ).isInstanceOf(IllegalArgumentException.class);
        verify(client, times(1)).send(any(), any());
    }

    @Test
    void exactBodyLimitAndEmptyErrorResponseAreReturnedWithoutInventingSuccess()
        throws Exception {
        var client = client();
        response(client, 404, new ByteArrayInputStream(new byte[100]), null);
        var result = new PublicGitHubClient(client).fetch(URI_EXAMPLE, 100);
        assertThat(result.status()).isEqualTo(404);
        assertThat(result.body()).hasSize(100);
        assertThat(result.location()).isNull();
    }

    @Test
    @Timeout(15)
    void stalledResponseBodyHasIndependentDeadlineAndIsClosed()
        throws Exception {
        var client = client();
        var released = new CountDownLatch(1);
        var closed = new AtomicBoolean();
        var input = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    released.await(13, TimeUnit.SECONDS);
                    return -1;
                } catch (InterruptedException error) {
                    throw new IOException("interrupted", error);
                }
            }

            @Override
            public void close() {
                closed.set(true);
                released.countDown();
            }
        };
        response(client, 200, input, null);
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() ->
                new PublicGitHubClient(client).fetch(URI_EXAMPLE, 100)
            )
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unavailable");
            assertThat(
                Duration.ofNanos(System.nanoTime() - started)
            ).isLessThan(Duration.ofSeconds(13));
            assertThat(closed).isTrue();
        } finally {
            released.countDown();
        }
    }
}
