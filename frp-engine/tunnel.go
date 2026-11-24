package frp

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/fatedier/frp/client"
	"github.com/fatedier/frp/pkg/config/source"
	v1 "github.com/fatedier/frp/pkg/config/v1"
	visitorplugin "github.com/fatedier/frp/pkg/plugin/visitor"
	frplog "github.com/fatedier/frp/pkg/util/log"
	"github.com/fatedier/golib/log"
)

const (
	pluginName     = "locus_internal_stcp"
	tokenSize      = 32
	startupTimeout = 15 * time.Second
	acceptTimeout  = 10 * time.Second
	tokenTimeout   = 500 * time.Millisecond
)

func init() {
	// Upstream errors may contain relay-supplied strings. Only fixed errors cross into the UI.
	frplog.Logger = frplog.Logger.WithOptions(log.WithOutput(io.Discard))
	visitorplugin.Register(pluginName, newVisitorPlugin)
}

// Tunnel carries one local SSH TCP stream through a single FRP STCP visitor.
type Tunnel struct {
	listener      *net.TCPListener
	cancel        context.CancelFunc
	ready         chan struct{}
	done          chan struct{}
	start         sync.Once
	mu            sync.Mutex
	token         [tokenSize]byte
	conn          *net.TCPConn
	closed        bool
	visitorFailed bool
}

// Start logs into a relay and activates the chosen visitor. Only loopback is bound.
func Start(profile []byte) (*Tunnel, error) {
	selected, err := readSelected(profile)
	if err != nil {
		return nil, err
	}
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return nil, errors.New("Could not open the local FRP transport socket.")
	}
	ctx, cancel := context.WithCancel(context.Background())
	tunnel := &Tunnel{
		listener: listener,
		cancel:   cancel,
		ready:    make(chan struct{}),
		done:     make(chan struct{}),
	}
	if _, err := rand.Read(tunnel.token[:]); err != nil {
		tunnel.Close()
		return nil, errors.New("Could not initialize the FRP transport.")
	}
	service, err := newService(selected, tunnel)
	if err != nil {
		tunnel.Close()
		return nil, errors.New("Could not configure the selected FRP visitor.")
	}
	go func() {
		defer close(tunnel.done)
		defer tunnel.Close()
		_ = service.Run(ctx)
	}()

	timer := time.NewTimer(startupTimeout)
	defer timer.Stop()
	select {
	case <-tunnel.ready:
		if ctx.Err() == nil {
			return tunnel, nil
		}
	case <-tunnel.done:
	case <-timer.C:
		tunnel.Close()
		return nil, errors.New("The FRP relay did not become ready in time.")
	}
	tunnel.Close()
	return nil, errors.New("Could not log in to the FRP relay. Check its address and token.")
}

func newService(profile selectedProfile, tunnel *Tunnel) (*client.Service, error) {
	tls := profile.Transport.TLS
	common := &v1.ClientCommonConfig{
		ServerAddr: profile.ServerAddr,
		ServerPort: profile.ServerPort,
		User:       profile.User,
		Auth: v1.AuthClientConfig{
			Method:           "token",
			Token:            profile.Auth.Token,
			AdditionalScopes: profile.Auth.AdditionalScopes,
		},
		Transport: v1.ClientTransportConfig{
			Protocol:          profile.Transport.Protocol,
			WireProtocol:      profile.Transport.WireProtocol,
			DialServerTimeout: 7,
			TCPMux:            profile.Transport.TCPMux,
			TLS: v1.TLSClientConfig{
				Enable:                    new(true),
				DisableCustomTLSFirstByte: tls.DisableCustomTLSFirstByte,
				TLSConfig: v1.TLSConfig{
					UseSystemCa: tls.UseSystemCa,
					ServerName:  tls.ServerName,
				},
			},
		},
	}
	visitor := &v1.STCPVisitorConfig{VisitorBaseConfig: v1.VisitorBaseConfig{
		Type:       "stcp",
		Name:       profile.Visitor.Name,
		ServerUser: profile.Visitor.ServerUser,
		ServerName: profile.Visitor.ServerName,
		SecretKey:  profile.Visitor.SecretKey,
		BindPort:   -1,
		Transport: v1.VisitorTransport{
			UseEncryption:  profile.Visitor.Transport.UseEncryption,
			UseCompression: profile.Visitor.Transport.UseCompression,
		},
		Plugin: v1.TypedVisitorPluginOptions{
			Type:                 pluginName,
			VisitorPluginOptions: &visitorOptions{tunnel: tunnel},
		},
	}}
	config := source.NewConfigSource()
	if err := config.ReplaceAll(nil, []v1.VisitorConfigurer{visitor}); err != nil {
		return nil, err
	}
	return client.NewService(client.ServiceOptions{
		Common:                 common,
		ConfigSourceAggregator: source.NewAggregator(config),
	})
}

func (t *Tunnel) GetPort() int32 {
	return int32(t.listener.Addr().(*net.TCPAddr).Port)
}

// GetToken returns a copy of the local handoff token. Write it before any SSH bytes.
func (t *Tunnel) GetToken() []byte {
	t.mu.Lock()
	defer t.mu.Unlock()
	return append([]byte(nil), t.token[:]...)
}

// GetFailureHint reports a fixed diagnostic when upstream STCP rejects the stream.
func (t *Tunnel) GetFailureHint() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.visitorFailed {
		return "Locus started the FRP visitor but could not open its route to SSH. Check that the remote STCP proxy is running and that the visitor's target and secret key match."
	}
	return ""
}

func (t *Tunnel) Close() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return
	}
	t.closed = true
	clear(t.token[:])
	t.cancel()
	_ = t.listener.Close()
	if t.conn != nil {
		_ = t.conn.Close()
		t.conn = nil
	}
}

func (t *Tunnel) accept(send func(net.Conn)) {
	defer t.listener.Close()
	end := time.Now().Add(acceptTimeout)
	_ = t.listener.SetDeadline(end)
	for {
		conn, err := t.listener.AcceptTCP()
		if err != nil {
			t.Close()
			return
		}
		if !t.authenticate(conn, end) {
			_ = conn.Close()
			continue
		}
		_ = t.listener.Close()
		send(&visitorStream{TCPConn: conn, tunnel: t})
		return
	}
}

type visitorStream struct {
	*net.TCPConn
	tunnel *Tunnel
}

func (stream *visitorStream) CloseWithError(_ error) error {
	stream.tunnel.mu.Lock()
	stream.tunnel.visitorFailed = true
	stream.tunnel.mu.Unlock()
	return stream.Close()
}

func (t *Tunnel) authenticate(conn *net.TCPConn, end time.Time) bool {
	readDeadline := time.Now().Add(tokenTimeout)
	if readDeadline.After(end) {
		readDeadline = end
	}
	var supplied [tokenSize]byte
	_ = conn.SetReadDeadline(readDeadline)
	_, err := io.ReadFull(conn, supplied[:])
	if err != nil {
		clear(supplied[:])
		return false
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	valid := !t.closed && subtle.ConstantTimeCompare(supplied[:], t.token[:]) == 1
	clear(supplied[:])
	if !valid {
		return false
	}
	clear(t.token[:])
	_ = conn.SetReadDeadline(time.Time{})
	t.conn = conn
	return true
}

type visitorOptions struct {
	tunnel *Tunnel
}

func (o *visitorOptions) Complete() {}

func (o *visitorOptions) Clone() v1.VisitorPluginOptions {
	return &visitorOptions{tunnel: o.tunnel}
}

type visitorPlugin struct {
	tunnel *Tunnel
	send   func(net.Conn)
}

func newVisitorPlugin(ctx visitorplugin.PluginContext, options v1.VisitorPluginOptions) (visitorplugin.Plugin, error) {
	visitor, ok := options.(*visitorOptions)
	if !ok || visitor.tunnel == nil || ctx.SendConnToVisitor == nil {
		return nil, errors.New("Could not initialize the local FRP visitor.")
	}
	return &visitorPlugin{tunnel: visitor.tunnel, send: ctx.SendConnToVisitor}, nil
}

func (p *visitorPlugin) Name() string { return pluginName }

func (p *visitorPlugin) Start() {
	p.tunnel.start.Do(func() {
		go p.tunnel.accept(p.send)
		close(p.tunnel.ready)
	})
}

func (p *visitorPlugin) Close() error {
	p.tunnel.Close()
	return nil
}
