package org.filteredpush.bdq_workbench.test_discovery;

import java.lang.reflect.Method;
import java.util.List;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.MethodParameter;
import org.filteredpush.bdq_workbench.model.TestType;

/** Discovered implementation metadata with invocation handle. */
public record DiscoveredImplementation(
        String providedTestId,
        String providedVersion,
        TestType testType,
        Phase phase,
        String implementationClass,
        String implementationMethod,
        String specification,
        List<MethodParameter> parameters,
        Object target,
        Method method) {

    public boolean isParameterized() {
        return parameters.stream().anyMatch(parameter -> parameter.role() == org.filteredpush.bdq_workbench.model.ParameterRole.PARAMETER);
    }
}
