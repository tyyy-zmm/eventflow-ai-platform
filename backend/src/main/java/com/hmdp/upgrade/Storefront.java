package com.hmdp.upgrade;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
public class Storefront {
    public record Shop(long id,String name,String description,String category,String area,String address,String imagePath,Integer fromPrice,
                       int offerCount,int totalAvailable,int savingsPercent,Instant nextSessionAt,double rating,int reviewCount,
                       int monthlySales,int distanceMeters) {}
    public record Offer(long id,String title,int priceCents,int faceValueCents,int available,int capacity,
                        Instant startsAt,Instant endsAt,String terms) {}
    public record Listing(List<Shop> items,int total,int page,List<String> categories,List<String> areas,Instant serverTime) {}
    public record ShopProfile(String businessHours,List<String> highlights,String reviewSummary,String serviceNotice) {}
    public record Detail(Shop shop,ShopProfile profile,List<Offer> offers,Instant serverTime) {}
    private final JdbcTemplate db;
    private final ShopCache cache;
    public Storefront(JdbcTemplate db,ShopCache cache) { this.db=db;this.cache=cache; }
    private Instant now() { return db.queryForObject("SELECT CURRENT_TIMESTAMP(3)",Timestamp.class).toInstant(); }
    static Shop shop(ResultSet r,int n) throws SQLException {
        return new Shop(r.getLong("id"),r.getString("name"),r.getString("description"),r.getString("category"),r.getString("area"),
            r.getString("address"),r.getString("image_path"),r.getObject("from_price")==null?null:r.getInt("from_price"),
            r.getInt("offer_count"),r.getInt("total_available"),r.getInt("savings_percent"),
            r.getTimestamp("next_session_at")==null?null:r.getTimestamp("next_session_at").toInstant(),r.getDouble("rating"),
            r.getInt("review_count"),r.getInt("monthly_sales"),r.getInt("distance_meters"));
    }
    private static final String SELECT="""
        SELECT s.*,p.category,p.area,p.address,p.image_path,p.rating,p.review_count,p.monthly_sales,p.distance_meters,
          (SELECT MIN(a.price_cents) FROM ux_offer o JOIN ux_activity a ON a.id=o.activity_id
           WHERE o.shop_id=s.id AND a.ends_at>CURRENT_TIMESTAMP(3)) AS from_price,
          (SELECT COUNT(*) FROM ux_offer o JOIN ux_activity a ON a.id=o.activity_id
           WHERE o.shop_id=s.id AND a.ends_at>CURRENT_TIMESTAMP(3)) AS offer_count,
          COALESCE((SELECT SUM(a.available) FROM ux_offer o JOIN ux_activity a ON a.id=o.activity_id
           WHERE o.shop_id=s.id AND a.ends_at>CURRENT_TIMESTAMP(3)),0) AS total_available,
          COALESCE((SELECT MAX(ROUND((1-a.price_cents/o.face_value_cents)*100)) FROM ux_offer o JOIN ux_activity a ON a.id=o.activity_id
           WHERE o.shop_id=s.id AND a.ends_at>CURRENT_TIMESTAMP(3) AND o.face_value_cents>0),0) AS savings_percent,
          (SELECT MIN(e.starts_at) FROM ux_experience_session e
           WHERE e.shop_id=s.id AND e.published=TRUE AND e.starts_at>CURRENT_TIMESTAMP(3)) AS next_session_at
        FROM ux_shop s JOIN ux_storefront p ON p.shop_id=s.id
        """;
    public Listing list(String query,String category,String area,String sort,int page) {
        if(query.length()>80 || category.length()>24 || area.length()>40 || page<1 || page>500) throw new Problem(400,"INVALID_FILTER");
        String order=switch(sort) {
            case "", "recommended" -> "FIELD(s.id,9104,9107,9109,9105,9108,9111,9112,9101,9102,9103,9106,9110,9113,9114,9115),s.id";
            case "price" -> "from_price IS NULL,from_price,s.id";
            case "availability" -> "total_available DESC,offer_count DESC,s.id";
            default -> throw new Problem(400,"INVALID_FILTER");
        };
        String pattern="%"+query.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String where=" WHERE p.published=TRUE AND (s.name LIKE ? ESCAPE '!' OR s.description LIKE ? ESCAPE '!' OR p.category LIKE ? ESCAPE '!') AND (?='' OR p.category=?) AND (?='' OR p.area=?)";
        var rows=db.query(SELECT+where+" ORDER BY "+order+" LIMIT 12 OFFSET ?",Storefront::shop,pattern,pattern,pattern,category,category,area,area,(page-1)*12);
        int total=db.queryForObject("SELECT COUNT(*) FROM ux_shop s JOIN ux_storefront p ON p.shop_id=s.id"+where,Integer.class,pattern,pattern,pattern,category,category,area,area);
        return new Listing(rows,total,page,
            db.queryForList("SELECT DISTINCT category FROM ux_storefront WHERE published=TRUE ORDER BY category",String.class),
            db.queryForList("SELECT DISTINCT area FROM ux_storefront WHERE published=TRUE ORDER BY area",String.class),now());
    }
    public Detail detail(long id) {
        var rows=db.query(SELECT+" WHERE s.id=? AND p.published=TRUE",Storefront::shop,id);
        if(rows.isEmpty()) throw new Problem(404,"SHOP_NOT_FOUND");
        var metadata=rows.get(0);
        var cached=cache.get(id);
        if(cached==null) throw new Problem(404,"SHOP_NOT_FOUND");
        var view=new Shop(id,cached.name(),cached.description(),metadata.category(),metadata.area(),metadata.address(),metadata.imagePath(),metadata.fromPrice(),
            metadata.offerCount(),metadata.totalAvailable(),metadata.savingsPercent(),metadata.nextSessionAt(),metadata.rating(),metadata.reviewCount(),
            metadata.monthlySales(),metadata.distanceMeters());
        var profile=db.queryForObject("""
            SELECT business_hours,highlights,review_summary,service_notice FROM ux_storefront WHERE shop_id=?
            """,(r,n)->new ShopProfile(r.getString("business_hours"),split(r.getString("highlights")),
                r.getString("review_summary"),r.getString("service_notice")),id);
        var offers=db.query("""
            SELECT a.*,o.title,o.face_value_cents,o.terms FROM ux_offer o JOIN ux_activity a ON a.id=o.activity_id
            WHERE o.shop_id=? AND a.ends_at>CURRENT_TIMESTAMP(3) ORDER BY a.starts_at,a.price_cents,a.id LIMIT 20
            """,(r,n)->new Offer(r.getLong("id"),r.getString("title"),r.getInt("price_cents"),r.getInt("face_value_cents"),r.getInt("available"),r.getInt("capacity"),
                r.getTimestamp("starts_at").toInstant(),r.getTimestamp("ends_at").toInstant(),r.getString("terms")),id);
        return new Detail(view,profile,offers,now());
    }
    private static List<String> split(String value) {
        if(value==null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(x->!x.isEmpty()).toList();
    }
}

@RestController
@RequestMapping("/v2/catalog/shops")
class StorefrontApi {
    private final Storefront service;
    StorefrontApi(Storefront service) { this.service=service; }
    @GetMapping public Storefront.Listing list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String category,
                                                @RequestParam(defaultValue="") String area,@RequestParam(defaultValue="recommended") String sort,
                                                @RequestParam(defaultValue="1") int page) {
        return service.list(q,category,area,sort,page);
    }
    @GetMapping("/{id}") public Storefront.Detail detail(@PathVariable long id) { return service.detail(id); }
}
