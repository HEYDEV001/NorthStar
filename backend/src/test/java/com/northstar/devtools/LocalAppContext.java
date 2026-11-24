package com.northstar.devtools;

import com.northstar.NorthstarApplication;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.mock.web.SpringBootMockServletContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.web.context.support.GenericWebApplicationContext;

/**
 * The app's own services for a gate tool (step 2.11): the production beans, wired as the API wires them, against the
 * database in .env.local, without serving anything.
 *
 * <ul>
 *   <li>The tools live in the test sources, next to fakes that stand in for Meta in every test. Classes compiled from
 *       the test sources are left out of the component scan here, so a tool gets the real services and never a fake.
 *   <li>The worker is off, so no job server starts and no business token is read. Flyway, which is on the test class
 *       path, is off too: a tool never changes the schema.
 *   <li>The app needs a servlet context for its security configuration; it gets a mock one, so no port is opened.
 *   <li>Settings come from {@link LocalEnv} above every other source, and {@link LocalEnv#requireLocal()} is checked
 *       again here, so there is no way to start this against a database elsewhere.
 * </ul>
 */
final class LocalAppContext {

    private LocalAppContext() {}

    static ConfigurableApplicationContext start(LocalEnv env) {
        return application(env).run();
    }

    /** The application, not yet started; tests adjust its listeners so they leave the JVM's logging alone. */
    static SpringApplication application(LocalEnv env) {
        env.requireLocal();
        Map<String, Object> settings = env.appProperties();

        SpringApplication application = new SpringApplication(NorthstarApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        // Quiet, and silent about a failed start: Spring Boot's report can quote settings, and the tool prints its own
        // line.
        application.setDefaultProperties(Map.of(
                "logging.level.root", "WARN",
                "logging.level.org.springframework.boot.SpringApplication", "OFF",
                "logging.level.org.springframework.boot.diagnostics", "OFF"));
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setApplicationContextFactory(type -> {
            GenericWebApplicationContext context = new GenericWebApplicationContext();
            context.setServletContext(new SpringBootMockServletContext(""));
            return context;
        });
        application.addInitializers(context -> {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("gate-env-local", settings));
            context.getBeanFactory().registerSingleton("withoutTestSources", new WithoutTestSources());
        });
        return application;
    }

    /** Leaves classes compiled from src/test/java out of the app's component scan. */
    private static final class WithoutTestSources extends TypeExcludeFilter {

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            return reader.getResource().getURL().getPath().contains("/test-classes/");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WithoutTestSources;
        }

        @Override
        public int hashCode() {
            return WithoutTestSources.class.hashCode();
        }
    }
}
