import {mkdirSync,readFileSync,readdirSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import os from 'node:os';
import {performance} from 'node:perf_hooks';
import {root,start,stop,request,fixture,invariant,sql,sleep,save} from './harness.mjs';
const duration=Number(process.env.BENCH_SECONDS??60),rounds=Number(process.env.BENCH_ROUNDS??3);
const smoke=process.argv.includes('--smoke');
if(!smoke && (duration<60 || rounds<3)) throw new Error('Formal runs require >=60 seconds and >=3 rounds');
const seconds=smoke?3:duration,repeats=smoke?1:rounds;
const dir=`${root}/evidence/${smoke?'smoke':'benchmark'}-${Date.now()}`;mkdirSync(dir,{recursive:true});
const results=[];let child;let sequence=Date.now();
function quantile(values,p) {if(!values.length)return null;const sorted=[...values].sort((a,b)=>a-b);return sorted[Math.min(sorted.length-1,Math.ceil(sorted.length*p)-1)];}
function dockerStats(){return execFileSync('docker',['stats','--no-stream','--format','{{json .}}','life-choice-mysql-1','life-choice-redis-1','life-choice-kafka-1'],{encoding:'utf8',timeout:15000}).trim().split('\n').map(JSON.parse);}
function manifest(dir){const out={};function visit(folder){for(const e of readdirSync(folder,{withFileTypes:true})){const p=`${folder}/${e.name}`;if(e.isDirectory())visit(p);else out[p.slice(root.length)]=createHash('sha256').update(readFileSync(p)).digest('hex');}}visit(`${root}/src`);visit(`${root}/scripts`);return out;}
async function load(mode,activity,rate,seconds) {
  const samples=[],inflight=new Set();let skipped=0;
  const start=performance.now();const planned=Math.floor(rate*seconds);
  for(let i=0;i<planned;i++) {
    const scheduled=start+i*1000/rate;
    await sleep(Math.max(0,scheduled-performance.now()));
    if(inflight.size>=64){skipped++;continue;}
    const sent=performance.now(),epoch=Date.now(),user=100000+i;
    const txn=mode==='sync'||mode==='async';
    const route=txn?(mode==='sync'?'/v2/benchmark/sync':'/v2/requests'):
      mode==='optimized'?`/v2/shops/${activity}`:`/v2/benchmark/${mode}/${activity}`;
    const promise=(async()=>{
      try {
        const response=await request(route,{user,method:txn?'POST':'GET',body:txn?{requestId:`bench_${activity}_${i}`,activityId:activity}:undefined});
        samples.push({i,epoch,schedulerDelayMs:sent-scheduled,latencyMs:performance.now()-sent,status:response.status,
          state:response.data.state??null,error:response.data.error??response.data.reason??null,requestId:txn?response.data.id:null});
      }catch(e){samples.push({i,epoch,schedulerDelayMs:sent-scheduled,latencyMs:performance.now()-sent,status:0,error:e.message});}
    })();
    inflight.add(promise);promise.finally(()=>inflight.delete(promise));
  }
  await Promise.all(inflight);
  await sleep(Math.max(0,start+seconds*1000-performance.now()));
  return {samples,skipped,elapsedSeconds:(performance.now()-start)/1000};
}
try {
  child=await start(`${dir}/server.log`);
  save(`${dir}/environment.json`,{started:new Date().toISOString(),platform:os.platform(),arch:os.arch(),cpus:os.cpus().length,
    model:os.cpus()[0]?.model,totalMemory:os.totalmem(),load:os.loadavg(),heap:'384m',pool:12,threads:48,
    scope:'Authenticated HTTP; all three isolated single-node middleware and load generator on same host. Not production peak capacity.',
    softTtlMs:5000,hardTtlMs:15000,windowSeconds:seconds,rounds:repeats,manifest:manifest(),docker:dockerStats()});
  // Rotate ordering by round to reduce systematic warmup/order bias.
  const modes=['sync','async','direct','ttl','optimized'];
  for(let round=0;round<repeats;round++) for(let index=0;index<modes.length;index++) {
    const mode=modes[(index+round)%modes.length],activity=++sequence,rate=mode==='sync'||mode==='async'?50:100;
    await fixture(activity);
    for(let i=0;i<20;i++) await request(`/v2/shops/${activity}`,{user:activity});
    await sleep(smoke?100:3000);
    const before=await invariant(),resourcesBefore=dockerStats(),cpuBefore=process.cpuUsage(),started=Date.now();
    console.log(`START round=${round+1} mode=${mode} target=${rate}/s seconds=${seconds}`);
    const loadResult=await load(mode,activity,rate,seconds);
    const backlogAtEnd=Number(sql(`SELECT COUNT(*) FROM ux_request WHERE activity_id=${activity} AND state='ACCEPTED'`));
    const drainStart=Date.now();
    while(Number(sql(`SELECT COUNT(*) FROM ux_request WHERE activity_id=${activity} AND state='ACCEPTED'`))>0) {
      if(Date.now()-drainStart>120000) throw new Error('Backlog did not drain');await sleep(250);
    }
    const orderStates=sql(`SELECT state,COUNT(*) FROM ux_request WHERE activity_id=${activity} GROUP BY state`);
    const timings=sql(`SELECT id,ROUND(UNIX_TIMESTAMP(created_at)*1000),ROUND(UNIX_TIMESTAMP(completed_at)*1000),state FROM ux_request WHERE activity_id=${activity}`)
      .split('\n').filter(Boolean).map(line=>{const [id,created,completed,state]=line.split('\t');return {id,created:Number(created),completed:Number(completed),state};});
    const lookup=new Map(timings.map(r=>[r.id,r]));
    for(const sample of loadResult.samples){const db=lookup.get(sample.requestId);if(db){sample.finalState=db.state;sample.serverCompletionMs=db.completed-db.created;sample.endToEndMs=db.completed-sample.epoch;}}
    const after=await invariant(),statuses={};for(const r of loadResult.samples)statuses[r.status]=(statuses[r.status]??0)+1;
    const success=timings.filter(r=>r.state==='SUCCEEDED');
    const result={round:round+1,mode,activity,rate,seconds,started,statuses,
      sent:loadResult.samples.length,skippedByLoadGenerator:loadResult.skipped,actualSendQps:loadResult.samples.length/loadResult.elapsedSeconds,
      admitted:loadResult.samples.filter(r=>r.status===202).length,succeeded:success.length,
      completedDuringWindow:success.filter(r=>r.completed<=started+seconds*1000).length,
      successTpsDuringWindow:success.filter(r=>r.completed<=started+seconds*1000).length/seconds,
      latencyP95:quantile(loadResult.samples.map(r=>r.latencyMs),.95),latencyP99:quantile(loadResult.samples.map(r=>r.latencyMs),.99),
      endToEndP95:quantile(loadResult.samples.map(r=>r.endToEndMs).filter(Number.isFinite),.95),
      schedulerDelayP95:quantile(loadResult.samples.map(r=>r.schedulerDelayMs),.95),
      backlogAtEnd,drainMs:Date.now()-drainStart,orderStates,
      databaseReads:after.cache.databaseReads-before.cache.databaseReads,invariants:after.invariants,
      loadGeneratorCpu:process.cpuUsage(cpuBefore),hostLoad:os.loadavg(),resourcesBefore,resourcesAfter:dockerStats()};
    save(`${dir}/${round+1}-${mode}-raw.json`,loadResult.samples);save(`${dir}/${round+1}-${mode}-db.json`,timings);
    results.push(result);save(`${dir}/results.json`,results);
    console.log(`DONE ${mode}: sent=${result.sent} statuses=${JSON.stringify(statuses)} p95=${result.latencyP95?.toFixed(2)}ms finalOrders=${result.succeeded} sqlReads=${result.databaseReads}`);
  }
  console.log(`Evidence: ${dir}`);
} catch(e){save(`${dir}/failure.json`,{message:e.message,stack:e.stack,completed:results});throw e;}
finally {await stop(child);}
