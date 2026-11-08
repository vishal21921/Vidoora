import test from 'node:test';
import assert from 'node:assert/strict';
import worker, {handle} from './worker.mjs';
const id='388f89c6-5e82-40d3-9dbe-24611d3bb67e';
const path=`/api/v1/videos/${id}/playback/360p/segment_000001.ts`;
const key=`processed/${id}/634a60d6-96c4-4673-8d32-caab82b9452c/360p/segment_000001.ts`;
const req=(suffix=path)=>new Request('https://edge.example'+suffix,{headers:{Authorization:'Bearer fixture','X-Vidoora-Share':'fixture'}});
const config=()=>({ENABLED:'true',API_ORIGIN:'https://api.example/',EDGE_SECRET:'fixture-secret-only-at-least-32-characters',PROCESSED_BUCKET:'fixture',MEDIA:{get:async()=>({body:'media',size:5})}});
test('default off, including native Worker entrypoint context',async()=>assert.equal((await worker.fetch(req(),{},{})).status,503));
test('approved segment forwards current identity and streams R2',async()=>{
 const response=await handle(req(),config(),async(url,options)=>{
  assert.equal(url.pathname,'/api/v1/internal/media/authorize');
  assert.equal(options.headers.get('Authorization'),'Bearer fixture');
  assert.equal(options.headers.get('X-Vidoora-Share'),'fixture');
  assert.deepEqual(JSON.parse(options.body),{videoId:id,asset:'360p/segment_000001.ts'});
  return Response.json({bucket:'fixture',key});
 });
 assert.equal(await response.text(),'media');assert.equal(response.headers.get('Cache-Control'),'no-store');
});
test('every segment request checks authorization again',async()=>{
 let calls=0,reads=0;const env=config();env.MEDIA.get=async()=>{reads++;return {body:'media',size:5};};
 const send=async()=>++calls===1 ? Response.json({bucket:'fixture',key}) : new Response(null,{status:404});
 assert.equal((await handle(req(),env,send)).status,200);
 assert.equal((await handle(req(),env,send)).status,404);assert.equal(reads,1);
});
test('denials and auth refresh status never access R2',async()=>{
 for(const status of [401,403,404,500]) {
  const env=config();env.MEDIA.get=()=>{throw Error('forbidden');};
  const response=await handle(req(),env,async()=>new Response(null,{status}));
  assert.equal(response.status,status===500 ? 503 : status);
  if(status===401) assert.ok(response.headers.has('WWW-Authenticate'));
 }
});
test('wrong bucket or video key rejected',async()=>{
 for(const grant of [{bucket:'foreign',key},{bucket:'fixture',key:key.replace(id,'00000000-0000-0000-0000-000000000000')},{bucket:'fixture',key:key+'x'}]) {
  const env=config();env.MEDIA.get=()=>{throw Error('forbidden');};
  assert.equal((await handle(req(),env,async()=>Response.json(grant))).status,404);
 }
});
test('queries, manifests and nonmedia do not call backend',async()=>{
 for(const suffix of [path+'?token=x',path.replace('segment_000001.ts','index.m3u8'),'/api/v1/downloads','/api/v1/auth/login'])
  assert.equal((await handle(req(suffix),config(),()=>{throw Error();})).status,404);
});
test('backend failure, redirects and missing media fail closed',async()=>{
 assert.equal((await handle(req(),config(),async()=>{throw Error();})).status,503);
 assert.equal((await handle(req(),config(),async()=>new Response(null,{status:302,headers:{Location:'https://evil.example'}}))).status,404);
 const env=config();env.MEDIA.get=async()=>null;
 assert.equal((await handle(req(),env,async()=>Response.json({bucket:'fixture',key}))).status,404);
});
test('oversized segment stream is cancelled',async()=>{
 let cancelled=false;const env=config();env.MEDIA.get=async()=>({size:17*1024*1024,body:{cancel:async()=>{cancelled=true;}}});
 assert.equal((await handle(req(),env,async()=>Response.json({bucket:'fixture',key}))).status,502);assert.ok(cancelled);
});
