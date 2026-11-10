package com.ai.orange.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** A request from the run network's subnets is a run's; a run may ask for /mcp/worker and nothing else. */
class RunNetworkGuardTest {

    static RunNetworkGuard guard(String... subnets) {
        return new RunNetworkGuard(() -> List.of(subnets));
    }

    static MockHttpServletRequest request(String remoteAddr, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void anAddressInsideTheSubnetIsARuns() {
        RunNetworkGuard guard = guard("172.18.0.0/16");

        assertThat(guard.fromRunNetwork("172.18.0.2")).isTrue();
        assertThat(guard.fromRunNetwork("172.18.255.254")).isTrue();
        assertThat(guard.fromRunNetwork("172.18.0.1")).as("the bridge's own address").isTrue();
    }

    @Test
    void anAddressOutsideTheSubnetIsNot() {
        RunNetworkGuard guard = guard("172.18.0.0/16");

        assertThat(guard.fromRunNetwork("127.0.0.1")).isFalse();
        assertThat(guard.fromRunNetwork("172.17.0.2")).as("Docker's default bridge").isFalse();
        assertThat(guard.fromRunNetwork("172.19.0.2")).isFalse();
        assertThat(guard.fromRunNetwork("192.168.1.10")).isFalse();
    }

    @Test
    void aPrefixThatEndsInsideAByteIsCompared() {
        RunNetworkGuard guard = guard("10.20.64.0/18");

        assertThat(guard.fromRunNetwork("10.20.64.1")).isTrue();
        assertThat(guard.fromRunNetwork("10.20.127.255")).isTrue();
        assertThat(guard.fromRunNetwork("10.20.128.0")).isFalse();
        assertThat(guard.fromRunNetwork("10.20.63.255")).isFalse();
        assertThat(guard("10.20.64.7/32").fromRunNetwork("10.20.64.7")).isTrue();
        assertThat(guard("10.20.64.7/32").fromRunNetwork("10.20.64.8")).isFalse();
    }

    @Test
    void ipv6SubnetsWork() {
        RunNetworkGuard guard = guard("172.18.0.0/16", "fd00:dead:beef::/48");

        assertThat(guard.fromRunNetwork("fd00:dead:beef::2")).isTrue();
        assertThat(guard.fromRunNetwork("fd00:dead:beef:1:2:3:4:5")).isTrue();
        assertThat(guard.fromRunNetwork("fd00:dead:bee0::2")).isFalse();
        assertThat(guard.fromRunNetwork("::1")).isFalse();
        assertThat(guard.fromRunNetwork("0:0:0:0:0:0:0:1")).isFalse();
        // The IPv4 subnet still counts next to it.
        assertThat(guard.fromRunNetwork("172.18.0.2")).isTrue();
    }

    @Test
    void anIpv4AddressWrittenAsIpv6IsTheSameAddress() {
        RunNetworkGuard guard = guard("172.18.0.0/16");

        assertThat(guard.fromRunNetwork("::ffff:172.18.0.2")).isTrue();
        assertThat(guard.fromRunNetwork("::ffff:127.0.0.1")).isFalse();
    }

    @Test
    void withoutSubnetsNothingIsARuns() {
        assertThat(guard().fromRunNetwork("172.18.0.2")).isFalse();
        assertThat(guard().fromRunNetwork("127.0.0.1")).isFalse();
    }

    @Test
    void anAddressThatDoesNotParseIsNotARuns() {
        RunNetworkGuard guard = guard("172.18.0.0/16", "0.0.0.0/0");

        assertThat(guard.fromRunNetwork(null)).isFalse();
        assertThat(guard.fromRunNetwork("")).isFalse();
        assertThat(guard.fromRunNetwork("unknown")).isFalse();
        // A name is never looked up: it is not an address.
        assertThat(guard.fromRunNetwork("localhost")).isFalse();
        assertThat(guard.fromRunNetwork("172.18.0")).isFalse();
        assertThat(guard.fromRunNetwork("172.18.0.2.3")).isFalse();
        assertThat(guard.fromRunNetwork("172.18.0.256")).isFalse();
        assertThat(guard.fromRunNetwork("2886860802")).as("172.18.0.2 as one number").isFalse();
        assertThat(guard.fromRunNetwork("172.18.0.2:8080")).isFalse();
        assertThat(guard.fromRunNetwork("fd00::zz")).isFalse();
    }

    @Test
    void aSubnetThatDoesNotParseIsLeftOutAndTheOthersCount() {
        RunNetworkGuard guard = guard("not-a-subnet", "172.18.0.0", "172.18.0.0/33", "172.18.0.0/x", "10.9.0.0/24");

        assertThat(guard.fromRunNetwork("172.18.0.2")).isFalse();
        assertThat(guard.fromRunNetwork("10.9.0.2")).isTrue();
    }

    @Test
    void theSubnetsAreReadAsTheyAreNow() {
        List<String> subnets = new ArrayList<>();
        RunNetworkGuard guard = new RunNetworkGuard(() -> List.copyOf(subnets));
        assertThat(guard.fromRunNetwork("172.18.0.2")).isFalse();

        // The network is looked at when the first container starts, or created anew with another range.
        subnets.add("172.18.0.0/16");
        assertThat(guard.fromRunNetwork("172.18.0.2")).isTrue();

        subnets.set(0, "172.30.0.0/16");
        assertThat(guard.fromRunNetwork("172.18.0.2")).isFalse();
        assertThat(guard.fromRunNetwork("172.30.0.2")).isTrue();
    }

    @Test
    void aRunMayAskForTheWorkerMcpAndNothingElse() {
        RunNetworkGuard guard = guard("172.18.0.0/16");

        assertThat(guard.refuses(request("172.18.0.2", "/mcp/worker"))).isFalse();
        assertThat(guard.refuses(request("172.18.0.2", "/tasks"))).isTrue();
        assertThat(guard.refuses(request("172.18.0.2", "/scheduler/resume"))).isTrue();
        assertThat(guard.refuses(request("172.18.0.2", "/mcp/steering"))).isTrue();
        assertThat(guard.refuses(request("172.18.0.2", "/sessions/events"))).isTrue();
        assertThat(guard.refuses(request("172.18.0.2", "/"))).isTrue();
    }

    @Test
    void anyOtherSpellingOfThePathIsRefused() {
        RunNetworkGuard guard = guard("172.18.0.0/16");

        for (String uri : List.of("/mcp/worker/", "/mcp/worker/../../tasks", "/mcp//worker", "/MCP/worker",
                "/mcp/worker;x=1", "/mcp/%77orker", "/mcp/worker%2f..%2f..%2ftasks", "//mcp/worker", "/tasks/../mcp/worker")) {
            assertThat(guard.refuses(request("172.18.0.2", uri))).as(uri).isTrue();
        }
        // A query string is not part of the path.
        MockHttpServletRequest withQuery = request("172.18.0.2", "/mcp/worker");
        withQuery.setQueryString("x=/tasks");
        assertThat(guard.refuses(withQuery)).isFalse();
    }

    @Test
    void whileTheRunNetworkIsUnknownOnlyThisHostAndTheWorkerMcpGetThrough() {
        // Container mode, and docker has not answered yet: a container that outlived the previous
        // coordinator may be calling, and nobody can say from which address.
        AtomicReference<List<String>> subnets = new AtomicReference<>();
        RunNetworkGuard guard = new RunNetworkGuard(subnets::get);

        for (String anyone : List.of("172.18.0.2", "192.168.1.10", "10.0.0.7", "fd00:1::2", "not-an-address", "")) {
            assertThat(guard.refusal(request(anyone, "/tasks"))).as(anyone).isEqualTo(RunNetworkGuard.NETWORK_UNKNOWN);
            assertThat(guard.refuses(request(anyone, "/scheduler/resume"))).as(anyone).isTrue();
            assertThat(guard.refuses(request(anyone, "/mcp/worker/"))).as(anyone).isTrue();
            // A run's own endpoint asks for the run's token behind the guard.
            assertThat(guard.refuses(request(anyone, "/mcp/worker"))).as(anyone).isFalse();
        }
        for (String thisHost : List.of("127.0.0.1", "127.0.0.53", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1")) {
            assertThat(guard.refuses(request(thisHost, "/tasks"))).as(thisHost).isFalse();
        }
        assertThat(guard.fromRunNetwork("172.18.0.2")).isFalse();

        // Docker answered: a run is refused for what it is, and everyone else gets through again.
        subnets.set(List.of("172.18.0.0/16"));
        assertThat(guard.refusal(request("172.18.0.2", "/tasks"))).isEqualTo(RunNetworkGuard.ONLY_THE_WORKER_MCP);
        assertThat(guard.refusal(request("192.168.1.10", "/tasks"))).isNull();
        assertThat(guard.refusal(request("127.0.0.1", "/tasks"))).isNull();
    }

    @Test
    void everyoneElseIsLeftToTheRulesBehindIt() {
        RunNetworkGuard guard = guard("172.18.0.0/16");

        assertThat(guard.refuses(request("127.0.0.1", "/tasks"))).isFalse();
        assertThat(guard.refuses(request("127.0.0.1", "/mcp/worker"))).isFalse();
        assertThat(guard.refuses(request("192.168.1.10", "/scheduler/resume"))).isFalse();
        // Process mode: the network is never looked at.
        assertThat(guard().refuses(request("172.18.0.2", "/tasks"))).isFalse();
    }
}
