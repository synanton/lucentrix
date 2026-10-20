package org.lucentrix.ingest.plugin.fs;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.lucentrix.ingest.runtime.plugin.ConfigEnv;
import org.lucentrix.ingest.runtime.plugin.JsonPluginConfig;

import java.io.InputStream;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@ToString
@Getter
public class FileSystemSourceConfig extends JsonPluginConfig<FileSystemSourceConfig.Settings> {

    public FileSystemSourceConfig(InputStream is, ConfigEnv configEnv) {
        super(is, configEnv);
    }

    public FileSystemSourceConfig(Settings settings, ConfigEnv configEnv) {
        super(settings, configEnv);
    }

    @Override
    protected String getName() {
        return settings.getName();
    }

    @Override
    protected Class<Settings> getConfigBeanClass() {
        return Settings.class;
    }

    @Override
    protected Settings defaultSettings() {
        return Settings.builder()
                .name("FileSystemSource")
                .include(List.of("**/*.pdf", "**/*.epub"))
                .computeSha256(true)
                .checkpointBatchSize(100)
                .maxDepth(64)
                .followSymlinks(false)
                .build();
    }

    @Builder
    @ToString
    @EqualsAndHashCode
    @Getter
    @Setter
    public static class Settings {
        private String name;
        private String root;
        private String rootId;
        private List<String> include;
        private boolean computeSha256;
        private int checkpointBatchSize;
        private int maxDepth;
        private boolean followSymlinks;
    }
}
