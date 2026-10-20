package local.wallet.cardstudio;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.UUID;

public class CropActivity extends Activity {
    CropView crop;
    final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=Ui.column(this);root.setBackgroundColor(0xff101612);setContentView(root);Ui.insets(this,root,0xff101612,false);
        LinearLayout bar=Ui.row(this);bar.setPadding(Ui.dp(this,16),Ui.dp(this,12),Ui.dp(this,16),Ui.dp(this,12));
        Button cancel=Ui.button(this,"取消",false);cancel.setOnClickListener(v->finish());bar.addView(cancel,new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,44)));
        TextView title=Ui.text(this,"调整卡面",18,0xfff6f8f5,true);title.setGravity(Gravity.CENTER);bar.addView(title,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));
        Button save=Ui.button(this,"完成",true);save.setEnabled(false);bar.addView(save,new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,44)));root.addView(bar);
        int w=getIntent().getIntExtra("width",936),h=getIntent().getIntExtra("height",576);
        crop=new CropView(this,w,h);root.addView(crop,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=Ui.column(this);footer.setPadding(Ui.dp(this,24),Ui.dp(this,18),Ui.dp(this,24),Ui.dp(this,28));
        TextView hint=Ui.text(this,"拖动定位 · 双指缩放",15,0xfff0f4f0,true);hint.setGravity(Gravity.CENTER);footer.addView(hint);Ui.gap(footer,10);
        TextView size=Ui.text(this,"按原卡比例裁切  "+w+" × "+h,13,0xffa6b9ac,false);size.setGravity(Gravity.CENTER);footer.addView(size);Ui.gap(footer,18);
        LinearLayout actions=Ui.row(this);Button rotate=Ui.button(this,"旋转 90°",false),reset=Ui.button(this,"重置",false);
        actions.addView(rotate,new LinearLayout.LayoutParams(0,Ui.dp(this,46),1));View gap=new View(this);actions.addView(gap,new LinearLayout.LayoutParams(Ui.dp(this,12),1));actions.addView(reset,new LinearLayout.LayoutParams(0,Ui.dp(this,46),1));footer.addView(actions);root.addView(footer);
        rotate.setOnClickListener(v->crop.rotate());reset.setOnClickListener(v->crop.reset());
        Uri uri=getIntent().getData();
        worker.execute(()->{
            try{
                ImageDecoder.Source source=ImageDecoder.createSource(getContentResolver(),uri);
                Bitmap bitmap=ImageDecoder.decodeBitmap(source,(decoder,info,s)->{
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);int max=Math.max(info.getSize().getWidth(),info.getSize().getHeight());
                    if(max>3072)decoder.setTargetSampleSize((int)Math.ceil(max/3072.0));
                });
                runOnUiThread(()->{if(isFinishing()){bitmap.recycle();return;}crop.setBitmap(bitmap);save.setEnabled(true);});
            }catch(Exception|OutOfMemoryError e){runOnUiThread(()->new AlertDialog.Builder(this).setMessage("无法打开这张图片："+e.getMessage()).setPositiveButton("返回",(d,x)->finish()).setOnCancelListener(d->finish()).show());}
        });
        save.setOnClickListener(v->{
            save.setEnabled(false);
            try{
                Bitmap out=crop.result();
                worker.execute(()->{
                    try{
                        File f=new File(getCacheDir(),"crop-"+UUID.randomUUID()+".png");
                        try(FileOutputStream os=new FileOutputStream(f)){if(!out.compress(Bitmap.CompressFormat.PNG,100,os))throw new IOException("图片保存失败");}
                        runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("crop",f.getAbsolutePath()));finish();});
                    }catch(Exception e){runOnUiThread(()->{save.setEnabled(true);new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("确定",null).show();});}finally{out.recycle();}
                });
            }catch(Exception|OutOfMemoryError e){save.setEnabled(true);new AlertDialog.Builder(this).setMessage("图片处理失败："+e.getMessage()).setPositiveButton("确定",null).show();}
        });
    }
    @Override protected void onDestroy(){super.onDestroy();worker.shutdown();if(crop!=null)crop.dispose();}

    static final class CropView extends View {
        Bitmap bitmap; final int outputW,outputH;final RectF frame=new RectF();
        float scale,minScale,left,top,lastX,lastY;final Paint p=new Paint(3);boolean gesture=false;
        final ScaleGestureDetector zoom;
        CropView(Context c,int w,int h){
            super(c);outputW=Math.min(4096,Math.max(1,w));outputH=Math.min(4096,Math.max(1,h));
            zoom=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector d){
                    if(bitmap==null)return false;float next=Math.max(minScale,Math.min(minScale*8,scale*d.getScaleFactor())),f=next/scale;
                    left=d.getFocusX()-(d.getFocusX()-left)*f;top=d.getFocusY()-(d.getFocusY()-top)*f;scale=next;clamp();invalidate();return true;
                }
            });setContentDescription("固定卡面比例的裁切区域，单指拖动，双指缩放");
        }
        void setBitmap(Bitmap b){bitmap=b;reset();}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            float width=w-Ui.dp(getContext(),40),height=width*outputH/outputW;
            if(height>h-Ui.dp(getContext(),40)){height=h-Ui.dp(getContext(),40);width=height*outputW/outputH;}
            frame.set((w-width)/2,(h-height)/2,(w+width)/2,(h+height)/2);reset();
        }
        void reset(){if(bitmap==null||frame.width()<=0)return;minScale=Math.max(frame.width()/bitmap.getWidth(),frame.height()/bitmap.getHeight());scale=minScale;left=frame.centerX()-bitmap.getWidth()*scale/2;top=frame.centerY()-bitmap.getHeight()*scale/2;clamp();invalidate();}
        void clamp(){if(bitmap==null)return;left=Math.max(frame.right-bitmap.getWidth()*scale,Math.min(frame.left,left));top=Math.max(frame.bottom-bitmap.getHeight()*scale,Math.min(frame.top,top));}
        void rotate(){if(bitmap==null)return;Matrix m=new Matrix();m.postRotate(90);Bitmap b=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);if(b!=bitmap)bitmap.recycle();bitmap=b;reset();}
        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            zoom.onTouchEvent(e);if(bitmap==null)return true;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();gesture=true;}
            else if(e.getActionMasked()==MotionEvent.ACTION_POINTER_UP){int i=e.getActionIndex()==0?1:0;lastX=e.getX(i);lastY=e.getY(i);}
            else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){if(!zoom.isInProgress()&&e.getPointerCount()==1&&gesture){left+=e.getX()-lastX;top+=e.getY()-lastY;clamp();invalidate();}lastX=e.getX();lastY=e.getY();}
            else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){gesture=false;performClick();}return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
        @Override protected void onDraw(Canvas c){
            c.drawColor(0xff101612);if(bitmap==null)return;
            p.setColor(-1);p.setStyle(Paint.Style.FILL);c.drawBitmap(bitmap,null,new RectF(left,top,left+bitmap.getWidth()*scale,top+bitmap.getHeight()*scale),p);
            p.setColor(0xb8101612);c.drawRect(0,0,getWidth(),frame.top,p);c.drawRect(0,frame.bottom,getWidth(),getHeight(),p);c.drawRect(0,frame.top,frame.left,frame.bottom,p);c.drawRect(frame.right,frame.top,getWidth(),frame.bottom,p);
            p.setColor(0xffd3e9da);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Ui.dp(getContext(),1));c.drawRoundRect(frame,Ui.dp(getContext(),14),Ui.dp(getContext(),14),p);
            p.setColor(0x65ffffff);for(int i=1;i<3;i++){float x=frame.left+frame.width()*i/3,y=frame.top+frame.height()*i/3;c.drawLine(x,frame.top,x,frame.bottom,p);c.drawLine(frame.left,y,frame.right,y,p);}p.setStyle(Paint.Style.FILL);
        }
        Bitmap result(){if(bitmap==null)throw new IllegalStateException("图片尚未加载");Bitmap out=Bitmap.createBitmap(outputW,outputH,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);c.drawColor(Color.WHITE);
            float factor=outputW/frame.width();c.translate((left-frame.left)*factor,(top-frame.top)*factor);c.scale(scale*factor,scale*factor);c.drawBitmap(bitmap,0,0,new Paint(3));return out;}
        void dispose(){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();bitmap=null;}
    }
}
