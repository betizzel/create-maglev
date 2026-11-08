#!/usr/bin/env python3
"""Capture deterministic Ponder scene screenshots in an owned Fabric client/server pair.

Prepare testing/build/test-runtime.json with :testing:prepareTestRuntime first. Requires
Java 17, minecraft-launcher-lib, Xvfb/xauth, network access for vanilla/Fabric assets,
and explicit --accept-eula agreement for the disposable loopback server.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import uuid

import record_tests as helpers

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "testing" / "build"
MINECRAFT = "1.20.1"
LOADER = "0.19.5"
INSTALLER = "1.0.3"
SERVER_LAUNCHER = "fabric-server-launch.jar"
SCENE_IDS = (
    "create_maglev:traction_substation",
    "create_maglev:maglev",
    "create_maglev:electric_bullet_bogeys",
)
GOGGLES_CAPTURE = "create_maglev:traction_substation_goggles"


def require_java17() -> Path:
    value = os.environ.get("JAVA17_HOME")
    if not value:
        raise RuntimeError("Set JAVA17_HOME to a Java 17 installation")
    java = Path(value).expanduser().resolve() / "bin/java"
    if not java.is_file():
        raise RuntimeError(f"Java 17 executable is missing: {java}")
    result = subprocess.run([str(java), "-version"], text=True, encoding="utf-8",
                            errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode != 0 or not re.search(r'version "17(?:\.|\")', result.stdout):
        raise RuntimeError(f"Expected Java 17 at {java}: {result.stdout.strip()}")
    return java


def write_options(client_dir: Path) -> None:
    overrides = {
        "fullscreen": "false", "guiScale": "2", "renderDistance": "5",
        "simulationDistance": "5", "maxFps": "60", "pauseOnLostFocus": "false",
        "autoJump": "false", "bobView": "false", "tutorialStep": "none",
        "lang": "en_us", "soundCategory_master": "0.0",
    }
    (client_dir / "options.txt").write_text(
        "".join(f"{key}:{value}\n" for key, value in overrides.items()), encoding="utf-8")


def write_server_properties(server_dir: Path, port: int) -> None:
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server_dir / "server.properties").write_text(f'''server-ip=127.0.0.1
server-port={port}
online-mode=false
enforce-secure-profile=false
spawn-protection=0
max-players=1
view-distance=5
simulation-distance=5
difficulty=peaceful
gamemode=creative
level-type=minecraft:flat
generator-settings={{"biome":"minecraft:plains","layers":[{{"height":1,"block":"minecraft:bedrock"}},{{"height":2,"block":"minecraft:dirt"}},{{"height":1,"block":"minecraft:grass_block"}}],"structure_overrides":[]}}
generate-structures=false
level-seed=21102026
allow-flight=true
enable-rcon=false
enable-query=false
motd=PRIVATE CREATE MAGLEV PONDER SCREENSHOTS
''', encoding="utf-8")


def download_installer(path: Path) -> None:
    url = f"https://maven.fabricmc.net/net/fabricmc/fabric-installer/{INSTALLER}/fabric-installer-{INSTALLER}.jar"
    request = urllib.request.Request(url, headers={"User-Agent": "CreateMaglev-PonderShot/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response, path.open("wb") as output:
        shutil.copyfileobj(response, output)


def install_server(java: Path, server_dir: Path, log: Path, user_home: Path) -> Path:
    installer = server_dir / f"fabric-installer-{INSTALLER}.jar"
    download_installer(installer)
    command = [str(java), f"-Duser.home={user_home}", "-jar", str(installer), "server", "-dir", str(server_dir),
               "-mcversion", MINECRAFT, "-loader", LOADER, "-downloadMinecraft"]
    result = subprocess.run(command, cwd=server_dir, text=True, encoding="utf-8", errors="replace",
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    log.write_text(result.stdout, encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError(f"Fabric server install failed; inspect {log}")
    launcher = server_dir / SERVER_LAUNCHER
    if not launcher.is_file():
        raise FileNotFoundError(f"Fabric installer did not create {launcher}; inspect {log}")
    return installer


def install_client(java: Path, launcher_dir: Path) -> str:
    import minecraft_launcher_lib

    minecraft_launcher_lib.install.install_minecraft_version(MINECRAFT, str(launcher_dir))
    minecraft_launcher_lib.fabric.install_fabric(
        MINECRAFT, str(launcher_dir), loader_version=LOADER, java=str(java))
    version = f"fabric-loader-{LOADER}-{MINECRAFT}"
    version_file = launcher_dir / "versions" / version / f"{version}.json"
    if not version_file.is_file():
        raise FileNotFoundError(f"Fabric client installation did not create {version_file}")
    return version


def check_existing(paths: tuple[Path, ...]) -> None:
    for path in paths:
        if path.exists() or path.is_symlink():
            raise FileExistsError(f"Refusing to overwrite an existing Ponder run path: {path}")


def run(args: argparse.Namespace) -> int:
    java = require_java17()
    runtime_file = BUILD / "test-runtime.json"
    if not runtime_file.is_file():
        raise FileNotFoundError(f"Missing prepared test runtime: {runtime_file}; run :testing:prepareTestRuntime first")
    if args.glfw_library is not None:
        args.glfw_library = helpers.resolved(args.glfw_library)
        if not args.glfw_library.is_file():
            raise FileNotFoundError(f"GLFW library does not exist: {args.glfw_library}")

    run_root = BUILD / "run"
    reports_root = BUILD / "reports"
    output = reports_root / args.run_name
    server_dir = run_root / f"{args.run_name}-server"
    client_dir = run_root / f"{args.run_name}-client"
    launcher_dir = run_root / f"{args.run_name}-launcher"
    check_existing((output, server_dir, client_dir, launcher_dir))

    build_root = BUILD.resolve()
    if build_root != BUILD.absolute():
        raise RuntimeError(f"Refusing to write through a testing/build symlink: {BUILD} -> {build_root}")
    for path in (output, server_dir, client_dir, launcher_dir):
        resolved = path.resolve(strict=False)
        if resolved != path.absolute() or build_root not in resolved.parents:
            raise RuntimeError(f"Refusing to create a non-isolated Ponder run path: {path} -> {resolved}")
    report_inputs: list[dict] = []
    runtime, mod_jars = helpers.audit_runtime(runtime_file, report_inputs)
    if runtime["minecraft"] != MINECRAFT or runtime["loader"] != LOADER:
        raise AssertionError("The prepared helper runtime has an unexpected Minecraft/Fabric version")

    reports_root.mkdir(parents=True, exist_ok=True)
    run_root.mkdir(parents=True, exist_ok=True)
    output.mkdir()
    server_dir.mkdir()
    client_dir.mkdir()
    launcher_dir.mkdir()
    server_mods = server_dir / "mods"
    client_mods = client_dir / "mods"
    server_mods.mkdir()
    client_mods.mkdir()
    screenshots_dir = output / "screenshots"
    screenshots_dir.mkdir()
    ipc = output / "private-ipc"
    ipc.mkdir(mode=0o700)
    private_tmp = output / "private-tmp"
    private_tmp.mkdir(mode=0o700)
    os.environ.update({
        "HOME": str(launcher_dir.resolve()),
        "TMPDIR": str(private_tmp.resolve()),
        "LIBGL_ALWAYS_SOFTWARE": "true",
        "XDG_RUNTIME_DIR": str(ipc.resolve()),
        "XDG_SESSION_TYPE": "x11",
        "WAYLAND_DISPLAY": "",
    })
    environment = dict(os.environ)

    report = {
        "complete": False,
        "passed": False,
        "minecraft": MINECRAFT,
        "loader": LOADER,
        "runName": args.run_name,
        "eulaAccepted": True,
        "serverProfile": str(server_dir.resolve()),
        "clientProfile": str(client_dir.resolve()),
        "launcherHome": str(launcher_dir.resolve()),
        "screenshotDirectory": str(screenshots_dir.resolve()),
        "sceneIds": list(SCENE_IDS),
        "screenshots": [],
        "inputs": report_inputs,
        "renderOverrides": {
            "display": "private 1280x800 Xvfb",
            "softwareOpenGL": True,
            "glfwLibrary": str(args.glfw_library) if args.glfw_library else None,
        },
    }
    server = client = None
    server_thread = client_thread = None
    server_ready = threading.Event()
    server_command: list[str] = []
    client_command: list[str] = []

    try:
        installer = install_server(java, server_dir, output / "fabric-server-install.log", launcher_dir.resolve())
        helpers.add_input(report_inputs, installer, "fabric-installer")
        bootstrap = server_dir / SERVER_LAUNCHER
        helpers.add_input(report_inputs, bootstrap, "fabric-server-launcher")
        version = install_client(java, launcher_dir)

        for profile_mods in (server_mods, client_mods):
            for jar in mod_jars:
                target = profile_mods / jar.name
                if target.exists():
                    raise FileExistsError(f"Two runtime mods have the same filename: {target.name}")
                shutil.copy2(jar, target)
        for profile_mods, role in ((server_mods, "server-mod"), (client_mods, "client-mod")):
            for jar in sorted(profile_mods.glob("*.jar")):
                helpers.add_input(report_inputs, jar, role)
        if args.glfw_library is not None:
            helpers.add_input(report_inputs, args.glfw_library, "host-native-glfw")

        write_options(client_dir)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        write_server_properties(server_dir, port)
        helpers.server_properties(server_dir, port)
        report["port"] = port

        server_command = [str(java), f"-Duser.home={launcher_dir.resolve()}", "-Xms512M", "-Xmx4G",
                          "-Dcreatemaglev.tests=true", "-jar", SERVER_LAUNCHER, "nogui"]
        report["serverCommand"] = server_command
        server = subprocess.Popen(server_command, cwd=server_dir, env=environment,
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding="utf-8", errors="replace", bufsize=1,
                                  start_new_session=True)
        server_thread = threading.Thread(target=helpers.read_log,
                                         args=(server, output / "server.log", None, server_ready), daemon=True)
        server_thread.start()
        server_deadline = time.monotonic() + 600
        while not server_ready.wait(0.5):
            if server.poll() is not None:
                raise RuntimeError(f"Dedicated server exited before readiness: {server.returncode}; inspect {output / 'server.log'}")
            if time.monotonic() >= server_deadline:
                raise TimeoutError(f"Dedicated server startup exceeded ten minutes; inspect {output / 'server.log'}")

        username = "MaglevPonder"
        client_jvm = ["-Xms512M", "-Xmx4G", "-Djava.awt.headless=false",
                      f"-Duser.home={launcher_dir.resolve()}", "-Dcreatemaglev.tests=true",
                      f"-Dcreatemaglev.tests.ponderShots={screenshots_dir.resolve()}"]
        if args.glfw_library is not None:
            client_jvm.append("-Dorg.lwjgl.glfw.libname=" + str(args.glfw_library))
        options = {
            "username": username,
            "uuid": str(uuid.uuid3(uuid.NAMESPACE_DNS, "OfflinePlayer:" + username)),
            "token": "0",
            "executablePath": str(java),
            "gameDirectory": str(client_dir.resolve()),
            "customResolution": True,
            "resolutionWidth": "1280",
            "resolutionHeight": "800",
            "jvmArguments": client_jvm,
            "quickPlayMultiplayer": f"127.0.0.1:{port}",
        }
        import minecraft_launcher_lib
        client_command = minecraft_launcher_lib.command.get_minecraft_command(version, str(launcher_dir), options)
        report["clientCommand"] = client_command
        client = subprocess.Popen(client_command, cwd=client_dir, env=environment,
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding="utf-8", errors="replace", bufsize=1,
                                  start_new_session=True)
        client_thread = threading.Thread(target=helpers.read_log,
                                         args=(client, output / "client.log", None, None), daemon=True)
        client_thread.start()
        try:
            client.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired as error:
            raise TimeoutError(f"Ponder client exceeded {args.timeout:g} seconds; inspect {output / 'client.log'}") from error
        client_thread.join(timeout=15)
        report["clientReturnCode"] = client.returncode
        if client.returncode != 0:
            raise RuntimeError(f"Ponder client exited with status {client.returncode}; inspect {output / 'client.log'}")
        failure_file = screenshots_dir / "failure.txt"
        if failure_file.is_file():
            raise RuntimeError(f"Ponder client reported failure: {failure_file.read_text(encoding='utf-8').strip()}")

        index_file = screenshots_dir / "index.json"
        if not index_file.is_file():
            raise RuntimeError(f"Ponder client exited without screenshot index.json; inspect {output / 'client.log'}")
        index = json.loads(index_file.read_text(encoding="utf-8"))
        shots = index.get("screenshots")
        if index.get("complete") is not True or not isinstance(shots, list) or len(shots) != 13:
            raise AssertionError(f"Expected four screenshots for each of three scenes plus the goggles view: {index}")
        if [shot.get("scene") for shot in shots[:12:4]] != list(SCENE_IDS):
            raise AssertionError(f"Screenshots are not ordered by the registered scene IDs: {shots}")
        if shots[12].get("scene") != GOGGLES_CAPTURE:
            raise AssertionError(f"The final screenshot must be the substation goggles view: {shots[12]}")
        for shot in shots:
            path = screenshots_dir / shot["file"]
            if not path.is_file() or path.stat().st_size < 8:
                raise FileNotFoundError(f"Missing Ponder screenshot: {path}")
            with path.open("rb") as image:
                if image.read(8) != b"\x89PNG\r\n\x1a\n":
                    raise AssertionError(f"Screenshot is not a PNG: {path}")
            report["screenshots"].append({
                "scene": shot["scene"], "file": f"screenshots/{path.name}", "tick": shot["tick"],
                "bytes": path.stat().st_size, "sha256": helpers.digest(path),
            })
        report["screenshotIndex"] = "screenshots/index.json"
        for item in report_inputs:
            source = Path(item["file"])
            if helpers.digest(source) != item["sha256"] or source.stat().st_size != item["bytes"]:
                raise AssertionError(f"A packaged runtime input changed during the capture: {source}")
        report["complete"] = True
        report["passed"] = True
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
        print("PONDER SCREENSHOT ERROR", report["error"], flush=True)
    finally:
        helpers.stop(client)
        if server is not None and server.poll() is None and server.stdin is not None:
            try:
                server.stdin.write("stop\n")
                server.stdin.flush()
                server.wait(timeout=60)
            except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
                helpers.stop(server)
        elif server is not None and server.poll() is None:
            helpers.stop(server)
        if client_thread is not None:
            client_thread.join(timeout=15)
        if server_thread is not None:
            server_thread.join(timeout=15)
        report.setdefault("clientReturnCode", client.returncode if client is not None else None)
        report["serverReturnCode"] = server.returncode if server is not None else None
        if report["passed"] and report["serverReturnCode"] != 0:
            report["complete"] = False
            report["passed"] = False
            report["error"] = f"Dedicated server exited with status {report['serverReturnCode']}"
        report["serverCommand"] = server_command
        report["clientCommand"] = client_command
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print("PONDER SCREENSHOT REPORT", output / "report.json", flush=True)
    return 0 if report["passed"] else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-eula", action="store_true", required=True,
                        help="record explicit EULA acceptance for the disposable loopback server")
    parser.add_argument("--run-name", default="ponder-visual-review",
                        help="unique name for isolated profiles and reports under testing/build")
    parser.add_argument("--timeout", type=float, default=900,
                        help="maximum seconds for the client capture (default: 900)")
    parser.add_argument("--glfw-library", type=Path,
                        help="optional absolute host GLFW library for software-rendered Xvfb")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", args.run_name) or args.run_name in (".", ".."):
        parser.error("--run-name must be one safe filename component")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    if args.glfw_library is not None and not args.glfw_library.is_absolute():
        parser.error("--glfw-library must be an absolute path")

    if os.environ.get("MAGLEV_PONDER_XVFB") != "1":
        xvfb_run = shutil.which("xvfb-run")
        if xvfb_run is None or shutil.which("xauth") is None:
            raise RuntimeError("Ponder screenshot capture requires xvfb-run and xauth")
        environment = dict(os.environ, MAGLEV_PONDER_XVFB="1")
        command = [xvfb_run, "--auto-servernum", "--server-args=-screen 0 1280x800x24 -nolisten tcp",
                   sys.executable, str(Path(__file__).resolve()), *sys.argv[1:]]
        return subprocess.run(command, env=environment, check=False).returncode
    return run(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"PONDER SCREENSHOT ERROR {type(error).__name__}: {error}", file=sys.stderr, flush=True)
        raise SystemExit(1)
