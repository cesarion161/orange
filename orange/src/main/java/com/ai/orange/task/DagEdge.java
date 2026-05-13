package com.ai.orange.task;

/** Generic directed edge consumed by {@link DagValidator}. */
public interface DagEdge<K> {
    K from();
    K to();
}
