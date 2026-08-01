package org.filteredpush.bdq_workbench.model;

import java.util.Map;

/** Binds a policy test to a discovered implementation method. */
public record ImplementationBinding(
        String testId,
        TestType testType,
        String implementationClass,
        String implementationMethod,
        Phase phase,
        Map<String, String> parameters,
        BindingStatus bindingStatus,
        ParameterizationCapability parameterizationCapability,
        String methodSelection,
        boolean usingDefaultParameters,
        java.util.List<BoundMethodParameter> parameterBindings,
        java.util.List<String> diagnostics) {
}
