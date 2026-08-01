package org.filteredpush.bdq_workbench.model;

/** Binding decision for a specific reflected method parameter. */
public record BoundMethodParameter(
        MethodParameter parameter,
        String resolvedSource,
        String suppliedValue,
        boolean bound,
        String reason) {
}
