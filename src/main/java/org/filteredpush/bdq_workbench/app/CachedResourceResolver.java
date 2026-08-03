/** CachedResourceResolver.java
 *
 * Resolves local file paths or remote HTTP(S) URLs to a local path, downloading and caching
 * remote resources on disk so repeated GUI/CLI runs avoid re-downloading them.
 *
 * Copyright 2026 President and Fellows of Harvard College
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves local/remote resources and caches downloaded remote files for reuse.
 *
 * <p>Used by the GUI (e.g. for use case files loaded from a URL) to accept either a local file
 * path or an {@code http://}/{@code https://} URL: local paths are validated and returned
 * as-is, while remote URLs are downloaded once into a per-user cache directory
 * ({@code ~/.bdq-workbench/cache}) and served from that cache on subsequent calls.
 */
final class CachedResourceResolver {
    private static final Logger LOG = LoggerFactory.getLogger(CachedResourceResolver.class);

    private final Path cacheDir;
    private final HttpClient httpClient;

    /**
     * Creates a resolver using the default per-user cache directory and a default
     * {@link HttpClient} (20s connect timeout, following redirects).
     */
    CachedResourceResolver() {
        this(defaultCacheDir(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /**
     * Creates a resolver with an explicit cache directory and HTTP client, primarily for
     * testing.
     *
     * @param cacheDir directory downloaded remote resources are cached under
     * @param httpClient client used to fetch remote resources
     */
    CachedResourceResolver(Path cacheDir, HttpClient httpClient) {
        this.cacheDir = cacheDir;
        this.httpClient = httpClient;
    }

    /**
     * Resolves a resource reference to a local path, downloading and caching it first if it is
     * a remote URL.
     *
     * @param source a local file path or an {@code http://}/{@code https://} URL
     * @param cacheFileName the file name to cache a downloaded remote resource under
     * @return the local path to the resource, ready to read
     * @throws AppException if {@code source} is blank, a local path that does not exist, or a
     *     remote URL that fails to download
     */
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
            LOG.debug("Using local resource: {}", local.toAbsolutePath());
            return local;
        }
        return resolveRemote(trimmed, cacheFileName);
    }

    /**
     * Downloads {@code url} into the cache directory (unless already cached) and returns the
     * cached path. Downloads are written to a temporary file first and atomically moved into
     * place to avoid serving a partially-written cache entry.
     *
     * @param url the remote resource URL
     * @param cacheFileName the file name to cache the download under
     * @return the local cached path
     * @throws AppException if the download fails, returns a non-2xx status, or is interrupted
     */
    private Path resolveRemote(String url, String cacheFileName) {
        try {
            Files.createDirectories(cacheDir);
            Path cached = cacheDir.resolve(cacheFileName);
            if (Files.exists(cached) && Files.size(cached) > 0L) {
                LOG.debug("Using cached remote resource: {} -> {}", url, cached.toAbsolutePath());
                return cached;
            }
            LOG.debug("Downloading remote resource: {}", url);
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
            LOG.debug("Cached remote resource: {} -> {}", url, cached.toAbsolutePath());
            return cached;
        } catch (IOException e) {
            throw new AppException("Failed to cache resource from " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("Interrupted while downloading resource from " + url, e);
        }
    }

    /**
     * @return the default per-user cache directory, {@code <user.home>/.bdq-workbench/cache}
     */
    private static Path defaultCacheDir() {
        String userHome = System.getProperty("user.home", ".");
        return Path.of(userHome, ".bdq-workbench", "cache");
    }

    /**
     * @param value the string to check
     * @return {@code true} if {@code value} starts with {@code http://} or {@code https://}
     *     (case-insensitive)
     */
    private static boolean isHttpUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
