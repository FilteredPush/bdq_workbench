package org.filteredpush.bdq_workbench.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void downloadsAndCachesRemoteResource() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bdqtest.ttl", exchange -> {
            requests.incrementAndGet();
            byte[] body = "@prefix ex: <urn:test:> .".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/bdqtest.ttl";
            Path cache = tempDir.resolve("cache");
            CachedResourceResolver resolver = new CachedResourceResolver(cache, HttpClient.newHttpClient());

            Path first = resolver.resolve(url, "bdqtest.ttl");
            Path second = resolver.resolve(url, "bdqtest.ttl");

            assertThat(first).isEqualTo(second);
            assertThat(Files.readString(first, StandardCharsets.UTF_8)).contains("@prefix ex:");
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
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
}
