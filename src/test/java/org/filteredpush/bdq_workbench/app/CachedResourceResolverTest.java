package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachedResourceResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesExistingLocalFile() throws Exception {
        Path local = tempDir.resolve("local.ttl");
        Files.writeString(local, "@prefix ex: <urn:test:> .", StandardCharsets.UTF_8);
        CachedResourceResolver resolver = new CachedResourceResolver(tempDir.resolve("cache"), HttpClient.newHttpClient());

        Path resolved = resolver.resolve(local.toString(), "unused.ttl");

        assertThat(resolved).isEqualTo(local);
    }

    /**
     * A second resolver revalidates the cached copy with If-Modified-Since, gets 304, and
     * serves the cached copy; repeated calls on one resolver do not ask again.
     */
    @Test
    void revalidatesCachedCopyOncePerResolver() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("@prefix ex: <urn:test:v1> .");
        List<String> conditionalHeaders = new CopyOnWriteArrayList<>();
        HttpServer server = versionedServer(body, Instant.parse("2026-01-01T00:00:00Z"), conditionalHeaders);
        try {
            String url = urlOf(server);
            Path cache = tempDir.resolve("cache");

            CachedResourceResolver first = new CachedResourceResolver(cache, HttpClient.newHttpClient());
            Path downloaded = first.resolve(url, "bdqtest.ttl");
            first.resolve(url, "bdqtest.ttl");
            Path revalidated = new CachedResourceResolver(cache, HttpClient.newHttpClient()).resolve(url, "bdqtest.ttl");

            assertThat(revalidated).isEqualTo(downloaded);
            assertThat(Files.readString(revalidated, StandardCharsets.UTF_8)).contains("urn:test:v1");
            assertThat(conditionalHeaders).containsExactly("", "Thu, 1 Jan 2026 00:00:00 GMT");
        } finally {
            server.stop(0);
        }
    }

    /** A remote resource republished after it was cached replaces the cached copy. */
    @Test
    void replacesCachedCopyWhenRemoteResourceChanged() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("@prefix ex: <urn:test:v1> .");
        AtomicReference<Instant> modified = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        HttpServer server = versionedServer(body, modified, new CopyOnWriteArrayList<>());
        try {
            String url = urlOf(server);
            Path cache = tempDir.resolve("cache");
            new CachedResourceResolver(cache, HttpClient.newHttpClient()).resolve(url, "bdqtest.ttl");

            body.set("@prefix ex: <urn:test:v2> .");
            modified.set(Instant.parse("2026-09-30T03:09:21Z"));
            Path refreshed = new CachedResourceResolver(cache, HttpClient.newHttpClient()).resolve(url, "bdqtest.ttl");

            assertThat(Files.readString(refreshed, StandardCharsets.UTF_8)).contains("urn:test:v2");
            assertThat(Files.getLastModifiedTime(refreshed).toInstant()).isEqualTo(modified.get());
        } finally {
            server.stop(0);
        }
    }

    /** An error response while revalidating keeps the cached copy rather than failing. */
    @Test
    void keepsCachedCopyWhenRevalidationFails() throws Exception {
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("bdqtest.ttl"), "@prefix ex: <urn:test:cached> .", StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bdqtest.ttl", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            Path resolved = new CachedResourceResolver(cache, HttpClient.newHttpClient())
                    .resolve(urlOf(server), "bdqtest.ttl");

            assertThat(Files.readString(resolved, StandardCharsets.UTF_8)).contains("urn:test:cached");
        } finally {
            server.stop(0);
        }
    }

    /** An unreachable server while revalidating keeps the cached copy, so runs work offline. */
    @Test
    void keepsCachedCopyWhenServerUnreachable() throws Exception {
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("bdqtest.ttl"), "@prefix ex: <urn:test:cached> .", StandardCharsets.UTF_8);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        String url = "http://127.0.0.1:" + closedPort + "/bdqtest.ttl";

        Path resolved = new CachedResourceResolver(cache, HttpClient.newHttpClient()).resolve(url, "bdqtest.ttl");

        assertThat(Files.readString(resolved, StandardCharsets.UTF_8)).contains("urn:test:cached");
    }

    @Test
    void failsForMissingLocalFile() {
        CachedResourceResolver resolver = new CachedResourceResolver(tempDir.resolve("cache"), HttpClient.newHttpClient());

        assertThatThrownBy(() -> resolver.resolve(tempDir.resolve("missing.ttl").toString(), "unused.ttl"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Resource not found");
    }

    @Test
    void reportsHttpStatusForMissingRemoteResource() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing.ttl", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            CachedResourceResolver resolver = new CachedResourceResolver(tempDir.resolve("cache"), HttpClient.newHttpClient());
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing.ttl";

            assertThatThrownBy(() -> resolver.resolve(url, "missing.ttl"))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("HTTP 404");
        } finally {
            server.stop(0);
        }
    }

    /**
     * Starts a server for {@code /bdqtest.ttl} that sends {@code Last-Modified} and honors
     * {@code If-Modified-Since}, recording each request's If-Modified-Since value ("" if none).
     */
    private static HttpServer versionedServer(
            AtomicReference<String> body, Instant modified, List<String> conditionalHeaders) throws Exception {
        return versionedServer(body, new AtomicReference<>(modified), conditionalHeaders);
    }

    /**
     * Starts a server for {@code /bdqtest.ttl} whose content and modification time can change,
     * sending {@code Last-Modified} and honoring {@code If-Modified-Since}.
     */
    private static HttpServer versionedServer(
            AtomicReference<String> body,
            AtomicReference<Instant> modified,
            List<String> conditionalHeaders) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bdqtest.ttl", exchange -> {
            String ifModifiedSince = exchange.getRequestHeaders().getFirst("If-Modified-Since");
            conditionalHeaders.add(ifModifiedSince == null ? "" : ifModifiedSince);
            Instant lastModified = modified.get();
            exchange.getResponseHeaders().add("Last-Modified",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(lastModified.atZone(ZoneOffset.UTC)));
            if (ifModifiedSince != null && !lastModified.isAfter(
                    ZonedDateTime.parse(ifModifiedSince, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    /** Returns the {@code /bdqtest.ttl} URL of a test server. */
    private static String urlOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/bdqtest.ttl";
    }
}
