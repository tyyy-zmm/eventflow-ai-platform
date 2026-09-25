package com.hmdp.upgrade;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TransactionsTest {
    @Test void retryIsBoundedAndEachFailedAttemptRollsBack() {
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(x->new SimpleTransactionStatus());
        var count=new AtomicInteger();
        assertThrows(CannotAcquireLockException.class,()->new Transactions(manager).run(()->{count.incrementAndGet();throw new CannotAcquireLockException("deadlock");}));
        assertEquals(3,count.get());verify(manager,times(3)).rollback(any());verify(manager,never()).commit(any());
    }
    @Test void retryRunsEntireActionThenCommitsOnce() {
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(x->new SimpleTransactionStatus());
        var count=new AtomicInteger();
        assertEquals("ok",new Transactions(manager).run(()->{if(count.incrementAndGet()<3)throw new CannotAcquireLockException("busy");return "ok";}));
        verify(manager,times(2)).rollback(any());verify(manager,times(1)).commit(any());
    }
}
