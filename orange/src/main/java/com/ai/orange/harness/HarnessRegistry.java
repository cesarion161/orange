package com.ai.orange.harness;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Looks up a harness adapter by the id a routing tier names. */
@Component
public class HarnessRegistry {

    private final Map<String, HarnessAdapter> byId;

    public HarnessRegistry(List<HarnessAdapter> adapters) {
        this.byId = adapters.stream().collect(Collectors.toMap(HarnessAdapter::id, Function.identity()));
    }

    public HarnessAdapter get(String id) {
        HarnessAdapter adapter = byId.get(id);
        if (adapter == null) {
            throw new IllegalArgumentException("no harness adapter '" + id + "' (known: " + byId.keySet() + ")");
        }
        return adapter;
    }
}
