/** ReportingService.java
 *
 * Dispatches an execution summary to every configured ReportExporter and writes each one's output to a file under a reports directory.
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
package org.filteredpush.bdq_workbench.reporting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.filteredpush.bdq_workbench.app.AppException;
import org.filteredpush.bdq_workbench.model.ExecutionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles export dispatch and output locations for report formats.
 *
 * <p>Holds the configured list of {@link ReportExporter} implementations and, on each
 * {@link #export(ExecutionSummary)} call, writes one output file per exporter into a
 * {@code reports} directory (created if it does not already exist) beneath the current working
 * directory, named {@code bdq-report-<format>.<extension>} where {@code <format>} and
 * {@code <extension>} are the exporter's {@link ReportExporter#format()} and
 * {@link ReportExporter#fileExtension()}.
 */
public class ReportingService {

	private static final Logger LOG = LoggerFactory.getLogger(ReportingService.class);
	private static final ProgressListener NO_OP_PROGRESS_LISTENER = new ProgressListener() {
	};

	private final List<ReportExporter> exporters;
	private final ProgressListener progressListener;

	/**
	 * Creates a reporting service that dispatches to the given exporters, in order.
	 *
	 * @param exporters the report exporters to invoke on each {@link #export(ExecutionSummary)}
	 *     call; copied defensively
	 */
	public ReportingService(List<ReportExporter> exporters) {
		this(exporters, NO_OP_PROGRESS_LISTENER);
	}

	/**
	 * Creates a reporting service that dispatches to the given exporters, in order, while also
	 * reporting per-export progress.
	 *
	 * @param exporters the report exporters to invoke on each {@link #export(ExecutionSummary)}
	 *     call; copied defensively
	 * @param progressListener listener notified as export begins and as each exporter completes;
	 *     null is treated as a no-op listener
	 */
	public ReportingService(List<ReportExporter> exporters, ProgressListener progressListener) {
		this.exporters = List.copyOf(exporters);
		this.progressListener = progressListener == null ? NO_OP_PROGRESS_LISTENER : progressListener;
	}

	/**
	 * Renders the given execution summary with every configured {@link ReportExporter},
	 * writing each one's output to its own file under a {@code reports} directory (created if
	 * it does not already exist).
	 *
	 * @param summary the execution summary (responses and aggregated metadata) to export
	 * @throws AppException if the reports directory cannot be created, or a report file cannot
	 *     be written
	 */
	public void export(ExecutionSummary summary) {
		// TODO: Make the report directory configurable via CLI or GUI
		Path reportDir = Path.of("reports");
		try {
			Files.createDirectories(reportDir);
			progressListener.onExportStarted(exporters.size());
			int completed = 0;
			for (ReportExporter exporter : exporters) {
				Path outputFile = reportDir.resolve("bdq-report-" + exporter.format() + "." + exporter.fileExtension());
				try (OutputStream out = Files.newOutputStream(outputFile)) {
					exporter.export(summary, out);
				}
				LOG.info("Exported {} report to {}", exporter.format(), outputFile.toAbsolutePath());
				completed++;
				progressListener.onExporterCompleted(exporter.format(), completed, exporters.size());
			}
		} catch (IOException e) {
			throw new AppException("Unable to export reports", e);
		}
	}

	/**
	 * Listener for coarse report-export progress.
	 */
	public interface ProgressListener {
		/**
		 * Called when report export begins.
		 *
		 * @param totalExports the number of configured exporters to run
		 */
		default void onExportStarted(int totalExports) {
		}

		/**
		 * Called after one exporter has successfully completed.
		 *
		 * @param format the export format that completed
		 * @param completedExports how many exporters have completed so far
		 * @param totalExports the total number of configured exporters
		 */
		default void onExporterCompleted(String format, int completedExports, int totalExports) {
		}
	}
}
