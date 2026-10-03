import assert from 'node:assert/strict';
import {mkdirSync,writeFileSync} from 'node:fs';
import {performance} from 'node:perf_hooks';
import os from 'node:os';
import {createServer,connect} from 'node:net';
import {root,projectRoot,start,stop,request,fixture,invariant,sql,compose,sleep,save,terminal,kafkaDrained,sourceManifest} from './harness.mjs';

if(process.env.UPGRADE_DB_SCHEMA!=='life_choice_verification' || !process.env.UPGRADE_DB_URL?.includes('/life_choice_verification?') || process.env.SPRING_DATA_REDIS_DATABASE!=='13')
  throw new Error('Run through scripts/verification.sh; this experiment only accepts the isolated verification environment.');
const mode=process.argv.includes('--capacity')?'capacity':'faults';
const smoke=process.argv.includes('--smoke');
const dir=`${root}/evidence/verification-${mode}-${Date.now()}`;
mkdirSync(dir,{recursive:true});mkdirSync(`${dir}/faults`,{recursive:true});
const results=[];let child;let sequence=Date.now();
const next=()=>++sequence;
let proxy,redisAvailable=true;
const sockets=new Set();
async function redisProxy() {
  proxy=createServer(client=>{
    if(!redisAvailable) {client.destroy();return;}
    const upstream=connect(Number(process.env.REDIS_PORT??26380),'127.0.0.1');
    sockets.add(client);sockets.add(upstream);
    client.pipe(upstream);upstream.pipe(client);
    client.on('error',()=>upstream.destroy());upstream.on('error',()=>client.destroy());
    client.on('close',()=>{sockets.delete(client);upstream.destroy();});
    upstream.on('close',()=>{sockets.delete(upstream);client.destroy();});
  });
  await new Promise((resolve,reject)=>{proxy.once('error',reject);proxy.listen(0,'127.0.0.1',resolve);});
  return proxy.address().port;
}
function interruptRedis() {redisAvailable=false;for(const socket of sockets) socket.destroy();}
const extra=['--upgrade.faults=true',`--upgrade.fault-directory=${dir}/faults`,
  '--upgrade.rate-user=100','--upgrade.rate-activity=1000000','--upgrade.backlog-limit=1000000'];
const launch=async()=>{child=await start(`${dir}/server.log`,extra);};
const count=(activity)=>Number(sql(`SELECT COUNT(*) FROM ux_order WHERE activity_id=${activity}`));
async function until(check,timeout=30000) {
  const began=Date.now();
  do {if(await check()) return Date.now()-began;await sleep(150);} while(Date.now()-began<timeout);
  throw new Error('Condition did not converge');
}
async function submit(activity,user,key) {
  return request('/v2/requests',{user,method:'POST',body:{requestId:key,activityId:activity}});
}
function redis(...args) {return compose('exec','-T','redis','redis-cli','-n','13',...args).trim();}
async function settled(activity) {
  await until(()=>Number(sql(`SELECT COUNT(*) FROM ux_request WHERE activity_id=${activity} AND state='ACCEPTED'`))===0,120000);
  await until(()=>Number(sql(`SELECT COUNT(*) FROM ux_reservation_action a JOIN ux_request r ON r.id=a.request_id WHERE r.activity_id=${activity} AND a.applied_at IS NULL`))===0,30000);
}
async function crash(point,action) {
  writeFileSync(`${dir}/faults/${point}`,'one-shot\n');
  try {await action();}catch{} // A lost HTTP response is expected for some crash points.
  await until(()=>child.exitCode!==null,15000);
  assert.equal(child.exitCode,91);await launch();
}
const percentile=(values,p)=>{const sorted=[...values].sort((a,b)=>a-b);return sorted[Math.min(sorted.length-1,Math.ceil(sorted.length*p)-1)]??null;};
try {
  if(mode==='faults') extra.push(`--spring.data.redis.port=${await redisProxy()}`);
  await launch();
  save(`${dir}/environment.json`,{date:new Date().toISOString(),mode,smoke,platform:os.platform(),arch:os.arch(),cpu:os.cpus()[0]?.model,
    manifest:sourceManifest(),cpuCount:os.cpus().length,totalMemory:os.totalmem(),database:'life_choice_verification',redisDatabase:13,topic:process.env.UPGRADE_TOPIC,heap:'384m',instances:1,
    kafkaSessionTimeoutMs:Number(process.env.UPGRADE_KAFKA_SESSION_TIMEOUT_MS??15000),kafkaHeartbeatMs:Number(process.env.UPGRADE_KAFKA_HEARTBEAT_MS??3000),
    scope:'Real HTTP, Redis, MySQL, Kafka on one host. Process crashes affect only the child JVM started by this script.'});
  if(mode==='faults') {
    for(const point of ['reserve-before-db','accept-before-response','send-before-mark','commit-before-ack']) {
      await kafkaDrained();
      const activity=next(),user=next(),key=`fault_${activity}`;await fixture(activity,1);
      await crash(point,()=>submit(activity,user,key));
      const began=Date.now();
      await until(()=>sql(`SELECT state FROM ux_request WHERE activity_id=${activity}`)!=='',30000);
      await settled(activity);
      const state=sql(`SELECT state FROM ux_request WHERE activity_id=${activity}`);
      assert.equal(state,point==='reserve-before-db'?'EXPIRED':'SUCCEEDED');
      assert.equal(count(activity),point==='reserve-before-db'?0:1);
      const replay=await submit(activity,user,key);assert.equal(replay.status,200);assert.equal(replay.data.state,state);
      const stock=Number(redis('GET',`ux:reserve:{${activity}}:stock`));assert.equal(stock,point==='reserve-before-db'?1:0);
      results.push({point,state,orders:count(activity),redisStock:stock,convergenceAfterStartupMs:Date.now()-began,invariants:(await invariant()).invariants});
      save(`${dir}/results.json`,results);console.log(JSON.stringify(results.at(-1)));
    }
    {
      const activity=next(),user=next(),key=`cancel_${activity}`;await fixture(activity,1);
      const accepted=await submit(activity,user,key);assert.equal(accepted.status,202);await terminal(user,accepted.data.id);await settled(activity);
      await crash('cancel-before-commit',()=>request(`/v2/requests/${accepted.data.id}/cancel`,{user,method:'POST'}));
      assert.equal(sql(`SELECT state FROM ux_order WHERE activity_id=${activity}`),'PENDING_CONFIRM');
      assert.equal(Number(sql(`SELECT available FROM ux_activity WHERE id=${activity}`)),0);
      for(let i=0;i<2;i++) assert.equal((await request(`/v2/requests/${accepted.data.id}/cancel`,{user,method:'POST'})).data.state,'CANCELLED');
      await settled(activity);assert.equal(Number(redis('GET',`ux:reserve:{${activity}}:stock`)),1);
      results.push({point:'cancel-before-commit',state:'CANCELLED',redisStock:1,orders:count(activity),invariants:(await invariant()).invariants});
      save(`${dir}/results.json`,results);console.log(JSON.stringify(results.at(-1)));
    }
    {
      const activity=next(),user=next(),key=`loss_${activity}`;await fixture(activity,2);
      const accepted=await submit(activity,user,key);assert.equal(accepted.status,202);await terminal(user,accepted.data.id);await settled(activity);
      redis('DEL',`ux:reserve:{${activity}}:stock`);
      const rejected=await submit(activity,user+1,`lost_${activity}`);assert.equal(rejected.status,503);
      assert.equal((await submit(activity,user,key)).data.id,accepted.data.id);
      const recovery=await request(`/v2/admin/activities/${activity}/recover`,{admin:true,method:'POST'});assert.equal(recovery.status,200);
      assert.equal(Number(redis('GET',`ux:reserve:{${activity}}:stock`)),1);
      assert.equal((await submit(activity,user,`duplicate_${activity}`)).status,409);
      const newOrder=await submit(activity,user+1,`new_${activity}`);assert.equal(newOrder.status,202);await terminal(user+1,newOrder.data.id);await settled(activity);
      assert.equal(count(activity),2);assert.equal(Number(redis('GET',`ux:reserve:{${activity}}:stock`)),0);
      results.push({point:'redis-stock-loss',rejectedWhileMissing:rejected.status,orders:2,redisStock:0,invariants:(await invariant()).invariants});
      save(`${dir}/results.json`,results);console.log(JSON.stringify(results.at(-1)));
    }
    {
      const activity=next(),user=next();await fixture(activity,2);
      const accepted=await submit(activity,user,`outage_${activity}`);assert.equal(accepted.status,202);
      await terminal(user,accepted.data.id);await settled(activity);
      const before=(await invariant()).cache;
      assert.equal((await request(`/v2/shops/${activity}`,{user})).status,200);
      const began=Date.now();interruptRedis();
      assert.equal((await request(`/v2/shops/${activity}`,{user})).status,200,'Warm L1 must survive Redis outage');
      assert.equal((await submit(activity,user+1,`blocked_${activity}`)).status,503);
      assert.equal((await request(`/v2/requests/${accepted.data.id}`,{user})).status,200);
      const update=await request(`/v2/admin/shops/${activity}`,{admin:true,method:'PUT',body:{name:'After Redis outage',description:'Durable invalidation'}});
      assert.equal(update.status,200);
      assert.equal(Number(sql(`SELECT COUNT(*) FROM ux_invalidation WHERE cache_key='ux:shop:{${activity}}'`)),1);
      await sleep(1100);
      const responses=await Promise.all(Array.from({length:100},(_,i)=>request(`/v2/shops/${activity}`,{user:user+100+i})));
      const statuses={};for(const response of responses) statuses[response.status]=(statuses[response.status]??0)+1;
      assert.ok(statuses[200]>0);assert.ok(statuses[503]>0);
      assert.ok(responses.every(response=>[200,503].includes(response.status)));
      redisAvailable=true;
      await until(async()=>{try {return (await request('/v2/admin/status',{admin:true})).status===200;}catch{return false;}},15000);
      await until(()=>Number(sql(`SELECT COUNT(*) FROM ux_invalidation WHERE cache_key='ux:shop:{${activity}}'`))===0);
      assert.equal((await request(`/v2/shops/${activity}`,{user})).data.name,'After Redis outage');
      const after=(await invariant()).cache;
      results.push({point:'redis-connection-outage',cacheStatuses:statuses,newPurchaseStatus:503,existingOrderQueryStatus:200,
        fallbackAttempts:after.fallbacks-before.fallbacks,fallbackRejected:after.fallbackRejected-before.fallbackRejected,
        recoveredWithinMs:Date.now()-began,invalidationRetried:true,invariants:(await invariant()).invariants});
      save(`${dir}/results.json`,results);console.log(JSON.stringify(results.at(-1)));
    }
  } else {
    const total=smoke?100:500,rounds=smoke?1:3,concurrencies=smoke?[4]:[4,8,12];
    for(let round=1;round<=rounds;round++) for(const concurrency of concurrencies) {
      const activity=next();await fixture(activity,total);const samples=[];let cursor=0;const began=performance.now();
      await Promise.all(Array.from({length:concurrency},()=> (async()=>{
        while(true) {
          const index=cursor++;if(index>=total) return;const sent=performance.now(),epoch=Date.now();
          try {
            const response=await submit(activity,activity*1000+index,`capacity_${activity}_${index}`);
            samples.push({index,sentEpoch:epoch,latencyMs:performance.now()-sent,status:response.status,id:response.data.id,error:response.data.error});
          } catch(error) {samples.push({index,sentEpoch:epoch,latencyMs:performance.now()-sent,status:0,error:error.message});}
        }
      })()));
      const httpSeconds=(performance.now()-began)/1000;
      const [backlogAtHttpEnd,unsentAtHttpEnd]=sql(`SELECT
        (SELECT COUNT(*) FROM ux_request WHERE activity_id=${activity} AND state='ACCEPTED'),
        (SELECT COUNT(*) FROM ux_outbox WHERE activity_id=${activity} AND sent_at IS NULL)`)
        .split('\t').map(Number);
      await settled(activity);const totalSeconds=(performance.now()-began)/1000;
      const rows=sql(`SELECT id,state,ROUND(UNIX_TIMESTAMP(completed_at)*1000) FROM ux_request WHERE activity_id=${activity}`)
        .split('\n').filter(Boolean).map(row=>{const [id,state,at]=row.split('\t');return {id,state,at:Number(at)};});
      const byId=new Map(rows.map(r=>[r.id,r]));
      for(const sample of samples) {const row=byId.get(sample.id);if(row) {sample.finalState=row.state;sample.endToEndMs=row.at-sample.sentEpoch;}}
      const successes=samples.filter(s=>s.finalState==='SUCCEEDED'),statuses={};for(const sample of samples) statuses[sample.status]=(statuses[sample.status]??0)+1;
      const databaseCompletionSeconds=successes.length===0?null:
        (Math.max(...successes.map(s=>s.sentEpoch+s.endToEndMs))-Math.min(...samples.map(s=>s.sentEpoch)))/1000;
      const result={round,concurrency,requests:total,statuses,successfulOrders:successes.length,httpSeconds,settledSeconds:totalSeconds,backlogAtHttpEnd,unsentAtHttpEnd,
        databaseCompletionSeconds,databaseOrderTps:databaseCompletionSeconds>0?successes.length/databaseCompletionSeconds:null,
        successfulOrderTps:successes.length/totalSeconds,httpP95Ms:percentile(samples.map(s=>s.latencyMs),.95),
        endToEndP95Ms:percentile(successes.map(s=>s.endToEndMs),.95),invariants:(await invariant()).invariants};
      results.push(result);save(`${dir}/${round}-${concurrency}-raw.json`,samples);save(`${dir}/results.json`,results);console.log(JSON.stringify(result));
      assert.equal(successes.length,total,'Only all-success runs qualify as successful-order capacity observations');
      assert.equal(count(activity),total);
    }
  }
  console.log(`Evidence: ${dir}`);
} catch(error) {save(`${dir}/failure.json`,{message:error.message,stack:error.stack,completed:results});throw error;}
finally {
  redisAvailable=true;await stop(child);
  for(const socket of sockets) socket.destroy();
  if(proxy) await new Promise(resolve=>proxy.close(resolve));
}
