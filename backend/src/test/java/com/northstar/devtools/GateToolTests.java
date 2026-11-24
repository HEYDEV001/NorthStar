package com.northstar.devtools;

import static org.assertj.core.api.Assertions.assertThat;

import com.northstar.PostgresAdmin;
import com.northstar.TestData;
import com.northstar.TestcontainersConfiguration;
import com.northstar.TestcontainersConfiguration.TestLogins;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Phase 2 gate's commands as they are run (step 2.11), against the tests' own database through a .env.local of
 * their own: each one's result, the token read from standard input and never shown, and a refusal, before anything is
 * read or written, of any database but a local one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class GateToolTests {

    private final PostgreSQLContainer postgres;
    private final TestLogins logins;
    private final Environment environment;
    private final TestData data;
    private final JdbcClient admin;

    @TempDir
    private Path directory;

    GateToolTests(
            @Autowired PostgreSQLContainer postgres,
            @Autowired TestLogins logins,
            @Autowired Environment environment,
            @Autowired PostgresAdmin postgresAdmin) {
        this.postgres = postgres;
        this.logins = logins;
        this.environment = environment;
        this.data = new TestData(postgresAdmin);
        this.admin = postgresAdmin.jdbc();
    }

    @Test
    void connectStoresTheNumberAndThePipedTokenAndRunningItAgainReplacesTheToken(CapturedOutput console) {
        UUID tenant = data.tenant();
        String number = "pn-" + UUID.randomUUID();
        String waba = "waba-" + UUID.randomUUID();
        String first = token();
        String second = token();

        Run stored = run(Map.of(), first + "\n", connect(tenant, number, waba));
        Run replaced = run(Map.of(TokenInput.VARIABLE, second), "", connect(tenant, number, waba));

        assertThat(stored.code()).as(stored.err()).isEqualTo(GateTool.OK);
        assertThat(stored.out()).startsWith("connected tenant=" + tenant).contains("number=added token=stored");
        assertThat(replaced.code()).as(replaced.err()).isEqualTo(GateTool.OK);
        assertThat(replaced.out()).contains("number=kept token=replaced");
        assertThat(admin.sql("""
                        select i.status || '|' || a.phone_number_id || '|' || a.display_phone || '|' || c.key_id || '|'
                               || (c.rotated_at is not null)
                        from northstar.integrations i
                        join northstar.whatsapp_accounts a on a.tenant_id = i.tenant_id
                        join secrets.integration_credentials c on c.id = i.credential_id
                        where i.tenant_id = :t""").param("t", tenant).query(String.class).list())
                .containsExactly("connected|" + number + "|+15550001234|test-1|true");
        for (String token : List.of(first, second)) {
            assertThat(stored.all() + replaced.all()).doesNotContain(token);
            assertThat(console).doesNotContain(token);
        }
        assertThat(stored.all() + replaced.all())
                .as("no number or Meta ID is printed back")
                .doesNotContain(number, waba, "+15550001234");
    }

    @Test
    void seedWorkflowAndQueueAutomatedTextRunThroughTheAppsOwnServices() {
        UUID tenant = data.tenant();
        UUID conversation = data.conversation(tenant, data.contact(tenant), null);

        Run seeded = run("seed-workflow", "--tenant=" + tenant);
        Run again = run("seed-workflow", "--tenant=" + tenant);
        Run queued = run(
                "queue-automated-text",
                "--tenant=" + tenant,
                "--conversation=" + conversation,
                "--text=Hello from the gate");

        assertThat(seeded.code()).as(seeded.err()).isEqualTo(GateTool.OK);
        assertThat(seeded.out()).startsWith("dev_seed tenant=" + tenant).contains(" created");
        assertThat(again.out()).contains(" already_there");
        assertThat(queued.code()).as(queued.err()).isEqualTo(GateTool.OK);
        assertThat(queued.out()).startsWith("automated_text result=queued message=");
        assertThat(queued.all()).doesNotContain("Hello from the gate");
        assertThat(admin.sql(
                                "select count(*) from northstar.messages where conversation_id = :c and body = 'Hello from the gate'"
                                        + " and status = 'queued'")
                        .param("c", conversation)
                        .query(Long.class)
                        .single())
                .as("queued for the worker, not sent")
                .isOne();
    }

    @Test
    void aDatabaseElsewhereIsRefusedByEveryCommandBeforeTheTokenIsRead() {
        for (String url : List.of(
                "jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:6543/postgres?sslmode=require",
                "jdbc:postgresql://127.0.0.1:5432,db.example.com:5432/postgres",
                "jdbc:postgresql://127.0.0.1:54322/postgres?PGHOST=db.example.com")) {
            Path file = envFile().set("DB_URL", url).in(directory);
            UUID tenant = UUID.randomUUID();
            for (String[] command : List.of(
                    connect(tenant, "pn", "waba"),
                    new String[] {"seed-workflow", "--tenant=" + tenant},
                    new String[] {"queue-automated-text", "--tenant=" + tenant, "--conversation=" + tenant})) {
                Run refused = run(file, Map.of(), unreadable(), command);

                assertThat(refused.code()).as(command[0]).isEqualTo(GateTool.REFUSED);
                assertThat(refused.err()).startsWith("refused code=database_not_local DB_URL");
                assertThat(refused.all()).doesNotContain("supabase.com", "example.com", "ap-south-1");
            }
        }
        Path supabase =
                envFile().set("SUPABASE_URL", "https://abcdef.supabase.co").in(directory);
        assertThat(run(supabase, Map.of(), unreadable(), "seed-workflow", "--tenant=" + UUID.randomUUID())
                        .err())
                .startsWith("refused code=service_not_local SUPABASE_URL");
    }

    @Test
    void aMissingFileOrSettingIsRefusedByName() throws IOException {
        Path missing = directory.resolve("nothing-here");
        assertThat(run(missing, Map.of(), unreadable(), "seed-workflow", "--tenant=" + UUID.randomUUID())
                        .err())
                .startsWith("refused code=env_file_unreadable");
        Path withoutKeys = Files.writeString(
                directory.resolve("no-keys"),
                Files.readString(envFile().in(directory)).replaceAll("(?m)^NORTHSTAR_CREDENTIAL_KEYS=.*$", ""));
        assertThat(run(withoutKeys, Map.of(), unreadable(), connect(UUID.randomUUID(), "pn", "waba"))
                        .err())
                .startsWith("refused code=setting_missing NORTHSTAR_CREDENTIAL_KEYS");
        assertThat(run(withoutKeys, Map.of(), unreadable(), webhookTwice()).err())
                .as("the app secret")
                .startsWith("refused code=setting_missing NORTHSTAR_WHATSAPP_APP_SECRET");
    }

    @Test
    void theTokenIsNeverAnArgumentAndWrongArgumentsTouchNothing() {
        String[] withToken = connect(UUID.randomUUID(), "pn", "waba");
        withToken = Arrays.copyOf(withToken, withToken.length + 1);
        withToken[withToken.length - 1] = "--token=EAAGshould-not-appear";

        Run refused = run(directory.resolve("never-read"), Map.of(), unreadable(), withToken);

        assertThat(refused.code()).isEqualTo(GateTool.REFUSED);
        assertThat(refused.err()).contains("never an argument");
        assertThat(refused.all()).doesNotContain("EAAG");
        for (String[] wrong : List.of(
                new String[] {"connect"},
                new String[] {
                    "connect", "--tenant=not-a-uuid", "--phone-number-id=1", "--waba-id=1", "--display-number=1"
                },
                new String[] {"seed-workflow", "--tenant=" + UUID.randomUUID(), "--force=yes"},
                new String[] {"webhook-twice", "--url=https://example.com/api/v1/me"})) {
            assertThat(run(directory.resolve("never-read"), Map.of(), unreadable(), wrong)
                            .code())
                    .as(String.join(" ", wrong))
                    .isEqualTo(GateTool.REFUSED);
        }
    }

    @Test
    void anUnknownBusinessIsRefused() {
        Run unknown = run("seed-workflow", "--tenant=" + UUID.randomUUID());

        assertThat(unknown.code()).isEqualTo(GateTool.REFUSED);
        assertThat(unknown.err()).startsWith("refused code=unknown_tenant");
    }

    @Test
    void aLoginThatDoesNotWorkIsNamedByItsSettingsBeforeTheTokenIsRead(CapturedOutput console) {
        String wrong = "wrong-password-" + UUID.randomUUID();
        for (String setting : List.of("DB_PASSWORD", "JOBRUNR_DB_PASSWORD", "SECRETS_DB_PASSWORD")) {
            Run refused = run(
                    envFile().set(setting, wrong).in(directory),
                    Map.of(),
                    unreadable(),
                    connect(UUID.randomUUID(), "pn", "waba"));

            assertThat(refused.code()).as(setting).isEqualTo(GateTool.REFUSED);
            assertThat(refused.err())
                    .startsWith("refused code=database_login_failed")
                    .contains(setting, "SQLSTATE 28P01");
            assertThat(refused.all() + console).doesNotContain(wrong);
        }
        Run nothingThere = run(
                envFile()
                        .set("DB_URL", "jdbc:postgresql://127.0.0.1:1/postgres")
                        .in(directory),
                Map.of(),
                unreadable(),
                "seed-workflow",
                "--tenant=" + UUID.randomUUID());
        assertThat(nothingThere.err()).startsWith("refused code=database_unreachable");
    }

    @Test
    void anUnexpectedFailureShowsItsKindAndNeverItsMessage(CapturedOutput console) {
        String secret = "EAAGleaked-" + UUID.randomUUID();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        GateTool failing = new GateTool(
                Map.of(),
                unreadable(),
                null,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                envFile().in(directory),
                application -> {
                    throw new IllegalStateException(secret, new SQLException(secret, "23505"));
                });

        int code = failing.run("seed-workflow", "--tenant=" + UUID.randomUUID());

        assertThat(code).isEqualTo(GateTool.FAILED);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .startsWith("failed IllegalStateException <- SQLException[23505]");
        assertThat(err.toString(StandardCharsets.UTF_8) + console).doesNotContain(secret);
    }

    @Test
    void helpAndUnknownCommandsShowTheUsage() {
        assertThat(run("help").code()).isEqualTo(GateTool.OK);
        assertThat(run("help").out()).contains("connect --tenant", "webhook-twice --url", TokenInput.VARIABLE);
        assertThat(run().code()).isEqualTo(GateTool.REFUSED);
        assertThat(run("drop-everything").code()).isEqualTo(GateTool.REFUSED);
    }

    // Helpers

    /** What a run answered and printed. */
    private record Run(int code, String out, String err) {
        String all() {
            return out + err;
        }
    }

    private Run run(String... args) {
        return run(Map.of(), "", args);
    }

    private Run run(Map<String, String> variables, String piped, String... args) {
        return run(
                envFile().in(directory),
                variables,
                new ByteArrayInputStream(piped.getBytes(StandardCharsets.UTF_8)),
                args);
    }

    private static Run run(Path envFile, Map<String, String> variables, InputStream in, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new GateTool(
                        new HashMap<>(variables),
                        in,
                        null,
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        envFile,
                        GateEnvFile::quietly)
                .run(args);
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private GateEnvFile envFile() {
        return GateEnvFile.of(postgres, logins, environment);
    }

    private static String[] connect(UUID tenant, String number, String waba) {
        return new String[] {
            "connect",
            "--tenant=" + tenant,
            "--phone-number-id=" + number,
            "--waba-id=" + waba,
            "--display-number=+15550001234"
        };
    }

    private static String[] webhookTwice() {
        return new String[] {
            "webhook-twice", "--url=http://127.0.0.1:1/webhooks/whatsapp", "--phone-number-id=1", "--waba-id=1"
        };
    }

    private static String token() {
        return "EAAGtest" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Standard input that fails the test if anything reads it. */
    private static InputStream unreadable() {
        return new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("standard input was read");
            }
        };
    }
}
