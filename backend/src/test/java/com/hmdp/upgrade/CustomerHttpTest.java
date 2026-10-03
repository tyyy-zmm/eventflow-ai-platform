package com.hmdp.upgrade;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="UPGRADE_INTEGRATION",matches="true")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"upgrade.demo-data=true","upgrade.jobs=true","upgrade.benchmark=false","upgrade.planning.mode=stub","upgrade.sandbox-payments=true"})
class CustomerHttpTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired Reservations reservations;
    record Client(String cookie,String csrf,String username,String id) {}
    static final String PASSWORD="b6-local-test-passphrase";
    ResponseEntity<Map> request(HttpMethod method,String path,Client client,Object body) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(client!=null) { headers.set("Cookie",client.cookie());headers.set("X-CSRF-Token",client.csrf()); }
        return http.exchange("http://127.0.0.1:"+port+"/v2"+path,method,new HttpEntity<>(body,headers),Map.class);
    }
    Client register() {
        String username="http_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        var result=request(HttpMethod.POST,"/auth/register",null,Map.of("username",username,"password",PASSWORD,"displayName","测试用户"));
        assertEquals(201,result.getStatusCode().value());String cookie=result.getHeaders().getFirst("Set-Cookie");
        assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));assertTrue(cookie.contains("Path=/v2"));
        return new Client(cookie.split(";")[0],(String)result.getBody().get("csrfToken"),username,(String)((Map)result.getBody().get("user")).get("id"));
    }
    long activity(int capacity) {
        long id=System.currentTimeMillis()*1000+new Random().nextInt(999);Instant now=Instant.now();
        db.update("INSERT INTO ux_activity VALUES(?,?,?,?,?,?,?)",id,capacity,capacity,990,Timestamp.from(now.minusSeconds(1)),Timestamp.from(now.plusSeconds(300)),Timestamp.from(now.plusSeconds(600)));reservations.prepare(id,capacity);return id;
    }
    Map submit(Client client,long activity,String key) {
        var result=request(HttpMethod.POST,"/requests",client,Map.of("requestId",key,"activityId",activity));
        assertTrue(result.getStatusCode().is2xxSuccessful(),result.toString());return result.getBody();
    }
    Map settled(Client client,String id) throws Exception {
        long until=System.nanoTime()+15_000_000_000L;
        while(System.nanoTime()<until) {
            var result=request(HttpMethod.GET,"/account/requests/"+id,client,null);assertEquals(200,result.getStatusCode().value());
            var item=(Map)result.getBody().get("item");if(!item.get("state").equals("ACCEPTED")) return item;Thread.sleep(150);
        } throw new AssertionError("request did not settle");
    }
    @Test void sessionRestoreLogoutAndLogin() {
        var client=register();assertEquals(200,request(HttpMethod.GET,"/auth/me",client,null).getStatusCode().value());
        assertEquals(204,request(HttpMethod.POST,"/auth/logout",client,null).getStatusCode().value());
        assertEquals(401,request(HttpMethod.GET,"/auth/me",client,null).getStatusCode().value());
        var login=request(HttpMethod.POST,"/auth/login",null,Map.of("username",client.username(),"password",PASSWORD));
        assertEquals(200,login.getStatusCode().value());assertNotEquals(client.cookie(),login.getHeaders().getFirst("Set-Cookie").split(";")[0]);
        assertEquals(client.id(),((Map)login.getBody().get("user")).get("id"));
    }
    @Test void sandboxPaymentHttpChecksOwnerCsrfAmountAndLateReceipt() throws Exception {
        var alice=register();var bob=register();String r=(String)submit(alice,activity(1),UUID.randomUUID().toString()).get("id");settled(alice,r);
        var created=request(HttpMethod.POST,"/account/payments",alice,Map.of("requestId",r));assertEquals(200,created.getStatusCode().value());
        String p=(String)created.getBody().get("id"),route="/account/payments/"+p+"/sandbox-receipt",channel="http_"+UUID.randomUUID();
        assertEquals(404,request(HttpMethod.GET,"/account/payments/"+p,bob,null).getStatusCode().value());
        assertEquals(403,request(HttpMethod.POST,route,new Client(alice.cookie(),"wrong",alice.username(),alice.id()),Map.of("channelId",channel,"amountCents",990)).getStatusCode().value());
        assertEquals(400,request(HttpMethod.POST,route,alice,Map.of("channelId",channel,"amountCents",1)).getStatusCode().value());
        assertEquals(409,request(HttpMethod.POST,"/requests/"+r+"/confirm",alice,null).getStatusCode().value());
        request(HttpMethod.POST,"/requests/"+r+"/cancel",alice,null);
        var paid=request(HttpMethod.POST,route,alice,Map.of("channelId",channel,"amountCents",990));assertEquals(200,paid.getStatusCode().value());
        assertTrue(Set.of("REFUND_PENDING","REFUNDED").contains(paid.getBody().get("outcome")));
        for(int i=0;i<40;i++) {
            var status=request(HttpMethod.GET,"/account/payments/"+p,alice,null);
            if("REFUNDED".equals(status.getBody().get("state"))) break;
            Thread.sleep(100);
        }
        assertEquals("REFUNDED",request(HttpMethod.GET,"/account/payments/"+p,alice,null).getBody().get("state"));
        assertEquals("REFUNDED",request(HttpMethod.POST,route,alice,Map.of("channelId",channel,"amountCents",990)).getBody().get("outcome"));
    }
    @Test void publicCatalogFiltersAndLiveStock() {
        var list=request(HttpMethod.GET,"/catalog/shops?q=西岸",null,null);assertEquals(200,list.getStatusCode().value());
        assertEquals(1,list.getBody().get("total"));
        var detail=request(HttpMethod.GET,"/catalog/shops/9101",null,null);assertEquals(200,detail.getStatusCode().value());
        assertFalse(((List)detail.getBody().get("offers")).isEmpty());
        assertEquals(0,request(HttpMethod.GET,"/catalog/shops?q=not_a_merchant",null,null).getBody().get("total"));
    }
    @Test void acceptedReplayCreatesOneOrderAndConfirmIsIdempotent() throws Exception {
        var client=register();long activity=activity(2);String key=UUID.randomUUID().toString();
        var first=submit(client,activity,key);var replay=submit(client,activity,key);assertEquals(first.get("id"),replay.get("id"));
        String id=(String)first.get("id");assertEquals("PENDING_CONFIRM",settled(client,id).get("orderState"));
        for(int i=0;i<2;i++) assertEquals("CONFIRMED",request(HttpMethod.POST,"/requests/"+id+"/confirm",client,null).getBody().get("state"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_order WHERE activity_id=?",Integer.class,activity));
        assertEquals(1,db.queryForObject("SELECT available FROM ux_activity WHERE id=?",Integer.class,activity));
    }
    @Test void cancellationReleasesOnceAndCannotRepurchase() throws Exception {
        var client=register();long activity=activity(1);String id=(String)submit(client,activity,UUID.randomUUID().toString()).get("id");settled(client,id);
        for(int i=0;i<2;i++) assertEquals("CANCELLED",request(HttpMethod.POST,"/requests/"+id+"/cancel",client,null).getBody().get("state"));
        assertEquals(1,db.queryForObject("SELECT available FROM ux_activity WHERE id=?",Integer.class,activity));
        var duplicate=request(HttpMethod.POST,"/requests",client,Map.of("requestId",UUID.randomUUID().toString(),"activityId",activity));
        assertEquals(409,duplicate.getStatusCode().value());assertEquals("ALREADY_PURCHASED",duplicate.getBody().get("error"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_request WHERE activity_id=?",Integer.class,activity));
    }
    @Test void otherAccountCannotReadOrCancelAndCustomerCannotAdmin() throws Exception {
        var alice=register();var bob=register();String key=UUID.randomUUID().toString();String id=(String)submit(alice,activity(1),key).get("id");settled(alice,id);
        assertEquals(404,request(HttpMethod.GET,"/account/requests/"+id,bob,null).getStatusCode().value());
        assertEquals(404,request(HttpMethod.GET,"/account/requests/by-key/"+key,bob,null).getStatusCode().value());
        assertEquals(404,request(HttpMethod.POST,"/requests/"+id+"/cancel",bob,null).getStatusCode().value());
        assertEquals(403,request(HttpMethod.GET,"/admin/status",alice,null).getStatusCode().value());
        assertEquals(403,request(HttpMethod.POST,"/auth/logout",new Client(alice.cookie(),"wrong",alice.username(),alice.id()),null).getStatusCode().value());
    }
    @Test void overdueConfirmationClosesAndReleases() throws Exception {
        var client=register();long activity=activity(1);String id=(String)submit(client,activity,UUID.randomUUID().toString()).get("id");settled(client,id);
        db.update("UPDATE ux_order SET confirm_until=? WHERE request_id=?",Timestamp.from(Instant.now().minusSeconds(1)),id);
        assertEquals("EXPIRED",request(HttpMethod.POST,"/requests/"+id+"/confirm",client,null).getBody().get("state"));
        assertEquals(1,db.queryForObject("SELECT available FROM ux_activity WHERE id=?",Integer.class,activity));
    }

    @Test void waitlistPromotionUsesTheSameReservationAndOrder() throws Exception {
        var owner=register();var next=register();long activity=activity(1);
        String first=(String)submit(owner,activity,UUID.randomUUID().toString()).get("id");settled(owner,first);
        var joined=request(HttpMethod.POST,"/waitlists",next,Map.of("activityId",activity));
        assertEquals(200,joined.getStatusCode().value());String waitId=(String)joined.getBody().get("id");
        assertEquals(404,request(HttpMethod.POST,"/waitlists/"+waitId+"/cancel",owner,null).getStatusCode().value());
        assertEquals("CANCELLED",request(HttpMethod.POST,"/requests/"+first+"/cancel",owner,null).getBody().get("state"));
        String promoted=null;long until=System.nanoTime()+20_000_000_000L;
        while(System.nanoTime()<until) {
            promoted=db.queryForObject("SELECT promoted_request_id FROM ux_waitlist WHERE id=?",String.class,waitId);
            if(promoted!=null) break;Thread.sleep(150);
        }
        assertNotNull(promoted);assertEquals("PENDING_CONFIRM",settled(next,promoted).get("orderState"));
        assertEquals("PROMOTED",db.queryForObject("SELECT state FROM ux_waitlist WHERE id=?",String.class,waitId));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_request WHERE user_id=? AND activity_id=?",Integer.class,Long.parseLong(next.id()),activity));
        assertEquals(0,db.queryForObject("SELECT available FROM ux_activity WHERE id=?",Integer.class,activity));
    }
    @Test void cancelledWaitlistDoesNotCreateAnOrder() throws Exception {
        var owner=register();var next=register();long activity=activity(1);
        String first=(String)submit(owner,activity,UUID.randomUUID().toString()).get("id");settled(owner,first);
        String id=(String)request(HttpMethod.POST,"/waitlists",next,Map.of("activityId",activity)).getBody().get("id");
        assertEquals("CANCELLED",request(HttpMethod.POST,"/waitlists/"+id+"/cancel",next,null).getBody().get("state"));
        String previousKey=db.queryForObject("SELECT request_key FROM ux_waitlist WHERE id=?",String.class,id);
        assertEquals("WAITING",request(HttpMethod.POST,"/waitlists",next,Map.of("activityId",activity)).getBody().get("state"));
        assertNotEquals(previousKey,db.queryForObject("SELECT request_key FROM ux_waitlist WHERE id=?",String.class,id));
        assertEquals("CANCELLED",request(HttpMethod.POST,"/waitlists/"+id+"/cancel",next,null).getBody().get("state"));
        request(HttpMethod.POST,"/requests/"+first+"/cancel",owner,null);Thread.sleep(1500);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_request WHERE user_id=? AND activity_id=?",Integer.class,Long.parseLong(next.id()),activity));
    }
    @Test void planningSharesSessionAndProtectsTaskOwnership() throws Exception {
        var owner=register();var other=register();var tomorrow=LocalDate.now(ZoneOffset.UTC).plusDays(1);
        var input=Map.of("constraints",Map.of("city","上海","from",tomorrow.atStartOfDay().toString(),"until",tomorrow.plusDays(1).atStartOfDay().toString(),"budgetCents",20000,"people",1),"preference","轻松用餐");
        var body=Map.of("requestKey","plan_"+UUID.randomUUID(),"input",input);
        var created=request(HttpMethod.POST,"/planning/tasks",owner,body);assertEquals(202,created.getStatusCode().value());
        String id=(String)created.getBody().get("id");
        assertEquals(id,request(HttpMethod.POST,"/planning/tasks",owner,body).getBody().get("id"));
        assertEquals(404,request(HttpMethod.GET,"/planning/tasks/"+id,other,null).getStatusCode().value());
        Map task=null;long until=System.nanoTime()+15_000_000_000L;
        while(System.nanoTime()<until) {
            task=request(HttpMethod.GET,"/planning/tasks/"+id,owner,null).getBody();
            if(!List.of("QUEUED","RUNNING").contains(task.get("status"))) break;Thread.sleep(150);
        }
        assertEquals("SUCCEEDED",task.get("status"));
        assertEquals("stub",((Map)task.get("result")).get("mode"));
    }
}
