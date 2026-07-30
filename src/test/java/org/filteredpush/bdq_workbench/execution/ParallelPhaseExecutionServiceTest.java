package org.filteredpush.bdq_workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.filteredpush.bdq_workbench.model.CanonicalRecord;
import org.filteredpush.bdq_workbench.model.ImplementationBinding;
import org.filteredpush.bdq_workbench.model.Phase;
import org.filteredpush.bdq_workbench.model.RecordDataset;
import org.filteredpush.bdq_workbench.test_discovery.DiscoveredImplementation;
import org.junit.jupiter.api.Test;

class ParallelPhaseExecutionServiceTest {

    @Test
    void orchestratesPhasesAndKeepsDeterministicOrder() throws Exception {
        ParallelPhaseExecutionService service = new ParallelPhaseExecutionService(2, new ReflectionExecutionAdapter());
        Method pre = Impl.class.getMethod("pre", Map.class);
        Method amend = Impl.class.getMethod("amend", Map.class);
        Method post = Impl.class.getMethod("post", Map.class);

        List<DiscoveredImplementation> discovered = List.of(
                new DiscoveredImplementation("t1", null, Phase.PRE_AMENDMENT, Impl.class.getName(), "pre", Map.of(), new Impl(), pre),
                new DiscoveredImplementation("t2", null, Phase.AMENDMENT, Impl.class.getName(), "amend", Map.of(), new Impl(), amend),
                new DiscoveredImplementation("t3", null, Phase.POST_AMENDMENT, Impl.class.getName(), "post", Map.of(), new Impl(), post));

        List<ImplementationBinding> bindings = List.of(
                new ImplementationBinding("t1", Impl.class.getName(), "pre", Phase.PRE_AMENDMENT, Map.of()),
                new ImplementationBinding("t2", Impl.class.getName(), "amend", Phase.AMENDMENT, Map.of()),
                new ImplementationBinding("t3", Impl.class.getName(), "post", Phase.POST_AMENDMENT, Map.of()));

        RecordDataset dataset = new RecordDataset(List.of(new CanonicalRecord("r1", Map.of("k", "v"))));

        var responses = service.execute(dataset, bindings, discovered);

        assertThat(responses).hasSize(3);
        assertThat(responses).extracting("phase").containsExactly(Phase.PRE_AMENDMENT, Phase.AMENDMENT, Phase.POST_AMENDMENT);
        assertThat(dataset.records().get(0).terms()).containsEntry("k", "v");
    }

    static class Impl {
        public boolean pre(Map<String, String> record) {
            return record.containsKey("k");
        }

        public boolean amend(Map<String, String> record) {
            record.put("k", "changed");
            return true;
        }

        public boolean post(Map<String, String> record) {
            return "changed".equals(record.get("k"));
        }
    }
}
