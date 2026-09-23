/** BindingStatus.java
 *
 * Enumerates how completely a policy test was bound to a candidate implementation method.
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
package org.filteredpush.bdq_workbench.model;

/** Binding completeness for a candidate implementation method. */
public enum BindingStatus {
    /** All required parameters were resolved and the method is fully bound. */
    BOUND,
    /** Some but not all required parameters were resolved. */
    PARTIAL,
    /** Binding failed because a required Darwin Core term was not present in the dataset. */
    TERM_MISSING,
    /** No candidate implementation method could be bound. */
    UNBOUND;

    /**
     * Reports whether a binding in this state is safe to execute.
     *
     * <p>{@link #BOUND} bindings are fully executable. {@link #PARTIAL} remains executable for
     * cases where only optional/defaultable inputs were omitted. {@link #TERM_MISSING} and
     * {@link #UNBOUND} are retained for diagnostics and unresolved reporting only and must not be
     * invoked reflectively.
     *
     * @return {@code true} for executable bindings, {@code false} otherwise
     */
    public boolean isRunnable() {
        return this == BOUND || this == PARTIAL;
    }
}
