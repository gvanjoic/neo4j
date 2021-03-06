/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.helpers.Clock;
import org.neo4j.helpers.ThisShouldNotHappenError;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.TxState;
import org.neo4j.kernel.api.constraints.UniquenessConstraint;
import org.neo4j.kernel.api.exceptions.InvalidTransactionTypeKernelException;
import org.neo4j.kernel.api.exceptions.ReadOnlyDatabaseKernelException;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.api.exceptions.schema.DropIndexFailureException;
import org.neo4j.kernel.api.exceptions.schema.SchemaRuleNotFoundException;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.api.labelscan.LabelScanStore;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.api.index.SchemaIndexProviderMap;
import org.neo4j.kernel.impl.api.state.ConstraintIndexCreator;
import org.neo4j.kernel.impl.api.state.LegacyIndexTransactionState;
import org.neo4j.kernel.impl.api.state.TxStateImpl;
import org.neo4j.kernel.impl.api.store.PersistenceCache;
import org.neo4j.kernel.impl.api.store.StoreReadLayer;
import org.neo4j.kernel.impl.locking.LockGroup;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.nioneo.store.IndexRule;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.nioneo.store.SchemaStorage;
import org.neo4j.kernel.impl.nioneo.store.UniquenessConstraintRule;
import org.neo4j.kernel.impl.nioneo.xa.TransactionRecordState;
import org.neo4j.kernel.impl.nioneo.xa.command.Command;
import org.neo4j.kernel.impl.transaction.xaframework.PhysicalTransactionRepresentation;
import org.neo4j.kernel.impl.transaction.xaframework.TransactionMonitor;

/**
 * This class should replace the {@link org.neo4j.kernel.api.KernelTransaction} interface, and take its name, as soon as
 * {@code TransitionalTxManagementKernelTransaction} is gone from {@code server}.
 */
public class KernelTransactionImplementation implements KernelTransaction, TxState.Holder
{
    // Logic
    private final SchemaWriteGuard schemaWriteGuard;
    private final IndexingService indexService;
    private final TransactionHooks hooks;
    private final LabelScanStore labelScanStore;
    private final SchemaStorage schemaStorage;
    private final ConstraintIndexCreator constraintIndexCreator;
    private final SchemaIndexProviderMap providerMap;
    private final UpdateableSchemaState schemaState;
    private final StatementOperationParts operations;
    private final boolean readOnly;
    private final Locks.Client locks;

    // State
    private final TransactionRecordState recordState;
    private TxStateImpl txState;
    private TransactionType transactionType = TransactionType.ANY;
    private TransactionHooks.TransactionHooksState hooksState;
    private boolean closing, closed;
    private boolean failure, success;
    private volatile boolean terminated;

    // For committing
    private TransactionHeaderInformation headerInformation;
    private final TransactionCommitProcess commitProcess;
    private final TransactionMonitor transactionMonitor;
    private final PersistenceCache persistenceCache;
    private final StoreReadLayer storeLayer;
    private final LegacyIndexTransactionState legacyIndexTransactionState;
    private final Clock clock;

    // Some header information
    private long startTimeMillis;
    private long lastTransactionIdWhenStarted;

    public KernelTransactionImplementation( StatementOperationParts operations, boolean readOnly,
                                            SchemaWriteGuard schemaWriteGuard, LabelScanStore labelScanStore,
                                            IndexingService indexService,
                                            UpdateableSchemaState schemaState,
                                            TransactionRecordState neoStoreTransaction,
                                            SchemaIndexProviderMap providerMap, NeoStore neoStore,
                                            Locks.Client locks, TransactionHooks hooks,
                                            ConstraintIndexCreator constraintIndexCreator,
                                            TransactionHeaderInformation transactionHeaderInformation,
                                            TransactionCommitProcess commitProcess,
                                            TransactionMonitor transactionMonitor,
                                            PersistenceCache persistenceCache,
                                            StoreReadLayer storeLayer,
                                            LegacyIndexTransactionState legacyIndexTransaction,
                                            Clock clock )
    {
        this.operations = operations;
        this.readOnly = readOnly;
        this.schemaWriteGuard = schemaWriteGuard;
        this.labelScanStore = labelScanStore;
        this.indexService = indexService;
        this.recordState = neoStoreTransaction;
        this.providerMap = providerMap;
        this.schemaState = schemaState;
        this.hooks = hooks;
        this.locks = locks;
        this.constraintIndexCreator = constraintIndexCreator;
        this.headerInformation = transactionHeaderInformation;
        this.commitProcess = commitProcess;
        this.transactionMonitor = transactionMonitor;
        this.persistenceCache = persistenceCache;
        this.storeLayer = storeLayer;
        this.legacyIndexTransactionState = legacyIndexTransaction;
        this.clock = clock;
        this.schemaStorage = new SchemaStorage( neoStore.getSchemaStore() );
    }

    /** Reset this transaction to a vanilla state, turning it into a logically new transaction. */
    public KernelTransactionImplementation initialize( TransactionHeaderInformation txHeader, long lastCommittedTx )
    {
        this.headerInformation = txHeader;
        this.terminated = closing = closed = failure = success = false;
        this.transactionType = TransactionType.ANY;
        this.hooksState = null;
        this.txState = null; // TODO: Implement txState.clear() instead, to re-use data structures
        this.legacyIndexTransactionState.initialize();
        this.recordState.initialize( lastCommittedTx );
        this.startTimeMillis = clock.currentTimeMillis();
        this.lastTransactionIdWhenStarted = lastCommittedTx;
        return this;
    }

    @Override
    public void success()
    {
        this.success = true;
    }

    @Override
    public void failure()
    {
        failure = true;
    }

    @Override
    public boolean shouldBeTerminated()
    {
        return terminated;
    }

    @Override
    public void markForTermination()
    {
        if ( !terminated )
        {
            failure = true;
            terminated = true;
            transactionMonitor.transactionTerminated();
        }
    }

    /** Implements reusing the same underlying {@link KernelStatement} for overlapping statements. */
    private KernelStatement currentStatement;

    @Override
    public TransactionRecordState getTransactionRecordState()
    {
        return recordState;
    }

    @Override
    public LegacyIndexTransactionState getLegacyIndexTransactionState()
    {
        return legacyIndexTransactionState;
    }

    @Override
    public boolean isOpen()
    {
        return !closed && !closing;
    }

    @Override
    public KernelStatement acquireStatement()
    {
        assertTransactionOpen();
        if ( currentStatement == null )
        {
            currentStatement = new KernelStatement( this, new IndexReaderFactory.Caching( indexService ),
                    labelScanStore, this, locks, operations, legacyIndexTransactionState );
        }
        currentStatement.acquire();
        return currentStatement;
    }

    public void releaseStatement( Statement statement )
    {
        assert currentStatement == statement;
        currentStatement = null;
    }

    public void upgradeToDataTransaction() throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException
    {
        assertDatabaseWritable();
        transactionType = transactionType.upgradeToDataTransaction();
    }

    public void upgradeToSchemaTransaction() throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException
    {
        doUpgradeToSchemaTransaction();
        transactionType = transactionType.upgradeToSchemaTransaction();
    }

    public void doUpgradeToSchemaTransaction() throws InvalidTransactionTypeKernelException, ReadOnlyDatabaseKernelException
    {
        assertDatabaseWritable();
        schemaWriteGuard.assertSchemaWritesAllowed();
    }

    private void assertDatabaseWritable() throws ReadOnlyDatabaseKernelException
    {
        if ( readOnly )
        {
            throw new ReadOnlyDatabaseKernelException();
        }
    }

    public void assertTokenWriteAllowed() throws ReadOnlyDatabaseKernelException
    {
        assertDatabaseWritable();
    }

    private void dropCreatedConstraintIndexes() throws TransactionFailureException
    {
        if ( hasTxStateWithChanges() )
        {
            for ( IndexDescriptor createdConstraintIndex : txState().constraintIndexesCreatedInTx() )
            {
                try
                {
                    // TODO logically, which statement should this operation be performed on?
                    constraintIndexCreator.dropUniquenessConstraintIndex( createdConstraintIndex );
                }
                catch ( DropIndexFailureException e )
                {
                    throw new IllegalStateException( "Constraint index that was created in a transaction should be " +
                            "possible to drop during rollback of that transaction.", e );
                }
                catch ( TransactionFailureException e )
                {
                    throw e;
                }
            }
        }
    }

    @Override
    public TxState txState()
    {
        if ( !hasTxState() )
        {
            txState = new TxStateImpl( legacyIndexTransactionState );
        }
        return txState;
    }

    @Override
    public boolean hasTxState()
    {
        return null != txState;
    }

    @Override
    public boolean hasTxStateWithChanges()
    {
        return hasTxState() && txState.hasChanges();
    }

    private void closeTransaction()
    {
        assertTransactionOpen();
        closed = true;
        if ( currentStatement != null )
        {
            currentStatement.forceClose();
            currentStatement = null;
        }
    }

    private void closeCurrentStatementIfAny()
    {
        if ( currentStatement != null )
        {
            currentStatement.forceClose();
            currentStatement = null;
        }
    }

    private void assertTransactionNotClosing()
    {
        if ( closing )
        {
            throw new IllegalStateException( "This transaction is already being closed." );
        }
    }

    private void prepareRecordChangesFromTransactionState()
    {
        if ( hasTxStateWithChanges() )
        {
            final AtomicBoolean clearState = new AtomicBoolean( false );
            txState().accept( new TxState.VisitorAdapter()
            {
                @Override
                public void visitCreatedNode( long id )
                {
                    recordState.nodeCreate( id );
                }

                @Override
                public void visitDeletedNode( long id )
                {
                    recordState.nodeDelete( id );
                }

                @Override
                public void visitCreatedRelationship( long id, int type, long startNode, long endNode )
                {
                    recordState.relCreate( id, type, startNode, endNode );
                }

                @Override
                public void visitDeletedRelationship( long id )
                {
                    recordState.relDelete( id );
                }

                @Override
                public void visitNodePropertyChanges( long id, Iterator<DefinedProperty> added, Iterator<DefinedProperty> changed, Iterator<Integer> removed )
                {
                    while(removed.hasNext())
                    {
                        recordState.nodeRemoveProperty( id, removed.next() );
                    }
                    while(changed.hasNext())
                    {
                        DefinedProperty prop = changed.next();
                        recordState.nodeChangeProperty( id, prop.propertyKeyId(), prop.value() );
                    }
                    while(added.hasNext())
                    {
                        DefinedProperty prop = added.next();
                        recordState.nodeAddProperty( id, prop.propertyKeyId(), prop.value() );
                    }
                }

                @Override
                public void visitRelPropertyChanges( long id, Iterator<DefinedProperty> added, Iterator<DefinedProperty> changed, Iterator<Integer> removed )
                {
                    while(removed.hasNext())
                    {
                        recordState.relRemoveProperty( id, removed.next() );
                    }
                    while(changed.hasNext())
                    {
                        DefinedProperty prop = changed.next();
                        recordState.relChangeProperty( id, prop.propertyKeyId(), prop.value() );
                    }
                    while(added.hasNext())
                    {
                        DefinedProperty prop = added.next();
                        recordState.relAddProperty( id, prop.propertyKeyId(), prop.value() );
                    }
                }

                @Override
                public void visitGraphPropertyChanges( Iterator<DefinedProperty> added, Iterator<DefinedProperty> changed, Iterator<Integer> removed )
                {
                    while(removed.hasNext())
                    {
                        recordState.graphRemoveProperty( removed.next() );
                    }
                    while(changed.hasNext())
                    {
                        DefinedProperty prop = changed.next();
                        recordState.graphChangeProperty( prop.propertyKeyId(), prop.value() );
                    }
                    while(added.hasNext())
                    {
                        DefinedProperty prop = added.next();
                        recordState.graphAddProperty( prop.propertyKeyId(), prop.value() );
                    }
                }

                @Override
                public void visitNodeLabelChanges( long id, Iterator<Integer> added, Iterator<Integer> removed )
                {
                    while(removed.hasNext())
                    {
                        recordState.removeLabelFromNode( removed.next(), id );
                    }

                    while(added.hasNext())
                    {
                        recordState.addLabelToNode( added.next(), id );
                    }
                }

                @Override
                public void visitAddedIndex( IndexDescriptor element, boolean isConstraintIndex )
                {
                    SchemaIndexProvider.Descriptor providerDescriptor = providerMap.getDefaultProvider()
                            .getProviderDescriptor();
                    IndexRule rule;
                    if ( isConstraintIndex )
                    {
                        rule = IndexRule.constraintIndexRule( schemaStorage.newRuleId(), element.getLabelId(),
                                element.getPropertyKeyId(), providerDescriptor,
                                null );
                    }
                    else
                    {
                        rule = IndexRule.indexRule( schemaStorage.newRuleId(), element.getLabelId(),
                                element.getPropertyKeyId(), providerDescriptor );
                    }
                    recordState.createSchemaRule( rule );
                }

                @Override
                public void visitRemovedIndex( IndexDescriptor element, boolean isConstraintIndex )
                {
                    try
                    {
                        SchemaStorage.IndexRuleKind kind = isConstraintIndex?
                                SchemaStorage.IndexRuleKind.CONSTRAINT : SchemaStorage.IndexRuleKind.INDEX;
                        IndexRule rule = schemaStorage.indexRule( element.getLabelId(), element.getPropertyKeyId(), kind );
                        recordState.dropSchemaRule( rule );
                    }
                    catch ( SchemaRuleNotFoundException e )
                    {
                        throw new ThisShouldNotHappenError(
                                "Tobias Lindaaker",
                                "Index to be removed should exist, since its existence should have " +
                                        "been validated earlier and the schema should have been locked.", e );
                    }
                }

                @Override
                public void visitAddedConstraint( UniquenessConstraint element )
                {
                    clearState.set( true );
                    long constraintId = schemaStorage.newRuleId();
                    IndexRule indexRule;
                    try
                    {
                        indexRule = schemaStorage.indexRule(
                                element.label(),
                                element.propertyKeyId(),
                                SchemaStorage.IndexRuleKind.CONSTRAINT );
                    }
                    catch ( SchemaRuleNotFoundException e )
                    {
                        throw new ThisShouldNotHappenError(
                                "Jacob Hansson",
                                "Index is always created for the constraint before this point.", e );
                    }
                    recordState.createSchemaRule( UniquenessConstraintRule.uniquenessConstraintRule(
                            constraintId, element.label(), element.propertyKeyId(), indexRule.getId() ) );
                    recordState.setConstraintIndexOwner( indexRule, constraintId );
                }

                @Override
                public void visitRemovedConstraint( UniquenessConstraint element )
                {
                    try
                    {
                        clearState.set( true );
                        UniquenessConstraintRule rule = schemaStorage
                                .uniquenessConstraint( element.label(), element.propertyKeyId() );
                        recordState.dropSchemaRule( rule );
                    }
                    catch ( SchemaRuleNotFoundException e )
                    {
                        throw new ThisShouldNotHappenError(
                                "Tobias Lindaaker",
                                "Constraint to be removed should exist, since its existence should " +
                                        "have been validated earlier and the schema should have been locked." );
                    }
                    // Remove the index for the constraint as well
                    visitRemovedIndex( new IndexDescriptor( element.label(), element.propertyKeyId() ), true );
                }
            } );
            if ( clearState.get() )
            {
                schemaState.clear();
            }
        }
    }

    private void assertTransactionOpen()
    {
        if ( closed )
        {
            throw new IllegalStateException( "This transaction has already been completed." );
        }
    }

    public boolean isReadOnly()
    {
        return !hasTxStateWithChanges() && recordState.isReadOnly() &&
                legacyIndexTransactionState.isReadOnly();
    }

    private enum TransactionType
    {
        ANY,
        DATA
                {
                    @Override
                    TransactionType upgradeToSchemaTransaction() throws InvalidTransactionTypeKernelException
                    {
                        throw new InvalidTransactionTypeKernelException(
                                "Cannot perform schema updates in a transaction that has performed data updates." );
                    }
                },
        SCHEMA
                {
                    @Override
                    TransactionType upgradeToDataTransaction() throws InvalidTransactionTypeKernelException
                    {
                        throw new InvalidTransactionTypeKernelException(
                                "Cannot perform data updates in a transaction that has performed schema updates." );
                    }
                };

        TransactionType upgradeToDataTransaction() throws InvalidTransactionTypeKernelException
        {
            return DATA;
        }

        TransactionType upgradeToSchemaTransaction() throws InvalidTransactionTypeKernelException
        {
            return SCHEMA;
        }
    }

    @Override
    public void close() throws TransactionFailureException
    {
        assertTransactionOpen();
        assertTransactionNotClosing();
        closeCurrentStatementIfAny();
        closing = true;
        try
        {
            if ( failure || !success )
            {
                rollback();
                if ( success )
                {
                    // Success was called, but also failure which means that the client code using this
                    // transaction passed through a happy path, but the transaction was still marked as
                    // failed for one or more reasons. Tell the user that although it looked happy it
                    // wasn't committed, but was instead rolled back.
                    throw new TransactionFailureException( Status.Transaction.MarkedAsFailed,
                            "Transaction rolled back even if marked as successful" );
                }
            }
            else
            {
                commit();
            }
        }
        finally
        {
            closed = true;
            closing = false;
            dispose();
        }
    }

    protected void dispose()
    {
    }

    private void commit() throws TransactionFailureException
    {
        boolean success = false;
        try ( LockGroup locks = new LockGroup() )
        {
            // Trigger transaction "before" hooks
            if ( (hooksState = hooks.beforeCommit( txState, this, storeLayer )) != null && hooksState.failed() )
            {
                throw new TransactionFailureException( Status.Transaction.HookFailed, hooksState.failure(), "" );
            }

            prepareRecordChangesFromTransactionState();

            // Convert changes into commands and commit
            if ( !isReadOnly() )
            {
                // Gather up commands from the various sources
                List<Command> commands = new ArrayList<>();
                recordState.extractCommands( commands );
                legacyIndexTransactionState.extractCommands( commands );

                /* Here's the deal: we track a quick-to-access hasChanges in transaction state which is true
                 * if there are any changes imposed by this transaction. Some changes made inside a transaction undo
                 * previously made changes in that same transaction, and so at some point a transaction may have
                 * changes and at another point, after more changes seemingly, the transaction may not have any changes.
                 * However, to track that "undoing" of the changes is a bit tedious, intrusive and hard to maintain
                 * and get right.... So to really make sure the transaction has changes we re-check by looking if we
                 * have produced any commands to add to the logical log.
                 */
                if ( !commands.isEmpty() )
                {
                    // Finish up the whole transaction representation
                    PhysicalTransactionRepresentation transactionRepresentation =
                            new PhysicalTransactionRepresentation( commands );
                    transactionRepresentation.setHeader( headerInformation.getAdditionalHeader(),
                            headerInformation.getMasterId(),
                            headerInformation.getAuthorId(),
                            startTimeMillis, lastTransactionIdWhenStarted, clock.currentTimeMillis() );

                    // Commit the transaction
                    commitProcess.commit( transactionRepresentation, locks );
                }
            }

            if ( hasTxStateWithChanges() )
            {
                persistenceCache.apply( txState );
            }
            success = true;
        }
        finally
        {
            if ( !success )
            {
                rollback();
            }
            else
            {
                afterCommit();
            }
        }
    }

    private void rollback() throws TransactionFailureException
    {
        try
        {
            try
            {
                dropCreatedConstraintIndexes();
            }
            catch ( IllegalStateException | SecurityException e )
            {
                throw new TransactionFailureException( Status.Transaction.CouldNotRollback, e,
                        "Could not drop created constraint indexes" );
            }

            if ( hasTxStateWithChanges() )
            {
                persistenceCache.invalidate( txState );
            }
        }
        finally
        {
            afterRollback();
        }
    }

    private void afterCommit()
    {
        try
        {
            release();
            closeTransaction();
            hooks.afterCommit( txState, this, hooksState );
        }
        finally
        {
            transactionMonitor.transactionFinished( true );
        }
    }

    private void afterRollback()
    {
        try
        {
            release();
            closeTransaction();
            hooks.afterRollback( txState, this, hooksState );
        }
        finally
        {
            transactionMonitor.transactionFinished( false );
        }
    }

    /** Release resources held up by this transaction & return it to the transaction pool. */
    private void release()
    {
        locks.close();
    }
}
