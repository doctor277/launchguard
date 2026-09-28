package io.github.doctor277.launchguard.events;

import tools.jackson.databind.json.JsonMapper;

public class EventJson {
    private final JsonMapper mapper = JsonMapper.builder().build();

    public String write(Object event) { return mapper.writeValueAsString(event); }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Malformed or unsupported monitoring event", exception);
        }
    }
}
