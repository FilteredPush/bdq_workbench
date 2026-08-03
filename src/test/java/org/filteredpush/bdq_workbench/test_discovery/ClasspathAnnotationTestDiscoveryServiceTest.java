package org.filteredpush.bdq_workbench.test_discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import org.datakurator.ffdq.annotations.ActedUpon;
import org.datakurator.ffdq.annotations.Provides;
import org.datakurator.ffdq.annotations.Validation;
import org.junit.jupiter.api.Test;

class ClasspathAnnotationTestDiscoveryServiceTest {

    @Test
    void fallsBackToDefaultClasspathWhenContextClassLoaderCannotSeeImplementations() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader isolated = new URLClassLoader(new URL[0], null)) {
            Thread.currentThread().setContextClassLoader(isolated);

            ClasspathAnnotationTestDiscoveryService service =
                    new ClasspathAnnotationTestDiscoveryService(List.of("org.filteredpush.bdq_workbench.test_discovery"));

            List<DiscoveredImplementation> discovered = service.discover();

            assertThat(discovered)
                    .filteredOn(implementation -> implementation.implementationClass().contains("DiscoverableImplementation"))
                    .singleElement()
                    .satisfies(implementation -> {
                        assertThat(implementation.providedTestId()).isEqualTo("urn:test:discoverable");
                        assertThat(implementation.implementationMethod()).isEqualTo("validate");
                    });
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    static class DiscoverableImplementation {
        @Validation(label = "DISCOVERABLE", description = "discoverable")
        @Provides("urn:test:discoverable")
        public static boolean validate(@ActedUpon("dwc:eventDate") String eventDate) {
            return eventDate != null;
        }
    }
}
