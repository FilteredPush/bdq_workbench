/** CachedResourceResolver.java
 *
 * Resolves local file paths or remote HTTP(S) URLs to a local path, downloading and caching
 * remote resources on disk so repeated GUI/CLI runs avoid re-downloading them, while still
 * picking up a remote resource that has changed since it was cached.
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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves local/remote resources and caches downloaded remote files for reuse.
 *
 * <p>Used by the GUI (e.g. for use case files loaded from a URL) to accept either a local file
 * path or an {@code http://}/{@code https://} URL: local paths are validated and returned
 * as-is, while remote URLs are downloaded into a per-user cache directory
 * ({@code ~/.bdq-workbench/cache}).
 *
 * <p>A cached copy is not trusted forever: the first time a resolver is asked for a cached URL
 * it revalidates the copy with a conditional GET ({@code If-Modified-Since}, from the cached
 * file's modification time, which is set to the server's {@code Last-Modified} on download).
 * {@code 304 Not Modified} keeps the copy; a successful response replaces it, so a republished
 * use case or test definition file is picked up on the next run. If the server cannot be
 * reached or answers with an error, the cached copy is used and a warning logged, so runs keep
 * working offline. Later calls on the same resolver reuse the copy without asking again.
 */
final class CachedResourceResolver {
    private static final Logger LOG = LoggerFactory.getLogger(CachedResourceResolver.class);

    /** How long to wait for a remote resource with no cached copy to download before giving up. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /**
     * How long to wait when checking a cached copy for updates. Shorter than
     * {@link #REQUEST_TIMEOUT}, since the cached copy is a usable fallback.
     */
    private static final Duration REVALIDATION_TIMEOUT = Duration.ofSeconds(15);

    /** HTTP status a server returns when a conditional GET finds the resource unchanged. */
    private static final int HTTP_NOT_MODIFIED = 304;

    private final Path cacheDir;
    private final HttpClient httpClient;

    /** URLs whose cached copy this resolver has already revalidated or freshly downloaded. */
    private final Set<String> checkedUrls = ConcurrentHashMap.newKeySet();

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
     * Returns the cached copy of {@code url}, downloading it if there is no cached copy yet and
     * revalidating an existing copy the first time this resolver is asked for it.
     *
     * @param url the remote resource URL
     * @param cacheFileName the file name to cache the download under
     * @return the local cached path
     * @throws AppException if there is no cached copy and the download fails, returns a non-2xx
     *     status, or is interrupted
     */
    private Path resolveRemote(String url, String cacheFileName) {
        try {
            Files.createDirectories(cacheDir);
            Path cached = cacheDir.resolve(cacheFileName);
            if (!Files.exists(cached) || Files.size(cached) == 0L) {
                download(url, cached);
            } else if (checkedUrls.contains(url)) {
                LOG.debug("Using cached remote resource: {} -> {}", url, cached.toAbsolutePath());
            } else {
                revalidate(url, cached);
            }
            checkedUrls.add(url);
            return cached;
        } catch (IOException e) {
            throw new AppException("Failed to cache resource from " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException("Interrupted while downloading resource from " + url, e);
        }
    }

    /**
     * Downloads {@code url} into {@code cached}, with no cached copy to fall back on.
     *
     * @param url the remote resource URL
     * @param cached the cache file to write
     * @throws IOException if the request or the cache write fails
     * @throws InterruptedException if the request is interrupted
     * @throws AppException if the server returns a non-2xx status
     */
    private void download(String url, Path cached) throws IOException, InterruptedException {
        LOG.debug("Downloading remote resource: {}", url);
        HttpResponse<byte[]> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(url)).GET().timeout(REQUEST_TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (!isSuccess(response.statusCode())) {
            throw new AppException("Failed to download " + url + " (HTTP " + response.statusCode() + ")");
        }
        store(response, cached);
        LOG.debug("Cached remote resource: {} -> {}", url, cached.toAbsolutePath());
    }

    /**
     * Asks the server whether {@code url} changed since {@code cached} was written, replacing the
     * cached copy if it did. Failures to reach the server, and error responses, leave the cached
     * copy in place with a warning rather than failing the run.
     *
     * @param url the remote resource URL
     * @param cached the existing, non-empty cache file
     * @throws IOException if the cached file's modification time cannot be read or the updated
     *     copy cannot be written
     * @throws InterruptedException if the request is interrupted
     */
    private void revalidate(String url, Path cached) throws IOException, InterruptedException {
        String ifModifiedSince = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Files.getLastModifiedTime(cached).toInstant().atZone(ZoneOffset.UTC));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(REVALIDATION_TIMEOUT)
                .header("If-Modified-Since", ifModifiedSince)
                .build();
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            LOG.warn("Could not check {} for updates ({}); using cached copy {}", url, e.toString(), cached);
            return;
        }
        if (response.statusCode() == HTTP_NOT_MODIFIED) {
            LOG.debug("Cached remote resource is current: {} -> {}", url, cached.toAbsolutePath());
        } else if (isSuccess(response.statusCode())) {
            store(response, cached);
            LOG.info("Updated cached copy of changed remote resource: {} -> {}", url, cached.toAbsolutePath());
        } else {
            LOG.warn("Could not check {} for updates (HTTP {}); using cached copy {}",
                    url, response.statusCode(), cached);
        }
    }

    /**
     * Writes a response body to the cache file via a temporary file moved atomically into place,
     * so a partially-written cache entry is never served, then stamps the file with the server's
     * {@code Last-Modified} time (when given) for the next revalidation.
     *
     * @param response the successful response to store
     * @param cached the cache file to write
     * @throws IOException if the cache write fails
     */
    private void store(HttpResponse<byte[]> response, Path cached) throws IOException {
        Path temp = Files.createTempFile(cacheDir, "bdq-", ".tmp");
        Files.write(temp, response.body());
        Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Optional<FileTime> lastModified = lastModified(response);
        if (lastModified.isPresent()) {
            Files.setLastModifiedTime(cached, lastModified.get());
        }
    }

    /**
     * Reads a response's {@code Last-Modified} header.
     *
     * @param response the response to inspect
     * @return the header's time, or empty if it is absent or not a valid RFC 1123 date
     */
    private static Optional<FileTime> lastModified(HttpResponse<?> response) {
        return response.headers().firstValue("Last-Modified").flatMap(value -> {
            try {
                return Optional.of(FileTime.from(
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()));
            } catch (DateTimeParseException e) {
                LOG.debug("Ignoring unparseable Last-Modified header: {}", value);
                return Optional.empty();
            }
        });
    }

    /**
     * @param statusCode an HTTP status code
     * @return {@code true} for a 2xx status
     */
    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * @return the default per-user cache directory, {@code <user.home>/.bdq-workbench/cache}
     */
    private static Path defaultCacheDir() {
        String userHome = System.getProperty("user.home", ".");
        return Path.of(userHome, ".bdq-workbench", "cache");
    }


    /**
     * Resolves a resource source to a local file, deriving its cache file name automatically.
     *
     * @param source the resource URL or local path
     * @return the local path of the resource, downloading and caching it if it is remote
     */
    Path resolveSource(String source) {
        return resolve(source, cacheNameFor(source));
    }

    /**
     * Reports whether a resource source is remote and therefore needs fetching and caching.
     *
     * @param source the resource URL or local path
     * @return {@code true} if {@code source} is an HTTP or HTTPS URL
     */
    static boolean isRemote(String source) {
        return source != null && isHttpUrl(source.trim());
    }

    static String cacheNameFor(String source) {
        String baseName = "resource";
        try {
            String uriPath = java.net.URI.create(source).getPath();
            if (uriPath != null && !uriPath.isBlank()) {
                baseName = Path.of(uriPath).getFileName().toString();
            }
        } catch (Exception ignored) {
            // source is not a URI, treat as local path
        }
        if ("resource".equals(baseName) && source != null && !source.isBlank()) {
            try {
                baseName = Path.of(source).getFileName().toString();
            } catch (Exception ignored) {
                // keep fallback
            }
        }
        if (baseName.contains(".")) {
            baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        }
        baseName = baseName.toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("(^-+|-+$)", "");
        if (baseName.isBlank()) {
            baseName = "resource";
        }
        int hash = Math.abs(source.hashCode());
        String extension = ".rdf";
        int dot = source.lastIndexOf('.');
        if (dot >= 0 && dot < source.length() - 1) {
            String candidate = source.substring(dot).toLowerCase();
            if (candidate.matches("\\.[a-z0-9]{1,8}")) {
                extension = candidate;
            }
        }
        return baseName + "-cached-" + hash + extension;
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
