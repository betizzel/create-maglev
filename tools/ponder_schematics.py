#!/usr/bin/env python3
"""Generate the addon Ponder schematics as deterministic 1.20.1 structure NBT."""
from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
import gzip
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "src/main/resources/assets/create_maglev/ponder"
DATA_VERSION = 3465


@dataclass(frozen=True, order=True)
class BlockState:
    name: str
    properties: tuple[tuple[str, str], ...] = ()


class Schematic:
    def __init__(self, size: tuple[int, int, int]):
        self.size = size
        self.blocks: dict[tuple[int, int, int], BlockState] = {}

    def set(self, x: int, y: int, z: int, name: str, **properties: str) -> None:
        if not (0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2]):
            raise ValueError(f"Block {(x, y, z)} is outside schematic bounds {self.size}")
        if ":" not in name:
            raise ValueError(f"Block id is not namespaced: {name}")
        self.blocks[(x, y, z)] = BlockState(name, tuple(sorted(properties.items())))

    def fill(self, first: tuple[int, int, int], last: tuple[int, int, int], name: str,
             **properties: str) -> None:
        for x in range(first[0], last[0] + 1):
            for y in range(first[1], last[1] + 1):
                for z in range(first[2], last[2] + 1):
                    self.set(x, y, z, name, **properties)

    def floor(self, block: str = "minecraft:gray_concrete") -> None:
        self.fill((0, 0, 0), (self.size[0] - 1, 0, self.size[2] - 1), block)

    def encode(self) -> bytes:
        states = sorted(set(self.blocks.values()))
        state_ids = {state: index for index, state in enumerate(states)}
        palette = [_compound_payload([
            _named(8, "Name", _string_payload(state.name)),
            *([_named(10, "Properties", _compound_payload([
                _named(8, name, _string_payload(value)) for name, value in state.properties
            ]))] if state.properties else []),
        ]) for state in states]
        block_tags = []
        for (x, y, z), state in sorted(self.blocks.items(), key=lambda item: (item[0][1], item[0][2], item[0][0])):
            block_tags.append(_compound_payload([
                _named(9, "pos", _list_payload(3, [struct.pack(">i", value) for value in (x, y, z)])),
                _named(3, "state", struct.pack(">i", state_ids[state])),
            ]))
        root = _compound_payload([
            _named(3, "DataVersion", struct.pack(">i", DATA_VERSION)),
            _named(9, "size", _list_payload(3, [struct.pack(">i", value) for value in self.size])),
            _named(9, "palette", _list_payload(10, palette)),
            _named(9, "blocks", _list_payload(10, block_tags)),
            _named(9, "entities", _list_payload(10, [])),
        ])
        return b"\x0a" + _utf("") + root


def _utf(value: str) -> bytes:
    encoded = value.encode("utf-8")
    if len(encoded) > 0xFFFF:
        raise ValueError("NBT string is longer than 65535 bytes")
    return struct.pack(">H", len(encoded)) + encoded


def _named(tag_type: int, name: str, payload: bytes) -> bytes:
    return bytes((tag_type,)) + _utf(name) + payload


def _compound_payload(tags: list[bytes]) -> bytes:
    return b"".join(tags) + b"\x00"


def _string_payload(value: str) -> bytes:
    return _utf(value)


def _list_payload(element_type: int, payloads: list[bytes]) -> bytes:
    return bytes((element_type,)) + struct.pack(">i", len(payloads)) + b"".join(payloads)


def traction_substation() -> Schematic:
    schematic = Schematic((15, 6, 11))
    schematic.floor()

    schematic.fill((1, 0, 2), (5, 0, 4), "minecraft:polished_andesite")
    schematic.set(2, 1, 3, "create_new_age:stirling_engine", axis="x")
    schematic.set(3, 1, 3, "create:shaft", axis="x")
    schematic.set(4, 1, 3, "create_new_age:generator_coil", axis="x")
    schematic.set(5, 1, 3, "create:andesite_casing")
    schematic.set(5, 2, 3, "create_new_age:electrical_connector", facing="up", mode="pull")

    schematic.set(9, 1, 5, "create_maglev:traction_substation", powered="false")
    schematic.set(9, 2, 5, "create_new_age:electrical_connector", facing="up", mode="pull")
    schematic.set(9, 1, 6, "minecraft:lever", face="floor", facing="north", powered="false")
    schematic.set(10, 1, 5, "minecraft:comparator", facing="east", mode="compare", powered="false")

    for z in range(1, 10):
        schematic.set(12, 1, z, "create:track", shape="zo", turn="false", waterlogged="false")
    schematic.set(12, 2, 5, "create_maglev:electric_bogey", axis="x")
    return schematic


def maglev_guideway() -> Schematic:
    schematic = Schematic((13, 6, 10))
    schematic.floor()

    for z in range(1, 9):
        schematic.set(2, 1, z, "create:track", shape="zo", turn="false", waterlogged="false")
        schematic.set(6, 1, z, "create_maglev:maglev_track", shape="zo")
    schematic.set(6, 2, 3, "create_maglev:maglev_bogey", axis="x")
    schematic.set(6, 2, 7, "create_maglev:maglev_bogey", axis="x")

    schematic.fill((4, 3, 3), (8, 3, 7), "minecraft:iron_block")
    schematic.fill((4, 4, 3), (8, 4, 3), "minecraft:white_concrete")
    schematic.fill((4, 4, 7), (8, 4, 7), "minecraft:white_concrete")
    schematic.fill((4, 4, 4), (4, 4, 6), "minecraft:light_blue_stained_glass")
    schematic.fill((8, 4, 4), (8, 4, 6), "minecraft:light_blue_stained_glass")
    schematic.fill((4, 5, 3), (8, 5, 7), "minecraft:light_gray_concrete")
    schematic.set(10, 1, 5, "create_maglev:traction_substation", powered="false")
    return schematic


def electric_and_bullet_bogeys() -> Schematic:
    schematic = Schematic((16, 6, 10))
    schematic.floor("minecraft:light_gray_concrete")

    for x in range(1, 7):
        schematic.set(x, 1, 3, "create:track", shape="xo", turn="false", waterlogged="false")
        schematic.set(x, 1, 6, "create_maglev:reinforced_track", shape="xo")
    schematic.set(3, 2, 3, "create_maglev:electric_bogey", axis="z")
    schematic.set(3, 2, 6, "create_maglev:bullet_bogey", axis="z")

    # A broad diagonal transition and a tighter turn illustrate curve-radius limits.
    schematic.set(9, 1, 1, "create:track", shape="xo", turn="false", waterlogged="false")
    schematic.set(10, 1, 1, "create:track", shape="xo", turn="false", waterlogged="false")
    schematic.set(11, 1, 1, "create:track", shape="pd", turn="false", waterlogged="false")
    schematic.set(12, 1, 2, "create:track", shape="pd", turn="false", waterlogged="false")
    schematic.set(13, 1, 3, "create:track", shape="pd", turn="false", waterlogged="false")
    schematic.set(14, 1, 4, "create:track", shape="xo", turn="false", waterlogged="false")

    schematic.set(9, 1, 7, "create_maglev:reinforced_track", shape="xo")
    schematic.set(10, 1, 7, "create_maglev:reinforced_track", shape="pd")
    schematic.set(10, 1, 8, "create_maglev:reinforced_track", shape="zo")
    return schematic


def main() -> None:
    schematics = {
        "traction_substation": traction_substation(),
        "maglev": maglev_guideway(),
        "electric_bullet_bogeys": electric_and_bullet_bogeys(),
    }
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, schematic in schematics.items():
        target = OUTPUT / f"{name}.nbt"
        with BytesIO() as compressed:
            with gzip.GzipFile(fileobj=compressed, mode="wb", filename="", compresslevel=9, mtime=0) as stream:
                stream.write(schematic.encode())
            payload = compressed.getvalue()
        target.write_bytes(payload)
        print(f"{target.relative_to(ROOT)}: {len(payload)} bytes, {len(schematic.blocks)} blocks")


if __name__ == "__main__":
    main()
