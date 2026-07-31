package org.filteredpush.bdq_workbench.test_discovery;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.TestDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default binding strategy preferring @Provides IDs, then explicit mappings. */
public class DefaultTestBindingService implements TestBindingService {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultTestBindingService.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    @Override
    public TestBindingResult bind(
            List<TestDefinition> tests,
            List<DiscoveredImplementation> discovered,
            Map<String, String> explicitMapping) {

    	LOG.debug("Binding {} tests to {} discovered implementations with {} explicit mappings",
				tests.size(), discovered.size(), explicitMapping.size());
    	
        Map<String, List<DiscoveredImplementation>> byProvided = discovered.stream()
                .filter(d -> d.providedTestId() != null && !d.providedTestId().isBlank())
                .collect(Collectors.groupingBy(d -> normalize(d.providedTestId())));
        Map<String, List<DiscoveredImplementation>> byVersion = discovered.stream()
                .filter(d -> d.providedVersion() != null && !d.providedVersion().isBlank())
                .collect(Collectors.groupingBy(d -> normalize(d.providedVersion())));
        Map<String, DiscoveredImplementation> byMethodKey = discovered.stream()
                .collect(Collectors.toMap(
                        d -> d.implementationClass() + "#" + d.implementationMethod(),
                        Function.identity(),
                        (a, b) -> a));

        List<ImplementationBinding> bindings = new ArrayList<>();
        List<TestDefinition> unresolved = new ArrayList<>();

        for (TestDefinition test : tests) {
            String normalizedTestId = normalize(test.id());
            List<DiscoveredImplementation> direct = byVersion.get(normalizedTestId);
            if (direct == null || direct.isEmpty()) {
                direct = byProvided.get(normalizedTestId);
            }
            boolean fallbackMatchedByProvidesOnly = false;
            if (direct == null || direct.isEmpty()) {
                String providedFallbackKey = toProvidesKey(normalizedTestId);
                if (providedFallbackKey != null) {
                    direct = byProvided.get(providedFallbackKey);
                    fallbackMatchedByProvidesOnly = direct != null && !direct.isEmpty();
                }
            }
            if (direct != null && !direct.isEmpty()) {
                if (fallbackMatchedByProvidesOnly) {
                    LOG.warn("Mapped test {} ({}) by @Provides fallback; no exact @ProvidesVersion match found",
                            test.id(), test.label());
                }
                direct.stream()
                        .map(d -> new ImplementationBinding(test.id(), d.implementationClass(), d.implementationMethod(), d.phase(), d.parameters()))
                        .forEach(bindings::add);
                continue;
            }
            String mappedMethod = explicitMapping.get(test.id());
            LOG.debug("Mapped test {} ({}) to explicit method {}", test.id(), test.label(), mappedMethod);
            if (mappedMethod != null && byMethodKey.containsKey(mappedMethod)) {
                DiscoveredImplementation d = byMethodKey.get(mappedMethod);
                LOG.debug("Mapped test {} ({}) to {}#{} with parameters {}",
						test.id(), test.label(), d.implementationClass(), d.implementationMethod(), d.parameters());
                Iterator<String> paramKeys = d.parameters().keySet().iterator();
                while (paramKeys.hasNext()) {
					String key = paramKeys.next();
					LOG.debug("Checking if test {} ({}) has parameter {} for mapped implementation {}#{}",
							test.id(), test.label(), key, d.implementationClass(), d.implementationMethod());
					if (!test.parameters().containsKey(key)) {
						LOG.warn("Mapped test {} ({}) to {}#{} but missing parameter {}",
								test.id(), test.label(), d.implementationClass(), d.implementationMethod(), key);
					}
				}
                bindings.add(new ImplementationBinding(test.id(), d.implementationClass(), d.implementationMethod(), d.phase(), d.parameters()));
            } else {
                unresolved.add(test);
            }
        }

        return new TestBindingResult(bindings, unresolved);
    }

    private static String normalize(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isBlank()) {
            return null;
        }
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String toProvidesKey(String testId) {
        if (testId == null || testId.isBlank()) {
            return null;
        }
        Matcher matcher = UUID_PATTERN.matcher(testId);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return testId;
    }
}
