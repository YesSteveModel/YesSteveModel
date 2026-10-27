package com.elfmcys.ysm.expression.host;

import dev.semaweave.identity.Hash256;
import dev.semaweave.identity.HashAlgorithmDescriptor;
import dev.semaweave.identity.IdentityHashAlgorithm;
import dev.semaweave.program.ReadOnlyBytes;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The exact hash implementation used by YSM expression identities. */
public enum ExpressionIdentityHash implements IdentityHashAlgorithm {
    SHA_256;

    private static final HashAlgorithmDescriptor DESCRIPTOR = new HashAlgorithmDescriptor("ysm:sha-256", 1);

    @Override
    public HashAlgorithmDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Hash256 hashCanonicalPreimage(ReadOnlyBytes canonicalPreimage) {
        try {
            return Hash256.copyOf(MessageDigest.getInstance("SHA-256").digest(canonicalPreimage.toByteArray()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
