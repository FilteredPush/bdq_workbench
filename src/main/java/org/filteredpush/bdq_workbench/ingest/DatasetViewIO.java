/** DatasetViewIO.java
 *
 * JSON load/save helpers for standalone DatasetView files.
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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.DatasetSchema;
import org.filteredpush.bdq_workbench.model.DatasetView;

/**
 * Loads and saves standalone dataset view JSON files.
 */
public class DatasetViewIO {
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Reads a dataset view JSON file.
	 *
	 * @param path view file path
	 * @return parsed dataset view
	 */
	public DatasetView load(Path path) {
		try {
			return mapper.readValue(path.toFile(), DatasetView.class);
		} catch (IOException e) {
			throw new AppException("Unable to read dataset view file " + path, e);
		}
	}

	/**
	 * Writes a dataset view JSON file.
	 *
	 * @param path view file path
	 * @param view view to persist
	 */
	public void save(Path path, DatasetView view) {
		try {
			mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), view);
		} catch (IOException e) {
			throw new AppException("Unable to save dataset view file " + path, e);
		}
	}

	/**
	 * Verifies a view is compatible with the current schema fingerprint.
	 *
	 * @param view loaded view
	 * @param schema current schema
	 */
	public void validateCompatibility(DatasetView view, DatasetSchema schema) {
		if (!view.schemaFingerprint().equals(schema.schemaFingerprint())) {
			throw new AppException("Dataset view fingerprint does not match dataset schema fingerprint: view="
					+ view.schemaFingerprint() + ", dataset=" + schema.schemaFingerprint());
		}
	}
}
