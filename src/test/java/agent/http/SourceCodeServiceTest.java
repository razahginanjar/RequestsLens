package agent.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SourceCodeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsSourceWindowFromConfiguredRoot() throws Exception {
        Path source = tempDir.resolve("demo/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, String.join(System.lineSeparator(),
            "package demo;",
            "class Example {",
            "  String value() {",
            "    return \"ok\";",
            "  }",
            "}"));

        SourceCodeService.SourceLookup lookup = new SourceCodeService()
            .lookup(tempDir.toString(), "demo.Example", 4, 1);

        assertTrue(lookup.sourceAvailable());
        assertEquals("demo/Example.java", lookup.sourcePath());
        assertEquals(3, lookup.startLine());
        assertEquals(5, lookup.endLine());
        assertEquals(3, lookup.lines().size());
        assertEquals(4, lookup.lines().get(1).lineNumber());
        assertTrue(lookup.lines().get(1).highlight());
        assertEquals("    return \"ok\";", lookup.lines().get(1).text());
    }

    @Test
    void mapsInnerClassToOuterSourceFile() throws Exception {
        Path source = tempDir.resolve("demo/Outer.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, String.join(System.lineSeparator(),
            "package demo;",
            "class Outer {",
            "  static class Inner {}",
            "}"));

        SourceCodeService.SourceLookup lookup = new SourceCodeService()
            .lookup(tempDir.toString(), "demo.Outer$Inner", 3, 0);

        assertTrue(lookup.sourceAvailable());
        assertEquals("demo/Outer.java", lookup.sourcePath());
        assertEquals("  static class Inner {}", lookup.lines().get(0).text());
    }

    @Test
    void rejectsUnsafeClassNames() {
        assertFalse(SourceCodeService.isSafeClassName("../demo.Example"));
        assertFalse(SourceCodeService.isSafeClassName("demo/Example"));
        assertFalse(SourceCodeService.isSafeClassName("demo..Example"));

        SourceCodeService.SourceLookup lookup = new SourceCodeService()
            .lookup(tempDir.toString(), "../demo.Example", 1, 1);

        assertFalse(lookup.sourceAvailable());
        assertEquals("Class name is invalid", lookup.message());
    }

    @Test
    void reportsMissingSourceWithoutEscapingRoot() {
        SourceCodeService.SourceLookup lookup = new SourceCodeService()
            .lookup(tempDir.toString(), "demo.Missing", 1, 1);

        assertFalse(lookup.sourceAvailable());
        assertTrue(lookup.message().contains("not found"));
        assertEquals(1, SourceCodeService.rootCount(tempDir.toString()));
    }
}
