#!/usr/bin/env python3
"""Run a loopback-only upstream frps/frpc fixture for an independent STCP visitor."""

import argparse
from contextlib import ExitStack
import json
import os
from pathlib import Path
import secrets
import socket
import socketserver
import subprocess
import tempfile
import threading
import time


LOOPBACK = "127.0.0.1"
PROXY_NAME = "locus_fixture"
GREETING = b"LOCUS-STCP-FIXTURE\n"


class Greeting(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.sendall(GREETING)


def unused_port():
    with socket.socket() as listener:
        listener.bind((LOOPBACK, 0))
        return listener.getsockname()[1]


def tcp_port(value):
    port = int(value)
    if not 1 <= port <= 65535:
        raise argparse.ArgumentTypeError("expected a TCP port from 1 to 65535")
    return port


def stop_process(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def start_process(stack, directory, binary, name, config):
    path = directory / (name + ".toml")
    path.write_text(config, encoding="utf-8")
    subprocess.run([binary, "verify", "-c", str(path)], capture_output=True,
                   check=True, timeout=10)
    log = stack.enter_context((directory / (name + ".log")).open("w", encoding="utf-8"))
    process = subprocess.Popen([binary, "-c", str(path)], stdout=log, stderr=log,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    stack.callback(stop_process, process)
    return process


def ready(probe, processes):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if any(process.poll() is not None for process in processes):
            raise RuntimeError("an FRP fixture process exited during startup")
        try:
            if probe():
                return
        except OSError:
            pass
        time.sleep(0.1)
    raise TimeoutError("FRP fixture did not become ready")


def read_greeting(port):
    with socket.create_connection((LOOPBACK, port), timeout=2) as connection:
        with connection.makefile("rb") as stream:
            return stream.readline(128)


def server_accepts(port):
    with socket.create_connection((LOOPBACK, port), timeout=1):
        return True


def run(args):
    token = secrets.token_hex(24)
    key = secrets.token_hex(24)
    relay_port = args.relay_port
    kcp = f"kcpBindPort = {relay_port}" if args.protocol == "kcp" else ""
    prefix = b"SSH-2.0-" if args.ssh_port else GREETING
    with ExitStack() as stack:
        directory = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix="locus-stcp-")))
        if args.ssh_port:
            backend_port = args.ssh_port
            if not read_greeting(backend_port).startswith(prefix):
                raise RuntimeError("the loopback backend did not send an SSH identification")
        else:
            backend = stack.enter_context(socketserver.ThreadingTCPServer((LOOPBACK, 0), Greeting))
            threading.Thread(target=backend.serve_forever, daemon=True).start()
            stack.callback(backend.shutdown)
            backend_port = backend.server_address[1]

        relay = start_process(stack, directory, args.frps, "relay", f"""
bindAddr = "{LOOPBACK}"
bindPort = {relay_port}
{kcp}
auth.method = "token"
auth.token = "{token}"
transport.tls.force = true
""")
        ready(lambda: server_accepts(relay_port), (relay,))

        client = f"""
serverAddr = "{LOOPBACK}"
serverPort = {relay_port}
auth.method = "token"
auth.token = "{token}"
transport.tls.enable = true
"""
        provider = start_process(stack, directory, args.frpc, "provider", client + f"""
[[proxies]]
name = "{PROXY_NAME}"
type = "stcp"
secretKey = "{key}"
localIP = "{LOOPBACK}"
localPort = {backend_port}
""")
        visitor_port = unused_port()
        with ExitStack() as reference:
            visitor = start_process(reference, directory, args.frpc, "reference", client + f"""
transport.protocol = "{args.protocol}"

[[visitors]]
name = "locus_reference"
type = "stcp"
serverName = "{PROXY_NAME}"
secretKey = "{key}"
bindAddr = "{LOOPBACK}"
bindPort = {visitor_port}
""")
            ready(lambda: read_greeting(visitor_port).startswith(prefix), (relay, provider, visitor))

        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        with os.fdopen(os.open(args.ready_file, flags, 0o600), "w", encoding="utf-8") as output:
            stack.callback(args.ready_file.unlink, missing_ok=True)
            json.dump({
                "relayHost": LOOPBACK,
                "relayPort": relay_port,
                "relayProtocol": args.protocol,
                "relayToken": token,
                "serverName": PROXY_NAME,
                "secretKey": key,
            }, output)
        print(f"Upstream frpc verified over {args.protocol}. Relay listening on {LOOPBACK}:{relay_port}.",
              flush=True)
        deadline = time.monotonic() + args.hold_seconds if args.hold_seconds else float("inf")
        while time.monotonic() < deadline:
            if relay.poll() is not None or provider.poll() is not None:
                raise RuntimeError("an FRP fixture process exited")
            time.sleep(0.2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frps", default="frps", help="upstream FRP relay executable")
    parser.add_argument("--frpc", default="frpc", help="upstream FRP client executable")
    parser.add_argument("--relay-port", type=tcp_port, required=True)
    parser.add_argument("--protocol", choices=("tcp", "kcp"), default="tcp",
                        help="relay transport used by the reference visitor")
    parser.add_argument("--ssh-port", type=tcp_port, help="use a loopback SSH server instead of the greeting server")
    parser.add_argument("--ready-file", type=Path, required=True, help="new private file for fixture parameters")
    parser.add_argument("--hold-seconds", type=int, help="stop the fixture automatically after this many seconds")
    args = parser.parse_args()
    if args.hold_seconds is not None and args.hold_seconds <= 0:
        parser.error("--hold-seconds must be positive")
    try:
        run(args)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
