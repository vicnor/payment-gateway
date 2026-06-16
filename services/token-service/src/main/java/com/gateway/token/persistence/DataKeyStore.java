package com.gateway.token.persistence;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

/** Thin persistence adapter for the {@code data_keys} DynamoDB table. */
@Component
public class DataKeyStore {

    private final DynamoDbTable<DataKeyItem> table;

    public DataKeyStore(DynamoDbTable<DataKeyItem> dataKeysTable) {
        this.table = dataKeysTable;
    }

    public void save(DataKeyItem item) {
        table.putItem(item);
    }

    public DataKeyItem findById(String dataKeyId) {
        return table.getItem(Key.builder().partitionValue(dataKeyId).build());
    }
}
