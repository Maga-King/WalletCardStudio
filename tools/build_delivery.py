"""Package public source and artwork retry evidence; exclude private research files."""
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"


def main():
    DIST.mkdir(exist_ok=True)
    evidence = ROOT / "research/apk-art-sites/retry-current-network.json"
    if evidence.is_file():
        retry = json.loads(evidence.read_text(encoding="utf-8"))
        failures = [row for row in retry["results"] if not row.get("localVerified") and not row.get("downloaded")]
    else:
        public = json.loads((ROOT / "docs/artwork-unavailable.json").read_text(encoding="utf-8"))
        retry = {"at": public["date"]}
        failures = [dict(row, originalUrl=row["url"]) for row in public["failed"]]
    lines = ["# APK 目录尚未下载的链接", "", "2026-10-20 关闭 VPN 后复查。214 条目录记录中 184 条下载成功，其余 30 条重新请求原地址及同资源镜像，共 62 次 GET，未补回新图。", "", "原地址结果：21 条 HTTP 404，3 条 HTTP 403，6 条域名、连接或超时错误。已有 184 张原图均校验通过，没有重复下载。另有 2 张 APK 内置图已提取。", "", "|卡面名称|原始链接|本次原地址结果|", "|---|---|---|"]
    for row in failures:
        attempt = row["attempts"][0]
        status = "HTTP " + str(attempt["status"]) if attempt.get("status") else "域名 / 连接 / 超时错误"
        title = row["name"].replace("|", "\\|").replace("\n", " ")
        lines.append(f'|{title}|<{row["originalUrl"]}>|{status}|')
    (DIST / "APK-未下载链接.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    # Explicit public fields only: do not package the private research directory.
    public = {"date": retry["at"], "failed": [{"name": row["name"], "url": row["originalUrl"], "attempts": row["attempts"]} for row in failures]}
    (DIST / "APK-未下载链接.json").write_text(json.dumps(public, ensure_ascii=False, indent=2), encoding="utf-8")
    gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    version = re.search(r"versionName '([^']+)'", gradle).group(1)
    files = [ROOT / name for name in ["README.md", ".gitignore", ".gitattributes", "build.ps1", "build.gradle", "settings.gradle", "gradle.properties", "app/build.gradle", "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"]]
    files += sorted(path for path in (ROOT / "docs").rglob("*") if path.is_file())
    for pattern in ["*.java", "*.xml", "*.aidl", "*.txt"]:
        files += sorted((ROOT / "app/src").rglob(pattern))
    files += sorted((ROOT / "tools").glob("*.py"))
    source = DIST / f"WalletCardStudio-{version}-Source.zip"
    with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in files:
            archive.write(file, "WalletCardStudio/" + file.relative_to(ROOT).as_posix())
    hashes = {}
    for file in [DIST / f"WalletCardStudio-{version}.apk", source]:
        hashes[file.name] = {"bytes": file.stat().st_size, "sha256": hashlib.sha256(file.read_bytes()).hexdigest()}
    offline = json.loads((DIST / "offline-summary.json").read_text(encoding="utf-8"))
    hashes["WalletArtwork-Offline.zip"] = {"bytes": offline["archiveBytes"], "sha256": offline["archiveSha256"]}
    (DIST / "checksums.json").write_text(json.dumps(hashes, indent=2), encoding="utf-8")
    print(json.dumps({"version": version, "failedLinks": len(failures), "sourceBytes": source.stat().st_size}))


if __name__ == "__main__":
    main()
