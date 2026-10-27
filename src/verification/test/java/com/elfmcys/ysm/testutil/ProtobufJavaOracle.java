package com.elfmcys.ysm.testutil;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProtobufJavaOracle {
    private static final String DESCRIPTOR_RESOURCE = "proto/ysm-main.pb";
    private static final Map<String, Descriptors.Descriptor> MESSAGES = loadMessages();

    private ProtobufJavaOracle() {}

    private static Descriptors.Descriptor descriptor(String fullName) {
        var descriptor = MESSAGES.get(fullName);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown protobuf message: " + fullName);
        }
        return descriptor;
    }

    public static DynamicMessage parse(String fullName, byte[] bytes) throws IOException {
        return DynamicMessage.parseFrom(descriptor(fullName), bytes);
    }

    public static Descriptors.FieldDescriptor field(Descriptors.Descriptor descriptor, String name) {
        var field = descriptor.findFieldByName(name);
        if (field == null) {
            throw new IllegalArgumentException("Unknown field " + descriptor.getFullName() + "." + name);
        }
        return field;
    }

    private static Map<String, Descriptors.Descriptor> loadMessages() {
        try (InputStream input = ProtobufJavaOracle.class.getClassLoader().getResourceAsStream(DESCRIPTOR_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing test descriptor resource: " + DESCRIPTOR_RESOURCE);
            }
            var set = DescriptorProtos.FileDescriptorSet.parseFrom(input);
            var definitions = new LinkedHashMap<String, DescriptorProtos.FileDescriptorProto>();
            for (var file : set.getFileList()) {
                definitions.put(file.getName(), file);
            }

            var files = new HashMap<String, Descriptors.FileDescriptor>();
            for (var file : definitions.values()) {
                buildFile(file.getName(), definitions, files);
            }

            var messages = new HashMap<String, Descriptors.Descriptor>();
            for (var file : files.values()) {
                for (var message : file.getMessageTypes()) {
                    indexMessage(message, messages);
                }
            }
            return Map.copyOf(messages);
        } catch (IOException | Descriptors.DescriptorValidationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Descriptors.FileDescriptor buildFile(String name,
        Map<String, DescriptorProtos.FileDescriptorProto> definitions, Map<String, Descriptors.FileDescriptor> files)
        throws Descriptors.DescriptorValidationException {
        var existing = files.get(name);
        if (existing != null) {
            return existing;
        }
        var definition = definitions.get(name);
        if (definition == null) {
            throw new IllegalStateException("Descriptor set is missing import: " + name);
        }
        var dependencies = new Descriptors.FileDescriptor[definition.getDependencyCount()];
        for (var index = 0; index < dependencies.length; index++) {
            dependencies[index] = buildFile(definition.getDependency(index), definitions, files);
        }
        var descriptor = Descriptors.FileDescriptor.buildFrom(definition, dependencies);
        files.put(name, descriptor);
        return descriptor;
    }

    private static void indexMessage(Descriptors.Descriptor descriptor, Map<String, Descriptors.Descriptor> messages) {
        messages.put(descriptor.getFullName(), descriptor);
        for (var nested : descriptor.getNestedTypes()) {
            indexMessage(nested, messages);
        }
    }
}
