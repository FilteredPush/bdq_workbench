package org.filteredpush.bdq_workbench.model;

import java.util.List;
import java.util.Map;

/** User-facing binding review row for preflight diagnostics. */
public record BindingReview(
        TestDefinition test,
        ImplementationStatus implementationStatus,
        BindingStatus bindingStatus,
        ParameterizationCapability parameterizationCapability,
        String chosenImplementationMethod,
        Map<String, String> parameterValues,
        boolean usingDefaultParameters,
        List<String> diagnostics) {
}
