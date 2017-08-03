package com.cockroachlabs;

import org.hibernate.TransactionException;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.transaction.spi.IsolationDelegate;
import org.hibernate.engine.transaction.spi.TransactionObserver;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.resource.jdbc.spi.JdbcSessionOwner;
import org.hibernate.resource.transaction.backend.jdbc.internal.JdbcIsolationDelegate;
import org.hibernate.resource.transaction.backend.jdbc.internal.JdbcResourceLocalTransactionCoordinatorImpl;
import org.hibernate.resource.transaction.backend.jdbc.spi.JdbcResourceTransaction;
import org.hibernate.resource.transaction.backend.jdbc.spi.JdbcResourceTransactionAccess;
import org.hibernate.resource.transaction.internal.SynchronizationRegistryStandardImpl;
import org.hibernate.resource.transaction.spi.*;

import javax.transaction.Status;
import java.util.ArrayList;
import java.util.List;

import static org.hibernate.internal.CoreLogging.messageLogger;

/**
 * Copied from
 *   https://github.com/hibernate/hibernate-orm/blob/master/hibernate-core/src/main/java/org/hibernate/resource/transaction/backend/jdbc/internal/JdbcResourceLocalTransactionCoordinatorImpl.java
 * and modified to remove the "rollback only" state for transactions, to support returning to a savepoint,
 * which is required to take full advantage of CockroachDB's transaction retries.
 */
public class CockroachDBTransactionCoordinator implements TransactionCoordinator {
    private static final CoreMessageLogger log = messageLogger( JdbcResourceLocalTransactionCoordinatorImpl.class );

    private final TransactionCoordinatorBuilder transactionCoordinatorBuilder;
    private final JdbcResourceTransactionAccess jdbcResourceTransactionAccess;
    private final TransactionCoordinatorOwner transactionCoordinatorOwner;
    private final SynchronizationRegistryStandardImpl synchronizationRegistry = new SynchronizationRegistryStandardImpl();

    private TransactionDriverControlImpl physicalTransactionDelegate;

    private int timeOut = -1;

    private final transient List<TransactionObserver> observers;

    /**
     * Construct a ResourceLocalTransactionCoordinatorImpl instance.  package-protected to ensure access goes through
     * builder.
     *
     * @param owner The transactionCoordinatorOwner
     */
    CockroachDBTransactionCoordinator(
            TransactionCoordinatorBuilder transactionCoordinatorBuilder,
            TransactionCoordinatorOwner owner,
            JdbcResourceTransactionAccess jdbcResourceTransactionAccess) {
        this.observers = new ArrayList<TransactionObserver>();
        this.transactionCoordinatorBuilder = transactionCoordinatorBuilder;
        this.jdbcResourceTransactionAccess = jdbcResourceTransactionAccess;
        this.transactionCoordinatorOwner = owner;
    }

    @Override
    public TransactionDriver getTransactionDriverControl() {
        // Again, this PhysicalTransactionDelegate will act as the bridge from the local transaction back into the
        // coordinator.  We lazily build it as we invalidate each delegate after each transaction (a delegate is
        // valid for just one transaction)
        if ( physicalTransactionDelegate == null ) {
            physicalTransactionDelegate = new TransactionDriverControlImpl( jdbcResourceTransactionAccess.getResourceLocalTransaction() );
        }
        return physicalTransactionDelegate;
    }

    @Override
    public void explicitJoin() {
        // nothing to do here, but log a warning
        log.callingJoinTransactionOnNonJtaEntityManager();
    }

    @Override
    public boolean isJoined() {
        return physicalTransactionDelegate != null && getTransactionDriverControl().isActive( true );
    }

    @Override
    public void pulse() {
        // nothing to do here
    }

    @Override
    public SynchronizationRegistry getLocalSynchronizations() {
        return synchronizationRegistry;
    }

    @Override
    public boolean isActive() {
        return transactionCoordinatorOwner.isActive();
    }

    @Override
    public IsolationDelegate createIsolationDelegate() {
        final JdbcSessionOwner jdbcSessionOwner = transactionCoordinatorOwner.getJdbcSessionOwner();

        return new JdbcIsolationDelegate(
                jdbcSessionOwner.getJdbcConnectionAccess(),
                jdbcSessionOwner.getJdbcSessionContext().getServiceRegistry().getService( JdbcServices.class ).getSqlExceptionHelper()
        );
    }

    @Override
    public TransactionCoordinatorBuilder getTransactionCoordinatorBuilder() {
        return this.transactionCoordinatorBuilder;
    }

    @Override
    public void setTimeOut(int seconds) {
        this.timeOut = seconds;
    }

    @Override
    public int getTimeOut() {
        return this.timeOut;
    }

    // PhysicalTransactionDelegate ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    private void afterBeginCallback() {
        if(this.timeOut > 0) {
            transactionCoordinatorOwner.setTransactionTimeOut( this.timeOut );
        }
        transactionCoordinatorOwner.afterTransactionBegin();
        for ( TransactionObserver observer : observers ) {
            observer.afterBegin();
        }
        log.trace( "CockroachDBTransactionCoordinator#afterBeginCallback" );
    }

    private void beforeCompletionCallback() {
        log.trace( "CockroachDBTransactionCoordinator#beforeCompletionCallback" );
        try {
            transactionCoordinatorOwner.beforeTransactionCompletion();
            synchronizationRegistry.notifySynchronizationsBeforeTransactionCompletion();
            for ( TransactionObserver observer : observers ) {
                observer.beforeCompletion();
            }
        }
        catch (RuntimeException e) {
            if ( physicalTransactionDelegate != null ) {
                // should never happen that the physicalTransactionDelegate is null, but to be safe
                physicalTransactionDelegate.markRollbackOnly();
            }
            throw e;
        }
    }

    private void afterCompletionCallback(boolean successful) {
        log.tracef( "CockroachDBTransactionCoordinator#afterCompletionCallback(%s)", successful );
        final int statusToSend = successful ? Status.STATUS_COMMITTED : Status.STATUS_UNKNOWN;
        synchronizationRegistry.notifySynchronizationsAfterTransactionCompletion( statusToSend );

        transactionCoordinatorOwner.afterTransactionCompletion( successful, false );
        for ( TransactionObserver observer : observers ) {
            observer.afterCompletion( successful, false );
        }
    }

    public void addObserver(TransactionObserver observer) {
        observers.add( observer );
    }

    @Override
    public void removeObserver(TransactionObserver observer) {
        observers.remove( observer );
    }

    /**
     * The delegate bridging between the local (application facing) transaction and the "physical" notion of a
     * transaction via the JDBC Connection.
     */
    public class TransactionDriverControlImpl implements TransactionDriver {
        private final JdbcResourceTransaction jdbcResourceTransaction;
        private boolean invalid;

        public TransactionDriverControlImpl(JdbcResourceTransaction jdbcResourceTransaction) {
            super();
            this.jdbcResourceTransaction = jdbcResourceTransaction;
        }

        protected void invalidate() {
            invalid = true;
        }

        @Override
        public void begin() {
            errorIfInvalid();

            jdbcResourceTransaction.begin();
            CockroachDBTransactionCoordinator.this.afterBeginCallback();
        }

        protected void errorIfInvalid() {
            if ( invalid ) {
                throw new IllegalStateException( "Physical-transaction delegate is no longer valid" );
            }
        }

        @Override
        public void commit() {
            try {
                CockroachDBTransactionCoordinator.this.beforeCompletionCallback();
                jdbcResourceTransaction.commit();
                CockroachDBTransactionCoordinator.this.afterCompletionCallback( true );
            }
            catch (RuntimeException e) {
                try {
                    rollback();
                }
                catch (RuntimeException e2) {
                    log.debug( "Encountered failure rolling back failed commit", e2 );;
                }
                throw e;
            }
        }

        @Override
        public void rollback() {
            if ( getStatus() == TransactionStatus.ACTIVE ) {
                jdbcResourceTransaction.rollback();
                CockroachDBTransactionCoordinator.this.afterCompletionCallback( false );
            }

            // no-op otherwise.
        }

        @Override
        public TransactionStatus getStatus() {
            return jdbcResourceTransaction.getStatus();
        }

        @Override
        public void markRollbackOnly() {
            // we don't want our transactions to become rollback only, because you could restore to a savepoint
        }
    }
}
