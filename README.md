<p align="center">
  <img src="./docs/media/locus-logo.svg" width="88" height="88" alt="">
</p>

<h1 align="center">Locus</h1>

<p align="center"><strong>Your Linux workspace, on your Android tablet.</strong></p>
<p align="center">SSH terminal, split windows, and remote file preview.</p>

<p align="center">
  <a href="https://github.com/Dqz00116/Locus/releases"><img alt="Latest GitHub release" src="https://img.shields.io/github/v/release/Dqz00116/Locus?include_prereleases&amp;label=version&amp;style=flat-square&amp;color=2563eb"></a>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-3ddc84?style=flat-square">
  <a href="./LICENSE"><img alt="MPL-2.0" src="https://img.shields.io/badge/license-MPL--2.0-orange?style=flat-square"></a>
</p>

<p align="center">
  <a href="./README.md">English</a> |
  <a href="./README_zh.md">简体中文</a>
</p>

---

Locus is an Android tablet client for remote Linux work. Connect over SSH, choose a working directory, and run the shell or terminal apps on your host. Keep independent terminals and remote file previews side by side, with support for Android input methods and physical keyboards.

[GitHub Releases](https://github.com/Dqz00116/Locus/releases) · [Quick Start](#quick-start)

## See Locus in action

### Dock and resize windows

Pick an edge to place another terminal, then drag a divider to give each pane the space it needs.

<img src="./docs/media/locus-docking.gif" width="960" alt="A new SSH terminal docks below another pane, then the split is resized">

### Preview remote files

Browse folders over SFTP and open a read-only preview alongside your terminal. Common source files get syntax highlighting. Supported images can also be previewed.

<img src="./docs/media/locus-files.gif" width="960" alt="Remote folders open into a syntax-highlighted source preview alongside a live terminal">

### Save and reopen layouts

Save a window arrangement with each pane’s directory and reopen it from the sidebar.

<img src="./docs/media/locus-layouts.gif" width="960" alt="A layout is saved, resized, then restored from the sidebar">

### Type Chinese in the terminal

Compose with Android’s Pinyin keyboard. Selecting `你好` sends the committed text to the remote terminal.

<img src="./docs/media/locus-input.gif" width="960" alt="Gboard shows Pinyin candidates, then the SSH terminal displays the selected text 你好">

## Quick Start

### 1. Install Locus

Get the APK from [GitHub Releases](https://github.com/Dqz00116/Locus/releases) or build it from source. Use an arm64 or x86_64 device running Android 9 or later and a Linux host with SSH and SFTP enabled.

<details>
<summary>Build and install from source</summary>

Install Go 1.26 or later, JDK 17 or 21, Android SDK Platform 34, Build Tools 34.0.0, Platform Tools, and Android NDK 27. Set `JAVA_HOME` and `ANDROID_HOME`, and add `go` and `adb` to your `PATH`.

From the repository root, run `gradlew.bat :app:assembleDebug` in Windows Command Prompt or `./gradlew :app:assembleDebug` on macOS or Linux. Gradle builds and packages the bundled FRP library automatically.

Connect your Android device to ADB and install the built APK.

```text
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To generate per-architecture APKs alongside the universal APK, add `-PlocusAbiApks=true` to the Gradle command.

</details>

### 2. Connect to your host

Open **Workspace actions → Connect**. To edit an existing connection, disconnect and open **Connection setup**.

#### Direct SSH

Choose **Direct SSH** when your tablet can reach the SSH host. Enter its hostname or IP address, port (usually `22`), and SSH username. Choose **Password** to enter the account password or **SSH key** to select a private key in Android’s file picker. Tap **Connect**.

<img src="./docs/media/locus-ssh.gif" width="960" alt="Direct SSH setup in Locus, including the key file picker and the remote directory chooser">

#### FRP STCP

Choose **FRP STCP** when an existing FRP relay and STCP proxy provide access to your SSH host. Tap **Import FRP profile** and pick your visitor `frpc.toml` in Android’s file picker. Locus selects a single enabled STCP visitor automatically. If there are several, choose the one that routes to SSH. Enter the target host’s SSH username and its password or private key, then tap **Connect**. Locus starts the built-in visitor from the imported settings.

<img src="./docs/media/locus-frp.gif" width="960" alt="FRP STCP setup in Locus, from importing a visitor TOML to opening a remote shell">

<details>
<summary>Example visitor TOML for a relay that uses token authentication</summary>

```toml
serverAddr = "frp.example.com"
serverPort = 7000
auth.method = "token"
auth.token = "REPLACE_WITH_RELAY_TOKEN"
transport.tls.enable = true

[[visitors]]
name = "ssh-visitor"
type = "stcp"
serverName = "ssh-proxy"
secretKey = "REPLACE_WITH_STCP_SECRET"
```

Use your relay’s address, port, and token. Set `serverName` to the STCP proxy name and match its `secretKey`. The SSH login uses the account on the target host. See the [official FRP STCP example](https://gofrp.org/en/docs/examples/stcp/) for relay and proxy setup.

</details>

### 3. Open a working directory

On the first connection, compare the displayed SSH fingerprint with one supplied by your server administrator before choosing **Trust host**. Browse or filter folders, then tap **Use this directory** to open a shell there. Use **New window** to add another terminal or choose **Browse files** from the window’s bottom grip.
