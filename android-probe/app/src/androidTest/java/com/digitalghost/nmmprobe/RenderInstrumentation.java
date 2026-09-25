package com.digitalghost.nmmprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Device-side regression through the shipped WebView and its real buttons. */
public final class RenderInstrumentation extends Instrumentation {
    private String freshMode;
    private String fixtureId;
    private boolean importHeif;
    private boolean decodeSuite;
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); freshMode=args==null?null:args.getString("fresh");
        fixtureId=args==null?null:args.getString("fixture"); importHeif=args!=null&&"true".equals(args.getString("importHeif"));
        decodeSuite=args!=null&&"true".equals(args.getString("decodeSuite")); start();
    }
    private WebView findWeb(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                WebView result=findWeb(group.getChildAt(i)); if(result!=null)return result;
            }
        }
        return null;
    }
    private String evaluate(WebView web, String script) throws Exception {
        CountDownLatch latch=new CountDownLatch(1);
        AtomicReference<String> result=new AtomicReference<>();
        runOnMainSync(()->web.evaluateJavascript(script,value->{result.set(value);latch.countDown();}));
        if(!latch.await(90,TimeUnit.SECONDS))throw new Exception("WebView evaluation timed out");
        return result.get();
    }
    @Override public void onStart() {
        Bundle status=new Bundle();
        try {
            File root=new File(getTargetContext().getCacheDir(),"studio-results");
            File selected=null;
            File[] candidates=root.listFiles();
            if(candidates!=null)for(File file:candidates){
                if(fixtureId!=null&&!file.getName().equals(fixtureId))continue;
                boolean valid=true;
                for(String name:new String[]{"source","mask","normals","detail_normals","lineart","depth"})
                    valid &= new File(file,name+".png").isFile();
                if(valid&&(selected==null||file.lastModified()>selected.lastModified()))selected=file;
            }
            if(selected==null)throw new Exception("Run one real photo analysis first; no fixture cache exists");
            Intent intent=new Intent(getTargetContext(),StudioActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity=startActivitySync(intent);
            AtomicReference<WebView> holder=new AtomicReference<>();
            runOnMainSync(()->holder.set(findWeb(activity.findViewById(android.R.id.content))));
            WebView web=holder.get();
            long deadline=System.currentTimeMillis()+60000;
            while(!"true".equals(evaluate(web,"typeof state !== 'undefined' && typeof render === 'function' && !!document.querySelector('#reflections')"))){
                if(System.currentTimeMillis()>deadline)throw new Exception("Editor startup timeout");
                Thread.sleep(250);
            }
            if(decodeSuite) {
                String casesText;
                try(java.io.InputStream input=getContext().getAssets().open("import-cases.json")){casesText=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
                org.json.JSONArray cases=new org.json.JSONArray(casesText), results=new org.json.JSONArray();
                boolean passed=true;
                for(int i=0;i<cases.length();i++) {
                    JSONObject test=cases.getJSONObject(i);byte[] bytes;
                    try(java.io.InputStream input=getContext().getAssets().open(test.getString("name"))){bytes=input.readAllBytes();}
                    String encoded=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP);
                    evaluate(web,"window.__decodeResult=null;(async()=>{const f=new File([Uint8Array.from(atob("+JSONObject.quote(encoded)+"),c=>c.charCodeAt(0))],"+JSONObject.quote(test.getString("name"))+",{type:"+JSONObject.quote(test.getString("type"))+"});const detected=await isHeifFile(f);const prepared=await normalizeImportFile(f);const image=await previewImportFile(prepared);window.__decodeResult={detected,width:image.width,height:image.height,type:prepared.type};})().catch(e=>window.__decodeResult={error:String(e)})");
                    deadline=System.currentTimeMillis()+60000;JSONObject result=null;
                    while(System.currentTimeMillis()<deadline){
                        Object decoded=new JSONTokener(evaluate(web,"JSON.stringify(window.__decodeResult||null)")).nextValue();
                        if(decoded instanceof String&&!decoded.equals("null")){result=new JSONObject((String)decoded);break;}
                        Thread.sleep(200);
                    }
                    if(result==null)throw new Exception("Decoder timed out");
                    boolean ok=test.optBoolean("error")?result.has("error"):result.optBoolean("detected")&&result.optInt("width")==test.getInt("width")&&result.optInt("height")==test.getInt("height")&&"image/png".equals(result.optString("type"));
                    passed &= ok;result.put("name",test.getString("name"));result.put("passed",ok);results.put(result);
                }
                JSONObject report=new JSONObject().put("passed",passed).put("cases",results);
                Files.write(new File(getTargetContext().getCacheDir(),"heif-decode-suite.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
                status.putString("decode_report",report.toString());finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,status);return;
            }
            JSONObject importReport=null;
            if(importHeif) {
                byte[] bytes;
                try(java.io.InputStream input=getContext().getAssets().open("miniature.HEIF")){bytes=input.readAllBytes();}
                String encoded=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP);
                Bundle progress=new Bundle();progress.putString("stage","HEIF import + fresh photo analysis");sendStatus(1,progress);
                evaluate(web,"(async()=>{const bytes=Uint8Array.from(atob("+JSONObject.quote(encoded)+"),c=>c.charCodeAt(0));await importPhoto(new File([bytes],'miniature.HEIF',{type:'application/octet-stream'}));window.__heifReport={passed:aiNormalReady&&aiLineartReady&&currentUploadFile?.type==='image/png',id:currentAnalysisId,width:canvas.width,height:canvas.height,uploadType:currentUploadFile?.type,status:document.querySelector('#aiStatus').textContent};})().catch(e=>window.__heifReport={error:String(e)})");
                deadline=System.currentTimeMillis()+900000;
                while(System.currentTimeMillis()<deadline){
                    Object decoded=new JSONTokener(evaluate(web,"JSON.stringify(window.__heifReport||null)")).nextValue();
                    if(decoded instanceof String&&!decoded.equals("null")){importReport=new JSONObject((String)decoded);break;}
                    Thread.sleep(1000);
                }
                if(importReport==null||!importReport.optBoolean("passed"))throw new Exception("HEIF import failed: "+importReport);
                selected=new File(root,importReport.getString("id"));
            }
            String script;
            try(java.io.InputStream input=getContext().getAssets().open("android-render-regression.js")){
                script=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            }
            String base="https://appassets.androidplatform.net/generated/"+selected.getName()+"/";
            if("true".equals(freshMode)||"refine".equals(freshMode)) {
                Bundle progress=new Bundle();progress.putString("stage","Running fresh native analysis: "+freshMode);sendStatus(1,progress);
                if("refine".equals(freshMode)) {
                    evaluate(web,"invokeAndroid('refine',"+JSONObject.quote(selected.getName())+",'[0.15,0.35,0.85,0.65]').then(r=>window.__freshResult=r).catch(e=>window.__freshResult={error:String(e)})");
                } else {
                    evaluate(web,"(async()=>{const img=await loadImage("+JSONObject.quote(base+"source.png")+");const c=document.createElement('canvas');c.width=img.width;c.height=img.height;c.getContext('2d').drawImage(img,0,0);window.__freshResult=await invokeAndroid('analyze',c.toDataURL('image/png'),'geometry-regression.png','[]','miniature figure');})().catch(e=>window.__freshResult={error:String(e)})");
                }
                deadline=System.currentTimeMillis()+900000;
                JSONObject analyzed=null;
                while(System.currentTimeMillis()<deadline) {
                    Object decoded=new JSONTokener(evaluate(web,"JSON.stringify(window.__freshResult||null)")).nextValue();
                    if(decoded instanceof String&&!decoded.equals("null")){analyzed=new JSONObject((String)decoded);break;}
                    Thread.sleep(1000);
                }
                if(analyzed==null||analyzed.has("error"))throw new Exception("Fresh analysis failed: "+analyzed);
                selected=new File(root,analyzed.getString("id"));
                if(!new File(selected,"geometry-v2.bin").isFile())throw new Exception("Fresh geometry cache missing");
                base="https://appassets.androidplatform.net/generated/"+selected.getName()+"/";
            }
            evaluate(web,"window.__renderFixture="+JSONObject.quote(base)+";"+script);
            deadline=System.currentTimeMillis()+180000;
            JSONObject report=null;
            while(System.currentTimeMillis()<deadline){
                String raw=evaluate(web,"JSON.stringify(window.__renderRegression || null)");
                Object decoded=new JSONTokener(raw).nextValue();
                if(decoded instanceof String&&!"null".equals(decoded)) {report=new JSONObject((String)decoded);break;}
                Thread.sleep(300);
            }
            if(report==null)throw new Exception("Render tests timed out");
            report.put("fixture",selected.getName());
            if(importReport!=null)report.put("heifImport",importReport);
            report.put("analysisMode",freshMode==null?"cached":freshMode);
            // Let the compositor present the final tested frame before capturing it.
            evaluate(web,"new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))");
            Thread.sleep(500);
            android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
            if(screenshot==null)throw new Exception("Device screenshot unavailable");
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(new File(getTargetContext().getCacheDir(),"render-regression.png"))){
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
            } finally { screenshot.recycle(); }
            Files.write(new File(getTargetContext().getCacheDir(),"render-regression.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            status.putString("render_report",report.toString());
            finish(report.optBoolean("passed",false)?Activity.RESULT_OK:Activity.RESULT_CANCELED,status);
        } catch(Throwable error){status.putString("error",error.toString());finish(Activity.RESULT_CANCELED,status);}
    }
}
