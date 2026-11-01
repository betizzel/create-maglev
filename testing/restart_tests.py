#!/usr/bin/env python3
"""Run the two-JVM native Maglev save/reload/resume regression."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
TESTING = ROOT / "testing"
BUILD = TESTING / "build"
OUTPUT = BUILD / "reports" / "restart"
SERVER_DIR = BUILD / "run" / "restart-server"
RUNTIME_FILE = BUILD / "test-runtime.json"
CHECKPOINT = OUTPUT / "checkpoint.json"
PREPARE_RESULT = OUTPUT / "prepare.json"
VERIFY_RESULT = OUTPUT / "verify.json"


def load_record_helpers():
    source = TESTING / "record_tests.py"
    spec = importlib.util.spec_from_file_location("create_maglev_record_tests", source)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not import runtime audit helpers from {source}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


def read_json(path: Path, description: str) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"Missing {description}: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise AssertionError(f"{description} must contain a JSON object: {path}")
    return value


def run_logged(command: list[str], log_path: Path, environment: dict[str, str], timeout: float) -> int:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log:
        log.write("Command: " + json.dumps(command) + "\n")
        log.flush()
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            text=True,
        )
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired as error:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=10)
            log.write(f"\nCommand timed out after {timeout:.1f} seconds.\n")
            log.flush()
            raise TimeoutError(f"Command exceeded {timeout:.1f}s; inspect {log_path}") from error


def require_passed(result: dict, phase: str) -> None:
    if result.get("phase") != phase or result.get("passed") is not True:
        raise AssertionError(f"{phase} server result did not report phase={phase}, passed=true: {result}")
    if not isinstance(result.get("serverPid"), int) or result["serverPid"] <= 0:
        raise AssertionError(f"{phase} result is missing a valid dedicated-server PID")
    if not isinstance(result.get("serverSession"), str) or not result["serverSession"]:
        raise AssertionError(f"{phase} result is missing its dedicated-server session token")


def check_prepare(checkpoint: dict, result: dict) -> None:
    require_passed(result, "prepare")
    if checkpoint.get("phase") != "prepare" or checkpoint.get("passed") is not True:
        raise AssertionError("Prepare checkpoint JSON is missing its passed=true phase marker")
    for field in ("trainId", "graphId", "serverSession", "serverPid", "destinationName", "train", "power"):
        if field not in checkpoint:
            raise AssertionError(f"Prepare checkpoint is missing required native state field {field!r}")
    train = checkpoint["train"]
    power = checkpoint["power"]
    required_train = ("styleId", "materialId", "trackTypeId", "speed", "targetSpeed", "lift",
                      "scheduleDestination", "leadingPoint", "liveCarriageUuidBeforeRestart")
    if any(field not in train for field in required_train):
        raise AssertionError("Prepare checkpoint is missing stable train/style/material/pose state")
    if train["scheduleDestination"] != checkpoint["destinationName"]:
        raise AssertionError("Prepare checkpoint schedule destination does not match its native station name")
    if abs(float(train["speed"])) < 0.2 or float(train["lift"]) < 0.15:
        raise AssertionError("Prepare checkpoint was not captured during native movement and levitation")
    if not isinstance(train["leadingPoint"], dict) or not all(axis in train["leadingPoint"] for axis in ("x", "y", "z")):
        raise AssertionError("Prepare checkpoint does not contain the real native track pose")
    for field in ("substationPos", "breakerPos", "retainedEnergy", "capacity", "energyBeforeCut",
                  "breakerOpen", "generationStopped", "wireDisconnected", "wireConnectedBeforeCut"):
        if field not in power:
            raise AssertionError(f"Prepare checkpoint is missing power-state field {field!r}")
    retained = int(power["retainedEnergy"])
    if retained <= 512 or retained > int(power["capacity"]):
        raise AssertionError("Prepare checkpoint must retain positive, finite, consumable substation charge")
    if int(power["energyBeforeCut"]) != retained:
        raise AssertionError("The genuine substation buffer changed during the controlled power cutoff")
    if power["breakerOpen"] is not True or power["generationStopped"] is not True \
            or power["wireDisconnected"] is not True or power["wireConnectedBeforeCut"] is not True:
        raise AssertionError("Prepare did not prove a live NewAge source was disconnected and the real breaker opened")
    evidence = result.get("evidence")
    if not isinstance(evidence, dict) or evidence.get("checkpointWritten") is not True \
            or evidence.get("schedulePersisted") is not True or evidence.get("trainRegistryResolved") is not True:
        raise AssertionError("Prepare result is missing schedule/registry/checkpoint evidence")
    if float(evidence.get("nativeMovementBlocks", 0)) < 4 \
            or abs(float(evidence.get("nativeSpeed", 0))) < 0.2 \
            or float(evidence.get("nativeLift", 0)) < 0.15 \
            or int(evidence.get("substationRetainedEnergy", 0)) != retained:
        raise AssertionError("Prepare result does not prove native movement, lift, and retained charge")
    if result["serverSession"] != checkpoint["serverSession"] \
            or result["serverPid"] != checkpoint["serverPid"]:
        raise AssertionError("Prepare result and checkpoint came from different server processes")


def check_verify(checkpoint: dict, result: dict, prepare_result: dict) -> None:
    require_passed(result, "verify")
    if result["serverSession"] == prepare_result["serverSession"]:
        raise AssertionError("Verify did not run in a fresh dedicated-server JVM session")
    evidence = result.get("evidence")
    if not isinstance(evidence, dict):
        raise AssertionError("Verify result is missing reload/resume evidence")
    reload = evidence.get("reload")
    no_power = evidence.get("noPower")
    resume = evidence.get("resume")
    if not all(isinstance(part, dict) for part in (reload, no_power, resume)):
        raise AssertionError("Verify result must report reload, unpowered-braking, and powered-resume evidence")
    train = checkpoint["train"]
    power = checkpoint["power"]
    if reload.get("createRegistryTrainResolved") is not True or reload.get("sameTrainUuid") is not True \
            or reload.get("trainId") != checkpoint["trainId"]:
        raise AssertionError("Verify did not resolve the persisted train by UUID from Create.RAILWAYS")
    if reload.get("liveScheduleRestored") is not True \
            or reload.get("scheduleDestination") != checkpoint["destinationName"]:
        raise AssertionError("Verify did not restore the native Create schedule and destination")
    for field in ("styleId", "materialId", "trackTypeId"):
        if reload.get(field) != train[field]:
            raise AssertionError(f"Verify did not preserve native train invariant {field}: {reload.get(field)!r} != {train[field]!r}")
    if abs(float(reload.get("loadedTargetSpeed", float("inf"))) - float(train["targetSpeed"])) > 1.0e-9 \
            or abs(float(reload.get("checkpointTargetSpeed", float("inf"))) - float(train["targetSpeed"])) > 1.0e-9:
        raise AssertionError("Verify did not preserve the persisted native train target speed")
    if reload.get("substationLoaded") is not True \
            or int(reload.get("retainedEnergy", -1)) != int(power["retainedEnergy"]):
        raise AssertionError("Verify did not load the same real substation and its exact retained energy")
    for field in ("breakerOpenAfterReload", "newAgeWireDisconnectedAfterReload",
                  "newAgeGenerationStoppedAfterReload", "supplyUnavailableAfterReload",
                  "currentLiveCarriageResolvedByUuid", "carriageChunkEntityTickingBeforeFirstTrainTick"):
        if reload.get(field) is not True:
            raise AssertionError(f"Verify reload evidence did not prove {field}")
    if float(reload.get("checkpointPoseDistance", 1)) > 1.0e-5:
        raise AssertionError("Reloaded native train pose differs from its disk checkpoint")
    if no_power.get("nativeEmergencyBrakeObserved") is not True \
            or no_power.get("nativeLandingObserved") is not True \
            or no_power.get("breakerRemainedOpen") is not True \
            or no_power.get("scheduleRetainedDuringBraking") is not True:
        raise AssertionError("Verify did not prove native emergency braking/landing with the breaker open")
    retained = int(power["retainedEnergy"])
    if int(no_power.get("energyBefore", -1)) != retained \
            or int(no_power.get("energyAfter", -1)) != retained:
        raise AssertionError("Closed-off supply changed the finite retained energy during reload braking")
    if abs(float(no_power.get("loadedSpeed", 0))) <= 0.05 \
            or abs(float(no_power.get("stoppedSpeed", 1))) >= 0.001 \
            or float(no_power.get("loadedLift", 0)) <= 0.1 \
            or float(no_power.get("landedLift", 1)) > 0.001:
        raise AssertionError("Verify did not observe braking and landing from the actually persisted moving/hovering state")
    if no_power.get("liveNonzeroBodyObserved") is not True \
            or float(no_power.get("firstLiveSpeed", 0)) <= 0.05 \
            or float(no_power.get("firstLiveLift", 0)) <= 0.1 \
            or int(no_power.get("liveBrakingSamples", 0)) < 2 \
            or int(no_power.get("liveLandingSamples", 0)) < 2 \
            or abs(float(no_power.get("landedPhysicalBodyLift", 1))) > 0.001:
        raise AssertionError("Restart braking/landing was not sampled on a live moving and raised carriage body")
    if resume.get("breakerClosed") is not True or resume.get("poweredByRetainedSubstation") is not True \
            or resume.get("nativePowered") is not True or resume.get("scheduleRetained") is not True:
        raise AssertionError("Verify did not resume native traction using the re-enabled real substation")
    if resume.get("generatorStillStopped") is not True or resume.get("wireStillDisconnected") is not True:
        raise AssertionError("Resume energy did not come from retained charge while the generator stayed isolated")
    if float(resume.get("nativeMovementBlocks", 0)) <= 0.5 \
            or float(resume.get("nativeLift", 0)) <= 0.1 \
            or int(resume.get("energyBefore", -1)) != retained \
            or int(resume.get("energyAfter", retained + 1)) >= retained \
            or int(resume.get("energyConsumed", 0)) <= 0:
        raise AssertionError("Verify did not prove schedule motion/lift and consumption from persisted finite charge")


def verify_inputs(record_helpers, inputs: list[dict]) -> None:
    for item in inputs:
        path = Path(item["file"])
        if not path.is_file():
            raise FileNotFoundError(f"Audited runtime input disappeared: {path}")
        actual_size = path.stat().st_size
        actual_digest = record_helpers.digest(path)
        if actual_size != item["bytes"] or actual_digest != item["sha256"]:
            raise AssertionError(f"Audited runtime input changed during restart run: {path}")


def run(args: argparse.Namespace) -> int:
    record_helpers = load_record_helpers()
    record_helpers.safe_reset_directory(OUTPUT)
    report = {
        "minecraft": "1.20.1",
        "loader": "fabric-loader-0.19.5",
        "complete": False,
        "passed": False,
        "world": str(SERVER_DIR),
        "worldReusedBetweenPhases": False,
        "inputs": [],
        "phaseCommands": {},
        "phaseResults": {},
        "logs": {},
    }
    write_report(OUTPUT / "report.json", report)
    deadline = time.monotonic() + args.timeout
    environment = dict(os.environ)

    def remaining_timeout() -> float:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"Restart regression exceeded its {args.timeout:.1f}s overall timeout")
        return remaining

    build_script = ROOT / "build.sh"
    prepare_runtime_command = [
        str(build_script), ":packageRuntime", ":testing:prepareTestRuntime",
        "--no-daemon", "--console=plain", "-PtestEula=true",
    ]
    report["prepareRuntimeCommand"] = prepare_runtime_command
    runtime_log = OUTPUT / "prepare-runtime.log"
    report["logs"]["prepareRuntime"] = runtime_log.name
    successful = False
    try:
        runtime_exit = run_logged(prepare_runtime_command, runtime_log, environment, remaining_timeout())
        report["prepareRuntimeReturnCode"] = runtime_exit
        if runtime_exit != 0:
            raise RuntimeError(f"Production/test runtime preparation failed with exit code {runtime_exit}; see {runtime_log}")

        inputs: list[dict] = []
        runtime, _runtime_paths = record_helpers.audit_runtime(RUNTIME_FILE, inputs)
        report["inputs"] = inputs
        report["runtimeAudit"] = {
            "manifest": runtime["productionManifest"],
            "testHelper": runtime["testMod"],
            "gameTestApi": runtime["gameTestApi"],
            "inputCount": len(inputs),
        }
        write_report(OUTPUT / "input-hashes.json", {"inputs": inputs})
        verify_inputs(record_helpers, inputs)

        phase_properties = [
            "-PtestEula=true",
        ]
        prepare_command = [str(build_script), ":testing:runRestartPrepare", "--no-daemon", "--console=plain", *phase_properties]
        report["phaseCommands"]["prepare"] = prepare_command
        prepare_log = OUTPUT / "prepare.log"
        report["logs"]["prepare"] = prepare_log.name
        prepare_exit = run_logged(prepare_command, prepare_log, environment, remaining_timeout())
        report["phaseReturnCodes"] = {"prepare": prepare_exit}
        if PREPARE_RESULT.is_file():
            report["phaseResults"]["prepare"] = read_json(PREPARE_RESULT, "prepare phase result")
        if CHECKPOINT.is_file():
            report["checkpoint"] = read_json(CHECKPOINT, "native train checkpoint")
        if prepare_exit != 0:
            raise RuntimeError(f"Restart prepare Gradle task failed with exit code {prepare_exit}; see {prepare_log}")
        checkpoint = report.get("checkpoint")
        prepare_result = report["phaseResults"].get("prepare")
        if not isinstance(checkpoint, dict) or not isinstance(prepare_result, dict):
            raise FileNotFoundError("Restart prepare did not retain both checkpoint.json and prepare.json")
        check_prepare(checkpoint, prepare_result)
        verify_inputs(record_helpers, inputs)

        level_dat = SERVER_DIR / "world" / "level.dat"
        if not level_dat.is_file():
            raise FileNotFoundError(f"Prepare did not leave its saved dedicated-server world: {level_dat}")
        report["worldBeforeVerify"] = {
            "levelDat": str(level_dat),
            "levelDatSha256": record_helpers.digest(level_dat),
            "levelDatBytes": level_dat.stat().st_size,
        }
        report["worldReusedBetweenPhases"] = True

        verify_command = [str(build_script), ":testing:runRestartVerify", "--no-daemon", "--console=plain", *phase_properties]
        report["phaseCommands"]["verify"] = verify_command
        verify_log = OUTPUT / "verify.log"
        report["logs"]["verify"] = verify_log.name
        verify_exit = run_logged(verify_command, verify_log, environment, remaining_timeout())
        report["phaseReturnCodes"]["verify"] = verify_exit
        if VERIFY_RESULT.is_file():
            report["phaseResults"]["verify"] = read_json(VERIFY_RESULT, "verify phase result")
        if verify_exit != 0:
            raise RuntimeError(f"Restart verify Gradle task failed with exit code {verify_exit}; see {verify_log}")
        verify_result = report["phaseResults"].get("verify")
        if not isinstance(verify_result, dict):
            raise FileNotFoundError("Restart verify did not retain verify.json")
        check_verify(checkpoint, verify_result, prepare_result)
        report["serverProcesses"] = {
            "prepare": {
                "pid": prepare_result["serverPid"],
                "session": prepare_result["serverSession"],
            },
            "verify": {
                "pid": verify_result["serverPid"],
                "session": verify_result["serverSession"],
            },
            "freshJvmSession": verify_result["serverSession"] != prepare_result["serverSession"],
        }
        report["serverJvmRestartProven"] = report["serverProcesses"]["freshJvmSession"]
        verify_inputs(record_helpers, inputs)
        if not level_dat.is_file():
            raise FileNotFoundError(f"Verify did not leave the reopened dedicated-server world intact: {level_dat}")
        report["worldAfterVerify"] = {
            "levelDatSha256": record_helpers.digest(level_dat),
            "levelDatBytes": level_dat.stat().st_size,
        }
        report["complete"] = True
        report["passed"] = True
        successful = True
    except BaseException as error:
        report["error"] = f"{type(error).__name__}: {error}"
        report["complete"] = False
        report["passed"] = False
        print(f"Restart regression failed: {report['error']}", file=sys.stderr, flush=True)
    finally:
        artifact_paths = {
            "checkpoint": CHECKPOINT,
            "prepareResult": PREPARE_RESULT,
            "verifyResult": VERIFY_RESULT,
            "prepareRuntimeLog": OUTPUT / "prepare-runtime.log",
            "prepareLog": OUTPUT / "prepare.log",
            "verifyLog": OUTPUT / "verify.log",
            "inputHashes": OUTPUT / "input-hashes.json",
        }
        report["retainedArtifacts"] = {}
        for label, path in artifact_paths.items():
            if path.is_file():
                report["retainedArtifacts"][label] = {
                    "file": path.name,
                    "bytes": path.stat().st_size,
                    "sha256": record_helpers.digest(path),
                }
        report["inputHashesFile"] = "input-hashes.json" if (OUTPUT / "input-hashes.json").is_file() else None
        report["finishedAtUnix"] = time.time()
        write_report(OUTPUT / "report.json", report)
        print(f"Restart regression report and dedicated-server logs: {OUTPUT}", flush=True)
    return 0 if successful else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-eula", action="store_true",
                        help="Explicitly agree to the Minecraft EULA for this private disposable test world")
    parser.add_argument("--timeout", type=float, default=1200,
                        help="Maximum total runtime-preparation and two-phase duration in seconds (default: 1200)")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Pass --accept-eula only after explicitly agreeing to https://aka.ms/MinecraftEULA")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
