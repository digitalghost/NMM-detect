// Keep every local tab on one origin so browser-wide inference locks also work
// when the app was opened once with 127.0.0.1 and once with localhost.
if (!window.AndroidNMM && window.location.hostname === '127.0.0.1') {
  const canonicalUrl = new URL(window.location.href);
  canonicalUrl.hostname = 'localhost';
  window.location.replace(canonicalUrl);
}

const isAndroidApp = Boolean(window.AndroidNMM);
const androidPending = new Map();
let androidRequestSequence = 0;

if (isAndroidApp) {
  document.documentElement.classList.add('android-app');
  const mobileStyles = document.createElement('link');
  mobileStyles.rel = 'stylesheet';
  mobileStyles.href = 'android.css';
  document.head.appendChild(mobileStyles);
}

window.__androidNMMCallback = (requestId, succeeded, payloadText) => {
  const pending = androidPending.get(requestId);
  if (!pending) return;
  androidPending.delete(requestId);
  let payload;
  try { payload = payloadText ? JSON.parse(payloadText) : {}; }
  catch { payload = {detail: payloadText || 'Android 返回了无法解析的数据'}; }
  if (succeeded) pending.resolve(payload);
  else pending.reject(new Error(payload.detail || 'Android 本地操作失败'));
};

window.__androidNMMProgress = message => {
  const status = document.querySelector('#aiStatus');
  if (!status) return;
  status.className = 'ai-status busy';
  status.textContent = message;
};

function invokeAndroid(method, ...args) {
  return new Promise((resolve, reject) => {
    const requestId = `android-${Date.now()}-${++androidRequestSequence}`;
    androidPending.set(requestId, {resolve, reject});
    try { window.AndroidNMM[method](requestId, ...args); }
    catch (error) { androidPending.delete(requestId); reject(error); }
  });
}

function blobToDataURL(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error || new Error('无法读取图片数据'));
    reader.readAsDataURL(blob);
  });
}

const canvas = document.querySelector('#mainCanvas');
const ctx = canvas.getContext('2d', { willReadFrequently: true });
const pinchViewportOverlay = isAndroidApp ? document.createElement('div') : null;
if(pinchViewportOverlay){
  pinchViewportOverlay.className='pinch-viewport';
  pinchViewportOverlay.hidden=true;
  document.querySelector('#canvasFrame').appendChild(pinchViewportOverlay);
}
// The 3D preview was removed; keep one detached canvas only for legacy helper
// functions that are no longer initialized or exposed by the interface.
const depthCanvas = document.createElement('canvas');
const photoCanvas = document.createElement('canvas');
const photoCtx = photoCanvas.getContext('2d', { willReadFrequently: true });
const photoBlurCanvas = document.createElement('canvas');
const photoBlurCtx = photoBlurCanvas.getContext('2d', { willReadFrequently: true });
const maskCanvas = document.createElement('canvas');
const maskCtx = maskCanvas.getContext('2d', { willReadFrequently: true });
const lineartCanvas = document.createElement('canvas');
const lineartCtx = lineartCanvas.getContext('2d', { willReadFrequently: true });
const normalSourceCanvas = document.createElement('canvas');
const normalSourceCtx = normalSourceCanvas.getContext('2d', { willReadFrequently: true });
const normalFilteredCanvas = document.createElement('canvas');
const normalFilteredCtx = normalFilteredCanvas.getContext('2d', { willReadFrequently: true });
const depthMapCanvas = document.createElement('canvas');
const depthMapCtx = depthMapCanvas.getContext('2d', { willReadFrequently: true });
const detailNormalSourceCanvas = document.createElement('canvas');
const detailNormalSourceCtx = detailNormalSourceCanvas.getContext('2d', { willReadFrequently: true });
const detailNormalFilteredCanvas = document.createElement('canvas');
const detailNormalFilteredCtx = detailNormalFilteredCanvas.getContext('2d', { willReadFrequently: true });
const lightingFrameCanvas = document.createElement('canvas');
const lightingFrameCtx = lightingFrameCanvas.getContext('2d');

const state = {
  material: 'silver', tint: 'natural', showOriginal: true, lightingEnabled: true, previewMode: 'lighting',
  gloss: 68, normalSmooth: 4, strength: .72, reflections: 3, bounce: .38, steps: 7,
  detailScale: 4, detailStrength: .28,
  artDirection: 'balanced',
  lightPreset: 'balanced', fillLight: .28, detailLight: .35,
  lightX: -.38, lightY: -.42, lightDragging: false,
  hasImage: true, maskDirty: true, regionSelecting: false, inferenceBusy: false
};

const MATERIAL_PRESETS = {
  silver: {name:'白银',gloss:68,palette:[[8,13,19],[39,51,64],[106,123,142],[205,217,226],[255,255,248]]},
  stainless: {name:'不锈钢',gloss:82,palette:[[7,11,16],[30,39,48],[83,98,111],[177,190,198],[247,250,248]]},
  aluminum: {name:'铝',gloss:48,palette:[[18,22,25],[60,68,73],[129,137,141],[206,211,212],[250,252,248]]},
  gold: {name:'黄金',gloss:76,palette:[[24,13,5],[75,42,9],[153,90,18],[235,176,58],[255,246,180]]},
  copper: {name:'红铜',gloss:70,palette:[[25,10,6],[82,31,16],[161,69,35],[227,139,83],[255,222,176]]},
  bronze: {name:'青铜',gloss:58,palette:[[24,12,10],[75,35,24],[139,72,43],[218,139,83],[255,226,176]]},
  darksteel: {name:'黑钢',gloss:62,palette:[[4,7,10],[16,24,31],[45,59,68],[111,126,133],[226,232,229]]}
};
const TINT_PRESETS = {
  natural: {name:'原色',color:null,strength:0},
  red: {name:'红色',color:[220,50,45],strength:.42},
  yellow: {name:'黄色',color:[238,198,38],strength:.36},
  green: {name:'绿色',color:[52,156,92],strength:.38},
  blue: {name:'蓝色',color:[52,117,202],strength:.42},
  purple: {name:'紫色',color:[142,77,191],strength:.40},
  black: {name:'黑色',color:[24,29,36],strength:.45}
};

function getActivePalette(){
  const material=MATERIAL_PRESETS[state.material]||MATERIAL_PRESETS.silver;
  const tint=TINT_PRESETS[state.tint]||TINT_PRESETS.natural;
  if(!tint.color)return material.palette;
  return material.palette.map((base,index)=>{
    const luminance=.2126*base[0]+.7152*base[1]+.0722*base[2];
    const highlight=index/(material.palette.length-1);
    const amount=tint.strength*(1-highlight*.45);
    const lightScale=.26+.82*luminance/255;
    return base.map((value,channel)=>Math.round(value*(1-amount)+tint.color[channel]*lightScale*amount));
  });
}
const LIGHTING_PRESETS = {
  single: {fillLight:0, detailLight:0},
  balanced: {fillLight:.28, detailLight:.35},
  detail: {fillLight:.16, detailLight:.68}
};
const ART_DIRECTION_PRESETS = {
  original: {name:'原始', band:0, contrast:0, separation:0},
  balanced: {name:'平衡', band:.58, contrast:.48, separation:.42},
  competition: {name:'竞赛', band:.88, contrast:.78, separation:.7}
};
let maskData = null;
let cachedBox = null;
let aiNormalData = null;
let aiNormalReady = false;
let aiLineartReady = false;
let photoDetailData = null;
let currentUploadFile = null;
let supplementRegions = [];
let regionStart = null;
let regionCurrent = null;
let depthPreviewReady = false;
let currentAnalysisId = null;
let pendingLocalRegion = null;
let detailNormalData = null;
let detailNormalReady = false;
let surfaceAnalysis = null;
let exportBusy = false;
let comparisonPosition = .5;
let lensActive = false;
let lensPoint = null;
let lensHoldTimer = null;
let lensTouchActive = false;
let lensTouchStart = null;
const pinchPointers = new Map();
let pinchGesture = null;
let lastNonOriginalMode = 'lighting';
const zoomStack = [];
let zoomDebounceTimer = null;
let committedZoomPercent = 100;
let requestedZoomPercent = 100;
const ZOOM_DEBOUNCE_MS = 800;

const ANALYSIS_LOCK_NAME = 'nmm-local-ai-analysis-v1';
const ANALYSIS_CHANNEL_NAME = 'nmm-local-ai-status-v1';
const ANALYSIS_HEARTBEAT_MS = 4000;
const ANALYSIS_LEASE_MS = 12000;
const GPU_COOLDOWN_MS = 5000;
const analysisPageId = crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`;
const analysisChannel = 'BroadcastChannel' in window ? new BroadcastChannel(ANALYSIS_CHANNEL_NAME) : null;
let localInferenceRunning = false;
let remoteInferenceOwner = null;
let remoteInferenceTimer = null;
let analysisHeartbeat = null;

function syncInferenceControls() {
  const busy = localInferenceRunning || Boolean(remoteInferenceOwner);
  const zoomed = zoomStack.length > 0;
  state.inferenceBusy = busy;
  const controls = document.querySelector('.controls');
  controls.classList.toggle('inference-locked', busy);
  controls.setAttribute('aria-busy', String(busy));
  ['imageInput','demoButton']
    .forEach(id => { document.querySelector(`#${id}`).disabled = busy; });
  document.querySelector('#regionSelectButton').disabled = busy || zoomed;
  ['normalSmooth','fillLight','reflections','bounce','steps'].forEach(id=>{
    document.querySelector(`#${id}`).disabled=busy;
  });
  document.querySelector('#detailLight').disabled=busy||!detailNormalReady;
  document.querySelector('#detailScale').disabled=busy||!detailNormalReady;
  document.querySelector('#detailStrength').disabled=busy||!detailNormalReady;
  document.querySelectorAll('#lightingPresets button').forEach(button=>{button.disabled=busy;});
  document.querySelectorAll('#artDirectionPresets button').forEach(button=>{button.disabled=busy;});
  const canZoom=!busy&&Boolean(currentAnalysisId);
  const zoomRange=document.querySelector('#zoomRange');
  zoomRange.disabled=!canZoom;
  document.querySelector('#zoomInButton').disabled=!canZoom||+zoomRange.value>=+zoomRange.max;
  document.querySelector('#zoomOutButton').disabled=!canZoom||+zoomRange.value<=+zoomRange.min;
  document.querySelector('#clearRegionsButton').disabled = busy || zoomed || supplementRegions.length === 0;
  document.querySelector('#downloadButton').disabled = busy || exportBusy || !aiNormalReady || !aiLineartReady;
  if (busy && state.regionSelecting) setRegionMode(false);
}

function broadcastInference(type) {
  analysisChannel?.postMessage({
    type,
    owner: analysisPageId,
    expiresAt: Date.now() + ANALYSIS_LEASE_MS
  });
}

function setRemoteInference(message) {
  if (!message?.owner || message.owner === analysisPageId || localInferenceRunning) return;
  clearTimeout(remoteInferenceTimer);
  if (message.type === 'released') {
    if (remoteInferenceOwner === message.owner) remoteInferenceOwner = null;
  } else if (message.type === 'busy') {
    remoteInferenceOwner = message.owner;
    const remaining = Math.max(1000, (message.expiresAt || Date.now()) - Date.now());
    remoteInferenceTimer = setTimeout(() => {
      if (remoteInferenceOwner === message.owner) {
        remoteInferenceOwner = null;
        syncInferenceControls();
      }
    }, remaining);
  }
  syncInferenceControls();
  if (remoteInferenceOwner) {
    const status = document.querySelector('#aiStatus');
    status.className = 'ai-status busy';
    status.textContent = '另一个页面正在进行 AI 推理，请稍候…';
  } else {
    const status = document.querySelector('#aiStatus');
    if (status.textContent.startsWith('另一个页面')) {
      status.className = 'ai-status';
      status.textContent = 'AI 已空闲，可以开始分析';
    }
  }
}

if (analysisChannel) {
  analysisChannel.onmessage = event => {
    if (event.data?.type === 'query' && localInferenceRunning) broadcastInference('busy');
    else setRemoteInference(event.data);
  };
  analysisChannel.postMessage({type:'query', owner:analysisPageId});
}
window.addEventListener('beforeunload',()=>{
  if(localInferenceRunning)broadcastInference('released');
  analysisChannel?.close();
});

const depthRenderer = {
  gl: null, program: null, vao: null, indexBuffer: null, indexCount: 0,
  textures: {}, dragging: false, lastX: 0, lastY: 0
};

function compileDepthShader(gl,type,source){
  const shader=gl.createShader(type);
  gl.shaderSource(shader,source);gl.compileShader(shader);
  if(!gl.getShaderParameter(shader,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(shader)||'3D shader compile failed');
  return shader;
}

function initDepthRenderer(){
  const gl=depthCanvas.getContext('webgl2',{alpha:false,antialias:true,preserveDrawingBuffer:true});
  if(!gl)return false;
  const vertexSource=`#version 300 es
    precision highp float;
    layout(location=0) in vec3 aPosition;
    layout(location=1) in vec3 aNormal;
    layout(location=2) in vec2 aUv;
    uniform vec2 uRotation;
    uniform float uAspect;
    out vec3 vNormal;
    out vec2 vUv;
    vec3 rotateX(vec3 p,float a){float c=cos(a),s=sin(a);return vec3(p.x,p.y*c-p.z*s,p.y*s+p.z*c);}
    vec3 rotateY(vec3 p,float a){float c=cos(a),s=sin(a);return vec3(p.x*c+p.z*s,p.y,-p.x*s+p.z*c);}
    void main(){
      vec3 p=rotateY(rotateX(aPosition,uRotation.x),uRotation.y);
      vec3 n=rotateY(rotateX(aNormal,uRotation.x),uRotation.y);
      gl_Position=vec4(p.x*.95/uAspect,p.y*.95,p.z*.55,1.0);
      vNormal=normalize(n);vUv=aUv;
    }`;
  const fragmentSource=`#version 300 es
    precision highp float;
    in vec3 vNormal;
    in vec2 vUv;
    uniform sampler2D uLineart;
    uniform sampler2D uPhoto;
    uniform sampler2D uMask;
    uniform vec3 uLight;
uniform vec3 uPalette[5];
uniform float uExponent;
uniform float uSmoothness;
uniform float uStrength;
    uniform float uFillLight;
    uniform float uDetailLight;
    uniform float uBounce;
    uniform float uSteps;
    uniform float uReflections;
    uniform float uShowOriginal;
    out vec4 outColor;
    vec3 paletteAt(float p){
      if(p<1.0)return mix(uPalette[0],uPalette[1],p);
      if(p<2.0)return mix(uPalette[1],uPalette[2],p-1.0);
      if(p<3.0)return mix(uPalette[2],uPalette[3],p-2.0);
      return mix(uPalette[3],uPalette[4],clamp(p-3.0,0.0,1.0));
    }
    void main(){
      float mask=texture(uMask,vUv).a;
      if(mask<.06)discard;
      vec3 n=normalize(vNormal),viewDir=vec3(0.0,0.0,1.0),lightDir=normalize(uLight);
      vec3 halfDir=normalize(lightDir+viewDir);
      float diffuse=max(0.0,dot(n,lightDir));
      float spec=pow(max(0.0,dot(n,halfDir)),uExponent);
      float rim=pow(max(0.0,1.0-n.z),3.2);
float diffuseGain=mix(.58,.42,uSmoothness);
float specularGain=mix(.28,.96,uSmoothness);
float rimGain=mix(.12,.25,uSmoothness);
float primary=clamp(.055+diffuse*diffuseGain+spec*specularGain+rim*rimGain,0.0,.999);
      vec3 fillDir=normalize(vec3(-lightDir.x*.85,-lightDir.y*.55,.55));
      float fillDiffuse=max(0.0,dot(n,fillDir));
      float fillSpec=pow(max(0.0,dot(n,normalize(fillDir+viewDir))),max(5.0,uExponent*.62));
      float fillLayer=min(.74,.045+uFillLight*(fillDiffuse*.5+fillSpec*.32));
      primary=max(primary,fillLayer);
      vec3 detailDir=normalize(vec3(lightDir.xy,.18));
      float detailBand=pow(max(0.0,dot(n,normalize(detailDir+viewDir))),max(10.0,uExponent*.9));
      primary=clamp(primary+detailBand*uDetailLight*.2,0.0,.999);
      float steps=max(3.0,uSteps);
      float quantized=floor(primary*(steps-1.0)+.5)/(steps-1.0);
      vec3 color=paletteAt(quantized*4.0);
      vec3 secondLight=normalize(vec3(-lightDir.x*.7,-.78,.34));
      vec3 thirdLight=normalize(vec3(lightDir.x>=0.0?-.92:.92,.18,.23));
float reflectionGain=mix(.58,1.12,uSmoothness);
float secondary=pow(max(0.0,dot(n,normalize(secondLight+viewDir))),max(4.0,uExponent*.48))*uBounce*reflectionGain;
float tertiary=pow(max(0.0,dot(n,normalize(thirdLight+viewDir))),max(5.0,uExponent*1.05))*uBounce*reflectionGain;
      if(uReflections>2.5&&tertiary>.065)color=vec3(.867,.659,.435);
      else if(uReflections>1.5&&secondary>.06)color=vec3(.322,.718,.827);
      vec3 paintColor=color;
      vec3 lineart=texture(uLineart,vUv).rgb;
      float ink=1.0-min(lineart.r,min(lineart.g,lineart.b));
      vec3 photo=texture(uPhoto,vUv).rgb;
      vec2 detailOffset=4.0/vec2(textureSize(uPhoto,0));
      float photoLum=dot(photo,vec3(.2126,.7152,.0722));
      float localLum=(photoLum*2.0
        +dot(texture(uPhoto,vUv+vec2(detailOffset.x,0.0)).rgb,vec3(.2126,.7152,.0722))
        +dot(texture(uPhoto,vUv-vec2(detailOffset.x,0.0)).rgb,vec3(.2126,.7152,.0722))
        +dot(texture(uPhoto,vUv+vec2(0.0,detailOffset.y)).rgb,vec3(.2126,.7152,.0722))
        +dot(texture(uPhoto,vUv-vec2(0.0,detailOffset.y)).rgb,vec3(.2126,.7152,.0722)))/6.0;
      float detail=clamp((photoLum+.07)/(localLum+.07),.72,1.34);
      vec3 paintedPhoto=clamp(paintColor*detail+(photoLum-localLum)*.22,0.0,1.0);
      vec3 guideColor=paintColor*(1.0-ink*.9);
      vec3 guide=mix(lineart,guideColor,uStrength*.9);
      vec3 photoPaint=mix(photo,paintedPhoto,min(1.0,uStrength*1.18));
      color=mix(guide,photoPaint,uShowOriginal);
      outColor=vec4(color,1.0);
    }`;
  try{
    const program=gl.createProgram();
    gl.attachShader(program,compileDepthShader(gl,gl.VERTEX_SHADER,vertexSource));
    gl.attachShader(program,compileDepthShader(gl,gl.FRAGMENT_SHADER,fragmentSource));
    gl.linkProgram(program);
    if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(program)||'3D shader link failed');
    depthRenderer.gl=gl;depthRenderer.program=program;
    gl.enable(gl.DEPTH_TEST);gl.clearColor(1,1,1,1);
    return true;
  }catch(error){
    document.querySelector('[data-view="3d"]').title='当前浏览器不支持 3D 预览';
    return false;
  }
}

function uploadDepthTexture(name,source,unit){
  const {gl}=depthRenderer;if(!gl)return;
  const texture=depthRenderer.textures[name]||gl.createTexture();
  depthRenderer.textures[name]=texture;
  gl.activeTexture(gl.TEXTURE0+unit);gl.bindTexture(gl.TEXTURE_2D,texture);
  gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,true);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
  gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,source);
}

function rebuildDepthGeometry(){
  const {gl,program}=depthRenderer;
  if(!gl||!program||!aiNormalData||!depthMapCanvas.width)return;
  const w=depthMapCanvas.width,h=depthMapCanvas.height,aspect=w/h;
  let cols=Math.min(384,w),rows=Math.max(2,Math.round(cols*h/w));
  if(rows>384){rows=384;cols=Math.max(2,Math.round(rows*w/h));}
  const depthPixels=depthMapCtx.getImageData(0,0,w,h).data;
  const maskPixels=maskCtx.getImageData(0,0,w,h).data;
  let minX=w,minY=h,maxX=0,maxY=0;
  for(let y=0;y<h;y+=2)for(let x=0;x<w;x+=2){
    if(maskPixels[(y*w+x)*4+3]>16){minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}
  }
  if(minX>maxX||minY>maxY){depthPreviewReady=false;return;}
  const centerU=(minX+maxX)/(2*Math.max(1,w-1)),centerV=(minY+maxY)/(2*Math.max(1,h-1));
  const subjectWidth=2*aspect*(maxX-minX)/Math.max(1,w-1),subjectHeight=2*(maxY-minY)/Math.max(1,h-1);
  const fitScale=1.62/Math.max(.1,subjectWidth,subjectHeight);
  const vertices=new Float32Array(cols*rows*8);
  const occupied=new Uint8Array(cols*rows);
  for(let row=0;row<rows;row++)for(let col=0;col<cols;col++){
    const u=col/(cols-1),v=row/(rows-1);
    const x=Math.min(w-1,Math.round(u*(w-1))),y=Math.min(h-1,Math.round(v*(h-1)));
    const source=(y*w+x)*4,target=(row*cols+col)*8;
    const depth=depthPixels[source]/255;
    vertices[target]=(u-centerU)*2*aspect*fitScale;
    vertices[target+1]=(centerV-v)*2*fitScale;
    vertices[target+2]=(.5-depth)*.36;
    const [nx,ny,nz]=normalAt(x,y);
    vertices[target+3]=nx;vertices[target+4]=ny;vertices[target+5]=nz;
    vertices[target+6]=u;vertices[target+7]=v;
    occupied[row*cols+col]=maskPixels[source+3]>16?1:0;
  }
  // Single-image depth is least reliable at silhouettes. Flatten the outer two
  // grid rings so a small camera orbit cannot pull the edge into long spikes.
  for(let row=0;row<rows;row++)for(let col=0;col<cols;col++){
    if(!occupied[row*cols+col])continue;
    let nearBoundary=false;
    for(let dy=-2;dy<=2&&!nearBoundary;dy++)for(let dx=-2;dx<=2;dx++){
      const yy=row+dy,xx=col+dx;
      if(yy<0||yy>=rows||xx<0||xx>=cols||!occupied[yy*cols+xx]){nearBoundary=true;break;}
    }
    if(nearBoundary)vertices[(row*cols+col)*8+2]*=.12;
  }
  const indices=[];
  for(let row=0;row<rows-1;row++)for(let col=0;col<cols-1;col++){
    const a=row*cols+col,b=a+1,c=a+cols,d=c+1;
    if(occupied[a]&&occupied[c]&&occupied[b])indices.push(a,c,b);
    if(occupied[b]&&occupied[c]&&occupied[d])indices.push(b,c,d);
  }
  const vao=depthRenderer.vao||gl.createVertexArray();depthRenderer.vao=vao;gl.bindVertexArray(vao);
  const vertexBuffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,vertexBuffer);gl.bufferData(gl.ARRAY_BUFFER,vertices,gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,3,gl.FLOAT,false,32,0);
  gl.enableVertexAttribArray(1);gl.vertexAttribPointer(1,3,gl.FLOAT,false,32,12);
  gl.enableVertexAttribArray(2);gl.vertexAttribPointer(2,2,gl.FLOAT,false,32,24);
  const indexBuffer=depthRenderer.indexBuffer||gl.createBuffer();depthRenderer.indexBuffer=indexBuffer;
  const indexData=new Uint32Array(indices);gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,indexBuffer);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,indexData,gl.STATIC_DRAW);
  depthRenderer.indexCount=indexData.length;
  uploadDepthTexture('lineart',lineartCanvas,0);uploadDepthTexture('photo',photoCanvas,1);uploadDepthTexture('mask',maskCanvas,2);
  depthPreviewReady=depthRenderer.indexCount>0;
  const depthButton=document.querySelector('[data-view="3d"]');
  depthButton.disabled=!depthPreviewReady;
  if(!depthPreviewReady&&state.viewMode==='3d')setViewMode('2d');
}

function renderDepthPreview(){
  const {gl,program}=depthRenderer;
  if(!gl||!program||!depthPreviewReady)return;
  gl.viewport(0,0,depthCanvas.width,depthCanvas.height);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);
  gl.useProgram(program);gl.bindVertexArray(depthRenderer.vao);
  const uniform=(name)=>gl.getUniformLocation(program,name);
  gl.uniform2f(uniform('uRotation'),state.depthRotateX,state.depthRotateY);
  gl.uniform1f(uniform('uAspect'),depthCanvas.width/depthCanvas.height);
  const lightLength=Math.hypot(state.lightX,state.lightY)||0;
  const lightZ=Math.sqrt(Math.max(.08,1-Math.min(.92,lightLength)**2));
  gl.uniform3f(uniform('uLight'),state.lightX,-state.lightY,lightZ);
  const smoothness=state.gloss/100;
  gl.uniform1f(uniform('uExponent'),8+smoothness*smoothness*88);
  gl.uniform1f(uniform('uSmoothness'),smoothness);
  gl.uniform1f(uniform('uStrength'),state.strength);
  gl.uniform1f(uniform('uFillLight'),state.fillLight);
  gl.uniform1f(uniform('uDetailLight'),detailNormalReady?state.detailLight:0);
  gl.uniform1f(uniform('uBounce'),state.bounce);
  gl.uniform1f(uniform('uSteps'),state.steps);
  gl.uniform1f(uniform('uReflections'),state.reflections);
  gl.uniform1f(uniform('uShowOriginal'),state.showOriginal?1:0);
  gl.uniform3fv(uniform('uPalette[0]'),new Float32Array(getActivePalette().flat().map(value=>value/255)));
  gl.uniform1i(uniform('uLineart'),0);gl.uniform1i(uniform('uPhoto'),1);gl.uniform1i(uniform('uMask'),2);
  gl.drawElements(gl.TRIANGLES,depthRenderer.indexCount,gl.UNSIGNED_INT,0);
}

function setViewMode(mode){
  if(mode==='3d'&&!depthPreviewReady)return;
  state.viewMode=mode;
  canvas.hidden=mode==='3d';depthCanvas.hidden=mode!=='3d';
  document.querySelector('#depthHint').hidden=mode!=='3d';
  document.querySelectorAll('#viewModeToggle button').forEach(button=>button.classList.toggle('active',button.dataset.view===mode));
  document.querySelector('#canvasInfo').textContent=mode==='3d'?'2.5D 深度预览 · 拖动画面观察形体':'最终指引 · 拖动太阳调整光照方向';
  if(mode==='3d')renderDepthPreview();else render();
  syncInferenceControls();
}

document.querySelectorAll('#viewModeToggle button').forEach(button=>button.addEventListener('click',()=>setViewMode(button.dataset.view)));
depthCanvas.addEventListener('pointerdown',event=>{depthRenderer.dragging=true;depthRenderer.lastX=event.clientX;depthRenderer.lastY=event.clientY;depthCanvas.setPointerCapture(event.pointerId);});
depthCanvas.addEventListener('pointermove',event=>{
  if(!depthRenderer.dragging)return;
  state.depthRotateY=Math.max(-.24,Math.min(.24,state.depthRotateY+(event.clientX-depthRenderer.lastX)*.0045));
  state.depthRotateX=Math.max(-.2,Math.min(.2,state.depthRotateX+(event.clientY-depthRenderer.lastY)*.0045));
  depthRenderer.lastX=event.clientX;depthRenderer.lastY=event.clientY;renderDepthPreview();
});
depthCanvas.addEventListener('pointerup',()=>{depthRenderer.dragging=false;});
depthCanvas.addEventListener('dblclick',()=>{});

function sizeCanvases(w, h) {
  const max = 1100;
  const scale = Math.min(1, max / Math.max(w, h));
  const cw = Math.max(1, Math.round(w * scale));
  const ch = Math.max(1, Math.round(h * scale));
  [canvas, depthCanvas, photoCanvas, photoBlurCanvas, maskCanvas, lineartCanvas, normalSourceCanvas, normalFilteredCanvas, depthMapCanvas, detailNormalSourceCanvas, detailNormalFilteredCanvas].forEach(c => { c.width = cw; c.height = ch; });
  photoDetailData = null;
  aiNormalReady = false;
  aiLineartReady = false;
  detailNormalData = null;
  detailNormalReady = false;
  surfaceAnalysis = null;
  depthPreviewReady = false;
}

function rebuildPhotoDetail() {
  if(!photoCanvas.width || !photoCanvas.height){photoDetailData=null;return;}
  const radius=Math.max(3,Math.min(9,Math.round(Math.max(photoCanvas.width,photoCanvas.height)/180)));
  photoBlurCtx.save();
  photoBlurCtx.clearRect(0,0,photoBlurCanvas.width,photoBlurCanvas.height);
  photoBlurCtx.filter=`blur(${radius}px)`;
  photoBlurCtx.drawImage(photoCanvas,0,0);
  photoBlurCtx.restore();
  const source=photoCtx.getImageData(0,0,photoCanvas.width,photoCanvas.height).data;
  const blurred=photoBlurCtx.getImageData(0,0,photoBlurCanvas.width,photoBlurCanvas.height).data;
  photoDetailData=new Float32Array(photoCanvas.width*photoCanvas.height);
  for(let p=0,index=0;p<source.length;p+=4,index++){
    const luminance=.2126*source[p]+.7152*source[p+1]+.0722*source[p+2];
    const localLuminance=.2126*blurred[p]+.7152*blurred[p+1]+.0722*blurred[p+2];
    photoDetailData[index]=Math.max(-48,Math.min(48,luminance-localLuminance));
  }
}

function createDemo() {
  sizeCanvases(900, 980);
  const w = photoCanvas.width, h = photoCanvas.height;
  const g = photoCtx.createRadialGradient(w*.5,h*.35,20,w*.5,h*.5,h*.72);
  g.addColorStop(0,'#534e45'); g.addColorStop(.48,'#262521'); g.addColorStop(1,'#0b0c0c');
  photoCtx.fillStyle = g; photoCtx.fillRect(0,0,w,h);
  // atmospheric streaks
  photoCtx.globalAlpha=.12; photoCtx.strokeStyle='#d9ff43';
  for(let i=0;i<7;i++){ photoCtx.beginPath(); photoCtx.moveTo(-50,h*(.13+i*.12)); photoCtx.lineTo(w*.46,h*(.02+i*.12)); photoCtx.stroke(); }
  photoCtx.globalAlpha=1;
  // miniature silhouette
  photoCtx.fillStyle='#111311';
  photoCtx.beginPath(); photoCtx.arc(w*.54,h*.26,w*.085,0,Math.PI*2); photoCtx.fill();
  photoCtx.beginPath(); photoCtx.moveTo(w*.43,h*.3); photoCtx.lineTo(w*.68,h*.33); photoCtx.lineTo(w*.75,h*.8); photoCtx.lineTo(w*.39,h*.8); photoCtx.closePath(); photoCtx.fill();
  photoCtx.fillStyle='#20231f'; photoCtx.fillRect(w*.49,h*.32,w*.12,h*.47);
  // sword
  photoCtx.save(); photoCtx.translate(w*.66,h*.31); photoCtx.rotate(-.48);
  photoCtx.fillStyle='#41443e'; photoCtx.fillRect(-8,-130,16,460); photoCtx.fillStyle='#171916'; photoCtx.fillRect(-48,310,96,17); photoCtx.restore();
  // shield base
  const cx=w*.35,cy=h*.56,r=Math.min(w,h)*.245;
  let sg=photoCtx.createRadialGradient(cx-r*.35,cy-r*.35,5,cx,cy,r);
  sg.addColorStop(0,'#686b61'); sg.addColorStop(.4,'#383b35'); sg.addColorStop(.78,'#1b1e1b'); sg.addColorStop(1,'#55594f');
  photoCtx.fillStyle=sg; photoCtx.beginPath(); photoCtx.arc(cx,cy,r,0,Math.PI*2); photoCtx.fill();
  photoCtx.strokeStyle='#11130f'; photoCtx.lineWidth=r*.09; photoCtx.stroke();
  photoCtx.strokeStyle='#77796e'; photoCtx.lineWidth=r*.018; photoCtx.beginPath(); photoCtx.arc(cx,cy,r*.9,0,Math.PI*2); photoCtx.stroke();
  photoCtx.fillStyle='#252824'; photoCtx.beginPath(); photoCtx.arc(cx,cy,r*.31,0,Math.PI*2); photoCtx.fill();
  photoCtx.strokeStyle='#77796e'; photoCtx.lineWidth=5; photoCtx.stroke();
  // decorative cuts
  photoCtx.strokeStyle='rgba(210,215,198,.23)'; photoCtx.lineWidth=3;
  for(let i=0;i<8;i++){ const a=i*Math.PI/4; photoCtx.beginPath(); photoCtx.moveTo(cx+Math.cos(a)*r*.4,cy+Math.sin(a)*r*.4); photoCtx.lineTo(cx+Math.cos(a)*r*.82,cy+Math.sin(a)*r*.82); photoCtx.stroke(); }
  rebuildPhotoDetail();
  maskCtx.clearRect(0,0,w,h); maskCtx.fillStyle='#fff'; maskCtx.beginPath(); maskCtx.arc(cx,cy,r*.92,0,Math.PI*2); maskCtx.fill();
  state.maskDirty=true; state.hasImage=true; aiNormalData=null; aiNormalReady=false; aiLineartReady=false; currentUploadFile=null;currentAnalysisId=null;
  supplementRegions=[];setRegionMode(false);setGuideReady(false);
  document.querySelector('#photoName').textContent='NMM 演示 · 圆盾';
  render();
}

function getMaskInfo() {
  if (!state.maskDirty && maskData && cachedBox) return { data: maskData, box: cachedBox };
  surfaceAnalysis=null;
  maskData = maskCtx.getImageData(0,0,canvas.width,canvas.height).data;
  let minX=canvas.width,minY=canvas.height,maxX=0,maxY=0,count=0;
  for(let y=0;y<canvas.height;y+=2) for(let x=0;x<canvas.width;x+=2) {
    if(maskData[(y*canvas.width+x)*4+3]>20){ minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);count++; }
  }
  cachedBox = count ? {minX,minY,maxX,maxY,cx:(minX+maxX)/2,cy:(minY+maxY)/2,rx:Math.max(8,(maxX-minX)/2),ry:Math.max(8,(maxY-minY)/2)} : null;
  state.maskDirty=false;
  return { data: maskData, box: cachedBox };
}

function normalAt(x,y,includeDetail=true) {
  let nx=0,ny=0,nz=1;
  const p=(Math.round(y)*canvas.width+Math.round(x))*4;
  if(aiNormalData){
    nx=aiNormalData[p]/127.5-1; ny=aiNormalData[p+1]/127.5-1; nz=aiNormalData[p+2]/127.5-1;
    const length=Math.hypot(nx,ny,nz)||1; nx/=length;ny/=length;nz/=length;
  }
  if(includeDetail&&detailNormalReady&&detailNormalData&&state.detailStrength>0){
    let dx=detailNormalData[p]/127.5-1,dy=detailNormalData[p+1]/127.5-1,dz=detailNormalData[p+2]/127.5-1;
    const detailLength=Math.hypot(dx,dy,dz)||1;dx/=detailLength;dy/=detailLength;dz/=detailLength;
    const amount=state.detailStrength;
    nx+= (dx-nx)*amount;ny+=(dy-ny)*amount;nz+=(dz-nz)*amount;
    const mixedLength=Math.hypot(nx,ny,nz)||1;nx/=mixedLength;ny/=mixedLength;nz/=mixedLength;
  }
  return [nx,ny,nz];
}

function updateSurfaceStatus(analysis){
  const status=document.querySelector('#surfaceStatus');
  if(!status)return;
  if(state.artDirection==='original'){
    status.textContent='艺术塑形已关闭，使用 v0.2.0 原始光照。';
    return;
  }
  if(!analysis){
    status.textContent='完成形体分析后，将自动建立表面分区。';
    return;
  }
  const {plane=0,cylinder=0,rounded=0,bevel=0,detail=0}=analysis.summary;
  status.textContent=`自动分区 ${analysis.patches.length} 块 · 平面 ${plane} · 柱面 ${cylinder} · 曲面 ${rounded} · 棱边/细节 ${bevel+detail}`;
}

function buildSurfaceAnalysis(){
  if(surfaceAnalysis)return surfaceAnalysis;
  if(!aiNormalData||!aiLineartReady||!canvas.width||!canvas.height)return null;
  const {data:mask,box}=getMaskInfo();
  if(!box)return null;
  const width=canvas.width,height=canvas.height;
  const step=Math.max(4,Math.round(Math.max(width,height)/210));
  const gridWidth=Math.ceil(width/step),gridHeight=Math.ceil(height/step);
  const cellCount=gridWidth*gridHeight;
  const valid=new Uint8Array(cellCount);
  const cellNX=new Float32Array(cellCount),cellNY=new Float32Array(cellCount),cellNZ=new Float32Array(cellCount);
  const cellDepth=new Float32Array(cellCount);
  const labels=new Int32Array(cellCount);labels.fill(-1);
  const lineData=lineartCtx.getImageData(0,0,width,height).data;
  const depthData=depthMapCtx.getImageData(0,0,width,height).data;
  let totalCells=0;

  for(let gy=0;gy<gridHeight;gy++){
    const y=Math.min(height-1,gy*step+Math.floor(step/2));
    for(let gx=0;gx<gridWidth;gx++){
      const x=Math.min(width-1,gx*step+Math.floor(step/2));
      const index=gy*gridWidth+gx,p=(y*width+x)*4;
      if(mask[p+3]<32)continue;
      let nx=aiNormalData[p]/127.5-1,ny=aiNormalData[p+1]/127.5-1,nz=aiNormalData[p+2]/127.5-1;
      const length=Math.hypot(nx,ny,nz)||1;nx/=length;ny/=length;nz/=length;
      const lineLuma=(lineData[p]+lineData[p+1]+lineData[p+2])/(3*255);
      const ink=1-lineLuma;
      // Near-black structure lines act as watershed boundaries. They keep two
      // neighbouring armour plates from becoming one giant transitive patch.
      if(ink>.64)continue;
      valid[index]=1;cellNX[index]=nx;cellNY[index]=ny;cellNZ[index]=nz;
      cellDepth[index]=(depthData[p]+depthData[p+1]+depthData[p+2])/(3*255);
      totalCells++;
    }
  }

  const queue=new Int32Array(cellCount);
  const patches=[];
  const neighbours=[[-1,0],[1,0],[0,-1],[0,1]];
  for(let seed=0;seed<cellCount;seed++){
    if(!valid[seed]||labels[seed]!==-1)continue;
    const patchId=patches.length;
    const seedNX=cellNX[seed],seedNY=cellNY[seed],seedNZ=cellNZ[seed],seedDepth=cellDepth[seed];
    let head=0,tail=0;queue[tail++]=seed;labels[seed]=patchId;
    let count=0,sumNX=0,sumNY=0,sumNZ=0,sumNXX=0,sumNYY=0,sumNXY=0,sumX=0,sumY=0;
    while(head<tail){
      const current=queue[head++],cy=Math.floor(current/gridWidth),cx=current-cy*gridWidth;
      const nx=cellNX[current],ny=cellNY[current],nz=cellNZ[current];
      count++;sumNX+=nx;sumNY+=ny;sumNZ+=nz;sumNXX+=nx*nx;sumNYY+=ny*ny;sumNXY+=nx*ny;
      sumX+=Math.min(width-1,cx*step+step*.5);sumY+=Math.min(height-1,cy*step+step*.5);
      for(const [ox,oy] of neighbours){
        const nextX=cx+ox,nextY=cy+oy;
        if(nextX<0||nextY<0||nextX>=gridWidth||nextY>=gridHeight)continue;
        const next=nextY*gridWidth+nextX;
        if(!valid[next]||labels[next]!==-1)continue;
        const localDot=nx*cellNX[next]+ny*cellNY[next]+nz*cellNZ[next];
        const seedDot=seedNX*cellNX[next]+seedNY*cellNY[next]+seedNZ*cellNZ[next];
        const localDepth=Math.abs(cellDepth[current]-cellDepth[next]);
        const seedDepthDelta=Math.abs(seedDepth-cellDepth[next]);
        if(localDot<.94||seedDot<.76||localDepth>.11||seedDepthDelta>.25)continue;
        labels[next]=patchId;queue[tail++]=next;
      }
    }
    const meanNX=sumNX/count,meanNY=sumNY/count,meanNZ=sumNZ/count;
    const covarianceXX=Math.max(0,sumNXX/count-meanNX*meanNX);
    const covarianceYY=Math.max(0,sumNYY/count-meanNY*meanNY);
    const covarianceXY=sumNXY/count-meanNX*meanNY;
    const trace=covarianceXX+covarianceYY;
    const discriminant=Math.sqrt(Math.max(0,(covarianceXX-covarianceYY)**2+4*covarianceXY**2));
    const eigen1=(trace+discriminant)/2,eigen2=Math.max(0,(trace-discriminant)/2);
    const spread=Math.max(0,1-Math.hypot(sumNX,sumNY,sumNZ)/count);
    let kind='rounded';
    if(count<=3)kind='detail';
    else if(meanNZ<.38)kind='bevel';
    else if(spread<.018)kind='plane';
    else if(eigen1>1e-4&&eigen2/eigen1<.32)kind='cylinder';
    patches.push({
      id:patchId,count,cx:sumX/count,cy:sumY/count,
      meanNX,meanNY,meanNZ,spread,eigen1,eigen2,kind,
      importance:.5,reliability:Math.max(0,Math.min(1,(count-2)/20)),
      horizonOffset:0,bandWidth:.14
    });
  }

  const summary={plane:0,cylinder:0,rounded:0,bevel:0,detail:0};
  for(const patch of patches){
    summary[patch.kind]=(summary[patch.kind]||0)+1;
    const normalizedX=(patch.cx-box.cx)/Math.max(1,box.rx);
    const normalizedY=(patch.cy-box.cy)/Math.max(1,box.ry);
    const centrality=1-Math.min(1,Math.hypot(normalizedX,normalizedY)/1.15);
    const sizeScore=Math.min(1,Math.sqrt(patch.count/Math.max(1,totalCells*.045)));
    const upperScore=1-Math.max(0,Math.min(1,(patch.cy-box.minY)/Math.max(1,box.maxY-box.minY)));
    patch.importance=Math.max(.12,Math.min(1,.18+centrality*.34+sizeScore*.3+upperScore*.18));
    patch.horizonOffset=Math.max(-.085,Math.min(.085,(patch.cy/height-.48)*.055+patch.meanNX*.025));
    patch.bandWidth=patch.kind==='plane'?.23:patch.kind==='cylinder'?.1:patch.kind==='bevel'?.075:patch.kind==='detail'?.085:.145;
  }
  surfaceAnalysis={step,gridWidth,gridHeight,labels,patches,summary};
  updateSurfaceStatus(surfaceAnalysis);
  return surfaceAnalysis;
}

function renderLightingFrame(drawOverlay=true) {
  if(!state.hasImage) return;
  ctx.clearRect(0,0,canvas.width,canvas.height);
  if(!state.lightingEnabled){
    ctx.drawImage(photoCanvas,0,0);
    if(drawOverlay)drawRegionOverlay();
    return;
  }
  const guideReady=Boolean(aiNormalData && aiLineartReady);
  const baseCanvas=guideReady && !state.showOriginal ? lineartCanvas : photoCanvas;
  ctx.drawImage(baseCanvas,0,0);
  const {data: mask,box}=getMaskInfo();
  if(!box || !guideReady){if(drawOverlay)drawRegionOverlay();return;}
  const src=(state.showOriginal ? photoCtx : lineartCtx).getImageData(0,0,canvas.width,canvas.height);
  const out=ctx.getImageData(0,0,canvas.width,canvas.height);
  const mag=Math.hypot(state.lightX,state.lightY);
  const lz=Math.sqrt(Math.max(.08,1-Math.min(.92,mag)*Math.min(.92,mag)));
  let lx=state.lightX, ly=-state.lightY;
  const ll=Math.hypot(lx,ly,lz); lx/=ll;ly/=ll; const Lz=lz/ll;
  let hx=lx,hy=ly,hz=Lz+1; const hl=Math.hypot(hx,hy,hz);hx/=hl;hy/=hl;hz/=hl;
  // Artistic fill stays opposite the key and is capped below pure white.
  let flx=-lx*.85,fly=-ly*.55,flz=.55;
  const fll=Math.hypot(flx,fly,flz);flx/=fll;fly/=fll;flz/=fll;
  let fhx=flx,fhy=fly,fhz=flz+1;
  const fhl=Math.hypot(fhx,fhy,fhz);fhx/=fhl;fhy/=fhl;fhz/=fhl;
  // A low grazing light follows the key azimuth and only responds to the
  // difference between broad and micro-structure normals.
  let dlx=lx,dly=ly,dlz=.18;
  if(Math.hypot(dlx,dly)<.05){dlx=-.8;dly=-.6;}
  const dll=Math.hypot(dlx,dly,dlz);dlx/=dll;dly/=dll;dlz/=dll;
  let dhx=dlx,dhy=dly,dhz=dlz+1;
  const dhl=Math.hypot(dhx,dhy,dhz);dhx/=dhl;dhy/=dhl;dhz/=dhl;
  // Two dimmer virtual lights approximate environment and inter-reflection.
  // They remain opposite/below the key light as it moves.
  let s2x=-lx*.7, s2y=-.78, s2z=.34;
  let s2l=Math.hypot(s2x,s2y,s2z); s2x/=s2l;s2y/=s2l;s2z/=s2l;
  let h2x=s2x,h2y=s2y,h2z=s2z+1;
  let h2l=Math.hypot(h2x,h2y,h2z); h2x/=h2l;h2y/=h2l;h2z/=h2l;
  let s3x=lx>=0?-.92:.92, s3y=.18, s3z=.23;
  let s3l=Math.hypot(s3x,s3y,s3z); s3x/=s3l;s3y/=s3l;s3z/=s3l;
  let h3x=s3x,h3y=s3y,h3z=s3z+1;
  let h3l=Math.hypot(h3x,h3y,h3z); h3x/=h3l;h3y/=h3l;h3z/=h3l;
  const pal=getActivePalette();
  const smoothness=state.gloss/100;
  const exponent=8+smoothness*smoothness*88;
  const diffuseGain=.58+(.42-.58)*smoothness;
  const specularGain=.28+(.96-.28)*smoothness;
  const rimGain=.12+(.25-.12)*smoothness;
  const reflectionGain=.58+(1.12-.58)*smoothness;
  const artProfile=ART_DIRECTION_PRESETS[state.artDirection]||ART_DIRECTION_PRESETS.original;
  const surfaces=artProfile.band>0?buildSurfaceAnalysis():null;
  if(!surfaces)updateSurfaceStatus(null);
  for(let y=Math.max(0,box.minY-3);y<=Math.min(canvas.height-1,box.maxY+3);y++){
    const surfaceRow=surfaces?Math.min(surfaces.gridHeight-1,Math.floor(y/surfaces.step))*surfaces.gridWidth:0;
    for(let x=Math.max(0,box.minX-3);x<=Math.min(canvas.width-1,box.maxX+3);x++){
      const p=(y*canvas.width+x)*4, a=mask[p+3]/255;
      if(a<.03) continue;
      const [nx,ny,nz]=normalAt(x,y);
      const [mnx,mny,mnz]=detailNormalReady?normalAt(x,y,false):[nx,ny,nz];
      const diffuse=Math.max(0,nx*lx+ny*ly+nz*Lz);
      const spec=Math.pow(Math.max(0,nx*hx+ny*hy+nz*hz),exponent);
      const fillDiffuse=Math.max(0,nx*flx+ny*fly+nz*flz);
      const fillSpec=Math.pow(Math.max(0,nx*fhx+ny*fhy+nz*fhz),Math.max(5,exponent*.62));
      const secondary=state.reflections>=2 ? Math.pow(Math.max(0,nx*h2x+ny*h2y+nz*h2z),Math.max(4,exponent*.48))*reflectionGain : 0;
      const tertiary=state.reflections>=3 ? Math.pow(Math.max(0,nx*h3x+ny*h3y+nz*h3z),Math.max(5,exponent*1.05))*reflectionGain : 0;
      const rim=Math.pow(1-nz,3.2);
      // Primary reflection owns the complete selected metal palette. Secondary
      // and tertiary lobes are semantic guide colours layered on top.
      let primary=.055+diffuse*diffuseGain+spec*specularGain+rim*rimGain;
      const fillLayer=Math.min(.74,.045+state.fillLight*(fillDiffuse*.5+fillSpec*.32));
      primary=Math.max(primary,fillLayer);
      if(detailNormalReady&&state.detailLight>0){
        const detailDiffuse=Math.max(0,nx*dlx+ny*dly+nz*dlz);
        const macroDiffuse=Math.max(0,mnx*dlx+mny*dly+mnz*dlz);
        const detailSpec=Math.pow(Math.max(0,nx*dhx+ny*dhy+nz*dhz),Math.max(7,exponent*.72));
        const macroSpec=Math.pow(Math.max(0,mnx*dhx+mny*dhy+mnz*dhz),Math.max(7,exponent*.72));
        const microLight=Math.max(0,detailDiffuse-macroDiffuse)*.55+Math.max(0,detailSpec-macroSpec)*.35;
        primary+=microLight*state.detailLight;
      }
      let environmentCool=0,environmentWarm=0;
      if(surfaces){
        const cellX=Math.min(surfaces.gridWidth-1,Math.floor(x/surfaces.step));
        const patchId=surfaces.labels[surfaceRow+cellX];
        const patch=patchId>=0?surfaces.patches[patchId]:null;
        if(patch){
          // Reflect the viewing direction around the broad surface normal. A
          // bright horizon stripe with dark shoulders approximates the large
          // sky/ground reflection that painters commonly encode in NMM.
          const reflectedX=2*mnz*mnx,reflectedY=2*mnz*mny;
          const environmentCoord=reflectedY*.92+reflectedX*.18*(lx>=0?1:-1)-patch.horizonOffset;
          const bandWidth=Math.max(.045,patch.bandWidth*(1.24-smoothness*.5));
          const brightBand=Math.exp(-.5*(environmentCoord/bandWidth)**2);
          const shoulderDistance=Math.abs(environmentCoord)-bandWidth*2.05;
          const darkShoulder=Math.exp(-.5*(shoulderDistance/Math.max(.035,bandWidth*.72))**2);
          const coherence=.08+.92*patch.reliability;
          const priority=.68+.42*patch.importance;
          primary+=brightBand*artProfile.band*(.18+.22*smoothness)*priority*coherence;
          primary-=darkShoulder*artProfile.contrast*(.085+.09*smoothness)*coherence;

          // Allocate slightly more contrast to large, central and upper
          // patches, while suppressing tiny low-confidence islands. This is a
          // deterministic art-direction score, not another AI inference.
          const pivot=.46-(patch.importance-.5)*.035;
          const contrastGain=1+artProfile.contrast*(.16+.2*patch.importance)*coherence;
          primary=pivot+(primary-pivot)*contrastGain;
          primary+=(patch.importance-.5)*artProfile.separation*.055*coherence;

          const sky=Math.max(0,Math.min(1,.5-environmentCoord*.88));
          const ground=1-sky;
          environmentCool=sky*(.045+.105*brightBand)*artProfile.band*state.bounce*coherence;
          environmentWarm=ground*(.035+.085*brightBand)*artProfile.band*state.bounce*coherence;
        }
      }
      primary=Math.max(0,Math.min(.999,primary));
      const q=Math.round(primary*(state.steps-1))/(state.steps-1);
      const pos=q*(pal.length-1), lo=Math.floor(pos), hi=Math.min(pal.length-1,lo+1), f=pos-lo;
      let r=pal[lo][0]*(1-f)+pal[hi][0]*f, g=pal[lo][1]*(1-f)+pal[hi][1]*f, b=pal[lo][2]*(1-f)+pal[hi][2]*f;
      const secondaryGuide=secondary*state.bounce+(state.reflections>=2?environmentCool:0);
      const tertiaryGuide=tertiary*state.bounce+(state.reflections>=3?environmentWarm:0);
      // Reflections keep the selected metal underneath instead of replacing it
      // with two fixed annotation colours. The cool/warm shifts are derived
      // from the current shade, which avoids cyan clashing with blue metals or
      // orange flattening gold and copper.
      const coolWeight=Math.min(.56,Math.max(0,(secondaryGuide-.035)*2.35));
      const warmWeight=Math.min(.5,Math.max(0,(tertiaryGuide-.04)*2.2));
      if(coolWeight>0 || warmWeight>0){
        const coolTarget=[Math.max(18,r*.58),Math.min(242,g*.78+42),Math.min(255,b*.82+68)];
        const warmTarget=[Math.min(255,r*.82+54),Math.min(238,g*.72+30),Math.max(22,b*.58)];
        const coolBias=coolWeight*coolWeight,warmBias=warmWeight*warmWeight;
        const biasTotal=coolBias+warmBias;
        const target=coolTarget.map((value,channel)=>(value*coolBias+warmTarget[channel]*warmBias)/biasTotal);
        const weight=Math.max(coolWeight,warmWeight);
        r=r*(1-weight)+target[0]*weight;
        g=g*(1-weight)+target[1]*weight;
        b=b*(1-weight)+target[2]*weight;
      }
      if(state.showOriginal){
        const detail=photoDetailData?.[p>>2]||0;
        const targetLuminance=.2126*r+.7152*g+.0722*b;
        const detailScale=Math.max(.72,Math.min(1.34,1+detail/Math.max(72,targetLuminance*2.2)));
        const detailLift=detail*.22;
        const paintedR=Math.max(0,Math.min(255,r*detailScale+detailLift));
        const paintedG=Math.max(0,Math.min(255,g*detailScale+detailLift));
        const paintedB=Math.max(0,Math.min(255,b*detailScale+detailLift));
        const paintMix=a*Math.min(1,state.strength*1.18);
        out.data[p]=src.data[p]*(1-paintMix)+paintedR*paintMix;
        out.data[p+1]=src.data[p+1]*(1-paintMix)+paintedG*paintMix;
        out.data[p+2]=src.data[p+2]*(1-paintMix)+paintedB*paintMix;
      }else{
        const alpha=a*state.strength*.88;
        out.data[p]=src.data[p]*(1-alpha)+r*alpha;
        out.data[p+1]=src.data[p+1]*(1-alpha)+g*alpha;
        out.data[p+2]=src.data[p+2]*(1-alpha)+b*alpha;
        const ink=1-Math.min(src.data[p],src.data[p+1],src.data[p+2])/255;
        const preserve=1-ink*.9;
        out.data[p]*=preserve;out.data[p+1]*=preserve;out.data[p+2]*=preserve;
      }
    }
  }
  ctx.putImageData(out,0,0);
  if(drawOverlay)drawRegionOverlay();
}

function ensureLightingFrameSize(){
  if(lightingFrameCanvas.width===canvas.width && lightingFrameCanvas.height===canvas.height)return;
  lightingFrameCanvas.width=canvas.width;lightingFrameCanvas.height=canvas.height;
}

function rebuildLightingFrame(){
  ensureLightingFrameSize();
  const savedLighting=state.lightingEnabled,savedOriginal=state.showOriginal;
  state.lightingEnabled=true;state.showOriginal=true;
  renderLightingFrame(false);
  lightingFrameCtx.clearRect(0,0,lightingFrameCanvas.width,lightingFrameCanvas.height);
  lightingFrameCtx.drawImage(canvas,0,0);
  state.lightingEnabled=savedLighting;state.showOriginal=savedOriginal;
}

function drawInspectionLens(){
  if(!lensActive || !lensPoint || state.previewMode==='compare' || !aiNormalReady || !aiLineartReady)return;
  const rect=canvas.getBoundingClientRect();
  const radius=Math.max(48,Math.min(140,84*canvas.width/Math.max(1,rect.width)));
  const source=state.previewMode==='original'?lightingFrameCanvas:photoCanvas;
  ctx.save();
  ctx.beginPath();ctx.arc(lensPoint.x,lensPoint.y,radius,0,Math.PI*2);ctx.clip();
  ctx.drawImage(source,0,0);
  ctx.restore();
  ctx.save();
  ctx.beginPath();ctx.arc(lensPoint.x,lensPoint.y,radius,0,Math.PI*2);
  ctx.lineWidth=Math.max(2,canvas.width/550);ctx.strokeStyle='rgba(222,244,255,.94)';ctx.stroke();
  ctx.beginPath();ctx.arc(lensPoint.x,lensPoint.y,radius+5,0,Math.PI*2);
  ctx.lineWidth=Math.max(1,canvas.width/900);ctx.strokeStyle='rgba(88,201,255,.58)';ctx.stroke();
  ctx.restore();
}

function renderPreviewComposite(){
  ctx.clearRect(0,0,canvas.width,canvas.height);
  if(state.previewMode==='original'){
    ctx.drawImage(photoCanvas,0,0);
  }else if(state.previewMode==='compare'){
    ctx.drawImage(photoCanvas,0,0);
    const split=Math.round(comparisonPosition*canvas.width);
    ctx.save();ctx.beginPath();ctx.rect(split,0,canvas.width-split,canvas.height);ctx.clip();
    ctx.drawImage(lightingFrameCanvas,0,0);ctx.restore();
  }else{
    ctx.drawImage(lightingFrameCanvas,0,0);
  }
  drawInspectionLens();
  drawRegionOverlay();
}

function render(){
  if(!state.hasImage)return;
  if(state.previewMode==='guide'){
    renderLightingFrame(true);updateCompareDividerUI();return;
  }
  const guideReady=Boolean(aiNormalData && aiLineartReady);
  if(!guideReady){renderLightingFrame(true);updateCompareDividerUI();return;}
  rebuildLightingFrame();
  renderPreviewComposite();
  updateCompareDividerUI();
}

function cloneCanvas(source){
  const copy=document.createElement('canvas');
  copy.width=source.width;copy.height=source.height;
  copy.getContext('2d').drawImage(source,0,0);
  return copy;
}

function captureZoomSnapshot(){
  return {
    analysisId:currentAnalysisId,
    photoName:document.querySelector('#photoName').textContent,
    canvasInfo:document.querySelector('#canvasInfo').textContent,
    supplementRegions:supplementRegions.map(region=>[...region]),
    photo:cloneCanvas(photoCanvas),
    mask:cloneCanvas(maskCanvas),
    lineart:cloneCanvas(lineartCanvas),
    normals:cloneCanvas(normalSourceCanvas),
    depth:cloneCanvas(depthMapCanvas),
    detailNormals:cloneCanvas(detailNormalSourceCanvas),
    detailNormalReady
  };
}

function drawMaskArtifact(maskImage,width,height){
  const temp=document.createElement('canvas');temp.width=width;temp.height=height;
  const tempCtx=temp.getContext('2d',{willReadFrequently:true});
  tempCtx.drawImage(maskImage,0,0,width,height);
  const pixels=tempCtx.getImageData(0,0,width,height);
  for(let p=0;p<pixels.data.length;p+=4){
    pixels.data[p+3]=pixels.data[p];
    pixels.data[p]=255;pixels.data[p+1]=255;pixels.data[p+2]=255;
  }
  maskCtx.clearRect(0,0,width,height);maskCtx.putImageData(pixels,0,0);
}

function updateZoomUI(info=null){
  const zoomRange=document.querySelector('#zoomRange');
  const zoomValue=document.querySelector('#zoomValue');
  const badge=document.querySelector('#zoomLevelBadge');
  zoomRange.value=String(requestedZoomPercent);
  zoomValue.value=`${(requestedZoomPercent/100).toFixed(requestedZoomPercent%100?2:0)}×`;
  badge.hidden=committedZoomPercent===100;
  if(committedZoomPercent>100){
    const gain=info?.linear_gain?` · 采样 ×${Number(info.linear_gain).toFixed(2)}`:'';
    badge.textContent=`ZOOM ${(committedZoomPercent/100).toFixed(2)}× · 1008 px${gain}`;
  }
  syncInferenceControls();
}

function resetCanvasZoomPreview(){
  canvas.style.transform='';
  depthCanvas.style.transform='';
}

function resetZoomState(){
  clearTimeout(zoomDebounceTimer);zoomDebounceTimer=null;
  zoomStack.length=0;
  committedZoomPercent=100;requestedZoomPercent=100;
  resetCanvasZoomPreview();
  document.querySelector('#zoomRange').value='100';
  document.querySelector('#zoomValue').value='1×';
  document.querySelector('#zoomLevelBadge').hidden=true;
  syncInferenceControls();updateLightUI();
}

async function enterZoomView(payload){
  const [cropImage,maskImage,normalImage,lineartImage,depthImage,detailNormalImage]=await Promise.all([
    loadImage(payload.artifacts.crop),loadImage(payload.artifacts.mask),
    loadImage(payload.artifacts.normals),loadImage(payload.artifacts.lineart),
    loadImage(payload.artifacts.depth),loadImage(payload.artifacts.detail_normals)
  ]);
  zoomStack.push(captureZoomSnapshot());
  const width=normalImage.naturalWidth||normalImage.width;
  const height=normalImage.naturalHeight||normalImage.height;
  sizeCanvases(width,height);
  photoCtx.clearRect(0,0,photoCanvas.width,photoCanvas.height);
  photoCtx.drawImage(cropImage,0,0,photoCanvas.width,photoCanvas.height);
  rebuildPhotoDetail();
  drawMaskArtifact(maskImage,maskCanvas.width,maskCanvas.height);
  normalSourceCtx.drawImage(normalImage,0,0,normalSourceCanvas.width,normalSourceCanvas.height);
  aiNormalReady=true;rebuildAINormals(false);
  detailNormalSourceCtx.drawImage(detailNormalImage,0,0,detailNormalSourceCanvas.width,detailNormalSourceCanvas.height);
  detailNormalReady=true;rebuildDetailNormals(false);
  lineartCtx.drawImage(lineartImage,0,0,lineartCanvas.width,lineartCanvas.height);
  aiLineartReady=true;
  depthMapCtx.drawImage(depthImage,0,0,depthMapCanvas.width,depthMapCanvas.height);
  currentAnalysisId=payload.id;
  committedZoomPercent=requestedZoomPercent;
  resetCanvasZoomPreview();
  supplementRegions=[];
  document.querySelector('#clearRegionsButton').disabled=true;
  state.maskDirty=true;
  if(state.viewMode==='3d')setViewMode('2d');
  rebuildDepthGeometry();
  setGuideReady(true);
  const info=payload.refinement;
  document.querySelector('#photoName').textContent=`Zoom ${(committedZoomPercent/100).toFixed(2)}× · ${info.source_size[0]}×${info.source_size[1]}`;
  document.querySelector('#canvasInfo').textContent=isAndroidApp
    ? '高精度局部视图 · 双指框选可继续精修'
    : '高精度中心视图 · 拖动右侧滑条可重新缩放';
  updateZoomUI(info);updateLightUI();
  render();
}

function restoreZoomSnapshot(snapshot){
  sizeCanvases(snapshot.photo.width,snapshot.photo.height);
  photoCtx.drawImage(snapshot.photo,0,0);
  maskCtx.drawImage(snapshot.mask,0,0);
  lineartCtx.drawImage(snapshot.lineart,0,0);
  normalSourceCtx.drawImage(snapshot.normals,0,0);
  depthMapCtx.drawImage(snapshot.depth,0,0);
  detailNormalSourceCtx.drawImage(snapshot.detailNormals,0,0);
  currentAnalysisId=snapshot.analysisId;
  supplementRegions=snapshot.supplementRegions;
  aiNormalReady=true;aiLineartReady=true;detailNormalReady=snapshot.detailNormalReady;state.maskDirty=true;
  rebuildPhotoDetail();rebuildAINormals(false);rebuildDetailNormals(false);rebuildDepthGeometry();
  document.querySelector('#photoName').textContent=snapshot.photoName;
  document.querySelector('#canvasInfo').textContent=snapshot.canvasInfo;
  document.querySelector('#clearRegionsButton').disabled=supplementRegions.length===0;
  setGuideReady(true);updateZoomUI();updateLightUI();render();
}

function zoomOut(){
  if(state.inferenceBusy||!zoomStack.length)return;
  clearTimeout(zoomDebounceTimer);zoomDebounceTimer=null;
  const rootSnapshot=zoomStack[0];
  zoomStack.length=0;
  committedZoomPercent=100;requestedZoomPercent=100;
  resetCanvasZoomPreview();
  restoreZoomSnapshot(rootSnapshot);
  const status=document.querySelector('#aiStatus');
  status.className='ai-status success';
  status.textContent='已恢复整体视图';
}

function pointerToCanvas(e){
  const r=canvas.getBoundingClientRect();
  return {x:(e.clientX-r.left)*canvas.width/r.width,y:(e.clientY-r.top)*canvas.height/r.height};
}
function drawRegionOverlay(){
  if(!supplementRegions.length && !regionStart)return;
  ctx.save();ctx.lineWidth=Math.max(2,canvas.width/500);ctx.strokeStyle='#d9ff43';ctx.fillStyle='rgba(217,255,67,.09)';ctx.setLineDash([9,6]);
  for(const [x1,y1,x2,y2] of supplementRegions){
    const x=x1*canvas.width,y=y1*canvas.height,w=(x2-x1)*canvas.width,h=(y2-y1)*canvas.height;
    ctx.fillRect(x,y,w,h);ctx.strokeRect(x,y,w,h);
  }
  if(regionStart && regionCurrent){
    const x=Math.min(regionStart.x,regionCurrent.x),y=Math.min(regionStart.y,regionCurrent.y);
    const w=Math.abs(regionCurrent.x-regionStart.x),h=Math.abs(regionCurrent.y-regionStart.y);
    ctx.fillRect(x,y,w,h);ctx.strokeRect(x,y,w,h);
  }
  ctx.restore();
}

function updateCompareDividerUI(){
  const divider=document.querySelector('#compareDivider');
  if(!divider)return;
  const visible=state.previewMode==='compare'&&aiNormalReady&&aiLineartReady;
  divider.hidden=!visible;
  if(!visible)return;
  const frameRect=document.querySelector('#canvasFrame').getBoundingClientRect();
  const canvasRect=canvas.getBoundingClientRect();
  divider.style.left=`${canvasRect.left-frameRect.left+canvasRect.width*comparisonPosition}px`;
  divider.style.top=`${canvasRect.top-frameRect.top}px`;
  divider.style.height=`${canvasRect.height}px`;
  divider.setAttribute('aria-valuenow',String(Math.round(comparisonPosition*100)));
}

function updatePreviewModeUI(){
  for(const [id,mode] of [['originalToggle','original'],['compareToggle','compare'],['lightingToggle','lighting']]){
    const button=document.querySelector(`#${id}`);
    const active=state.previewMode===mode;
    button.classList.toggle('active',active);
    button.classList.toggle('inactive',mode==='lighting'&&state.previewMode==='original');
    button.setAttribute('aria-pressed',String(active));
  }
  stageLight.hidden=state.previewMode==='original';
  updateCompareDividerUI();
}

function setPreviewMode(mode,renderAfter=true){
  if(!['original','compare','lighting'].includes(mode))return;
  if(mode!=='original')lastNonOriginalMode=mode;
  state.previewMode=mode;
  state.lightingEnabled=mode!=='original';
  state.showOriginal=true;
  lensActive=false;lensPoint=null;
  updatePreviewModeUI();
  const messages={
    original:'当前显示原始照片；按住 Alt / Option 可在鼠标附近查看光影。',
    compare:'拖动中间分隔线：左侧是原图，右侧是 NMM 光影。',
    lighting:'当前显示完整 NMM 光影；按住 Alt / Option 可局部查看原图。'
  };
  document.querySelector('#tipText').textContent=messages[mode];
  const canvasMessages={
    original:'原图 · Alt / Option 局部查看光影',
    compare:'拖动分隔线 · 左侧原图 / 右侧光影',
    lighting:'光影 · Alt / Option 局部查看原图'
  };
  document.querySelector('#canvasInfo').textContent=canvasMessages[mode];
  if(renderAfter)render();
}

function setGuideReady(enabled){
  ['originalToggle','compareToggle','lightingToggle'].forEach(id=>{
    document.querySelector(`#${id}`).disabled=!enabled;
  });
  if(!enabled){
    state.previewMode='lighting';state.showOriginal=true;state.lightingEnabled=true;
    lensActive=false;lensPoint=null;
    resetZoomState();
  }
  updatePreviewModeUI();
  syncInferenceControls();
}
function setRegionMode(active){
  state.regionSelecting=active;
  regionStart=null;regionCurrent=null;
  const button=document.querySelector('#regionSelectButton');
  if(!button)return;
  button.classList.toggle('active',active);
  button.textContent=active?'在画面上拖出矩形…':'补充识别区域';
  canvas.classList.toggle('region-selecting',active);
  document.querySelector('#regionHint').textContent=active
    ? '按住鼠标拖出漏识别区域，松开后会自动重新分析。'
    : '模型识别不完整时，点击上方按钮，再在漏识别部位拖出矩形。';
  render();
}
canvas.addEventListener('pointerdown',e=>{
  if(state.inferenceBusy||!state.regionSelecting)return;
  canvas.setPointerCapture(e.pointerId);
  regionStart=pointerToCanvas(e);regionCurrent=regionStart;render();
});
canvas.addEventListener('pointermove',e=>{
  if(regionStart){regionCurrent=pointerToCanvas(e);render();}
});
canvas.addEventListener('pointerup',async()=>{
  if(!regionStart || !regionCurrent)return;
  const x1=Math.max(0,Math.min(regionStart.x,regionCurrent.x))/canvas.width;
  const y1=Math.max(0,Math.min(regionStart.y,regionCurrent.y))/canvas.height;
  const x2=Math.min(canvas.width,Math.max(regionStart.x,regionCurrent.x))/canvas.width;
  const y2=Math.min(canvas.height,Math.max(regionStart.y,regionCurrent.y))/canvas.height;
  regionStart=null;regionCurrent=null;
  if((x2-x1)*canvas.width<12 || (y2-y1)*canvas.height<12){setRegionMode(false);return;}
  supplementRegions.push([x1,y1,x2,y2]);
  document.querySelector('#clearRegionsButton').disabled=false;
  setRegionMode(false);
  await runAIAnalysis(true);
});
canvas.addEventListener('pointercancel',()=>{regionStart=null;regionCurrent=null;render();});

const compareDivider=document.querySelector('#compareDivider');
let dividerDragging=false;
function setComparisonPositionFromEvent(event){
  const rect=canvas.getBoundingClientRect();
  comparisonPosition=Math.max(0,Math.min(1,(event.clientX-rect.left)/Math.max(1,rect.width)));
  renderPreviewComposite();updateCompareDividerUI();
}
compareDivider.addEventListener('pointerdown',event=>{
  event.preventDefault();event.stopPropagation();dividerDragging=true;
  compareDivider.setPointerCapture(event.pointerId);setComparisonPositionFromEvent(event);
});
compareDivider.addEventListener('pointermove',event=>{if(dividerDragging)setComparisonPositionFromEvent(event);});
compareDivider.addEventListener('pointerup',event=>{
  dividerDragging=false;compareDivider.releasePointerCapture?.(event.pointerId);
});
compareDivider.addEventListener('pointercancel',()=>{dividerDragging=false;});
compareDivider.addEventListener('keydown',event=>{
  const amount=event.shiftKey?.1:.04;
  if(event.key==='Home')comparisonPosition=0;
  else if(event.key==='End')comparisonPosition=1;
  else if(event.key==='ArrowLeft')comparisonPosition=Math.max(0,comparisonPosition-amount);
  else if(event.key==='ArrowRight')comparisonPosition=Math.min(1,comparisonPosition+amount);
  else return;
  event.preventDefault();renderPreviewComposite();updateCompareDividerUI();
});

function pinchDistance(points){
  return Math.hypot(points[0].clientX-points[1].clientX,points[0].clientY-points[1].clientY);
}

function pinchViewport(points,bounds){
  const left=Math.max(bounds.left,Math.min(points[0].clientX,points[1].clientX));
  const top=Math.max(bounds.top,Math.min(points[0].clientY,points[1].clientY));
  const right=Math.min(bounds.right,Math.max(points[0].clientX,points[1].clientX));
  const bottom=Math.min(bounds.bottom,Math.max(points[0].clientY,points[1].clientY));
  return [
    (left-bounds.left)/Math.max(1,bounds.width),
    (top-bounds.top)/Math.max(1,bounds.height),
    (right-bounds.left)/Math.max(1,bounds.width),
    (bottom-bounds.top)/Math.max(1,bounds.height)
  ];
}

function resetPinchPreview(){
  canvas.style.transform='';
  canvas.style.transformOrigin='';
  depthCanvas.style.transform='';
  depthCanvas.style.transformOrigin='';
  if(pinchViewportOverlay)pinchViewportOverlay.hidden=true;
  requestAnimationFrame(updateCompareDividerUI);
}

async function commitPinchGesture(gesture){
  resetPinchPreview();
  if(!gesture||state.inferenceBusy||!currentAnalysisId)return;
  const status=document.querySelector('#aiStatus');
  if(gesture.ratio<.88){
    if(zoomStack.length)zoomOut();
    else{
      status.className='ai-status';
      status.textContent='已经是整体视图';
    }
    return;
  }
  if(gesture.ratio<1.08)return;
  const region=gesture.region;
  const width=region[2]-region[0],height=region[3]-region[1];
  if(width*canvas.width<48||height*canvas.height<48){
    status.className='ai-status error';
    status.textContent='双指框选区域太窄，请让两个触点形成更大的对角矩形';
    return;
  }
  const localGain=1/Math.max(width,height);
  const rawTarget=Math.max(committedZoomPercent+25,committedZoomPercent*localGain);
  requestedZoomPercent=Math.min(300,Math.round(rawTarget/25)*25);
  updateZoomUI();
  status.className='ai-status busy';
  status.textContent='正在把双指对角矩形作为新视口，以 1008 px 重新计算深度…';
  await runLocalRefinement(region);
  if(!zoomStack.length){
    committedZoomPercent=100;requestedZoomPercent=100;updateZoomUI();
  }
}

if(isAndroidApp){
  canvas.addEventListener('pointerdown',event=>{
    if(event.pointerType!=='touch'||state.regionSelecting||state.inferenceBusy||!currentAnalysisId)return;
    pinchPointers.set(event.pointerId,{clientX:event.clientX,clientY:event.clientY});
    canvas.setPointerCapture?.(event.pointerId);
    if(pinchPointers.size!==2)return;
    clearTimeout(lensHoldTimer);lensHoldTimer=null;lensTouchStart=null;
    if(lensActive||lensTouchActive)closeInspectionLens();
    const points=[...pinchPointers.values()];
    const bounds=canvas.getBoundingClientRect();
    pinchGesture={
      pointerIds:new Set(pinchPointers.keys()),bounds,
      startDistance:Math.max(1,pinchDistance(points)),ratio:1,
      region:pinchViewport(points,bounds)
    };
  });
  canvas.addEventListener('pointermove',event=>{
    if(!pinchPointers.has(event.pointerId))return;
    pinchPointers.set(event.pointerId,{clientX:event.clientX,clientY:event.clientY});
    if(!pinchGesture||pinchPointers.size<2)return;
    event.preventDefault();
    const points=[...pinchPointers.values()].slice(0,2);
    pinchGesture.ratio=pinchDistance(points)/pinchGesture.startDistance;
    pinchGesture.region=pinchViewport(points,pinchGesture.bounds);
    const frameBounds=document.querySelector('#canvasFrame').getBoundingClientRect();
    const left=Math.min(points[0].clientX,points[1].clientX)-frameBounds.left;
    const top=Math.min(points[0].clientY,points[1].clientY)-frameBounds.top;
    pinchViewportOverlay.hidden=false;
    pinchViewportOverlay.style.left=`${left}px`;
    pinchViewportOverlay.style.top=`${top}px`;
    pinchViewportOverlay.style.width=`${Math.abs(points[0].clientX-points[1].clientX)}px`;
    pinchViewportOverlay.style.height=`${Math.abs(points[0].clientY-points[1].clientY)}px`;
    const midpointX=(points[0].clientX+points[1].clientX)/2-pinchGesture.bounds.left;
    const midpointY=(points[0].clientY+points[1].clientY)/2-pinchGesture.bounds.top;
    const previewScale=Math.max(.78,Math.min(1.55,pinchGesture.ratio));
    canvas.style.transformOrigin=`${midpointX}px ${midpointY}px`;
    canvas.style.transform=`scale(${previewScale})`;
    const status=document.querySelector('#aiStatus');
    status.className='ai-status';
    status.textContent=pinchGesture.ratio>=1
      ? '松手后将双指触点的对角矩形作为局部视口重新推理'
      : '继续收拢并松手可退出局部视图';
  });
  const finishPinch=event=>{
    if(!pinchPointers.has(event.pointerId))return;
    const completed=pinchGesture&&pinchGesture.pointerIds.has(event.pointerId)?{...pinchGesture}:null;
    pinchPointers.delete(event.pointerId);
    if(!completed)return;
    pinchGesture=null;
    pinchPointers.clear();
    commitPinchGesture(completed);
  };
  canvas.addEventListener('pointerup',finishPinch);
  canvas.addEventListener('pointercancel',finishPinch);
}

function refreshLensComposite(){
  if(aiNormalReady&&aiLineartReady&&state.previewMode!=='guide')renderPreviewComposite();
  else render();
}
function setLensFromPointer(event){
  lensPoint=pointerToCanvas(event);lensActive=true;refreshLensComposite();
}
function closeInspectionLens(){
  clearTimeout(lensHoldTimer);lensHoldTimer=null;lensTouchStart=null;
  if(!lensActive&&!lensTouchActive)return;
  lensActive=false;lensTouchActive=false;lensPoint=null;refreshLensComposite();
}
canvas.addEventListener('pointerdown',event=>{
  if(event.pointerType!=='touch'||state.regionSelecting||state.previewMode==='compare'||!aiNormalReady
    ||(isAndroidApp&&pinchPointers.size>1))return;
  lensTouchStart={x:event.clientX,y:event.clientY};
  lensHoldTimer=setTimeout(()=>{
    lensTouchActive=true;canvas.setPointerCapture(event.pointerId);setLensFromPointer(event);
  },420);
});
canvas.addEventListener('pointermove',event=>{
  if(state.regionSelecting||state.previewMode==='compare'||(isAndroidApp&&pinchGesture))return;
  if(lensTouchStart&&!lensTouchActive&&Math.hypot(event.clientX-lensTouchStart.x,event.clientY-lensTouchStart.y)>9){
    clearTimeout(lensHoldTimer);lensHoldTimer=null;lensTouchStart=null;
  }
  if(lensTouchActive){setLensFromPointer(event);return;}
  if(event.pointerType!=='touch'&&event.altKey&&aiNormalReady){setLensFromPointer(event);}
  else if(lensActive)closeInspectionLens();
});
canvas.addEventListener('pointerup',event=>{
  clearTimeout(lensHoldTimer);lensHoldTimer=null;lensTouchStart=null;
  if(lensTouchActive){canvas.releasePointerCapture?.(event.pointerId);closeInspectionLens();}
});
canvas.addEventListener('pointerleave',event=>{if(event.pointerType!=='touch'&&lensActive)closeInspectionLens();});
document.addEventListener('keyup',event=>{if(event.key==='Alt'&&lensActive&&!lensTouchActive)closeInspectionLens();});

function previewZoom(target){
  const range=document.querySelector('#zoomRange');
  const min=+range.min,max=+range.max,step=+range.step;
  target=Math.max(min,Math.min(max,Math.round(target/step)*step));
  requestedZoomPercent=target;
  range.value=String(target);
  document.querySelector('#zoomValue').value=`${(target/100).toFixed(target%100?2:0)}×`;
  const previewScale=target/committedZoomPercent;
  canvas.style.transform=`scale(${previewScale})`;
  depthCanvas.style.transform=`scale(${previewScale})`;
  requestAnimationFrame(updateCompareDividerUI);
  clearTimeout(zoomDebounceTimer);
  const status=document.querySelector('#aiStatus');
  status.className='ai-status';
  status.textContent=target===committedZoomPercent?'缩放比例未改变':`Zoom 预览 ${(target/100).toFixed(2)}× · 停止拖动后重新计算`;
  syncInferenceControls();
  zoomDebounceTimer=setTimeout(()=>commitZoom(target),ZOOM_DEBOUNCE_MS);
}

async function commitZoom(target){
  zoomDebounceTimer=null;
  resetCanvasZoomPreview();
  if(state.inferenceBusy||!currentAnalysisId||target===committedZoomPercent)return;
  if(target===100){zoomOut();return;}
  if(zoomStack.length){
    const rootSnapshot=zoomStack[0];
    zoomStack.length=0;
    committedZoomPercent=100;
    restoreZoomSnapshot(rootSnapshot);
  }
  requestedZoomPercent=target;
  updateZoomUI();
  const size=100/target;
  const edge=(1-size)/2;
  await runLocalRefinement([edge,edge,1-edge,1-edge]);
  if(!zoomStack.length){
    committedZoomPercent=100;requestedZoomPercent=100;
    updateZoomUI();
  }
}

canvas.addEventListener('wheel',event=>{
  const range=document.querySelector('#zoomRange');
  if(range.disabled)return;
  event.preventDefault();
  previewZoom(+range.value+(event.deltaY<0?+range.step:-range.step));
},{passive:false});

function setLightFromEvent(e, element){
  const r=element.getBoundingClientRect();
  state.lightX=Math.max(-.9,Math.min(.9,(e.clientX-(r.left+r.width/2))/(r.width/2)));
  state.lightY=Math.max(-.9,Math.min(.9,(e.clientY-(r.top+r.height/2))/(r.height/2)));
  updateLightUI();render();
}
const stageLight=document.querySelector('#stageLight');
const frame=document.querySelector('#canvasFrame');
stageLight.addEventListener('pointerdown',e=>{
  e.stopPropagation(); state.lightDragging=true; stageLight.setPointerCapture(e.pointerId);
});
stageLight.addEventListener('pointermove',e=>{ if(state.lightDragging)setLightFromEvent(e,frame); });
stageLight.addEventListener('pointerup',()=>state.lightDragging=false);

function updateLightUI(){
  const x=(state.lightX+1)*50,y=(state.lightY+1)*50;
  stageLight.style.left=x+'%';stageLight.style.top=y+'%';
}

document.querySelector('#imageInput').addEventListener('change',e=>{
  if(state.inferenceBusy){e.target.value='';return;}
  const file=e.target.files[0]; if(!file)return;
  currentUploadFile=file;
  const img=new Image(); img.onload=()=>{ sizeCanvases(img.width,img.height);photoCtx.drawImage(img,0,0,photoCanvas.width,photoCanvas.height);rebuildPhotoDetail();maskCtx.clearRect(0,0,maskCanvas.width,maskCanvas.height);aiNormalData=null;aiNormalReady=false;aiLineartReady=false;currentAnalysisId=null;supplementRegions=[];setRegionMode(false);setGuideReady(false);document.querySelector('#clearRegionsButton').disabled=true;state.maskDirty=true;state.hasImage=true;document.querySelector('#photoName').textContent=file.name;document.querySelector('#aiStatus').textContent='照片已载入，正在自动分析…';render();URL.revokeObjectURL(img.src);requestAnimationFrame(()=>runAIAnalysis(false)); }; img.src=URL.createObjectURL(file);
});
document.querySelector('#demoButton').onclick=createDemo;
document.querySelector('#regionSelectButton').onclick=()=>setRegionMode(!state.regionSelecting);
document.querySelector('#zoomRange').addEventListener('input',event=>previewZoom(+event.target.value));
document.querySelector('#zoomInButton').onclick=()=>{const range=document.querySelector('#zoomRange');previewZoom(+range.value + +range.step);};
document.querySelector('#zoomOutButton').onclick=()=>{const range=document.querySelector('#zoomRange');previewZoom(+range.value - +range.step);};
document.querySelector('#clearRegionsButton').onclick=async()=>{supplementRegions=[];document.querySelector('#clearRegionsButton').disabled=true;if(aiNormalReady)await runAIAnalysis(false);else render();};

function bindButtons(selector,key,attr,after=render){
  document.querySelectorAll(selector).forEach(btn=>btn.addEventListener('click',()=>{ document.querySelectorAll(selector).forEach(b=>b.classList.remove('active'));btn.classList.add('active');state[key]=btn.dataset[attr];after(); }));
}

function applyLightingPreset(name){
  const preset=LIGHTING_PRESETS[name];if(!preset)return;
  state.lightPreset=name;state.fillLight=preset.fillLight;state.detailLight=preset.detailLight;
  document.querySelectorAll('#lightingPresets button').forEach(button=>button.classList.toggle('active',button.dataset.lighting===name));
  document.querySelector('#fillLight').value=Math.round(state.fillLight*100);
  document.querySelector('#fillLightValue').value=Math.round(state.fillLight*100)+'%';
  document.querySelector('#detailLight').value=Math.round(state.detailLight*100);
  document.querySelector('#detailLightValue').value=Math.round(state.detailLight*100)+'%';
  render();
}
document.querySelectorAll('#lightingPresets button').forEach(button=>button.addEventListener('click',()=>applyLightingPreset(button.dataset.lighting)));

function applyArtDirection(name){
  if(!ART_DIRECTION_PRESETS[name])return;
  state.artDirection=name;
  document.querySelectorAll('#artDirectionPresets button').forEach(button=>button.classList.toggle('active',button.dataset.artDirection===name));
  updateSurfaceStatus(surfaceAnalysis);
  render();
}
document.querySelectorAll('#artDirectionPresets button').forEach(button=>button.addEventListener('click',()=>applyArtDirection(button.dataset.artDirection)));

function updatePrimaryLegend(){
  const palette=getActivePalette();
  const colors=Array.from({length:state.steps},(_,index)=>{
    const position=index/(state.steps-1)*(palette.length-1);
    const low=Math.floor(position),high=Math.min(palette.length-1,low+1),mix=position-low;
    return palette[low].map((value,channel)=>Math.round(value*(1-mix)+palette[high][channel]*mix));
  });
  const legend=document.querySelector('#primaryLegendSwatches');
  legend.innerHTML=colors.map((color,index)=>
    `<i title="第 ${index+1} 阶 · rgb(${color.join(', ')})" style="background:rgb(${color.join(',')})"></i>`
  ).join('');
  legend.setAttribute('aria-label',`${state.steps} 个一次反射色阶`);
}
function updateMaterialUI(){
  document.querySelectorAll('#materialPicker button').forEach(button=>button.classList.toggle('active',button.dataset.material===state.material));
  document.querySelectorAll('#tintPicker button').forEach(button=>button.classList.toggle('active',button.dataset.tint===state.tint));
  document.querySelector('#materialComboName').textContent=`${MATERIAL_PRESETS[state.material].name} · ${TINT_PRESETS[state.tint].name}`;
  updatePrimaryLegend();render();
}
document.querySelectorAll('#materialPicker button').forEach(button=>button.addEventListener('click',()=>{
  state.material=button.dataset.material;
  state.gloss=MATERIAL_PRESETS[state.material].gloss;
  document.querySelector('#gloss').value=state.gloss;
  document.querySelector('#glossValue').value=(state.gloss<34?'粗糙 ':state.gloss<72?'半光 ':'镜面 ')+state.gloss+'%';
  updateMaterialUI();
}));
document.querySelectorAll('#tintPicker button').forEach(button=>button.addEventListener('click',()=>{
  state.tint=button.dataset.tint;updateMaterialUI();
}));
const originalToggle=document.querySelector('#originalToggle');
const compareToggle=document.querySelector('#compareToggle');
const lightingToggle=document.querySelector('#lightingToggle');
let originalPeekTimer=null,originalPeeking=false,suppressOriginalClick=false,peekReturnMode='lighting';

originalToggle.addEventListener('pointerdown',()=>{
  if(originalToggle.disabled||state.previewMode==='original')return;
  peekReturnMode=state.previewMode;
  originalPeekTimer=setTimeout(()=>{
    originalPeeking=true;suppressOriginalClick=true;setPreviewMode('original');
  },220);
});
function finishOriginalPeek(){
  clearTimeout(originalPeekTimer);originalPeekTimer=null;
  if(originalPeeking){
    originalPeeking=false;setPreviewMode(peekReturnMode);
    setTimeout(()=>{suppressOriginalClick=false;},400);
  }
}
originalToggle.addEventListener('pointerup',finishOriginalPeek);
originalToggle.addEventListener('pointercancel',finishOriginalPeek);
originalToggle.addEventListener('pointerleave',finishOriginalPeek);
originalToggle.addEventListener('click',event=>{
  if(suppressOriginalClick){event.preventDefault();suppressOriginalClick=false;return;}
  setPreviewMode('original');
});
compareToggle.addEventListener('click',()=>setPreviewMode('compare'));
lightingToggle.addEventListener('click',()=>setPreviewMode('lighting'));

document.addEventListener('keydown',event=>{
  if(event.key!=='\\' || event.repeat || !aiNormalReady || !aiLineartReady || /INPUT|TEXTAREA|SELECT/.test(event.target.tagName))return;
  event.preventDefault();
  setPreviewMode(state.previewMode==='original'?lastNonOriginalMode:'original');
});

function loadImage(url){
  return new Promise((resolve,reject)=>{const img=new Image();img.onload=()=>resolve(img);img.onerror=reject;img.src=url+'?t='+Date.now();});
}

function rebuildAINormals(renderAfter=true){
  if(!aiNormalReady)return;
  normalFilteredCtx.save();
  normalFilteredCtx.clearRect(0,0,normalFilteredCanvas.width,normalFilteredCanvas.height);
  normalFilteredCtx.filter=state.normalSmooth>0?`blur(${state.normalSmooth}px)`:'none';
  normalFilteredCtx.drawImage(normalSourceCanvas,0,0);
  normalFilteredCtx.restore();
  aiNormalData=normalFilteredCtx.getImageData(0,0,normalFilteredCanvas.width,normalFilteredCanvas.height).data;
  surfaceAnalysis=null;
  if(renderAfter)render();
}

function rebuildDetailNormals(renderAfter=true){
  if(!detailNormalReady){detailNormalData=null;return;}
  detailNormalFilteredCtx.save();
  detailNormalFilteredCtx.clearRect(0,0,detailNormalFilteredCanvas.width,detailNormalFilteredCanvas.height);
  const radius=Math.max(0,(state.detailScale-1)*.42);
  detailNormalFilteredCtx.filter=radius>0?`blur(${radius}px)`:'none';
  detailNormalFilteredCtx.drawImage(detailNormalSourceCanvas,0,0);
  detailNormalFilteredCtx.restore();
  detailNormalData=detailNormalFilteredCtx.getImageData(0,0,detailNormalFilteredCanvas.width,detailNormalFilteredCanvas.height).data;
  if(renderAfter)render();
}

async function performLocalRefinement(region){
  const status=document.querySelector('#aiStatus');
  status.className='ai-status busy';
  status.textContent='正在把当前 Zoom 视口以 1008 px 重新计算深度与光影…';
  if(!currentAnalysisId)throw new Error('请先完成一次整体分析');
  let payload;
  if(isAndroidApp){
    payload=await invokeAndroid('refine',currentAnalysisId,JSON.stringify(region));
  }else{
    const form=new FormData();
    form.append('analysis_id',currentAnalysisId);
    form.append('region',JSON.stringify(region));
    const response=await fetch('/api/refine',{method:'POST',body:form});
    payload=await response.json();
    if(!response.ok)throw new Error(payload.detail||'局部精修失败');
  }
  await enterZoomView(payload);
  const info=payload.refinement;
  await new Promise(resolve=>setTimeout(resolve,GPU_COOLDOWN_MS));
  return {
    succeeded:true,
    finalStatus:`Zoom ${zoomStack.length} 完成 · ${info.source_size[0]}×${info.source_size[1]} → ${info.output_size[0]}×${info.output_size[1]} · 线性采样 ×${Number(info.linear_gain).toFixed(2)}`
  };
}

async function runLocalRefinement(region){
  if(localInferenceRunning||!currentAnalysisId)return;
  pendingLocalRegion=region;
  try{await runAIAnalysis(false);}finally{pendingLocalRegion=null;}
}

async function performAIAnalysis(isSupplement=false){
  if(pendingLocalRegion)return performLocalRefinement(pendingLocalRegion);
  const status=document.querySelector('#aiStatus');
  status.className='ai-status busy';
  status.textContent=isSupplement
    ? '正在按框选区域增补主体并重建高精度形体…'
    : '正在进行最高精度主体聚焦估算，约需 2–3 分钟…';
  let finalStatus = '';
  let succeeded = false;
  try{
    let file=currentUploadFile;
    let prompt='miniature figure';
    if(!file){
      const blob=await new Promise(resolve=>photoCanvas.toBlob(resolve,'image/png'));
      file=new File([blob],'nmm-demo.png',{type:'image/png'});
      // The built-in demo is an abstract shield, not a photographed figure.
      prompt='circle';
    }
    let payload;
    if(isAndroidApp){
      payload=await invokeAndroid(
        'analyze',
        await blobToDataURL(file),
        file.name||'miniature.png',
        JSON.stringify(supplementRegions),
        prompt
      );
    }else{
      const form=new FormData();form.append('file',file);form.append('prompt',prompt);form.append('regions',JSON.stringify(supplementRegions));
      const response=await fetch('/api/analyze',{method:'POST',body:form});
      payload=await response.json();
      if(!response.ok)throw new Error(payload.detail||'分析失败');
    }
    if(!isSupplement)resetZoomState();
    currentAnalysisId=payload.id;
    const [maskImage,normalImage,lineartImage,depthImage]=await Promise.all([
      loadImage(payload.artifacts.mask),loadImage(payload.artifacts.normals),
      loadImage(payload.artifacts.lineart),loadImage(payload.artifacts.depth)
    ]);
    const temp=document.createElement('canvas');temp.width=canvas.width;temp.height=canvas.height;
    const tempCtx=temp.getContext('2d',{willReadFrequently:true});tempCtx.drawImage(maskImage,0,0,temp.width,temp.height);
    const pixels=tempCtx.getImageData(0,0,temp.width,temp.height);
    for(let p=0;p<pixels.data.length;p+=4){pixels.data[p+3]=pixels.data[p];pixels.data[p]=255;pixels.data[p+1]=255;pixels.data[p+2]=255;}
    maskCtx.clearRect(0,0,maskCanvas.width,maskCanvas.height);maskCtx.putImageData(pixels,0,0);
    normalSourceCtx.clearRect(0,0,normalSourceCanvas.width,normalSourceCanvas.height);normalSourceCtx.drawImage(normalImage,0,0,normalSourceCanvas.width,normalSourceCanvas.height);aiNormalReady=true;rebuildAINormals(false);
    lineartCtx.clearRect(0,0,lineartCanvas.width,lineartCanvas.height);lineartCtx.drawImage(lineartImage,0,0,lineartCanvas.width,lineartCanvas.height);aiLineartReady=true;
    depthMapCtx.clearRect(0,0,depthMapCanvas.width,depthMapCanvas.height);depthMapCtx.drawImage(depthImage,0,0,depthMapCanvas.width,depthMapCanvas.height);
    rebuildDepthGeometry();
    setGuideReady(true);state.maskDirty=true;
    document.querySelector('#tipText').textContent='一次反射使用当前金属色阶；青蓝色表示二次反射，暖橙色表示三次反射。';
    document.querySelector('#photoName').textContent='AI 形体指引 · '+file.name;
    const precision=payload.depth_precision;
    const precisionNote=precision?.mode==='subject_focus'
      ? ` · 主体线性采样约 ×${Number(precision.effective_gain).toFixed(2)}`
      : ' · 主体已占满画面';
    finalStatus=isSupplement
      ? `增补识别完成 · 已合并 ${supplementRegions.length} 个区域${precisionNote}`
      : `分析完成${precisionNote} · 可拖动灯光继续调整`;
    succeeded=true;render();
  }catch(error){
    if(isSupplement)supplementRegions.pop();
    document.querySelector('#clearRegionsButton').disabled=supplementRegions.length===0;
    finalStatus=error instanceof TypeError && /fetch/i.test(error.message)
      ? '本地 AI 服务已停止，请重启服务后再试'
      : error.message;
    render();
  }
  status.className='ai-status busy';
  status.textContent=succeeded?'分析完成 · 正在安全释放显存…':'正在等待显存状态稳定…';
  await new Promise(resolve=>setTimeout(resolve,GPU_COOLDOWN_MS));
  return {succeeded, finalStatus};
}

async function runAIAnalysis(isSupplement=false){
  const status=document.querySelector('#aiStatus');
  if(localInferenceRunning)return;
  const execute=async()=>{
    localInferenceRunning=true;
    remoteInferenceOwner=null;
    clearTimeout(remoteInferenceTimer);
    syncInferenceControls();
    broadcastInference('busy');
    analysisHeartbeat=setInterval(()=>broadcastInference('busy'),ANALYSIS_HEARTBEAT_MS);
    let result={succeeded:false,finalStatus:'分析未完成，请稍后重试'};
    try{
      result=await performAIAnalysis(isSupplement);
    }catch(error){
      result.finalStatus=error.message||result.finalStatus;
    }finally{
      clearInterval(analysisHeartbeat);
      analysisHeartbeat=null;
      localInferenceRunning=false;
      broadcastInference('released');
      syncInferenceControls();
    }
    status.className=`ai-status ${result.succeeded?'success':'error'}`;
    status.textContent=result.finalStatus;
  };
  if(navigator.locks?.request){
    await navigator.locks.request(ANALYSIS_LOCK_NAME,{ifAvailable:true},async lock=>{
      if(!lock){
        status.className='ai-status busy';
        status.textContent='另一个页面正在进行 AI 推理，请稍候…';
        return;
      }
      await execute();
    });
  }else if(!state.inferenceBusy){
    await execute();
  }
}
[['gloss','gloss','glossValue',v=>(v<34?'粗糙 ':v<72?'半光 ':'镜面 ')+v+'%'],['strength','strength','strengthValue',v=>v+'%'],['reflections','reflections','reflectionsValue',v=>v+' 层'],['bounce','bounce','bounceValue',v=>v+'%'],['steps','steps','stepsValue',v=>v+' 阶']].forEach(([id,key,out,fmt])=>{
  const el=document.querySelector('#'+id);el.addEventListener('input',()=>{state[key]=(key==='strength'||key==='bounce')?+el.value/100:+el.value;document.querySelector('#'+out).value=fmt(+el.value);if(key==='steps')updatePrimaryLegend();render();if(key==='gloss')renderDepthPreview();});
});

function setCustomLighting(){
  state.lightPreset='custom';
  document.querySelectorAll('#lightingPresets button').forEach(button=>button.classList.remove('active'));
}
const fillLightControl=document.querySelector('#fillLight');
fillLightControl.addEventListener('input',event=>{
  state.fillLight=+event.target.value/100;setCustomLighting();
  document.querySelector('#fillLightValue').value=event.target.value+'%';
  render();renderDepthPreview();
});
const detailLightControl=document.querySelector('#detailLight');
detailLightControl.addEventListener('input',event=>{
  state.detailLight=+event.target.value/100;setCustomLighting();
  document.querySelector('#detailLightValue').value=event.target.value+'%';
  render();renderDepthPreview();
});

const normalSmoothControl=document.querySelector('#normalSmooth');
normalSmoothControl.addEventListener('input',event=>{
  state.normalSmooth=+event.target.value;
  document.querySelector('#normalSmoothValue').value=state.normalSmooth+' px';
  rebuildAINormals();if(aiNormalReady&&depthMapCanvas.width)rebuildDepthGeometry();renderDepthPreview();
});

const detailScaleControl=document.querySelector('#detailScale');
detailScaleControl.addEventListener('input',event=>{
  state.detailScale=+event.target.value;
  document.querySelector('#detailScaleValue').value=state.detailScale+' px';
  rebuildDetailNormals();if(aiNormalReady&&depthMapCanvas.width)rebuildDepthGeometry();renderDepthPreview();
});

const detailStrengthControl=document.querySelector('#detailStrength');
detailStrengthControl.addEventListener('input',event=>{
  state.detailStrength=+event.target.value/100;
  document.querySelector('#detailStrengthValue').value=event.target.value+'%';
  render();if(aiNormalReady&&depthMapCanvas.width)rebuildDepthGeometry();renderDepthPreview();
});

function canvasToBlob(source,type='image/png'){
  return new Promise((resolve,reject)=>source.toBlob(
    blob=>blob?resolve(blob):reject(new Error('无法生成导出图片')),
    type
  ));
}

async function downloadBlob(blob,filename){
  if(isAndroidApp){
    await invokeAndroid('saveFile',await blobToDataURL(blob),filename,blob.type||'application/octet-stream');
    return;
  }
  const url=URL.createObjectURL(blob);
  const link=document.createElement('a');
  link.href=url;link.download=filename;
  document.body.appendChild(link);link.click();link.remove();
  setTimeout(()=>URL.revokeObjectURL(url),2000);
}

function exportFilename(kind,extension){
  const now=new Date();
  const stamp=[now.getFullYear(),String(now.getMonth()+1).padStart(2,'0'),String(now.getDate()).padStart(2,'0')].join('-');
  return `nmm-${kind}-${stamp}.${extension}`;
}

function exportLightPositionLabel(){
  const horizontal=state.lightX<-.24?'左':state.lightX>.24?'右':'';
  const vertical=state.lightY<-.24?'上':state.lightY>.24?'下':'';
  return horizontal||vertical?`${horizontal}${vertical}`:'正面';
}

function fillRoundedRect(context,x,y,width,height,radius){
  const r=Math.min(radius,width/2,height/2);
  context.beginPath();
  context.moveTo(x+r,y);
  context.arcTo(x+width,y,x+width,y+height,r);
  context.arcTo(x+width,y+height,x,y+height,r);
  context.arcTo(x,y+height,x,y,r);
  context.arcTo(x,y,x+width,y,r);
  context.closePath();
  context.fill();
}

function annotateExportFrame(source,modeLabel){
  const output=cloneCanvas(source);
  const out=output.getContext('2d');
  const width=output.width,height=output.height;
  const unit=Math.max(1,Math.min(width,height)/900);
  const markerRadius=Math.max(14,18*unit);
  const sourceX=Math.max(markerRadius*1.4,Math.min(width-markerRadius*1.4,(state.lightX+1)*.5*width));
  const sourceY=Math.max(markerRadius*1.4,Math.min(height-markerRadius*1.4,(state.lightY+1)*.5*height));
  const targetX=cachedBox?(cachedBox.minX+cachedBox.maxX)/2:width/2;
  const targetY=cachedBox?(cachedBox.minY+cachedBox.maxY)/2:height/2;
  let dx=targetX-sourceX,dy=targetY-sourceY;
  let distance=Math.hypot(dx,dy);
  if(distance<Math.min(width,height)*.12){
    // A centred handle represents a frontal light. Give it a visible on-image
    // direction without pretending it is a lateral source.
    dx=0;dy=Math.min(width,height)*.2;distance=Math.abs(dy);
  }
  const ux=dx/distance,uy=dy/distance;
  const startX=sourceX+ux*markerRadius*1.35,startY=sourceY+uy*markerRadius*1.35;
  const arrowLength=Math.min(distance-markerRadius*2.4,Math.min(width,height)*.3);
  const endX=startX+ux*Math.max(markerRadius*2.2,arrowLength);
  const endY=startY+uy*Math.max(markerRadius*2.2,arrowLength);
  const accent='#d9ff43';

  out.save();
  out.lineCap='round';out.lineJoin='round';
  out.shadowColor='rgba(0,0,0,.78)';out.shadowBlur=8*unit;
  out.strokeStyle='rgba(217,255,67,.96)';out.lineWidth=Math.max(3,3.2*unit);
  out.setLineDash([10*unit,7*unit]);
  out.beginPath();out.moveTo(startX,startY);out.lineTo(endX,endY);out.stroke();
  out.setLineDash([]);
  const arrowSize=Math.max(10,12*unit),angle=Math.atan2(uy,ux);
  out.fillStyle=accent;
  out.beginPath();
  out.moveTo(endX,endY);
  out.lineTo(endX-arrowSize*Math.cos(angle-.55),endY-arrowSize*Math.sin(angle-.55));
  out.lineTo(endX-arrowSize*Math.cos(angle+.55),endY-arrowSize*Math.sin(angle+.55));
  out.closePath();out.fill();

  // Light source marker: a bright core plus eight rays, matching the draggable
  // sun in the editor while remaining legible over either a dark or light photo.
  out.strokeStyle=accent;out.lineWidth=Math.max(2,2.6*unit);
  for(let index=0;index<8;index++){
    const rayAngle=index*Math.PI/4;
    out.beginPath();
    out.moveTo(sourceX+Math.cos(rayAngle)*markerRadius*1.18,sourceY+Math.sin(rayAngle)*markerRadius*1.18);
    out.lineTo(sourceX+Math.cos(rayAngle)*markerRadius*1.55,sourceY+Math.sin(rayAngle)*markerRadius*1.55);
    out.stroke();
  }
  out.fillStyle='rgba(8,14,22,.9)';
  out.beginPath();out.arc(sourceX,sourceY,markerRadius,0,Math.PI*2);out.fill();
  out.strokeStyle=accent;out.beginPath();out.arc(sourceX,sourceY,markerRadius,0,Math.PI*2);out.stroke();
  out.fillStyle=accent;out.textAlign='center';out.textBaseline='middle';
  out.font=`800 ${Math.max(11,12*unit)}px Inter,"PingFang SC",sans-serif`;
  out.fillText('L1',sourceX,sourceY+.5*unit);
  out.restore();

  const paddingX=12*unit,pillHeight=Math.max(28,31*unit);
  out.textBaseline='middle';out.textAlign='left';
  out.font=`700 ${Math.max(11,12*unit)}px Inter,"PingFang SC",sans-serif`;
  const lightText=`主光源 · ${exportLightPositionLabel()}`;
  const lightWidth=out.measureText(lightText).width+paddingX*2;
  let labelX=sourceX<width*.56?sourceX+markerRadius*1.8:sourceX-markerRadius*1.8-lightWidth;
  let labelY=sourceY-pillHeight/2;
  labelX=Math.max(8*unit,Math.min(width-lightWidth-8*unit,labelX));
  labelY=Math.max(8*unit,Math.min(height-pillHeight-8*unit,labelY));
  out.fillStyle='rgba(5,11,18,.88)';fillRoundedRect(out,labelX,labelY,lightWidth,pillHeight,8*unit);
  out.strokeStyle='rgba(217,255,67,.68)';out.lineWidth=Math.max(1,unit);
  out.strokeRect(labelX+.5,labelY+.5,lightWidth-1,pillHeight-1);
  out.fillStyle=accent;out.fillText(lightText,labelX+paddingX,labelY+pillHeight/2);

  const footerText='虚线箭头 = 光线入射方向';
  out.font=`650 ${Math.max(10,11*unit)}px Inter,"PingFang SC",sans-serif`;
  const footerWidth=out.measureText(footerText).width+paddingX*2;
  const footerX=Math.max(8*unit,width-footerWidth-10*unit),footerY=height-pillHeight-10*unit;
  out.fillStyle='rgba(5,11,18,.84)';fillRoundedRect(out,footerX,footerY,footerWidth,pillHeight,8*unit);
  out.fillStyle='#e7ecdf';out.fillText(footerText,footerX+paddingX,footerY+pillHeight/2);

  const modeText=modeLabel||'NMM 光影';
  out.font=`800 ${Math.max(10,11*unit)}px Inter,"PingFang SC",sans-serif`;
  const modeWidth=out.measureText(modeText).width+paddingX*2;
  out.fillStyle='rgba(5,11,18,.84)';fillRoundedRect(out,10*unit,10*unit,modeWidth,pillHeight,8*unit);
  out.fillStyle='#f1f4ed';out.fillText(modeText,10*unit+paddingX,10*unit+pillHeight/2);
  return output;
}

function captureExportFrames(){
  if(!state.hasImage || !aiNormalReady || !aiLineartReady)throw new Error('请等待照片分析完成后再导出');
  const saved={
    showOriginal:state.showOriginal,
    lightingEnabled:state.lightingEnabled,
    previewMode:state.previewMode,
    regions:supplementRegions,
    start:regionStart,
    current:regionCurrent
  };
  let current,painted,guide;
  try{
    // Recognition boxes are UI controls and must never be burned into a
    // painting reference. All captures still share the exact same pixels.
    supplementRegions=[];regionStart=null;regionCurrent=null;
    render();current=cloneCanvas(canvas);
    state.previewMode='lighting';state.lightingEnabled=true;state.showOriginal=true;
    render();painted=cloneCanvas(canvas);
    state.previewMode='guide';state.showOriginal=false;
    render();guide=cloneCanvas(canvas);
  }finally{
    state.showOriginal=saved.showOriginal;
    state.lightingEnabled=saved.lightingEnabled;
    state.previewMode=saved.previewMode;
    supplementRegions=saved.regions;regionStart=saved.start;regionCurrent=saved.current;
    render();
  }
  const currentMode={original:'当前视图 · 原图',compare:'当前视图 · 对比',lighting:'当前视图 · NMM 光影'}[saved.previewMode]||'当前视图';
  return {
    original:annotateExportFrame(photoCanvas,'原始照片'),
    painted:annotateExportFrame(painted,'NMM 照片上色'),
    guide:annotateExportFrame(guide,'NMM 纯光影指引'),
    current:annotateExportFrame(current,currentMode)
  };
}

function steppedExportPalette(){
  const palette=getActivePalette();
  return Array.from({length:state.steps},(_,index)=>{
    const position=index/(state.steps-1)*(palette.length-1);
    const low=Math.floor(position),high=Math.min(palette.length-1,low+1),mix=position-low;
    return palette[low].map((value,channel)=>Math.round(value*(1-mix)+palette[high][channel]*mix));
  });
}

function createComparisonSheet({original,painted,guide}){
  const sourceWidth=original.width,sourceHeight=original.height;
  const sheetWidth=Math.min(2200,Math.max(1400,sourceWidth*2));
  const margin=Math.round(sheetWidth*.03),gap=Math.round(sheetWidth*.018);
  const headerHeight=Math.round(sheetWidth*.095),labelHeight=Math.round(sheetWidth*.026);
  const pairWidth=Math.floor((sheetWidth-margin*2-gap)/2);
  const pairHeight=Math.round(sourceHeight/sourceWidth*pairWidth);
  const guideWidth=sheetWidth-margin*2;
  const guideHeight=Math.round(sourceHeight/sourceWidth*guideWidth);
  const footerHeight=Math.round(sheetWidth*.1);
  const sheetHeight=headerHeight+labelHeight+pairHeight+gap+labelHeight+guideHeight+footerHeight+margin;
  const sheet=document.createElement('canvas');sheet.width=sheetWidth;sheet.height=sheetHeight;
  const out=sheet.getContext('2d');
  out.fillStyle='#11110f';out.fillRect(0,0,sheetWidth,sheetHeight);
  out.textBaseline='middle';
  out.fillStyle='#d9ff43';out.font=`800 ${Math.round(sheetWidth*.027)}px Inter, "PingFang SC", sans-serif`;
  out.fillText('NMM 光影参考板',margin,Math.round(headerHeight*.35));
  out.fillStyle='#eeeae0';out.font=`600 ${Math.round(sheetWidth*.012)}px Inter, "PingFang SC", sans-serif`;
  const material=`${MATERIAL_PRESETS[state.material].name} × ${TINT_PRESETS[state.tint].name}`;
  out.fillText(`${material}  ·  ${state.steps} 阶  ·  光滑度 ${state.gloss}%  ·  强度 ${Math.round(state.strength*100)}%`,margin,Math.round(headerHeight*.7));
  out.textAlign='right';out.fillStyle='#77746b';out.font=`500 ${Math.round(sheetWidth*.009)}px Inter, "PingFang SC", sans-serif`;
  out.fillText('各画面像素位置完全一致',sheetWidth-margin,Math.round(headerHeight*.7));out.textAlign='left';

  function drawPanel(source,x,y,width,height,label,accent){
    out.fillStyle='#1b1b18';out.fillRect(x,y,width,labelHeight+height);
    out.fillStyle=accent;out.fillRect(x,y,Math.max(6,Math.round(sheetWidth*.004)),labelHeight);
    out.fillStyle='#dedad0';out.font=`700 ${Math.round(sheetWidth*.011)}px Inter, "PingFang SC", sans-serif`;
    out.fillText(label,x+Math.round(sheetWidth*.014),y+labelHeight/2);
    out.imageSmoothingEnabled=true;out.imageSmoothingQuality='high';
    out.drawImage(source,x,y+labelHeight,width,height);
    out.strokeStyle='#393832';out.lineWidth=2;out.strokeRect(x+.5,y+.5,width-1,labelHeight+height-1);
  }
  let y=headerHeight;
  drawPanel(original,margin,y,pairWidth,pairHeight,'01  原始照片','#d7d5cd');
  drawPanel(painted,margin+pairWidth+gap,y,pairWidth,pairHeight,'02  照片上的 NMM 光影','#d9ff43');
  y+=labelHeight+pairHeight+gap;
  drawPanel(guide,margin,y,guideWidth,guideHeight,'03  纯光影落点指引','#d9ff43');

  const footerY=y+labelHeight+guideHeight;
  out.fillStyle='#cbc7bc';out.font=`700 ${Math.round(sheetWidth*.01)}px Inter, "PingFang SC", sans-serif`;
  out.fillText('主光色阶',margin,footerY+footerHeight*.42);
  const palette=steppedExportPalette();
  const swatchWidth=Math.max(32,Math.round(sheetWidth*.025)),swatchHeight=Math.round(sheetWidth*.018);
  let swatchX=margin+Math.round(sheetWidth*.075);
  for(const color of palette){out.fillStyle=`rgb(${color.join(',')})`;out.fillRect(swatchX,footerY+footerHeight*.31,swatchWidth,swatchHeight);swatchX+=swatchWidth;}
  const keyX=Math.max(swatchX+gap,Math.round(sheetWidth*.59));
  out.fillStyle='#69b4c8';out.fillRect(keyX,footerY+footerHeight*.31,swatchHeight,swatchHeight);
  out.fillStyle='#aaa69c';out.font=`500 ${Math.round(sheetWidth*.0085)}px Inter, "PingFang SC", sans-serif`;
  out.fillText('二次反射 · 材质冷调',keyX+swatchHeight+12,footerY+footerHeight*.42);
  const thirdX=Math.round(sheetWidth*.79);
  out.fillStyle='#d59d67';out.fillRect(thirdX,footerY+footerHeight*.31,swatchHeight,swatchHeight);
  out.fillStyle='#aaa69c';out.fillText('三次反射 · 材质暖调',thirdX+swatchHeight+12,footerY+footerHeight*.42);
  out.fillStyle='#69665e';out.font=`500 ${Math.round(sheetWidth*.0078)}px Inter, "PingFang SC", sans-serif`;
  out.fillText('冷暖反射会保留当前材质底色，不再使用固定颜色覆盖。',margin,footerY+footerHeight*.76);
  return sheet;
}

const exportControl=document.querySelector('#exportControl');
const exportMenu=document.querySelector('#exportMenu');
const downloadButton=document.querySelector('#downloadButton');
const exportStatus=document.querySelector('#exportStatus');

function setExportMenu(open){
  exportMenu.hidden=!open;
  downloadButton.setAttribute('aria-expanded',String(open));
}

function setExportStatus(message,type=''){
  exportStatus.textContent=message;
  exportStatus.className=`export-status ${type}`.trim();
}

function setExportBusy(busy){
  exportBusy=busy;
  downloadButton.disabled=busy || state.inferenceBusy || !aiNormalReady || !aiLineartReady;
  exportMenu.querySelectorAll('button').forEach(button=>{button.disabled=busy;});
  downloadButton.firstChild.textContent=busy?'正在导出… ':'导出 ';
}

downloadButton.onclick=()=>setExportMenu(exportMenu.hidden);
document.addEventListener('pointerdown',event=>{
  if(!exportControl.contains(event.target))setExportMenu(false);
});
document.addEventListener('keydown',event=>{if(event.key==='Escape')setExportMenu(false);});

exportMenu.addEventListener('click',async event=>{
  const action=event.target.closest('[data-export]')?.dataset.export;
  if(!action || exportBusy)return;
  setExportMenu(false);setExportBusy(true);setExportStatus('正在准备高清画面…');
  try{
    await new Promise(resolve=>requestAnimationFrame(resolve));
    const frames=captureExportFrames();
    if(action==='comparison'){
      const sheet=createComparisonSheet(frames);
      await downloadBlob(await canvasToBlob(sheet),exportFilename('reference-board','png'));
      setExportStatus('高清对照 PNG 已导出','success');
    }else if(action==='mp4'){
      setExportStatus('正在生成微信对比视频…');
      if(isAndroidApp){
        await invokeAndroid(
          'exportMp4',
          await blobToDataURL(await canvasToBlob(frames.original)),
          await blobToDataURL(await canvasToBlob(frames.painted)),
          exportFilename('wechat-comparison','mp4')
        );
      }else{
        const form=new FormData();
        form.append('original',await canvasToBlob(frames.original),'original.png');
        form.append('nmm',await canvasToBlob(frames.painted),'nmm.png');
        const response=await fetch('/api/export-mp4',{method:'POST',body:form});
        if(!response.ok){
          const payload=await response.json().catch(()=>null);
          throw new Error(payload?.detail||'MP4 视频生成失败');
        }
        await downloadBlob(await response.blob(),exportFilename('wechat-comparison','mp4'));
      }
      setExportStatus('微信对比视频已导出','success');
    }else{
      await downloadBlob(await canvasToBlob(frames.current),exportFilename('current-view','png'));
      setExportStatus('当前画面已导出','success');
    }
  }catch(error){
    setExportStatus(error.message||'导出失败','error');
  }finally{
    setExportBusy(false);
  }
});

window.addEventListener('resize',()=>{updateLightUI();updateCompareDividerUI();});
updatePrimaryLegend();createDemo();updateLightUI();syncInferenceControls();
