package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.version.VersionCompatibility;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/** Reads the exact model/container identity from the verified container preamble only. */
public final class ModelFileIdentityReader {
    private ModelFileIdentityReader() {}

    public static ModelFileIdentity read(SeekableByteChannel file) throws IOException {
        return read(readContainer(file));
    }

    public static AssetContainerView readContainer(SeekableByteChannel file) throws IOException {
        return AssetContainerReader.read(classifyAccess(file));
    }

    static SeekableByteChannel classifyAccess(SeekableByteChannel file) {
        Objects.requireNonNull(file, "file");
        return file instanceof AccessClassifyingChannel ? file : new AccessClassifyingChannel(file);
    }

    static ModelFileIdentity read(AssetContainerView assetView) throws IOException {
        if (!ModelFileConstant.SCHEMA_ID.equals(assetView.getSchema())) {
            throw new IOException("Schema ID mismatch: " + assetView.getSchema());
        }

        var vendor = assetView.getSchemaProperty(ModelFileConstant.PROP_VENDOR);
        if (vendor == null) {
            throw new IOException("Vendor property not found");
        }
        var versionText = assetView.getSchemaProperty(ModelFileConstant.PROP_VERSION);
        if (versionText == null) {
            throw new IOException("Version property not found");
        }
        if (!supportsVersion(ModelFileConstant.CURRENT_VERSION.toString(), versionText)) {
            throw new UnsupportedEncodingException(
                String.format("Unsupported model version: \"%s\". Exported by \"%s\"", versionText, vendor));
        }

        var encodedModelId = assetView.getSchemaProperty(ModelFileConstant.PROP_MODEL_ID);
        if (!isLowerHexModelId(encodedModelId)) {
            throw new IOException("Model identity property must contain exactly 64 lowercase hexadecimal characters");
        }
        return new ModelFileIdentity(Hash256.parse(encodedModelId), assetView.getContainerId());
    }

    static boolean supportsVersion(String currentText, String candidateText) {
        return VersionCompatibility.isCompatible(currentText, candidateText, (current, candidate) -> {
            var supported = new DefaultArtifactVersion(current);
            var version = new DefaultArtifactVersion(candidate);
            return version.getMajorVersion() <= supported.getMajorVersion()
                && version.getMinorVersion() <= supported.getMinorVersion();
        });
    }

    private static boolean isLowerHexModelId(String value) {
        if (value == null || value.length() != Hash256.SIZE * 2) {
            return false;
        }
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static final class AccessClassifyingChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;

        private AccessClassifyingChannel(SeekableByteChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            try {
                return delegate.read(destination);
            } catch (IOException failure) {
                throw access(failure);
            }
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            try {
                return delegate.write(source);
            } catch (IOException failure) {
                throw access(failure);
            }
        }

        @Override
        public long position() throws IOException {
            try {
                return delegate.position();
            } catch (IOException failure) {
                throw access(failure);
            }
        }

        @Override
        public SeekableByteChannel position(long position) throws IOException {
            try {
                delegate.position(position);
                return this;
            } catch (IOException failure) {
                throw access(failure);
            }
        }

        @Override
        public long size() throws IOException {
            try {
                return delegate.size();
            } catch (IOException failure) {
                throw access(failure);
            }
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            try {
                delegate.truncate(size);
                return this;
            } catch (IOException failure) {
                throw access(failure);
            }
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } catch (IOException failure) {
                throw access(failure);
            }
        }

        private static AssetLoadException access(IOException failure) {
            if (failure instanceof AssetLoadException classified) {
                return classified;
            }
            return AssetLoadException.access("Failed to access model container", failure);
        }
    }
}
