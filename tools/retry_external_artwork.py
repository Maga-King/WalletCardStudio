"""Retry failed original resources, preserving verified existing image bytes."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
from datetime import datetime, timezone
from urllib.parse import urlsplit
import json, requests, io, re, hashlib
from PIL import Image

root=Path(__file__).resolve().parents[1]
p=root/'dist/Extra-Artwork/catalog.json'; research=root/'research/apk-art-sites'
cards=json.loads(p.read_text(encoding='utf8')); stamp=datetime.now(timezone.utc).isoformat()
evidence=[]; todo=[]
for i,c in enumerate(cards):
    if 'file' in c:
        f=root/'dist'/c['file']
        ok=f.is_file() and f.stat().st_size==c.get('bytes') and hashlib.sha256(f.read_bytes()).hexdigest()==c.get('sha256')
        evidence.append(dict(index=i+1,name=c['name'],existingFile=c['file'],localVerified=ok))
        if not ok: raise RuntimeError('Existing file integrity mismatch: '+str(f))
    else: todo.append((i,c))

def urls_for(c):
    urls=[c['url']]; parts=urlsplit(c['url'])
    if '/gh/gddhy/MiPayCard-onlineCard/' in parts.path:
        suffix=parts.path.split('/gh/gddhy/MiPayCard-onlineCard/',1)[1]
        urls.extend(['https://raw.githubusercontent.com/gddhy/MiPayCard-onlineCard/master/'+suffix,
                     'https://cdn.jsdelivr.net/gh/gddhy/MiPayCard-onlineCard@master/'+suffix])
    return list(dict.fromkeys(urls))

def retry(pair):
    i,c=pair
    result=dict(index=i+1,name=c['name'],originalUrl=c['url'],previousError=c.get('error'),attempts=[])
    for url in urls_for(c):
        attempt=dict(url=url)
        try:
            r=requests.get(url,timeout=(8,20),headers={'User-Agent':'WalletCardStudio public artwork archive'})
            attempt.update(status=r.status_code,bytes=len(r.content),contentType=r.headers.get('Content-Type'),finalUrl=r.url)
            r.raise_for_status()
            im=Image.open(io.BytesIO(r.content)); im.verify()
            ext='.'+{'JPEG':'jpg','PNG':'png','GIF':'gif','WEBP':'webp'}.get(im.format,im.format.lower())
            name=re.sub(r'[<>:"/\\|?*\x00-\x1f]','_',c['name']).strip(' .')[:100] or 'card'
            f=p.parent/(f'{i+1:03d}_{name}'+ext); f.write_bytes(r.content)
            c.update(file=f.relative_to(root/'dist').as_posix(),sha256=hashlib.sha256(r.content).hexdigest(),bytes=len(r.content),width=im.width,height=im.height,downloadUrl=url)
            c.pop('error',None); attempt['verifiedImage']=True
            result['attempts'].append(attempt); result['downloaded']=True
            return result
        except Exception as e:
            attempt['error']=str(e); c['error']=str(e); result['attempts'].append(attempt)
    result['downloaded']=False
    return result

with ThreadPoolExecutor(max_workers=3) as pool: retried=list(pool.map(retry,todo))
evidence.extend(retried)
def save(path,value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf8')
save(p,cards); save(research/'download-results.json',cards)
save(research/'retry-current-network.json',dict(at=stamp,concurrency=3,connectTimeoutSeconds=8,readTimeoutSeconds=20,verifiedExisting=len(cards)-len(todo),retried=len(todo),results=evidence))
good=[c for c in cards if 'file'in c]
summary=dict(directoryEntries=len(cards),downloaded=len(good),failed=len(cards)-len(good),uniqueImageHashes=len(set(c['sha256'] for c in good)),bytes=sum(c['bytes'] for c in good),bundledImages=2,retryAt=stamp,retryExistingVerified=len(cards)-len(todo),retryAttempted=len(todo),retryNewDownloads=sum(x['downloaded'] for x in retried))
summary['retryRemainingOriginalOutcomes']=dict(Counter(str(x['attempts'][0].get('status','connection error')) for x in retried if not x['downloaded']))
summary['retryTotalHttpRequests']=sum(len(x['attempts']) for x in retried)
save(research/'summary.json',summary)
causes=Counter(str(a.get('status','connection/image error')) for x in retried if not x['downloaded'] for a in x['attempts'])
with (research/'REPORT.md').open('a',encoding='utf8') as out:
    out.write('\n\n## Retry after VPN disabled ('+stamp+')\n\n')
    out.write(f"Verified {len(cards)-len(todo)} existing files against length and SHA-256 without downloading them again. Retried all {len(todo)} failed entries with at most 3 concurrent GETs and bounded timeouts. Newly recovered: {summary['retryNewDownloads']}; downloaded: {len(good)}/{len(cards)}; remaining failed: {summary['failed']}.\n\n")
    out.write('Failed HTTP/connection outcomes across original and identical-resource raw/CDN URLs: '+json.dumps(dict(causes),ensure_ascii=False)+'. Per-URL HTTP status, redirect URL, bytes, and errors: `retry-current-network.json`. No guessed replacement artwork was used.\n')
print(json.dumps(summary,ensure_ascii=False),flush=True)
