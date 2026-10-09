package com.hmdp.upgrade;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
@ConditionalOnProperty(name="upgrade.demo-data",havingValue="true")
public class DemoStorefront implements ApplicationRunner {
    @org.springframework.beans.factory.annotation.Autowired
    private ShopBloom bloom;
    private final JdbcTemplate db;
    private final Transactions tx;
    private final Reservations reservations;
    public DemoStorefront(JdbcTemplate db,Transactions tx,Reservations reservations) { this.db=db;this.tx=tx;this.reservations=reservations; }
    @Override public void run(ApplicationArguments args) {
        seed();
    }
    @Scheduled(cron="0 0 0 * * *",zone="Asia/Shanghai")
    public void seed() {
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var start=day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        var end=day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        var sessionDay=day.plusDays(1);
        String[][] stores={
            {"星河音乐节","校园乐队、原创歌手与夜间舞台联合演出。","音乐演出","东校区 · 体育场","东校区体育场","/event-assets/stage.svg","音乐,露天,热门"},
            {"新生脱口秀专场","由校内喜剧社带来的原创段子与互动演出。","戏剧舞台","南校区 · 大礼堂","南校区大礼堂","/event-assets/stage.svg","喜剧,互动,室内"},
            {"青春辩论赛决赛","年度校园辩论赛决赛，现场见证冠军诞生。","校园赛事","主校区 · 报告厅","主校区综合报告厅","/event-assets/lecture.svg","辩论,决赛,思辨"},
            {"毕业季草坪音乐会","民谣、流行与合唱节目组成的毕业季特别演出。","音乐演出","主校区 · 中心草坪","主校区中心草坪","/event-assets/stage.svg","毕业季,音乐,草坪"},
            {"校园篮球全明星赛","院系明星球员对抗赛与中场互动活动。","校园赛事","东校区 · 体育馆","东校区体育馆","/event-assets/arena.svg","篮球,竞技,互动"},
            {"天文观测开放夜","使用校内望远镜观测星空，并配有讲解环节。","社团活动","北校区 · 天文台","北校区天文台","/event-assets/space.svg","天文,夜间,科普"},
            {"非遗手作体验课","体验传统技艺，在指导下完成一件手工作品。","创意工坊","西校区 · 创客空间","西校区创客空间 201","/event-assets/craft.svg","非遗,手作,体验"},
            {"校园攀岩挑战日","包含新手体验、技巧指导与限时挑战。","运动体验","东校区 · 攀岩馆","东校区体育中心攀岩馆","/event-assets/arena.svg","攀岩,运动,挑战"},
            {"青年艺术作品展","集中展示绘画、摄影与数字媒体学生作品。","展览市集","主校区 · 美术馆","主校区美术馆一层","/event-assets/gallery.svg","艺术,摄影,展览"},
            {"人工智能前沿讲座","邀请产业研究者分享多模态模型与智能体实践。","讲座论坛","南校区 · 科创中心","南校区科创中心报告厅","/event-assets/lecture.svg","人工智能,讲座,科技"},
            {"校园烘焙工坊","学习基础烘焙流程并完成限定主题甜点。","创意工坊","西校区 · 实训中心","西校区实训中心 305","/event-assets/craft.svg","烘焙,手作,社交"},
            {"正念减压体验课","面向学生的呼吸练习与基础正念体验。","社团活动","北校区 · 学生活动中心","北校区学生活动中心 204","/event-assets/wellbeing.svg","减压,正念,体验"},
            {"桌游社主题局","主持人带领的策略桌游主题场，新手可参加。","社团活动","主校区 · 社团之家","主校区社团之家 3 号室","/event-assets/craft.svg","桌游,社交,新手"},
            {"校园公益市集","学生社团摊位、旧物交换与公益义卖。","展览市集","南校区 · 林荫大道","南校区林荫大道","/event-assets/gallery.svg","市集,公益,社团"},
            {"经典电影露天放映","草坪露天电影与映后交流，提供限定观影名额。","电影放映","东校区 · 湖畔草坪","东校区湖畔草坪","/event-assets/cinema.svg","电影,露天,交流"}
        };
        int[] prices={2990,3900,1590,2590,5990,4990,12900,9900,6900,4900,5900,15900,6900,7900,12900};
        int[] faces={5000,6000,2500,4000,9000,8000,15900,12900,9900,7900,8900,21900,9900,10900,16900};
        double[] ratings={4.8,4.7,4.6,4.9,4.8,4.7,4.9,4.8,4.7,4.8,4.9,4.8,4.7,4.9,4.8};
        int[] reviews={1268,938,516,2180,1736,822,647,1092,431,728,1184,563,386,742,916};
        int[] sales={3280,2416,1859,4600,3928,2260,986,1740,812,1536,2080,691,1260,732,1140};
        int[] distances={1800,900,2400,650,1500,3100,4200,2800,1200,2200,1300,3600,1700,4500,2600};
        tx.run(()->{
            for(int i=0;i<stores.length;i++) {
                long shop=9101+i;var s=stores[i];
                if(db.queryForObject("SELECT COUNT(*) FROM ux_shop WHERE id=?",Long.class,shop)==0) bloom.beforeInsert(shop);
                db.update("INSERT INTO ux_shop(id,name,description,revision) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE name=VALUES(name),description=VALUES(description),revision=revision+1",shop,s[0],s[1]);
                String hours=i%3==0?"09:00-21:30":i%3==1?"10:30-22:00":"10:00-21:00";
                String highlights=s[6]+",校园活动,限量抢票";
                String review="近期关注集中在“"+s[6].split(",")[0]+"”和现场体验。热度数据来自固定演示样例。";
                String notice="请按票面时间到场并携带校园身份证明；下单后 5 分钟内完成模拟支付，取消或超时将释放名额。";
                db.update("INSERT INTO ux_storefront(shop_id,category,area,address,image_path,published,rating,review_count,monthly_sales,distance_meters,business_hours,highlights,review_summary,service_notice) VALUES(?,?,?,?,?,TRUE,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE category=VALUES(category),area=VALUES(area),address=VALUES(address),image_path=VALUES(image_path),published=TRUE,rating=VALUES(rating),review_count=VALUES(review_count),monthly_sales=VALUES(monthly_sales),distance_meters=VALUES(distance_meters),business_hours=VALUES(business_hours),highlights=VALUES(highlights),review_summary=VALUES(review_summary),service_notice=VALUES(service_notice)",
                    shop,s[2],s[3],s[4],s[5],ratings[i],reviews[i],sales[i],distances[i],hours,highlights,review,notice);
                for(int offer=0;offer<2;offer++) {
                    long id=activityId(day,i,offer);
                    int capacity=offer==0?100:30,price=offer==0?prices[i]:prices[i]*3,face=offer==0?faces[i]:faces[i]*3;
                    db.update("INSERT INTO ux_activity(id,capacity,available,price_cents,starts_at,ends_at,process_until) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                        id,capacity,capacity,price,Timestamp.from(start),Timestamp.from(end),Timestamp.from(end.plusSeconds(300)));
                    db.update("INSERT INTO ux_offer(activity_id,shop_id,title,face_value_cents,terms) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE shop_id=VALUES(shop_id),title=VALUES(title),face_value_cents=VALUES(face_value_cents),terms=VALUES(terms)",
                        id,shop,offer==0?"学生单人票":"双人同行票",face,
                        "每人每票档限购一单；建单后 5 分钟内完成模拟支付，取消或过期后不可重复购买同一票档。仅用于本地演示，不涉及真实支付或现场核销。");
                }
                for(int slot=0;slot<2;slot++) {
                    int hour=10+((i+slot*3)%6)*2;
                    var sessionStart=sessionDay.atTime(hour,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
                    var sessionEnd=sessionStart.plusSeconds(90*60);
                    long sessionId=sessionId(sessionDay,i,slot);
                    String title=slot==0?s[0]+"午间场":s[0]+"晚间场";
                    String tags=s[2]+","+s[3]+","+s[6];
                    db.update("INSERT INTO ux_experience_session(id,shop_id,title,tags,starts_at,ends_at,price_cents,capacity,available,published) "
                        +"VALUES(?,?,?,?,?,?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE title=VALUES(title),tags=VALUES(tags),starts_at=VALUES(starts_at),ends_at=VALUES(ends_at),price_cents=VALUES(price_cents),capacity=VALUES(capacity),available=LEAST(available,VALUES(capacity)),published=TRUE",
                        sessionId,shop,title,tags,Timestamp.from(sessionStart),Timestamp.from(sessionEnd),prices[i],20,20);
                }
            }
            return null;
        });
        for(int i=0;i<stores.length;i++) for(int offer=0;offer<2;offer++) {
            long id=activityId(day,i,offer);
            Integer available=db.queryForObject("SELECT available FROM ux_activity WHERE id=?",Integer.class,id);
            if(db.queryForObject("SELECT COUNT(*) FROM ux_request WHERE activity_id=?",Long.class,id)==0)
                reservations.prepare(id,available==null?0:available);
        }
    }

    private long activityId(LocalDate day,int storeIndex,int offerIndex) {
        if(storeIndex<9) return 20000000+day.toEpochDay()*100+storeIndex*10+offerIndex;
        return 40000000+day.toEpochDay()*100+(storeIndex-9)*10+offerIndex;
    }

    private long sessionId(LocalDate day,int storeIndex,int slotIndex) {
        if(storeIndex<9) return 30000000+day.toEpochDay()*100+storeIndex*10+slotIndex;
        return 50000000+day.toEpochDay()*100+(storeIndex-9)*10+slotIndex;
    }
}
