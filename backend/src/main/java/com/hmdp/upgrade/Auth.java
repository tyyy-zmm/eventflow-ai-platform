package com.hmdp.upgrade;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.DataAccessException;

@Component
public class Auth extends OncePerRequestFilter {
    public record Identity(long user,boolean admin) {}
    private record Window(long second,AtomicInteger count) {}
    private final byte[] secret;
    private final ConcurrentHashMap<Long,Window> windows=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    private Accounts accounts;
    @Value("${upgrade.benchmark:false}")
    private boolean laboratory;
    @Value("${upgrade.metrics-token:}")
    private String metricsToken="";
    public Auth(@Value("${upgrade.auth-secret}") String secret) {
        if(secret.length()<32) throw new IllegalArgumentException("UPGRADE_AUTH_SECRET must contain at least 32 characters");
        this.secret=secret.getBytes(StandardCharsets.UTF_8);
    }
    Identity verify(String authorization) {
        try {
            if(authorization==null || !authorization.startsWith("Bearer ") || authorization.length()>512) throw new Exception();
            String[] token=authorization.substring(7).split("\\.");
            if(token.length!=2) throw new Exception();
            Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(secret,"HmacSHA256"));
            if(!MessageDigest.isEqual(hmac.doFinal(token[0].getBytes(StandardCharsets.UTF_8)),Base64.getUrlDecoder().decode(token[1]))) throw new Exception();
            String[] fields=token[0].split(":");
            if(fields.length!=3 || Long.parseLong(fields[2])<=System.currentTimeMillis()/1000) throw new Exception();
            long user=Long.parseLong(fields[0]);
            if(user<=0 || !java.util.List.of("user","admin").contains(fields[1])) throw new Exception();
            return new Identity(user,fields[1].equals("admin"));
        } catch(Exception e) { throw new Problem(401,"UNAUTHORIZED"); }
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        try {
            res.setHeader("Cache-Control","no-store");
            res.setHeader("X-Content-Type-Options","nosniff");
            boolean safe=req.getMethod().equals("GET") || req.getMethod().equals("HEAD");
            if(!safe) {
                sameOrigin(req);
                if(req.getContentLengthLong()>16384) throw new Problem(413,"BODY_TOO_LARGE");
                req=new BoundedJsonRequest(req);
            }
            String path=req.getRequestURI();
            boolean health=safe && (path.equals("/actuator/health") || path.startsWith("/actuator/health/"));
            String authorization=req.getHeader("Authorization");
            boolean metrics=metrics(path,safe,authorization);
            boolean catalog=safe && (path.equals("/v2/catalog/shops") || path.matches("/v2/catalog/shops/[0-9]+"));
            boolean signIn=req.getMethod().equals("POST") && (path.equals("/v2/auth/login") || path.equals("/v2/auth/register"));
            if(signIn && (req.getContentType()==null || !req.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")))
                throw new Problem(415,"JSON_REQUIRED");
            if(health || metrics || catalog || signIn) { chain.doFilter(req,res);return; }

            String sessionToken=cookie(req);
            Identity identity;
            if(sessionToken!=null) {
                var session=accounts.authenticate(sessionToken);
                identity=new Identity(Long.parseLong(session.user().id()),false);
                req.setAttribute("session",session);
                String expectedAccount=req.getHeader("X-Account-ID");
                if(expectedAccount!=null && !expectedAccount.equals(session.user().id())) throw new Problem(409,"SESSION_CHANGED");
                if(!safe) {
                    String supplied=req.getHeader("X-CSRF-Token");
                    if(supplied==null || !MessageDigest.isEqual(session.csrfToken().getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
                        throw new Problem(403,"CSRF_REJECTED");
                }
            } else if(laboratory) identity=verify(authorization);
            else throw new Problem(401,"UNAUTHORIZED");
            if(req.getRequestURI().startsWith("/v2/admin/") && !identity.admin()) throw new Problem(403,"ADMIN_REQUIRED");
            long second=System.currentTimeMillis()/1000;
            if(windows.size()>20000) windows.entrySet().removeIf(e->e.getValue().second()<second-2);
            if(windows.size()>20000 && !windows.containsKey(identity.user())) throw new Problem(503,"QUERY_LIMITER_FULL");
            Window window=windows.compute(identity.user(),(id,old)->old==null||old.second()!=second?new Window(second,new AtomicInteger()):old);
            if(window.count().incrementAndGet()>100) throw new Problem(429,"USER_REQUEST_RATE");
            req.setAttribute("identity",identity);
            chain.doFilter(req,res);
        } catch(Problem p) {
            res.setStatus(p.status);res.setContentType("application/json");
            if(p.status==429 || p.status==503) res.setHeader("Retry-After","1");
            res.getWriter().write("{\"error\":\""+p.code+"\"}");
        } catch(DataAccessException unavailable) {
            res.setStatus(503);res.setHeader("Retry-After","1");res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"DATABASE_UNAVAILABLE\"}");
        }
    }
    static String cookie(HttpServletRequest req) {
        if(req.getCookies()!=null) for(Cookie cookie:req.getCookies()) if(cookie.getName().equals("life_session")) return cookie.getValue();
        return null;
    }
    static void sameOrigin(HttpServletRequest req) {
        if("cross-site".equals(req.getHeader("Sec-Fetch-Site"))) throw new Problem(403,"ORIGIN_REJECTED");
        String origin=req.getHeader("Origin");
        if(origin==null) return;
        try {
            var parsed=java.net.URI.create(origin);
            if(!req.getScheme().equalsIgnoreCase(parsed.getScheme()) || !req.getHeader("Host").equalsIgnoreCase(parsed.getRawAuthority())
                || (parsed.getRawPath()!=null && !parsed.getRawPath().isEmpty())) throw new IllegalArgumentException();
        } catch(Exception e) { throw new Problem(403,"ORIGIN_REJECTED"); }
    }
    boolean metrics(String path,boolean safe,String authorization) {
        return safe && path.equals("/actuator/prometheus") && !metricsToken.isBlank() && authorization!=null
            && MessageDigest.isEqual(("Bearer "+metricsToken).getBytes(StandardCharsets.UTF_8),authorization.getBytes(StandardCharsets.UTF_8));
    }
}
