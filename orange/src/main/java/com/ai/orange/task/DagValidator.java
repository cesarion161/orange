package com.ai.orange.task;

import com.ai.orange.task.exception.DagCycleException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Validates that a proposed task graph is acyclic. Operates on opaque keys so
 * it can run before tasks have been assigned UUIDs in the DB.
 */
@Component
public class DagValidator {

    public <K> void validate(Collection<K> nodes, Collection<? extends DagEdge<K>> edges) {
        Map<K, List<K>> adj = new HashMap<>(nodes.size());
        for (K node : nodes) adj.put(node, new ArrayList<>());

        for (DagEdge<K> e : edges) {
            if (!adj.containsKey(e.from())) {
                throw new IllegalArgumentException("edge references unknown node: " + e.from());
            }
            if (!adj.containsKey(e.to())) {
                throw new IllegalArgumentException("edge references unknown node: " + e.to());
            }
            adj.get(e.from()).add(e.to());
        }

        // Iterative DFS with three colours: WHITE (unseen), GREY (on stack), BLACK (done).
        Map<K, Color> color = new HashMap<>(nodes.size());
        for (K n : nodes) color.put(n, Color.WHITE);

        for (K start : nodes) {
            if (color.get(start) == Color.WHITE) {
                detectCycle(start, adj, color);
            }
        }
    }

    private <K> void detectCycle(K start, Map<K, List<K>> adj, Map<K, Color> color) {
        Deque<Frame<K>> stack = new ArrayDeque<>();
        Deque<K> path = new ArrayDeque<>();
        stack.push(new Frame<>(start, adj.get(start).iterator()));
        path.push(start);
        color.put(start, Color.GREY);

        while (!stack.isEmpty()) {
            Frame<K> top = stack.peek();
            if (top.children.hasNext()) {
                K next = top.children.next();
                Color c = color.get(next);
                if (c == Color.GREY) {
                    throw new DagCycleException(buildCyclePath(path, next));
                }
                if (c == Color.WHITE) {
                    color.put(next, Color.GREY);
                    stack.push(new Frame<>(next, adj.get(next).iterator()));
                    path.push(next);
                }
            } else {
                color.put(top.node, Color.BLACK);
                stack.pop();
                path.pop();
            }
        }
    }

    private <K> List<K> buildCyclePath(Deque<K> path, K backEdgeTarget) {
        List<K> reversed = new ArrayList<>(path);
        Collections.reverse(reversed);
        List<K> cycle = new ArrayList<>();
        boolean inCycle = false;
        for (K p : reversed) {
            if (p.equals(backEdgeTarget)) inCycle = true;
            if (inCycle) cycle.add(p);
        }
        cycle.add(backEdgeTarget);
        return cycle;
    }

    private enum Color { WHITE, GREY, BLACK }

    private record Frame<K>(K node, Iterator<K> children) {}
}
