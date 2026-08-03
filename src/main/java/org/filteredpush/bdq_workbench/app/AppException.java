/** AppException.java
 *
 * Base unchecked exception for application-level failures in the workbench (invalid
 * configuration, missing resources, and other startup or execution problems).
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

/**
 * Base runtime exception for the workbench application.
 *
 * <p>Thrown by {@link ConfigLoader}, {@link CachedResourceResolver}, and
 * {@link BdqWorkbenchApplication} (among others) to signal configuration and startup problems
 * that should be reported to the user rather than treated as unexpected failures.
 */
public class AppException extends RuntimeException {

    /**
     * Creates an exception with the given message and no cause.
     *
     * @param message human-readable description of the failure
     */
    public AppException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given message and underlying cause.
     *
     * @param message human-readable description of the failure
     * @param cause the underlying exception that triggered this failure
     */
    public AppException(String message, Throwable cause) {
        super(message, cause);
    }
}
