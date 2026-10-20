"""Download the public Cardentify catalog and original image bytes (resumable).

Requires: pip install requests Pillow
Usage: python tools/download_cardentify.py --output dist/Cardentify --source all
       python tools/download_cardentify.py --output dist/Cardentify-MiPay --source "Mi Pay"
"""
import argparse
import collections
import concurrent.futures
import hashlib
import html
import io
import json
from pathlib import Path
import threading
import time
import xml.etree.ElementTree as ET

import requests
from PIL import Image

API = "https://cards.no2.ac/api/cards"
SITE = "https://cards.no2.ac/"
MAX = 24 * 1024 * 1024
local = threading.local()


def session():
    if not hasattr(local, "session"):
        local.session = requests.Session()
        local.session.headers["User-Agent"] = "WalletCardStudio/1.0 (personal artwork archive)"
    return local.session


def write_json(path, value):
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("dist/Cardentify"))
    parser.add_argument("--source", default="all")
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()
    out = args.output
    (out / "images").mkdir(parents=True, exist_ok=True)
    response = session().get(API, timeout=(12, 35))
    response.raise_for_status()
    raw = response.json()
    if not isinstance(raw, list):
        raise ValueError("Unexpected catalog response")
    write_json(out / "upstream-catalog.json", raw)
    items = {}
    for product in raw:
        for art in product.get("images", []):
            if args.source != "all" and art.get("source") != args.source:
                continue
            url = art["image"]
            if not url.startswith("https://"):
                continue
            item = items.setdefault(url, {
                "name": product["issuer"]["name"] + " · " + product["name"],
                "url": url, "source": art.get("source") or "未标注",
                "sourcePage": SITE, "assetId": art["asset_id"],
                "products": [], "lowQuality": bool(art.get("low_quality")),
            })
            item["products"].append({"id": product["id"], "name": product["name"],
                                     "issuer": product["issuer"]["name"], "source": art.get("source")})
    cards = list(items.values())
    print(f"Catalog: {len(raw)} products, {len(cards)} unique images selected", flush=True)
    old = out / "catalog.json"
    prior = {x["url"]: x for x in json.loads(old.read_text(encoding="utf-8")).get("cards", [])} if old.exists() else {}

    def download(item):
        previous = prior.get(item["url"], {})
        if previous.get("file"):
            f = out / previous["file"]
            if f.is_file() and hashlib.sha256(f.read_bytes()).hexdigest() == previous.get("sha256"):
                item.update({k: previous[k] for k in ("file", "sha256", "bytes", "width", "height")})
                return item
        for attempt in range(3):
            try:
                with session().get(item["url"], stream=True, timeout=(12, 30)) as r:
                    r.raise_for_status()
                    if int(r.headers.get("Content-Length", 0)) > MAX:
                        raise ValueError("Image exceeds 24 MB")
                    data = bytearray()
                    for chunk in r.iter_content(65536):
                        data.extend(chunk)
                        if len(data) > MAX:
                            raise ValueError("Image exceeds 24 MB")
                if item["url"].split("?", 1)[0].lower().endswith(".svg"):
                    svg = ET.fromstring(data)
                    if svg.tag.rsplit("}", 1)[-1] != "svg":
                        raise ValueError("Invalid SVG document")
                    box = svg.get("viewBox", "0 0 0 0").replace(",", " ").split()
                    w, h = (round(float(box[2])), round(float(box[3]))) if len(box) == 4 else (0, 0)
                    ext = "svg"
                else:
                    with Image.open(io.BytesIO(data)) as im:
                        w, h = im.size
                        ext = {"JPEG": "jpg", "WEBP": "webp", "PNG": "png", "GIF": "gif"}.get(im.format)
                        if not ext or w > 16000 or h > 16000:
                            raise ValueError("Unsupported image")
                        im.verify()
                relative = "images/" + hashlib.sha256(item["url"].encode()).hexdigest() + "." + ext
                target = out / relative
                tmp = target.with_suffix(".part")
                tmp.write_bytes(data)
                tmp.replace(target)
                item.update(file=relative, sha256=hashlib.sha256(data).hexdigest(), bytes=len(data), width=w, height=h)
                return item
            except Exception as e:
                if attempt == 2:
                    item["error"] = str(e)
                else:
                    time.sleep(attempt + 1)
        return item

    completed = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, min(args.workers, 6))) as pool:
        for item in pool.map(download, cards):
            completed.append(item)
            if len(completed) % 20 == 0 or len(completed) == len(cards):
                ok = sum("file" in x for x in completed)
                print(f"Processed {len(completed)}/{len(cards)}; saved {ok}; failed {len(completed)-ok}", flush=True)
                write_json(out / "catalog.json", {"source": SITE, "api": API, "cards": completed})

    ok = [x for x in completed if "file" in x]
    mi = [x for x in completed if any(p.get("source") == "Mi Pay" for p in x["products"])]
    write_json(out / "mipay-catalog.json", {"source": SITE, "cards": mi})
    write_json(out / "failed.json", [x for x in completed if "error" in x])
    tiles = []
    for x in ok:
        src = html.escape(x["file"], quote=True)
        name, origin = html.escape(x["name"]), html.escape(x["source"])
        tiles.append(f'<a class="card" href="{src}" download data-source="{origin}" data-name="{name}"><img loading="lazy" src="{src}"><b>{name}</b><small>{origin} · {x["width"]} × {x["height"]}</small></a>')
    (out / "index.html").write_text('''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Cardentify 卡面收藏</title><style>body{font:16px system-ui;background:#f5f6f8;color:#182923;margin:32px}input,select{padding:12px;font:inherit;margin:6px}main{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:20px}.card{display:flex;flex-direction:column;gap:10px;padding:16px;background:white;border-radius:16px;text-decoration:none;color:inherit}img{width:100%;height:190px;object-fit:contain}small{color:#66756d}header{margin-bottom:24px}</style><header><h1>Cardentify 卡面收藏</h1><p>图片按下载时原始字节保存；来源标注由 Cardentify 提供。点击图片下载。</p><input id="q" placeholder="搜索银行、卡名"><select id="source"><option value="">全部来源</option><option>Mi Pay</option><option>Apple Pay</option><option>云闪付</option></select><span id="count"></span></header><main>''' + "".join(tiles) + '''</main><script>function filter(){let n=0;document.querySelectorAll('.card').forEach(e=>{let show=e.dataset.name.toLowerCase().includes(q.value.toLowerCase())&&(!source.value||e.dataset.source===source.value);e.style.display=show?'':'none';if(show)n++});count.textContent=n+' 张'}q.oninput=source.onchange=filter;filter()</script></html>''', encoding="utf-8")
    stats = {"products": len(raw), "selectedImages": len(cards), "downloadedImages": len(ok),
             "failedImages": len(cards)-len(ok), "mipayImages": len(mi),
             "bytes": sum(x["bytes"] for x in ok), "sources": dict(collections.Counter(x["source"] for x in ok))}
    write_json(out / "summary.json", stats)
    print(json.dumps(stats, ensure_ascii=True), flush=True)


if __name__ == "__main__":
    main()
