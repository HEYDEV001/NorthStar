package com.northstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests never reach the network (step 1.11): Surefire runs them with a hosts file that knows only localhost, so a
 * provider's host name does not resolve. Literal IP addresses are not blocked; adapters use host names.
 */
class NetworkGuardTests {

    @Test
    void theGuardIsOn() {
        assertThat(System.getProperty("jdk.net.hosts.file"))
                .as("run through Maven: pom.xml adds -Djdk.net.hosts.file to Surefire's argLine")
                .endsWith("offline-hosts");
    }

    @Test
    void providerHostsDoNotResolve() {
        for (String host : List.of(
                "graph.facebook.com", "api.razorpay.com", "oauth2.googleapis.com", "api.resend.com", "example.com")) {
            assertThatThrownBy(() -> InetAddress.getByName(host)).as(host).isInstanceOf(UnknownHostException.class);
        }
    }

    @Test
    void localhostStillResolves() throws UnknownHostException {
        assertThat(InetAddress.getByName("localhost").isLoopbackAddress()).isTrue();
    }
}
