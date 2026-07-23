package com.openjiuwen.gateway.bus.control;

import java.util.Optional;

public class FakeStreamRefResolver implements StreamRefResolver {
    private String endpoint = "http://rt:8000";
    public void setEndpoint(String e) { this.endpoint = e; }
    public void setFail() { this.endpoint = null; }
    @Override public Optional<String> resolve(String streamRef) { return Optional.ofNullable(endpoint); }
}
