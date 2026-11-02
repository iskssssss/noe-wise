package com.skillforge.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 平台账号隔离及显式测试覆盖的配置验收，不启动数据库或业务宿主。 */
@SpringBootTest(
        classes = PlatformDatasourceEnvironmentPostProcessorTest.ConfigurationFixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PlatformDatasourceEnvironmentPostProcessorTest {

    @Autowired
    private DataSourceProperties dynamicDatasource;

    @Autowired
    private ConfigurableEnvironment applicationEnvironment;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost/test_fixture");
        registry.add("spring.datasource.username", () -> "dynamic-test-user");
        registry.add("spring.datasource.password", () -> "dynamic-test-password");
    }

    @Test
    void platformCredentialsOverrideSharedInstitutionEnvironment() {
        StandardEnvironment environment = environment(Map.of(
                "SPRING_DATASOURCE_USERNAME", "institution-runtime",
                "SPRING_DATASOURCE_PASSWORD", "institution-test-password",
                "PLATFORM_DATASOURCE_USERNAME", "platform-runtime",
                "PLATFORM_DATASOURCE_PASSWORD", "platform-test-password"));

        DataSourceProperties properties = bindDatasource(environment);

        assertThat(properties.getUsername()).isEqualTo("platform-runtime");
        assertThat(properties.getPassword()).isEqualTo("platform-test-password");
    }

    @Test
    void platformUsernameDefaultsToPlatformRole() {
        StandardEnvironment environment = environment(Map.of(
                "SPRING_DATASOURCE_USERNAME", "institution-runtime",
                "SPRING_DATASOURCE_PASSWORD", "institution-test-password",
                "PLATFORM_DATASOURCE_PASSWORD", "platform-test-password"));

        assertThat(bindDatasource(environment).getUsername()).isEqualTo("skillforge_platform");
    }

    @Test
    void missingPlatformPasswordCannotFallBackToInstitutionPassword() {
        StandardEnvironment environment = environment(Map.of(
                "SPRING_DATASOURCE_USERNAME", "institution-runtime",
                "SPRING_DATASOURCE_PASSWORD", "institution-test-password"));

        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("skillforge_platform");
        assertThatThrownBy(() -> environment.getProperty("spring.datasource.password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PLATFORM_DATASOURCE_PASSWORD");
    }

    @Test
    void explicitCommandLineDatasourceKeepsPriority() {
        StandardEnvironment environment = environment(Map.of(
                "SPRING_DATASOURCE_USERNAME", "institution-runtime",
                "SPRING_DATASOURCE_PASSWORD", "institution-test-password"),
                "--spring.datasource.username=command-line-test-user",
                "--spring.datasource.password=command-line-test-password");

        DataSourceProperties properties = bindDatasource(environment);

        assertThat(properties.getUsername()).isEqualTo("command-line-test-user");
        assertThat(properties.getPassword()).isEqualTo("command-line-test-password");
    }

    @Test
    void dynamicPropertySourceKeepsExplicitSpringDatasourceOverride() {
        assertThat(applicationEnvironment.getPropertySources().contains("platformDatasourceCredentials")).isTrue();
        assertThat(dynamicDatasource.getUrl()).isEqualTo("jdbc:postgresql://localhost/test_fixture");
        assertThat(dynamicDatasource.getUsername()).isEqualTo("dynamic-test-user");
        assertThat(dynamicDatasource.getPassword()).isEqualTo("dynamic-test-password");
    }

    private StandardEnvironment environment(Map<String, Object> variables, String... arguments) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        if (arguments.length > 0) {
            environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(arguments));
        }
        new PlatformDatasourceEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(ConfigurationFixture.class));
        return environment;
    }

    private DataSourceProperties bindDatasource(StandardEnvironment environment) {
        return Binder.get(environment).bind("spring.datasource", DataSourceProperties.class).get();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataSourceProperties.class)
    static class ConfigurationFixture {
    }
}
