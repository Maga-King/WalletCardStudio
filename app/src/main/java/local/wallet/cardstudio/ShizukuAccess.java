package local.wallet.cardstudio;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.IOException;
import rikka.shizuku.Shizuku;

final class ShizukuAccess {
    private static final Object LOCK=new Object();
    private static Context context;
    private static IWalletShell service;
    private static boolean binding, initialized;
    private static String bindError;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ServiceConnection CONNECTION=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            synchronized(LOCK){service=IWalletShell.Stub.asInterface(binder);binding=false;bindError=null;LOCK.notifyAll();}
        }
        @Override public void onServiceDisconnected(ComponentName name){reset("Shizuku 用户服务已断开");}
        @Override public void onBindingDied(ComponentName name){reset("Shizuku 用户服务已失效");}
        @Override public void onNullBinding(ComponentName name){reset("Shizuku 用户服务未提供接口");}
    };
    private static void reset(String error){synchronized(LOCK){service=null;binding=false;bindError=error;LOCK.notifyAll();}}
    static void init(Context value){
        synchronized(LOCK){
            context=value.getApplicationContext();
            if(initialized)return;
            initialized=true;
            Shizuku.addBinderDeadListener(()->reset("Shizuku 已停止，请重新启动并授权"));
        }
    }
    static boolean ready(){try{return Shizuku.pingBinder()&&!Shizuku.isPreV11();}catch(RuntimeException e){return false;}}
    static boolean hasPermission(){try{return ready()&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}catch(RuntimeException e){return false;}}
    static int uid(){try{return ready()?Shizuku.getUid():-1;}catch(RuntimeException e){return -1;}}
    static void requestPermission(int requestCode){
        if(!ready())throw new IllegalStateException("请先启动 Shizuku");
        Shizuku.requestPermission(requestCode);
    }
    private static IWalletShell bind() throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("请在后台线程运行 Shizuku 操作");
        if(!ready())throw new IOException("Shizuku 未启动或版本过旧");
        if(!hasPermission())throw new IOException("请先授予 Shizuku 使用权限");
        synchronized(LOCK){
            if(context==null)throw new IOException("Shizuku 尚未初始化");
            if(service!=null&&service.asBinder().isBinderAlive())return service;
            if(!binding){
                binding=true;bindError=null;
                MAIN.post(()->{
                    try {
                        Shizuku.UserServiceArgs args=new Shizuku.UserServiceArgs(new ComponentName(context,ShizukuShellService.class))
                            .tag("wallet-shell-v1").processNameSuffix("wallet_privileged").version(context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode).daemon(true);
                        Shizuku.bindUserService(args,CONNECTION);
                    }catch(Exception e){reset("无法绑定 Shizuku 用户服务："+e.getMessage());}
                });
            }
            long deadline=SystemClock.elapsedRealtime()+15000;
            while(binding&&service==null){
                long left=deadline-SystemClock.elapsedRealtime();if(left<=0)break;
                try{LOCK.wait(left);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("等待 Shizuku 服务已取消",e);}
            }
            if(service==null||!service.asBinder().isBinderAlive()){
                binding=false;
                throw new IOException(bindError==null?"绑定 Shizuku 用户服务超时，请检查 Shizuku 状态":bindError);
            }
            return service;
        }
    }
    static String execute(String script) throws IOException {
        IWalletShell target=bind();
        try{
            String reply=target.execute(script);
            if(reply!=null&&reply.startsWith("WCS1:OK\n"))return reply.substring(8);
            if(reply!=null&&reply.startsWith("WCS1:ERR:")){
                int split=reply.indexOf('\n');String message=split<0?"Shizuku 服务返回格式无效":reply.substring(split+1);
                if(reply.startsWith("WCS1:ERR:2\n"))throw new RootShell.TimeoutException(message);
                throw new IOException(message);
            }
            throw new IOException("Shizuku 服务协议不匹配，请重新启动 Shizuku");
        }
        catch(RemoteException e){reset("Shizuku 连接已断开");throw new IOException("Shizuku 连接已断开，事务备份已保留，请重新启动后刷新",e);}
        catch(SecurityException e){throw new IOException("Shizuku 授权已失效，请重新授权",e);}
    }
}
