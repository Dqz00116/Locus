package dev.locus.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Checks the metadata-only SSH config importer with disposable values. */
public final class OpenSshConfigSmoke {
    public static void main(String[] args) throws Exception {
        String config = "\ufeffInclude other-config\r\n"
                + "PORT = 2222\r\n"
                + "Host dev-code Dev-Alt\r\n"
                + "    HostName=\"127.0.0.1\" # user-selected FRP target\r\n"
                + "    User \"developer\"\r\n"
                + "    IdentityFile ~/.ssh/id_work\r\n"
                + "    ProxyCommand command-with-unterminated-value \"\r\n"
                + "Match exec \"privileged command\"\r\n"
                + "    HostName ignored.example\r\n"
                + "    User ignored\r\n"
                + "Host dev-* !dev-alt\r\n"
                + "    User wildcard\r\n"
                + "Host DEV-CODE\r\n"
                + "    HostName ignored-too.example\r\n"
                + "Host another\r\n"
                + "    Port 2223\r\n"
                + "Host *\r\n"
                + "    User fallback\r\n";
        List<OpenSshConfig.Entry> entries =
                OpenSshConfig.parse(config.getBytes(StandardCharsets.UTF_8));
        check(entries.size() == 3, "Explicit aliases could not be resolved");
        checkEntry(entries.get(0), "dev-code", "127.0.0.1", 2222, "developer");
        checkEntry(entries.get(1), "Dev-Alt", "127.0.0.1", 2222, "developer");
        checkEntry(entries.get(2), "another", "another", 2222, "fallback");
        check(entries.get(0).requiresProxy && entries.get(1).requiresProxy
                && !entries.get(2).requiresProxy, "Proxy requirement was not detected");

        String proxies = "Host deploy\n ProxyJump none\n ProxyCommand some-command\n"
                + "Host backup\n ProxyJump bastion\nHost *\n ProxyCommand other-command\n";
        entries = OpenSshConfig.parse(proxies.getBytes(StandardCharsets.UTF_8));
        check(!entries.get(0).requiresProxy && entries.get(1).requiresProxy,
                "Proxy directives did not preserve first-value precedence");

        String defaults = "Host lab1 lab2\nHost lab? !lab2\n User team\nHost *\n Port 2200\n";
        entries = OpenSshConfig.parse(defaults.getBytes(StandardCharsets.UTF_8));
        checkEntry(entries.get(0), "lab1", "lab1", 2200, "team");
        checkEntry(entries.get(1), "lab2", "lab2", 2200, null);
        entries = OpenSshConfig.parse("Host direct".getBytes(StandardCharsets.UTF_8));
        checkEntry(entries.get(0), "direct", "direct", 22, null);

        expectFailure("Host h\nPort synthetic-secret".getBytes(StandardCharsets.UTF_8));
        expectFailure("Host *\nUser fallback".getBytes(StandardCharsets.UTF_8));
        expectFailure(new byte[] {(byte) 0xff});
        expectFailure(new byte[OpenSshConfig.MAX_BYTES + 1]);
        System.out.println("PASS SSH alias import, precedence, safe directives and input bounds");
    }

    private static void checkEntry(OpenSshConfig.Entry entry, String alias, String hostName,
                                   int port, String username) {
        check(alias.equals(entry.alias), "Unexpected alias");
        check(hostName.equals(entry.hostName), "Unexpected hostname");
        check(port == entry.port, "Unexpected port");
        check(java.util.Objects.equals(username, entry.username), "Unexpected username");
    }

    private static void expectFailure(byte[] data) throws Exception {
        try {
            OpenSshConfig.parse(data);
            throw new AssertionError("An invalid config was accepted");
        } catch (IOException expected) {
            check(!expected.getMessage().contains("synthetic-secret"), "Input was exposed in the error");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
