package com.ai.orange.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** The shared JSON mapper for orange's own payloads (plans, task metadata, event payloads). */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private Json() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
