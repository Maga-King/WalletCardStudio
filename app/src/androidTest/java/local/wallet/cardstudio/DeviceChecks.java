package local.wallet.cardstudio;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Opt-in device checks: normal app identity, actual Root access and cache round trip. */
public final class DeviceChecks extends Instrumentation {
    Bundle args;
    final StringBuilder report=new StringBuilder();
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    void note(String text){report.append(text).append('\n');Bundle update=new Bundle();update.putString("stream",text+"\n");sendStatus(0,update);}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            note("Starting device checks");
            Context c=getTargetContext();
            final Throwable[] cropError={null};
            runOnMainSync(()->{try{
                CropActivity.CropView view=new CropActivity.CropView(c,936,576);view.layout(0,0,1080,1200);
                Bitmap source=Bitmap.createBitmap(2000,1000,Bitmap.Config.ARGB_8888);source.eraseColor(Color.MAGENTA);view.setBitmap(source);
                view.left+=5000;view.top-=5000;view.clamp();
                Bitmap out=view.result();require(out.getWidth()==936&&out.getHeight()==576,"Crop dimensions changed");
                require(out.getPixel(0,0)==Color.MAGENTA&&out.getPixel(935,575)==Color.MAGENTA,"Crop leaves an uncovered edge");out.recycle();
                view.rotate();out=view.result();require(out.getPixel(0,0)==Color.MAGENTA&&out.getPixel(935,575)==Color.MAGENTA,"Rotated crop leaves an uncovered edge");out.recycle();view.dispose();
            }catch(Throwable t){cropError[0]=t;}});
            if(cropError[0]!=null)throw new AssertionError("Crop failed",cropError[0]);note("PASS: crop ratio, edge clamping and rotation");
            if(args!=null&&"true".equals(args.getString("bootstrap"))){note("Importing local artwork archive");CloudStore.bootstrap(c);}
            List<WalletStore.Art> cloud=CloudStore.entries(c);WalletStore.Art sample=null;int mi=0,apple=0,website=0,issues=0,checked=0,bili=0;
            for(WalletStore.Art a:cloud){
                if("Mi Pay".equals(a.source)){mi++;if(sample==null)sample=a;}
                if("Apple Pay".equals(a.source))apple++;
                boolean site=a.sourcePage!=null&&a.sourcePage.contains("cards.no2.ac");
                boolean issue=a.sourcePage!=null&&a.sourcePage.contains("github.com/no2ac/Cardentify");
                if(site)website++;if(issue)issues++;
                if(site&&a.name.contains("2233")&&a.name.contains("中国银行"))bili++;
                JSONObject stored=CloudStore.row(CloudStore.db(c),a.key);
                if(a.width>0){require(CloudStore.complete(stored),"Incomplete local original: "+a.name);checked++;if(checked%200==0)note("Verified "+checked+" local originals");}
            }
            require(website>=842&&issues>=21&&mi>=72&&apple>0&&bili>=2&&sample!=null,"Offline sources or Issue 122 variants missing");
            note("PASS: "+checked+" SHA256-verified originals; website="+website+", issues="+issues+", Mi Pay="+mi+", Apple Pay="+apple+", Issue122="+bili);
            byte[] original=java.nio.file.Files.readAllBytes(sample.file.toPath());
            Uri exported=CloudStore.export(c,sample);try(InputStream in=c.getContentResolver().openInputStream(exported)){require(in!=null&&Arrays.equals(original,CloudStore.readLimited(in,CloudStore.MAX)),"Gallery export changes original bytes");}finally{c.getContentResolver().delete(exported,null,null);}
            note("PASS: gallery export preserves exact original bytes");
            archiveChecks(c);
            if(args!=null&&"true".equals(args.getString("network"))){
                CloudStore.SyncResult sync=CloudStore.syncCardentifyOffline(c,false);
                require(sync.added==0&&sync.downloaded==0&&sync.failed==0&&sync.skipped>=842,"Incremental sync re-downloads a verified local original");
                note("PASS: manual incremental sync skipped "+sync.skipped+" exact originals, zero image downloads");
            }
            if(args!=null&&"true".equals(args.getString("wallet"))){
                WalletStore store=new WalletStore(c);store.scan();require(!store.cards.isEmpty(),"Wallet cards not found");
                int mapped=0;for(WalletStore.Card card:store.cards)if(card.primary()!=null)mapped++;
                require(mapped>0,"No artwork mapped to wallet cards");
                if("true".equals(args.getString("shell"))){
                    require(store.cacheOnly&&RootShell.usingShizuku(),"Shell test is not using Shizuku cache mode");
                    require("2000".equals(RootShell.run("id -u").trim()),"Shizuku service is not shell UID 2000");
                    require(RootShell.run("if test -r "+RootShell.q(store.data+"/databases/wallet")+"; then echo READABLE; else echo DENIED; fi").trim().equals("DENIED"),"Shell unexpectedly has wallet private database access");
                    int remembered=0;for(WalletStore.Card card:store.cards)if(!"cache".equals(card.type))remembered++;
                    require(remembered>=3,"Previous Root card-name mapping not retained");note("PASS: actual Shizuku UID 2000, private DB denied, "+store.cards.size()+" cache entries, "+remembered+" remembered cards");
                }else note("PASS: Root global mount, database snapshot, "+store.cards.size()+" cards, "+mapped+" mapped images");
                if("true".equals(args.getString("roundtrip"))){if(store.cacheOnly)roundTripWithBackups(c,store);else roundTrip(c,store);}
            }
            result.putString("stream",report.toString());finish(-1,result);
        }catch(Throwable t){result.putString("stream",report+"FAILED: "+t+"\n"+android.util.Log.getStackTraceString(t));finish(0,result);}
    }
    void archiveChecks(Context c)throws Exception{
        String[] urls={"https://cardstudio-check.invalid/first.png","https://cardstudio-check.invalid/second.png"};
        String[] keys={WalletStore.sha(urls[0]),WalletStore.sha(urls[1])};
        Bitmap b=Bitmap.createBitmap(20,12,Bitmap.Config.ARGB_8888);b.eraseColor(Color.CYAN);ByteArrayOutputStream encoded=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,encoded);b.recycle();byte[] bytes=encoded.toByteArray();
        String sha=WalletStore.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        JSONArray cards=new JSONArray();for(int i=0;i<2;i++){JSONObject row=new JSONObject();row.put("name","Same test name");row.put("url",urls[i]);row.put("file","images/"+i+".png");row.put("sha256",sha);cards.put(row);}
        ByteArrayOutputStream data=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(data)){zip.putNextEntry(new ZipEntry("catalog.json"));zip.write(new JSONObject().put("cards",cards).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();for(int i=0;i<2;i++){zip.putNextEntry(new ZipEntry("images/"+i+".png"));zip.write(bytes);zip.closeEntry();}}
        try{
            require(CloudStore.importArchive(c,new ByteArrayInputStream(data.toByteArray()))==2,"Distinct full URLs were merged by equal name or content");
            require(CloudStore.importArchive(c,new ByteArrayInputStream(data.toByteArray()))==0,"Exact archive import adds duplicates");
            File damaged=new File(CloudStore.row(CloudStore.db(c),keys[0]).getString("file"));try(FileOutputStream out=new FileOutputStream(damaged)){out.write(0);}
            require(!CloudStore.complete(CloudStore.row(CloudStore.db(c),keys[0])),"Corrupt original incorrectly marked complete");
            require(CloudStore.importArchive(c,new ByteArrayInputStream(data.toByteArray()))==0&&CloudStore.complete(CloudStore.row(CloudStore.db(c),keys[0])),"Archive import failed to repair damaged original");
            note("PASS: full-URL identity, equal-name/content variants retained, duplicate import skipped, damaged file repaired");
        }finally{for(String key:keys){CloudStore.db(c).delete("card_sources","key=?",new String[]{key});CloudStore.db(c).delete("cards","key=?",new String[]{key});new File(CloudStore.dir(c),key+".image").delete();}}
    }
    void roundTrip(Context c,WalletStore store)throws Exception{
        WalletStore.Card candidate=null;
        for(WalletStore.Card card:store.cards){if(card.primary()==null||store.customized(card))continue;boolean virgin=true;for(WalletStore.Art a:card.targets)if(store.original(a).exists())virgin=false;if(virgin){candidate=card;break;}}
        if(candidate==null){roundTripWithBackups(c,store);return;}
        final WalletStore.Card card=candidate;ArrayList<String> hashes=new ArrayList<>();ArrayList<File> baseline=new ArrayList<>();
        String db=store.data+"/databases/wallet";String dbHash=RootShell.run("sha256sum "+RootShell.q(db)).trim();
        for(WalletStore.Art a:card.targets){hashes.add(WalletStore.fileHash(a.file));File copy=new File(c.getCacheDir(),a.key+".device-check");java.nio.file.Files.copy(a.file.toPath(),copy.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);baseline.add(copy);}
        File crop=new File(c.getCacheDir(),"device-check-crop.png");Bitmap bitmap=Bitmap.createBitmap(card.primary().width,card.primary().height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.rgb(41,108,91));try(FileOutputStream out=new FileOutputStream(crop)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
        boolean restored=false;
        try{
            store.replace(card,crop);require(store.canRestore(card),"Replacement did not create backups");require(store.customized(card),"Replacement status missing");
            for(int i=0;i<card.targets.size();i++){WalletStore.Art a=card.targets.get(i);require(!WalletStore.fileHash(a.file).equals(hashes.get(i)),"Replacement did not change artwork");String remote=RootShell.run("sha256sum "+RootShell.q(store.remote+"/"+a.key+".0")).trim().split(" ")[0];require(remote.equals(WalletStore.fileHash(a.file)),"Replacement preview and wallet differ");}
            store.restore(card);for(int i=0;i<card.targets.size();i++)require(WalletStore.fileHash(card.targets.get(i).file).equals(hashes.get(i)),"Restore did not recover exact original bytes");
            require(!store.customized(card),"Restore status not reset");require(RootShell.run("sha256sum "+RootShell.q(db)).trim().equals(dbHash),"Wallet database changed");
            String journal=RootShell.run("cat "+RootShell.q(store.remote+"/journal"));require(journal.startsWith("libcore.io.DiskLruCache\n"),"Cache journal header changed");require(!journal.substring(journal.indexOf("\n\n")+2).contains("\n\n"),"Blank journal line breaks wallet cache");
            for(WalletStore.Art a:card.targets)require(journal.contains("CLEAN "+a.key+" "+a.file.length()+"\n"),"Cache journal image size not updated");
            restored=true;note("PASS: actual cache replace + exact-byte restore, valid journal, wallet DB unchanged");
        }finally{
            if(!restored){StringBuilder rollback=new StringBuilder(store.stopWallet());for(int i=0;i<card.targets.size();i++){WalletStore.Art a=card.targets.get(i);rollback.append("cat ").append(WalletStore.q(baseline.get(i))).append(" > ").append(RootShell.q(store.remote+"/"+a.key+".0")).append("\nprintf 'CLEAN ").append(a.key).append(" ").append(baseline.get(i).length()).append("\\n' >> ").append(RootShell.q(store.remote+"/journal")).append("\n");}rollback.append("sync\n");RootShell.run(rollback.toString());}
            for(File f:baseline)f.delete();crop.delete();
        }
    }
    void roundTripWithBackups(Context c,WalletStore store)throws Exception{
        WalletStore.Card card=null;for(WalletStore.Card item:store.cards)if(item.primary()!=null&&store.canRestore(item)){card=item;break;}
        require(card!=null,"No mapped card available for cache round trip");
        final WalletStore.Card target=card;ArrayList<File> baseline=new ArrayList<>();ArrayList<String> currentHashes=new ArrayList<>(),backupHashes=new ArrayList<>();
        Map<String,?> preferences=new HashMap<>(c.getSharedPreferences("changes",0).getAll());
        String db=store.data+"/databases/wallet",dbHash=store.cacheOnly?null:RootShell.run("sha256sum "+RootShell.q(db)).trim();
        for(WalletStore.Art a:target.targets){currentHashes.add(WalletStore.fileHash(a.file));backupHashes.add(WalletStore.fileHash(store.original(a)));File copy=new File(store.cacheOnly?store.pending:c.getCacheDir(),a.key+".preserved-check");java.nio.file.Files.copy(a.file.toPath(),copy.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);baseline.add(copy);}
        File crop=new File(c.getCacheDir(),"device-check-existing.png");Bitmap bitmap=Bitmap.createBitmap(target.primary().width,target.primary().height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.rgb(41,108,91));try(FileOutputStream out=new FileOutputStream(crop)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
        try{
            store.replace(target,crop);
            for(int i=0;i<target.targets.size();i++)require(!WalletStore.fileHash(target.targets.get(i).file).equals(currentHashes.get(i)),"Replacement did not change artwork");
            store.restore(target);
            for(int i=0;i<target.targets.size();i++)require(WalletStore.fileHash(target.targets.get(i).file).equals(backupHashes.get(i)),"Restore differs from existing original backup");
        }finally{
            // Restore the exact pre-test custom artwork through the production transaction.
            // Existing original backups and the user's per-card preferences are preserved.
            java.lang.reflect.Method commit=WalletStore.class.getDeclaredMethod("commit",WalletStore.Card.class,List.class,boolean.class);commit.setAccessible(true);commit.invoke(store,target,baseline,true);
            android.content.SharedPreferences.Editor editor=c.getSharedPreferences("changes",0).edit();
            for(WalletStore.Card item:store.cards){String key="card_"+item.id;Object value=preferences.get(key);if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else editor.remove(key);}editor.commit();
            for(int i=0;i<target.targets.size();i++){WalletStore.Art a=target.targets.get(i);require(WalletStore.fileHash(a.file).equals(currentHashes.get(i)),"Pre-test artwork was not recovered");require(WalletStore.fileHash(store.original(a)).equals(backupHashes.get(i)),"Existing original backup changed");String remote=RootShell.run("sha256sum "+RootShell.q(store.remote+"/"+a.key+".0")).trim().split(" ")[0];require(remote.equals(currentHashes.get(i)),"Wallet cache differs from pre-test bytes");}
            for(File file:baseline)file.delete();crop.delete();
        }
        if(dbHash!=null)require(RootShell.run("sha256sum "+RootShell.q(db)).trim().equals(dbHash),"Wallet database changed");
        String journal=RootShell.run("cat "+RootShell.q(store.remote+"/journal"));require(journal.startsWith("libcore.io.DiskLruCache\n1\n")&&!journal.substring(journal.indexOf("\n\n")+2).contains("\n\n"),"Invalid cache journal");
        for(WalletStore.Art a:target.targets)require(journal.contains("CLEAN "+a.key+" "+a.file.length()+"\n"),"Cache journal length missing");
        note("PASS: "+RootShell.modeLabel()+" actual cache replace + restore; pre-test custom artwork, original backups and preferences preserved"+(dbHash==null?"":"; wallet DB unchanged"));
    }
}
