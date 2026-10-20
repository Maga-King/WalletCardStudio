package local.wallet.cardstudio;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class CloudStore {
    static final long MAX=24L*1024*1024, MAX_ARCHIVE=1536L*1024*1024;
    static final String CARDENTIFY="https://cards.no2.ac/api/cards";
    static final Object WRITE=new Object();
    static volatile Helper helper;
    static volatile boolean migrated;
    static final class Helper extends SQLiteOpenHelper {
        final Context context;
        Helper(Context c){super(c,"offline-cards.db",null,2);context=c.getApplicationContext();setWriteAheadLoggingEnabled(true);}
        public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE cards (key TEXT PRIMARY KEY,url TEXT NOT NULL DEFAULT '',name TEXT NOT NULL,aliases TEXT NOT NULL DEFAULT '[]',source TEXT NOT NULL DEFAULT '',sourcePage TEXT NOT NULL DEFAULT '',mime TEXT NOT NULL DEFAULT '',width INTEGER NOT NULL DEFAULT 0,height INTEGER NOT NULL DEFAULT 0,bytes INTEGER NOT NULL DEFAULT 0,sha256 TEXT NOT NULL DEFAULT '',file TEXT NOT NULL DEFAULT '',originalFile TEXT NOT NULL DEFAULT '',error TEXT NOT NULL DEFAULT '')");
            db.execSQL("CREATE UNIQUE INDEX full_url ON cards(url) WHERE url<>''");
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY,value TEXT)");
            db.execSQL("CREATE TABLE card_sources (key TEXT NOT NULL,name TEXT,source TEXT,sourcePage TEXT,url TEXT,UNIQUE(key,name,source,sourcePage,url))");
            addOriginalChecks(db);
        }
        void addOriginalChecks(SQLiteDatabase db){db.execSQL("ALTER TABLE cards ADD COLUMN originalSha256 TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE cards ADD COLUMN originalBytes INTEGER NOT NULL DEFAULT 0");}
        public void onUpgrade(SQLiteDatabase db,int old,int version){if(old<2){addOriginalChecks(db);db.execSQL("CREATE TABLE IF NOT EXISTS card_sources (key TEXT NOT NULL,name TEXT,source TEXT,sourcePage TEXT,url TEXT,UNIQUE(key,name,source,sourcePage,url))");}}
    }
    static File dir(Context c){File f=new File(c.getFilesDir(),"library");f.mkdirs();return f;}
    static SQLiteDatabase db(Context c) throws Exception {
        if(helper==null)synchronized(WRITE){if(helper==null)helper=new Helper(c.getApplicationContext());}
        SQLiteDatabase d=helper.getWritableDatabase();if(!migrated)migrate(c,d);return d;
    }
    static void migrate(Context c,SQLiteDatabase d) throws Exception {
        synchronized(WRITE){
            if(migrated)return;
            try(Cursor cur=d.rawQuery("SELECT value FROM meta WHERE key='legacy-migrated'",null)){if(cur.moveToFirst()){migrated=true;return;}}
            File legacy=new File(dir(c),"catalog.json");
            if(legacy.isFile()){
                JSONObject old=new JSONObject(new String(Files.readAllBytes(legacy.toPath()),StandardCharsets.UTF_8));
                Iterator<String> keys=old.keys();
                while(keys.hasNext()){
                    String oldUrl=keys.next();JSONObject row=old.optJSONObject(oldUrl);if(row==null)continue;
                    if(!https(oldUrl))continue;row.put("url",oldUrl);String key=WalletStore.sha(oldUrl);
                    upsert(d,key,row);
                    File f=new File(dir(c),key+".image");
                    if(f.isFile())try{record(d,key,f,bounds(f),WalletStore.fileHash(f),"");}catch(Exception ignored){/* Preserve old originals, retry via manual sync. */}
                }
            }
            ContentValues flag=new ContentValues();flag.put("key","legacy-migrated");flag.put("value","1");d.insertWithOnConflict("meta",null,flag,SQLiteDatabase.CONFLICT_REPLACE);
            migrated=true;
        }
    }
    static boolean https(String value){try{checkUrl(value);return true;}catch(Exception e){return false;}}
    static String identity(JSONObject row) throws Exception {
        String url=row.optString("url","");if(https(url))return WalletStore.sha(url);
        String key=row.optString("key","");if(key.matches("[a-zA-Z0-9_-]{1,128}"))return key;
        String file=row.optString("file","");
        if(file.isEmpty())throw new IOException("离线条目缺少文件身份");
        return WalletStore.sha("offline:"+row.optString("sourcePage","")+"\n"+file);
    }
    static boolean exists(SQLiteDatabase d,String key){try(Cursor c=d.rawQuery("SELECT key FROM cards WHERE key=?",new String[]{key})){return c.moveToFirst();}}
    static int upsert(SQLiteDatabase d,String key,JSONObject row) throws Exception {
        String title=row.optString("name","卡面");boolean had=exists(d,key);LinkedHashSet<String> names=new LinkedHashSet<>();
        try(Cursor cur=d.rawQuery("SELECT name,aliases FROM cards WHERE key=?",new String[]{key})){if(cur.moveToFirst()){names.add(cur.getString(0));JSONArray a=new JSONArray(cur.getString(1));for(int i=0;i<a.length();i++)names.add(a.getString(i));}}
        names.add(title);JSONArray incoming=row.optJSONArray("aliases");if(incoming!=null)for(int i=0;i<incoming.length();i++)names.add(incoming.optString(i));
        String first=names.iterator().next();names.remove(first);names.remove("");JSONArray aliases=new JSONArray();for(String n:names)aliases.put(n);
        ContentValues v=new ContentValues();v.put("key",key);v.put("name",first);v.put("aliases",aliases.toString());
        if(!had)v.put("url",https(row.optString("url",""))?row.optString("url"):"");
        for(String k:new String[]{"source","sourcePage"})if(row.has(k)&&!row.optString(k).isEmpty())v.put(k,row.optString(k));
        if(had){v.remove("key");d.update("cards",v,"key=?",new String[]{key});}else d.insertOrThrow("cards",null,v);
        ContentValues provenance=new ContentValues();provenance.put("key",key);for(String field:new String[]{"name","source","sourcePage","url"})provenance.put(field,row.optString(field,""));d.insertWithOnConflict("card_sources",null,provenance,SQLiteDatabase.CONFLICT_IGNORE);
        return had?0:1;
    }
    static JSONObject row(SQLiteDatabase d,String key) throws Exception {
        try(Cursor c=d.rawQuery("SELECT * FROM cards WHERE key=?",new String[]{key})){if(!c.moveToFirst())return null;JSONObject o=new JSONObject();for(int i=0;i<c.getColumnCount();i++)o.put(c.getColumnName(i),c.getType(i)==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):c.getString(i));return o;}
    }
    static BitmapFactory.Options bounds(File file) throws IOException {
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),o);
        if(o.outWidth<1||o.outHeight<1||o.outWidth>16000||o.outHeight>16000||o.outMimeType==null)throw new IOException("文件不是可读取的原始图片");return o;
    }
    static void record(SQLiteDatabase d,String key,File file,BitmapFactory.Options info,String hash,String original) throws Exception {
        ContentValues v=new ContentValues();v.put("file",file.getAbsolutePath());v.put("mime",info.outMimeType);v.put("width",info.outWidth);v.put("height",info.outHeight);v.put("bytes",file.length());v.put("sha256",hash);v.put("error","");if(!original.isEmpty()){File raw=new File(original);v.put("originalFile",original);v.put("originalSha256",WalletStore.fileHash(raw));v.put("originalBytes",raw.length());}d.update("cards",v,"key=?",new String[]{key});
    }
    static boolean complete(JSONObject o) {
        if(o==null)return false;
        try{File f=new File(o.optString("file",""));if(!f.isFile()||f.length()!=o.optLong("bytes")||o.optString("sha256").isEmpty())return false;
            if(!WalletStore.fileHash(f).equals(o.getString("sha256")))return false;
            if(!o.optString("originalFile").isEmpty()){File raw=new File(o.getString("originalFile"));if(!raw.isFile()||raw.length()!=o.optLong("originalBytes")||!WalletStore.fileHash(raw).equals(o.optString("originalSha256")))return false;}
            BitmapFactory.Options b=bounds(f);return b.outWidth==o.optInt("width")&&b.outHeight==o.optInt("height");
        }catch(Exception e){return false;}
    }
    static void remember(Context c,String name,String url) throws Exception {
        checkUrl(url);SQLiteDatabase d=db(c);JSONObject o=new JSONObject();o.put("name",name);o.put("url",url);synchronized(WRITE){upsert(d,WalletStore.sha(url),o);}
    }
    static JSONObject load(Context c) throws Exception {
        JSONObject all=new JSONObject();SQLiteDatabase d=db(c);try(Cursor cur=d.rawQuery("SELECT key,url FROM cards",null)){while(cur.moveToNext()){JSONObject o=row(d,cur.getString(0));all.put(cur.getString(1).isEmpty()?cur.getString(0):cur.getString(1),o);}}return all;
    }
    static void save(Context c,JSONObject all) throws Exception {JSONArray a=new JSONArray();Iterator<String> keys=all.keys();while(keys.hasNext()){String key=keys.next();JSONObject o=all.getJSONObject(key);if(!o.has("url"))o.put("url",key);a.put(o);}merge(c,a);}
    static List<WalletStore.Art> entries(Context c) throws Exception {
        SQLiteDatabase d=db(c);ArrayList<WalletStore.Art> result=new ArrayList<>();
        try(Cursor cur=d.rawQuery("SELECT key,name,aliases,url,source,sourcePage,mime,width,height,file FROM cards ORDER BY name,key",null)){
            while(cur.moveToNext()){
                WalletStore.Art a=new WalletStore.Art();a.key=cur.getString(0);a.name=cur.getString(1);JSONArray aliases=new JSONArray(cur.getString(2));for(int i=0;i<aliases.length();i++)a.name+=" / "+aliases.getString(i);
                a.url=cur.getString(3);a.source=cur.getString(4);a.sourcePage=cur.getString(5);a.mime=cur.getString(6);a.width=cur.getInt(7);a.height=cur.getInt(8);String file=cur.getString(9);a.file=new File(file.isEmpty()?new File(dir(c),a.key+".image").getAbsolutePath():file);result.add(a);
            }
        }return result;
    }
    static List<WalletStore.Art> downloaded(Context c) throws Exception {ArrayList<WalletStore.Art> out=new ArrayList<>();for(WalletStore.Art a:entries(c))if(a.width>0&&a.file.isFile())out.add(a);return out;}
    static int importCatalog(Context c,InputStream in) throws Exception {
        String s=new String(readLimited(in,12L*1024*1024),StandardCharsets.UTF_8);JSONArray a=s.trim().startsWith("[")?new JSONArray(s):new JSONObject(s).getJSONArray("cards");return merge(c,a);
    }
    static int merge(Context c,JSONArray list) throws Exception {
        if(list.length()>10000)throw new IOException("目录最多支持10000项");
        for(int i=0;i<list.length();i++)checkUrl(list.getJSONObject(i).getString("url"));
        SQLiteDatabase d=db(c);int count=0;synchronized(WRITE){d.beginTransaction();try{for(int i=0;i<list.length();i++){JSONObject o=list.getJSONObject(i);count+=upsert(d,WalletStore.sha(o.getString("url")),o);}d.setTransactionSuccessful();}finally{d.endTransaction();}}return count;
    }
    static class SyncResult {int added,downloaded,skipped,failed;}
    static int syncCardentify(Context c,boolean onlyMiPay) throws Exception {return syncCardentifyOffline(c,onlyMiPay).added;}
    static SyncResult syncCardentifyOffline(Context c,boolean onlyMiPay) throws Exception {
        JSONArray products=new JSONArray(new String(fetch(CARDENTIFY,12L*1024*1024),StandardCharsets.UTF_8)),list=new JSONArray();
        for(int i=0;i<products.length();i++){
            JSONObject product=products.getJSONObject(i);JSONArray images=product.optJSONArray("images");if(images==null)continue;JSONObject issuer=product.optJSONObject("issuer");String bank=issuer==null?"":issuer.optString("name","");
            for(int j=0;j<images.length();j++){JSONObject image=images.getJSONObject(j);String source=image.optString("source","");if(onlyMiPay&&!"Mi Pay".equals(source))continue;
                JSONObject o=new JSONObject();o.put("name",bank+" · "+product.optString("name","卡面"));o.put("url",image.getString("image"));o.put("source",source);o.put("sourcePage","https://cards.no2.ac/");list.put(o);
            }
        }
        if(list.length()==0)throw new IOException("在线目录未返回卡面");SyncResult r=new SyncResult();r.added=merge(c,list);SQLiteDatabase d=db(c);Set<String> urls=new LinkedHashSet<>();for(int i=0;i<list.length();i++)urls.add(list.getJSONObject(i).getString("url"));
        for(String url:urls){String key=WalletStore.sha(url);JSONObject old=row(d,key);if(complete(old)){r.skipped++;continue;}
            try{download(c,old.optString("name","卡面"),url);r.downloaded++;}catch(Exception e){r.failed++;ContentValues v=new ContentValues();v.put("error",String.valueOf(e.getMessage()));d.update("cards",v,"key=?",new String[]{key});}
        }return r;
    }
    static SyncResult syncCatalogOrMissing(Context c,boolean onlyMiPay) throws Exception {return syncCardentifyOffline(c,onlyMiPay);}
    static WalletStore.Art download(Context c,String name,String address) throws Exception {
        checkUrl(address);SQLiteDatabase d=db(c);String key=WalletStore.sha(address);remember(c,name,address);
        JSONObject old=row(d,key);if(!complete(old)){
            byte[] data=fetch(address,MAX);File temp=File.createTempFile("download-",".image",c.getCacheDir());
            try{try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);out.getFD().sync();}BitmapFactory.Options info=bounds(temp);String hash=WalletStore.fileHash(temp);File file=new File(dir(c),key+".image");
                synchronized(WRITE){Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);record(d,key,file,info,hash,"");}
            }finally{temp.delete();}
        }
        JSONObject saved=row(d,key);WalletStore.Art a=new WalletStore.Art();a.key=key;a.name=saved.getString("name");a.url=saved.getString("url");a.source=saved.optString("source");a.sourcePage=saved.optString("sourcePage");a.mime=saved.optString("mime");a.width=saved.optInt("width");a.height=saved.optInt("height");a.file=new File(saved.getString("file"));return a;
    }
    static void bootstrap(Context c) throws Exception {
        db(c);File file=new File(c.getFilesDir(),"bootstrap.zip");if(!file.isFile())return;
        try(InputStream in=new FileInputStream(file)){importArchive(c,in);}if(!file.delete())throw new IOException("离线包导入成功，但临时包清理失败");
    }
    static String archivePath(String value) throws IOException {
        if(value.isEmpty()||value.startsWith("/")||value.contains("\\")||value.contains(":")||value.indexOf('\0')>=0)throw new IOException("离线包图片路径无效");
        for(String part:value.split("/"))if(part.equals("..")||part.equals("."))throw new IOException("离线包图片路径无效");return value;
    }
    static final class Staged {JSONObject row;String key,hash,original="";File file,originalTemp;BitmapFactory.Options info;}
    static File zipFile(Context c,ZipFile zip,String name,long[] total) throws Exception {
        ZipEntry entry=zip.getEntry(archivePath(name));if(entry==null||entry.isDirectory())throw new IOException("离线包缺少图片："+name);if(entry.getSize()>MAX)throw new IOException("单张图片超过24MB");File temp=File.createTempFile("offline-",".image",c.getCacheDir());
        try(InputStream in=zip.getInputStream(entry);FileOutputStream out=new FileOutputStream(temp)){byte[] b=new byte[16384];long size=0;int n;while((n=in.read(b))!=-1){size+=n;total[0]+=n;if(size>MAX||total[0]>MAX_ARCHIVE)throw new IOException("离线包超过大小限制");out.write(b,0,n);}out.getFD().sync();return temp;}catch(Exception e){temp.delete();throw e;}
    }
    static int importArchive(Context c,InputStream input) throws Exception {
        File archive=File.createTempFile("catalog-",".zip",c.getCacheDir());ArrayList<Staged> stages=new ArrayList<>();
        try{
            try(FileOutputStream out=new FileOutputStream(archive)){byte[] b=new byte[65536];long size=0;int n;while((n=input.read(b))!=-1){size+=n;if(size>MAX_ARCHIVE)throw new IOException("离线包超过1.5GB");out.write(b,0,n);}}
            try(ZipFile zip=new ZipFile(archive)){
                if(zip.size()>10000)throw new IOException("离线包最多支持10000个文件条目");
                ZipEntry manifest=zip.getEntry("catalog.json");if(manifest==null)throw new IOException("离线包缺少catalog.json");JSONArray cards;
                try(InputStream in=zip.getInputStream(manifest)){cards=new JSONObject(new String(readLimited(in,16L*1024*1024),StandardCharsets.UTF_8)).getJSONArray("cards");}
                if(cards.length()>10000)throw new IOException("离线包最多支持10000条");long[] total={0};
                // Validate every entry before committing any row; read named ZIP entries only.
                for(int i=0;i<cards.length();i++){
                    JSONObject o=cards.getJSONObject(i);Staged s=new Staged();stages.add(s);s.row=o;s.key=identity(o);s.file=zipFile(c,zip,o.getString("file"),total);s.info=bounds(s.file);s.hash=WalletStore.fileHash(s.file);
                    String expected=o.optString("sha256","");if(!expected.matches("(?i)[a-f0-9]{64}")||!expected.equalsIgnoreCase(s.hash))throw new IOException("离线图片SHA256校验失败："+o.optString("name"));
                    if(o.optInt("width",s.info.outWidth)!=s.info.outWidth||o.optInt("height",s.info.outHeight)!=s.info.outHeight||o.optLong("bytes",s.file.length())!=s.file.length())throw new IOException("离线图片尺寸或长度不匹配");
                    if(!o.optString("mime",s.info.outMimeType).equalsIgnoreCase(s.info.outMimeType))throw new IOException("离线图片格式不匹配");
                    String original=o.optString("originalFile","");if(!original.isEmpty()&&!original.equals(o.getString("file")))s.originalTemp=zipFile(c,zip,original,total);
                }
            }
            SQLiteDatabase d=db(c);int added=0;synchronized(WRITE){d.beginTransaction();try{
                for(Staged s:stages){added+=upsert(d,s.key,s.row);JSONObject previous=row(d,s.key);File destination=new File(dir(c),s.key+".image");
                    // The same URL is exact identity, but a supplied revised file is retained if its bytes changed.
                    if(!complete(previous)||!s.hash.equals(previous.optString("sha256")))Files.move(s.file.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
                    if(s.originalTemp!=null){File original=new File(dir(c),s.key+".original");Files.move(s.originalTemp.toPath(),original.toPath(),StandardCopyOption.REPLACE_EXISTING);s.original=original.getAbsolutePath();}
                    record(d,s.key,destination,s.info,s.hash,s.original);
                }d.setTransactionSuccessful();
            }finally{d.endTransaction();}}return added;
        }finally{archive.delete();for(Staged s:stages){if(s.file!=null)s.file.delete();if(s.originalTemp!=null)s.originalTemp.delete();}}
    }
    static byte[] fetch(String address,long limit) throws Exception {
        URL url=checkUrl(address);
        for(int i=0;i<5;i++){
            HttpsURLConnection conn=(HttpsURLConnection)url.openConnection();conn.setConnectTimeout(18000);conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(false);conn.setRequestProperty("User-Agent","WalletCardStudio/1.0");
            try{
                int code=conn.getResponseCode();
                if(code>=300&&code<400){String next=conn.getHeaderField("Location");if(next==null)throw new IOException("重定向地址缺失");url=checkUrl(new URL(url,next).toString());continue;}
                if(code!=200)throw new IOException("下载失败，HTTP "+code);
                if(conn.getContentLengthLong()>limit)throw new IOException("文件超过大小限制");
                try(InputStream in=conn.getInputStream()){byte[] bytes=readLimited(in,limit);long expected=conn.getContentLengthLong();if(expected>=0&&bytes.length!=expected)throw new IOException("下载未完成，请稍后重试");return bytes;}
            }finally{conn.disconnect();}
        }throw new IOException("重定向次数过多");
    }
    static URL checkUrl(String s) throws Exception {
        URL u=new URL(s);if(!u.getProtocol().equals("https")||u.getHost().trim().isEmpty()||u.getUserInfo()!=null)throw new IOException("请使用 HTTPS 图片直链");return u;
    }
    static byte[] readLimited(InputStream in,long limit) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[16384];int n;
        while((n=in.read(buf))!=-1){if(b.size()+n>limit)throw new IOException("文件太大");b.write(buf,0,n);}return b.toByteArray();
    }
    static Uri export(Context c,WalletStore.Art a) throws Exception {
        if(a.file==null||!a.file.isFile())throw new IOException("请先下载卡面");
        String ext=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(a.mime==null?"image/png":a.mime);if(ext==null)ext="png";
        String title=a.name.replaceAll("[\\\\/:*?\"<>|]","_");if(title.length()>55)title=title.substring(0,55);
        ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,title+"_"+System.currentTimeMillis()+"."+ext);
        v.put(MediaStore.Images.Media.MIME_TYPE,a.mime==null?"image/png":a.mime);v.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/钱包卡面");v.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new IOException("图库创建文件失败");
        try{
            try(InputStream in=new FileInputStream(a.file);OutputStream out=c.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("无法写入图库");byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            v.clear();v.put(MediaStore.Images.Media.IS_PENDING,0);c.getContentResolver().update(uri,v,null,null);return uri;
        }catch(Exception e){c.getContentResolver().delete(uri,null,null);throw e;}
    }
}
