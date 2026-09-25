package com.hmdp.upgrade;

import java.time.Instant;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerAuthTest {
    Auth auth;Accounts accounts;boolean passed;
    @BeforeEach void setup() {
        auth=new Auth("a".repeat(40));accounts=mock(Accounts.class);ReflectionTestUtils.setField(auth,"accounts",accounts);
        when(accounts.authenticate("token")).thenReturn(new Accounts.Session(new Accounts.User("123","alice","Alice"),"csrf",Instant.now().plusSeconds(100)));
    }
    MockHttpServletResponse call(String method,String path,String origin,String csrf,boolean cookie,byte[] body) throws Exception {
        var req=new MockHttpServletRequest(method,path);req.addHeader("Host","localhost:4176");
        if(origin!=null) req.addHeader("Origin",origin);if(csrf!=null) req.addHeader("X-CSRF-Token",csrf);
        if(cookie) req.setCookies(new Cookie("life_session","token"));req.setContentType("application/json");req.setContent(body);
        var response=new MockHttpServletResponse();auth.doFilter(req,response,(a,b)->passed=true);return response;
    }
    @Test void publicCatalogNeedsNoAccount() throws Exception { assertEquals(200,call("GET","/v2/catalog/shops",null,null,false,new byte[0]).getStatus());assertTrue(passed); }
    @Test void privateOrdersNeedSession() throws Exception { assertEquals(401,call("GET","/v2/account/requests",null,null,false,new byte[0]).getStatus());assertFalse(passed); }
    @Test void cookieWritesNeedCsrf() throws Exception { assertEquals(403,call("POST","/v2/requests","http://localhost:4176",null,true,new byte[0]).getStatus());assertFalse(passed); }
    @Test void correctSessionAndCsrfPass() throws Exception { assertEquals(200,call("POST","/v2/requests","http://localhost:4176","csrf",true,new byte[0]).getStatus());assertTrue(passed); }
    @Test void crossOriginLoginCannotReplaceSession() throws Exception { assertEquals(403,call("POST","/v2/auth/login","https://hostile.example",null,false,new byte[0]).getStatus());assertFalse(passed); }
    @Test void customerCannotAccessAdmin() throws Exception { assertEquals(403,call("GET","/v2/admin/status",null,null,true,new byte[0]).getStatus());assertFalse(passed); }
    @Test void oversizedCredentialsFailBeforeHashing() throws Exception { assertEquals(413,call("POST","/v2/auth/register",null,null,false,new byte[16385]).getStatus());assertFalse(passed);verifyNoInteractions(accounts); }
    @Test void bearerExperimentalIdentityDisabledInNormalMode() throws Exception {
        var req=new MockHttpServletRequest("GET","/v2/account/requests");req.addHeader("Authorization","Bearer experimental");
        var response=new MockHttpServletResponse();auth.doFilter(req,response,(a,b)->passed=true);assertEquals(401,response.getStatus());assertFalse(passed);
    }
    @Test void anotherTabAccountSwitchDoesNotMixOrderHistory() throws Exception {
        var req=new MockHttpServletRequest("GET","/v2/account/requests");req.setCookies(new Cookie("life_session","token"));req.addHeader("X-Account-ID","456");
        var response=new MockHttpServletResponse();auth.doFilter(req,response,(a,b)->passed=true);assertEquals(409,response.getStatus());assertFalse(passed);
    }
}
