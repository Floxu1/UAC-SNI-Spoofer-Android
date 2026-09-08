"""Strip gomobile `go.*` from the Psiphon AAR and rename libgojni.so to libgopsi.so."""
from __future__ import annotations

import io
import os
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = Path(os.environ.get("POW_PSIPHON_SRC", ROOT / "app" / "libs" / "psiphontunnel-2.0.39.aar"))
DST = Path(os.environ.get("POW_PSIPHON_DST", ROOT / "app" / "libs" / "psiphontunnel-isolated.aar"))


def rewrite_jar(src: bytes) -> bytes:
    src_buf = io.BytesIO(src)
    dst_buf = io.BytesIO()
    with zipfile.ZipFile(src_buf, "r") as zin, zipfile.ZipFile(dst_buf, "w") as zout:
        for info in zin.infolist():
            name = info.filename.replace("\\", "/")
            if name == "go" or name.startswith("go/"):
                continue
            zout.writestr(info, zin.read(info.filename))
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
            elif name.endswith("libgojni.so"):
                name = name.replace("libgojni.so", "libgopsi.so")
            info.filename = name
            zout.writestr(info, payload)
    DST.write_bytes(dst_buf.getvalue())
    print(f"wrote {DST} ({DST.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
