# CausVane: installing, running and releasing

CausVane is a desktop application: it always opens a window, and there is nothing
to type in a terminal to install or use it.

Contents: [Installing](#installing-causvane) · [Using it](#using-it) ·
[Developing it](#developing-it-in-intellij) · [Releasing a new version](#releasing-a-new-version)

---

## Installing CausVane

Download the file for your system from the latest release on GitHub:
**https://github.com/AhmedAbdelaty22/CausVane/releases/latest**

| System | File | What to do |
|---|---|---|
| macOS | `CausVane-macOS.zip` | Double-click the zip, then drag **CausVane** into **Applications**. |
| Windows | `CausVane-Windows.zip` | Right-click the zip, choose **Extract All**, and move the **CausVane** folder to where you keep programs. Start `CausVane.exe` inside it. |
| Linux | `CausVane-Linux.tar.gz` | Open it with your archive manager, extract the **CausVane** folder, and double-click `CausVane/bin/CausVane`. |

Each app includes its own Java, so nothing else needs to be installed.

**The first start.** The apps are not signed with a paid developer certificate, so
each system asks once whether to trust them:

- **macOS:** right-click **CausVane** in Applications, choose **Open**, then **Open**
  again in the dialog. From then on it opens normally.
- **Windows:** if a blue "Windows protected your PC" box appears, click
  **More info**, then **Run anyway**.

**Keeping it handy.**

- macOS: right-click its Dock icon while it is running, then **Options → Keep in Dock**.
- Windows: right-click `CausVane.exe` and choose **Pin to Start** or **Pin to taskbar**.
- Linux: most desktops let you right-click the launcher and add it to favourites.

**Updating** is the same as installing: replace the app with the one from a newer
release. Settings are kept.

**Uninstalling:** move CausVane from Applications to the Trash (macOS), or delete the
CausVane folder and any shortcuts (Windows, Linux). CausVane installs no service,
database or background process.

---

## Using it

- Open a log with **Open**, by dropping the file on the window, or by choosing
  **Open With → CausVane** on the file. Compressed `.gz` logs open directly.
- **Watch live** follows a log that is still being written.
- **More → Save a report** saves a Markdown or JSON report;
  **Copy for a ticket** copies a summary ready to paste.

Memory use does not grow with the size of the log, so large files open normally.

Everything the window shows is described in the README and in
`docs/CausVane-Documentation.pdf`.

---

## Developing it in IntelliJ

1. Open the project folder in IntelliJ IDEA. It is a Maven project and needs
   JDK 21 or newer.
2. Run the shared run configuration **CausVane** (main class `io.causvane.Main`)
   with the green ▶ button. The window opens straight from the compiled code;
   nothing is installed on the computer.
3. Run the tests from the **Maven** tool window: **CausVane → Lifecycle → package**
   runs all of them and builds `target/CausVane.jar`, which the release apps are
   made from.

The desktop apps for macOS, Windows and Linux are not built on your computer.
GitHub builds them, each on its own system, when a release is published (see below).

**The icon** is drawn in code by `io.causvane.gui.AppIcon`, so the window always has
it. The files in `packaging/` (`CausVane.icns`, `.ico`, `.png`) are exports of that
drawing, used by GitHub's build for each system's app. `packaging/IconExport.java`
regenerates them after the design changes.

**What keeps it working the same on every system:**

- Windows log files in UTF-16 (as PowerShell writes them) are detected and read, as
  are Windows line endings and `C:\...` paths in stack traces.
- No arrow or symbol characters that some systems' fonts lack; those are drawn as shapes.
- Numbers and dates do not change with the system language.
- The window never opens larger than the screen, and its layout adapts to any size
  down to 720 × 520.

---

## Releasing a new version

The repository is **https://github.com/AhmedAbdelaty22/CausVane**.

1. Change the version number in four places: `<version>` in `pom.xml`, `VERSION` in
   `src/main/java/io/causvane/Main.java`, and `--app-version` in
   `packaging/build-app.sh` and `packaging/build-app.bat`.
2. Run the tests in IntelliJ (Maven → Lifecycle → package), then commit and push.
3. On GitHub, open **Releases → Draft a new release**, create the tag (for example
   `v2.3.0`), write the notes, and click **Publish release**.

Publishing starts `.github/workflows/build.yml`: GitHub runs all the tests on
Linux, Windows and macOS, builds the desktop app on each, and attaches
`CausVane-macOS.zip`, `CausVane-Windows.zip` and `CausVane-Linux.tar.gz` to the
release after all three pass. The table of downloads in
`.github/RELEASE_NOTES.md` can be pasted into the release notes.

Before going further public, read through `samples/` once more: the logs are
invented, but confirm nothing in the repository is internal to your company.
