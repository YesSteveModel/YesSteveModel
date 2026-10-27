package dev.semaweave.compilation;

/** Test-only access to the deliberately unforgeable package-private token constructor. */
public final class TestResourceGenerations {
    private TestResourceGenerations() {}

    public static ResourceGenerationToken create() {
        return new ResourceGenerationToken();
    }
}
