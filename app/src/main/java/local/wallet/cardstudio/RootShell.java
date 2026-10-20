package local.wallet.cardstudio;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootShell {
    private static volatile Context context;
    static void init(Context value){context=value.getApplicationContext();ShizukuAccess.init(context);}
    static boolean usingShizuku(){return context!=null&&"shizuku".equals(context.getSharedPreferences("access",0).getString("mode","root"));}
    static boolean shellMode(){return usingShizuku()&&ShizukuAccess.uid()!=0;}
    static void setMode(String mode){
        if(!"root".equals(mode)&&!"shizuku".equals(mode))throw new IllegalArgumentException("未知访问模式");
        if(context==null)throw new IllegalStateException("访问模式尚未初始化");
        context.getSharedPreferences("access",0).edit().putString("mode",mode).apply();
    }
    static String modeLabel(){if(!usingShizuku())return "Root";int uid=ShizukuAccess.uid();return uid==0?"Shizuku · Root":uid==2000?"Shizuku · Shell":"Shizuku · 未连接";}
    static final class TimeoutException extends IOException {
        TimeoutException(String message) { super(message); }
    }
    static String q(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    static synchronized String run(String script) throws IOException {
        if(usingShizuku())return ShizukuAccess.execute(script);
        // Android isolates other apps' data mounts even for an inherited root shell.
        // KernelSU and Magisk provide this flag to enter the global mount namespace.
        Process p = new ProcessBuilder("su", "--mount-master", "-c", "set -eu\n" + script).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) != -1) if (output.size() < 131072) output.write(b, 0, n);
            } catch (IOException ignored) { }
        }, "root-output");
        reader.start();
        try {
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                // Do not kill only the su wrapper: that can orphan a shell/rename
                // and skip its EXIT recovery. The transaction PID remains live
                // until the synchronous script and its children finish normally.
                throw new TimeoutException("Root 操作超时，事务备份已保留。请稍后刷新以检查并恢复；不要重复替换");
            }
            reader.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("等待已取消，后台事务及备份已保留，请稍后刷新", e);
        }
        String text = new String(output.toByteArray(),StandardCharsets.UTF_8);
        if (p.exitValue() != 0) throw new IOException("Root 操作未完成：" + (text.trim().isEmpty() ? "请检查 Root 授权" : text.substring(0, Math.min(text.length(), 1400))));
        return text;
    }
}
