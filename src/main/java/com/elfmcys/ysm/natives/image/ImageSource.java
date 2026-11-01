package com.elfmcys.ysm.natives.image;

import java.io.IOException;

/** A repeatable external source of encoded image data. */
@FunctionalInterface
public interface ImageSource {
    /** Returns a caller-owned image carrier over completed bytes that consumers must not modify. */
    Image open() throws IOException;
}
