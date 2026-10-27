package com.elfmcys.ysm.expression.host;

import com.elfmcys.ysm.compatibility.AbiVersion;

import dev.semaweave.identity.Fingerprint;
import dev.semaweave.program.ReadOnlyBytes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Frozen published-declaration environment for local artifact reuse. */
public record DeclarationEnvironment(List<String> eligibleComponentClasses, Fingerprint fingerprint,
    List<Class<?>> linkageTypes) {

    private static final String DOMAIN = "ysm:declaration-environment:" + AbiVersion.CACHE_ANIMATION;

    public DeclarationEnvironment {
        eligibleComponentClasses = Objects.requireNonNull(eligibleComponentClasses, "eligibleComponentClasses").stream()
            .distinct().sorted().toList();
        Objects.requireNonNull(fingerprint, "fingerprint");
        linkageTypes = List.copyOf(linkageTypes);
    }

    public static DeclarationEnvironment create(Iterable<LinkedDeclaration> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        var classes = new java.util.TreeSet<String>();
        var declarations = new java.util.ArrayList<LinkedDeclaration>();
        descriptors.forEach(declarations::add);
        declarations.sort(java.util.Comparator.comparing(descriptor -> descriptor.schema().owner()));
        var linkageTypes = new java.util.LinkedHashSet<Class<?>>();
        for (var descriptor : declarations) {
            classes.add(descriptor.schema().owner() + ":" + descriptor.schema().componentSubject());
            linkageTypes.add(descriptor.owner());
            linkageTypes.add(descriptor.helper());
            for (var member : descriptor.members()) {
                linkageTypes.add(member.subject());
                linkageTypes.add(member.binding().methodType().returnType());
                linkageTypes.addAll(member.binding().methodType().parameterList());
            }
        }
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                text(output, DOMAIN);
                output.writeInt(classes.size());
                for (var className : classes) {
                    text(output, className);
                }
                for (var declaration : declarations) {
                    text(output, declaration.verifiedModId());
                    byte[] schema = declaration.schema().encode();
                    output.writeInt(schema.length);
                    output.write(schema);
                }
            }
            return new DeclarationEnvironment(List.copyOf(classes),
                Fingerprint.compute(ExpressionIdentityHash.SHA_256, ReadOnlyBytes.copyOf(bytes.toByteArray())),
                List.copyOf(linkageTypes));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        var bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
