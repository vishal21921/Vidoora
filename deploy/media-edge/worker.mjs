// Private HLS segment delivery. No playlists, downloads or arbitrary object URLs.
const respond = (status) => new Response(null, {status, headers:{'Cache-Control':'no-store'}});
const route = /^\/api\/v1\/videos\/([0-9a-f-]{36})\/playback\/([0-9]{1,3}p\/segment_[0-9]{6}\.ts)$/;
export async function handle(request, env, send=fetch) {
  if (env.ENABLED !== 'true') return respond(503);
  try {
    const url=new URL(request.url), match=route.exec(url.pathname);
    if(request.method!=='GET' || !match || url.search) return respond(404);
    const origin=new URL(env.API_ORIGIN);
    if(origin.protocol!=='https:' || origin.username || origin.password || origin.pathname!=='/' || origin.search || origin.hash ||
       !env.EDGE_SECRET || env.EDGE_SECRET.length<32) return respond(503);
    const headers=new Headers({'Content-Type':'application/json','X-Vidoora-Edge':env.EDGE_SECRET});
    for(const name of ['Authorization','X-Vidoora-Share']) {
      const value=request.headers.get(name);if(value) headers.set(name,value);
    }
    const approval=await send(new URL('/api/v1/internal/media/authorize',origin),{
      method:'POST',headers,body:JSON.stringify({videoId:match[1],asset:match[2]}),
      redirect:'error',signal:AbortSignal.timeout(10000)
    });
    if(!approval.ok) {
      // Preserve 401 so the existing Android session refresh remains effective.
      if(approval.status===401) return new Response(null,{status:401,headers:{'Cache-Control':'no-store','WWW-Authenticate':'Bearer realm="Vidoora"'}});
      return respond(approval.status===403 ? 403 : approval.status>=500 ? 503 : 404);
    }
    const grant=await approval.json();
    const escaped=match[2].replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
    const keyPattern=new RegExp('^processed/'+match[1]+'/[0-9a-f-]{36}/'+escaped+'$');
    if(grant.bucket!==env.PROCESSED_BUCKET || typeof grant.key!=='string' || !keyPattern.test(grant.key)) return respond(404);
    // Auth decisions are never cached. All bytes remain behind the private binding.
    const object=await env.MEDIA.get(grant.key);
    if(!object) return respond(404);
    if(object.size>16*1024*1024) { await object.body.cancel();return respond(502); }
    return new Response(object.body,{headers:{'Content-Type':'video/mp2t','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
  } catch { return respond(503); }
}
export default {fetch(request,env) { return handle(request,env); }};
