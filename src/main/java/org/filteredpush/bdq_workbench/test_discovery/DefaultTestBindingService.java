package org.filteredpush.bdq_workbench.test_discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.TestDefinition;

/** Default binding strategy preferring @Provides IDs, then explicit mappings. */
public class DefaultTestBindingService implements TestBindingService {

    @Override
    public TestBindingResult bind(
            List<TestDefinition> tests,
            List<DiscoveredImplementation> discovered,
            Map<String, String> explicitMapping) {

        Map<String, List<DiscoveredImplementation>> byProvided = discovered.stream()
                .collect(Collectors.groupingBy(DiscoveredImplementation::providedTestId));
        Map<String, DiscoveredImplementation> byMethodKey = discovered.stream()
                .collect(Collectors.toMap(
                        d -> d.implementationClass() + "#" + d.implementationMethod(),
                        Function.identity(),
                        (a, b) -> a));

        List<ImplementationBinding> bindings = new ArrayList<>();
        List<TestDefinition> unresolved = new ArrayList<>();

        for (TestDefinition test : tests) {
            List<DiscoveredImplementation> direct = byProvided.get(test.id());
            if (direct != null && !direct.isEmpty()) {
                direct.stream()
                        .map(d -> new ImplementationBinding(test.id(), d.implementationClass(), d.implementationMethod(), d.phase(), d.parameters()))
                        .forEach(bindings::add);
                continue;
            }
            String mappedMethod = explicitMapping.get(test.id());
            if (mappedMethod != null && byMethodKey.containsKey(mappedMethod)) {
                DiscoveredImplementation d = byMethodKey.get(mappedMethod);
                bindings.add(new ImplementationBinding(test.id(), d.implementationClass(), d.implementationMethod(), d.phase(), d.parameters()));
            } else {
                unresolved.add(test);
            }
        }

        return new TestBindingResult(bindings, unresolved);
    }
}
