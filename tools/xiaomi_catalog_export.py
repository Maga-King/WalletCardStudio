"""Export images returned by the authorized Xiaomi transit product queries."""
import concurrent.futures,hashlib,io,json,pathlib,requests
from PIL import Image
root=pathlib.Path(__file__).resolve().parents[1]
products=json.loads((root/'research/xiaomi-live/products.json').read_text(encoding='utf-8'))
out=root/'dist/Xiaomi-Artwork';(out/'images').mkdir(parents=True,exist_ok=True)
by={}
fields=['issuedListBgHd','preIssuedListBg','issuedListBg','preIssuedDetailBg','issuedDetailBg','gifUrl']
for x in products:
 for f in fields:
  u=x.get(f)
  if isinstance(u,str) and u.startswith('https://'):
   name=x['cardName']+(' HD' if f=='issuedListBgHd' else '')
   by.setdefault(u,{'name':name,'url':u,'source':'Xiaomi Wallet live transit product catalog','sourcePage':'https://tsmapi.pay.xiaomi.com/api/login/spcard/v2/queryCardProduct','product':x['cardName'],'field':f})
def get(card):
 u=card['url'];key=hashlib.sha256(u.encode()).hexdigest()[:16]
 try:
  r=requests.get(u,timeout=40);r.raise_for_status();im=Image.open(io.BytesIO(r.content));ext=im.format.lower().replace('jpeg','jpg');p=out/'images'/f'{card["product"]}-{key}.{ext}';p.write_bytes(r.content)
  return dict(card,file=p.relative_to(out).as_posix(),width=im.width,height=im.height,bytes=len(r.content),sha256=hashlib.sha256(r.content).hexdigest())
 except Exception as e:return dict(card,error=str(e))
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:cards=list(pool.map(get,by.values()))
auxiliary=[x for x in cards if x.get('height',0)>1000 or x.get('width')==1000]
main=[x for x in cards if x not in auxiliary]
(out/'cards.json').write_text(json.dumps({'cards':main,'auxiliaryAssets':auxiliary},ensure_ascii=False,indent=2),encoding='utf-8')
(out/'catalog.json').write_text(json.dumps({'cards':main},ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'products':len(products),'assets':len(cards),'downloaded':sum('file'in x for x in cards),'bytes':sum(x.get('bytes',0) for x in cards)},indent=2))
