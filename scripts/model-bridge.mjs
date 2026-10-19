// Optional loopback bridge for a locally installed Ollama model. Never starts or downloads a model.
// Run only after choosing a model you already have: ATLAS_LOCAL_MODEL=<name> node scripts/model-bridge.mjs
import http from "node:http";
const model=process.env.ATLAS_LOCAL_MODEL;
if(!model) throw new Error("Set ATLAS_LOCAL_MODEL to a model already installed in local Ollama.");
let active=0;
const server=http.createServer(async(req,res)=>{
  const reply=(status,value)=>{res.writeHead(status,{"Content-Type":"application/json"});res.end(JSON.stringify(value));};
  if(req.method!=="POST"||req.url!=="/explain")return reply(404,{error:"Not found"});
  if(active>=1)return reply(429,{error:"Local model busy"});
  active++;
  try{
    const chunks=[];let size=0;
    for await(const chunk of req){size+=chunk.length;if(size>64000){reply(413,{error:"Context limit"});req.destroy();return;}chunks.push(chunk);}
    const payload=JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if(typeof payload.question!=="string"||!Array.isArray(payload.context)||payload.context.length>5)return reply(400,{error:"Invalid context"});
    const upstream=await fetch("http://127.0.0.1:11434/api/chat",{method:"POST",headers:{"Content-Type":"application/json"},signal:AbortSignal.timeout(3500),body:JSON.stringify({model,stream:false,format:"json",options:{temperature:0,num_predict:700},messages:[{role:"system",content:payload.instructions},{role:"user",content:JSON.stringify({question:payload.question,untrustedSourceContext:payload.context})}]})});
    if(!upstream.ok)throw new Error("Local provider unavailable");
    let received="";for await(const chunk of upstream.body){received+=Buffer.from(chunk).toString("utf8");if(received.length>32000)throw new Error("Model response limit");}
    const completion=JSON.parse(JSON.parse(received).message.content);
    const allowed=new Set(payload.context.map(c=>c.id));
    if(typeof completion.answer!=="string"||completion.answer.length>8000||!Array.isArray(completion.citationIds)||!completion.citationIds.length||!completion.citationIds.every(id=>allowed.has(id)))throw new Error("Invalid citations");
    reply(200,completion);
  }catch{reply(503,{error:"Model unavailable or invalid response; ATLAS will use local evidence."});}
  finally{active--;}
});
server.requestTimeout=5000;server.headersTimeout=5000;server.maxConnections=4;
server.listen(11435,"127.0.0.1",()=>console.log("ATLAS local model bridge listening on loopback port 11435. Source is sent only after approval in ATLAS."));
