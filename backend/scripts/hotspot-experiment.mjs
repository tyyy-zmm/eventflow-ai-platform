import assert from 'node:assert/strict';
import {mkdirSync,readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {performance} from 'node:perf_hooks';
import os from 'node:os';
import {root,projectRoot,start,stop,request,fixture,invariant,sql,compose,sleep,save,kafkaDrained,sourceManifest} from './harness.mjs';
assert.equal(process.env.UPGRADE_DB_SCHEMA,'life_choice_verification');assert.ok(process.env.UPGRADE_DB_URL?.includes('/life_choice_verification?'));assert.equal(process.env.SPRING_DATA_REDIS_DATABASE,'13');
const long=process.argv.includes('--long'),duration=long?900:0;
const dir=`${root}/evidence/hotspot-${long?'long':'controlled'}-${Date.now()}`;mkdirSync(dir,{recursive:true});
const results=[],owned=[];let child,sequence=Date.now();
const pct=(v,p)=>v.length?[...v].sort((a,b)=>a-b)[Math.ceil(v.length*p)-1]:null;
async function metrics(){const r=await request('/v2/admin/pipeline',{admin:true});assert.equal(r.status,200);return r.data;}
async function until(fn,limit=120000){const began=Date.now();while(!(await fn())){if(Date.now()-began>limit)throw Error('Drain deadline');await sleep(250);}}
async function pool(items,fn,n=8){let index=0;await Promise.all(Array.from({length:n},async()=>{while(index<items.length){const i=index++;await fn(items[i],i);}}));}
function history(){return sql("SELECT (SELECT COUNT(*) FROM ux_activity),(SELECT COUNT(*) FROM ux_request),(SELECT COUNT(*) FROM ux_order),(SELECT COUNT(*) FROM ux_outbox),(SELECT COUNT(*) FROM ux_reservation_action),(SELECT COUNT(*) FROM ux_reservation_epoch)").split('\t').map(Number);}
function stages(before,after){return Object.fromEntries(Object.entries(after.timers.stages).map(([name,h])=>{const a=before.timers.stages[name],n=h.count-a.count,b=h.buckets.map((x,i)=>x-a.buckets[i]);let sum=0,p95=null;for(let i=0;i<b.length;i++){sum+=b[i];if(n&&sum>=Math.ceil(n*.95)){p95=after.timers.bucketUpperMs[i]??'>60000';break;}}return [name,{count:n,failures:h.failures-a.failures,meanMs:n?(h.sumMs-a.sumMs)/n:null,p95UpperMs:p95,buckets:b}];}));}
function sampler(){
 const samples=[];let closed=false,timer,error='';
 const p=spawn('docker',['compose','exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names --unbuffered'],{cwd:projectRoot,stdio:['pipe','pipe','pipe']});
 p.stdin.on('error',()=>{});p.stderr.on('data',x=>error+=x.toString());
 const query=()=>{if(!closed)p.stdin.write("SELECT ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000),COUNT(*),COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA='life_choice_verification' AND l.OBJECT_NAME='ux_activity';\n");};
 createInterface({input:p.stdout}).on('line',line=>{const [at,edges,transactions]=line.split('\t').map(Number);if(Number.isFinite(at))samples.push({at,edges,transactions});if(!closed)timer=setTimeout(query,200);});query();
 return {samples,close:async()=>{if(closed)return;closed=true;clearTimeout(timer);p.stdin.end();await new Promise(resolve=>{if(p.exitCode!==null)return resolve();p.once('exit',resolve);setTimeout(()=>p.kill(),2000).unref();});assert.equal(error.trim(),'');assert.ok(samples.length);}};
}
async function settle(id){await until(()=>Number(sql(`SELECT (SELECT COUNT(*) FROM ux_request WHERE activity_id=${id} AND state='ACCEPTED')+(SELECT COUNT(*) FROM ux_outbox WHERE activity_id=${id} AND sent_at IS NULL)+(SELECT COUNT(*) FROM ux_reservation_action a JOIN ux_request r ON r.id=a.request_id WHERE r.activity_id=${id} AND a.applied_at IS NULL)`))===0);}
function cleanup(id){
 assert.ok(owned.includes(id)&&Number.isSafeInteger(id));
 // Only fixtures created and recorded by this process. JVM is stopped and Kafka
 // has drained before removal; no shared tables or Redis databases are reset.
 sql(`START TRANSACTION;
 DELETE a FROM ux_reservation_action a JOIN ux_request r ON r.id=a.request_id WHERE r.activity_id=${id};
 DELETE FROM ux_order WHERE activity_id=${id}; DELETE FROM ux_outbox WHERE activity_id=${id};
 DELETE FROM ux_request WHERE activity_id=${id}; DELETE FROM ux_reservation_epoch WHERE activity_id=${id};
 DELETE FROM ux_activity WHERE id=${id}; DELETE FROM ux_invalidation WHERE cache_key='ux:shop:{${id}}';
 DELETE FROM ux_shop WHERE id=${id}; COMMIT;`);
 compose('exec','-T','redis','redis-cli','-n','13','UNLINK',...['stock','users','requests','pending','epoch'].map(k=>`ux:reserve:{${id}}:${k}`),...['stock','users','requests'].map(k=>`ux:bench:{${id}}:${k}`),`ux:shop:{${id}}`,`ux:shop:{${id}}:lock`,`ux:sold:{${id}}`);
}
async function run(compact,round){
 const name=`${round}-${compact?'compact':'legacy'}`;
 child=await start(`${dir}/${name}.log`,['--upgrade.rate-user=100','--upgrade.rate-activity=1000000','--upgrade.backlog-limit=1000000','--upgrade.relay-batch=100',`--upgrade.compact-transactions=${compact}`]);
 await kafkaDrained();await until(async()=>{const m=await metrics();return Object.values(m.queues).every(q=>Number(q.size)===0);});
 // In controlled runs require every historical order's normal confirmation window
 // to have expired first, so it cannot add RESTORE actions mid-comparison.
 if(!long)await until(()=>Number(sql("SELECT COUNT(*) FROM ux_order WHERE state='PENDING_CONFIRM'"))===0,360000);
 const historyBefore=history();const id=++sequence;owned.push(id);save(`${dir}/owned-fixtures.json`,owned);
 const total=long?duration*40:500;await fixture(id,total+50);
 const post=(index)=>request('/v2/requests',{user:id*1000+index,method:'POST',body:{requestId:`hotspot_${id}_${index}`,activityId:id}});
 await pool(Array.from({length:50},(_,i)=>i),async i=>assert.equal((await post(i)).status,202));await settle(id);await sleep(500);
 const before=await metrics(),samples=[],trace=[],locks=sampler();let collecting=true,traceError;
 const begin=performance.now(),epoch=Date.now();let inflight=0,maxInflight=0,skipped=0;
 const traceTask=(async()=>{while(collecting){const m=await metrics();trace.push({at:Date.now(),queues:m.queues,runtime:m.runtime,repair:m.repair});if(trace.length%6===0){const progress={name,elapsedSeconds:Math.round((performance.now()-begin)/1000),completedHttp:samples.length,inflight,skipped,queues:m.queues,runtime:m.runtime};save(`${dir}/progress.json`,progress);console.log(JSON.stringify(progress));}await sleep(long?5000:1000);}})().catch(e=>{traceError=e;});
 const send=async(i,scheduled=performance.now())=>{inflight++;maxInflight=Math.max(inflight,maxInflight);const sent=performance.now(),at=Date.now();try{const r=await post(i+50);samples.push({index:i,at,latencyMs:performance.now()-sent,scheduleDelayMs:sent-scheduled,status:r.status,id:r.data.id,error:r.data.error});}catch(e){samples.push({index:i,at,latencyMs:performance.now()-sent,status:0,error:e.message});}finally{inflight--;}};
 let valid=false;
 try {
  if(!long)await pool(Array.from({length:total},(_,i)=>i),i=>send(i));
  else {const pending=new Set();for(let i=0;i<total;i++){const target=begin+i*25,wait=target-performance.now();if(wait>0)await sleep(wait);if(inflight>=64){skipped++;continue;}const p=send(i,target);pending.add(p);p.finally(()=>pending.delete(p));}await Promise.all(pending);}
  const httpSeconds=(performance.now()-begin)/1000;const httpEnd=await metrics();await settle(id);const settledSeconds=(performance.now()-begin)/1000;
  const after=await metrics();collecting=false;await traceTask;await locks.close();if(traceError)throw traceError;
  // Persist before aggregation; a reporting failure must not discard the load samples.
  save(`${dir}/${name}-checkpoint.json`,{samples,trace,locks:locks.samples,before,after,epoch,httpSeconds,settledSeconds,skipped,maxInflight});
  const rows=[];let cursor='';
  while(true){
   const page=sql(`SELECT id,state,ROUND(UNIX_TIMESTAMP(completed_at)*1000) FROM ux_request WHERE activity_id=${id} AND id>'${cursor}' ORDER BY id LIMIT 500`).split('\n').filter(Boolean).map(x=>x.split('\t'));
   rows.push(...page);if(page.length<500)break;cursor=page.at(-1)[0];assert.match(cursor,/^[0-9a-f-]{36}$/);
  }
  const lookup=new Map(rows.map(([id,state,at])=>[id,{state,at:Number(at)}]));const statuses={},errors={};
  for(const s of samples){statuses[s.status]=(statuses[s.status]??0)+1;if(s.error)errors[s.error]=(errors[s.error]??0)+1;const r=lookup.get(s.id);if(r){s.finalState=r.state;s.endToEndMs=r.at-s.at;}}
  const success=samples.filter(s=>s.finalState==='SUCCEEDED');
  const windows=[];for(let sec=0;sec<(long?duration:httpSeconds);sec+=long?60:1){const a=samples.filter(s=>s.at>=epoch+sec*1000&&s.at<epoch+(sec+(long?60:1))*1000);windows.push({startSecond:sec,requests:a.length,success:a.filter(s=>s.finalState==='SUCCEEDED').length,httpP95Ms:pct(a.map(s=>s.latencyMs),.95),endToEndP95Ms:pct(a.filter(s=>s.endToEndMs!==undefined).map(s=>s.endToEndMs),.95)});}
  const result={name,compact,round,durationSeconds:duration,planned:total,requests:samples.length,skipped,maxInflight,statuses,errors,successfulOrders:success.length,httpSeconds,settledSeconds,completionProxyTps:success.length/((Math.max(...success.map(s=>s.at+s.endToEndMs))-epoch)/1000),settledTps:success.length/settledSeconds,httpP95Ms:pct(samples.map(s=>s.latencyMs),.95),endToEndP95Ms:pct(success.map(s=>s.endToEndMs),.95),scheduleP95Ms:pct(samples.map(s=>s.scheduleDelayMs??0),.95),stages:stages(before,after),historyBefore,queuesAtHttpEnd:httpEnd.queues,windows,locks:{samples:locks.samples.length,nonzero:locks.samples.filter(s=>s.transactions>0).length,peakTransactions:Math.max(...locks.samples.map(s=>s.transactions))},orderStates:sql(`SELECT state,COUNT(*) FROM ux_order WHERE activity_id=${id} GROUP BY state`),invariants:(await invariant()).invariants};
  save(`${dir}/${name}-raw.json`,{samples,trace,locks:locks.samples,before,after});results.push(result);save(`${dir}/results.json`,results);console.log(JSON.stringify({...result,stages:undefined,windows:undefined}));
  assert.ok(samples.every(s=>[202,429,503].includes(s.status)));assert.equal(success.length,samples.filter(s=>s.status===202).length);
  if(!long){assert.equal(skipped,0);assert.equal(success.length,total);}
  await kafkaDrained();valid=true;
 }finally{collecting=false;try{await traceTask;await locks.close();save(`${dir}/${name}-samples-preserved.json`,{samples,trace,locks:locks.samples,before,epoch,skipped,maxInflight});}finally{await stop(child);child=null;}}
 if(valid){cleanup(id);const afterCleanup=history();results.at(-1).historyAfterCleanup=afterCleanup;save(`${dir}/results.json`,results);if(!long)assert.deepEqual(afterCleanup,historyBefore,'Historical table cardinalities changed');}
}
try {
 save(`${dir}/environment.json`,{date:new Date().toISOString(),duration,rate:long?40:null,concurrency:long?null:8,instanceHeap:'384m',cpu:os.cpus()[0]?.model,memory:os.totalmem(),artifactSha256:createHash('sha256').update(readFileSync(`${root}/target/life-choice-backend-1.0.0.jar`)).digest('hex'),manifest:sourceManifest(),historyColumns:['ux_activity','ux_request','ux_order','ux_outbox','ux_reservation_action','ux_reservation_epoch'],scope:'Fixed logical historical row counts for controlled pairs; same instrumented binary and background settings; completion_at is a transaction-internal time proxy; lock samples include distinct waiting transactions.'});
 if(long)await run(true,1);else for(let round=1;round<=3;round++)for(const compact of round%2?[false,true]:[true,false])await run(compact,round);
 if(!long)for(const result of results)assert.deepEqual(result.historyBefore,results[0].historyBefore,"Historical cardinality differs across paired trials");
 console.log(`Evidence: ${dir}`);
}catch(e){save(`${dir}/failure.json`,{message:e.message,stack:e.stack,ownedFixtures:owned});throw e;}finally{await stop(child);}
