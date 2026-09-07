import * as THREE from './assets/vendor/three/three.module.js';
import {GLTFLoader} from './assets/vendor/three/loaders/GLTFLoader.js';

const stage=document.querySelector('#characterStage'),status=document.querySelector('#characterStatus');
const buttons=[...document.querySelectorAll('.character-controls button')];
const reduced=matchMedia('(prefers-reduced-motion: reduce)');
const hint='拖动旋转 · 滚轮缩放 · 点击人物自动展示';
buttons.forEach(b=>b.disabled=true);
function unavailable(message){status.textContent=message;stage.classList.add('unavailable');buttons.forEach(b=>b.disabled=true);}
async function init(){
 const renderer=new THREE.WebGLRenderer({antialias:true,alpha:true,powerPreference:'low-power'});
 renderer.setPixelRatio(Math.min(devicePixelRatio,1.75));renderer.outputColorSpace=THREE.SRGBColorSpace;
 renderer.toneMapping=THREE.ACESFilmicToneMapping;renderer.toneMappingExposure=1.1;
 stage.prepend(renderer.domElement);renderer.domElement.setAttribute('aria-label','菜月昴雕塑，可拖动旋转，滚轮缩放');
 const scene=new THREE.Scene(),camera=new THREE.PerspectiveCamera(31,1,.1,50);
 scene.add(new THREE.HemisphereLight(0xdde7ff,0x3b303e,2));
 const key=new THREE.DirectionalLight(0xffe7ce,3.2);key.position.set(-3,6,5);scene.add(key);
 const rim=new THREE.DirectionalLight(0xc3b1ff,2.4);rim.position.set(3,4,-3);scene.add(rim);
 let model;
 try{model=(await new GLTFLoader().loadAsync('/assets/models/subaru-supplied.glb')).scene;}
 catch{renderer.dispose();renderer.domElement.remove();unavailable('模型加载失败，请刷新重试；仍可正常登录');return;}
 scene.add(model);
 let yaw=0,zoom=1,auto=false,drag=null,frame=0,last=0,lost=false;
 const draw=()=>{if(!lost)renderer.render(scene,camera);};
 function resize(){const {width,height}=stage.getBoundingClientRect();if(!width||!height)return;renderer.setSize(width,height);camera.aspect=width/height;const distance=Math.max(7.9,4.2/camera.aspect)*zoom;camera.position.set(0,2.65,distance);camera.lookAt(0,1.7,0);camera.updateProjectionMatrix();draw();}
 function updateStatus(){status.textContent=auto?'正在旋转展示 · 点击暂停':hint;document.querySelector('#greetCharacter').setAttribute('aria-pressed',String(auto));}
 function animate(now){frame=0;if(document.hidden||lost)return;const dt=Math.min((now-last)/1000,.05);last=now;if(auto)yaw+=dt*.3;model.rotation.y=yaw;draw();if(auto&&!reduced.matches)frame=requestAnimationFrame(animate);}
 function start(){cancelAnimationFrame(frame);model.rotation.y=yaw;draw();if(auto&&!reduced.matches&&!document.hidden&&!lost){last=performance.now();frame=requestAnimationFrame(animate);}}
 function toggle(){if(reduced.matches){yaw+=Math.PI/4;auto=false;}else auto=!auto;updateStatus();start();}
 document.querySelector('#greetCharacter').onclick=toggle;
 document.querySelector('#rotateCharacter').onclick=()=>{yaw+=Math.PI/4;start();};
 document.querySelector('#resetCharacter').onclick=()=>{yaw=0;zoom=1;auto=false;updateStatus();start();resize();};
 document.querySelector('#zoomCharacter').onclick=()=>{zoom=zoom>.85?.75:1;resize();};
 renderer.domElement.addEventListener('wheel',e=>{e.preventDefault();zoom=THREE.MathUtils.clamp(zoom+e.deltaY*.001,.65,1.4);resize();},{passive:false});
 renderer.domElement.addEventListener('pointerdown',e=>{drag={id:e.pointerId,x:e.clientX,start:e.clientX,moved:false};renderer.domElement.setPointerCapture(e.pointerId);});
 renderer.domElement.addEventListener('pointermove',e=>{if(!drag||drag.id!==e.pointerId)return;drag.moved ||= Math.abs(e.clientX-drag.start)>5;yaw+=(e.clientX-drag.x)*.009;drag.x=e.clientX;auto=false;updateStatus();start();});
 renderer.domElement.addEventListener('pointerup',()=>{if(drag&&!drag.moved)toggle();drag=null;});
 for(const name of ['pointercancel','lostpointercapture'])renderer.domElement.addEventListener(name,()=>drag=null);
 renderer.domElement.addEventListener('webglcontextlost',e=>{e.preventDefault();lost=true;cancelAnimationFrame(frame);status.textContent='3D 显示已暂停，登录不受影响';});
 renderer.domElement.addEventListener('webglcontextrestored',()=>{lost=false;updateStatus();resize();start();});
 document.addEventListener('visibilitychange',start);reduced.addEventListener('change',()=>{auto=false;updateStatus();start();});
 new ResizeObserver(resize).observe(stage);buttons.forEach(b=>b.disabled=false);stage.dataset.ready='true';updateStatus();resize();start();
}
init().catch(()=>unavailable('当前设备暂不支持 3D，仍可正常登录'));
