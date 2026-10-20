"""Recompress the existing artwork ZIP with DEFLATE 9, preserving every byte."""
import copy
import hashlib
import json
import os
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
ARCHIVE = DIST / "WalletArtwork-Offline.zip"


def sha_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    temporary = ARCHIVE.with_suffix(".repacking.zip")
    before = ARCHIVE.stat().st_size
    expected = {}
    try:
        with zipfile.ZipFile(ARCHIVE) as source, zipfile.ZipFile(
            temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=9, allowZip64=True
        ) as output:
            members = source.infolist()
            if len({member.filename for member in members}) != len(members):
                raise ValueError("Duplicate archive member names")
            output.comment = source.comment
            for index, member in enumerate(members, 1):
                data = source.read(member)
                expected[member.filename] = hashlib.sha256(data).hexdigest()
                info = copy.copy(member)
                info.compress_type = zipfile.ZIP_DEFLATED
                output.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
                if index % 200 == 0:
                    print(f"Compressed {index}/{len(members)} entries", flush=True)
        with zipfile.ZipFile(temporary) as verification:
            if verification.namelist() != list(expected):
                raise ValueError("Archive member list changed")
            for member in verification.infolist():
                # Reading verifies ZIP CRC; SHA verifies equality with the original archive.
                if hashlib.sha256(verification.read(member)).hexdigest() != expected[member.filename]:
                    raise ValueError("Archive content changed: " + member.filename)
        result = {
            "method": "DEFLATE",
            "compressionLevel": 9,
            "entriesVerified": len(expected),
            "beforeBytes": before,
            "afterBytes": temporary.stat().st_size,
            "savedBytes": before - temporary.stat().st_size,
            "sha256": sha_file(temporary),
        }
        os.replace(temporary, ARCHIVE)
        summary_path = DIST / "offline-summary.json"
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        summary.update(archiveBytes=result["afterBytes"], archiveSha256=result["sha256"], compression="DEFLATE-9")
        summary_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
        checksums_path = DIST / "checksums.json"
        if checksums_path.exists():
            checksums = json.loads(checksums_path.read_text(encoding="utf-8"))
            checksums[ARCHIVE.name] = {"bytes": result["afterBytes"], "sha256": result["sha256"]}
            checksums_path.write_text(json.dumps(checksums, indent=2), encoding="utf-8")
        (DIST / "offline-compression.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result), flush=True)
    finally:
        if temporary.exists():
            temporary.unlink()


if __name__ == "__main__":
    main()
