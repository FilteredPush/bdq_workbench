package org.filteredpush.bdq_workbench.model;

import java.util.HashMap;
import java.util.Map;

/** Canonical record representation for ingestion and execution. */
public final class CanonicalRecord {
    private final String id;
    private final Map<String, String> terms;

    public CanonicalRecord(String id, Map<String, String> terms) {
        this.id = id;
        this.terms = new HashMap<>(terms);
    }

    public String id() {
        return id;
    }

    public Map<String, String> terms() {
        return terms;
    }

    public CanonicalRecord copy() {
        return new CanonicalRecord(id, terms);
    }
}
