package com.hmdp.upgrade;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class AccountsTest {
    JdbcTemplate db;Accounts accounts;Trading trading;
    static final String PASSWORD="test-only-passphrase-2026";
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__upgrade.sql"),new ClassPathResource("db/migration/V2__customer_sessions_and_storefront.sql"),new ClassPathResource("db/migration/V3__reservation_actions.sql")).execute(source);
        db=new JdbcTemplate(source);var tx=new Transactions(new DataSourceTransactionManager(source));accounts=new Accounts(db,tx);trading=new Trading(db,tx);
    }
    Accounts.Login register() { return accounts.register("Alice_01","小唐",PASSWORD,"unit"); }
    @Test void storesPasswordAndSessionAsHashesAndCanonicalizesUsername() {
        var login=register();assertEquals("alice_01",login.session().user().username());
        String hash=db.queryForObject("SELECT password_hash FROM ux_account",String.class);
        assertNotEquals(PASSWORD,hash);assertTrue(new BCryptPasswordEncoder().matches(PASSWORD,hash));
        assertEquals(Accounts.digest(login.cookie()),db.queryForObject("SELECT token_hash FROM ux_session",String.class));
        assertNotEquals(login.cookie(),db.queryForObject("SELECT token_hash FROM ux_session",String.class));
        assertEquals(login.session(),accounts.authenticate(login.cookie()));
        assertTrue(Long.parseLong(login.session().user().id())>=4000000000000000L);
    }
    @Test void duplicateUsernameCannotCreateAnotherAccount() {
        register();assertEquals(409,assertThrows(Problem.class,()->accounts.register("ALICE_01","other",PASSWORD,"unit")).status);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_account",Integer.class));
    }
    @Test void invalidAndUnknownCredentialsFailIdentically() {
        register();assertEquals("BAD_CREDENTIALS",assertThrows(Problem.class,()->accounts.login("alice_01","wrong-password-long","unit")).code);
        assertEquals("BAD_CREDENTIALS",assertThrows(Problem.class,()->accounts.login("unknown_user",PASSWORD,"unit")).code);
    }
    @Test void passwordsRespectBcryptUtf8Limit() {
        assertThrows(Problem.class,()->Accounts.password("short"));
        assertThrows(Problem.class,()->Accounts.password("汉".repeat(25)));
        assertDoesNotThrow(()->Accounts.password("汉".repeat(24)));
        assertThrows(Problem.class,()->Accounts.username("a' OR 1=1"));
    }
    @Test void logoutAndExpiryRevokeSessionButNotAccount() {
        var login=register();accounts.logout(login.cookie());assertThrows(Problem.class,()->accounts.authenticate(login.cookie()));
        var again=accounts.login("alice_01",PASSWORD,"unit");assertNotEquals(login.cookie(),again.cookie());
        db.update("UPDATE ux_session SET expires_at=?",Timestamp.from(Instant.now().minusSeconds(1)));
        assertThrows(Problem.class,()->accounts.authenticate(again.cookie()));accounts.cleanup();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ux_session",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ux_account",Integer.class));
    }
    @Test void ninthSessionRevokesOldestAndCapsStorage() {
        var first=register();for(int i=0;i<8;i++) accounts.login("alice_01",PASSWORD,"unit");
        assertEquals(8,db.queryForObject("SELECT COUNT(*) FROM ux_session",Integer.class));
        assertThrows(Problem.class,()->accounts.authenticate(first.cookie()));
    }
    @Test void customerQueryDoesNotLeakAnotherUsersOrdersAndExpiresOverdueOrders() {
        var now=Instant.now();db.update("INSERT INTO ux_activity VALUES(1,2,2,990,?,?,?)",Timestamp.from(now.minusSeconds(1)),Timestamp.from(now.plusSeconds(60)),Timestamp.from(now.plusSeconds(90)));
        var request=trading.accept(1,"customer01",1,true);var queries=new CustomerRequests(db,trading);
        assertEquals(0,queries.list(2,1,"").total());
        assertThrows(Problem.class,()->queries.get(2,"customer01",true));
        assertEquals(request.id(),queries.get(1,"customer01",true).item().id());
        db.update("UPDATE ux_order SET confirm_until=?",Timestamp.from(now.minusSeconds(1)));
        assertEquals("EXPIRED",queries.get(1,request.id(),false).item().orderState());
        assertEquals(2,db.queryForObject("SELECT available FROM ux_activity WHERE id=1",Integer.class));
        assertEquals(1,queries.list(1,1,"closed").total());assertEquals(0,queries.list(1,1,"pending").total());
        assertThrows(Problem.class,()->queries.list(1,0,""));
    }
}
