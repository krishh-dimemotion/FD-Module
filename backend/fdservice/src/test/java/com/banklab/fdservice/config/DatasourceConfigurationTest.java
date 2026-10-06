package com.banklab.fdservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Exercises real Boot config loading without opening a database connection. */
class DatasourceConfigurationTest {

    @TempDir
    Path localConfig;

    @Test
    void testProfileOverridesLocalBaseUrlButInheritsLocalSettings() throws Exception {
        withLocalBaseConfig("test").run(context -> {
            assertThat(context).hasNotFailed();
            String url = context.getEnvironment().getRequiredProperty("spring.datasource.url");
            assertThat(URI.create(url.substring("jdbc:".length())).getPath()).isEqualTo("/fd_db_test");
            assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                    .isEqualTo("local-config-user");
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("create");
        });
    }

    @Test
    void defaultProfileKeepsLocalApplicationDatabaseAndSettings() throws Exception {
        withLocalBaseConfig("default").run(context -> {
            assertThat(context).hasNotFailed();
            String url = context.getEnvironment().getRequiredProperty("spring.datasource.url");
            assertThat(URI.create(url.substring("jdbc:".length())).getPath()).isEqualTo("/fd_db");
            assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                    .isEqualTo("local-config-user");
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
        });
    }

    private ApplicationContextRunner withLocalBaseConfig(String profile) throws Exception {
        // Reproduce a developer's external base file, using synthetic non-secret values.
        Files.writeString(localConfig.resolve("application.properties"), """
                spring.datasource.url=jdbc:mysql://localhost:3306/fd_db
                spring.datasource.username=local-config-user
                """);
        String locations = System.getProperty("spring.config.location");
        assertThat(locations).as("Surefire must configure profile-aware location grouping").isNotBlank();
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile,
                        "spring.config.location=" + locations + ";optional:" + localConfig.toUri());
    }
}
