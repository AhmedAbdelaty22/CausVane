# EasyLog: installing, running and publishing notes

Everything starts from one file: `target/EasyLog.jar`. It contains the app and
all its libraries, and it is the same file for macOS, Windows and Linux.

Build it with:

```bash
mvn clean package
```

Commands marked **(tested)** were run on this Mac. The rest are standard but have
not been run here.

---

Contents: [Installing](#installing-easylog) · [1. Running it](#1-running-it) ·
[2. Native app](#2-native-app-with-its-own-name-and-icon-no-java-required) ·
[Checking a computer](#checking-that-it-works-on-a-given-computer) ·
[3. Publishing](#3-ways-to-publish-it) · [4. New versions](#4-releasing-a-new-version)

---

## Installing EasyLog

There are two ways to install it. Pick one per computer.

| | Option A: native app | Option B: the JAR |
|---|---|---|
| What you copy | An app built for that system | One file, `EasyLog.jar`, the same for every system |
| Needs Java installed | No, Java is bundled | Yes, Java 21 or newer |
| Name and icon in Dock / Start menu | EasyLog | Icon yes; some systems label it "java" |
| Size | About 80 MB | 3.5 MB |
| Must be built on | The same kind of system it will run on | Anywhere |

EasyLog keeps no settings, database or background service. Installing is copying
files, and uninstalling is deleting them.

Only the macOS steps below were carried out on a real machine. The Windows and
Linux steps are the standard procedure for each system but have not been run.

### macOS

**Option A: native app**

1. Build it (once per version), from the project folder:

   ```bash
   mvn clean package && sh packaging/build-app.sh
   ```

   This creates `dist/EasyLog.app` **(tested)**.
2. Drag `dist/EasyLog.app` into the **Applications** folder.
3. Open it from Launchpad or Spotlight. To keep it in the Dock, right-click its Dock
   icon and choose Options, then Keep in Dock.
4. If the app was copied from another Mac or downloaded, macOS blocks the first
   launch because it is not signed by an Apple developer account. Right-click the
   app, choose **Open**, then **Open** again. This is needed once.
5. Optional, to use the command line from any folder, add this line to `~/.zshrc`
   and open a new terminal:

   ```bash
   alias easylog="/Applications/EasyLog.app/Contents/MacOS/EasyLog"
   ```

   Then `easylog analyze app.log` works anywhere.

**Option B: the JAR**

1. Install Java 21 or newer if `java -version` does not show it: download the macOS
   `.pkg` from https://adoptium.net and run it.
2. Copy `EasyLog.jar` anywhere, for example your Applications or Documents folder.
3. Double-click it, or run `java -jar EasyLog.jar`.

**Uninstall:** drag `EasyLog.app` (or the JAR) to the Trash. Remove the alias line
if you added it.

### Windows

**Option A: native app**

1. On a Windows PC with JDK 21+ and Maven, from the project folder:

   ```bat
   mvn clean package
   ```

   ```bat
   packaging\build-app.bat
   ```

   This creates the folder `dist\EasyLog` containing `EasyLog.exe` (the window)
   and `EasyLogCLI.exe` (the same program for use in a terminal).
2. Copy the whole `dist\EasyLog` folder to where programs live, for example
   `C:\Program Files\EasyLog` (needs administrator rights) or
   `%LOCALAPPDATA%\Programs\EasyLog` (no rights needed). Keep the folder
   together; the `.exe` does not work on its own.
3. Right-click `EasyLog.exe` and choose **Pin to Start** or **Pin to taskbar**,
   or **Send to → Desktop (create shortcut)**.
4. The first launch may show a blue "Windows protected your PC" box, because the
   program is not signed. Click **More info**, then **Run anyway**.
5. Optional, for the command line from any folder: add the install folder to the
   PATH (Start → "Edit environment variables for your account" → Path → New), then
   use `EasyLogCLI analyze app.log`.

For a proper installer with a Start-menu entry and an entry under "Installed apps",
install the WiX Toolset, change `app-image` to `msi` in `packaging\build-app.bat`,
and run it again. It produces `dist\EasyLog-2.1.0.msi`; double-click to install.

**Option B: the JAR**

1. Install Java 21 or newer: download the Windows `.msi` from https://adoptium.net.
   In the installer, enable **Associate .jar** and **Set JAVA_HOME / add to PATH**.
2. Copy `EasyLog.jar` anywhere and double-click it.
3. If double-clicking does nothing, open a terminal in that folder and run
   `java -jar EasyLog.jar` to see the reason.

**Uninstall:** delete the install folder and any shortcuts, or use "Installed apps"
if you used the `.msi`. Remove the PATH entry if you added one.

### Linux

**Option A: native app**

1. On a Linux machine with JDK 21+ and Maven, from the project folder:

   ```bash
   mvn clean package && sh packaging/build-app.sh
   ```

   This creates the folder `dist/EasyLog` with the launcher `bin/EasyLog`.
2. Copy it into place and make the command available (no root needed):

   ```bash
   mkdir -p ~/.local/opt ~/.local/bin && cp -r dist/EasyLog ~/.local/opt/ && ln -sf ~/.local/opt/EasyLog/bin/EasyLog ~/.local/bin/easylog
   ```

   `easylog` now starts the window, and `easylog analyze app.log` runs in the
   terminal (if `~/.local/bin` is on your PATH, which is the default on most
   distributions).
3. To get an entry with the icon in the applications menu, save this as
   `~/.local/share/applications/easylog.desktop`, replacing `YOU` with your user
   name:

   ```ini
   [Desktop Entry]
   Type=Application
   Name=EasyLog
   Comment=Finds, explains and pinpoints errors in application logs
   Exec=/home/YOU/.local/opt/EasyLog/bin/EasyLog %f
   Icon=/home/YOU/.local/opt/EasyLog/lib/EasyLog.png
   Categories=Development;Utility;
   Terminal=false
   ```

For a system package instead, run `sh packaging/build-app.sh deb` (Debian, Ubuntu;
needs `fakeroot`) or `sh packaging/build-app.sh rpm` (Fedora, RHEL; needs
`rpm-build`), then install the file it puts in `dist/` with
`sudo apt install ./dist/<file>.deb` or `sudo dnf install ./dist/<file>.rpm`. The
package adds the menu entry itself.

**Option B: the JAR**

1. Install Java 21 or newer. It must not be a "headless" package, or the window
   cannot open:
   - Debian / Ubuntu: `sudo apt install openjdk-21-jre`
   - Fedora / RHEL: `sudo dnf install java-21-openjdk`
2. Copy `EasyLog.jar` anywhere and run `java -jar EasyLog.jar`.

**Uninstall:** `rm -rf ~/.local/opt/EasyLog ~/.local/bin/easylog ~/.local/share/applications/easylog.desktop`,
or remove the package with `apt remove easylog` / `dnf remove easylog`.

### After installing

- Confirm it works on that computer with the self-test described under
  [Checking that it works on a given computer](#checking-that-it-works-on-a-given-computer).
- **Updating** to a new version is the same as installing: replace the app (or the
  JAR) with the new one. Nothing needs migrating.
- **Installing for a team:** build the native app once per operating system and
  share that build, or share the JAR and have each person install Java.

---

## 1. Running it

### Any desktop with Java 21 or newer

Check Java first:

```bash
java -version
```

If it is missing or older than 21, install a JDK from https://adoptium.net (free,
all platforms).

| What you want | Command |
|---|---|
| Open the GUI **(tested)** | `java -jar EasyLog.jar` |
| Open the GUI on a file **(tested)** | `java -jar EasyLog.jar app.log` |
| Analyze in the terminal **(tested)** | `java -jar EasyLog.jar analyze app.log` |
| All options | `java -jar EasyLog.jar --help` |

### macOS

- Terminal: the commands above.
- Double-click: works if a JDK is installed. If macOS blocks it the first time,
  right-click the JAR, choose Open, then confirm.
- No Java on the machine: build the native app (section 2).

### Windows

- PowerShell or cmd: `java -jar EasyLog.jar`
- Double-click: works when `.jar` files are associated with Java. If nothing
  happens, start it from a terminal once to see the error.
- A window with no console: `javaw -jar EasyLog.jar`
- Optional `EasyLog.bat` next to the JAR, so you can type `EasyLog app.log`:

  ```bat
  @echo off
  java -jar "%~dp0EasyLog.jar" %*
  ```

### Linux

- Desktop: `java -jar EasyLog.jar`
- Optional launcher script, saved as `EasyLog` next to the JAR and made
  executable with `chmod +x EasyLog`:

  ```bash
  #!/bin/sh
  exec java -jar "$(dirname "$0")/EasyLog.jar" "$@"
  ```

- The JDK must not be a "headless" build for the GUI (on Debian/Ubuntu install
  `openjdk-21-jre`, not `openjdk-21-jre-headless`).

### Servers, SSH sessions, CI (no display)

The GUI cannot start without a display; the app says so and exits. Use the CLI:

```bash
java -jar EasyLog.jar analyze /var/log/app/app.log --min-level ERROR --top 5
java -jar EasyLog.jar analyze app.log --format json -o report.json
java -jar EasyLog.jar analyze app.log --follow
```

A common support workflow: run `analyze --format markdown -o report.md` on the
server, or copy the log to your laptop and open it in the GUI.

### WSL (Windows Subsystem for Linux)

- CLI works like any Linux server.
- GUI works on Windows 11 (WSLg). On Windows 10 it is simpler to run the JAR
  from Windows itself and open the file through `\\wsl$\...`.

### Docker (CLI only)

Useful when the machine has Docker but no Java. Save as `Dockerfile` in the
project root:

```dockerfile
FROM eclipse-temurin:21-jre
COPY target/EasyLog.jar /opt/EasyLog.jar
ENTRYPOINT ["java", "-jar", "/opt/EasyLog.jar", "analyze"]
```

```bash
docker build -t easylog .
docker run --rm -v /var/log/app:/logs:ro easylog /logs/app.log --no-color
```

### Large files

Memory use does not grow with file size, so the default heap is normally fine.
A 55 MB / 668,000-line log was analyzed with `-Xmx256m` **(tested)**.

---

## 2. Native app with its own name and icon (no Java required)

Run from the JAR, the program appears as a Java process: the window and Dock show
the EasyLog icon, but macOS still labels the Dock entry "java". A native build
fixes that. It is a normal application called **EasyLog**, with the icon in
Finder / Explorer, the Dock and the task switcher, and it bundles its own Java so
users install nothing else.

`jpackage` (part of the JDK) only builds for the operating system it runs on, so
each platform needs its own build.

| Platform | Command (from the project root, after `mvn clean package`) | Result |
|---|---|---|
| macOS **(tested, 79 MB)** | `sh packaging/build-app.sh` | `dist/EasyLog.app` |
| Linux | `sh packaging/build-app.sh` | `dist/EasyLog/bin/EasyLog` |
| Windows | `packaging\build-app.bat` | `dist\EasyLog\EasyLog.exe` |

On macOS, drag `dist/EasyLog.app` into Applications to install it. The app also
works as the CLI: `dist/EasyLog.app/Contents/MacOS/EasyLog analyze app.log`.

For an installer instead of a bare app, pass a type: `sh packaging/build-app.sh dmg`
(macOS, also `pkg`), `deb` or `rpm` (Linux, needs `fakeroot` / `rpm-build`). On
Windows change `app-image` to `msi` in the `.bat` file (needs the WiX Toolset).

**The icon.** It is drawn in code by `io.easylog.gui.AppIcon`, so the running
window always has it. The files in `packaging/` (`EasyLog.icns`, `.ico`, `.png`)
are exports of that drawing for the native builds. After changing the design,
regenerate them:

```bash
java -cp target/EasyLog.jar packaging/IconExport.java packaging
```

```bash
iconutil -c icns packaging/EasyLog.iconset -o packaging/EasyLog.icns && rm -rf packaging/EasyLog.iconset
```

**Signing.** Unsigned apps trigger warnings on other people's machines:

- macOS: Gatekeeper blocks unsigned downloads. Proper fix is an Apple Developer
  ID (99 USD/year) plus notarization. Workaround for colleagues: right-click, Open.
- Windows: SmartScreen warns on unsigned installers. Proper fix is a code-signing
  certificate. Workaround: "More info", then "Run anyway".

---

## Checking that it works on a given computer

EasyLog has a built-in check. On any Windows, macOS or Linux machine with a
desktop, run:

```bash
java -jar EasyLog.jar self-test samples/checkout-db-failover.log
```

It opens the real window, loads the file, confirms that errors were listed, one is
selected and the window painted, then closes and prints one line:

```
EasyLog self-test passed on Windows 11 amd64, Java 21.0.5: window opened, 7 errors listed, ...
```

Exit code 0 means it works on that system; 1 means it does not, with the reason.

Status of each system:

| System | Status |
|---|---|
| macOS (Apple Silicon, Java 25) | Self-test passed, from the JAR and from `EasyLog.app` |
| Windows | Not run yet. Code audited and adjusted for Windows; run the self-test to confirm |
| Linux | Not run yet. Code audited and adjusted for Linux; run the self-test to confirm |

**Automatic check on all three.** `.github/workflows/build.yml` makes GitHub run,
on Linux, Windows and macOS for every push: the 157 tests, the CLI on every sample,
the GUI self-test, and the native app build. It starts working as soon as the
project is pushed to a GitHub repository (section 3, option B); each run also
attaches the built app for each system as a download.

What was done so the same JAR behaves the same everywhere:

- Windows log files in UTF-16 (PowerShell `>` output) are detected and read, as are
  Windows line endings and `C:\...` paths in stack traces.
- No arrow or symbol characters that some systems' fonts lack; those are drawn as shapes.
- Numbers and dates do not change with the system language.
- The terminal output switches to plain ASCII on consoles that cannot show box characters.
- The window never opens larger than the screen.
- The Windows build includes `EasyLogCLI.exe`, because a windowed `.exe` cannot
  print to a terminal.

Known cosmetic difference: on Linux, when run from the JAR, some desktops group
the window under a generic Java name in the dock. The native build does not have
this.

---

## 3. Ways to publish it

Nothing has been published yet. The project is not even a git repository, and
publishing is something you should trigger yourself. Options, simplest first:

### A. Share the JAR directly

Send `EasyLog.jar` over a shared drive, internal wiki or chat. Recipients need
Java 21+. Good for a single team. No setup.

### B. GitHub repository with Releases (recommended)

Gives you version history, a download page and a place for bug reports.

1. Decide on a licence first if the repository will be public (MIT and Apache-2.0
   are the usual choices). The two libraries used, FlatLaf and Jackson, are both
   Apache-2.0, so either is compatible.
2. Create the repository and push (needs the `gh` tool and a GitHub account):

   ```bash
   git init && git add . && git commit -m "EasyLog 1.0.0"
   ```

   ```bash
   gh repo create EasyLog --private --source . --push
   ```

   Use `--public` instead of `--private` to make it visible to everyone.
3. Publish a release with the JAR attached:

   ```bash
   gh release create v1.0.0 target/EasyLog.jar --title "EasyLog 1.0.0" --notes "First release"
   ```

Before going public, read through `samples/` once more: the logs are invented,
but confirm nothing in the repository is internal to your company.

### C. Automatic installers for all three systems (GitHub Actions)

Because `jpackage` cannot cross-build, the usual approach is a workflow that runs
on `macos-latest`, `windows-latest` and `ubuntu-latest`, each running
`mvn package` and then `jpackage`, and uploads the `.dmg`, `.msi` and `.deb` to
the release. This builds on option B.

### D. Package managers

Lets users install and update with one command. Each needs a GitHub release to
point at.

- macOS: a Homebrew tap (`brew install yourname/tap/easylog`).
- Windows: Scoop bucket or `winget` manifest.
- Linux: attach the `.deb` / `.rpm` from option C.

Worth it only once you have more than a handful of users.

### E. Docker image

Push the image from section 1 to Docker Hub or GitHub Container Registry
(`docker push`). Suits teams that analyze logs on servers or in CI. CLI only.

### F. Maven Central / GitHub Packages

This publishes the code as a library for other Java projects to depend on. It is
the wrong channel for an end-user application; skip it unless someone wants to
embed the parser in their own tool.

### Suggested path

Start with A for your own team today. Move to B when you want versioned
downloads. Add C when people without Java need it.

---

## 4. Releasing a new version

1. Change `<version>` in `pom.xml` and `VERSION` in
   `src/main/java/io/easylog/Main.java`.
2. `mvn clean package` (runs the 157 tests).
3. Publish the new `target/EasyLog.jar` through whichever channel you chose.
