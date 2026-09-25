import {mkdirSync} from 'node:fs';
import os from 'node:os';
import {performance} from 'node:perf_hooks';
import {root,start,stop,request,fixture,invariant,sql,rootSql,sleep,save,kafkaDrained} from './harness.mjs';

const smoke=process.argv.includes('--smoke');
const rounds=smoke?1:Number(process.env.FULL_CHAIN_ROUNDS??3);
const requests=smoke?200:Number(process.env.FULL_CHAIN_REQUESTS??1000);
const stock=smoke?20:Number(process.env.FULL_CHAIN_STOCK??100);
const concurrency=Number(process.env.FULL_CHAIN_CONCURRENCY??16);
if(!smoke && (rounds<3 || requests<1000)) throw new Error('Formal runs require at least 3 rounds and 1000 requests');
const dir=`${root}/evidence/full-chain-${Date.now()}`;mkdirSync(dir,{recursive:true});
const quantile=(values,p)=>{const sorted=[...values].sort((a,b)=>a-b);return sorted[Math.min(sorted.length-1,Math.ceil(sorted.length*p)-1)]??null;};
const results=[];let child;let sequence=Date.now();

async function load(activity) {
  const samples=new Array(requests);let cursor=0;const started=performance.now();
  async function worker() {
    while(true) {
      const i=cursor++;if(i>=requests)return;
      const epoch=Date.now(),sent=performance.now();
      try {
        const response=await request('/v2/requests',{user:2_000_000+i,method:'POST',body:{requestId:`full_${activity}_${i}`,activityId:activity}});
        samples[i]={i,epoch,status:response.status,latencyMs:performance.now()-sent,requestId:response.data.id??null,
          state:response.data.state??null,error:response.data.error??null};
      } catch(error) { samples[i]={i,epoch,status:0,latencyMs:performance.now()-sent,error:error.message}; }
    }
  }
  await Promise.all(Array.from({length:concurrency},worker));
  return {samples,elapsedSeconds:(performance.now()-started)/1000};
}

function statements() {
  const raw=rootSql(`SELECT COUNT_STAR,SUM_ROWS_EXAMINED,SUM_ROWS_AFFECTED
    FROM performance_schema.events_statements_summary_by_digest
    WHERE SCHEMA_NAME='upgrade' AND (
      DIGEST_TEXT LIKE '%ux_activity%' OR DIGEST_TEXT LIKE '%ux_request%' OR DIGEST_TEXT LIKE '%ux_order%' OR
      DIGEST_TEXT LIKE '%ux_outbox%' OR DIGEST_TEXT LIKE '%ux_reservation_action%');`);
  const rows=raw.split('\n').filter(Boolean).map(line=>line.split('\t').map(Number));
  return {statementCount:rows.reduce((n,row)=>n+row[0],0),rowsExamined:rows.reduce((n,row)=>n+row[1],0),rowsAffected:rows.reduce((n,row)=>n+row[2],0)};
}

try {
  child=await start(`${dir}/server.log`,['--upgrade.rate-user=100','--upgrade.rate-activity=100000','--upgrade.backlog-limit=100000']);
  save(`${dir}/environment.json`,{started:new Date().toISOString(),platform:os.platform(),arch:os.arch(),cpus:os.cpus().length,
    model:os.cpus()[0]?.model,totalMemory:os.totalmem(),heap:'384m',pool:12,httpThreads:48,rounds,requests,stock,concurrency,
    scope:'Local single-node full path: HTTP -> Redis Lua -> MySQL request/outbox -> Kafka -> MySQL order -> reservation confirmation.'});
  for(let round=1;round<=rounds;round++) {
    const activity=++sequence;await fixture(activity,stock);await sleep(300);
    rootSql('TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;');
    const loaded=await load(activity),accepted=loaded.samples.filter(x=>x.status===202&&x.requestId);
    const drainStarted=Date.now();
    while(true) {
      const [pending,outbox,actions]=sql(`SELECT
        (SELECT COUNT(*) FROM ux_request WHERE activity_id=${activity} AND state='ACCEPTED'),
        (SELECT COUNT(*) FROM ux_outbox WHERE activity_id=${activity} AND sent_at IS NULL),
        (SELECT COUNT(*) FROM ux_reservation_action a JOIN ux_request r ON r.id=a.request_id WHERE r.activity_id=${activity} AND a.applied_at IS NULL);`).split('\t').map(Number);
      if(pending===0&&outbox===0&&actions===0) break;
      if(Date.now()-drainStarted>120000) throw new Error(`Full path did not drain: ${pending}/${outbox}/${actions}`);
      await sleep(100);
    }
    await kafkaDrained();
    const dbRows=sql(`SELECT id,ROUND(UNIX_TIMESTAMP(created_at)*1000),ROUND(UNIX_TIMESTAMP(completed_at)*1000),state FROM ux_request WHERE activity_id=${activity}`)
      .split('\n').filter(Boolean).map(line=>{const [id,created,completed,state]=line.split('\t');return {id,created:Number(created),completed:Number(completed),state};});
    const byId=new Map(dbRows.map(row=>[row.id,row]));
    for(const sample of accepted) { const row=byId.get(sample.requestId);if(row){sample.finalState=row.state;sample.endToEndMs=row.completed-sample.epoch;} }
    const statuses={};for(const sample of loaded.samples)statuses[sample.status]=(statuses[sample.status]??0)+1;
    const check=await invariant(),orders=Number(sql(`SELECT COUNT(*) FROM ux_order WHERE activity_id=${activity}`));
    const result={round,activity,requests,stock,concurrency,statuses,httpQps:requests/loaded.elapsedSeconds,
      httpP95:quantile(loaded.samples.map(x=>x.latencyMs),.95),accepted:accepted.length,orders,
      orderEndToEndP95:quantile(accepted.map(x=>x.endToEndMs).filter(Number.isFinite),.95),drainMs:Date.now()-drainStarted,
      databaseRequests:dbRows.length,sql:statements(),invariants:check.invariants,reservations:check.reservations};
    if(orders!==stock || result.databaseRequests!==stock || result.invariants.stockViolations!==0 || result.invariants.stateViolations!==0 || result.reservations.unappliedActions!==0)
      throw new Error(`Full path correctness failed: ${JSON.stringify(result)}`);
    save(`${dir}/${round}-raw.json`,loaded.samples);results.push(result);save(`${dir}/results.json`,results);
    console.log(`DONE round=${round} httpQps=${result.httpQps.toFixed(1)} p95=${result.httpP95.toFixed(2)}ms accepted=${accepted.length} orders=${orders} e2eP95=${result.orderEndToEndP95?.toFixed(2)}ms sql=${result.sql.statementCount}`);
  }
  const summary={medianHttpQps:quantile(results.map(x=>x.httpQps),.5),worstHttpP95:Math.max(...results.map(x=>x.httpP95)),
    worstOrderEndToEndP95:Math.max(...results.map(x=>x.orderEndToEndP95)),databaseRequests:results.map(x=>x.databaseRequests),
    orders:results.map(x=>x.orders),sqlStatements:results.map(x=>x.sql.statementCount)};
  save(`${dir}/summary.json`,summary);console.log(`SUMMARY evidence=${dir}`);
} catch(error) { save(`${dir}/failure.json`,{message:error.message,stack:error.stack,completed:results});throw error; }
finally { await stop(child); }
