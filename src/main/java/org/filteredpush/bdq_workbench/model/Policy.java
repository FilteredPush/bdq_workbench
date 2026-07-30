package org.filteredpush.bdq_workbench.model;

import java.util.List;

/** Policy containing linked test identifiers. */
public record Policy(String id, List<String> testIds) {
}
