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
            {"西岸餐桌","炭烤牛排、当季蔬菜与双人套餐。","西餐","徐汇 · 西岸","西岸体验街区 18 号","/life-assets/steak.jpg","约会,安静"},
            {"青柠小馆","泰式风味与新鲜香料，适合与朋友分享。","亚洲菜","静安 · 南京西路","南京西路体验街区 26 号","/life-assets/thai.jpg","朋友聚会,风味"},
            {"街角小食","清爽小食、鲜虾与轻食组合。","轻食","杨浦 · 大学路","大学路体验街区 9 号","/life-assets/light.jpg","轻食,休闲"},
            {"云间咖啡","手冲咖啡、当日甜点与安静阅读空间。","咖啡甜品","长宁 · 愚园路","愚园路体验街区 37 号","/life-assets/coffee.jpg","咖啡,独处,安静"},
            {"沸腾里","鲜切食材与多人锅底套餐，适合聚会。","火锅","黄浦 · 新天地","马当路体验街区 12 号","/life-assets/hotpot.jpg","聚餐,热闹"},
            {"炭火研究所","现烤肉串与时令小菜，提供双人组合。","烧烤","普陀 · 长寿路","长寿路体验街区 52 号","/life-assets/steak.jpg","夜间,朋友聚会"},
            {"小小造物社","面向亲子的手作课程与主题创作体验。","亲子体验","浦东 · 前滩","前滩体验街区 21 号","/life-assets/family-craft.jpg","亲子,手作"},
            {"城市攀岩馆","零基础攀岩体验与教练安全指导。","运动健身","虹口 · 北外滩","东大名路体验街区 66 号","/life-assets/climbing.jpg","运动,挑战"},
            {"光影现场","小型展览、沉浸演出与周末限定活动。","展览演出","静安 · 苏河湾","北苏州路体验街区 8 号","/life-assets/gallery.jpg","展览,演出,周末"},
            {"鮨月料理","当季刺身、炙烤小食与午间定食。","日料","徐汇 · 衡山路","衡山路体验街区 23 号","/life-assets/thai.jpg","日料,约会"},
            {"麦香工房","现烤欧包、可颂与周末烘焙体验课。","烘焙体验","静安 · 武定路","武定路体验街区 41 号","/life-assets/coffee.jpg","烘焙,下午茶"},
            {"栖心疗愈所","都市芳疗、肩颈舒缓与双人放松体验。","SPA按摩","长宁 · 古北","黄金城道体验街区 16 号","/life-assets/spa.jpg","放松,预约"},
            {"回合制空间","桌游包间、主持带玩与多人主题局。","桌游娱乐","黄浦 · 人民广场","西藏中路体验街区 29 号","/life-assets/gallery.jpg","桌游,朋友聚会"},
            {"毛球日记","宠物洗护、基础美容与陪伴互动体验。","宠物生活","浦东 · 世纪公园","梅花路体验街区 35 号","/life-assets/family-craft.jpg","宠物,休闲"},
            {"映刻写真馆","轻写真、证件照与城市旅拍套餐。","摄影写真","杨浦 · 五角场","大学路体验街区 55 号","/life-assets/gallery.jpg","摄影,纪念"}
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
                String highlights=s[6]+",可预约,到店体验";
                String review="环境与服务稳定，近期评价集中提到“"+s[6].split(",")[0]+"”和预约体验。演示评分来自固定样例数据。";
                String notice="请按预约时间到店；权益不与其他优惠同享。购买后 5 分钟内确认，取消或超时将释放名额。";
                db.update("INSERT INTO ux_storefront(shop_id,category,area,address,image_path,published,rating,review_count,monthly_sales,distance_meters,business_hours,highlights,review_summary,service_notice) VALUES(?,?,?,?,?,TRUE,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE category=VALUES(category),area=VALUES(area),address=VALUES(address),image_path=VALUES(image_path),published=TRUE,rating=VALUES(rating),review_count=VALUES(review_count),monthly_sales=VALUES(monthly_sales),distance_meters=VALUES(distance_meters),business_hours=VALUES(business_hours),highlights=VALUES(highlights),review_summary=VALUES(review_summary),service_notice=VALUES(service_notice)",
                    shop,s[2],s[3],s[4],s[5],ratings[i],reviews[i],sales[i],distances[i],hours,highlights,review,notice);
                for(int offer=0;offer<2;offer++) {
                    long id=activityId(day,i,offer);
                    int capacity=offer==0?100:30,price=offer==0?prices[i]:prices[i]*3,face=offer==0?faces[i]:faces[i]*3;
                    db.update("INSERT INTO ux_activity(id,capacity,available,price_cents,starts_at,ends_at,process_until) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                        id,capacity,capacity,price,Timestamp.from(start),Timestamp.from(end),Timestamp.from(end.plusSeconds(300)));
                    db.update("INSERT INTO ux_offer(activity_id,shop_id,title,face_value_cents,terms) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE shop_id=VALUES(shop_id),title=VALUES(title),face_value_cents=VALUES(face_value_cents),terms=VALUES(terms)",
                        id,shop,offer==0?"单人到店代金券":"双人分享组合券",face,
                        "每人每场限一单；建单后5分钟内确认，取消或过期后不可重复购买同场。仅用于本地演示，不涉及真实支付或商家核销。");
                }
                for(int slot=0;slot<2;slot++) {
                    int hour=10+((i+slot*3)%6)*2;
                    var sessionStart=sessionDay.atTime(hour,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
                    var sessionEnd=sessionStart.plusSeconds(90*60);
                    long sessionId=sessionId(sessionDay,i,slot);
                    String title=slot==0?s[0]+"午间体验":s[0]+"晚间体验";
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
