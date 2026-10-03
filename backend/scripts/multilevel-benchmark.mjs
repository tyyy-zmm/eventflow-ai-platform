import {spawn} from 'node:child_process';
import {mkdirSync,openSync,closeSync} from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {performance} from 'node:perf_hooks';
import {root,token,stop,sleep,save,sourceManifest} from './harness.mjs';

if(!process.env.UPGRADE_TEST_DB_URL) throw new Error('Set UPGRADE_TEST_DB_URL to an isolated test database first.');
const smoke=process.argv.includes('--smoke');
const seconds=smoke?2:Number(process.env.CACHE_BENCH_SECONDS??30);
const rounds=smoke?1:Number(process.env.CACHE_BENCH_ROUNDS??3);
const concurrency=Number(process.env.CACHE_BENCH_CONCURRENCY??8);
if(!Number.isFinite(seconds)||seconds<1||!Number.isInteger(rounds)||rounds<1||!Number.isInteger(concurrency)||concurrency<1||concurrency>48)
  throw new Error('Invalid benchmark settings');
const bases=['http://127.0.0.1:18093','http://127.0.0.1:18094'];
const dir=path.join(root,'evidence',`multilevel-${smoke?'smoke':'benchmark'}-${Date.now()}`);
mkdirSync(dir,{recursive:true});
const children=[],results=[];
const adminAuthorization=`Bearer ${token(900,'admin')}`;
const userAuthorizations=Array.from({length:1024},(_,i)=>`Bearer ${token(4_000_000+i,'user')}`);
let requestSequence=0;
async function request(instance,route,{method='GET',body,authorization=adminAuthorization}={}) {
  const response=await fetch(bases[instance]+route,{method,headers:{Authorization:authorization,'Content-Type':'application/json'},
    body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(5000)});
  const data=await response.json();return {status:response.status,data};
}
async function checked(instance,route,options) {
  const response=await request(instance,route,options);
  if(response.status!==200) throw new Error(`${route}: ${JSON.stringify(response)}`);
  return response.data;
}
async function launch(index) {
  try {await fetch(bases[index],{signal:AbortSignal.timeout(500)});throw new Error(`Port ${18093+index} is already in use`);}
  catch(error) {if(error.message.includes('already in use')) throw error;}
  const fd=openSync(path.join(dir,`server-${index}.log`),'a',0o600);
  const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java'):'java';
  const child=spawn(java,['-Xmx384m','-jar','target/life-choice-backend-1.0.0.jar',`--server.port=${18093+index}`,
    '--upgrade.benchmark=true','--upgrade.jobs=false','--spring.kafka.admin.auto-create=false',
    `--spring.data.redis.database=${process.env.UPGRADE_TEST_REDIS_DB??13}`],
    {cwd:root,env:{...process.env,UPGRADE_DB_URL:process.env.UPGRADE_TEST_DB_URL},stdio:['ignore',fd,fd]});
  children.push(child);closeSync(fd);
  for(let i=0;i<120;i++) {
    if(child.exitCode!==null) throw new Error(`Instance ${index} exited; inspect its log`);
    try {await checked(index,'/v2/admin/status');return;}catch{}
    await sleep(250);
  }
  throw new Error(`Instance ${index} startup timeout`);
}
const percentile=(values,p)=>values.length?[...values].sort((a,b)=>a-b)[Math.min(values.length-1,Math.ceil(values.length*p)-1)]:null;
try {
  await launch(0);await launch(1);
  const id=Date.now();
  await checked(0,`/v2/admin/fixtures/${id}?stock=10`,{method:'POST'});
  await checked(1,`/v2/shops/${id}`);
  await checked(0,`/v2/admin/shops/${id}`,{method:'PUT',body:{name:'Changed across instances',description:'Synthetic benchmark'}});
  const updated=performance.now();let observed=false;
  do {
    if((await checked(1,`/v2/shops/${id}`)).name==='Changed across instances') {observed=true;break;}
    await sleep(10);
  } while(performance.now()-updated<2000);
  if(!observed) throw new Error('Cross-instance invalidation failed');
  save(path.join(dir,'environment.json'),{date:new Date().toISOString(),node:process.version,platform:os.platform(),arch:os.arch(),cpu:os.cpus()[0]?.model,
    manifest:sourceManifest(),cpuCount:os.cpus().length,totalMemory:os.totalmem(),hostLoad:os.loadavg(),instances:2,heapPerInstance:'384m',concurrency,seconds,rounds,smoke,syntheticUsers:1024,invalidationObservedMs:performance.now()-updated,
    scope:'Closed-loop authenticated HTTP on one host; successful shop reads only. Not maximum capacity or order TPS.'});
  const modes=['direct','ttl','optimized'];
  for(let round=0;round<rounds;round++) for(let offset=0;offset<modes.length;offset++) {
    const mode=modes[(round+offset)%modes.length];
    const route=mode==='optimized'?`/v2/shops/${id}`:`/v2/benchmark/${mode}/${id}`;
    for(let i=0;i<100;i++) await checked(i%2,route,{authorization:userAuthorizations[i%userAuthorizations.length]});
    const before=await Promise.all(bases.map((_,i)=>checked(i,'/v2/admin/status')));
    const samples=[],started=performance.now();
    await Promise.all(Array.from({length:concurrency},(_,worker)=>(async()=>{
      while(performance.now()-started<seconds*1000) {
        const sent=performance.now();
        try {const r=await request(worker%2,route,{authorization:userAuthorizations[requestSequence++%userAuthorizations.length]});samples.push({latencyMs:performance.now()-sent,status:r.status});}
        catch(error) {samples.push({latencyMs:performance.now()-sent,status:0,error:error.message});}
      }
    })()));
    const elapsedSeconds=(performance.now()-started)/1000;
    const after=await Promise.all(bases.map((_,i)=>checked(i,'/v2/admin/status')));
    const success=samples.filter(s=>s.status===200),statuses={};
    for(const sample of samples) statuses[sample.status]=(statuses[sample.status]??0)+1;
    const delta=key=>after.reduce((sum,item,i)=>sum+item.cache[key]-before[i].cache[key],0);
    const result={round:round+1,mode,elapsedSeconds,requests:samples.length,statuses,errorRate:1-success.length/samples.length,successfulQps:success.length/elapsedSeconds,
      successP95Ms:percentile(success.map(s=>s.latencyMs),.95),successP99Ms:percentile(success.map(s=>s.latencyMs),.99),
      databaseReads:delta('databaseReads'),localHits:delta('localHits'),redisHits:delta(mode==='ttl'?'ttlHits':'hits')};
    results.push(result);save(path.join(dir,`${round+1}-${mode}-raw.json`),samples);save(path.join(dir,'results.json'),results);
    console.log(JSON.stringify(result));
  }
  const median=values=>percentile(values,.5);
  const aggregate=Object.fromEntries(modes.map(mode=>{
    const rows=results.filter(x=>x.mode===mode);
    return [mode,{medianQps:median(rows.map(x=>x.successfulQps)),medianP95Ms:median(rows.map(x=>x.successP95Ms)),
      medianP99Ms:median(rows.map(x=>x.successP99Ms)),totalDatabaseReads:rows.reduce((n,x)=>n+x.databaseReads,0),
      totalRequests:rows.reduce((n,x)=>n+x.requests,0),errorRate:rows.reduce((n,x)=>n+x.requests*x.errorRate,0)/rows.reduce((n,x)=>n+x.requests,0)}];
  }));
  const ratio=(value,base)=>base===0?null:value/base;
  const summary={rounds,seconds,concurrency,aggregate,comparisons:{
    optimizedVsDirect:{qpsRatio:ratio(aggregate.optimized.medianQps,aggregate.direct.medianQps),p95Reduction:1-ratio(aggregate.optimized.medianP95Ms,aggregate.direct.medianP95Ms)},
    optimizedVsRedisTtl:{qpsRatio:ratio(aggregate.optimized.medianQps,aggregate.ttl.medianQps),p95Reduction:1-ratio(aggregate.optimized.medianP95Ms,aggregate.ttl.medianP95Ms)}
  }};
  save(path.join(dir,'summary.json'),summary);console.log(JSON.stringify(summary));
  console.log(`Evidence: ${dir}`);
} catch(error) {save(path.join(dir,'failure.json'),{message:error.message,completed:results});throw error;}
finally {for(const child of children.reverse()) await stop(child);}
