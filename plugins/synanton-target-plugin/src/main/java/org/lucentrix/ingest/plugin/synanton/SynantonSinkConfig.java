package org.lucentrix.ingest.plugin.synanton;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.lucentrix.ingest.runtime.plugin.ConfigEnv;
import org.lucentrix.ingest.runtime.plugin.JsonPluginConfig;

import java.io.InputStream;

@EqualsAndHashCode(callSuper = true)
@ToString
@Getter
public class SynantonSinkConfig extends JsonPluginConfig<SynantonSinkConfig.Settings> {

    public SynantonSinkConfig(InputStream is, ConfigEnv configEnv) {
        super(is, configEnv);
    }

    public SynantonSinkConfig(Settings settings, ConfigEnv configEnv) {
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
                .name("synanton")
                .baseUrl("http://localhost:8090")
                .synfluxUrl("")
                .tenant("demo")
                .apiKey("")
                .sourceId("books-demo")
                .connectorId("upload")
                .sourceEndpoint("upload://books")
                .build();
    }

    @Builder
    @ToString
    @EqualsAndHashCode
    @Getter
    public static class Settings {
        private String name;
        /** Platform API base (synflux NodePort in the demo). */
        private String baseUrl;
        /** Deprecated: the sink now uses the single multipart ingestion endpoint. */
        private String synfluxUrl;
        /** Informational only; the tenant comes from the bearer token. */
        private String tenant;
        private String apiKey;
        /** Upload source registered with the platform. */
        private String sourceId;
        /** Connector id recorded on the source and each ingestion run. */
        private String connectorId;
        /** Source endpoint label (informational for upload sources). */
        private String sourceEndpoint;
    }
}
