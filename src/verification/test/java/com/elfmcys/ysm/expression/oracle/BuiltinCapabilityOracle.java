package com.elfmcys.ysm.expression.oracle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Independent expectations for built-in registration and call boundaries.
 *
 * <p>
 * Keep these inputs independent of production declarations, manifests and linkage output so an implementation cannot
 * supply its own expected answer. Typed signatures and values belong to {@link BuiltinStaticOracle}.
 */
public final class BuiltinCapabilityOracle {
    public static final String RESOURCE = "/com/elfmcys/ysm/expression/oracle/builtin-capability-oracle.json";

    public record Capability(String sourceSpelling, String stableId, String access, int minimum, int maximum,
        String optionalCapability, boolean staged) {
        public boolean optional() {
            return optionalCapability != null && !optionalCapability.isEmpty();
        }

        public String label() {
            return sourceSpelling + '/' + access;
        }
    }

    public record Family(String family, String modId, List<String> expectedKeys) {
    }

    private final List<Capability> capabilities;
    private final List<Family> families;

    private BuiltinCapabilityOracle(List<Capability> capabilities, List<Family> families) {
        this.capabilities = List.copyOf(capabilities);
        this.families = List.copyOf(families);
    }

    public static BuiltinCapabilityOracle load() {
        try (InputStream stream = BuiltinCapabilityOracle.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("oracle resource is missing: " + RESOURCE);
            }
            var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            return parse(root);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot read oracle resource " + RESOURCE, failure);
        }
    }

    public List<Capability> capabilities() {
        return capabilities;
    }

    public List<Family> families() {
        return families;
    }

    public List<Capability> invokeCapabilities() {
        return capabilities.stream().filter(value -> "INVOKE".equals(value.access())).toList();
    }

    public List<Capability> readCapabilities() {
        return capabilities.stream().filter(value -> "READ".equals(value.access())).toList();
    }

    public List<Capability> optionalCapabilities() {
        return capabilities.stream().filter(Capability::optional).toList();
    }

    public Capability require(String sourceSpelling) {
        return capabilities.stream().filter(value -> value.sourceSpelling().equals(sourceSpelling)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("capability is not in the oracle: " + sourceSpelling));
    }

    private static BuiltinCapabilityOracle parse(JsonObject root) {
        var capabilities = new ArrayList<Capability>();
        for (var element : root.getAsJsonArray("capabilities")) {
            var object = element.getAsJsonObject();
            var optional = object.get("optional_capability");
            capabilities.add(new Capability(object.get("source_spelling").getAsString(),
                object.get("stable_id").getAsString(), object.get("access").getAsString(),
                object.get("minimum").getAsInt(), object.get("maximum").getAsInt(),
                optional.isJsonNull() ? null : optional.getAsString(), object.get("staged").getAsBoolean()));
        }
        var families = new ArrayList<Family>();
        for (var element : root.getAsJsonArray("families")) {
            var object = element.getAsJsonObject();
            families.add(new Family(object.get("family").getAsString(), object.get("mod_id").getAsString(),
                strings(object.getAsJsonArray("expected_keys"))));
        }
        return new BuiltinCapabilityOracle(capabilities, families);
    }

    private static List<String> strings(JsonArray array) {
        var result = new ArrayList<String>(array.size());
        for (var element : array) {
            result.add(element.getAsString());
        }
        return List.copyOf(result);
    }
}
