import concurrent.futures, hashlib, io, json, pathlib, re, zipfile
import requests
from PIL import Image
import fitz
import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[1]
RESEARCH = ROOT/'research/github-issues'
OUT = ROOT/'dist/Cardentify-Issues'
for folder in [RESEARCH/'attachments', OUT/'images', OUT/'originals']:
    folder.mkdir(parents=True, exist_ok=True)
issues = json.loads((RESEARCH/'issues.json').read_text(encoding='utf-8'))
comments = json.loads((RESEARCH/'comments.json').read_text(encoding='utf-8'))
catalog = json.loads((ROOT/'research/cardentify-catalog.json').read_text(encoding='utf-8'))
known = {pathlib.PurePosixPath(im['image']).stem for card in catalog for im in card['images']}
def fingerprint(data):
    im=Image.open(io.BytesIO(data)).convert('RGBA')
    canvas=Image.new('RGBA',im.size,'white');canvas.alpha_composite(im)
    return np.asarray(canvas.convert('RGB').resize((48,30)),dtype=np.float32).reshape(-1)
published_files=[]; published_vectors=[]
for f in (ROOT/'dist/Cardentify/images').glob('*'):
    try: vector=fingerprint(f.read_bytes())
    except Exception:
        try:
            document=fitz.open(f)
            vector=fingerprint(document[0].get_pixmap().tobytes('png'))
        except Exception: continue
    published_files.append(f);published_vectors.append(vector)
published_vectors=np.asarray(published_vectors)
tasks = []
for issue in issues:
    if issue['number'] in [1,2,7,11,18,19,26]:
        continue  # Discussions and tutorial screenshots, not card-art submissions.
    body = issue.get('body') or ''
    texts = [body] + [c.get('body') or '' for c in comments.get(str(issue['number']), []) if isinstance(c, dict)]
    source_match = re.search(r'### 卡面来源\s+([^\n]+)', body)
    source = source_match.group(1).strip() if source_match else 'GitHub Issue'
    names = re.findall(r'卡片名称[：:]\s*([^\r\n]+)', body)
    urls = []
    for text in texts:
        for url in re.findall(r'https?://[^\s<>"\)]+', text):
            url = url.rstrip('.,')
            if ('github.com/user-attachments/' in url or 'user-images.githubusercontent.com/' in url or re.search(r'\.(?:png|jpg|jpeg|webp|zip|pdf)(?:\?|$)',url,re.I)) and url not in urls:
                urls.append(url)
    for n,url in enumerate(urls):
        tasks.append((issue,url,names[n] if n<len(names) else issue['title'].replace('[卡面提交]','').replace('[批量提交]','').strip() or 'Issue '+str(issue['number']),source))

def download(task):
    issue,url,name,source = task
    key = hashlib.sha256(url.encode()).hexdigest()
    path = RESEARCH/'attachments'/key
    try:
        if path.exists(): data = path.read_bytes()
        else:
            r = requests.get(url, timeout=70); r.raise_for_status(); data=r.content;path.write_bytes(data)
        return task,data,None
    except Exception as e:return task,None,str(e)

rows=[]; audit=[]; seen=set(); errors=[]
def image(data, name, task, original=None):
    issue,url,_,source=task
    digest=hashlib.sha256(data).hexdigest()
    try:
        im=Image.open(io.BytesIO(data));width,height=im.size;im.verify();ext={'JPEG':'.jpg','PNG':'.png','WEBP':'.webp','GIF':'.gif'}.get(im.format,'.png')
        if not 1.3 <= width/height <= 2.0:
            audit.append({'issueNumber':issue['number'],'name':name,'sha256':digest,'status':'excluded-non-card-aspect','sourceUrl':url})
            return
    except Exception:return
    status='already-published' if digest in known else 'supplement'
    match=None
    if status=='supplement' and len(published_vectors):
        distances=np.sqrt(np.mean((published_vectors-fingerprint(data))**2,axis=1))
        nearest=int(np.argmin(distances))
        if float(distances[nearest])<4:
            status='already-published-render-variant';match=published_files[nearest].name
    audit.append({'issueNumber':issue['number'],'name':name,'sha256':digest,'status':status,'sourceUrl':url,**({'matchingPublishedFile':match} if match else {})})
    if status!='supplement' or digest in seen:return
    seen.add(digest)
    relative='images/'+digest+ext
    (OUT/relative).write_bytes(data)
    overrides={70:('平安银行动物主题借记卡（产品名待核验）','云闪付'),97:('工商银行宇宙星座信用卡（狮子座）','云闪付'),110:('Hana Bank Debit Visa（投稿信息待核验）','Apple Pay（投稿声称，维护者质疑支持情况）'),113:('交通联合透明纪念卡','原创复刻'),115:('中华人民共和国社会保障卡','Apple Pay')}
    if issue['number'] in overrides:name,source=overrides[issue['number']]
    rows.append({'name':name,'url':relative,'source':source,'sourcePage':issue['html_url'],'issueNumber':issue['number'],'sourceUrl':url,'sha256':digest,**({'original':original} if original else {})})

with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
    for task,data,error in pool.map(download,tasks):
        issue,url,name,source=task
        if error:errors.append({'issueNumber':issue['number'],'url':url,'error':error});continue
        if zipfile.is_zipfile(io.BytesIO(data)):
            zpath='originals/issue-'+str(issue['number'])+'-'+hashlib.sha256(data).hexdigest()[:12]+'.zip'
            (OUT/zpath).write_bytes(data)
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for member in archive.infolist():
                    # Read only card art types in memory; never execute or extract archive paths.
                    if member.file_size>40_000_000 or pathlib.PurePosixPath(member.filename).suffix.lower() not in ['.png','.jpg','.jpeg','.webp','.pdf']:continue
                    blob=archive.read(member)
                    title=pathlib.PurePosixPath(member.filename).stem
                    if member.filename.lower().endswith('.pdf'):
                        pdfpath='originals/'+hashlib.sha256(blob).hexdigest()+'.pdf';(OUT/pdfpath).write_bytes(blob)
                        doc=fitz.open(stream=blob,filetype='pdf')
                        for p,page in enumerate(doc):
                            pix=page.get_pixmap(matrix=fitz.Matrix(2,2),alpha=True)
                            image(pix.tobytes('png'),title+((' '+str(p+1)) if len(doc)>1 else ''),task,pdfpath)
                    else:image(blob,title,task,zpath)
        elif data.startswith(b'%PDF'):
            pdfpath='originals/'+hashlib.sha256(data).hexdigest()+'.pdf';(OUT/pdfpath).write_bytes(data)
            for page in fitz.open(stream=data,filetype='pdf'):image(page.get_pixmap(matrix=fitz.Matrix(2,2),alpha=True).tobytes('png'),name,task,pdfpath)
        else:image(data,name,task)
        print('processed',issue['number'],len(rows),flush=True)
for file,value in [('catalog.json',rows),('audit.json',audit),('errors.json',errors)]:
    (OUT/file).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
keep_images={pathlib.PurePosixPath(row['url']).name for row in rows}
for f in (OUT/'images').iterdir():
    if f.is_file() and f.name not in keep_images:f.unlink()
keep_originals={pathlib.PurePosixPath(row['original']).name for row in rows if row.get('original')}
for f in (OUT/'originals').iterdir():
    if f.is_file() and f.name not in keep_originals and not f.name.startswith('issue-133-'):f.unlink()
print(json.dumps({'attachments':len(tasks),'supplements':len(rows),'auditImages':len(audit),'errors':len(errors),'issueCounts':{str(n):sum(r['issueNumber']==n for r in rows) for n in sorted({r['issueNumber'] for r in rows})}},ensure_ascii=False))
