package org.filteredpush.bdq_workbench.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;

/** Resolves local/remote resources and caches downloaded remote files for reuse. */
final class CachedResourceResolver {

    private final Path cacheDir;
    private final HttpClient httpClient;

    CachedResourceResolver() {
        this(defaultCacheDir(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    CachedResourceResolver(Path cacheDir, HttpClient httpClient) {
        this.cacheDir = cacheDir;
        this.httpClient = httpClient;
    }

    Path resolve(String source, String cacheFileName) {
        String trimmed = source == null ? "" : source.trim();
        if (trimmed.isEmpty()) {
            throw new AppException("Missing resource source");
        }
        if (!isHttpUrl(trimmed)) {
            Path local = Path.of(trimmed);
            if (Files.notExists(local)) {
                throw new AppException("Resource not found: " + local);
            }
            return local;
        }
        return resolveRemote(trimmed, cacheFileName);
    }

    private Path resolveRemote(String url, String cacheFileName) {
        try {
            Files.createDirectories(cacheDir);
            Path cached = cacheDir.resolve(cacheFileName);
            if (Files.exists(cached) && Files.size(cached) > 0L) {
                return cached;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AppException("Failed to download " + url + " (HTTP " + response.statusCode() + ")");
            }
            Path temp = Files.createTempFile(cacheDir, "bdq-", ".tmp");
            Files.write(temp, response.body());
            Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return cached;
        } catch (IOException e) {
            throw new AppException("Failed to cache resource from " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("Interrupted while downloading resource from " + url, e);
        }
    }

    private static Path defaultCacheDir() {
        String userHome = System.getProperty("user.home", ".");
        return Path.of(userHome, ".bdq-workbench", "cache");
    }

    private static boolean isHttpUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
