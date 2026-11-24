# AGENTS.md

## Mission
Build an Android tablet thin client for remote Linux work. The two essential jobs are interacting with arbitrary remote CLI/TUI applications (including OpenCode) and conveniently browsing remote files. Read DESIGN.md before implementation.

## Non-negotiable constraints
- Remote First: all development workloads run remotely; local input, rendering, caching and transport are allowed. No local Linux distribution.
- Directory First: the user opens a terminal and explicitly chooses a remote working directory, then acts. Do not require project or agent selection.
- Semantic Workspace: initially the chosen directory; users may explicitly add other roots. Ordinary subsequent `cd` must not silently rewrite the root set.
- Unified Window / Action-driven View: one user-facing window concept; actions determine terminal or file presentation. No Agent/File window type picker.
- Window Equality / Free Docking: no privileged main pane or mandatory sidebar; user-controlled layout.
- User-owned Lifecycle: only explicit user action closes a window. Process exit, disconnect and invalid paths do not close it. Default close policy asks when a live terminal channel is involved; configurable. Never imply that closing a channel kills or preserves an independent agent backend.
- Application Agnostic: treat remote applications as opaque CLI/TUI programs. Do not integrate agent-specific task, history or process management.
- Connection Abstraction: configurable endpoint/transport/authentication boundary; no assumption of FRP, VPN or particular network topology. SSH/SFTP is the first implementation, not a UI dependency.
- Browse First: directory navigation and immediate read-only preview; no full editor or file manager.
- Native Input First: native Android Chinese IME and physical keyboard are P0. No mandatory auxiliary input box or clipboard workaround. Composition stays local; committed text reaches the remote PTY correctly.

## Engineering rules
- KISS/YAGNI. Reuse proven components; do not build a terminal emulator, SSH protocol stack or generic plugin framework from scratch.
- Candidate libraries in DESIGN.md are hypotheses, not approved dependencies. Verify existence, source, license, API, Android compatibility and maintenance before adoption.
- Inspect the existing repository and local SDK/toolchain first. Preserve existing work. Use the repository's conventions where compatible with these constraints.
- Start with a runnable technical spike; test the highest-risk input/rendering path before building full workspace UI. Keep changes small and testable.
- Never claim device tests passed without actual evidence. Report unavailable devices, servers or credentials as blockers; do not invent results.
- Never commit credentials, disable host-key checks, or grant remote output authority to execute local actions. Do not expose additional network services by default.
- Do not expand scope into agent orchestration, mandatory remote helpers, VPN/tunneling services, full IDE or local Linux runtime.
- Prefer working code and reproducible tests over additional speculative design documents. Record consequential deviations and reasons briefly.
