package com.northstar.devtools;

import com.northstar.integrations.internal.WhatsAppNumberConnector;
import com.northstar.integrations.internal.WhatsAppNumberConnector.Connected;
import java.io.Console;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The Phase 2 gate's local tools (step 2.11, docs/phase2-gate-runbook.md), started by {@code scripts/local/gate}:
 *
 * <ul>
 *   <li>{@code connect}: a WhatsApp number for a business ({@link WhatsAppNumberConnector});
 *   <li>{@code seed-workflow}: a published development workflow for it ({@link SeedDevWorkflow});
 *   <li>{@code queue-automated-text}: an automated message to a conversation ({@link QueueAutomatedText});
 *   <li>{@code webhook-twice}: the same signed webhook, posted twice ({@link PostSignedWebhookTwice}).
 * </ul>
 *
 * They are test-source code: nothing here is in the app or its image. The three that use the database work only with
 * the one in .env.local, and only when it is on this machine. They print IDs, fixed codes and what to do next, never a
 * token, secret, message text or phone number; an unexpected failure prints the kinds of exception and the database's
 * code, never a message, which can quote values.
 */
public final class GateTool {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int REFUSED = 2;

    private static final String[] APP_LOGIN = {"DB_USER", "DB_PASSWORD"};
    private static final String[] JOBS_LOGIN = {"JOBRUNR_DB_USER", "JOBRUNR_DB_PASSWORD"};
    private static final String[] SECRETS_LOGIN = {"SECRETS_DB_USER", "SECRETS_DB_PASSWORD"};

    static final String USAGE = """
            Phase 2 gate tools (docs/phase2-gate-runbook.md). Local database only.
              connect --tenant=<uuid> --phone-number-id=<id> --waba-id=<id> --display-number=<number>
                  the Meta access token is read from the terminal, standard input or %s
              seed-workflow --tenant=<uuid>
              queue-automated-text --tenant=<uuid> --conversation=<uuid> [--text=<text>]
              webhook-twice --url=<webhook url> --phone-number-id=<id> --waba-id=<id>
                  [--from=910000<six digits>] [--text=<text>] [--hours-ago=<hours>]
            """.formatted(TokenInput.VARIABLE);

    private final Map<String, String> environment;
    private final InputStream in;
    private final Console console;
    private final PrintStream out;
    private final PrintStream err;
    private final Path envFile;
    private final Function<SpringApplication, SpringApplication> adjust;

    GateTool(
            Map<String, String> environment,
            InputStream in,
            Console console,
            PrintStream out,
            PrintStream err,
            Path envFile,
            Function<SpringApplication, SpringApplication> adjust) {
        this.environment = environment;
        this.in = in;
        this.console = console;
        this.out = out;
        this.err = err;
        this.envFile = envFile;
        this.adjust = adjust;
    }

    public static void main(String[] args) {
        GateTool tool = new GateTool(
                System.getenv(),
                System.in,
                System.console(),
                System.out,
                System.err,
                LocalEnv.FILE,
                Function.identity());
        System.exit(tool.run(args));
    }

    /** Runs one command and returns its exit code. */
    int run(String... args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            out.print(USAGE);
            return args.length == 0 ? REFUSED : OK;
        }
        List<String> options = Arrays.asList(args).subList(1, args.length);
        try {
            switch (args[0]) {
                case "connect" -> connect(options);
                case "seed-workflow" -> seedWorkflow(options);
                case "queue-automated-text" -> queueAutomatedText(options);
                case "webhook-twice" -> webhookTwice(options);
                default -> {
                    err.print(USAGE);
                    return REFUSED;
                }
            }
            return OK;
        } catch (LocalToolException refused) {
            err.println("refused code=" + refused.code() + " " + refused.getMessage());
            return REFUSED;
        } catch (RuntimeException unexpected) {
            err.println("failed " + kinds(unexpected) + ". Is the database migrated"
                    + " (./mvnw initialize flyway:migrate)? See docs/phase2-gate-runbook.md.");
            return FAILED;
        }
    }

    private void connect(List<String> options) {
        ToolArguments arguments =
                ToolArguments.parse(options, "tenant", "phone-number-id", "waba-id", "display-number");
        WhatsAppNumberConnector.Request request = new WhatsAppNumberConnector.Request(
                arguments.requireUuid("tenant"),
                arguments.require("phone-number-id"),
                arguments.require("waba-id"),
                arguments.require("display-number"));
        LocalEnv env = localEnv();
        env.require("NORTHSTAR_CREDENTIAL_KEYS");
        env.require("NORTHSTAR_CREDENTIAL_ACTIVE_KEY");
        LocalDatabaseCheck.logins(env, APP_LOGIN, JOBS_LOGIN, SECRETS_LOGIN);

        byte[] token = TokenInput.read(environment, in, console);
        try (ConfigurableApplicationContext context = start(env)) {
            Connected connected;
            try (WhatsAppNumberConnector connector = WhatsAppNumberConnector.open(context)) {
                connected = connector.connect(request, token);
            }
            out.println("connected tenant=" + request.tenantId() + " whatsapp_account=" + connected.accountId()
                    + " integration=" + connected.integrationId() + " number="
                    + (connected.numberAdded() ? "added" : "kept") + " token="
                    + (connected.tokenReplaced() ? "replaced" : "stored"));
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private void seedWorkflow(List<String> options) {
        UUID tenant = ToolArguments.parse(options, "tenant").requireUuid("tenant");
        LocalEnv env = localEnv();
        LocalDatabaseCheck.logins(env, APP_LOGIN, JOBS_LOGIN);
        try (ConfigurableApplicationContext context = start(env)) {
            SeedDevWorkflow.Seeded seeded = SeedDevWorkflow.on(context).seed(tenant);
            out.println("dev_seed tenant=" + tenant + " workflow=" + seeded.workflowId() + " version="
                    + seeded.versionId() + (seeded.created() ? " created" : " already_there"));
        }
    }

    private void queueAutomatedText(List<String> options) {
        ToolArguments arguments = ToolArguments.parse(options, "tenant", "conversation", "text");
        UUID tenant = arguments.requireUuid("tenant");
        UUID conversation = arguments.requireUuid("conversation");
        String text = arguments.get("text").orElse(QueueAutomatedText.DEFAULT_TEXT);
        LocalEnv env = localEnv();
        LocalDatabaseCheck.logins(env, APP_LOGIN, JOBS_LOGIN);
        try (ConfigurableApplicationContext context = start(env)) {
            QueueAutomatedText.Answer answer = QueueAutomatedText.on(context).queue(tenant, conversation, text);
            out.println("automated_text result=" + answer.result().name().toLowerCase(Locale.ROOT) + " message="
                    + answer.messageId() + " action=" + answer.actionId());
        }
    }

    private void webhookTwice(List<String> options) {
        ToolArguments arguments =
                ToolArguments.parse(options, "url", "phone-number-id", "waba-id", "from", "text", "hours-ago");
        URI url;
        try {
            url = URI.create(arguments.require("url"));
        } catch (IllegalArgumentException malformed) {
            throw new LocalToolException("url_invalid", "--url is not a web address. Nothing was sent.");
        }
        PostSignedWebhookTwice.checked(url);
        String from = arguments.get("from").orElseGet(GateTool::syntheticNumber);
        PostSignedWebhookTwice.Message message = new PostSignedWebhookTwice.Message(
                arguments.require("phone-number-id"),
                arguments.require("waba-id"),
                from,
                arguments.get("text").orElse(PostSignedWebhookTwice.DEFAULT_TEXT),
                arguments.integer("hours-ago", 0));
        byte[] secret = LocalEnv.load(envFile).require(LocalEnv.APP_SECRET).getBytes(StandardCharsets.UTF_8);
        try {
            PostSignedWebhookTwice.Answers answers = new PostSignedWebhookTwice().postTwice(url, message, secret);
            out.println("webhook_twice first=" + answers.first() + " second=" + answers.second() + " from=" + from
                    + " provider_event_id=message:" + answers.wamid());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /** A fresh synthetic sender, so a run does not join an earlier run's conversation. */
    private static String syntheticNumber() {
        return "910000%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private LocalEnv localEnv() {
        LocalEnv env = LocalEnv.load(envFile);
        env.requireLocal();
        return env;
    }

    private ConfigurableApplicationContext start(LocalEnv env) {
        return adjust.apply(LocalAppContext.application(env)).run();
    }

    /** The kinds of failure, outermost first, with the database's code: never a message, which can quote values. */
    private static String kinds(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!text.isEmpty()) text.append(" <- ");
            text.append(cause.getClass().getSimpleName());
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                text.append('[').append(sql.getSQLState()).append(']');
            }
            if (cause.getCause() == cause) break;
        }
        return text.toString();
    }
}
