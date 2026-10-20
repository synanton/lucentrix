package org.lucentrix.ingest.plugin.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lucentrix.ingest.encrypt.PasswordEncryptor;
import org.lucentrix.ingest.runtime.plugin.ConfigEnv;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FileSystemSourceConfigTest {

    @Test
    public void configRoundTrip(@TempDir Path tempDir) {
        FileSystemSourceConfig.Settings settings = FileSystemSourceConfig.Settings.builder()
                .name("FilesystemTest")
                .root("/tmp/library")
                .rootId("library")
                .include(List.of("**/*.pdf", "**/*.epub"))
                .computeSha256(true)
                .checkpointBatchSize(50)
                .maxDepth(20)
                .followSymlinks(false)
                .build();

        ConfigEnv configEnv = ConfigEnv.builder()
                .encryptor(new PasswordEncryptor())
                .build();

        FileSystemSourceConfig fromCode = new FileSystemSourceConfig(settings, configEnv);
        File configFile = new File(tempDir.toFile(), "filesystem.json");

        try (FileOutputStream out = new FileOutputStream(configFile)) {
            fromCode.save(out);
        } catch (Exception ex) {
            throw new RuntimeException("Error saving config", ex);
        }

        try (FileInputStream in = new FileInputStream(configFile)) {
            FileSystemSourceConfig fromFile = new FileSystemSourceConfig(in, configEnv);
            assertEquals(fromCode, fromFile);
        } catch (Exception ex) {
            throw new RuntimeException("Error reading config", ex);
        }
    }
}
