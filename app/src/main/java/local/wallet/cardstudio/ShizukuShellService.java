package local.wallet.cardstudio;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Loaded by Shizuku, not an Android Service or normal application process. */
public final class ShizukuShellService extends IWalletShell.Stub {
    private Process active;
    private boolean shuttingDown;
    public ShizukuShellService() { }
    public ShizukuShellService(Context ignored) { }
    private static String error(int code,String text){return "WCS1:ERR:"+code+"\n"+text;}

    @Override public synchronized String execute(String script) {
        if(shuttingDown)return error(3,"Shizuku 服务正在退出");
        if(active!=null&&active.isAlive())return error(3,"上一项 Shizuku 操作仍在运行，请稍后刷新");
        if(script==null||script.getBytes(StandardCharsets.UTF_8).length>262144)return error(1,"命令为空或过长");
        final Process process;
        try { process=new ProcessBuilder("/system/bin/sh","-c","set -eu\n"+script).redirectErrorStream(true).start(); }
        catch(IOException e){return error(1,"Shizuku 无法启动命令："+e.getMessage());}
        active=process;
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{
            try(InputStream in=process.getInputStream()){
                byte[] bytes=new byte[4096];int n;
                while((n=in.read(bytes))!=-1)synchronized(output){int keep=Math.min(n,131072-output.size());if(keep>0)output.write(bytes,0,keep);}
            }catch(IOException ignored){ }
        },"shizuku-output");
        reader.start();
        try {
            // Leave the whole shell alive on timeout, so its transaction and EXIT
            // recovery can finish. Later requests cannot overlap the active shell.
            if(!process.waitFor(90,TimeUnit.SECONDS))return error(2,"Shizuku 操作超时，后台事务与备份已保留，请稍后刷新");
            reader.join(3000);
        }catch(InterruptedException e){Thread.currentThread().interrupt();return error(2,"等待已取消，Shizuku 后台事务与备份已保留");}
        String text; synchronized(output){text=new String(output.toByteArray(),StandardCharsets.UTF_8);}
        if(process.exitValue()!=0)return error(1,"Shizuku 操作未完成："+(text.trim().isEmpty()?"请检查当前身份的文件访问权限":text.substring(0,Math.min(1400,text.length()))));
        return "WCS1:OK\n"+text;
    }
    @Override public synchronized void destroy() {
        shuttingDown=true;
        if(active==null||!active.isAlive()){System.exit(0);return;}
        final Process finishing=active;
        new Thread(()->{try{finishing.waitFor();}catch(InterruptedException e){Thread.currentThread().interrupt();}System.exit(0);},"shizuku-finish").start();
    }
}
