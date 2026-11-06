package com.taca.cart.config;

import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

import java.util.Objects;

public class RetryingMongoTransactionManager extends MongoTransactionManager {
    private final MongoCommitRetryExecutor commitRetryExecutor;

    public RetryingMongoTransactionManager(
            MongoDatabaseFactory databaseFactory,
            MongoCommitRetryExecutor commitRetryExecutor) {
        super(databaseFactory, TransactionOptions.builder()
                .readConcern(ReadConcern.SNAPSHOT)
                .writeConcern(WriteConcern.MAJORITY)
                .build());
        this.commitRetryExecutor = Objects.requireNonNull(commitRetryExecutor);
    }

    @Override
    protected void doCommit(MongoTransactionObject transactionObject) {
        commitRetryExecutor.execute(transactionObject::commitTransaction);
    }
}
