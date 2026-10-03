import {readFileSync,writeFileSync} from 'node:fs';
import path from 'node:path';
const median=a=>{const s=[...a].sort((x,y)=>x-y),m=Math.floor(s.length/2);return s.length%2?s[m]:(s[m-1]+s[m])/2;};
for(const folder of process.argv.slice(2)) {
 const rows=JSON.parse(readFileSync(path.join(folder,'results.json'),'utf8'));
 const result={source:path.basename(folder),runs:rows.length,planned:rows.reduce((n,r)=>n+r.planned,0),sent:rows.reduce((n,r)=>n+r.requests,0),successful:rows.reduce((n,r)=>n+r.successfulOrders,0),generatorSkipped:rows.reduce((n,r)=>n+r.skipped,0),groups:[]};
 for(const compact of [...new Set(rows.map(r=>r.compact))]) {
  const selected=rows.filter(r=>r.compact===compact),summary={compact,rounds:selected.length};
  for(const key of ['completionProxyTps','settledTps','httpP95Ms','endToEndP95Ms','scheduleP95Ms'])summary[key]={median:median(selected.map(r=>r[key])),min:Math.min(...selected.map(r=>r[key])),max:Math.max(...selected.map(r=>r[key]))};
  summary.stages={};for(const stage of ['ACCEPT_LOCK_QUERY','ACCEPT_LOCK_HELD','ORDER_LOCK_QUERY','ORDER_LOCK_HELD','ACCEPT_TX','ORDER_TX','SQL_REQUEST_READ','SQL_POST_COMMIT_READ','RECONCILE_CYCLE','RECONCILE_ACTIVITY']) {
   summary.stages[stage]={meanMsMedian:median(selected.map(r=>r.stages[stage].meanMs??0)),counts:selected.map(r=>r.stages[stage].count),p95UpperMs:selected.map(r=>r.stages[stage].p95UpperMs)};
  }
  result.groups.push(summary);
 }
 result.historyStableAcrossRuns=rows.every(r=>JSON.stringify(r.historyBefore)===JSON.stringify(rows[0].historyBefore)&&JSON.stringify(r.historyAfterCleanup)===JSON.stringify(r.historyBefore));
 result.longRuns=[];
 for(const row of rows.filter(r=>r.durationSeconds>0)) {
  const raw=JSON.parse(readFileSync(path.join(folder,`${row.name}-raw.json`),'utf8')),trace=raw.trace;
  const windows=row.windows;
  result.longRuns.push({name:row.name,minuteWindows:windows,
   firstFiveMinutesP95Median:median(windows.slice(0,5).map(w=>w.endToEndP95Ms)),lastFiveMinutesP95Median:median(windows.slice(-5).map(w=>w.endToEndP95Ms)),
   maxHeapUsedBytes:Math.max(...trace.map(t=>t.runtime.heapUsedBytes)),minHeapUsedBytes:Math.min(...trace.map(t=>t.runtime.heapUsedBytes)),
   firstMinuteMinHeapBytes:Math.min(...trace.slice(0,12).map(t=>t.runtime.heapUsedBytes)),lastMinuteMinHeapBytes:Math.min(...trace.slice(-12).map(t=>t.runtime.heapUsedBytes)),
   gcCollectionsObserved:trace.at(-1).runtime.gcCount-trace[0].runtime.gcCount,
   queuePeaks:Object.fromEntries(['accepted','outbox','reservationActions'].map(k=>[k,{size:Math.max(...trace.map(t=>Number(t.queues[k].size))),oldestMs:Math.max(...trace.map(t=>Number(t.queues[k].oldestMs)))}])),
   scannedActivities:raw.after.repair.scannedActivities-raw.before.repair.scannedActivities,completedSweeps:raw.after.repair.completedSweeps-raw.before.repair.completedSweeps,
   sampledLockWaitFraction:row.locks.nonzero/row.locks.samples,peakWaitingTransactions:row.locks.peakTransactions});
 }
 writeFileSync(path.join(folder,'summary.json'),JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify({folder,runs:result.runs,planned:result.planned,sent:result.sent,successful:result.successful,generatorSkipped:result.generatorSkipped}));
}
