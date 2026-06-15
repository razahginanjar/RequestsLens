package agent.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class JarPackageDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void suggestsCommonPackageFromSpringBootJarClasses() throws Exception {
        Path jar = tempDir.resolve("app.jar");
        writeJar(jar,
            "BOOT-INF/classes/com/user/app/controller/UserController.class",
            "BOOT-INF/classes/com/user/app/service/UserService.class",
            "BOOT-INF/classes/org/springframework/boot/loader/Launcher.class",
            "BOOT-INF/lib/spring.jar");

        JarPackageDiscovery.DiscoveryResult result = JarPackageDiscovery.discover(jar);

        assertTrue(result.available());
        assertEquals("com.user.app", result.suggestedPackage());
        assertEquals(2, result.classCount());
        assertEquals(2, result.packageCount());
        assertEquals("com.user.app.controller", result.topPackages().get(0).packageName());
    }

    @Test
    void suggestsPackageFromRegularJarClasses() throws Exception {
        Path jar = tempDir.resolve("plain.jar");
        writeJar(jar,
            "demo/DemoApplication.class",
            "demo/DemoService.class",
            "com/fasterxml/jackson/ObjectMapper.class");

        JarPackageDiscovery.DiscoveryResult result = JarPackageDiscovery.discover(jar);

        assertTrue(result.available());
        assertEquals("demo", result.suggestedPackage());
        assertEquals(2, result.classCount());
        assertEquals(1, result.packageCount());
    }

    @Test
    void unavailableWhenJarDoesNotExist() {
        JarPackageDiscovery.DiscoveryResult result =
            JarPackageDiscovery.discover(tempDir.resolve("missing.jar"));

        assertFalse(result.available());
        assertTrue(result.warnings().get(0).contains("not found"));
    }

    @Test
    void doesNotExcludeOrgApacheApplicationPackages() throws Exception {
        Path jar = tempDir.resolve("apache-owned-app.jar");
        writeJar(jar,
            "org/apache/acme/app/App.class",
            "org/apache/acme/app/Service.class",
            "org/apache/commons/lang3/StringUtils.class");

        JarPackageDiscovery.DiscoveryResult result = JarPackageDiscovery.discover(jar);

        assertTrue(result.available());
        assertEquals("org.apache.acme.app", result.suggestedPackage());
        assertEquals(2, result.classCount());
    }

    private static void writeJar(Path jar, String... entries) throws IOException {
        try (JarOutputStream out = new JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            for (String entry : entries) {
                JarEntry jarEntry = new JarEntry(entry);
                out.putNextEntry(jarEntry);
                out.closeEntry();
            }
        }
    }
}
