package com.elfmcys.ysm.mock.host;

import java.util.Map;
import java.util.Objects;

record HostAction(String id, String name, Map<String, String> arguments) {
    HostAction {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Action id is not a safe path segment");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Action name must not be blank");
        }
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }

    String require(String name) {
        var value = arguments.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing action argument: " + name);
        }
        return value;
    }
}
