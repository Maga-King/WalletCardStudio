from pathlib import Path
import zipfile,io,json,hashlib
root=Path(__file__).resolve().parents[1]
apk=next((Path.home()/'Downloads').glob('MiPay*ksu.APK'))
z=zipfile.ZipFile(apk); a=zipfile.ZipFile(io.BytesIO(z.read('assets/assets.zip')))
out=root/'dist/Extra-Artwork/APK-bundled'; out.mkdir(parents=True,exist_ok=True)
cs=[]
for n in a.namelist():
    b=a.read(n); (out/n).write_bytes(b)
    cs.append(dict(name={'luotianyi.png':'天依柠檬卡','miku.png':'招行初音卡'}.get(n,n),url='',source='MiPayCard APK bundled artwork',sourcePage='local APK',file='Extra-Artwork/APK-bundled/'+n,sha256=hashlib.sha256(b).hexdigest(),bytes=len(b)))
(out/'catalog.json').write_text(json.dumps(cs,ensure_ascii=False,indent=2),encoding='utf8')
(root/'research/apk-art-sites/apk-card-cache-map.json').write_text(json.dumps(json.loads(z.read('assets/card_list.json')),ensure_ascii=False,indent=2),encoding='utf8')
(root/'research/apk-art-sites/apk-sha256.txt').write_text(hashlib.sha256(apk.read_bytes()).hexdigest(),encoding='ascii')
print('Bundled images:',len(cs))
