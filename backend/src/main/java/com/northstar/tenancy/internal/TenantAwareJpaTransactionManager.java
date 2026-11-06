package com.northstar.tenancy.internal;

import com.northstar.tenancy.TenantContext;
import com.northstar.tenancy.TenantScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Every transaction, read-only ones included, starts by setting app.tenant_id, app.user_id and app.role on its own
 * connection (data-model.md section 12). Without a {@link TenantScope} the transaction is refused before anything is
 * opened. Spring holds the connection for the whole transaction, so every query in it runs with this context.
 */
final class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    TenantAwareJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        TenantContext context = TenantScope.require();
        super.doBegin(transaction, definition);
        try {
            EntityManagerHolder holder =
                    (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
            EntityManager entityManager = holder.getEntityManager();
            entityManager
                    .unwrap(Session.class)
                    .doWork(connection ->
                            connection.unwrap(TenantGuardedConnection.class).beginTenantTransaction(context));
        } catch (RuntimeException e) {
            abandon(transaction);
            throw e;
        }
    }

    /** Rolls back and releases what super.doBegin opened, so a failed begin leaves nothing bound to the thread. */
    private void abandon(Object transaction) {
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        if (holder != null && holder.getEntityManager().getTransaction().isActive()) {
            holder.getEntityManager().getTransaction().rollback();
        }
        doCleanupAfterCompletion(transaction);
    }
}
