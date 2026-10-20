import concurrent.futures as cf
import hashlib, io, json, re, zipfile
from pathlib import Path
from urllib.parse import quote, unquote, urlparse
import requests
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'dist/Extra-Artwork'; OUT.mkdir(parents=True,exist_ok=True)
RES=ROOT/'research/apk-art-sites'; RES.mkdir(parents=True,exist_ok=True)
session=requests.Session(); session.headers['User-Agent']='WalletCardStudio public artwork archive'
probes=[]
def get(url):
    r=session.get(url,timeout=(12,35)); r.raise_for_status(); return r
def savejson(p,x): p.write_text(json.dumps(x,ensure_ascii=False,indent=2),encoding='utf8')
cards=[]
for tag,url in [
 ('api-online','https://mi-pay-card-api.gddhy.net?online=true'),
 ('api-list','https://mi-pay-card-api.gddhy.net?list=true'),
 ('cdn-online','https://fastly.jsdelivr.net/gh/gddhy/MiPayCard@master/online_card.json'),
 ('raw-online','https://raw.githubusercontent.com/gddhy/MiPayCard/master/online_card.json'),
 ('gallery','https://gddhy.net/MiPayCard-onlineCard/'),
 ('cdn-gallery','https://fastly.jsdelivr.net/gh/gddhy/MiPayCard-onlineCard/')]:
    try:
        r=get(url); probes.append(dict(tag=tag,url=url,status=r.status_code,bytes=len(r.content),type=r.headers.get('Content-Type')))
        if 'online' in tag:
            t=r.content.decode('utf-8-sig').strip(); data=json.loads(t if t.startswith('[') else '['+t.rstrip(',')+']')
            clean=[dict(name=x.get('cardName',''),url=x.get('link',''),source='MiPayCard community online directory',sourcePage=url) for x in data]
            savejson(RES/(tag+'.json'),clean)
            if not cards: cards=clean
        else: (RES/(tag+'.html')).write_bytes(r.content)
    except Exception as e: probes.append(dict(tag=tag,url=url,error=str(e)))
if not cards:
    t=(ROOT/'research/mipay-online_card.json').read_text(encoding='utf-8-sig').strip()
    cards=[dict(name=x.get('cardName',''),url=x.get('link',''),source='MiPayCard community online directory',sourcePage='https://github.com/gddhy/MiPayCard/blob/master/online_card.json') for x in json.loads('['+t.rstrip(',')+']')]
savejson(RES/'site-probes.json',probes)
savejson(RES/'online-catalog.json',cards)
def download(pair):
    i,c=pair; result=dict(c)
    if not c['url'].startswith('http'): return dict(result,error='APK internal file reference')
    urls=[c['url']]
    if '/gh/gddhy/MiPayCard-onlineCard/' in c['url']:
        suffix=c['url'].split('/gh/gddhy/MiPayCard-onlineCard/',1)[1]
        urls += ['https://raw.githubusercontent.com/gddhy/MiPayCard-onlineCard/master/'+suffix]
    for url in urls:
        try:
            r=get(url); im=Image.open(io.BytesIO(r.content)); im.verify()
            ext='.'+{'JPEG':'jpg','PNG':'png','GIF':'gif','WEBP':'webp'}.get(im.format,im.format.lower())
            name=re.sub(r'[<>:"/\\|?*\x00-\x1f]','_',c['name']).strip(' .')[:100] or 'card'
            p=OUT/(f'{i+1:03d}_{name}'+ext); p.write_bytes(r.content)
            result.update(file=p.relative_to(ROOT/'dist').as_posix(),sha256=hashlib.sha256(r.content).hexdigest(),bytes=len(r.content),width=im.width,height=im.height,downloadUrl=url)
            return result
        except Exception as e: result['error']=str(e)
    return result
with cf.ThreadPoolExecutor(max_workers=5) as pool:
    results=list(pool.map(download,enumerate(cards)))
savejson(OUT/'catalog.json',results)
savejson(RES/'download-results.json',results)
print(json.dumps(dict(entries=len(results),downloaded=sum('file'in x for x in results),failed=sum('file'not in x for x in results)),ensure_ascii=False),flush=True)
