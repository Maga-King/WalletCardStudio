package local.wallet.cardstudio;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.*;
import android.os.Process;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

final class WalletStore {
    static final String PKG = "com.finshell.wallet";
    // MainActivity and instrumentation/background callers can own different stores.
    // A transaction spans several root shells; serialize recovery and the entire
    // commit/cleanup sequence, not just individual shell invocations.
    private static final Object STORE_LOCK = new Object();
    final Context ctx;
    File pictures, pending;
    final File originals;
    boolean cacheOnly;
    private File work;
    final String remote, data;
    final int user;
    final ArrayList<Card> cards = new ArrayList<>();
    final LinkedHashMap<String, Art> images = new LinkedHashMap<>();

    static class Art {
        String key, name, url = "", mime, source = "", sourcePage = "";
        int width, height;
        File file;
    }
    static class Card {
        String id, name, type;
        ArrayList<Art> targets = new ArrayList<>();
        int shared = 1;
        Art primary() { return targets.isEmpty() ? null : targets.get(0); }
    }

    WalletStore(Context c) {
        ctx = c.getApplicationContext(); RootShell.init(ctx); user = Process.myUid()/100000;
        remote = "/storage/emulated/"+user+"/Android/data/"+PKG+"/files/Finshell/Wallet/DownloadPics";
        data = "/data/user/"+user+"/"+PKG;
        originals = new File(ctx.getFilesDir(),"originals"); originals.mkdirs();
        configurePaths();
    }
    static String sha(String s) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
    static String hex(byte[] bytes) { StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString(); }
    static String fileHash(File f) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(f)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return hex(md.digest());
    }
    static String q(File f) { return RootShell.q(f.getAbsolutePath()); }
    private String own(File f) { if(cacheOnly)return "";return "chown "+Process.myUid()+":"+Process.myUid()+" "+q(f)+"\nchmod 600 "+q(f)+"\n"; }
    String stopWallet() { return "am force-stop --user "+user+" "+PKG+"\n"; }
    private void configurePaths() {
        cacheOnly=RootShell.shellMode();
        if(cacheOnly){File base=ctx.getExternalFilesDir("wallet-work");if(base==null)throw new IllegalStateException("外部工作目录不可用");work=base;pictures=new File(base,"pics");pending=new File(base,"pending");}
        else {work=ctx.getCacheDir();pictures=new File(ctx.getCacheDir(),"wallet-pictures");pending=new File(ctx.getFilesDir(),"pending");}
        pictures.mkdirs();pending.mkdirs();
    }
    private void selectMode() throws IOException {
        File external=ctx.getExternalFilesDir("wallet-work");
        File other=RootShell.shellMode()?new File(ctx.getFilesDir(),"pending/transaction"):(external==null?null:new File(external,"pending/transaction"));
        if(other!=null&&other.exists())throw new IOException("另一权限模式存在未完成事务，请切回原模式刷新恢复后再切换");
        if(cacheOnly!=RootShell.shellMode())configurePaths();
    }
    static boolean hasPendingTransaction(Context c) {
        synchronized(STORE_LOCK){File ext=c.getExternalFilesDir("wallet-work");return new File(c.getFilesDir(),"pending/transaction").exists()||(ext!=null&&new File(ext,"pending/transaction").exists());}
    }
    boolean hasPendingTransaction(){return hasPendingTransaction(ctx);}
    String notice(){return cacheOnly?"Shizuku 缓存模式：不能读取钱包私有数据库。已识别名称来自上次 Root 记录；其余仅按缓存卡面显示。共享同一缓存的卡会一并变化。":"";}
    private String copyCommand(){return cacheOnly?"cp ":"cp -p ";}
    private void readPreviews() {
        images.clear();File[] files=pictures.listFiles();if(files==null)return;
        for(File f:files){if(!f.getName().matches("[a-f0-9]{64}\\.0"))continue;Art a=inspect(f);if(a==null)continue;a.key=f.getName().substring(0,64);a.name="缓存卡面·"+a.key.substring(0,6);images.put(a.key,a);}
    }
    private void copyShellPreviews() throws Exception {
        File[] stale=pictures.listFiles();if(stale!=null)for(File f:stale)f.delete();
        String script=stopWallet()+"test -d "+RootShell.q(remote)+" || { echo '钱包缓存目录不可读，请先在钱包打开卡片'; exit 1; }\n"+
            "for f in "+RootShell.q(remote)+"/*.0; do\n[ -f \"$f\" ] || continue\n[ ! -L \"$f\" ] || continue\nn=\"${f##*/}\"\ncp \"$f\" "+q(pictures)+"/\"$n\"\ndone\n";
        RootShell.run(script);
    }
    private void scanCache() throws Exception {
        copyShellPreviews();readPreviews();cards.clear();HashSet<String> mapped=new HashSet<>();File map=new File(ctx.getFilesDir(),"wallet-card-map.json");
        if(map.isFile())try{
            JSONArray saved=new JSONArray(new String(Files.readAllBytes(map.toPath()),StandardCharsets.UTF_8));
            for(int i=0;i<saved.length();i++){
                JSONObject o=saved.getJSONObject(i);Card c=new Card();c.id=o.getString("id");c.name=o.getString("name");c.type=o.getString("type");JSONArray targets=o.getJSONArray("targets");
                for(int j=0;j<targets.length();j++){JSONObject t=targets.getJSONObject(j);Art a=images.get(t.optString("key"));if(a!=null){a.name=c.name;a.url=t.optString("url","");c.targets.add(a);mapped.add(a.key);}}
                if(!c.targets.isEmpty())cards.add(c);
            }
        }catch(JSONException badMap){cards.clear();mapped.clear();}
        for(Art a:images.values())if(!mapped.contains(a.key)&&a.width>=250&&a.width/(float)a.height>=1.25f&&a.width/(float)a.height<=2.3f){Card c=new Card();c.id="cache_"+a.key;c.type="cache";c.name="缓存卡面·"+a.key.substring(0,6);c.targets.add(a);cards.add(c);}
        for(Card c:cards){Set<String> sharing=new HashSet<>();for(Card other:cards)for(Art a:other.targets)for(Art b:c.targets)if(a.key.equals(b.key))sharing.add(other.id);c.shared=Math.max(1,sharing.size());}
    }
    private void saveCardMap() throws Exception {
        JSONArray saved=new JSONArray();for(Card c:cards){JSONObject o=new JSONObject();o.put("id",c.id);o.put("name",c.name);o.put("type",c.type);JSONArray targets=new JSONArray();for(Art a:c.targets){JSONObject t=new JSONObject();t.put("key",a.key);t.put("url",a.url);targets.put(t);}o.put("targets",targets);saved.put(o);}
        File file=new File(ctx.getFilesDir(),"wallet-card-map.json"),temp=new File(ctx.getFilesDir(),"wallet-card-map.tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(saved.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private Map<String,String> prepareShellBackups(Card card,boolean restore) throws Exception {
        // App, not shell, owns authoritative backups. Refresh while wallet is stopped.
        StringBuilder script=new StringBuilder(stopWallet());Map<String,File> snapshots=new LinkedHashMap<>();
        File snapshotDir=new File(work,"snapshot");snapshotDir.mkdirs();
        for(Art a:card.targets){if(!a.key.matches("[a-f0-9]{64}"))throw new IOException("无效缓存文件名");File snapshot=new File(snapshotDir,a.key+".snapshot");snapshots.put(a.key,snapshot);String remoteFile=RootShell.q(remote+"/"+a.key+".0");script.append("test -f ").append(remoteFile).append("\ntest ! -L ").append(remoteFile).append("\ncp ").append(remoteFile).append(" ").append(q(snapshot)).append("\n");}
        try{
            RootShell.run(script.toString());Map<String,String> hashes=new LinkedHashMap<>();
            for(Art a:card.targets){File snapshot=snapshots.get(a.key);Art current=inspect(snapshot);if(current==null)throw new IOException("当前远程缓存不是有效图片");if(current.width!=a.width||current.height!=a.height)throw new IOException("远程卡面尺寸已变化，请刷新列表后重试");String hash=fileHash(snapshot);hashes.put(a.key,hash);
                Files.copy(snapshot.toPath(),new File(pictures,a.key+".0").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                File backup=original(a);if(!restore&&!backup.isFile()){File temp=new File(originals,UUID.randomUUID()+".backup");try{Files.copy(snapshot.toPath(),temp.toPath());try(FileOutputStream out=new FileOutputStream(temp,true)){out.getFD().sync();}Files.move(temp.toPath(),backup.toPath());}finally{temp.delete();}}
            }return hashes;
        }finally{for(File snapshot:snapshots.values())snapshot.delete();}
    }

    synchronized void scan() throws Exception {
        synchronized(STORE_LOCK) { scanLocked(); }
    }
    private void scanLocked() throws Exception {
        selectMode();
        recoverTransaction();
        if(cacheOnly){scanCache();return;}
        File snapshot=new File(ctx.getCacheDir(),"wallet-snapshot");snapshot.mkdirs();
        for(File f:Objects.requireNonNull(snapshot.listFiles()))f.delete();
        File dbFile=new File(snapshot,"wallet");
        String db=data+"/databases/wallet";
        String script="test \"$(id -u)\" = 0\n"+stopWallet()+
            "test -f "+RootShell.q(db)+" || { echo '没有找到钱包数据库，请先在钱包中打开卡包'; exit 1; }\n"+
            "cp "+RootShell.q(db)+" "+q(dbFile)+"\n"+own(dbFile)+
            "if [ -f "+RootShell.q(db+"-wal")+" ]; then cp "+RootShell.q(db+"-wal")+" "+q(new File(snapshot,"wallet-wal"))+"\n"+own(new File(snapshot,"wallet-wal"))+"fi\n"+
            "if [ -d "+RootShell.q(remote)+" ]; then for f in "+RootShell.q(remote)+"/*.0; do\n"+
            "[ -f \"$f\" ] || continue\n[ ! -L \"$f\" ] || continue\n"+
            "n=\"${f##*/}\"\ncp \"$f\" "+q(pictures)+"/\"$n\"\nchown "+Process.myUid()+":"+Process.myUid()+" "+q(pictures)+"/\"$n\"\nchmod 600 "+q(pictures)+"/\"$n\"\ndone\nfi\n";
        // Remove only our preview copies; wallet files and backups are untouched.
        for(File f:Objects.requireNonNull(pictures.listFiles()))f.delete();
        try {
            RootShell.run(script);
            images.clear();cards.clear();
            File[] pics=pictures.listFiles();if(pics!=null)for(File f:pics){
                if(!f.getName().matches("[a-f0-9]{64}\\.0"))continue;
                Art a=inspect(f);if(a==null)continue;a.key=f.getName().substring(0,64);a.name="缓存卡面 · "+a.key.substring(0,6);images.put(a.key,a);
            }
            try(SQLiteDatabase dbCopy=SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)){
                try(Cursor check=dbCopy.rawQuery("PRAGMA quick_check(1)",null)){
                    if(!check.moveToFirst() || !"ok".equals(check.getString(0)))throw new IOException("钱包数据库快照校验失败，请重新刷新");
                }
                Set<String> columns=new HashSet<>();
                try(Cursor cur=dbCopy.rawQuery("PRAGMA table_info(NFC_CARD)",null)){while(cur.moveToNext())columns.add(cur.getString(1));}
                for(String col:new String[]{"_id","DISPLAY_NAME","CARD_TYPE","CARD_IMG"})
                    if(!columns.contains(col))throw new IOException("当前钱包数据库结构暂不支持：缺少 "+col);
                String optional=columns.contains("NEW_CARD_IMG")?"NEW_CARD_IMG":"NULL";
                try(Cursor cur=dbCopy.rawQuery("SELECT _id,DISPLAY_NAME,CARD_TYPE,"+optional+",CARD_IMG FROM NFC_CARD WHERE CAST(CARD_TYPE AS TEXT) IN ('1','5') ORDER BY CARD_TYPE,_id",null)){
                    while(cur.moveToNext()){
                        Card c=new Card();c.id=cur.getString(0);c.name=cur.getString(1);c.type=cur.getString(2);
                        if(c.name==null||c.name.trim().isEmpty())c.name="1".equals(c.type)?"银行卡":"公交卡";
                        LinkedHashSet<String> urls=new LinkedHashSet<>();
                        for(int i=3;i<=4;i++)if(!cur.isNull(i)&&cur.getString(i).startsWith("https://"))urls.add(cur.getString(i));
                        for(String url:urls){
                            String key=sha(url);Art a=images.get(key);
                            CloudStore.remember(ctx,c.name,url);
                            if(a!=null&&a.width>200&&a.height>100){a.name=c.name;a.url=url;c.targets.add(a);}
                        }
                        cards.add(c);
                    }
                }
            }
            for(Card c:cards)if(c.primary()!=null){
                HashSet<String> sharedIds=new HashSet<>();
                for(Card other:cards)for(Art a:other.targets)for(Art mine:c.targets)if(a.key.equals(mine.key))sharedIds.add(other.id);
                c.shared=Math.max(1,sharedIds.size());
            }
            saveCardMap();
        } finally { for(File f:Objects.requireNonNull(snapshot.listFiles()))f.delete(); }
    }
    static Art inspect(File file) {
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),o);
        if(o.outWidth<1||o.outHeight<1)return null;
        Art a=new Art();a.file=file;a.width=o.outWidth;a.height=o.outHeight;a.mime=o.outMimeType;a.name=file.getName();return a;
    }
    List<Art> gallery() throws Exception {
        ArrayList<Art> result=new ArrayList<>();HashSet<String> keys=new HashSet<>();
        for(Art a:images.values())if(a.width>=250&&a.width/(float)a.height>=1.25f&&a.width/(float)a.height<=2.3f){result.add(a);keys.add(a.key);}
        for(Art a:CloudStore.downloaded(ctx))if(!keys.contains(a.key))result.add(a);
        return result;
    }
    File original(Art a) { return new File(originals,a.key+".original"); }
    boolean canRestore(Card c) { return !c.targets.isEmpty()&&c.targets.stream().allMatch(a->original(a).isFile()); }
    boolean customized(Card c) { return ctx.getSharedPreferences("changes",0).getBoolean("card_"+c.id,false); }

    private static Bitmap.CompressFormat format(String mime) {
        if("image/webp".equals(mime))return Bitmap.CompressFormat.WEBP_LOSSLESS;
        if("image/jpeg".equals(mime))return Bitmap.CompressFormat.JPEG;
        return Bitmap.CompressFormat.PNG;
    }
    synchronized void replace(Card c, File crop) throws Exception {
        synchronized(STORE_LOCK){ selectMode(); replaceLocked(c,crop); }
    }
    private void replaceLocked(Card c, File crop) throws Exception {
        if(c.primary()==null)throw new IOException("请先在钱包中打开这张卡，让钱包下载卡面，再刷新列表");
        Bitmap bitmap=BitmapFactory.decodeFile(crop.getAbsolutePath());if(bitmap==null)throw new IOException("裁切图片无法解码");
        ArrayList<File> sources=new ArrayList<>();
        try {
            for(Art a:c.targets){
                if(a.width>4096||a.height>4096)throw new IOException("卡面尺寸过大");
                File out=new File(pending,UUID.randomUUID()+".image");
                Bitmap scaled=Bitmap.createScaledBitmap(bitmap,a.width,a.height,true);
                try(FileOutputStream stream=new FileOutputStream(out)){if(!scaled.compress(format(a.mime),100,stream))throw new IOException("图片编码失败");stream.getFD().sync();}
                if(scaled!=bitmap)scaled.recycle();sources.add(out);
            }
            commit(c,sources,false);
        } finally {bitmap.recycle();if(!transaction().exists())for(File f:sources)f.delete();crop.delete();}
    }
    synchronized void restore(Card c) throws Exception {
        synchronized(STORE_LOCK){selectMode();restoreLocked(c);}
    }
    private void restoreLocked(Card c) throws Exception {
        if(!canRestore(c))throw new IOException("这张卡还没有原图备份");
        ArrayList<File> sources=new ArrayList<>();
        try {
            for(Art a:c.targets){File source=original(a);if(cacheOnly){source=new File(pending,UUID.randomUUID()+".restore");Files.copy(original(a).toPath(),source.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}sources.add(source);}
            commit(c,sources,true);
        }finally{if(cacheOnly&&!transaction().exists())for(File source:sources)source.delete();}
    }
    private File transaction() { return new File(pending,"transaction"); }
    private String rollbackScript(List<String> keys, File txn) {
        StringBuilder s=new StringBuilder("recover() {\nset +e\nfailed=0\n");
        s.append("if [ -f ").append(q(new File(txn,"prepared"))).append(" ] && [ ! -f ").append(q(new File(txn,"committed"))).append(" ]; then\n");
        for(String key:keys) {
            String backup=RootShell.q(remote+"/"+key+".studio-undo");
            s.append("if [ -f ").append(backup).append(" ]; then mv -f ").append(backup).append(" ").append(RootShell.q(remote+"/"+key+".0")).append(" || failed=1; fi\n");
        }
        String backup=RootShell.q(remote+"/journal.studio-undo");
        s.append("if [ \"$failed\" = 0 ] && [ -f ").append(backup).append(" ]; then mv -f ").append(backup).append(" ").append(RootShell.q(remote+"/journal")).append(" || failed=1; fi\nfi\n");
        s.append("[ \"$failed\" = 0 ] || return 1\n");
        for(String key:keys)s.append("rm -f ").append(RootShell.q(remote+"/"+key+".studio-tmp")).append(" ").append(RootShell.q(remote+"/"+key+".studio-undo")).append(" || return 1\n");
        s.append("rm -f ").append(backup).append(" ").append(RootShell.q(remote+"/journal.studio-tmp")).append(" || return 1\nsync\nreturn 0\n}\n");
        return s.toString();
    }
    private void finishCommitted(List<String> keys,File txn) throws Exception {
        RootShell.run("echo $$ > "+q(new File(txn,"pid"))+"\n"+rollbackScript(keys,txn)+"recover || { echo '事务已提交，但备份清理未完成，请刷新'; exit 1; }\n");
        clearTransaction(txn);
    }
    private void recoverTransaction() throws Exception {
        File txn=transaction(); if(!txn.exists())return;
        File keysFile=new File(txn,"keys");
        if(!keysFile.isFile())throw new IOException("存在未完成事务，缺少恢复清单；备份已保留，请勿再次替换");
        List<String> keys=Files.readAllLines(keysFile.toPath(),StandardCharsets.UTF_8);
        for(String key:keys)if(!key.matches("[a-f0-9]{64}"))throw new IOException("事务恢复清单无效，备份已保留");
        String pid=RootShell.q(new File(txn,"pid").getAbsolutePath());
        String script="test -s "+pid+" || { echo '事务进程状态未知，备份已保留，请稍后刷新'; exit 1; }\n"+
            "p=$(cat "+pid+")\ncase \"$p\" in ''|*[!0-9]*) exit 1;; esac\n"+
            "if kill -0 \"$p\" 2>/dev/null; then echo '上一次事务仍在运行，请稍后刷新'; exit 1; fi\n"+
            "echo $$ > "+pid+"\n"+
            stopWallet()+rollbackScript(keys,txn)+"recover || { echo '原子恢复未完成，备份已保留'; exit 1; }\n";
        RootShell.run(script);
        clearTransaction(txn);
    }
    private void clearTransaction(File txn) throws IOException {
        File[] files=txn.listFiles();if(files!=null)for(File f:files)if(!f.delete())throw new IOException("无法清理已完成事务状态");
        if(!txn.delete())throw new IOException("无法清理已完成事务状态");
    }
    private String checkedJournal() throws Exception {
        File copy=new File(pending,"journal-check");
        // Pre-create in our own SELinux domain; root only writes the existing inode.
        try(FileOutputStream out=new FileOutputStream(copy)) { }
        try {
            RootShell.run(stopWallet()+"test ! -L "+RootShell.q(remote+"/journal")+"\ncat "+RootShell.q(remote+"/journal")+" > "+q(copy)+"\n");
            byte[] bytes=Files.readAllBytes(copy.toPath());
            if(bytes.length==0||bytes[bytes.length-1]!='\n')throw new IOException("钱包缓存索引尾部不完整，请先打开钱包让其恢复，再刷新");
            String[] lines=new String(bytes,StandardCharsets.US_ASCII).split("\n",-1);
            if(lines.length<6||!"libcore.io.DiskLruCache".equals(lines[0])||!"1".equals(lines[1])||!lines[2].matches("[0-9]+")||!"1".equals(lines[3])||!lines[4].isEmpty())throw new IOException("不支持的图片缓存索引格式");
            for(int i=5;i<lines.length-1;i++) {
                String line=lines[i];
                if(line.matches("(DIRTY|READ|REMOVE) [a-f0-9]{64}"))continue;
                if(!line.matches("CLEAN [a-f0-9]{64} [0-9]+"))throw new IOException("钱包缓存索引记录不完整，请先打开钱包恢复，再刷新");
                try { Long.parseLong(line.substring(line.lastIndexOf(' ')+1)); }catch(NumberFormatException e){throw new IOException("缓存索引长度无效",e);}
            }
            return fileHash(copy);
        } finally {copy.delete();}
    }
    private void commit(Card c,List<File> sources,boolean restore) throws Exception {
        synchronized(STORE_LOCK) { commitLocked(c,sources,restore); }
    }
    private void commitLocked(Card c,List<File> sources,boolean restore) throws Exception {
        selectMode();
        recoverTransaction();
        Map<String,String> expected=cacheOnly?prepareShellBackups(c,restore):Collections.emptyMap();
        String journalHash=checkedJournal();
        String dir=RootShell.q(remote), journal=RootShell.q(remote+"/journal");
        ArrayList<String> keys=new ArrayList<>();
        for(Art a:c.targets){if(!a.key.matches("[a-f0-9]{64}"))throw new IOException("无效的缓存文件名");keys.add(a.key);}
        File txn=transaction();if(!txn.mkdir())throw new IOException("存在未完成事务，请先刷新恢复");
        File keysFile=new File(txn,"keys"), pidFile=new File(txn,"pid");
        try(FileOutputStream out=new FileOutputStream(keysFile)) {out.write((String.join("\n",keys)+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        // Existing app-owned inode keeps its SELinux label when root records the PID.
        try(FileOutputStream out=new FileOutputStream(pidFile)) { }
        StringBuilder s=new StringBuilder("echo $$ > "+q(pidFile)+"\n"+stopWallet());
        s.append("test -d ").append(dir).append("\n");
        s.append("test \"$(sha256sum ").append(journal).append(" | cut -d ' ' -f 1)\" = ").append(RootShell.q(journalHash)).append(" || { echo '缓存索引已变化，请刷新后重试'; exit 1; }\n");
        s.append(rollbackScript(keys,txn));
        s.append("trap 'status=$?; recover; exit \"$status\"' EXIT\n");
        for(int i=0;i<c.targets.size();i++){
            Art a=c.targets.get(i);if(!a.key.matches("[a-f0-9]{64}"))throw new IOException("无效的缓存文件名");
            String target=RootShell.q(remote+"/"+a.key+".0");
            s.append("test -f ").append(target).append(" || { echo '卡面缓存已消失，请打开钱包后刷新'; exit 1; }\n");
            s.append("test ! -L ").append(target).append("\n");
            if(cacheOnly)s.append("test \"$(sha256sum ").append(target).append(" | cut -d ' ' -f 1)\" = ").append(RootShell.q(expected.get(a.key))).append(" || { echo 'Cache changed; refresh before retry'; exit 1; }\n");
            String undo=RootShell.q(remote+"/"+a.key+".studio-undo");
            s.append("test ! -e ").append(undo).append("\n").append(copyCommand()).append(target).append(" ").append(undo).append("\n");
            if(!restore&&!cacheOnly){
                File original=original(a),temp=new File(originals,a.key+".temp");
                if(!original.isFile())try(FileOutputStream out=new FileOutputStream(temp)){ }
                s.append("if [ ! -f ").append(q(original)).append(" ]; then cp ").append(target).append(" ").append(q(temp)).append("\n").append(own(temp)).append("mv ").append(q(temp)).append(" ").append(q(original)).append("\nfi\n");
            }
            String tmp=RootShell.q(remote+"/"+a.key+".studio-tmp");
            s.append(copyCommand()).append(target).append(" ").append(tmp).append("\ncat ").append(q(sources.get(i))).append(" > ").append(tmp).append("\n");
            s.append("test \"$(sha256sum ").append(tmp).append(" | cut -d ' ' -f 1)\" = ").append(RootShell.q(fileHash(sources.get(i)))).append("\n");
        }
        String nextJournal=RootShell.q(remote+"/journal.studio-tmp");
        s.append("test ! -e ").append(RootShell.q(remote+"/journal.studio-undo")).append("\n").append(copyCommand()).append(journal).append(" ").append(RootShell.q(remote+"/journal.studio-undo")).append("\n").append(copyCommand()).append(journal).append(" ").append(nextJournal).append("\n");
        for(int i=0;i<c.targets.size();i++)s.append("printf 'CLEAN ").append(c.targets.get(i).key).append(" ").append(sources.get(i).length()).append("\\n' >> ").append(nextJournal).append("\n");
        s.append("sync\n");
        if(cacheOnly){
            for(Art a:c.targets)s.append("test \"$(sha256sum ").append(RootShell.q(remote+"/"+a.key+".0")).append(" | cut -d ' ' -f 1)\" = ").append(RootShell.q(expected.get(a.key))).append(" || { echo 'Cache changed; refresh before retry'; exit 1; }\n");
            s.append("test \"$(sha256sum ").append(journal).append(" | cut -d ' ' -f 1)\" = ").append(RootShell.q(journalHash)).append(" || { echo 'Journal changed; refresh before retry'; exit 1; }\n");
        }
        s.append("touch ").append(q(new File(txn,"prepared"))).append("\nsync\n");
        for(int i=0;i<c.targets.size();i++){
            Art a=c.targets.get(i);String target=RootShell.q(remote+"/"+a.key+".0");
            s.append("mv -f ").append(RootShell.q(remote+"/"+a.key+".studio-tmp")).append(" ").append(target).append("\n");
            s.append("test \"$(sha256sum ").append(target).append(" | cut -d ' ' -f 1)\" = ").append(RootShell.q(fileHash(sources.get(i)))).append("\n");
        }
        s.append("mv -f ").append(nextJournal).append(" ").append(journal).append("\n");
        // A committed marker makes recovery finish cleanup rather than undo success.
        s.append("sync\ntouch ").append(q(new File(txn,"committed"))).append("\nsync\ntrap - EXIT\n");
        RootShell.run(s.toString());
        finishCommitted(keys,txn);
        for(Art a:c.targets){Files.copy(sources.get(c.targets.indexOf(a)).toPath(),a.file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        android.content.SharedPreferences.Editor e=ctx.getSharedPreferences("changes",0).edit();
        for(Card other:cards)for(Art a:other.targets)for(Art b:c.targets)if(a.key.equals(b.key))e.putBoolean("card_"+other.id,!restore);
        e.apply();
    }
}
