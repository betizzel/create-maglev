#!/usr/bin/env python3
"""Record genuine on-foot walking inside a moving native train in owned Homestead copies.

Prepare the helper with :testing:prepareTestRuntime first. Requires Java 17,
Minecraft Launcher Lib, ffmpeg/ffprobe and Xvfb/xauth. No live world is copied.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import queue
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid

import record_tests as helpers

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "testing/build"
BOOTSTRAP = "fabric-server-mc.1.20.1-loader.0.19.5-launcher.1.1.2.jar"
helpers.EVENTS = (
    "MAGLEV_WALKTHROUGH_JOINED", "MAGLEV_WALKTHROUGH_UI", "MAGLEV_WALKTHROUGH_READY",
    "MAGLEV_WALKTHROUGH_MOVING_READY",
    "MAGLEV_WALKTHROUGH_CHECKPOINT", "MAGLEV_WALKTHROUGH_RESULT",
    "MAGLEV_WALKTHROUGH_RECORDING_DONE", "MAGLEV_WALKTHROUGH_COMPLETE", "MAGLEV_WALKTHROUGH_FATAL",
)


def screenshot(path: Path) -> dict:
    result = subprocess.run([
        "ffmpeg", "-hide_banner", "-y", "-f", "x11grab", "-video_size", "1280x800",
        "-i", os.environ["DISPLAY"], "-frames:v", "1", "-update", "1", str(path),
    ], capture_output=True, text=True, check=False)
    path.with_suffix(".ffmpeg.log").write_text(result.stderr)
    if result.returncode != 0:
        raise RuntimeError(f"Could not capture {path}: {result.stderr}")
    return {"file": path.name, "sha256": helpers.digest(path), "bytes": path.stat().st_size}


def write_options(client: Path) -> None:
    overrides = {
        "fullscreen": "false", "guiScale": "2", "renderDistance": "5", "simulationDistance": "5",
        "maxFps": "60", "pauseOnLostFocus": "false", "autoJump": "false", "bobView": "false",
        "tutorialStep": "none", "lang": "en_us", "soundCategory_master": "0.0",
        "key_key.forward": "key.keyboard.w",
    }
    original = (client / "config/defaultoptions/options.txt").read_text()
    keys = set()
    lines = []
    for line in original.splitlines():
        key = line.split(":", 1)[0]
        keys.add(key)
        lines.append(key + ":" + overrides[key] if key in overrides else line)
    lines.extend(key + ":" + value for key, value in overrides.items() if key not in keys)
    (client / "options.txt").write_text("\n".join(lines) + "\n")


def run(args: argparse.Namespace) -> int:
    args.minecraft_home = helpers.resolved(args.minecraft_home)
    if args.glfw_library is not None:
        args.glfw_library = helpers.resolved(args.glfw_library)
    output = BUILD / "reports" / args.run_name
    server_dir = BUILD / "run" / (args.run_name + "-server")
    client_dir = BUILD / "run" / (args.run_name + "-client")
    if any(path.exists() for path in (output, server_dir, client_dir)):
        raise RuntimeError("Choose a new --run-name; earlier evidence and worlds are never overwritten")
    for template in (args.server_template, args.client_template):
        if (template / "world").exists() or (template / "saves").exists():
            raise RuntimeError(f"Use a prepared mod/config template without worlds or saves: {template}")
    java = Path(os.environ["JAVA17_HOME"]) / "bin/java"
    if not java.is_file():
        raise RuntimeError(f"Java 17 is missing: {java}")
    runtime = json.loads((BUILD / "test-runtime.json").read_text())
    addon_meta = next(item for item in runtime["inputHashes"] if item["id"] == "create_maglev")
    addon = Path(addon_meta["file"])
    if helpers.digest(addon) != addon_meta["sha256"] or addon.stat().st_size != addon_meta["bytes"]:
        raise RuntimeError("The production addon no longer matches the prepared runtime descriptor")
    output.mkdir(parents=True)
    control = output / "control"
    control.mkdir()
    shutil.copytree(args.server_template, server_dir)
    shutil.copytree(args.client_template, client_dir)
    report = {
        "minecraft": "1.20.1", "loader": "0.19.5", "pack": "NUKES Homestead 2.0 prepared templates",
        "complete": False, "passed": False, "inputs": [], "clientEvents": [], "checkpoints": [],
        "serverProfile": str(server_dir), "clientProfile": str(client_dir),
        "audio": "Silent private-display capture; no desktop audio or microphone capture",
        "scope": "A moving, fully assembled native Create maglev carriage; genuine on-foot aisle traversal in both directions during measured carriage motion, without seats, flight or movement injection",
        "cameraActions": "Initial exterior and doorway staging are labelled server actions; subsequent turns adjust only view angles, never player positions",
        "renderOverrides": {
            "display": "private 1280x800 Xvfb", "softwareOpenGL": True,
            "glfwLibrary": str(args.glfw_library) if args.glfw_library else None,
            "glxVendor": os.environ.get("__GLX_VENDOR_LIBRARY_NAME"),
            "packShaders": "Original pack default preserved; shaders disabled",
        },
        "visualReview": "Required after capture; native checks alone cannot certify visible enclosure/interior",
        "screenshots": [],
    }
    extras = [addon, Path(runtime["testMod"]), Path(runtime["gameTestApi"])]
    for profile, role in ((server_dir, "server-mod"), (client_dir, "client-mod")):
        for source in extras:
            shutil.copy2(source, profile / "mods" / source.name)
        for path in sorted((profile / "mods").glob("*.jar")):
            helpers.add_input(report["inputs"], path, role)
    if args.glfw_library:
        helpers.add_input(report["inputs"], args.glfw_library, "host-native-glfw")
    write_options(client_dir)
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    (server_dir / "eula.txt").write_text("eula=true\n")
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
motd=PRIVATE MOVING PASSENGER TRAIN DEMONSTRATION
''')
    ipc = output / "private-ipc"
    ipc.mkdir(mode=0o700)
    environment = dict(os.environ, LIBGL_ALWAYS_SOFTWARE="true", XDG_RUNTIME_DIR=str(ipc), XDG_SESSION_TYPE="x11", WAYLAND_DISPLAY="")
    native_result = output / "server-results.json"
    common_jvm = ["-Xms512M", "-Xmx8G", "-Dcreatemaglev.tests=true", "-Dcreatemaglev.tests.walkthrough=true", f"-Dcreatemaglev.tests.controlDir={control}"]
    server_command = [str(java), *common_jvm, f"-Dcreatemaglev.tests.results={native_result}", "-jar", BOOTSTRAP, "nogui"]
    report["serverCommand"] = server_command
    report["port"] = port
    messages: queue.Queue = queue.Queue()
    ready = threading.Event()
    server = client = capture = None
    server_thread = client_thread = None
    result = None
    recording_finished = client_complete = False
    event_stream = (output / "client-events.jsonl").open("w")
    try:
        server = subprocess.Popen(server_command, cwd=server_dir, env=environment, stdin=subprocess.DEVNULL,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                                  errors="replace", bufsize=1, start_new_session=True)
        server_thread = threading.Thread(target=helpers.read_log, args=(server, output / "server.log", None, ready), daemon=True)
        server_thread.start()
        deadline = time.monotonic() + 600
        while not ready.wait(0.5):
            if server.poll() is not None:
                raise RuntimeError(f"Full-pack server exited before readiness: {server.returncode}")
            if time.monotonic() > deadline:
                raise TimeoutError("Full Homestead server startup exceeded ten minutes")
        helpers.server_properties(server_dir, port)
        import minecraft_launcher_lib
        username = "MaglevWalkDemo"
        client_jvm = [*common_jvm, "-Djava.awt.headless=false"]
        if args.glfw_library:
            client_jvm.append("-Dorg.lwjgl.glfw.libname=" + str(args.glfw_library))
        options = {
            "username": username, "uuid": str(uuid.uuid3(uuid.NAMESPACE_DNS, "OfflinePlayer:" + username)),
            "token": "0", "executablePath": str(java), "gameDirectory": str(client_dir),
            "customResolution": True, "resolutionWidth": "1280", "resolutionHeight": "800",
            "jvmArguments": client_jvm, "quickPlayMultiplayer": f"127.0.0.1:{port}",
        }
        command = minecraft_launcher_lib.command.get_minecraft_command("fabric-loader-0.19.5-1.20.1", str(args.minecraft_home), options)
        client = subprocess.Popen(command, cwd=client_dir, env=environment, stdin=subprocess.DEVNULL,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                                  errors="replace", bufsize=1, start_new_session=True)
        client_thread = threading.Thread(target=helpers.read_log, args=(client, output / "client.log", messages, None), daemon=True)
        client_thread.start()
        deadline = time.monotonic() + args.timeout
        while not client_complete:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Passenger walkthrough client timed out")
            try:
                marker, event = messages.get(timeout=min(remaining, 5))
            except queue.Empty:
                if client.poll() is not None:
                    raise RuntimeError(f"Client exited before walkthrough completion: {client.returncode}")
                continue
            if marker in ("process_exit", "reader_error"):
                raise RuntimeError(f"Client stream ended: {marker}: {event}")
            report["clientEvents"].append({"marker": marker, "event": event})
            event_stream.write(json.dumps({"marker": marker, "event": event}) + "\n")
            event_stream.flush()
            if marker == "MAGLEV_WALKTHROUGH_JOINED":
                report["screenshots"].append(screenshot(output / "first-join.png"))
                (control / "onboard.start").touch()
            elif marker == "MAGLEV_WALKTHROUGH_READY":
                report["ready"] = event
                report["screenshots"].append(screenshot(output / "exterior.png"))
                capture = helpers.Capture(output / "moving-passenger-train.mp4", "moving-passenger-train", "Full-size native maglev: actual boarding and two-way on-foot aisle walking while moving")
                (control / "record.start").touch()
                print("RECORDING MOVING ENCLOSED PASSENGER TRAIN", flush=True)
            elif marker == "MAGLEV_WALKTHROUGH_MOVING_READY":
                report["movingReady"] = event
                report["screenshots"].append(screenshot(output / "moving-ready.png"))
                print("NATIVE MOVING CARRIAGE READY", event.get("evidence", {}).get("trainSpeed"), flush=True)
            elif marker == "MAGLEV_WALKTHROUGH_CHECKPOINT":
                report["checkpoints"].append(event)
                name = event["name"]
                report["screenshots"].append(screenshot(output / (name + ".png")))
                print("NATIVE WALK CHECKPOINT", name, event.get("passed"), flush=True)
            elif marker == "MAGLEV_WALKTHROUGH_RESULT":
                result = event
                report["nativeResult"] = result
            elif marker == "MAGLEV_WALKTHROUGH_RECORDING_DONE":
                if capture is None or result is None or not result.get("passed"):
                    raise AssertionError("Capture ended without a native passing walkthrough result")
                report["video"] = capture.finish()
                capture = None
                recording_finished = True
                (control / "record.stop").touch()
            elif marker == "MAGLEV_WALKTHROUGH_COMPLETE":
                client_complete = True
            elif marker == "MAGLEV_WALKTHROUGH_FATAL":
                raise RuntimeError("Native client input failed: " + json.dumps(event))
        client.wait(timeout=90)
        server.wait(timeout=90)
        report["clientReturnCode"], report["serverReturnCode"] = client.returncode, server.returncode
        native = json.loads(native_result.read_text())
        if not native.get("complete") or not native.get("passed"):
            raise AssertionError("Persisted native walking evidence did not report complete/passed")
        if [item["name"] for item in report["checkpoints"]] != ["inside", "rear", "returned"]:
            raise AssertionError("The moving walkthrough must cover boarding and both aisle directions")
        for item in report["inputs"]:
            path = Path(item["file"])
            if helpers.digest(path) != item["sha256"] or path.stat().st_size != item["bytes"]:
                raise AssertionError(f"A packaged input changed during the demonstration: {path}")
        report["serverReport"] = native
        report["complete"] = True
        report["passed"] = recording_finished and result is not None and result.get("passed") is True and client.returncode == 0 and server.returncode == 0
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
        print("PASSENGER DEMONSTRATION ERROR", report["error"], flush=True)
    finally:
        if capture is not None:
            helpers.stop(capture.process)
        helpers.stop(client)
        helpers.stop(server)
        if client_thread is not None:
            client_thread.join(timeout=15)
        if server_thread is not None:
            server_thread.join(timeout=15)
        event_stream.close()
        report.setdefault("clientReturnCode", client.returncode if client is not None else None)
        report.setdefault("serverReturnCode", server.returncode if server is not None else None)
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print("PASSENGER DEMONSTRATION REPORT", output / "report.json", flush=True)
    return 0 if report["passed"] else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-eula", action="store_true", required=True)
    parser.add_argument("--run-name", default="moving-passenger-demo")
    parser.add_argument("--server-template", type=Path, default=BUILD / "homestead-compat/server-template")
    parser.add_argument("--client-template", type=Path, default=BUILD / "homestead-compat/client-template")
    parser.add_argument("--minecraft-home", type=Path, default=ROOT / "work/client-cache")
    parser.add_argument("--glfw-library", type=Path, default=os.environ.get("MAGLEV_GLFW_LIBRARY"))
    parser.add_argument("--timeout", type=float, default=1200)
    args = parser.parse_args()
    if not args.run_name or Path(args.run_name).name != args.run_name or args.run_name in (".", ".."):
        parser.error("--run-name must be a single owned directory name")
    if not os.environ.get("JAVA17_HOME"):
        parser.error("Set JAVA17_HOME to a complete Java 17 installation")
    if os.environ.get("MAGLEV_RECORDED_XVFB") != "1":
        environment = dict(os.environ, MAGLEV_RECORDED_XVFB="1")
        return subprocess.run([
            "xvfb-run", "--auto-servernum", "--server-args=-screen 0 1280x800x24 -nolisten tcp",
            sys.executable, str(Path(__file__).resolve()), *sys.argv[1:],
        ], cwd=ROOT, env=environment, check=False).returncode
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
