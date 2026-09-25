package com.hmdp.upgrade;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/auth")
public class AccountApi {
    public record Credentials(String username,String password,String displayName) {}
    private final Accounts accounts;
    private final boolean secure;
    public AccountApi(Accounts accounts,@Value("${upgrade.cookie-secure:false}") boolean secure) { this.accounts=accounts;this.secure=secure; }
    private String cookie(String value,long age) {
        return ResponseCookie.from("life_session",value).httpOnly(true).secure(secure).sameSite("Strict").path("/v2").maxAge(age).build().toString();
    }
    private ResponseEntity<?> signedIn(Accounts.Login login,int status) {
        return ResponseEntity.status(status).header("Set-Cookie",cookie(login.cookie(),28800)).body(login.session());
    }
    @PostMapping("/register") public ResponseEntity<?> register(@RequestBody Credentials body,HttpServletRequest req) {
        return signedIn(accounts.register(body.username(),body.displayName(),body.password(),req.getRemoteAddr()),201);
    }
    @PostMapping("/login") public ResponseEntity<?> login(@RequestBody Credentials body,HttpServletRequest req) {
        return signedIn(accounts.login(body.username(),body.password(),req.getRemoteAddr()),200);
    }
    @GetMapping("/me") public Accounts.Session me(HttpServletRequest req) {
        var session=(Accounts.Session)req.getAttribute("session");
        if(session==null) throw new Problem(401,"CUSTOMER_SESSION_REQUIRED");
        return session;
    }
    @PostMapping("/logout") public ResponseEntity<?> logout(HttpServletRequest req) {
        accounts.logout(Auth.cookie(req));
        return ResponseEntity.noContent().header("Set-Cookie",cookie("",0)).build();
    }
}
