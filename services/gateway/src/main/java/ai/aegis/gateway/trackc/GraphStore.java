package ai.aegis.gateway.trackc;

/**
 * Track C port: a graph store for causal / dependency graphs (e.g. Neo4j). Dormant by default.
 *
 * The core derives a lightweight dependency graph from incident co-occurrence in Postgres.
 * Track C adds a real graph database for richer causal analysis (PCMCI / path queries).
 * Activate on a capable host by starting the graph overlay and setting
 * SPRING_PROFILES_ACTIVE=...,neo4j — see LAPTOP_SETUP.md. Until then the
 * {@link DormantGraphStore} is wired and reports inactive.
 */
public interface GraphStore {

    String name();

    boolean isActive();

    String status();
}
