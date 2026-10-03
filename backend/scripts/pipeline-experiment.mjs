import assert from 'node:assert/strict';
import {mkdirSync,readFileSync} from 'node:fs';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {performance} from 'node:perf_hooks';
import os from 'node:os';
import {root,projectRoot,start,stop,request,fixture,invariant,sql,sleep,save,kafkaDrained,sourceManifest} from './harness.mjs';
if(process.env.UPGRADE_DB_SCHEMA!=='life_choice_verification'||!process.env.UPGRADE_DB_URL?.includes('/life_choice_verification?')||process.env.SPRING_DATA_REDIS_DATABASE!=='13') throw Error('Use verification.sh');
const mode=process.argv.includes('--sustained')?'sustained':'variants';
const dir=`${root}/evidence/pipeline-${mode}-${Date.now()}`;mkdirSync(dir,{recursive:true});
const results=[];let child;let sequence=Date.now();
const percentile=(a,p)=>a.length?[...a].sort((x,y)=>x-y)[Math.ceil(a.length*p)-1]:null;
const settings={batch:20,window:1,repairBatch:100,repairDelay:1000};
const variants=[['baseline',{}],['relay-batch-100',{batch:100}],['send-window-4',{window:4}],['repair-batch-300',{repairBatch:300}],['repair-delay-100',{repairDelay:100}]];
const common=['--upgrade.rate-user=100','--upgrade.rate-activity=1000000','--upgrade.backlog-limit=1000000'];
const metrics=async()=>{const r=await request('/v2/admin/pipeline',{admin:true});assert.equal(r.status,200);return r.data;};
function delta(before,after) {
 const stages={};for(const [name,h] of Object.entries(after.timers.stages)) {
  const old=before.timers.stages[name],count=h.count-old.count,buckets=h.buckets.map((v,i)=>v-old.buckets[i]);
  let sum=0,bound=null;for(let i=0;i<buckets.length;i++){sum+=buckets[i];if(count&&sum>=Math.ceil(count*.95)){bound=after.timers.bucketUpperMs[i]??'>60000';break;}}
  stages[name]={count,failures:h.failures-old.failures,meanMs:count?(h.sumMs-old.sumMs)/count:null,p95UpperMs:bound,buckets};
 }return stages;
}
function locks() {
 const rows=[];let closed=false,timer;
 const proc=spawn('docker',['compose','exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names --unbuffered'],{cwd:projectRoot,stdio:['pipe','pipe','pipe']});
 let error='';proc.stderr.on('data',b=>error+=b.toString());proc.stdin.on('error',()=>{});
 const query=()=>{if(!closed)proc.stdin.write("SELECT ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000),COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA='life_choice_verification' AND l.OBJECT_NAME='ux_activity';\n");};
 createInterface({input:proc.stdout}).on('line',line=>{const [epoch,waiting]=line.split('\t').map(Number);if(Number.isFinite(epoch)&&Number.isFinite(waiting))rows.push({epoch,waiting});if(!closed)timer=setTimeout(query,200);});query();
 return {rows,close:async()=>{if(closed)return;closed=true;clearTimeout(timer);proc.stdin.end();await new Promise(resolve=>{if(proc.exitCode!==null)return resolve();proc.once('exit',resolve);setTimeout(()=>proc.kill(),2000).unref();});if(error.trim())throw Error(error);assert.ok(rows.length,'No actual lock samples');}};
}
async function pooled(items,fn,n=4){let cursor=0;await Promise.all(Array.from({length:n},async()=>{while(cursor<items.length){const i=cursor++;await fn(items[i],i);}}));}
async function run(name,overrides,round,profile) {
 const config={...settings,...overrides};child=await start(`${dir}/${round}-${name}.log`,[...common,`--upgrade.relay-batch=${config.batch}`,`--upgrade.relay-window=${config.window}`,`--upgrade.repair-batch=${config.repairBatch}`,`--upgrade.repair-delay-ms=${config.repairDelay}`]);
 await kafkaDrained();
 const prepare=process.argv.find(a=>a.startsWith('--confirm-from='));
 if(prepare&&!results.length){const previous=JSON.parse(readFileSync(prepare.slice('--confirm-from='.length),'utf8')).samples.filter(s=>s.finalState==='SUCCEEDED');await pooled(previous,async s=>{assert.equal((await request(`/v2/requests/${s.id}/confirm`,{user:s.user,method:'POST'})).status,200);});}
 // Let earlier fixtures expire naturally before the measured interval.
 for(let i=0;;i++){const n=Number(sql("SELECT COUNT(*) FROM ux_order WHERE state='PENDING_CONFIRM' AND confirm_until<=CURRENT_TIMESTAMP(3)"));if(!n)break;if(i>180)throw Error('Old expiry backlog');await sleep(1000);}
 const activities=Array.from({length:profile?.activities??1},()=>++sequence);
 const count=profile?profile.duration*profile.maxRate:500;
 for(const id of activities)await fixture(id,count);
 const samples=[],trace=[];const before=await metrics();const sampler=locks();let sampling=true;
 const traceTask=(async()=>{while(sampling){trace.push({epoch:Date.now(),...(await metrics()).queues});await sleep(1000);}})();
 const began=performance.now(),epochStart=Date.now();let inflight=0,maxInflight=0,skipped=0;const pending=new Set();
 const send=async(index,scheduled=performance.now())=>{
  const sent=performance.now(),sentEpoch=Date.now(),activity=activities[index%activities.length],user=activity*1000+index;
  inflight++;maxInflight=Math.max(maxInflight,inflight);
  try {const r=await request('/v2/requests',{user,method:'POST',body:{requestId:`pipe_${activity}_${index}`,activityId:activity}});samples.push({index,activity,user,sentEpoch,scheduleDelayMs:sent-scheduled,latencyMs:performance.now()-sent,status:r.status,id:r.data.id,error:r.data.error});}
  catch(e){samples.push({index,activity,user,sentEpoch,scheduleDelayMs:sent-scheduled,latencyMs:performance.now()-sent,status:0,error:e.message});}finally{inflight--;}
 };
 let after;
 try {
  if(!profile)await pooled(Array.from({length:500},(_,i)=>i),i=>send(i),8);
  else {let index=0;for(let sec=0;sec<profile.duration;sec++){
   const rate=profile.rate(sec);for(let j=0;j<rate;j++){
    const target=began+sec*1000+j*1000/rate;const wait=target-performance.now();if(wait>0)await sleep(wait);
    if(inflight>=64){skipped++;continue;}const p=send(index++,target);pending.add(p);p.finally(()=>pending.delete(p));
   }
  }await Promise.all(pending);}
  const httpSeconds=(performance.now()-began)/1000;
  const ids=activities.join(',');const atEnd=sql(`SELECT COUNT(*) FROM ux_request WHERE activity_id IN (${ids}) AND state='ACCEPTED'`);
  const drain=Date.now();while(Number(sql(`SELECT (SELECT COUNT(*) FROM ux_request WHERE activity_id IN (${ids}) AND state='ACCEPTED')+(SELECT COUNT(*) FROM ux_reservation_action a JOIN ux_request r ON r.id=a.request_id WHERE r.activity_id IN (${ids}) AND a.applied_at IS NULL)`))){if(Date.now()-drain>120000)throw Error('Drain timeout');await sleep(200);}
  const settledSeconds=(performance.now()-began)/1000;after=await metrics();
  const rows=sql(`SELECT id,state,ROUND(UNIX_TIMESTAMP(completed_at)*1000) FROM ux_request WHERE activity_id IN (${ids})`).split('\n').filter(Boolean).map(line=>line.split('\t'));
  const byId=new Map(rows.map(([id,state,at])=>[id,{state,at:Number(at)}]));const statuses={};
  for(const s of samples){statuses[s.status]=(statuses[s.status]??0)+1;const r=byId.get(s.id);if(r){s.finalState=r.state;s.endToEndMs=r.at-s.sentEpoch;}}
  const success=samples.filter(s=>s.finalState==='SUCCEEDED');const databaseSeconds=success.length?(Math.max(...success.map(s=>s.sentEpoch+s.endToEndMs))-epochStart)/1000:null;
  const result={name,round,config,profile:profile?{duration:profile.duration,rates:profile.rates,activities:profile.activities}:null,plannedArrivals:samples.length+skipped,requests:samples.length,skipped,generatorLimited:skipped>0,allOfferedSucceeded:skipped===0&&success.length===samples.length,maxInflight,statuses,successfulOrders:success.length,httpSeconds,settledSeconds,drainMs:Date.now()-drain,acceptedAtHttpEnd:Number(atEnd),databaseOrderTps:databaseSeconds?success.length/databaseSeconds:null,settledTps:success.length/settledSeconds,httpP95Ms:percentile(samples.map(s=>s.latencyMs),.95),endToEndP95Ms:percentile(success.map(s=>s.endToEndMs),.95),scheduleDelayP95Ms:percentile(samples.map(s=>s.scheduleDelayMs),.95),stages:delta(before,after),invariants:(await invariant()).invariants};
  sampling=false;await traceTask;await sampler.close();
  result.locks={samples:sampler.rows.length,nonzero:sampler.rows.filter(r=>r.waiting>0).length,peak:Math.max(...sampler.rows.map(r=>r.waiting))};
  save(`${dir}/${round}-${name}-raw.json`,{samples,trace,lockSamples:sampler.rows,before,after});results.push(result);save(`${dir}/results.json`,results);
  console.log(JSON.stringify({...result,stages:undefined}));
  if(!profile){assert.equal(skipped,0);assert.equal(success.length,samples.length,'Every offered request must succeed in a qualifying batch run');}
  else {assert.ok(samples.every(s=>[202,429,503].includes(s.status)),'Unexpected HTTP/transport failure');assert.equal(success.length,samples.filter(s=>s.status===202).length,'Every accepted request must complete');}
  // Confirm through the business API after measurement so later expiry cannot pollute trials.
  await pooled(success,async s=>{assert.equal((await request(`/v2/requests/${s.id}/confirm`,{user:s.user,method:'POST'})).status,200);});
 }finally{sampling=false;await traceTask.catch(()=>{});await sampler.close();await stop(child);child=null;}
}
try {
 save(`${dir}/environment.json`,{date:new Date().toISOString(),mode,manifest:sourceManifest(),cpu:os.cpus()[0]?.model,cpuCount:os.cpus().length,memory:os.totalmem(),instances:1,heap:'384m',schema:'life_choice_verification',redisDb:13,scope:'Same-host middleware; instrumented baseline; lock sampler counts wait edges (not distinct transactions), 200ms plus query time; stage percentiles are histogram upper bounds, attempt based.'});
 if(mode==='variants')for(let round=1;round<=3;round++){const offset=round-1;for(let j=0;j<variants.length;j++){const [name,config]=variants[(j+offset)%variants.length];await run(name,config,round);}}
 else {
  const config=JSON.parse(process.env.PIPELINE_CONFIG??'{}');
  const profiles=process.argv.includes('--safe-only')?[{name:'steady-safe',duration:60,maxRate:40,activities:1,rates:'40/s for 60s',rate:()=>40}]:[
   {name:'steady-hot',duration:60,maxRate:80,activities:1,rates:'80/s for 60s',rate:()=>80},
   {name:'burst-hot',duration:60,maxRate:160,activities:1,rates:'40/s 0-20s;160/s 20-40s;40/s 40-60s',rate:s=>s>=20&&s<40?160:40},
   {name:'steady-distributed',duration:60,maxRate:80,activities:10,rates:'80/s for 60s over 10 activities',rate:()=>80}];
  const filter=process.argv.find(a=>a.startsWith('--profile='))?.split('=')[1];
  const selected=profiles.filter(p=>!filter||p.name===filter);assert.ok(selected.length,'Unknown profile');
  for(const p of selected)await run(p.name,config,1,p);
 }
 console.log(`Evidence: ${dir}`);
}catch(e){save(`${dir}/failure.json`,{message:e.message,stack:e.stack});throw e;}finally{await stop(child);}
