/** DataPackageArchiveSupport.java
 *
 * Utilities for detecting and opening Data Package manifests stored in zip archives.
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
package org.filteredpush.bdq_workbench.ingest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Utilities for Data Package manifests inside zip inputs.
 */
final class DataPackageArchiveSupport {

	/** Utility class; not instantiable. */
	private DataPackageArchiveSupport() {
	}

	/**
	 * Reports whether a zip input contains a {@code datapackage.json} manifest.
	 *
	 * @param inputPath candidate dataset path
	 * @return {@code true} when the path is a zip containing a data package manifest
	 */
	static boolean isDataPackageArchive(Path inputPath) {
		String fileName = inputPath.getFileName().toString().toLowerCase();
		if (!fileName.endsWith(".zip")) {
			return false;
		}
		try (ZipFile zipFile = new ZipFile(inputPath.toFile())) {
			return findManifestEntryName(zipFile).isPresent();
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Resolves the manifest path for either an unpacked data package or a zip-contained one.
	 *
	 * <p>For zip inputs this keeps the zip filesystem open while {@code reader} executes, so the
	 * caller may also read package data files referenced from the manifest.
	 *
	 * @param inputPath path to either {@code datapackage.json} or a zip containing it
	 * @param reader callback that consumes the resolved manifest path
	 * @param <T> result type produced by the callback
	 * @return callback result
	 * @throws IOException if the manifest cannot be resolved or read
	 */
	static <T> T withManifestPath(Path inputPath, ManifestPathReader<T> reader) throws IOException {
		if (!inputPath.getFileName().toString().toLowerCase().endsWith(".zip")) {
			return reader.read(inputPath);
		}
		URI archiveUri = URI.create("jar:" + inputPath.toUri());
		try (FileSystem zipFs = FileSystems.newFileSystem(archiveUri, Map.of())) {
			Path manifestPath = findManifestPath(zipFs)
					.orElseThrow(() -> new IOException("Zip input contains no datapackage.json manifest: " + inputPath));
			return reader.read(manifestPath);
		}
	}

	/**
	 * Reads one value from a resolved manifest path.
	 *
	 * @param <T> callback result type
	 */
	@FunctionalInterface
	interface ManifestPathReader<T> {
		/**
		 * Reads one value from the provided manifest path.
		 *
		 * @param manifestPath resolved path to the data package manifest
		 * @return callback result
		 * @throws IOException if reading fails
		 */
		T read(Path manifestPath) throws IOException;
	}

	/**
	 * Finds a manifest entry path within a zip archive.
	 *
	 * @param zipFile zip file to inspect
	 * @return matching manifest entry name when present
	 */
	private static Optional<String> findManifestEntryName(ZipFile zipFile) {
		return zipFile.stream()
				.filter(entry -> !entry.isDirectory())
				.map(ZipEntry::getName)
				.filter(DataPackageArchiveSupport::isDataPackageManifestName)
				.min(Comparator.comparingInt(String::length).thenComparing(String::compareTo));
	}

	/**
	 * Finds a manifest path within an opened zip filesystem.
	 *
	 * @param zipFs opened zip filesystem
	 * @return manifest path when present
	 * @throws IOException if directory traversal fails
	 */
	private static Optional<Path> findManifestPath(FileSystem zipFs) throws IOException {
		try (Stream<Path> paths = Files.walk(zipFs.getPath("/"))) {
			return paths
					.filter(Files::isRegularFile)
					.filter(path -> isDataPackageManifestName(path.toString().replace('\\', '/')))
					.min(Comparator
							.comparingInt((Path path) -> path.toString().length())
							.thenComparing(path -> path.toString()));
		}
	}

	/**
	 * Reports whether a zip entry/path names {@code datapackage.json}.
	 *
	 * @param entryName candidate path or entry name
	 * @return {@code true} when the name resolves to a data package manifest
	 */
	private static boolean isDataPackageManifestName(String entryName) {
		String normalized = entryName.toLowerCase();
		return normalized.equals("datapackage.json")
				|| normalized.endsWith("/datapackage.json")
				|| normalized.endsWith("\\datapackage.json");
	}
}
