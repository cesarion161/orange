package com.ai.orange.agent.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Single shared {@link ObjectMapper} configured for the wire protocol.
 * Reads {@link Event} (stdout) and writes {@link Command} (stdin).
 */
public final class ProtocolCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private ProtocolCodec() {}

    public static Event readEvent(String ndjsonLine) throws JsonProcessingException {
        return MAPPER.readValue(ndjsonLine, Event.class);
    }

    public static String writeCommand(Command command) throws JsonProcessingException {
        return MAPPER.writeValueAsString(command);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
