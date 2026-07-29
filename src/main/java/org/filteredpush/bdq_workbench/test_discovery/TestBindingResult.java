package org.filteredpush.bdq_workbench.test_discovery;

import java.util.List;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.TestDefinition;

/** Binding output with unresolved policy tests retained as first-class outcomes. */
public record TestBindingResult(List<ImplementationBinding> bindings, List<TestDefinition> unresolved) {
}
