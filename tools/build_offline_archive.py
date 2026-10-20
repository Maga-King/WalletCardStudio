"""Build the app's verified offline original-art archive without network requests."""
import hashlib
import io
import json
from pathlib import Path
import zipfile

from PIL import Image
import pymupdf

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"


def read(path):
    data = json.loads(path.read_text(encoding="utf-8"))
    return data if isinstance(data, list) else data["cards"]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    output = DIST / "WalletArtwork-Offline.zip"
    manifest = {"version": 1, "created": "2026-10-20", "cards": []}
    written = set()
    identities = {}
    counts = {}
    raw_bytes = 0
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        def add_bytes(name, data):
            nonlocal raw_bytes
            if name not in written:
                archive.writestr(name, data)
                written.add(name)
                raw_bytes += len(data)

        def add_art(row, path, collection, base):
            original_data = path.read_bytes()
            data = original_data
            original_file = ""
            if path.suffix.lower() == ".svg":
                # Keep the unmodified vector original alongside a bitmap for Android cropping.
                original_file = "originals/" + digest(data) + ".svg"
                add_bytes(original_file, data)
                doc = pymupdf.open(stream=data, filetype="svg")
                page = doc[0]
                factor = min(2.0, 4096 / max(page.rect.width, page.rect.height))
                data = page.get_pixmap(matrix=pymupdf.Matrix(factor, factor), alpha=True).tobytes("png")
                doc.close()
            with Image.open(io.BytesIO(data)) as image:
                width, height = image.size
                mime = {"PNG": "image/png", "JPEG": "image/jpeg", "WEBP": "image/webp", "GIF": "image/gif"}[image.format]
                ext = {"PNG": "png", "JPEG": "jpg", "WEBP": "webp", "GIF": "gif"}[image.format]
                image.verify()
            sha = digest(data)
            url = row.get("url", "")
            if not url.startswith("https://"):
                url = ""
            source_page = row.get("sourcePage", "")
            identity = url or "offline:" + source_page + "\n" + (row.get("url") or row.get("file") or path.name)
            key = hashlib.sha256(identity.encode("utf-8")).hexdigest()
            file = "images/" + key + "." + ext
            # Duplicate URLs share one original while retaining all product names as aliases.
            if key in identities:
                previous = identities[key]
                if previous["sha256"] != sha:
                    raise ValueError("One image identity refers to different original bytes: " + identity)
                name = row.get("name", path.stem)
                if name != previous["name"] and name not in previous["aliases"]:
                    previous["aliases"].append(name)
                return
            add_bytes(file, data)
            if not original_file and row.get("original"):
                original = base / row["original"]
                if original.is_file():
                    source = original.read_bytes()
                    original_file = "originals/" + digest(source) + original.suffix.lower()
                    add_bytes(original_file, source)
            aliases = []
            for product in row.get("products", []):
                alias = product.get("issuer", "") + " · " + product.get("name", "")
                if alias != row.get("name") and alias not in aliases:
                    aliases.append(alias)
            item = {"key": key, "name": row.get("name", path.stem), "aliases": aliases,
                    "url": url, "source": row.get("source", ""), "sourcePage": source_page,
                    "collection": collection, "file": file, "sha256": sha,
                    "bytes": len(data), "mime": mime, "width": width, "height": height}
            if original_file:
                item["originalFile"] = original_file
            if row.get("sourceUrl"):
                item["attachmentUrl"] = row["sourceUrl"]
            identities[key] = item
            manifest["cards"].append(item)
            counts[collection] = counts.get(collection, 0) + 1

        site = DIST / "Cardentify"
        for row in read(site / "catalog.json"):
            add_art(row, site / row["file"], "cardentify", site)
        issues = DIST / "Cardentify-Issues"
        for row in read(issues / "catalog.json"):
            add_art(row, issues / row["url"], "cardentify-issues", issues)
        xiaomi = DIST / "Xiaomi-Artwork"
        for row in read(xiaomi / "catalog.json"):
            add_art(row, xiaomi / row["file"], "xiaomi-transit", xiaomi)
        community = DIST / "Extra-Artwork"
        for row in read(community / "catalog.json"):
            if row.get("file"):
                add_art(row, DIST / row["file"], "community", community)
        for row in read(community / "APK-bundled/catalog.json"):
            add_art(row, DIST / row["file"], "community", community)
        archive.writestr("catalog.json", json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8"))
        archive.writestr("README.txt", "原始卡面离线包。导入钱包卡面软件后，本地浏览、搜索、裁切均不访问网站。\nCardentify 网站与 Issue、Xiaomi 公交、MiPayCard 社区来源分别保留。\nSVG 原件及投稿 ZIP/PDF 原件放在 originals，Android 使用的位图单独保存。\n不同完整网址和不同版本不按名称或近似图像去重。\n".encode("utf-8"))
    (DIST / "offline-catalog.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    summary = {"entries": len(manifest["cards"]), "collections": counts, "uncompressedBytes": raw_bytes, "compression": "DEFLATE-9",
               "archiveBytes": output.stat().st_size, "archiveSha256": digest(output.read_bytes())}
    (DIST / "offline-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary), flush=True)


if __name__ == "__main__":
    main()
