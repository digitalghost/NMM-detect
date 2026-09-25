(async () => {
  const assert=(condition,message)=>{if(!condition)throw new Error(message);};
  const change=(id,value)=>{
    const input=document.getElementById(id);input.value=String(value);
    input.dispatchEvent(new Event('input',{bubbles:true}));
  };
  const capture=()=>new Uint8ClampedArray(ctx.getImageData(0,0,canvas.width,canvas.height).data);
  const difference=(a,b)=>{
    let changed=0,total=0;
    for(let p=0;p<a.length;p+=4){
      let delta=0;for(let c=0;c<3;c++)delta+=Math.abs(a[p+c]-b[p+c]);
      if(delta)changed++;total+=delta;
    }
    return {changedPixels:changed,meanChannelDifference:total/(a.length/4*3)};
  };
  sizeCanvases(128,128);
  photoCtx.fillStyle='#777777';photoCtx.fillRect(0,0,128,128);
  lineartCtx.fillStyle='white';lineartCtx.fillRect(0,0,128,128);
  maskCtx.fillStyle='white';maskCtx.fillRect(0,0,128,128);
  depthMapCtx.fillStyle='#888';depthMapCtx.fillRect(0,0,128,128);
  for(const [context,micro] of [[normalSourceCtx,false],[detailNormalSourceCtx,true]]){
    const data=context.createImageData(128,128);
    for(let y=0;y<128;y++)for(let x=0;x<128;x++){
      const p=(y*128+x)*4;
      const nx=micro?.48*Math.sin(x*1.3):.5*Math.sin(x*.22);
      const ny=micro?.42*Math.cos(y*1.1):.4*Math.cos(y*.18);
      const nz=Math.sqrt(1-nx*nx-ny*ny);
      data.data.set([(nx+1)*127.5,(ny+1)*127.5,(nz+1)*127.5,255],p);
    }
    context.putImageData(data,0,0);
  }
  if(Object.keys(fixtures).length){
    const images={};
    await Promise.all(Object.entries(fixtures).map(([name,url])=>new Promise((resolve,reject)=>{
      const img=new Image();img.onload=()=>{images[name]=img;resolve();};img.onerror=()=>reject(new Error(name));img.src=url;
    })));
    sizeCanvases(images.cutout.width,images.cutout.height);
    for(const [name,context] of [['cutout',photoCtx],['lineart',lineartCtx],['normals',normalSourceCtx],['detail_normals',detailNormalSourceCtx],['depth',depthMapCtx]]){
      context.drawImage(images[name],0,0,canvas.width,canvas.height);
    }
    drawMaskArtifact(images.mask,canvas.width,canvas.height);
  }
  state.hasImage=true;state.maskDirty=true;state.previewMode='lighting';
  state.artDirection='balanced';state.bounce=.38;state.reflections=3;
  aiNormalReady=true;aiLineartReady=true;detailNormalReady=true;
  rebuildPhotoDetail();rebuildAINormals(false);rebuildDetailNormals(false);
  const reports={filterSupported:'filter' in ctx,width:canvas.width,height:canvas.height};
  change('normalSmooth',0);const sharp=capture();
  change('normalSmooth',24);const smooth=capture();
  reports.smoothing=difference(sharp,smooth);
  assert(reports.smoothing.meanChannelDifference>1,'Smoothing has no visible effect');
  change('normalSmooth',0);
  assert(difference(sharp,capture()).changedPixels===0,'Smoothing accumulates on repeated changes');
  change('normalSmooth',4);
  change('detailScale',1);const fine=capture();
  change('detailScale',12);reports.detailScale=difference(fine,capture());
  assert(reports.detailScale.meanChannelDifference>.3,'Detail scale has no visible effect');
  change('detailScale',1);
  assert(difference(fine,capture()).changedPixels===0,'Detail blur accumulates');
  let previous=new Uint8ClampedArray(detailNormalData);
  for(let value=2;value<=12;value++){
    change('detailScale',value);
    assert(difference(previous,detailNormalData).changedPixels>0,'Detail scale step is ineffective: '+value);
    previous=new Uint8ClampedArray(detailNormalData);
  }
  change('detailScale',4);
  for(const direction of ['original','balanced','competition']){
    state.artDirection=direction;surfaceAnalysis=null;
    change('reflections',1);const one=capture();
    change('reflections',2);const two=capture();
    change('reflections',3);const three=capture();
    reports[direction]={second:difference(one,two),third:difference(two,three)};
    assert(reports[direction].second.meanChannelDifference>.3,'Second reflection invisible: '+direction);
    assert(reports[direction].third.meanChannelDifference>.3,'Third reflection invisible: '+direction);
    change('bounce',0);change('reflections',1);const zero=capture();
    change('reflections',3);
    assert(difference(zero,capture()).changedPixels===0,'Bounce=0 must disable environment reflections');
    change('bounce',38);
  }
  // A flat field must remain constant even at the maximum blur radius.
  const flat=document.createElement('canvas');flat.width=16;flat.height=16;
  const fc=flat.getContext('2d');fc.fillStyle='rgba(80,140,200,0.5)';fc.fillRect(0,0,16,16);
  const before=fc.getImageData(0,0,16,16).data;
  drawBlurredImage(flat,fc,24);
  const after=fc.getImageData(0,0,16,16).data;
  assert(difference(before,after).changedPixels===0,'Blur changes flat fields at borders');
  reports.result='PASS';
  return JSON.stringify(reports);
})()
