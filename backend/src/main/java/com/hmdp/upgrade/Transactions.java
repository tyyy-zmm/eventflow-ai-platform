package com.hmdp.upgrade;

import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

@Component
public class Transactions {
    private final TransactionTemplate tx;
    public Transactions(PlatformTransactionManager manager) {
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
    }
    public <T> T run(Supplier<T> action) {
        for(int attempt=0;;attempt++) {
            try { return tx.execute(status -> action.get()); }
            catch(ConcurrencyFailureException retryable) {
                if(attempt==2) throw retryable;
                try { Thread.sleep(10L*(attempt+1)); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw retryable; }
            }
        }
    }
}
