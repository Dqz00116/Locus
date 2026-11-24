# Remote Workspace — MVP design

Status: product constraints agreed; implementation candidates unverified. Target: Android tablet, physical keyboard/mouse, including Samsung DeX; remote headless Linux.

## 1. User workflow
Open the client/terminal, connect to a configured remote Linux host, explicitly choose a working directory (e.g. `cd ~/workspace/game`), then run an arbitrary CLI/TUI program or browse files. Open additional generic windows, explicitly choose their directories and actions, arrange them freely, inspect remote output and files, and close windows manually. The agent and file views are peers. No agent-first dashboard, mandatory file tree, project wizard or local development environment.

## 2. Product semantics
- Workspace: logical collection of related remote directories and windows, usually one directory. The initial explicitly chosen working directory becomes its default root; other directories can be added/removed without changing or deleting remote files. Roots are not security boundaries. A subsequent ordinary shell `cd` does not automatically change roots. Exact initial capture UX is an implementation decision; a minimal explicit confirmation is acceptable rather than guessing from shell output.
- Window: generic persistent UI container with user-selected directory, stable identity and current presentation. No public AgentWindow/FileWindow classes or type selection. Terminal is the natural initial interaction; a browse action presents directory tree + immediate preview. User can open a new generic window or deliberately change presentation; do not automatically replace a running terminal. Directory inheritance, if offered, is a suggestion, not a forced choice.
- View: internal terminal or file presentation. Terminal handles arbitrary programs; file view handles navigation and read-only preview. Preserve live terminal state when temporarily hiding it if feasible; do not silently terminate it.
- Dock: content-agnostic layout, split horizontally/vertically, resize, move, tab groups, maximize/restore. Start with two resizable panes, then add recursive layout and tabs. No mandatory primary pane.
- Lifecycle: windows survive program exit, shell exit, network failure and missing files. User closes them explicitly. Default close confirmation for a live terminal channel, configurable (ask or disconnect; detach only if a supported persistent terminal session actually exists). Cancellation leaves window intact. Closing a file-only window can be immediate. Never claim control of an agent backend; OpenCode may run independently of its TUI depending on launch mode. No mandatory tmux/session manager. SSH reconnection is not PTY or agent-session restoration.

## 3. Minimal architecture

```text
Android client (Kotlin/Compose candidate)
  Workspace state (roots, windows, layout)
    Window/Dock manager (content independent)
      Terminal view + native input adapter
      File tree + preview renderer
  Configurable connection abstraction
    SSH/SFTP implementation (first provider)
       | existing user network: LAN / FRP / VPN / proxy / etc.
Remote Linux: sshd, shell/PTY, filesystem, arbitrary applications
```

Keep connection configuration separate from workspace state. A profile contains endpoint, port, username, authentication reference and optional routing configuration where supported. Network reachability is supplied by the user; do not implement FRP or VPN. One SSH endpoint can support multiple channels and SFTP when server policy allows. Do not require separate exposed HTTP ports. MVP remote dependency: standard SSH service with SFTP enabled; no custom remote daemon.

Conceptual contracts (not mandatory class hierarchy):

```text
ConnectionProvider.connect(profile) -> RemoteConnection
RemoteConnection.openTerminal(cwd) -> TerminalChannel
RemoteConnection.openFileSystem() -> RemoteFileSystem
TerminalChannel: write(bytes), receive(bytes), resize(cols, rows), close(), status
RemoteFileSystem: list(path), stat(path), read(path, offset?, length?)
Workspace: id, connectionRef, roots[], defaultDirectory, windows[], layout
Window: id, selectedDirectory, presentation, runtimeState
```

Use asynchronous, cancellable IO, bounded buffers and explicit error states. Distinguish selected directory from actual shell CWD; SSH does not automatically expose subsequent shell CWD. If needed, use explicit path entry or validated shell integration (e.g. OSC 7), not fragile prompt parsing. Never treat terminal output as trusted local commands. Keep stable window IDs and terminal resources independent of Compose recomposition and dock movement. Persist serializable workspace/layout/profile metadata, not live sockets or secrets.

## 4. MVP scope

Required: connection configuration and authentication with host identity validation; remote PTY with ANSI/VT, resize and keyboard/mouse input; native Chinese IME; unified windows; manually selected paths; multi-root workspace; basic free docking; remote directory tree and immediate read-only preview; explicit close behavior; restore workspace/layout; honest disconnect/reconnect states.

File preview baseline: plain text/source code (syntax highlighting desirable), images, common video formats. Markdown/PDF/HTML may use existing renderers after baseline works; do not block the first spike on them. Use bounded reads for text/images and range-capable streaming or bounded temporary cache for media. Unsupported formats show an explicit fallback. Do not execute remote HTML or JavaScript with privileged native bridges; isolate active content. No file editing, upload, rename/delete, dedicated Git/SVN UI, global indexing or agent-specific integration in MVP.

## 5. Candidate technology and research leads

Preferred hypothesis: Kotlin + Jetpack Compose, native terminal and input adapter, native SSH/SFTP, minimal Compose dock, native media/image rendering with WebView only where useful. Native-first is motivated by IME, hardware keyboard, focus and resize; it is not proof of superiority. Verify all links and current APIs before selecting dependencies.

| Candidate | Role | Verification required |
|---|---|---|
| https://github.com/google/vtfx | Native terminal candidate | Repository availability, license, Compose API, IME, CJK, mouse, resize, maintenance |
| https://github.com/connectbot/termlib | Alternative terminal | Actual Android API, native dependencies/ABI, IME and TUI behavior |
| https://github.com/termux/termux-app | Reference/fallback terminal implementation | Terminal component extraction, license obligations, input behavior |
| https://github.com/connectbot/cbssh | SSH/SFTP candidate | Availability, auth, host key validation, PTY, resize, concurrent channels, SFTP reads |
| https://github.com/connectbot/connectbot | Android SSH reference | Input/lifecycle and connection practices |
| https://github.com/wavetermdev/waveterm | Product architecture reference | Unified blocks, layout, remote capability separation; not a direct Android port |
| https://github.com/dockview/dockview | Dock interaction/model reference | Recursive layout and persistence; web component, not native Compose |
| https://github.com/caplin/FlexLayout | Alternative web dock reference | Layout model, touch behavior |
| https://github.com/xtermjs/xterm.js/issues/5377 | Mobile terminal risk lead | Verify issue contents and current status; test WebView if considering fallback |

Do not assume claimed protocol support implies working Chinese input or agent compatibility. libghostty is another possible engine, but Android IME integration and renderer are separate work; do not prioritize it without evidence.

## 6. Native input and terminal correctness (P0)

Android IME composition is local. Handle composing updates, commit, cancellation, selection/deletion and focus transitions without leaking phonetic keystrokes or duplicating committed text. Encode committed Unicode as UTF-8. Route hardware control keys through terminal protocol encoding, respecting IME consumption. Handle paste according to negotiated terminal mode. Do not implement arbitrary remote text editing by assuming local selection offsets match remote PTY state.

Test Chinese continuous composition, candidate selection, mixed Chinese/English, backspace and cursor navigation, physical keyboard language switch, Ctrl/Alt/Esc/Tab, multiline paste, CJK wide characters, wrapping, emoji/combining characters, mouse events and focus changes. Verify both a shell and an interactive TUI such as OpenCode. Remote locale and application width handling can also cause defects; isolate client versus remote issues. Test under network latency: composition must remain responsive locally.

## 7. Implementation order

### Stage 0 — inspect and verify
Inspect repository, Android SDK/JDK/Gradle, emulator/device availability. Check candidate source, licenses, buildability and interfaces. Select smallest compatible dependency set. Use reproducible checks and report blockers.

### Stage 1 — runnable spike (first deliverable)
Build/install a minimal Android app with a native terminal, configurable SSH connection and PTY. Exercise a real shell, Chinese IME, hardware keys, OpenCode where installed. Add a second independent terminal and simple resizable split to test focus and resize. Add minimal SFTP list/read and a text/image preview beside the terminal. No complete workspace settings or polished dock yet. Use a test host supplied by user; do not hardcode credentials. A local mock can test UI but does not substitute for real SSH/TUI validation.

### Stage 2 — MVP organization
Implement generic windows, explicit path selection, initial root and additional roots, recursive dock layout, tabs, maximize/restore, read-only preview baseline, explicit close policy and persistent workspace metadata. Keep connection provider separate. Implement reconnect without pretending to restore a lost PTY.

### Stage 3 — target-device validation
Test Android tablet with physical keyboard, mouse and DeX where available. Fix P0 defects before expanding formats or adding convenience features. Produce build/run instructions and concise test results.

## 8. Acceptance criteria

| Scenario | Pass condition |
|---|---|
| Connect | Authenticated SSH connection; unknown/changed host keys handled explicitly |
| Directory-first | User can choose a remote directory before launching a program; no project wizard |
| Agent TUI | An interactive TUI renders and accepts input without blocking defects |
| Chinese IME | Native composition/commit works without duplicated, lost or prematurely transmitted text |
| Keyboard/mouse | Control keys, language switching, selection, scrolling and negotiated mouse events work |
| Multiple panes | Independent PTYs; resize propagates and TUIs redraw; focus changes do not leak input |
| Files | Browse remote tree and preview text/image/video baseline without manual download workflow |
| Workspace | Add/remove roots without filesystem mutation; ordinary cd does not rewrite root set |
| Dock | User arranges peer windows; no fixed primary pane; layout restores after restart |
| Lifecycle | Exit/disconnect does not close windows; explicit close confirmation configurable |
| Reconnect | Clear status; reconnect transport and reopen resources where possible; no false session recovery claim |
| Security | Host identity checked, secrets not logged/committed, remote content cannot invoke privileged local actions |

Use reproducible checks for the acceptance criteria and identify untested cases. First success milestone is Stage 1 working code, not completion of all MVP features.

## 9. Deliberately deferred decisions
Exact workspace capture UX, view switching gestures/shortcuts, richer path linking, advanced media formats, optional remote helper, persistent PTY support, additional connection providers and automatic profile switching. Choose the smallest reversible behavior when required by the spike. Do not invent a broad framework to accommodate future possibilities.
