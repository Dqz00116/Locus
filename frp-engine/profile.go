package frp

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"unicode"

	v1 "github.com/fatedier/frp/pkg/config/v1"
	"github.com/fatedier/frp/pkg/naming"
	"github.com/pelletier/go-toml/v2"
)

const maxProfileBytes = 64 * 1024

type relayConfig struct {
	ServerAddr string `toml:"serverAddr" json:"serverAddr"`
	ServerPort int    `toml:"serverPort" json:"serverPort"`
	User       string `toml:"user" json:"user,omitempty"`
	Auth       struct {
		Method           string         `toml:"method" json:"method,omitempty"`
		Token            string         `toml:"token" json:"token,omitempty"`
		TokenSource      map[string]any `toml:"tokenSource" json:"-"`
		AdditionalScopes []v1.AuthScope `toml:"additionalScopes" json:"additionalScopes,omitempty"`
	} `toml:"auth" json:"auth"`
	Transport struct {
		Protocol     string `toml:"protocol" json:"protocol,omitempty"`
		WireProtocol string `toml:"wireProtocol" json:"wireProtocol,omitempty"`
		TCPMux       *bool  `toml:"tcpMux" json:"tcpMux,omitempty"`
		ProxyURL     string `toml:"proxyURL" json:"-"`
		TLS          struct {
			Enable                    *bool  `toml:"enable" json:"enable,omitempty"`
			DisableCustomTLSFirstByte *bool  `toml:"disableCustomTLSFirstByte" json:"disableCustomTLSFirstByte,omitempty"`
			UseSystemCa               bool   `toml:"useSystemCa" json:"useSystemCa,omitempty"`
			ServerName                string `toml:"serverName" json:"serverName,omitempty"`
			CertFile                  string `toml:"certFile" json:"-"`
			KeyFile                   string `toml:"keyFile" json:"-"`
			TrustedCaFile             string `toml:"trustedCaFile" json:"-"`
		} `toml:"tls" json:"tls"`
	} `toml:"transport" json:"transport"`
}

type visitorConfig struct {
	Name       string `toml:"name" json:"name"`
	Type       string `toml:"type" json:"type"`
	Enabled    *bool  `toml:"enabled" json:"-"`
	ServerUser string `toml:"serverUser" json:"serverUser,omitempty"`
	ServerName string `toml:"serverName" json:"serverName"`
	SecretKey  string `toml:"secretKey" json:"secretKey"`
	BindAddr   string `toml:"bindAddr" json:"-"`
	BindPort   int    `toml:"bindPort" json:"-"`
	// Retained for matching an old SSH loopback setting. The embedded visitor never binds this port.
	LegacyLoopbackPort int `toml:"-" json:"legacyLoopbackPort,omitempty"`
	Transport          struct {
		UseEncryption  bool `toml:"useEncryption" json:"useEncryption,omitempty"`
		UseCompression bool `toml:"useCompression" json:"useCompression,omitempty"`
	} `toml:"transport" json:"transport"`
	Plugin map[string]any `toml:"plugin" json:"-"`
}

type importedProfile struct {
	relayConfig
	Visitors []visitorConfig `toml:"visitors"`
	Proxies  []struct{}      `toml:"proxies"`
	Includes []string        `toml:"includes"`
	Start    []string        `toml:"start"`
}

type selectedProfile struct {
	relayConfig
	Visitor visitorConfig `json:"visitor"`
}

type visitorSummary struct {
	Name                string `json:"name"`
	Label               string `json:"label"`
	Identity            string `json:"identity"`
	LegacyLoopbackPort  int    `json:"legacyLoopbackPort,omitempty"`
	HasProxyDefinitions bool   `json:"hasProxyDefinitions,omitempty"`
}

// Inspect returns non-secret metadata for enabled STCP visitors in a TOML or selected profile.
func Inspect(profile []byte) (string, error) {
	if len(profile) > maxProfileBytes {
		return "", errors.New("Choose an FRP file no larger than 64 KiB.")
	}
	profile = bytes.TrimSpace(profile)
	summaries := make([]visitorSummary, 0)
	if len(profile) > 0 && profile[0] == '{' {
		selected, err := readSelected(profile)
		if err != nil {
			return "", err
		}
		summaries = append(summaries, summarize(selected.relayConfig, selected.Visitor, false))
	} else {
		imported, err := readImported(profile)
		if err != nil {
			return "", err
		}
		for _, visitor := range imported.Visitors {
			summaries = append(summaries, summarize(imported.relayConfig, visitor, len(imported.Proxies) != 0))
		}
	}
	data, err := json.Marshal(summaries)
	return string(data), err
}

// Select normalizes a single visitor so its siblings and unrelated TOML fields are discarded.
func Select(profile []byte, name string) ([]byte, error) {
	imported, err := readImported(profile)
	if err != nil {
		return nil, err
	}
	for _, visitor := range imported.Visitors {
		if visitor.Name == name {
			return json.Marshal(selectedProfile{relayConfig: imported.relayConfig, Visitor: visitor})
		}
	}
	return nil, errors.New("The selected STCP visitor was not found.")
}

func summarize(relay relayConfig, visitor visitorConfig, hasProxyDefinitions bool) visitorSummary {
	address := net.JoinHostPort(relay.ServerAddr, strconv.Itoa(relay.ServerPort))
	target := naming.BuildTargetServerProxyName(relay.User, visitor.ServerUser, visitor.ServerName)
	return visitorSummary{
		Name:                visitor.Name,
		Label:               visitor.Name + " · " + address,
		Identity:            "frp-stcp://" + address + "/" + url.PathEscape(target),
		LegacyLoopbackPort:  visitor.LegacyLoopbackPort,
		HasProxyDefinitions: hasProxyDefinitions,
	}
}

func readImported(data []byte) (importedProfile, error) {
	var profile importedProfile
	if len(data) == 0 || len(data) > maxProfileBytes {
		return profile, errors.New("Choose a nonempty FRP TOML file no larger than 64 KiB.")
	}
	if err := toml.Unmarshal(data, &profile); err != nil {
		return profile, errors.New("Could not parse the FRP TOML file.")
	}
	if len(profile.Visitors) == 0 {
		if len(profile.Proxies) != 0 {
			return profile, errors.New("This file defines FRP proxies. Choose the client TOML with an STCP entry under [[visitors]].")
		}
		if len(profile.Includes) != 0 {
			return profile, errors.New("Choose a self-contained FRP TOML with the relay settings and its STCP [[visitors]] entry.")
		}
		return profile, errors.New("Choose the FRP client TOML with an STCP entry under [[visitors]].")
	}
	if err := profile.relayConfig.validate(); err != nil {
		return profile, err
	}
	enabled := profile.Visitors[:0]
	seen := make(map[string]bool)
	for _, visitor := range profile.Visitors {
		if visitor.Type != "stcp" || visitor.Enabled != nil && !*visitor.Enabled ||
			len(profile.Start) > 0 && !slices.Contains(profile.Start, visitor.Name) {
			continue
		}
		if err := visitor.validate(); err != nil {
			return profile, err
		}
		if seen[visitor.Name] {
			return profile, errors.New("FRP visitor names must be unique.")
		}
		seen[visitor.Name] = true
		visitor.LegacyLoopbackPort = loopbackBindPort(visitor.BindAddr, visitor.BindPort)
		enabled = append(enabled, visitor)
	}
	profile.Visitors = enabled
	if len(enabled) == 0 {
		return profile, errors.New("The FRP file contains no enabled STCP visitor.")
	}
	return profile, nil
}

func loopbackBindPort(address string, port int) int {
	if port < 1 || port > 65535 {
		return 0
	}
	address = strings.TrimSpace(address)
	if address == "" || strings.EqualFold(address, "localhost") {
		return port
	}
	ip := net.ParseIP(strings.Trim(address, "[]"))
	if ip != nil && (ip.IsLoopback() || ip.IsUnspecified()) {
		return port
	}
	return 0
}

func readSelected(data []byte) (selectedProfile, error) {
	var profile selectedProfile
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if len(data) == 0 || len(data) > maxProfileBytes || decoder.Decode(&profile) != nil ||
		decoder.Decode(new(any)) != io.EOF {
		return profile, errors.New("Could not read the saved FRP visitor.")
	}
	if err := profile.relayConfig.validate(); err != nil {
		return profile, err
	}
	return profile, profile.Visitor.validate()
}

func (relay *relayConfig) validate() error {
	relay.ServerAddr = strings.ToLower(strings.TrimSpace(relay.ServerAddr))
	if strings.HasPrefix(relay.ServerAddr, "[") && strings.HasSuffix(relay.ServerAddr, "]") {
		relay.ServerAddr = relay.ServerAddr[1 : len(relay.ServerAddr)-1]
	}
	if relay.ServerPort == 0 {
		relay.ServerPort = 7000
	}
	if !validHost(relay.ServerAddr) || relay.ServerPort < 1 || relay.ServerPort > 65535 ||
		!validName(relay.User, true) {
		return errors.New("Enter a valid FRP relay address, port and user.")
	}
	if (relay.Auth.Method != "" && relay.Auth.Method != "token") || relay.Auth.TokenSource != nil {
		return errors.New("Use an FRP token specified directly in the TOML file.")
	}
	if len(relay.Auth.Token) > 4096 || strings.Contains(relay.Auth.Token, "{{") {
		return errors.New("The FRP token must be a literal value no longer than 4096 bytes.")
	}
	for _, scope := range relay.Auth.AdditionalScopes {
		if scope != v1.AuthScopeHeartBeats && scope != v1.AuthScopeNewWorkConns {
			return errors.New("The FRP file contains an unsupported authentication scope.")
		}
	}
	transport := relay.Transport
	if transport.Protocol != "" && transport.Protocol != "tcp" && transport.Protocol != "kcp" {
		return errors.New("Choose TCP or KCP for FRP transport.protocol.")
	}
	if transport.WireProtocol != "" && transport.WireProtocol != "v1" && transport.WireProtocol != "v2" {
		return errors.New("Choose v1 or v2 for FRP transport.wireProtocol.")
	}
	if transport.ProxyURL != "" {
		return errors.New("This FRP profile requires transport.proxyURL, which Locus does not support.")
	}
	tls := transport.TLS
	if tls.Enable != nil && !*tls.Enable {
		return errors.New("Enable TLS for the FRP relay.")
	}
	if tls.CertFile != "" || tls.KeyFile != "" || tls.TrustedCaFile != "" {
		return errors.New("FRP imports support TLS with system trust instead of certificate file paths.")
	}
	if tls.ServerName != "" && !validHost(tls.ServerName) {
		return errors.New("Enter a valid FRP TLS server name.")
	}
	return nil
}

func (visitor visitorConfig) validate() error {
	if visitor.Type != "stcp" || !validName(visitor.Name, false) ||
		!validName(visitor.ServerName, false) || !validName(visitor.ServerUser, true) {
		return errors.New("Each STCP visitor needs a valid name and serverName.")
	}
	if visitor.Plugin != nil {
		return errors.New("Locus supports regular STCP visitors without FRP plugins.")
	}
	if visitor.SecretKey == "" || len(visitor.SecretKey) > 4096 ||
		strings.Contains(visitor.SecretKey, "{{") {
		return errors.New("Each STCP visitor needs a literal secretKey no longer than 4096 bytes.")
	}
	if visitor.LegacyLoopbackPort < 0 || visitor.LegacyLoopbackPort > 65535 {
		return errors.New("The saved FRP visitor has an invalid local port hint.")
	}
	return nil
}

func validName(name string, optional bool) bool {
	return (optional || name != "") && len(name) <= 128 && name == strings.TrimSpace(name) &&
		!strings.ContainsAny(name, "{}") && strings.IndexFunc(name, unicode.IsControl) < 0
}

func validHost(host string) bool {
	if net.ParseIP(host) != nil {
		return true
	}
	return host != "" && len(host) <= 253 && strings.IndexFunc(host, func(ch rune) bool {
		return !(ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' ||
			ch >= '0' && ch <= '9' || ch == '.' || ch == '-' || ch == '_')
	}) < 0
}
