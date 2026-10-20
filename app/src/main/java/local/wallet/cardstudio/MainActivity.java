package local.wallet.cardstudio;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    static final int PICK=41,CROP=42,CATALOG=43,ARCHIVE=44,SHIZUKU_PERMISSION=45;
    final Shizuku.OnRequestPermissionResultListener shizukuPermission=(request,grant)->{
        if(request!=SHIZUKU_PERMISSION)return;
        runOnUiThread(()->{if(isDestroyed())return;if(grant==PackageManager.PERMISSION_GRANTED)switchAccess("shizuku");else toast("未获得 Shizuku 授权，可在设置中重试");});
    };
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final ThreadPoolExecutor thumbs=new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,new LinkedBlockingDeque<Runnable>(48),new ThreadPoolExecutor.DiscardOldestPolicy());
    final LruCache<String,Bitmap> bitmapCache=new LruCache<String,Bitmap>(12*1024*1024){@Override protected int sizeOf(String k,Bitmap b){return b.getAllocationByteCount();}};
    final ArrayList<WalletStore.Art> cloudEntries=new ArrayList<>();
    final ArrayList<WalletStore.Card> visibleCards=new ArrayList<>();
    final ArrayList<WalletStore.Art> walletImages=new ArrayList<>();
    WalletStore store;LinearLayout root,content,body;FrameLayout host;TextView status,count;Button mine,library;Button[] tabs;
    boolean busy=false,inLibrary=false;WalletStore.Card selected;
    int tab=0,filter=0,renderVersion=0,libraryFilter=0;String libraryQuery="",networkQuery="",networkSource="全部来源";
    ArtAdapter adapter;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new WalletStore(this);Shizuku.addRequestPermissionResultListener(shizukuPermission);
        if(state!=null){tab=state.getInt("tab",0);String id=state.getString("cardId");if(id!=null){selected=new WalletStore.Card();selected.id=id;}}
        build();
        // Import the supplied local package before scanning. No network is used at startup.
        job("正在准备离线图库，首次导入可能需要几分钟…",()->{
            CloudStore.bootstrap(this);
            List<WalletStore.Art> imported=CloudStore.entries(this);
            runOnUiThread(()->{if(!isDestroyed()){cloudEntries.clear();cloudEntries.addAll(imported);render();}});
            runOnUiThread(()->status.setText("正在读取已添加卡片；离线图库可在下方打开…"));
            try{store.scan();return store.notice();}catch(Exception e){return "卡片读取未完成："+message(e);}
        },note->{resolveSelected();if(!note.isEmpty())status.setText(note);});
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(selected!=null)out.putString("cardId",selected.id);out.putInt("tab",tab);}
    void build(){
        root=Ui.column(this);root.setBackgroundColor(Ui.BG);setContentView(root);Ui.insets(this,root,Ui.BG,true);
        LinearLayout header=Ui.column(this);header.setPadding(dp(20),dp(18),dp(20),dp(12));
        header.addView(Ui.text(this,"WALLET / PERSONALIZE",10,Ui.GREEN,true));Ui.gap(header,10);
        header.addView(Ui.text(this,"让卡面，成为你的。",25,Ui.INK,true));Ui.gap(header,10);
        status=Ui.text(this,"离线图库 · 支持 Root / Shizuku",12,Ui.MUTED,false);header.addView(status);root.addView(header);
        LinearLayout nav=Ui.row(this);nav.setPadding(dp(12),0,dp(12),dp(8));String[] labels={"我的卡片","卡面库","网络库","设置"};tabs=new Button[4];
        for(int i=0;i<4;i++){final int index=i;Button b=Ui.button(this,labels[i],tab==i);b.setTextSize(12);b.setPadding(0,0,0,0);tabs[i]=b;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(43),1);if(i>0)lp.leftMargin=dp(5);nav.addView(b,lp);b.setOnClickListener(v->{tab=index;inLibrary=tab==1||tab==2;render();});}
        mine=tabs[0];library=tabs[1];root.addView(nav);host=new FrameLayout(this);root.addView(host,new LinearLayout.LayoutParams(-1,0,1));render();
    }
    int dp(float n){return Ui.dp(this,n);}
    interface Job<T>{T call() throws Exception;}
    interface Done<T>{void accept(T value) throws Exception;}
    String message(Throwable e){return e.getMessage()==null?e.toString():e.getMessage();}
    <T> void job(String message,Job<T> action,Done<T> done){
        if(busy){toast("正在处理，请稍候");return;}busy=true;status.setText(message);render();
        worker.execute(()->{try{
            T value=action.call();List<WalletStore.Art> snapshot=CloudStore.entries(this);List<WalletStore.Card> cards=new ArrayList<>(store.cards);List<WalletStore.Art> images=new ArrayList<>(store.images.values());
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;visibleCards.clear();visibleCards.addAll(cards);walletImages.clear();walletImages.addAll(images);cloudEntries.clear();cloudEntries.addAll(snapshot);status.setText("离线图库 · 浏览、搜索和裁切均无需联网");try{done.accept(value);}catch(Exception e){error(e);}render();});
        }catch(Exception|OutOfMemoryError e){
            List<WalletStore.Art> fallback;try{fallback=CloudStore.entries(this);}catch(Exception ignored){fallback=Collections.emptyList();}final List<WalletStore.Art> data=fallback;
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;cloudEntries.clear();cloudEntries.addAll(data);status.setText("操作未完成 · 仍可浏览离线图库");error(e);render();});
        }});
    }
    void resolveSelected(){if(selected!=null){String id=selected.id;selected=null;for(WalletStore.Card c:visibleCards)if(c.id.equals(id))selected=c;}}
    void refresh(){job("正在通过 "+RootShell.modeLabel()+" 读取钱包…",()->{store.scan();return store.notice();},note->{resolveSelected();if(!note.isEmpty())status.setText(note);});}
    void render(){
        if(host==null)return;renderVersion++;host.removeAllViews();adapter=null;
        for(int i=0;i<tabs.length;i++){tabs[i].setBackground(Ui.bg(this,tab==i?Ui.INK:0xffedf1ee,14));tabs[i].setTextColor(tab==i?-1:Ui.INK);}
        if(tab==1||tab==2){renderLibrary();return;}
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=Ui.column(this);content.setPadding(dp(20),dp(8),dp(20),dp(28));scroll.addView(content);host.addView(scroll);
        if(busy){ProgressBar p=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);p.setIndeterminate(true);content.addView(p,new LinearLayout.LayoutParams(-1,dp(3)));Ui.gap(content,12);}
        if(tab==0)renderCards();else renderSettings();
    }
    void section(String title,String subtitle){content.addView(Ui.text(this,title,20,Ui.INK,true));Ui.gap(content,9);content.addView(Ui.text(this,subtitle,12,Ui.MUTED,false));Ui.gap(content,16);}
    void renderCards(){
        LinearLayout row=Ui.row(this);TextView heading=Ui.text(this,store.cacheOnly?"钱包缓存 · Shizuku":"已添加的卡片",19,Ui.INK,true);row.addView(heading,new LinearLayout.LayoutParams(0,-2,1));Button refresh=Ui.button(this,"刷新",false);refresh.setEnabled(!busy);refresh.setOnClickListener(v->refresh());row.addView(refresh,new LinearLayout.LayoutParams(dp(70),dp(39)));content.addView(row);Ui.gap(content,14);
        if(store.cacheOnly){content.addView(Ui.text(this,"普通 Shizuku 无法读取钱包私有数据库。这里沿用此前识别的卡名；未识别的图片按缓存显示。",12,Ui.MUTED,false));Ui.gap(content,12);}
        LinearLayout chips=Ui.row(this);String[] labels={"全部","银行卡","公交卡"};
        for(int i=0;i<3;i++){final int index=i;Button b=Ui.button(this,labels[i],filter==i);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(35),1);if(i>0)lp.leftMargin=dp(8);chips.addView(b,lp);b.setOnClickListener(v->{filter=index;render();});}content.addView(chips);Ui.gap(content,18);
        int n=0;for(WalletStore.Card c:visibleCards){if(filter==1&&!"1".equals(c.type)||filter==2&&!"5".equals(c.type))continue;n++;cardView(c);}
        if(n==0){LinearLayout empty=panel();Ui.gap(empty,20);empty.addView(Ui.text(this,busy?"正在识别卡片…":"还没有可显示的卡片",20,Ui.INK,true));Ui.gap(empty,12);empty.addView(Ui.text(this,"在钱包中打开银行卡或公交卡，让卡面加载完成，然后点击刷新。",14,Ui.MUTED,false));Ui.gap(empty,16);Button b=Ui.button(this,"读取钱包",true);b.setEnabled(!busy);b.setOnClickListener(v->refresh());empty.addView(b,new LinearLayout.LayoutParams(-1,dp(46)));content.addView(empty);}
        Ui.gap(content,8);TextView note=Ui.text(this,"替换前自动备份原图。钱包更新、重新下载或清理缓存后，可再次应用喜欢的卡面。",12,Ui.MUTED,false);note.setLineSpacing(dp(4),1);content.addView(note);
    }
    LinearLayout panel(){LinearLayout p=Ui.column(this);p.setPadding(dp(16),dp(16),dp(16),dp(16));p.setBackground(Ui.bg(this,-1,22));return p;}
    void cardView(WalletStore.Card c){
        LinearLayout p=panel();LinearLayout heading=Ui.row(this);TextView name=Ui.text(this,c.name,18,Ui.INK,true);heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=Ui.text(this,store.customized(c)?"已自定义":"1".equals(c.type)?"银行卡":"5".equals(c.type)?"公交卡":"缓存",10,Ui.GREEN,true);badge.setPadding(dp(9),dp(5),dp(9),dp(5));badge.setBackground(Ui.bg(this,0xffeaf3ed,8));heading.addView(badge);p.addView(heading);Ui.gap(p,16);
        WalletStore.Art a=c.primary();if(a!=null){p.addView(preview(a),new LinearLayout.LayoutParams(-1,cardHeight(a,32)));Ui.gap(p,12);p.addView(Ui.text(this,a.width+" × "+a.height+" · 保持原卡比例",11,Ui.MUTED,false));}
        else{TextView missing=Ui.text(this,"卡面尚未缓存\n请先在钱包中打开这张卡，再刷新",13,Ui.MUTED,false);missing.setGravity(Gravity.CENTER);missing.setBackground(Ui.bg(this,Ui.BG,14));p.addView(missing,new LinearLayout.LayoutParams(-1,dp(150)));}
        Ui.gap(p,16);LinearLayout actions=Ui.row(this);Button edit=Ui.button(this,"更换卡面",true),restore=Ui.button(this,"恢复原图",false);edit.setEnabled(!busy&&a!=null);restore.setEnabled(!busy&&store.canRestore(c));
        actions.addView(edit,new LinearLayout.LayoutParams(0,dp(44),1));View gap=new View(this);actions.addView(gap,new LinearLayout.LayoutParams(dp(10),1));actions.addView(restore,new LinearLayout.LayoutParams(0,dp(44),1));p.addView(actions);
        edit.setOnClickListener(v->choose(c));restore.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("恢复「"+c.name+"」").setMessage("恢复为第一次替换前的卡面，并关闭钱包以刷新显示。"+sharedText(c)).setPositiveButton("恢复",(d,w)->job("正在恢复原图…",()->{store.restore(c);return null;},r->success("已恢复原图"))).setNegativeButton("取消",null).show());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(16);content.addView(p,lp);
    }
    int cardHeight(WalletStore.Art a,int inset){int width=getResources().getDisplayMetrics().widthPixels-dp(48+inset);return Math.max(dp(100),Math.min(dp(330),Math.round(width*(a.height>0?(float)a.height/a.width:.615f))));}
    ImageView preview(WalletStore.Art a){ImageView im=new ImageView(this);im.setBackground(Ui.bg(this,0xffe9eeea,14));im.setClipToOutline(true);im.setScaleType(ImageView.ScaleType.FIT_CENTER);bindImage(im,a);return im;}
    void bindImage(ImageView image,WalletStore.Art art){
        image.setImageDrawable(null);image.setContentDescription(art.name);
        File file=art.file;String token=(file==null?art.key:file.getAbsolutePath()+":"+file.lastModified()+":"+file.length());image.setTag(token);
        if(file==null||!file.isFile())return;
        Bitmap cached=bitmapCache.get(token);if(cached!=null){image.setImageBitmap(cached);return;}
        if(thumbs.isShutdown())return;
        thumbs.execute(()->{try{
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),o);
            if(o.outWidth<1||o.outHeight<1)return;o.inSampleSize=1;while(o.outWidth/o.inSampleSize>720||o.outHeight/o.inSampleSize>600)o.inSampleSize*=2;
            o.inJustDecodeBounds=false;o.inPreferredConfig=Bitmap.Config.RGB_565;Bitmap b=BitmapFactory.decodeFile(file.getAbsolutePath(),o);if(b==null)return;
            bitmapCache.put(token,b);runOnUiThread(()->{if(!isDestroyed()&&token.equals(image.getTag()))image.setImageBitmap(b);});
        }catch(Exception|OutOfMemoryError ignored){}});
    }
    void choose(WalletStore.Card c){selected=c;new AlertDialog.Builder(this).setTitle("为「"+c.name+"」选择卡面").setItems(new String[]{"从系统图库选择","从卡面库选择","从网络库选择","下载图片网址"},(d,i)->{if(i==0)pick();else if(i==1||i==2){tab=i;inLibrary=true;render();toast("点击卡面后选择「用于卡片」");}else downloadDialog();}).show();}
    void pick(){Intent i=new Intent(MediaStoreAction());i.setType("image/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivityForResult(i,PICK);}catch(ActivityNotFoundException e){Intent fallback=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(fallback,PICK);}}
    String MediaStoreAction(){return Build.VERSION.SDK_INT>=33?"android.provider.action.PICK_IMAGES":Intent.ACTION_OPEN_DOCUMENT;}
    void crop(Uri uri){if(selected==null||selected.primary()==null){toast("请先选择要替换的卡片");return;}WalletStore.Art a=selected.primary();Intent i=new Intent(this,CropActivity.class).setData(uri).putExtra("width",a.width).putExtra("height",a.height).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,CROP);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
        if(request==PICK&&data.getData()!=null)crop(data.getData());
        else if(request==CROP){String path=data.getStringExtra("crop");if(path!=null&&selected!=null)confirmReplace(new File(path));}
        else if((request==CATALOG||request==ARCHIVE)&&data.getData()!=null){Uri u=data.getData();job(request==ARCHIVE?"正在导入离线图片包，请保持应用打开…":"正在导入目录…",()->{try(InputStream in=getContentResolver().openInputStream(u)){if(in==null)throw new IOException("无法读取文件");return request==ARCHIVE?CloudStore.importArchive(this,in):CloudStore.importCatalog(this,in);}},n->toast("导入完成，新增 "+n+" 项"));}
    }
    String sharedText(WalletStore.Card c){return c.shared>1?"\n\n注意：有 "+c.shared+" 张卡共用同一图片缓存，会一起变化。":"";}
    void confirmReplace(File f){WalletStore.Card target=selected;
        new AlertDialog.Builder(this).setTitle("应用到「"+target.name+"」").setMessage("保存裁切后的图片并自动备份原图。应用时会关闭钱包。"+sharedText(target)).setPositiveButton("应用卡面",(d,w)->job("正在备份并替换卡面…",()->{store.replace(target,f);return null;},r->{tab=0;inLibrary=false;success("卡面已应用");})).setNegativeButton("取消",(d,w)->f.delete()).setOnCancelListener(d->f.delete()).show();
    }
    void success(String title){new AlertDialog.Builder(this).setTitle(title).setMessage("重新打开钱包查看效果。").setPositiveButton("打开钱包",(d,w)->openWallet()).setNegativeButton("完成",null).show();}
    void openWallet(){Intent i=getPackageManager().getLaunchIntentForPackage(WalletStore.PKG);if(i!=null)startActivity(i);else toast("未找到一加 / OPPO 钱包");}
    boolean networkArt(WalletStore.Art a){String page=safe(a.sourcePage).toLowerCase(Locale.ROOT);return page.contains("cards.no2.ac")||page.contains("github.com/no2ac/cardentify");}
    boolean communityArt(WalletStore.Art a){String s=(safe(a.source)+" "+safe(a.sourcePage)).toLowerCase(Locale.ROOT);return s.contains("社区")||s.contains("community")||s.contains("mipaycard")||s.contains("旧apk");}
    boolean xiaomiArt(WalletStore.Art a){String s=(safe(a.source)+" "+safe(a.sourcePage)).toLowerCase(Locale.ROOT);return !networkArt(a)&&(s.contains("xiaomi")||s.contains("小米")||s.contains("tsmapi.pay.xiaomi"));}
    String safe(String s){return s==null?"":s;}
    boolean localArt(WalletStore.Art a){return a.file!=null&&a.file.isFile()&&a.width>0&&a.height>0;}
    WalletStore.Art originalArt(WalletStore.Art a){
        File original=store.original(a);if(!original.isFile())return a;
        WalletStore.Art copy=new WalletStore.Art();copy.key=a.key+":original";copy.name=a.name+" · 原图";copy.url=a.url;copy.source="已添加卡片原图";copy.sourcePage=a.sourcePage;copy.width=a.width;copy.height=a.height;copy.mime=a.mime;copy.file=original;return copy;
    }
    List<WalletStore.Art> libraryEntries(){
        LinkedHashMap<String,WalletStore.Art> all=new LinkedHashMap<>();
        if(tab==2){for(WalletStore.Art a:cloudEntries)if(networkArt(a)&&(networkSource.equals("全部来源")||networkSource.equals(safe(a.source))))all.put(a.key,a);}
        else if(libraryFilter==1){for(WalletStore.Art a:walletImages)all.put(a.key,a);for(WalletStore.Art a:cloudEntries)if(!networkArt(a)&&localArt(a))all.put(a.key,a);}
        else if(libraryFilter==2){for(WalletStore.Art a:cloudEntries)if(communityArt(a)&&!networkArt(a))all.put(a.key,a);}
        else{
            for(WalletStore.Card c:visibleCards)for(WalletStore.Art a:c.targets){WalletStore.Art original=originalArt(a);all.put(original.key,original);}
            for(WalletStore.Art a:cloudEntries)if(xiaomiArt(a))all.put(a.key,a);
        }
        String query=(tab==2?networkQuery:libraryQuery).trim().toLowerCase(Locale.ROOT);ArrayList<WalletStore.Art> result=new ArrayList<>();
        for(WalletStore.Art a:all.values()){if(!query.isEmpty()&&!(safe(a.name)+" "+safe(a.source)+" "+safe(a.sourcePage)).toLowerCase(Locale.ROOT).contains(query))continue;result.add(a);}return result;
    }
    void renderLibrary(){
        LinearLayout page=Ui.column(this);page.setPadding(dp(20),dp(6),dp(20),0);host.addView(page);
        page.addView(Ui.text(this,tab==2?"网络库 · 离线原图":"卡面库",20,Ui.INK,true));Ui.gap(page,8);
        page.addView(Ui.text(this,tab==2?"Cardentify 全部来源与 Issue 投稿 · 手动同步在设置":"已添加卡片原图与小米公交卡面",12,Ui.MUTED,false));Ui.gap(page,10);
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(14);search.setHint("搜索银行、卡名或来源");search.setContentDescription("搜索银行、卡名或来源");search.setText(tab==2?networkQuery:libraryQuery);page.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
        if(tab==2){
            LinkedHashSet<String> sources=new LinkedHashSet<>();sources.add("全部来源");for(WalletStore.Art a:cloudEntries)if(networkArt(a)&&!safe(a.source).isEmpty())sources.add(a.source);
            ArrayList<String> labels=new ArrayList<>(sources);Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));int index=labels.indexOf(networkSource);if(index<0){networkSource="全部来源";index=0;}spinner.setSelection(index);page.addView(spinner,new LinearLayout.LayoutParams(-1,dp(44)));spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){networkSource=labels.get(pos);updateArtList();}});
        }else{
            LinearLayout filters=Ui.row(this);String[] labels={"卡片与小米","全部本地缓存","社区"};for(int i=0;i<labels.length;i++){final int index=i;Button b=Ui.button(this,labels[i],libraryFilter==i);b.setTextSize(11);b.setPadding(dp(3),0,dp(3),0);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(37),1);if(i>0)lp.leftMargin=dp(5);filters.addView(b,lp);b.setOnClickListener(v->{libraryQuery=search.getText().toString();libraryFilter=index;render();});}page.addView(filters);Ui.gap(page,6);
        }
        count=Ui.text(this,"",12,Ui.MUTED,false);page.addView(count);Ui.gap(page,8);
        if(selected!=null)page.addView(Ui.text(this,"应用目标："+selected.name+" · 选择后先裁切",11,Ui.GREEN,true));
        if(busy){ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);page.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));}
        ListView list=new ListView(this);list.setDivider(null);list.setClipToPadding(false);list.setPadding(0,dp(8),0,dp(16));
        // Use the native draggable fast-scroll thumb rather than a passive scroll indicator.
        list.setVerticalScrollBarEnabled(false);list.setFastScrollEnabled(true);list.setFastScrollAlwaysVisible(true);list.setVerticalScrollbarPosition(View.SCROLLBAR_POSITION_RIGHT);
        adapter=new ArtAdapter();list.setAdapter(adapter);page.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((p,v,pos,id)->{WalletStore.Art a=adapter.items.get(pos);if(!localArt(a)){toast("本地原图缺失，请在设置中手动同步或导入离线包");return;}artDialog(a);});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int c){if(tab==2)networkQuery=s.toString();else libraryQuery=s.toString();updateArtList();}public void afterTextChanged(Editable e){}});updateArtList();
    }
    void updateArtList(){if(adapter==null)return;adapter.items.clear();adapter.items.addAll(libraryEntries());adapter.notifyDataSetChanged();int local=0;for(WalletStore.Art a:adapter.items)if(localArt(a))local++;count.setText(adapter.items.size()+" 张卡面 · "+local+" 张离线原图");}
    class ArtHolder {LinearLayout panel;ImageView image;TextView name,origin;}
    class ArtAdapter extends BaseAdapter {
        final ArrayList<WalletStore.Art> items=new ArrayList<>();
        public int getCount(){return items.size();}public Object getItem(int p){return items.get(p);}public long getItemId(int p){return p;}
        public View getView(int position,View recycled,android.view.ViewGroup parent){
            ArtHolder h;
            if(recycled==null){h=new ArtHolder();LinearLayout wrapper=Ui.column(MainActivity.this);wrapper.setPadding(0,0,0,dp(12));h.panel=panel();h.image=new ImageView(MainActivity.this);h.image.setScaleType(ImageView.ScaleType.FIT_CENTER);h.image.setBackground(Ui.bg(MainActivity.this,0xffe9eeea,12));h.image.setClipToOutline(true);h.panel.addView(h.image,new LinearLayout.LayoutParams(-1,dp(190)));Ui.gap(h.panel,10);h.name=Ui.text(MainActivity.this,"",14,Ui.INK,true);h.panel.addView(h.name);Ui.gap(h.panel,6);h.origin=Ui.text(MainActivity.this,"",11,Ui.MUTED,false);h.panel.addView(h.origin);wrapper.addView(h.panel);wrapper.setTag(h);recycled=wrapper;}else h=(ArtHolder)recycled.getTag();
            WalletStore.Art a=items.get(position);h.name.setText(a.name);String origin=safe(a.source);if(origin.isEmpty())origin="本地卡面";h.origin.setText(origin+(localArt(a)?" · "+a.width+" × "+a.height:" · 本地原图缺失"));bindImage(h.image,a);return recycled;
        }
    }
    void settingAction(String title,Runnable action){Button b=Ui.button(this,title,false);b.setEnabled(!busy);b.setOnClickListener(v->action.run());content.addView(b,new LinearLayout.LayoutParams(-1,dp(47)));Ui.gap(content,10);}
    void renderSettings(){
        section("设置","平时浏览、搜索与裁切只读取本地图片。只有下方手动同步会联网；已完整保存的原图将跳过。");
        content.addView(Ui.text(this,"当前访问方式："+RootShell.modeLabel(),14,Ui.INK,true));Ui.gap(content,10);
        settingAction("切换访问方式 · Root / Shizuku",()->chooseAccessMode());
        content.addView(Ui.text(this,"Root 或以 Root 启动的 Shizuku 可识别完整卡名。普通 Shizuku 读取和替换缓存，首次使用时可能只能按图片选择。",12,Ui.MUTED,false));Ui.gap(content,18);
        settingAction("手动同步 Cardentify · 全部来源",()->sync(false));
        settingAction("仅同步 Mi Pay 来源",()->sync(true));
        settingAction("导入离线 ZIP 图片包",()->pickCatalog(ARCHIVE));
        settingAction("导入 JSON 目录",()->pickCatalog(CATALOG));
        settingAction("下载指定图片网址",()->downloadDialog());
        settingAction("重新读取钱包卡片",()->refresh());
        Ui.gap(content,8);content.addView(Ui.text(this,"原图在首次替换前自动备份；恢复与替换沿用钱包原图比例，所有来源都先进入裁切。",12,Ui.MUTED,false));
    }
    void chooseAccessMode(){new AlertDialog.Builder(this).setTitle("选择钱包访问方式").setSingleChoiceItems(new String[]{"Root · 完整识别卡片","Shizuku · 按服务权限读取"},RootShell.usingShizuku()?1:0,(dialog,which)->{dialog.dismiss();if(which==0){switchAccess("root");return;}if(!ShizukuAccess.ready()){new AlertDialog.Builder(this).setTitle("请先启动 Shizuku").setMessage("在 Shizuku 中启动服务，然后回到这里选择 Shizuku 并授权。").setPositiveButton("打开 Shizuku",(d,w)->openShizuku()).setNegativeButton("关闭",null).show();return;}if(ShizukuAccess.hasPermission())switchAccess("shizuku");else try{ShizukuAccess.requestPermission(SHIZUKU_PERMISSION);}catch(RuntimeException e){error(e);}}).setNegativeButton("取消",null).show();}
    void openShizuku(){Intent intent=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");if(intent!=null)startActivity(intent);else new AlertDialog.Builder(this).setMessage("尚未安装 Shizuku，请安装并启动后再使用此模式。").setPositiveButton("确定",null).show();}
    void switchAccess(String mode){if(busy){toast("正在处理，请稍后切换");return;}if(store.hasPendingTransaction()){new AlertDialog.Builder(this).setTitle("请先恢复未完成的替换").setMessage("使用当前访问方式点击刷新，完成事务恢复后再切换。").setPositiveButton("确定",null).show();return;}RootShell.setMode(mode);store=new WalletStore(this);selected=null;visibleCards.clear();walletImages.clear();refresh();}
    void pickCatalog(int request){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,request);}
    void sync(boolean miPay){job("正在手动同步并校验本地原图，请保持应用打开…",()->CloudStore.syncCardentifyOffline(this,miPay),r->new AlertDialog.Builder(this).setTitle("同步完成").setMessage("新增目录 "+r.added+" 项\n下载 "+r.downloaded+" 张\n跳过完整原图 "+r.skipped+" 张\n失败 "+r.failed+" 张").setPositiveButton("确定",null).show());}
    void artDialog(WalletStore.Art a){new AlertDialog.Builder(this).setTitle(a.name).setItems(new String[]{"用于卡片 · 按原卡比例裁切","保存到系统图库","查看 / 复制图片网址"},(d,n)->{
        if(n==0){if(selected!=null&&selected.primary()!=null)crop(Uri.fromFile(a.file));else selectTarget(a);}
        else if(n==1)job("正在保存到图库…",()->CloudStore.export(this,a),u->toast("已保存到图库 / 钱包卡面"));
        else{if(a.url.isEmpty()){toast("该缓存图片尚未匹配到网址");return;}TextView t=Ui.text(this,a.url,13,Ui.INK,false);t.setTextIsSelectable(true);t.setPadding(dp(24),dp(16),dp(24),dp(16));new AlertDialog.Builder(this).setTitle("图片直链").setView(t).setPositiveButton("复制",(x,y)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("卡面网址",a.url));toast("已复制");}).setNegativeButton("关闭",null).show();}
    }).show();}
    void selectTarget(WalletStore.Art a){ArrayList<WalletStore.Card> list=new ArrayList<>();for(WalletStore.Card c:visibleCards)if(c.primary()!=null)list.add(c);if(list.isEmpty()){toast("先读取一加钱包中的银行卡或公交卡");return;}new AlertDialog.Builder(this).setTitle("应用到哪张卡").setItems(list.stream().map(c->c.name).toArray(String[]::new),(d,w)->{selected=list.get(w);crop(Uri.fromFile(a.file));}).show();}
    void downloadDialog(){LinearLayout box=Ui.column(this);box.setPadding(dp(24),dp(8),dp(24),dp(8));EditText name=new EditText(this),url=new EditText(this);name.setSingleLine();name.setHint("卡面名称（可选）");url.setHint("https://… 图片直链");url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);box.addView(name);box.addView(url);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("下载卡面图片").setView(box).setPositiveButton("下载",null).setNegativeButton("取消",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{String s=url.getText().toString().trim();try{CloudStore.checkUrl(s);}catch(Exception e){url.setError("请输入 HTTPS 图片直链");return;}String title=name.getText().toString().trim();d.dismiss();download(title.isEmpty()?"网址卡面":title,s,true);}));d.show();}
    void download(String name,String url,boolean offerUse){job("正在下载卡面…",()->CloudStore.download(this,name,url),a->{tab=1;inLibrary=true;if(offerUse)artDialog(a);else toast("已下载卡面");});}
    void error(Throwable e){new AlertDialog.Builder(this).setTitle("操作未完成").setMessage(message(e)).setPositiveButton("确定",null).show();}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){super.onDestroy();Shizuku.removeRequestPermissionResultListener(shizukuPermission);worker.shutdown();thumbs.shutdownNow();bitmapCache.evictAll();}
}
