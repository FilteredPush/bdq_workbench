package org.filteredpush.bdq_workbench.model;

/** Reflected method parameter metadata used for execution binding. */
public record MethodParameter(
        int index,
        String name,
        ParameterRole role,
        String source,
        String typeName,
        boolean required) {
}
