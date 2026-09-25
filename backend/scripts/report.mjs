import {readFileSync,readdirSync,writeFileSync} from 'node:fs';
import path from 'node:path';
import {root} from './harness.mjs';
const folder=process.argv[2]??readdirSync(`${root}/evidence`).filter(x=>x.startsWith('benchmark-')).sort().at(-1);
const directory=path.isAbsolute(folder)?folder:`${root}/evidence/${folder}`;
const results=JSON.parse(readFileSync(`${directory}/results.json`));
const environment=JSON.parse(readFileSync(`${directory}/environment.json`));
if(results.length!==15)throw new Error('Expected five modes and three complete rounds');
const fmt=x=>Number(x).toFixed(2);
const rows=results.map(r=>`| ${r.round} | ${r.mode} | ${fmt(r.actualSendQps)} | ${Object.entries(r.statuses).map(([k,v])=>`${k}:${v}`).join(', ')} | ${fmt(r.latencyP95)} | ${r.endToEndP95===null?'-':fmt(r.endToEndP95)} | ${r.succeeded} | ${r.databaseReads} | ${r.backlogAtEnd} |`).join('\n');
const totals=Object.fromEntries(['sync','async','direct','ttl','optimized'].map(mode=>{
  const r=results.filter(r=>r.mode===mode);
  return [mode,{sent:r.reduce((n,x)=>n+x.sent,0),orders:r.reduce((n,x)=>n+x.succeeded,0),
    failures:r.reduce((n,x)=>n+Object.entries(x.statuses).filter(([s])=>!['200','202'].includes(s)).reduce((n,[s,c])=>n+c,0),0),
    worstP95:Math.max(...r.map(x=>x.latencyP95)),sql:r.reduce((n,x)=>n+x.databaseReads,0)}];
}));
const text=`# B5 正式性能对照报告\n\n生成依据：\`${path.basename(directory)}\`，${environment.started}。\n\n## 环境与方法\n\n- ${environment.model}，${environment.cpus} 逻辑 CPU，内存 ${(environment.totalMemory/1024**3).toFixed(0)} GiB；应用与负载器、中间件同机。\n- JVM 堆 384 MiB，MySQL 连接池 12，HTTP 工作线程 48；独立 MySQL 8.4 / Redis 7.4 / Kafka 3.9.1 单节点。\n- 同步/异步各 50 个计划请求/秒；三种读取各 100 个计划请求/秒；每轮正式窗口 60 秒，各 3 轮，轮换执行顺序。\n- 每个模式用新活动/商家，正式测量前 20 次商家读取预热 DB/JVM。普通 TTL 键在正式窗口首次填充，优化缓存经过预热；这不是完全相同初始缓存状态的极限吞吐竞赛。普通 TTL 的首次冷读计入统计。\n- 普通 TTL 15 秒，优化软 TTL 5 秒、硬 TTL 15~16.5 秒；一个正式窗口覆盖多轮到期。\n- 全部 HTTP 请求带签名身份；同步与异步经过同一准入及数据库约束。无模型 API 调用。\n\n## 全部轮次\n\n| 轮次 | 模式 | 实际发送/s | HTTP 状态计数 | HTTP P95 ms | 建单端到端 P95 ms | 最终成功订单 | 商家 SQL 读取尝试 | 窗口末积压 |\n|---|---|---:|---|---:|---:|---:|---:|---:|\n${rows}\n\n建单端到端时间按客户端发出时刻到数据库完成时刻计算，依赖本机与容器时钟一致；不包含客户端下一次轮询的等待。HTTP 延迟包含拒绝响应，不能代替成功订单端到端延迟。原始文件保存每个请求的状态、原因和时延。\n\n## 可以支持的结论\n\n- 同步 ${totals.sync.sent} 次请求，最终 ${totals.sync.orders} 单；异步 ${totals.async.sent} 次请求，最终 ${totals.async.orders} 单。完整结果和失败计数见上表。\n- 异步方案将受理与建单分离，但增加排队和 Kafka 转发延迟；不能据此声称提升订单完成速度或最大吞吐。\n- 直读共 ${totals.direct.sql} 次 SQL 尝试、${totals.direct.failures} 个非 200/202 响应；普通 TTL ${totals.ttl.sql} 次；优化缓存 ${totals.optimized.sql} 次。优化缓存主动按软 TTL 刷新，回源次数不一定少于更长 TTL 的普通缓存，其目的包括控制陈旧时间和重建竞争。\n- 优化缓存三轮中最差 HTTP P95 为 ${fmt(totals.optimized.worstP95)} ms，仅适用于本次 100 RPS 档位，不是系统最大 QPS。\n- 每轮库存/订单一致性结果都在汇总中保留；${results.every(r=>r.invariants.stockViolations===0&&r.invariants.stateViolations===0)?'本次全部为零违规。':'本次存在违规，不能通过验收。'}\n\n## 不支持的结论\n\n- 不能把目标 RPS 写成最大 QPS，不能把 202 算成订单成功，不能复用 EventFlow 旧性能数据。\n- 这是本地回归负载，没有测到同步和异步的饱和拐点；没有证明线上规模、跨机器扩展、跨可用区恢复能力。\n- Docker 资源是轮次前后采样而非全窗口连续峰值；负载器 CPU、调度偏差和主机负载在 JSON 中记录。\n- 库存充足的稳态性能与 1000 用户抢 100 份的正确性测试是两项不同实验，不能合并成一个性能结论。\n\n## 原始证据\n\n- \`environment.json\`：环境、资源配置及源码/脚本 SHA256。\n- \`results.json\`：全部 15 轮、失败、积压、实际速率及不变量。\n- \`*-raw.json\`：每次请求与调度延迟。\n- \`*-db.json\`：请求在数据库中的处理时间与终态。\n- \`server.log\`：应用运行日志。\n\n后续新增测试文件或不影响性能路径的实验辅助函数会改变源码清单；以这份运行目录的哈希说明本轮版本，不将后续未测实现归入本轮性能结果。\n`;
writeFileSync(`${directory}/报告.md`,text);console.log(`${directory}/报告.md`);
