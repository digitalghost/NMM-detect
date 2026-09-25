(async()=>{
  // The desktop WKWebView harness can replay the exact Android artifact set.
  const load=name=>typeof fixtures==='undefined'?loadImage(window.__renderFixture+name+'.png'):new Promise((resolve,reject)=>{
    const image=new Image();image.onload=()=>resolve(image);image.onerror=()=>reject(Error('Fixture failed: '+name));
    image.src=fixtures[name==='source'?'cutout':name];
  });
  const [source,mask,normals,detail,lineart,depth]=await Promise.all(['source','mask','normals','detail_normals','lineart','depth'].map(load));
  sizeCanvases(source.width,source.height);
  for(const [image,context] of [[source,photoCtx],[normals,normalSourceCtx],[detail,detailNormalSourceCtx],[lineart,lineartCtx],[depth,depthMapCtx]])context.drawImage(image,0,0,canvas.width,canvas.height);
  drawMaskArtifact(mask,canvas.width,canvas.height);
  aiNormalReady=true;aiLineartReady=true;detailNormalReady=true;
  state.maskDirty=true;state.hasImage=true;state.previewMode='lighting';
  state.artDirection='balanced';state.bounce=.38;state.reflections=3;
  rebuildPhotoDetail();rebuildAINormals(false);rebuildDetailNormals(false);syncInferenceControls();render();
  const capture=()=>new Uint8ClampedArray(ctx.getImageData(0,0,canvas.width,canvas.height).data);
  const diff=(a,b)=>{let pixels=0,sum=0;for(let p=0;p<a.length;p+=4){let d=0;for(let c=0;c<3;c++)d+=Math.abs(a[p+c]-b[p+c]);if(d)pixels++;sum+=d;}return {changedPixels:pixels,meanChannelDifference:sum/(a.length/4*3)};};
  const press=(selector)=>{const b=document.querySelector(selector);if(!b||b.disabled)throw Error('Control unavailable: '+selector);b.click();};
  const option=(id,value)=>press(`.android-value-options[data-control="${id}"] button[data-value="${value}"]`);
  const report={webView:navigator.userAgent,width:canvas.width,height:canvas.height,tests:{}};
  // Main / balanced / detail presets, not just direct state assignment.
  press('#lightingPresets [data-lighting="single"]');const single=capture();
  press('#lightingPresets [data-lighting="balanced"]');const balanced=capture();
  press('#lightingPresets [data-lighting="detail"]');const detailed=capture();
  report.tests.lightingMainToBalanced=diff(single,balanced);
  report.tests.lightingBalancedToDetail=diff(balanced,detailed);
  press('#lightingPresets [data-lighting="balanced"]');
  option('reflections',1);const one=capture();option('reflections',2);const two=capture();option('reflections',3);const three=capture();
  report.tests.reflection1to2=diff(one,two);report.tests.reflection2to3=diff(two,three);
  option('detailScale',2);const fine=capture();option('detailScale',4);const standard=capture();option('detailScale',8);const broad=capture();
  report.tests.detail2to4=diff(fine,standard);report.tests.detail4to8=diff(standard,broad);
  option('detailScale',2);report.detailRoundTrip=diff(fine,capture()).changedPixels===0;
  option('steps',3);const low=capture();option('steps',9);report.tests.toneSteps3to9=diff(low,capture());option('steps',7);
  option('bounce',0);option('reflections',1);const off=capture();option('reflections',3);report.bounceOff=diff(off,capture()).changedPixels===0;
  option('bounce',38);option('detailScale',4);
  report.state={lightPreset:state.lightPreset,reflections:state.reflections,detailScale:state.detailScale,steps:state.steps};
  report.passed=Object.values(report.tests).every(result=>result.changedPixels>100&&result.meanChannelDifference>.05)&&report.detailRoundTrip&&report.bounceOff;
  setAIStatus('渲染回归验证完成 · 当前为真机分析缓存照片','success');
  document.querySelector('#photoName').textContent='真机渲染验证';
  document.querySelector('.advanced-details').open=true;
  setLightingPanelCollapsed(false);
  window.__renderRegression=report;
  return JSON.stringify(report);
})().catch(error=>{window.__renderRegression={error:String(error)};return JSON.stringify(window.__renderRegression);})
