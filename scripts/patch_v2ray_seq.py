"""Patch libv2ray Seq.<clinit> to call GoJniLoader.load() instead of loadLibrary("gojni").

gomobile always hardcodes System.loadLibrary("gojni"). UAC PoW loads Psiphon's
runtime as libgopsi.so in :uacpow, so Seq must pick the library from the process name.
"""
from __future__ import annotations

import io
import os
import struct
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = Path(os.environ.get("POW_V2RAY_SRC", ROOT / "app" / "libs" / "libv2ray-native-tun.aar"))
DST = Path(os.environ.get("POW_V2RAY_DST", ROOT / "app" / "libs" / "libv2ray-seqpatched.aar"))
LOADER = "com/uacspoofer/mobile/engine/pow/GoJniLoader"


def parse_constant_pool(data: bytes) -> tuple[int, int, dict[int, tuple]]:
    count = struct.unpack_from(">H", data, 8)[0]
    i = 10
    idx = 1
    entries: dict[int, tuple] = {}
    while idx < count:
        tag = data[i]
        i += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, i)[0]
            i += 2
            raw = data[i : i + length]
            i += length
            entries[idx] = ("utf8", raw.decode("utf-8", "replace"))
        elif tag == 7:
            name_idx = struct.unpack_from(">H", data, i)[0]
            i += 2
            entries[idx] = ("class", name_idx)
        elif tag == 8:
            utf_idx = struct.unpack_from(">H", data, i)[0]
            i += 2
            entries[idx] = ("string", utf_idx)
        elif tag in (16, 19, 20):
            i += 2
            entries[idx] = ("skip2",)
        elif tag in (3, 4):
            i += 4
            entries[idx] = ("u4",)
        elif tag in (9, 10, 11, 12, 17, 18):
            i += 4
            entries[idx] = ("ref",)
        elif tag in (5, 6):
            i += 8
            entries[idx] = ("wide",)
            idx += 1
        elif tag == 15:
            i += 3
            entries[idx] = ("methodhandle",)
        else:
            raise ValueError(f"unknown constant tag {tag} at {i}")
        idx += 1
    return count, i, entries


def append_methodref(data: bytes, class_name: str, method: str, desc: str) -> tuple[bytes, int]:
    count, end, _ = parse_constant_pool(data)
    extra = bytearray()

    def utf8(text: str) -> int:
        nonlocal count
        encoded = text.encode("utf-8")
        extra.append(1)
        extra.extend(struct.pack(">H", len(encoded)))
        extra.extend(encoded)
        idx = count
        count += 1
        return idx

    def class_info(name_idx: int) -> int:
        nonlocal count
        extra.append(7)
        extra.extend(struct.pack(">H", name_idx))
        idx = count
        count += 1
        return idx

    def name_and_type(name_idx: int, desc_idx: int) -> int:
        nonlocal count
        extra.append(12)
        extra.extend(struct.pack(">H", name_idx))
        extra.extend(struct.pack(">H", desc_idx))
        idx = count
        count += 1
        return idx

    def methodref(cls_idx: int, nat_idx: int) -> int:
        nonlocal count
        extra.append(10)
        extra.extend(struct.pack(">H", cls_idx))
        extra.extend(struct.pack(">H", nat_idx))
        idx = count
        count += 1
        return idx

    cls_utf = utf8(class_name)
    method_utf = utf8(method)
    desc_utf = utf8(desc)
    cls_idx = class_info(cls_utf)
    nat_idx = name_and_type(method_utf, desc_utf)
    mref = methodref(cls_idx, nat_idx)
    patched = bytearray(data)
    struct.pack_into(">H", patched, 8, count)
    patched[end:end] = extra
    return bytes(patched), mref


def patch_seq(data: bytes) -> bytes:
    _, _, entries = parse_constant_pool(data)
    utf8 = {idx: value[1] for idx, value in entries.items() if value[0] == "utf8"}
    gojni_string = next(
        idx for idx, value in entries.items()
        if value[0] == "string" and utf8.get(value[1]) == "gojni"
    )
    patched, mref = append_methodref(data, LOADER, "load", "()V")
    ldc = bytes((0x12, gojni_string)) if gojni_string < 256 else bytes((0x13, gojni_string >> 8, gojni_string & 0xFF))
    found = patched.find(ldc)
    if found < 0:
        raise SystemExit(f"ldc String gojni (#{gojni_string}) not found")
    invoke_at = found + len(ldc)
    if patched[invoke_at] != 0xB8:
        raise SystemExit(f"expected invokestatic after ldc gojni, got {patched[invoke_at]:#x}")
    old_total = len(ldc) + 3
    replacement = bytes((0xB8, (mref >> 8) & 0xFF, mref & 0xFF)) + (b"\x00" * (old_total - 3))
    body = bytearray(patched)
    body[found : found + old_total] = replacement
    if LOADER.encode("utf-8") not in body:
        raise SystemExit("GoJniLoader was not added to the constant pool")
    return bytes(body)


def rewrite_jar(src: bytes) -> bytes:
    src_buf = io.BytesIO(src)
    dst_buf = io.BytesIO()
    with zipfile.ZipFile(src_buf, "r") as zin, zipfile.ZipFile(dst_buf, "w") as zout:
        for info in zin.infolist():
            payload = zin.read(info.filename)
            name = info.filename.replace("\\", "/")
            if name == "go/Seq.class":
                payload = patch_seq(payload)
            zout.writestr(info, payload)
    return dst_buf.getvalue()


def main() -> None:
    DST.parent.mkdir(parents=True, exist_ok=True)
    dst_buf = io.BytesIO()
    with zipfile.ZipFile(SRC, "r") as zin, zipfile.ZipFile(dst_buf, "w") as zout:
        for info in zin.infolist():
            payload = zin.read(info.filename)
            name = info.filename.replace("\\", "/")
            if name == "classes.jar":
                payload = rewrite_jar(payload)
            info.filename = name
            zout.writestr(info, payload)
    DST.write_bytes(dst_buf.getvalue())
    print(f"wrote {DST} ({DST.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
