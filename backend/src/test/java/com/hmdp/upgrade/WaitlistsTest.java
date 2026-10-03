package com.hmdp.upgrade;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WaitlistsTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final Reservations reservations=mock(Reservations.class);
    final Admission admission=mock(Admission.class);
    final Trading trading=mock(Trading.class);
    final Waitlists service=new Waitlists(db,mock(Transactions.class),reservations,admission,trading,true);
    final Waitlists.Claim claim=new Waitlists.Claim("wait-id",100,7,"wait_key");

    @Test void promotionPassesReservationEpochToTransaction() {
        when(reservations.reserve(eq(100L),eq(7L),eq("wait_key"),any())).thenReturn(new Reservations.Result(Reservations.Decision.ADMITTED,true,"epoch-1"));
        when(trading.acceptReserved(7,"wait_key",100,false,"epoch-1")).thenThrow(new IllegalStateException("uncertain database commit"));
        service.promote(claim);
        verify(trading).acceptReserved(7,"wait_key",100,false,"epoch-1");
        // A transport failure does not prove rollback. Reconciliation adjudicates it.
        verify(reservations,never()).releaseNow(anyLong(),anyLong(),anyString(),anyString());
        verify(admission).leave();
    }
    @Test void releasedReplayCannotCreateALateOrder() {
        when(reservations.reserve(eq(100L),eq(7L),eq("wait_key"),any())).thenReturn(new Reservations.Result(Reservations.Decision.REPLAY_RELEASED,false,"epoch-1"));
        service.promote(claim);
        verify(trading,never()).acceptReserved(anyLong(),anyString(),anyLong(),anyBoolean(),anyString());
        verifyNoInteractions(admission);
        verify(db).update("UPDATE ux_waitlist SET state=?,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND state='PROMOTING'","EXPIRED","wait-id");
    }
    @Test void definiteRejectionReleasesOnlyItsEpoch() {
        when(reservations.reserve(eq(100L),eq(7L),eq("wait_key"),any())).thenReturn(new Reservations.Result(Reservations.Decision.ADMITTED,true,"epoch-1"));
        when(trading.acceptReserved(7,"wait_key",100,false,"epoch-1")).thenThrow(new Problem(409,"ACTIVITY_CLOSED"));
        service.promote(claim);
        verify(reservations).releaseNow(100,7,"wait_key","epoch-1");
        verify(admission).leave();
    }
}
