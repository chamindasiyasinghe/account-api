package com.westpac.assessment;

import static com.westpac.assessment.TestConstants.SWAGGER_ENABLED_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

class ProfileConfigurationTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class ProfileProbe {}

  ConfigurableApplicationContext profile(String profile) {
    return new SpringApplicationBuilder(ProfileProbe.class)
        .web(WebApplicationType.NONE)
        .properties("spring.main.banner-mode=off", "logging.level.root=OFF")
        .run(
            "--spring.profiles.active=" + profile,
            "--DB_URL=jdbc:postgresql://db.example/assessment",
            "--DB_USERNAME=test-user",
            "--DB_PASSWORD=placeholder",
            "--REDIS_HOST=redis.example",
            "--REDIS_PASSWORD=placeholder",
            "--API_KEY=placeholder",
            "--JWT_SECRET=placeholder",
            "--JWT_ISSUER=issuer.example",
            "--JWT_AUDIENCE=assessment-api");
  }

  @Test
  void localUses8090AndEnablesSwagger() {
    try (var context = profile("local")) {
      var env = context.getEnvironment();
      assertThat(env.getProperty("server.port")).isEqualTo("8090");
      assertThat(env.getProperty(SWAGGER_ENABLED_PROPERTY, Boolean.class)).isTrue();
      assertThat(env.getProperty("spring.cache.type")).isEqualTo("redis");
    }
  }

  @Test
  void testUsesH2AndSimpleCacheWithoutFlyway() {
    try (var context = profile("test")) {
      var env = context.getEnvironment();
      assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:h2:");
      assertThat(env.getProperty("spring.cache.type")).isEqualTo("simple");
      assertThat(env.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
    }
  }

  @Test
  void uatUsesExternalServicesAndEnablesSwagger() {
    try (var context = profile("uat")) {
      var env = context.getEnvironment();
      assertThat(env.getProperty("spring.datasource.url"))
          .isEqualTo("jdbc:postgresql://db.example/assessment");
      assertThat(env.getProperty("spring.data.redis.ssl.enabled", Boolean.class)).isTrue();
      assertThat(env.getProperty(SWAGGER_ENABLED_PROPERTY, Boolean.class)).isTrue();
    }
  }

  @Test
  void prodDisablesSwaggerAndUsesExternalServices() {
    try (var context = profile("prod")) {
      var env = context.getEnvironment();
      assertThat(env.getProperty("server.port")).isEqualTo("8090");
      assertThat(env.getProperty(SWAGGER_ENABLED_PROPERTY, Boolean.class)).isFalse();
      assertThat(env.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
      assertThat(env.getProperty("spring.data.redis.host")).isEqualTo("redis.example");
      assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }
  }
}
