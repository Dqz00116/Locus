package frp

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

const profileFixture = `
serverAddr = "Relay.example"
serverPort = 7443
auth.token = "relay-fixture-secret"
includes = ["/no/locus/config/reads"]

[[visitors]]
name = "ssh"
type = "stcp"
serverName = "linux"
serverUser = "builder"
secretKey = "ssh-fixture-secret"
bindPort = 2222

[[visitors]]
name = "web"
type = "stcp"
serverName = "code-server"
secretKey = "unrelated-fixture-secret"
bindPort = 8888
`

func TestSelectKeepsOneVisitor(t *testing.T) {
	list, err := Inspect([]byte(profileFixture))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(list, "secret") || !strings.Contains(list, "relay.example:7443/builder.linux") {
		t.Fatal("Unexpected visitor metadata")
	}
	saved, err := Select([]byte(profileFixture), "ssh")
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(saved, []byte("unrelated-fixture-secret")) || bytes.Contains(saved, []byte("includes")) ||
		bytes.Contains(saved, []byte("bindPort")) || !bytes.Contains(saved, []byte("ssh-fixture-secret")) {
		t.Fatal("Selection did not preserve only the chosen visitor")
	}
	one, err := Inspect(saved)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(one, "secret") || strings.Contains(one, "web") ||
		!strings.Contains(one, "relay.example:7443/builder.linux") {
		t.Fatal("Saved visitor metadata is invalid")
	}
}

func TestConfigErrorsDoNotExposeInput(t *testing.T) {
	for _, data := range []string{
		"auth.token = synthetic-invalid-text",
		`{"bogus":"synthetic-invalid-text"}`,
		strings.Replace(profileFixture, "relay-fixture-secret", "{{ .Envs.synthetic-invalid-text }}", 1),
	} {
		_, err := Inspect([]byte(data))
		if err == nil || strings.Contains(err.Error(), "synthetic-invalid-text") {
			t.Fatal("Expected a fixed configuration error")
		}
	}
}

func TestInspectDistinguishesVisitorAndProxyConfigs(t *testing.T) {
	proxy := `
[[proxies]]
name = "private-proxy-name"
type = "stcp"
localPort = 22
secretKey = "proxy-fixture-secret"
`
	for _, test := range []struct {
		config, hint, hidden string
	}{
		{proxy, "defines FRP proxies", "private-proxy-name"},
		{`includes = ["/private/include/path"]`, "self-contained FRP TOML", "/private/include/path"},
		{`serverAddr = "relay.example"`, "client TOML", "relay.example"},
	} {
		_, err := Inspect([]byte(test.config))
		if err == nil || !strings.Contains(err.Error(), test.hint) ||
			!strings.Contains(err.Error(), "[[visitors]]") || strings.Contains(err.Error(), test.hidden) {
			t.Fatal("Expected a role-specific import error without file contents")
		}
	}
	mixed := []byte(profileFixture + proxy)
	list, err := Inspect(mixed)
	if err != nil {
		t.Fatal(err)
	}
	var visitors []visitorSummary
	if json.Unmarshal([]byte(list), &visitors) != nil || len(visitors) != 2 ||
		!visitors[0].HasProxyDefinitions || visitors[0].LegacyLoopbackPort != 2222 ||
		strings.Contains(list, "private-proxy-name") || strings.Contains(list, "proxy-fixture-secret") {
		t.Fatal("Import should identify the mixed config without disclosing proxy settings")
	}
	saved, err := Select(mixed, "ssh")
	if err != nil {
		t.Fatal(err)
	}
	defer clear(saved)
	list, err = Inspect(saved)
	if err != nil {
		t.Fatal(err)
	}
	visitors = nil
	if json.Unmarshal([]byte(list), &visitors) != nil || len(visitors) != 1 ||
		visitors[0].HasProxyDefinitions || visitors[0].LegacyLoopbackPort != 2222 ||
		bytes.Contains(saved, []byte("proxy-fixture-secret")) {
		t.Fatal("The saved visitor should retain only its loopback matching hint")
	}
}

func TestLegacyLoopbackPortHint(t *testing.T) {
	for _, test := range []struct {
		address    string
		port, want int
	}{
		{"", 2222, 2222},
		{"localhost", 2222, 2222},
		{"127.0.0.1", 2222, 2222},
		{"[::1]", 2222, 2222},
		{"0.0.0.0", 2222, 2222},
		{"::", 2222, 2222},
		{"192.0.2.1", 2222, 0},
		{"remote.example", 2222, 0},
		{"127.0.0.1", 0, 0},
		{"127.0.0.1", -1, 0},
		{"127.0.0.1", 65536, 0},
	} {
		if got := loopbackBindPort(test.address, test.port); got != test.want {
			t.Errorf("loopbackBindPort(%q, %d) = %d, want %d", test.address, test.port, got, test.want)
		}
	}
}

func TestRelayProtocolSelection(t *testing.T) {
	for _, protocol := range []string{"", "tcp", "kcp"} {
		t.Run(protocol, func(t *testing.T) {
			toml := profileFixture
			if protocol != "" {
				toml = fmt.Sprintf("transport.protocol = %q\n%s", protocol, toml)
			}
			saved, err := Select([]byte(toml), "ssh")
			if err != nil {
				t.Fatal(err)
			}
			defer clear(saved)
			decoded, err := readSelected(saved)
			if err != nil {
				t.Fatal(err)
			}
			if decoded.Transport.Protocol != protocol {
				t.Fatal("Selected profile did not preserve the relay protocol")
			}
		})
	}
	for _, test := range []struct {
		config    string
		wantField string
	}{
		{"transport.protocol = \"websocket\"\n", "transport.protocol"},
		{"transport.wireProtocol = \"other\"\n", "transport.wireProtocol"},
		{"transport.proxyURL = \"https://fixture-key@example.org\"\n", "transport.proxyURL"},
	} {
		_, err := Inspect([]byte(test.config + profileFixture))
		if err == nil || !strings.Contains(err.Error(), test.wantField) ||
			strings.Contains(err.Error(), "fixture-key") {
			t.Fatal("Expected a relay field error without imported data")
		}
	}
}

func TestLoopbackRequiresToken(t *testing.T) {
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	_, cancel := context.WithCancel(context.Background())
	tunnel := &Tunnel{listener: listener, cancel: cancel}
	defer tunnel.Close()
	if _, err := rand.Read(tunnel.token[:]); err != nil {
		t.Fatal(err)
	}
	accepted := make(chan net.Conn, 1)
	go tunnel.accept(func(conn net.Conn) { accepted <- conn })
	bad, err := net.Dial("tcp4", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	_ = bad.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err := bad.Write(bytes.Repeat([]byte{1}, tokenSize)); err != nil {
		t.Fatal(err)
	}
	if _, err := bad.Read(make([]byte, 1)); err != io.EOF {
		t.Fatal("Unauthorized stream was not rejected")
	}
	bad.Close()
	good, err := net.Dial("tcp4", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer good.Close()
	if _, err := good.Write(append(tunnel.GetToken(), []byte("SSH")...)); err != nil {
		t.Fatal(err)
	}
	select {
	case server := <-accepted:
		_ = server.SetReadDeadline(time.Now().Add(time.Second))
		data := make([]byte, 3)
		if _, err := io.ReadFull(server, data); err != nil || string(data) != "SSH" {
			t.Fatal("The handoff ate bytes from the SSH stream")
		}
		if token := tunnel.GetToken(); !bytes.Equal(token, make([]byte, tokenSize)) {
			t.Fatal("The handoff token was not cleared")
		}
		stream, ok := server.(interface{ CloseWithError(error) error })
		if tunnel.GetFailureHint() != "" || !ok {
			t.Fatal("Expected an error-free STCP stream that can report visitor failures")
		}
		if err := stream.CloseWithError(fmt.Errorf("upstream-fixture-secret")); err != nil {
			t.Fatal(err)
		}
		if hint := tunnel.GetFailureHint(); hint == "" || strings.Contains(hint, "upstream-fixture-secret") {
			t.Fatal("Expected a sanitized STCP failure hint")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Authorized stream was not handed to the visitor")
	}
}

// Run against a disposable FRP relay and STCP provider that route to a real SSH service.
func TestSTCPLocalFixture(t *testing.T) {
	fixturePath := os.Getenv("LOCUS_FRP_TEST_FIXTURE")
	if fixturePath == "" {
		t.Skip("LOCUS_FRP_TEST_FIXTURE is unset")
	}
	data, err := os.ReadFile(fixturePath)
	if err != nil {
		t.Fatal("Could not open the fixture configuration")
	}
	var fixture struct {
		RelayHost     string
		RelayPort     int
		RelayToken    string
		RelayProtocol string
		ServerName    string
		SecretKey     string
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal("Could not parse the fixture configuration")
	}
	// Hold the imported bindPort open while the embedded visitor connects using its own socket.
	oldListener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer oldListener.Close()
	legacyPort := oldListener.Addr().(*net.TCPAddr).Port
	for _, test := range []struct {
		name, key string
		wantError bool
	}{
		{"opens SSH", fixture.SecretKey, false},
		{"STCP rejection", fixture.SecretKey + "-invalid", true},
	} {
		t.Run(test.name, func(t *testing.T) {
			document := fmt.Sprintf("serverAddr = %q\nserverPort = %d\nauth.token = %q\ntransport.protocol = %q\n"+
				"[[visitors]]\nname = \"ssh\"\ntype = \"stcp\"\nserverName = %q\nsecretKey = %q\nbindPort = %d\n",
				fixture.RelayHost, fixture.RelayPort, fixture.RelayToken, fixture.RelayProtocol,
				fixture.ServerName, test.key, legacyPort)
			selected, err := Select([]byte(document), "ssh")
			if err != nil {
				t.Fatal(err)
			}
			defer clear(selected)
			tunnel, err := Start(selected)
			if err != nil {
				t.Fatal(err)
			}
			defer tunnel.Close()
			if int(tunnel.GetPort()) == legacyPort {
				t.Fatal("The embedded visitor used the imported local port")
			}
			conn, err := net.Dial("tcp4", net.JoinHostPort("127.0.0.1", strconv.Itoa(int(tunnel.GetPort()))))
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
			token := tunnel.GetToken()
			_, err = conn.Write(token)
			clear(token)
			if err != nil {
				t.Fatal(err)
			}
			banner := make([]byte, len("SSH-2.0-OpenSSH_"))
			_, err = io.ReadFull(conn, banner)
			if test.wantError {
				if err == nil || tunnel.GetFailureHint() == "" {
					t.Fatal("Expected a route failure diagnostic after STCP rejected the connection")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if string(banner) != "SSH-2.0-OpenSSH_" || tunnel.GetFailureHint() != "" {
				t.Fatal("The connected backend did not identify itself as OpenSSH")
			}
		})
	}
}
