import {spawn} from 'node:child_process';
import {mkdirSync,openSync,closeSync,readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import path from 'node:path';
import os from 'node:os';
import {performance} from 'node:perf_hooks';
import {root,token,stop,sleep,save,sourceManifest} from './harness.mjs';

if(!process.env.UPGRADE_TEST_DB_URL) throw new Error('Set UPGRADE_TEST_DB_URL to an isolated test database first.');
const smoke=process.argv.includes('--smoke');
const seconds=smoke?2:Number(process.env.CACHE_BENCH_SECONDS??30);
const rounds=smoke?1:Number(process.env.CACHE_BENCH_ROUNDS??3);
const concurrency=Number(process.env.CACHE_BENCH_CONCURRENCY??8);
const shopCount=Number(process.env.CACHE_BENCH_SHOPS??1);
const authMode=process.env.CACHE_BENCH_AUTH??'bearer';
const distribution=process.env.CACHE_BENCH_DISTRIBUTION??'uniform';
const warmupSeconds=Number(process.env.CACHE_BENCH_WARMUP_SECONDS??0);
const modes=(process.env.CACHE_BENCH_MODES??'direct,ttl,optimized').split(',');
if(!['uniform','hot80'].includes(distribution)||!Number.isFinite(warmupSeconds)||warmupSeconds<0||warmupSeconds>60) throw new Error('Invalid workload');
if(new Set(modes).size!==modes.length||!modes.includes('ttl')||!modes.includes('optimized')||modes.some(x=>!['direct','ttl','optimized'].includes(x))) throw new Error('Invalid modes');
// Deterministic 80% to the hottest 10% of shops; same starting sequence per mode.
function shopOffset(sequence) {
  if(distribution==='uniform'||shopCount===1) return sequence%shopCount;
  const hot=Math.max(1,Math.floor(shopCount*.1)),slot=sequence%10,cycle=Math.floor(sequence/10);
  return slot<8 ? (cycle*8+slot)%hot : hot+(cycle*2+slot-8)%(shopCount-hot);
}
if(!['bearer','cookie'].includes(authMode)) throw new Error('Invalid auth mode');
if(!Number.isInteger(shopCount)||shopCount<1||shopCount>100) throw new Error('Invalid shop count');
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
  const response=await fetch(bases[instance]+route,{method,headers:{...(authorization.startsWith('Cookie ')?{Cookie:authorization.slice(7)}:{Authorization:authorization}),'Content-Type':'application/json'},
    body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(5000)});
  const data=await response.json();return {status:response.status,data,cookie:response.headers.get('set-cookie')};
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
  for(let n=1;n<shopCount;n++) {
    await sleep(30);
    await checked(0,`/v2/admin/fixtures/${id+n}?stock=10`,{method:'POST'});
    await checked(0,`/v2/admin/shops/${id+n}`,{method:'PUT',body:{name:'Changed across instances',description:'Synthetic benchmark'}});
  }
  await checked(0,`/v2/admin/fixtures/${id}?stock=10`,{method:'POST'});
  await checked(1,`/v2/shops/${id}`);
  await checked(0,`/v2/admin/shops/${id}`,{method:'PUT',body:{name:'Changed across instances',description:'Synthetic benchmark'}});
  const updated=performance.now();let observed=false;
  do {
    if((await checked(1,`/v2/shops/${id}`)).name==='Changed across instances') {observed=true;break;}
    await sleep(10);
  } while(performance.now()-updated<2000);
  if(!observed) throw new Error('Cross-instance invalidation failed');
  const invalidationObservedMs=performance.now()-updated;
  if(authMode==='cookie') {
    userAuthorizations.length=0;
    for(let n=0;n<128;n++) {
      let registered;
      for(let attempt=0;attempt<20;attempt++) {
        registered=await request(n%2,'/v2/auth/register',{method:'POST',body:{username:`bench_${id}_${n}`,password:`local-benchmark-${id}-${n}`,displayName:'Synthetic benchmark'}});
        if(registered.status!==429) break;
        await sleep(5000);
      }
      if(registered.status!==201 || !registered.cookie) throw new Error(`Benchmark session registration failed: status=${registered.status}, code=${registered.data.error}`);
      userAuthorizations.push('Cookie '+registered.cookie.split(';')[0]);
    }
  }
  save(path.join(dir,'environment.json'),{date:new Date().toISOString(),node:process.version,platform:os.platform(),arch:os.arch(),cpu:os.cpus()[0]?.model,
    manifest:sourceManifest(),jarSha256:createHash('sha256').update(readFileSync(path.join(root,'target/life-choice-backend-1.0.0.jar'))).digest('hex'),authMode,shopCount,distribution,warmupSeconds,modes,cpuCount:os.cpus().length,totalMemory:os.totalmem(),hostLoad:os.loadavg(),instances:2,heapPerInstance:'384m',concurrency,seconds,rounds,smoke,syntheticUsers:userAuthorizations.length,invalidationObservedMs,
    scope:'Closed-loop authenticated HTTP on one host; successful shop reads only. Not maximum capacity or order TPS. Bearer mode excludes cookie session database lookup; cookie mode includes it.'});
  for(let round=0;round<rounds;round++) for(let offset=0;offset<modes.length;offset++) {
    const mode=modes[(round+offset)%modes.length];
    const routeFor=shop=>mode==='optimized'?`/v2/shops/${shop}`:`/v2/benchmark/${mode}/${shop}`;
    for(let i=0;i<Math.max(100,shopCount*2);i++) await checked(i%2,routeFor(id+i%shopCount),{authorization:userAuthorizations[i%userAuthorizations.length]});
    const warmStarted=performance.now();
    await Promise.all(Array.from({length:concurrency},(_,worker)=>(async()=>{
      let n=worker;
      while(performance.now()-warmStarted<warmupSeconds*1000) {
        await request(worker%2,routeFor(id+shopOffset(n)),{authorization:userAuthorizations[n%userAuthorizations.length]});
        n+=concurrency;
      }
    })()));
    requestSequence=0;
    const clientCpu=process.cpuUsage(),hostLoadBefore=os.loadavg();
    const before=await Promise.all(bases.map((_,i)=>checked(i,'/v2/admin/status')));
    const samples=[],started=performance.now();
    await Promise.all(Array.from({length:concurrency},(_,worker)=>(async()=>{
      while(performance.now()-started<seconds*1000) {
        const sent=performance.now();
        const sequence=requestSequence++,shop=id+shopOffset(sequence);
        try {const r=await request(worker%2,routeFor(shop),{authorization:userAuthorizations[sequence%userAuthorizations.length]});const valid=r.status===200 && r.data.id===shop && r.data.name==='Changed across instances' && r.data.description==='Synthetic benchmark';samples.push({latencyMs:performance.now()-sent,status:r.status,valid,...(!valid?{errorCode:r.data.error??'INVALID_BODY'}:{})});}
        catch(error) {samples.push({latencyMs:performance.now()-sent,status:0,error:error.message});}
      }
    })()));
    const elapsedSeconds=(performance.now()-started)/1000;
    const after=await Promise.all(bases.map((_,i)=>checked(i,'/v2/admin/status')));
    const success=samples.filter(s=>s.valid===true),statuses={},errorCodes={};
    for(const sample of samples) {statuses[sample.status]=(statuses[sample.status]??0)+1;if(!sample.valid){const code=sample.errorCode??sample.error??'UNKNOWN';errorCodes[code]=(errorCodes[code]??0)+1;}}
    const delta=key=>after.reduce((sum,item,i)=>sum+item.cache[key]-before[i].cache[key],0);
    const result={round:round+1,mode,elapsedSeconds,hostLoadBefore,hostLoadAfter:os.loadavg(),clientCpuMicros:process.cpuUsage(clientCpu),requests:samples.length,statuses,errorCodes,errorRate:1-success.length/samples.length,successfulQps:success.length/elapsedSeconds,
      successP95Ms:percentile(success.map(s=>s.latencyMs),.95),successP99Ms:percentile(success.map(s=>s.latencyMs),.99),
      meanSuccessLatencyMs:success.reduce((n,s)=>n+s.latencyMs,0)/success.length,
      invalidBodies:samples.filter(s=>s.status===200 && !s.valid).length,
      databaseReads:delta('databaseReads'),localHits:delta('localHits'),redisHits:delta(mode==='ttl'?'ttlHits':'hits')};
    results.push(result);save(path.join(dir,`${round+1}-${mode}-raw.json`),samples);save(path.join(dir,'results.json'),results);
    console.log(JSON.stringify(result));
  }
  const median=values=>{const sorted=[...values].sort((a,b)=>a-b),mid=Math.floor(sorted.length/2);return sorted.length%2?sorted[mid]:(sorted[mid-1]+sorted[mid])/2;};
  const aggregate=Object.fromEntries(modes.map(mode=>{
    const rows=results.filter(x=>x.mode===mode);
    return [mode,{medianQps:median(rows.map(x=>x.successfulQps)),medianP95Ms:median(rows.map(x=>x.successP95Ms)),
      medianP99Ms:median(rows.map(x=>x.successP99Ms)),totalDatabaseReads:rows.reduce((n,x)=>n+x.databaseReads,0),
      totalRequests:rows.reduce((n,x)=>n+x.requests,0),errorRate:rows.reduce((n,x)=>n+x.requests*x.errorRate,0)/rows.reduce((n,x)=>n+x.requests,0)}];
  }));
  const ratio=(value,base)=>base===0?null:value/base;
  const summary={rounds,seconds,concurrency,aggregate,comparisons:{
    ...(aggregate.direct?{optimizedVsDirect:{qpsRatio:ratio(aggregate.optimized.medianQps,aggregate.direct.medianQps),p95Reduction:1-ratio(aggregate.optimized.medianP95Ms,aggregate.direct.medianP95Ms)}}:{}),
    optimizedVsRedisTtl:{qpsRatio:ratio(aggregate.optimized.medianQps,aggregate.ttl.medianQps),p95Reduction:1-ratio(aggregate.optimized.medianP95Ms,aggregate.ttl.medianP95Ms)}
  }};
  save(path.join(dir,'summary.json'),summary);console.log(JSON.stringify(summary));
  console.log(`Evidence: ${dir}`);
} catch(error) {save(path.join(dir,'failure.json'),{message:error.message,completed:results});throw error;}
finally {for(const child of children.reverse()) await stop(child);}
