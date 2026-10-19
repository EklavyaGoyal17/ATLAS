import {performance} from "node:perf_hooks";
const token=process.env.ATLAS_API_TOKEN;if(!token)throw new Error("Set ATLAS_API_TOKEN for the local API.");
const base="http://127.0.0.1:8080/api";
const conditions={runtime:process.version,base,concurrency:4,samplesPerEndpoint:20,tls:false,scope:"Warm local Docker API/PostgreSQL; small observed sample, not capacity evidence."};
async function measure(path){
  const durations=[];const statuses={};let next=0;
  await Promise.all(Array.from({length:4},async()=>{while(next++<20){const start=performance.now();const response=await fetch(base+path,{headers:{Authorization:`Bearer ${token}`},signal:AbortSignal.timeout(10000)});await response.arrayBuffer();durations.push(performance.now()-start);statuses[response.status]=(statuses[response.status]??0)+1;await new Promise(resolve=>setTimeout(resolve,100));}}));
  durations.sort((a,b)=>a-b);const percentile=p=>+durations[Math.min(durations.length-1,Math.ceil(p*durations.length)-1)].toFixed(2);
  return {path,samples:durations.length,statuses,p50Ms:percentile(.5),p95Ms:percentile(.95),maxMs:percentile(1)};
}
const results=[];for(const path of ["/payments?page=0&size=20","/workspace/documents?page=0","/observability/metrics"])results.push(await measure(path));
console.log(JSON.stringify({conditions,results},null,2));if(results.some(r=>Object.keys(r.statuses).some(s=>s!=="200")))process.exitCode=1;
