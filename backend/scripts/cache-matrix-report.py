"""Recalculate every raw run and write a compact, reproducible matrix report."""
import collections
import hashlib
import json
import math
from pathlib import Path
import shutil
import statistics
import sys

matrix = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
output.mkdir(parents=True, exist_ok=True)
all_results = []
checks = []
for logfile in sorted(matrix.glob('*.log')):
    evidence_lines = [line[10:] for line in logfile.read_text().splitlines() if line.startswith('Evidence: ')]
    if not evidence_lines:
        raise RuntimeError(f'Incomplete case: {logfile.name}')
    evidence = Path(evidence_lines[-1])
    env = json.loads((evidence/'environment.json').read_text())
    results = json.loads((evidence/'results.json').read_text())
    summary = json.loads((evidence/'summary.json').read_text())
    for result in results:
        raw_path = evidence/f"{result['round']}-{result['mode']}-raw.json"
        raw = json.loads(raw_path.read_text())
        latencies = sorted(x['latencyMs'] for x in raw if x.get('valid') is True)
        p95 = latencies[math.ceil(len(latencies)*.95)-1]
        qps = len(latencies)/result['elapsedSeconds']
        assert len(raw) == result['requests']
        assert abs(p95-result['successP95Ms']) < 1e-8
        assert abs(qps-result['successfulQps']) < 1e-8
        assert abs(1-len(latencies)/len(raw)-result['errorRate']) < 1e-8
        codes = collections.Counter(x.get('errorCode',x.get('error','UNKNOWN')) for x in raw if not x.get('valid'))
        assert dict(codes) == result['errorCodes']
        checks.append({'case':logfile.stem,'round':result['round'],'mode':result['mode'],'passed':True,
                       'rawSha256':hashlib.sha256(raw_path.read_bytes()).hexdigest(),'samples':len(raw)})
    modes = {}
    for mode in ['ttl','optimized']:
        rows = [x for x in results if x['mode']==mode]
        errors = collections.Counter()
        for r in rows: errors.update(r['errorCodes'])
        modes[mode] = {'p95':statistics.median(r['successP95Ms'] for r in rows),
                       'qps':statistics.median(r['successfulQps'] for r in rows),
                       'p95Range':[min(r['successP95Ms'] for r in rows),max(r['successP95Ms'] for r in rows)],
                       'qpsRange':[min(r['successfulQps'] for r in rows),max(r['successfulQps'] for r in rows)],
                       'requests':sum(r['requests'] for r in rows),'errors':dict(errors),
                       'databaseReads':sum(r['databaseReads'] for r in rows),
                       'localHits':sum(r['localHits'] for r in rows)}
        assert abs(modes[mode]['p95']-summary['aggregate'][mode]['medianP95Ms'])<1e-8
    pairs=[]
    for n in range(1,env['rounds']+1):
        a=next(r for r in results if r['round']==n and r['mode']=='ttl')
        b=next(r for r in results if r['round']==n and r['mode']=='optimized')
        pairs.append({'round':n,'p95Reduction':1-b['successP95Ms']/a['successP95Ms'],'qpsRatio':b['successfulQps']/a['successfulQps']})
    all_results.append({'case':logfile.stem,'evidence':str(evidence),'environment':{k:env[k] for k in ['authMode','concurrency','shopCount','distribution','rounds','seconds','warmupSeconds','jarSha256','cpu','cpuCount']},
                        'modes':modes,'pairedRounds':pairs,'p95Reduction':1-modes['optimized']['p95']/modes['ttl']['p95'],
                        'qpsRatio':modes['optimized']['qps']/modes['ttl']['qps']})
    dest=output/logfile.stem
    dest.mkdir(exist_ok=True)
    for file in ['environment.json','results.json','summary.json']: shutil.copy2(evidence/file,dest/file)
(output/'analysis.json').write_text(json.dumps(all_results,ensure_ascii=False,indent=2)+'\n')
(output/'raw-checks.json').write_text(json.dumps(checks,indent=2)+'\n')
lines=['# 缓存补充实验：正常登录、并发与访问分布','',
       '业务代码及 JAR 未修改。本实验比较 Redis TTL 单级缓存与现有多级缓存，不新增缓存优化。', '',
       '## 测试条件','',
       '同一台开发机、两个 384 MiB JVM，MySQL/Redis 为本机容器。闭环 HTTP，128 个真实注册 Cookie 会话；Bearer 组为 1024 个测试身份，仅用于诊断。各场景串行，每组四轮，每模式预热 5 秒、测量 20 秒。A/B 顺序逐轮交换。每个成功响应校验 ID、名称和描述。', '',
       '商家基础信息端点；不是完整详情页、整站容量或订单 TPS。用户注册和初始装载不计入测量。预热缓存，期间仍会自然过期；不包含真实网络或冷启动测试。', '',
       '## 结果','',
       '以下为每轮成功请求 P95/QPS 的中位数（四轮取中间两值平均），不是合并所有请求后的 P95。错误请求另列，不将失败吞吐算成功。','',
       '| 场景 | Redis P95 ms | 多级 P95 ms | P95 降幅 | Redis req/s | 多级 req/s | 吞吐倍数 |',
       '|---|---:|---:|---:|---:|---:|---:|']
for item in all_results:
    a,b=item['modes']['ttl'],item['modes']['optimized']
    lines.append(f"| {item['case']} | {a['p95']:.3f} | {b['p95']:.3f} | {item['p95Reduction']:.1%} | {a['qps']:.0f} | {b['qps']:.0f} | {item['qpsRatio']:.2f} |")
lines+=['','uniform20：20 个商家均匀轮询。hot100：100 个商家，80% 请求访问最热的 10 个，其余 20% 访问另外 90 个；为确定性合成分布。','', '## 波动与错误','']
for item in all_results:
    lines.append(f"### {item['case']}\n")
    for mode,m in item['modes'].items():
        lines.append(f"- {mode}：P95 范围 {m['p95Range'][0]:.3f}–{m['p95Range'][1]:.3f} ms；QPS 范围 {m['qpsRange'][0]:.0f}–{m['qpsRange'][1]:.0f}；请求 {m['requests']}；失败 {sum(m['errors'].values())}，错误码 {m['errors']}；商家数据库读取 {m['databaseReads']}。")
    lines.append('- 配对轮次 P95 降幅：'+', '.join(f"{r['p95Reduction']:.1%}" for r in item['pairedRounds'])+'。\n')
lines+=['## 本次结果怎么使用','',
        '本次预定矩阵中，正常登录、32 并发、20 商家均匀访问组的四轮 P95 均改善（约 12%–20%），中位数从 12.852 ms 降至 10.856 ms，吞吐从约 4605 提升至 5812 req/s。相较只选择最大降幅，这组重复性更好；仍仅代表本机该次实验。',
        '8 并发组汇总 P95 降幅为 23.5%，但一轮退化。热点组汇总降幅为 5.6%，四个配对轮次中却有三个轻微退化，不能称为稳定改善。不同模式中位数之比不是配对改善的中位数，因此必须同时阅读逐轮结果。',
        '测试认证组约 45.1% 的 P95 降幅用于诊断，不替代正常登录结果。旧实验与本次环境负载、预热及测量时长不同；没有改动业务代码，不能说本次把之前的 9.3% 进一步优化到了新数字。',
        '若用于简历，可限定表述为：在同机 32 并发、20 商家均匀查询、正常登录的四轮 A/B 测试中，商家基础信息接口 P95 中位数从 12.852 ms 降至 10.856 ms，成功吞吐从 4605 提升至 5812 req/s。建议先在独立压测机及稳定负载下复测，再定稿性能数字。', '',
        '## 解读边界','',
        '- 所有预定场景均展示，未按结果选择最好的一轮；场景间负载不同，不能把不同组基线和优化结果拼接。',
        '- Cookie 请求每次仍会认证数据库会话。Bearer 诊断排除了该查询，但身份数量、运行时段也不同；不能相减 P95 来精确估计认证耗时。',
        '- 同机压测端、服务端及中间件共享 CPU；clientCpuMicros 与系统负载记录可用于识别干扰，但没有完成服务端各阶段耗时归因。闭环吞吐也不等于最大可承载吞吐。',
        '- 商家 databaseReads 不含会话 SQL；商家少量回源不代表数据库总访问量很低。',
        '- 现有多级缓存软过期约 5 秒、硬过期约 15 秒，本地有效期最多约 1 秒；TTL 基线约 15 秒过期。两边刷新策略不同，本次不修改这些策略。多级缓存可能刷新更频繁，不能把收益解读为商家数据库读取次数必然下降。',
        '- 每轮 20 秒仅覆盖有限过期周期；开发机后台负载、JIT、GC 和顺序残余影响仍可能造成波动。提升更大也不等于可保证生产性能。','',
        '## 复现','',
        '从项目根目录运行 `bash backend/scripts/cache-matrix.sh`。需要本机验证用 MySQL、Redis 等依赖和现有构建 JAR；脚本使用隔离验证库及 Redis DB 13，不连接生产库。', '',
        f'已从原始样本独立复算 {len(checks)} 轮的计数、成功 QPS、P95、错误率及错误码；全部通过。原始大文件保存在 backend/evidence，本文同目录保存紧凑结果、环境、源码哈希与原始文件校验哈希。','']
(output/'README.md').write_text('\n'.join(lines))
print(json.dumps([{k:v for k,v in x.items() if k not in ['environment','pairedRounds','evidence']} for x in all_results],ensure_ascii=False,indent=2))
