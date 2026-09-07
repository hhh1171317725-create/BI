// Converts this supplied single-mesh, untextured print project without changing the source.
const fs=require('node:fs'),path=require('node:path'),{execFileSync}=require('node:child_process');
const source=path.resolve(process.argv[2]||'');
if(!fs.existsSync(source)||!source.endsWith('.3mf'))throw Error('Usage: node scripts/convert-login-3mf.cjs model.3mf');
const escaped=source.replaceAll("'","''");
const xml=execFileSync('powershell',['-NoProfile','-Command',`Add-Type -AssemblyName System.IO.Compression.FileSystem; $a=[IO.Compression.ZipFile]::OpenRead('${escaped}'); try { $r=[IO.StreamReader]::new($a.GetEntry('3D/Objects/object_1.model').Open()); $r.ReadToEnd(); $r.Dispose() } finally { $a.Dispose() }`],{encoding:'utf8',maxBuffer:32*1024*1024});
if((xml.match(/<mesh>/g)||[]).length!==1)throw Error('Expected the supplied single-mesh project');
const points=[],indices=[];
for(const m of xml.matchAll(/<vertex x="([^"]+)" y="([^"]+)" z="([^"]+)"\s*\/>/g))points.push(+m[1],+m[3],-m[2]);
for(const m of xml.matchAll(/<triangle v1="(\d+)" v2="(\d+)" v3="(\d+)"[^>]*\/>/g))indices.push(+m[1],+m[2],+m[3]);
if(!points.length||!indices.length)throw Error('Empty geometry');
const min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity];
points.forEach((v,i)=>{min[i%3]=Math.min(min[i%3],v);max[i%3]=Math.max(max[i%3],v);});
const scale=3.4/(max[1]-min[1]),center=[(min[0]+max[0])/2,min[1],(min[2]+max[2])/2];
const positions=Float32Array.from(points,(v,i)=>(v-center[i%3])*scale),normals=new Float32Array(points.length);
for(let i=0;i<indices.length;i+=3){const a=indices[i]*3,b=indices[i+1]*3,c=indices[i+2]*3;if(Math.max(a,b,c)>=points.length)throw Error('Bad index');const u=[positions[b]-positions[a],positions[b+1]-positions[a+1],positions[b+2]-positions[a+2]],v=[positions[c]-positions[a],positions[c+1]-positions[a+1],positions[c+2]-positions[a+2]],n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]];for(const j of [a,b,c])for(let k=0;k<3;k++)normals[j+k]+=n[k];}
for(let i=0;i<normals.length;i+=3){const length=Math.hypot(normals[i],normals[i+1],normals[i+2])||1;for(let k=0;k<3;k++)normals[i+k]/=length;}
const arrays=[positions,normals,Uint32Array.from(indices)],buffers=arrays.map(a=>Buffer.from(a.buffer)),binary=Buffer.concat(buffers);let offset=0;
const gltf={asset:{version:'2.0',generator:'BI supplied 3MF converter'},scene:0,scenes:[{nodes:[0]}],nodes:[{mesh:0,name:'User supplied Subaru statue'}],meshes:[{primitives:[{attributes:{POSITION:0,NORMAL:1},indices:2,material:0}]}],materials:[{name:'Ivory sculpture',pbrMetallicRoughness:{baseColorFactor:[.64,.59,.51,1],metallicFactor:.12,roughnessFactor:.58}}],buffers:[{byteLength:binary.length}],bufferViews:buffers.map((b,i)=>{const view={buffer:0,byteOffset:offset,byteLength:b.length,target:i===2?34963:34962};offset+=b.length;return view;}),accessors:[{bufferView:0,componentType:5126,count:positions.length/3,type:'VEC3',min:min.map((v,i)=>(v-center[i])*scale),max:max.map((v,i)=>(v-center[i])*scale)},{bufferView:1,componentType:5126,count:normals.length/3,type:'VEC3'},{bufferView:2,componentType:5125,count:indices.length,type:'SCALAR'}]};
const raw=Buffer.from(JSON.stringify(gltf)),json=Buffer.concat([raw,Buffer.alloc((4-raw.length%4)%4,32)]),header=Buffer.alloc(12),jh=Buffer.alloc(8),bh=Buffer.alloc(8);header.write('glTF');header.writeUInt32LE(2,4);header.writeUInt32LE(28+json.length+binary.length,8);jh.writeUInt32LE(json.length);jh.writeUInt32LE(0x4e4f534a,4);bh.writeUInt32LE(binary.length);bh.writeUInt32LE(0x004e4942,4);
const output=path.resolve(__dirname,'../frontend/assets/models/subaru-supplied.glb');fs.writeFileSync(output,Buffer.concat([header,jh,json,bh,binary]));console.log(JSON.stringify({output,vertices:positions.length/3,triangles:indices.length/3,bytes:fs.statSync(output).size}));
