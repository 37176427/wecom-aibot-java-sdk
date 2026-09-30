#!/usr/bin/env python3
"""Download pinned public test data only; never reads or writes robot credentials."""
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

COMMIT = "8c6678b657ede1e7883fc164ef73ed483c7796c3"
BASE = f"https://raw.githubusercontent.com/androidx/media/{COMMIT}/"
ASSETS = [
    ("tone.amr", "libraries/test_data/src/test/assets/media/amr/sine-nb.amr",
     "610c0164856207c928a003004b4884ba601eef10cc34a509daa0fc9e32ed7393"),
    ("short-video.mp4", "libraries/test_data/src/test/assets/media/mp4/sample_15fps_720p_1s.mp4",
     "ad21129b03005963ad900a6ed64263cf026dcd049e1977ec8f60fdb3143cdd2a"),
]
root = Path(__file__).resolve().parents[1] / "target" / "sandbox-media"
root.mkdir(parents=True, exist_ok=True)
records = []
for name, source, expected in ASSETS:
    with urlopen(BASE + source, timeout=30) as response:
        data = response.read(2 * 1024 * 1024 + 1)
    digest = hashlib.sha256(data).hexdigest()
    if digest != expected:
        raise RuntimeError(f"Public fixture checksum mismatch: {name}")
    (root / name).write_bytes(data)
    records.append({"file": name, "source": BASE + source, "bytes": len(data), "sha256": digest})
with urlopen(BASE + "LICENSE", timeout=30) as response:
    (root / "LICENSE-AndroidX").write_bytes(response.read())
(root / "sources.json").write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
print(f"WECOM_TEST_VOICE_FILE={root / 'tone.amr'}")
print(f"WECOM_TEST_VIDEO_FILE={root / 'short-video.mp4'}")
print("WECOM_TEST_CARD_IMAGE_URL=https://www.w3.org/Icons/w3c_home.png")
