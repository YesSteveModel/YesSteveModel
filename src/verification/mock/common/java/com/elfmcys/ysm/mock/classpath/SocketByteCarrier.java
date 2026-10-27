package com.elfmcys.ysm.mock.classpath;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Objects;

/** Physical-only length framing for production-encoded bytes. */
public final class SocketByteCarrier implements AutoCloseable {
    private static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;

    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;

    public SocketByteCarrier(Socket socket) throws IOException {
        this.socket = Objects.requireNonNull(socket, "socket");
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public void send(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length <= 0 || bytes.length > MAX_FRAME_BYTES) {
            throw new IOException("Carrier frame length is outside the physical bound: " + bytes.length);
        }
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    public byte[] receive() throws IOException {
        var length = input.readInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Carrier frame length is outside the physical bound: " + length);
        }
        var bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
