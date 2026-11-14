package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.migrate.Migrate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.flywaydb.core.FlywayTelemetryManager;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.extensibility.Plugin;
import org.flywaydb.core.internal.NullFlywayTelemetryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * The migration runner (db/migrate, step 1.15), which the deploy's pre-deploy job runs as migrator. It builds exactly
 * the schema the tests build, uses the Maven plugin's settings and the tests' Flyway, sends no telemetry, and prints no
 * password, credentials in a URL, or SQL.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class MigrationRunnerTests {

    private static final Path MIGRATIONS = Path.of("../db/migrations");
    private static final Path RUNNER_POM = Path.of("../db/migrate/pom.xml");
    private static final Path BACKEND_POM = Path.of("pom.xml");

    private final PostgreSQLContainer postgres;
    private final TestcontainersConfiguration.TestLogins logins;

    MigrationRunnerTests(
            @Autowired PostgreSQLContainer postgres, @Autowired TestcontainersConfiguration.TestLogins logins) {
        this.postgres = postgres;
        this.logins = logins;
    }

    @Test
    void appliesEveryMigrationAndBuildsTheSameSchemaAsTheAppsTests(CapturedOutput output) throws Exception {
        String database = freshDatabase();
        long migrationFiles;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            migrationFiles = files.filter(file -> file.getFileName().toString().matches("V\\d+__.+\\.sql"))
                    .count();
        }

        assertThat(Migrate.run(settings(database, logins.migratorPassword()), MIGRATIONS))
                .isZero();
        assertThat(output).contains("migrate_succeeded applied=" + migrationFiles + " ");

        assertThat(schemaOf(database))
                .as("the schema the runner builds, against the one the app's tests migrate")
                .containsExactlyElementsOf(schemaOf("postgres"));
        assertThat(historyOf(database)).containsExactlyElementsOf(historyOf("postgres"));

        // The pre-deploy job runs on every deploy: a second run applies nothing and succeeds.
        assertThat(Migrate.run(settings(database, logins.migratorPassword()), MIGRATIONS))
                .isZero();
        assertThat(output).contains("migrate_succeeded applied=0 ");
        assertThat(output).doesNotContain(logins.migratorPassword());
    }

    @Test
    void aFailedMigrationNamesItsVersionButNeverItsSql(@TempDir Path migrations, CapturedOutput output)
            throws Exception {
        String marker = "sql-marker-" + UUID.randomUUID();
        Files.writeString(migrations.resolve("V1__first.sql"), "create table northstar.runner_probe (id int);");
        Files.writeString(
                migrations.resolve("V2__breaks.sql"),
                "insert into northstar.no_such_table (note) values ('" + marker + "');");
        String database = freshDatabase();

        assertThat(Migrate.run(settings(database, logins.migratorPassword()), migrations))
                .isOne();

        assertThat(output)
                .contains("migrate_failed ")
                .contains("sql_state=42P01")
                .contains("version=2 description=\"breaks\"")
                .doesNotContain(marker)
                .doesNotContain("no_such_table")
                .doesNotContain(logins.migratorPassword());
    }

    @Test
    void anEditedMigrationFailsValidationByVersionWithoutItsSql(@TempDir Path migrations, CapturedOutput output)
            throws Exception {
        String marker = "sql-marker-" + UUID.randomUUID();
        Path first = migrations.resolve("V1__first.sql");
        Files.writeString(first, "create table northstar.runner_probe (id int);");
        String database = freshDatabase();
        assertThat(Migrate.run(settings(database, logins.migratorPassword()), migrations))
                .isZero();

        Files.writeString(first, "create table northstar.runner_probe (id int, note text default '" + marker + "');");

        assertThat(Migrate.run(settings(database, logins.migratorPassword()), migrations))
                .isOne();
        assertThat(output)
                .contains("migrate_failed reason=validation")
                .contains("migration version=1 description=\"first\" state=SUCCESS checksum_matches=false")
                .doesNotContain(marker);
    }

    @Test
    void aWrongPasswordGivesItsSqlStateAndNeverThePassword(CapturedOutput output) throws Exception {
        String wrong = "wrong-" + UUID.randomUUID();

        assertThat(Migrate.run(settings(freshDatabase(), wrong), MIGRATIONS)).isOne();

        assertThat(output)
                .contains("migrate_failed ")
                .contains("sql_state=28P01")
                .doesNotContain(wrong);
    }

    @Test
    void refusesMissingSettingsAndCredentialsInTheUrl(CapturedOutput output) {
        String password = "pw-" + UUID.randomUUID();
        String url = "jdbc:postgresql://db.example.test:5432/postgres";

        assertThat(Migrate.run(Map.of("FLYWAY_URL", url, "FLYWAY_USER", "migrator"), MIGRATIONS))
                .isEqualTo(2);
        assertThat(output).contains("migrate_refused reason=missing_setting name=FLYWAY_PASSWORD");

        for (String withCredentials : List.of(
                url + "?password=" + password,
                url + "?user=migrator&Password=" + password,
                "jdbc:postgresql://migrator:" + password + "@db.example.test:5432/postgres")) {
            Map<String, String> settings =
                    Map.of("FLYWAY_URL", withCredentials, "FLYWAY_USER", "migrator", "FLYWAY_PASSWORD", password);
            assertThat(Migrate.run(settings, MIGRATIONS)).isEqualTo(2);
        }
        assertThat(output)
                .contains("migrate_refused reason=credentials_in_url name=FLYWAY_URL")
                .doesNotContain(password)
                .doesNotContain("db.example.test");
    }

    /** The runner's Flyway settings are the Maven plugin's, and it gets Flyway from the same Spring Boot version. */
    @Test
    void usesTheMavenPluginsSettingsAndTheTestsVersions() throws Exception {
        Document backend = parse(BACKEND_POM);
        Document runner = parse(RUNNER_POM);
        Element plugin = flywayPlugin(backend);
        FluentConfiguration runnerSettings = Migrate.configure(MIGRATIONS);

        assertThat(List.of(runnerSettings.getSchemas())).containsExactlyElementsOf(texts(plugin, "schema"));
        assertThat(runnerSettings.isCreateSchemas()).isEqualTo(flag(plugin, "createSchemas", true));
        assertThat(runnerSettings.isCleanDisabled()).isTrue().isEqualTo(flag(plugin, "cleanDisabled", false));
        assertThat(runnerSettings.isValidateOnMigrate()).isTrue().isEqualTo(flag(plugin, "validateOnMigrate", true));
        assertThat(texts(plugin, "location"))
                .containsExactly("filesystem:${project.basedir}/../db/migrations")
                .as("the runner reads the same folder");
        assertThat(runnerSettings.getLocations())
                .singleElement()
                .satisfies(
                        location -> assertThat(Path.of(location.getRootPath())).isEqualTo(MIGRATIONS.toAbsolutePath()));

        assertThat(parentVersion(runner))
                .as("db/migrate/pom.xml must use the backend's Spring Boot version")
                .isEqualTo(parentVersion(backend));
        assertThat(runner.getElementsByTagName("dependency").getLength()).isPositive();
        for (Element dependency : elements(runner, "dependency")) {
            assertThat(dependency.getElementsByTagName("version").getLength())
                    .as("runner dependencies take their versions from Spring Boot's dependency management")
                    .isZero();
        }
    }

    /** flyway-core registers only its no-op telemetry manager; Redgate's sending one ships with its CLI, not here. */
    @Test
    void flywaySendsNoTelemetry() {
        List<Plugin> telemetry = ServiceLoader.load(Plugin.class, Migrate.class.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get)
                .filter(FlywayTelemetryManager.class::isInstance)
                .toList();

        assertThat(telemetry)
                .isNotEmpty()
                .allSatisfy(manager -> assertThat(manager).isInstanceOf(NullFlywayTelemetryManager.class));
    }

    // Helpers.

    /** A new, empty database with the roles bootstrapped, as on a new environment. */
    private String freshDatabase() throws SQLException {
        String name = "runner_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = connect("postgres");
                Statement statement = admin.createStatement()) {
            statement.execute("create database " + name);
        }
        try (Connection admin = connect(name)) {
            DatabaseBootstrap.applyRoles(admin);
        }
        return name;
    }

    private Map<String, String> settings(String database, String password) {
        return Map.of("FLYWAY_URL", urlOf(database), "FLYWAY_USER", "migrator", "FLYWAY_PASSWORD", password);
    }

    private String urlOf(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(urlOf(database), postgres.getUsername(), postgres.getPassword());
    }

    /** Everything in the northstar and jobrunr schemas that migrations create, one sorted line per object. */
    private List<String> schemaOf(String database) throws SQLException {
        return lines(database, """
                with s as (select oid, nspname from pg_namespace where nspname in ('northstar', 'jobrunr'))
                select 'schema ' || n.nspname || ' owner=' || pg_get_userbyid(n.nspowner)
                       || ' acl=' || coalesce(n.nspacl::text, '')
                from pg_namespace n join s on s.oid = n.oid
                union all
                select 'relation ' || s.nspname || '.' || c.relname || ' kind=' || c.relkind::text
                       || ' owner=' || pg_get_userbyid(c.relowner) || ' rls=' || c.relrowsecurity
                       || ' force=' || c.relforcerowsecurity || ' acl=' || coalesce(c.relacl::text, '')
                from pg_class c join s on s.oid = c.relnamespace
                union all
                select 'column ' || s.nspname || '.' || c.relname || '.' || a.attname
                       || ' ' || format_type(a.atttypid, a.atttypmod) || ' notnull=' || a.attnotnull
                       || ' default=' || coalesce(pg_get_expr(d.adbin, d.adrelid), '')
                       || ' acl=' || coalesce(a.attacl::text, '')
                from pg_attribute a
                join pg_class c on c.oid = a.attrelid
                join s on s.oid = c.relnamespace
                left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                where a.attnum > 0 and not a.attisdropped
                union all
                select 'constraint ' || s.nspname || '.' || coalesce(c.relname, '-') || '.' || con.conname
                       || ' ' || pg_get_constraintdef(con.oid)
                from pg_constraint con join s on s.oid = con.connamespace left join pg_class c on c.oid = con.conrelid
                union all
                select 'index ' || pg_get_indexdef(i.indexrelid)
                from pg_index i join pg_class c on c.oid = i.indexrelid join s on s.oid = c.relnamespace
                union all
                select 'policy ' || schemaname || '.' || tablename || '.' || policyname || ' ' || permissive
                       || ' ' || roles::text || ' ' || cmd || ' using=' || coalesce(qual, '')
                       || ' check=' || coalesce(with_check, '')
                from pg_policies where schemaname in ('northstar', 'jobrunr')
                union all
                select 'function ' || s.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid)
                       || ') owner=' || pg_get_userbyid(p.proowner) || ' secdef=' || p.prosecdef
                       || ' config=' || coalesce(p.proconfig::text, '') || ' acl=' || coalesce(p.proacl::text, '')
                       || ' body=' || md5(p.prosrc)
                from pg_proc p join s on s.oid = p.pronamespace
                union all
                select 'trigger ' || s.nspname || '.' || c.relname || ' ' || pg_get_triggerdef(t.oid)
                from pg_trigger t join pg_class c on c.oid = t.tgrelid join s on s.oid = c.relnamespace
                where not t.tgisinternal
                union all
                select 'default_acl ' || pg_get_userbyid(d.defaclrole) || ' ' || coalesce(s.nspname, '-')
                       || ' ' || d.defaclobjtype::text || ' ' || d.defaclacl::text
                from pg_default_acl d left join s on s.oid = d.defaclnamespace
                where s.oid is not null or (d.defaclnamespace = 0 and pg_get_userbyid(d.defaclrole) = 'migrator')
                order by 1""");
    }

    private List<String> historyOf(String database) throws SQLException {
        return lines(database, """
                select version || ' ' || description || ' ' || type || ' ' || coalesce(checksum::text, '-')
                       || ' ' || installed_by || ' ' || success
                from northstar.flyway_schema_history order by installed_rank""");
    }

    private List<String> lines(String database, String sql) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Connection admin = connect(database);
                Statement statement = admin.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) lines.add(rows.getString(1));
        }
        return lines;
    }

    private static Document parse(Path pom) throws Exception {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + pom, e);
        }
    }

    private static Element flywayPlugin(Document pom) {
        return elements(pom, "plugin").stream()
                .filter(plugin -> "flyway-maven-plugin".equals(childText(plugin, "artifactId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("backend/pom.xml has no flyway-maven-plugin"));
    }

    private static String parentVersion(Document pom) {
        Element parent = (Element) pom.getElementsByTagName("parent").item(0);
        return childText(parent, "version");
    }

    private static boolean flag(Element plugin, String name, boolean byDefault) {
        List<String> values = texts(plugin, name);
        return values.isEmpty() ? byDefault : Boolean.parseBoolean(values.getFirst());
    }

    private static List<String> texts(Element parent, String tag) {
        return elements(parent, tag).stream()
                .map(element -> element.getTextContent().trim())
                .toList();
    }

    private static List<Element> elements(Document document, String tag) {
        return elements(document.getDocumentElement(), tag);
    }

    private static List<Element> elements(Element parent, String tag) {
        List<Element> found = new ArrayList<>();
        var nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) found.add((Element) nodes.item(i));
        return found;
    }

    private static String childText(Element parent, String tag) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && tag.equals(child.getTagName()))
                return child.getTextContent().trim();
        }
        return null;
    }
}
