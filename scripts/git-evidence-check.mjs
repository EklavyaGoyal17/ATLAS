// Creates only a synthetic repository below this checkout's ignored .runtime folder; removes nothing.
import assert from "node:assert/strict";import {execFileSync} from "node:child_process";import {mkdir,writeFile} from "node:fs/promises";import {randomUUID} from "node:crypto";import {fileURLToPath} from "node:url";
const root=fileURLToPath(new URL("../",import.meta.url));const relative=`.runtime/git-evidence-${randomUUID()}`;const folder=fileURLToPath(new URL(`../${relative}/`,import.meta.url));
const token=process.env.ATLAS_API_TOKEN;if(!token)throw new Error("Set ATLAS_API_TOKEN for the local API.");
await mkdir(folder,{recursive:true});
const git=(...args)=>execFileSync("git",["-c","core.hooksPath=","-c","core.fsmonitor=false","-c","commit.gpgSign=false","-C",folder,...args],{cwd:root,windowsHide:true,stdio:"pipe"});
git("init","--quiet");await writeFile(`${folder}/Example.java`,"class Example { int value() { return 1; } }\n");git("add","--","Example.java");git("-c","user.name=ATLAS fixture","-c","user.email=fixture@example.invalid","commit","--quiet","--no-gpg-sign","--no-verify","-m","Synthetic source evidence fixture");
await writeFile(`${folder}/Example.java`,"class Example { int value() { return 2; } }\n");
async function api(path,body){const response=await fetch(`http://127.0.0.1:8080/api${path}`,{method:body?"POST":"GET",headers:{Authorization:`Bearer ${token}`,...(body?{"Content-Type":"application/json"}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(15000)});const data=await response.json();assert.ok(response.ok,JSON.stringify(data));return data;}
try{
 await api("/repository/import",{path:relative,approved:true});const evidence=await api("/repository/git?path=Example.java");assert.match(evidence.history,/Synthetic source evidence fixture/);assert.match(evidence.changes,/-class Example.*return 1/);assert.match(evidence.changes,/\+class Example.*return 2/);console.log("PASS: real Git history and tracked source diff through the bounded API; synthetic fixture retained in ignored .runtime.");
}finally{await api("/repository/import",{path:".",approved:true});}
