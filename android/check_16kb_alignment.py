#!/usr/bin/env python3
"""检查 APK 中 AArch64 ELF 的 16 KB LOAD 对齐和 lib/ 下的 ZIP 对齐。"""

from __future__ import annotations

import struct
import sys
import zipfile
from pathlib import Path

PAGE_SIZE = 16 * 1024
EM_AARCH64 = 183
PT_LOAD = 1


def elf_issues(name: str, data: bytes) -> tuple[bool, list[str]]:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return False, []

    elf_class = data[4]
    byte_order = data[5]
    if byte_order == 1:
        endian = "<"
    elif byte_order == 2:
        endian = ">"
    else:
        return False, [f"{name}: ELF 字节序无效"]

    machine = struct.unpack_from(endian + "H", data, 18)[0]
    if machine != EM_AARCH64:
        return False, []

    if elf_class == 2:  # ELF64
        phoff = struct.unpack_from(endian + "Q", data, 32)[0]
        phentsize = struct.unpack_from(endian + "H", data, 54)[0]
        phnum = struct.unpack_from(endian + "H", data, 56)[0]
        align_offset = 48
        offset_offset = 8
        vaddr_offset = 16
        word_format = "Q"
    elif elf_class == 1:  # ELF32（理论上不会是 AArch64）
        phoff = struct.unpack_from(endian + "I", data, 28)[0]
        phentsize = struct.unpack_from(endian + "H", data, 42)[0]
        phnum = struct.unpack_from(endian + "H", data, 44)[0]
        align_offset = 28
        offset_offset = 4
        vaddr_offset = 8
        word_format = "I"
    else:
        return True, [f"{name}: 未知 ELF class {elf_class}"]

    problems: list[str] = []
    load_count = 0
    for index in range(phnum):
        base = phoff + index * phentsize
        if base + phentsize > len(data):
            problems.append(f"{name}: 程序头被截断")
            break
        p_type = struct.unpack_from(endian + "I", data, base)[0]
        if p_type != PT_LOAD:
            continue
        load_count += 1
        p_offset = struct.unpack_from(endian + word_format, data, base + offset_offset)[0]
        p_vaddr = struct.unpack_from(endian + word_format, data, base + vaddr_offset)[0]
        p_align = struct.unpack_from(endian + word_format, data, base + align_offset)[0]
        if p_align < PAGE_SIZE:
            problems.append(
                f"{name}: PT_LOAD[{index}] p_align=0x{p_align:x}，小于 0x4000"
            )
        if p_offset % PAGE_SIZE != p_vaddr % PAGE_SIZE:
            problems.append(
                f"{name}: PT_LOAD[{index}] offset/vaddr 不满足 16 KB 同余对齐"
            )

    if load_count == 0:
        problems.append(f"{name}: 没有 PT_LOAD 段")
    return True, problems


def local_data_offset(apk_file, info: zipfile.ZipInfo) -> int:
    apk_file.seek(info.header_offset)
    header = apk_file.read(30)
    if len(header) != 30:
        raise ValueError("ZIP 本地文件头被截断")
    fields = struct.unpack("<IHHHHHIIIHH", header)
    if fields[0] != 0x04034B50:
        raise ValueError("ZIP 本地文件头签名错误")
    return info.header_offset + 30 + fields[-2] + fields[-1]


def main() -> int:
    if len(sys.argv) != 2:
        print(f"用法: {Path(sys.argv[0]).name} <apk>")
        return 2

    apk = Path(sys.argv[1])
    if not apk.is_file():
        print(f"APK 不存在: {apk}")
        return 2

    problems: list[str] = []
    arm64_count = 0

    with apk.open("rb") as raw, zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            if not info.filename.endswith(".so"):
                continue
            data = archive.read(info)
            is_arm64, current = elf_issues(info.filename, data)
            if not is_arm64:
                continue
            arm64_count += 1
            problems.extend(current)

            if info.filename.startswith("lib/arm64-v8a/"):
                if info.compress_type != zipfile.ZIP_STORED:
                    problems.append(f"{info.filename}: native library 被压缩，无法直接页对齐加载")
                else:
                    try:
                        offset = local_data_offset(raw, info)
                        if offset % PAGE_SIZE:
                            problems.append(
                                f"{info.filename}: APK 数据偏移 0x{offset:x} 未按 16 KB 对齐"
                            )
                    except ValueError as exc:
                        problems.append(f"{info.filename}: {exc}")

    if arm64_count == 0:
        problems.append("APK 中没有检测到 AArch64 ELF")

    print(f"检查了 {arm64_count} 个 AArch64 ELF")
    if problems:
        print("16 KB 兼容性检查发现问题：")
        for problem in problems:
            print(f"::warning::{problem}")
        return 1

    print("16 KB ELF 与 APK 对齐检查通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
