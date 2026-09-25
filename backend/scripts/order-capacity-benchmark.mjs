import {mkdirSync} from 'node:fs';
import os from 'node:os';
import {performance} from 'node:perf_hooks';
import {root,start,stop,request,fixture,invariant,sql,rootSql,sleep,save,kafkaDrained} from './harness.mjs';

const smoke=process.argv.includes('--smoke');
const rounds=smoke?1:Number(process.env.ORDER_CAPACITY_ROUNDS??3);
const requests=smoke?200:Number(process.env.ORDER_CAPACITY_REQUESTS??1000);
const concurrencies=smoke?[8]:(process.env.ORDER_CAPACITY_CONCURRENCIES??'4,8,12,16')
  .split(',').map(Number).filter(Number.isFinite);
if(!smoke && (rounds<3 || requests<1000 || concurrencies.length<3))
  throw new Error('Formal runs require at least 3 rounds, 1000 requests and 3 concurrency levels');
if(concurrencies.some(value=>value<1 || value>128)) throw new Error('Concurrency must be between 1 and 128');

const dir=`${root}/evidence/order-capacity-${Date.now()}`;
mkdirSync(dir,{recursive:true});
const quantile=(values,p)=>{
  const sorted=[...values].sort((a,b)=>a-b);
  return sorted[Math.min(sorted.length-1,Math.ceil(sorted.length*p)-1)]??null;
};
const median=values=>quantile(values,.5);
const results=[];
let child;
let sequence=Date.now();

async function load(activity,concurrency) {
  const samples=new Array(requests);
  let cursor=0;
  const started=performance.now();
  async function worker() {
    while(true) {
      const i=cursor++;
      if(i>=requests) return;
      const sentEpoch=Date.now();
      const sent=performance.now();
      try {
        const response=await request('/v2/requests',{
          user:3_000_000+(activity%1_000_000)*requests+i,
          method:'POST',
          body:{requestId:`capacity_${activity}_${i}`,activityId:activity}
        });
        samples[i]={i,sentEpoch,status:response.status,latencyMs:performance.now()-sent,
          requestId:response.data.id??null,state:response.data.state??null,error:response.data.error??null};
      } catch(error) {
        samples[i]={i,sentEpoch,status:0,latencyMs:performance.now()-sent,error:error.message};
      }
    }
  }
  await Promise.all(Array.from({length:concurrency},worker));
  return {samples,httpElapsedSeconds:(performance.now()-started)/1000};
}

async function waitForCompletion(activity,timeout=180000) {
  const started=Date.now();
  while(Date.now()-started<timeout) {
    const [accepted,outbox,actions,orders]=sql(`SELECT
      (SELECT COUNT(*) FROM ux_request WHERE activity_id=${activity} AND state='ACCEPTED'),
      (SELECT COUNT(*) FROM ux_outbox WHERE activity_id=${activity} AND sent_at IS NULL),
      (SELECT COUNT(*) FROM ux_reservation_action a JOIN ux_request r ON r.id=a.request_id
        WHERE r.activity_id=${activity} AND a.applied_at IS NULL),
      (SELECT COUNT(*) FROM ux_order WHERE activity_id=${activity});`).split('\t').map(Number);
    if(accepted===0 && outbox===0 && actions===0 && orders===requests) return Date.now()-started;
    await sleep(100);
  }
  throw new Error(`Successful order path did not drain for activity ${activity}`);
}

function statements() {
  const raw=rootSql(`SELECT COUNT_STAR,SUM_ROWS_EXAMINED,SUM_ROWS_AFFECTED
    FROM performance_schema.events_statements_summary_by_digest
    WHERE SCHEMA_NAME='upgrade' AND (
      DIGEST_TEXT LIKE '%ux_activity%' OR DIGEST_TEXT LIKE '%ux_request%' OR DIGEST_TEXT LIKE '%ux_order%' OR
      DIGEST_TEXT LIKE '%ux_outbox%' OR DIGEST_TEXT LIKE '%ux_reservation_action%');`);
  const rows=raw.split('\n').filter(Boolean).map(line=>line.split('\t').map(Number));
  return {statementCount:rows.reduce((n,row)=>n+row[0],0),rowsExamined:rows.reduce((n,row)=>n+row[1],0),
    rowsAffected:rows.reduce((n,row)=>n+row[2],0)};
}

try {
  child=await start(`${dir}/server.log`,[
    '--upgrade.rate-user=100','--upgrade.rate-activity=1000000','--upgrade.backlog-limit=1000000'
  ]);
  save(`${dir}/environment.json`,{
    started:new Date().toISOString(),platform:os.platform(),arch:os.arch(),cpus:os.cpus().length,
    model:os.cpus()[0]?.model,totalMemory:os.totalmem(),heap:'384m',pool:12,httpThreads:48,
    rounds,requestsPerStage:requests,concurrencies,
    scope:'Local single-node all-success path: HTTP -> Redis Lua -> MySQL request/outbox -> Kafka -> MySQL order -> Redis confirmation.',
    interpretation:'Stock equals request count and every request uses a unique user. QPS therefore measures successful full-path orders, not sold-out fast failures.'
  });

  for(const concurrency of concurrencies) {
    for(let round=1;round<=rounds;round++) {
      const activity=++sequence;
      await fixture(activity,requests);
      await sleep(300);
      rootSql('TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;');
      const loaded=await load(activity,concurrency);
      const acceptedCount=loaded.samples.filter(sample=>sample.status===202).length;
      if(acceptedCount!==requests) {
        const statuses={};
        for(const sample of loaded.samples) statuses[sample.status]=(statuses[sample.status]??0)+1;
        throw new Error(`Load exceeds zero-error admission range: concurrency=${concurrency}, statuses=${JSON.stringify(statuses)}`);
      }
      const drainMs=await waitForCompletion(activity);
      await kafkaDrained();

      const rows=sql(`SELECT id,state,ROUND(UNIX_TIMESTAMP(created_at)*1000),ROUND(UNIX_TIMESTAMP(completed_at)*1000)
        FROM ux_request WHERE activity_id=${activity}`)
        .split('\n').filter(Boolean).map(line=>{
          const [id,state,created,completed]=line.split('\t');
          return {id,state,created:Number(created),completed:Number(completed)};
        });
      const byId=new Map(rows.map(row=>[row.id,row]));
      for(const sample of loaded.samples) {
        const row=byId.get(sample.requestId);
        if(row) { sample.finalState=row.state;sample.endToEndMs=row.completed-sample.sentEpoch; }
      }
      const latencies=loaded.samples.map(sample=>sample.latencyMs);
      const endToEnd=loaded.samples.map(sample=>sample.endToEndMs).filter(Number.isFinite);
      const statuses={};
      for(const sample of loaded.samples) statuses[sample.status]=(statuses[sample.status]??0)+1;
      const firstSent=Math.min(...loaded.samples.map(sample=>sample.sentEpoch));
      const lastCompleted=Math.max(...rows.map(row=>row.completed));
      const fullElapsedSeconds=(lastCompleted-firstSent)/1000;
      const orders=Number(sql(`SELECT COUNT(*) FROM ux_order WHERE activity_id=${activity}`));
      const activityUnappliedActions=Number(sql(`SELECT COUNT(*) FROM ux_reservation_action a
        JOIN ux_request r ON r.id=a.request_id WHERE r.activity_id=${activity} AND a.applied_at IS NULL`));
      const check=await invariant();
      const reservationResponse=await request(`/v2/admin/fixtures/${activity}/reservation`,{admin:true});
      if(reservationResponse.status!==200) throw new Error(`Reservation status unavailable: ${JSON.stringify(reservationResponse)}`);
      const activityReservation=reservationResponse.data;
      const result={round,activity,concurrency,requests,stock:requests,statuses,
        accepted:acceptedCount,
        successfulOrders:orders,errorRate:loaded.samples.filter(sample=>sample.status!==202).length/requests,
        httpAcceptanceQps:requests/loaded.httpElapsedSeconds,httpP50:quantile(latencies,.5),httpP95:quantile(latencies,.95),httpP99:quantile(latencies,.99),
        successfulOrderQps:orders/fullElapsedSeconds,orderP50:quantile(endToEnd,.5),orderP95:quantile(endToEnd,.95),orderP99:quantile(endToEnd,.99),
        httpElapsedSeconds:loaded.httpElapsedSeconds,fullElapsedSeconds,drainMs,databaseRequests:rows.length,activityUnappliedActions,
        sql:statements(),invariants:check.invariants,reservations:activityReservation,globalReservations:check.reservations};
      if(result.accepted!==requests || orders!==requests || rows.some(row=>row.state!=='SUCCEEDED') ||
        result.invariants.stockViolations!==0 || result.invariants.stateViolations!==0 ||
        activityReservation.pending!==0 || activityReservation.remaining!==0 || activityUnappliedActions!==0)
        throw new Error(`Capacity correctness failed: ${JSON.stringify(result)}`);
      save(`${dir}/c${concurrency}-r${round}-raw.json`,loaded.samples);
      results.push(result);
      save(`${dir}/results.json`,results);
      console.log(`DONE concurrency=${concurrency} round=${round} acceptedQps=${result.httpAcceptanceQps.toFixed(1)} orderQps=${result.successfulOrderQps.toFixed(1)} orderP95=${result.orderP95.toFixed(1)}ms errors=${result.errorRate}`);
    }
  }

  const levels=concurrencies.map(concurrency=>{
    const rows=results.filter(result=>result.concurrency===concurrency);
    return {concurrency,
      medianAcceptanceQps:median(rows.map(row=>row.httpAcceptanceQps)),
      medianSuccessfulOrderQps:median(rows.map(row=>row.successfulOrderQps)),
      worstOrderP95:Math.max(...rows.map(row=>row.orderP95)),
      worstOrderP99:Math.max(...rows.map(row=>row.orderP99)),
      totalRequests:rows.reduce((sum,row)=>sum+row.requests,0),
      totalSuccessfulOrders:rows.reduce((sum,row)=>sum+row.successfulOrders,0),
      errorRate:rows.reduce((sum,row)=>sum+row.requests*row.errorRate,0)/rows.reduce((sum,row)=>sum+row.requests,0)};
  });
  const best=[...levels].sort((a,b)=>b.medianSuccessfulOrderQps-a.medianSuccessfulOrderQps)[0];
  const summary={levels,bestTestedLevel:best,totalRequests:results.reduce((sum,row)=>sum+row.requests,0),
    totalSuccessfulOrders:results.reduce((sum,row)=>sum+row.successfulOrders,0),
    stockViolations:results.reduce((sum,row)=>sum+row.invariants.stockViolations,0),
    stateViolations:results.reduce((sum,row)=>sum+row.invariants.stateViolations,0)};
  save(`${dir}/summary.json`,summary);
  console.log(`SUMMARY bestConcurrency=${best.concurrency} medianOrderQps=${best.medianSuccessfulOrderQps.toFixed(1)} worstP95=${best.worstOrderP95.toFixed(1)}ms evidence=${dir}`);
} catch(error) {
  save(`${dir}/failure.json`,{message:error.message,stack:error.stack,completed:results});
  throw error;
} finally {
  await stop(child);
}
