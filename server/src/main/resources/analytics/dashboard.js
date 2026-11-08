"use strict";
const byId=id=>document.getElementById(id);
let access=null,refresh=null;
const number=new Intl.NumberFormat();
async function api(path,method="GET",body){
 const response=await fetch(path,{method,cache:"no-store",headers:{"Content-Type":"application/json",...(access?{Authorization:"Bearer "+access}:{})},body:body?JSON.stringify(body):undefined});
 const result=await response.json();
 if(!response.ok||!result.success)throw new Error(response.status===401?"Session expired. Log out and sign in again.":result.error?.message||"Unable to load analytics.");
 return result.data;
}
function table(target,head,rows){
 const holder=byId(target);holder.replaceChildren();
 if(!rows.length){const p=document.createElement("p");p.textContent="No activity recorded for this selection.";holder.append(p);return;}
 const table=document.createElement("table"),thead=document.createElement("thead"),header=document.createElement("tr");
 head.forEach(label=>{const th=document.createElement("th");th.textContent=label;header.append(th);});thead.append(header);table.append(thead);
 const tbody=document.createElement("tbody");rows.forEach(values=>{const tr=document.createElement("tr");values.forEach(value=>{const td=document.createElement("td");td.textContent=String(value);tr.append(td);});tbody.append(tr);});table.append(tbody);holder.append(table);
}
const metrics=m=>[number.format(m.opens),number.format(m.uniqueViewers),number.format(m.starts),number.format(m.views),number.format(Math.round(m.watchSeconds/60)),number.format(m.completions)];
const headings=["Opens","Daily viewers","Starts","Views","Watch minutes","Completions"];
async function load(){
 byId("message").textContent="Loading…";
 const query=new URLSearchParams({from:byId("from").value,to:byId("to").value,creator:byId("creator").value,video:byId("video").value});
 try{
 const report=await api("/api/v1/analytics/report?"+query);
 byId("scope").textContent=report.admin?"All creator analytics":"Your video analytics";
 byId("creator-filter").hidden=!report.admin;byId("creator-section").hidden=!report.admin;
 const totals=byId("totals");totals.replaceChildren();metrics(report.totals).forEach((value,index)=>{const card=document.createElement("div");card.className="stat";card.textContent=headings[index];const strong=document.createElement("strong");strong.textContent=value;card.append(strong);totals.append(card);});
 table("days",["Day (India)",...headings],report.days.map(day=>[day.day,...metrics(day.metrics)]));
 table("videos",["Video","Video ID","Creator",...headings],report.videos.map(video=>[video.title,video.id,video.creatorId,...metrics(video.metrics)]));
 table("creators",["Creator ID",...headings],report.creators.map(creator=>[creator.id,...metrics(creator.metrics)]));
 byId("message").textContent="Updated just now.";
 }catch(error){byId("message").textContent=error.message;}
}
byId("signin").addEventListener("submit",async event=>{
 event.preventDefault();const button=event.target.querySelector("button");button.disabled=true;
 try{const result=await api("/api/v1/auth/login","POST",{email:byId("email").value.trim(),password:byId("password").value});access=result.accessToken;refresh=result.refreshToken;byId("password").value="";
 const day=()=>new Intl.DateTimeFormat("en-CA",{timeZone:"Asia/Kolkata",year:"numeric",month:"2-digit",day:"2-digit"}).format(new Date());
 byId("to").value=day();byId("from").value=new Date(Date.parse(day()+"T00:00:00Z")-6*86400000).toISOString().slice(0,10);
 byId("login").hidden=true;byId("dashboard").hidden=false;byId("logout").hidden=false;await load();
 }catch(error){byId("message").textContent=error.message;}finally{button.disabled=false;}
});
byId("filters").addEventListener("submit",event=>{event.preventDefault();load();});
byId("logout").addEventListener("click",async()=>{try{if(refresh)await api("/api/v1/auth/logout","POST",{refreshToken:refresh});}catch(_){}finally{access=null;refresh=null;location.reload();}});
