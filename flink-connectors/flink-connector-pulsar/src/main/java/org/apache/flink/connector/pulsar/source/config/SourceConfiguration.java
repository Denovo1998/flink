/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.pulsar.source.config;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.pulsar.common.config.PulsarConfiguration;
import org.apache.flink.connector.pulsar.sink.writer.serializer.PulsarSchemaWrapper;
import org.apache.flink.connector.pulsar.source.enumerator.cursor.CursorPosition;
import org.apache.flink.connector.pulsar.source.enumerator.cursor.StartCursor;

import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionMode;
import org.apache.pulsar.client.api.SubscriptionType;

import java.time.Duration;
import java.util.Objects;

import static org.apache.flink.connector.base.source.reader.SourceReaderOptions.ELEMENT_QUEUE_CAPACITY;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_ALLOW_KEY_SHARED_OUT_OF_ORDER_DELIVERY;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_AUTO_COMMIT_CURSOR_INTERVAL;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_DEFAULT_FETCH_TIME;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_ENABLE_AUTO_ACKNOWLEDGE_MESSAGE;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_MAX_FETCH_RECORDS;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_MAX_FETCH_TIME;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_PARTITION_DISCOVERY_INTERVAL_MS;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_READ_SCHEMA_EVOLUTION;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_READ_TRANSACTION_TIMEOUT;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_SUBSCRIPTION_MODE;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_SUBSCRIPTION_NAME;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_SUBSCRIPTION_TYPE;
import static org.apache.flink.connector.pulsar.source.PulsarSourceOptions.PULSAR_VERIFY_INITIAL_OFFSETS;

/** The configuration class for pulsar source. */
@PublicEvolving
public class SourceConfiguration extends PulsarConfiguration {
    private static final long serialVersionUID = 8488507275800787580L;

    private final int messageQueueCapacity;
    private final long partitionDiscoveryIntervalMs;
    private final boolean enableAutoAcknowledgeMessage;
    private final boolean enableSchemaEvolution;
    private final long autoCommitCursorInterval;
    private final long transactionTimeoutMillis;
    private final Duration defaultFetchTime;
    private final Duration maxFetchTime;
    private final int maxFetchRecords;
    private final CursorVerification verifyInitialOffsets;
    private final String subscriptionName;
    private final SubscriptionType subscriptionType;
    private final SubscriptionMode subscriptionMode;
    private final boolean allowKeySharedOutOfOrderDelivery;

    public SourceConfiguration(Configuration configuration) {
        super(configuration);

        this.messageQueueCapacity = getInteger(ELEMENT_QUEUE_CAPACITY);
        this.partitionDiscoveryIntervalMs = get(PULSAR_PARTITION_DISCOVERY_INTERVAL_MS);
        this.enableAutoAcknowledgeMessage = get(PULSAR_ENABLE_AUTO_ACKNOWLEDGE_MESSAGE);
        this.enableSchemaEvolution = get(PULSAR_READ_SCHEMA_EVOLUTION);
        this.autoCommitCursorInterval = get(PULSAR_AUTO_COMMIT_CURSOR_INTERVAL);
        this.transactionTimeoutMillis = get(PULSAR_READ_TRANSACTION_TIMEOUT);
        this.defaultFetchTime = get(PULSAR_DEFAULT_FETCH_TIME, Duration::ofMillis);
        this.maxFetchTime = get(PULSAR_MAX_FETCH_TIME, Duration::ofMillis);
        this.maxFetchRecords = get(PULSAR_MAX_FETCH_RECORDS);
        this.verifyInitialOffsets = get(PULSAR_VERIFY_INITIAL_OFFSETS);
        this.subscriptionName = get(PULSAR_SUBSCRIPTION_NAME);
        this.subscriptionType = get(PULSAR_SUBSCRIPTION_TYPE);
        this.subscriptionMode = get(PULSAR_SUBSCRIPTION_MODE);
        this.allowKeySharedOutOfOrderDelivery = get(PULSAR_ALLOW_KEY_SHARED_OUT_OF_ORDER_DELIVERY);
    }

    /** The capacity of the element queue in the source reader. */
    public int getMessageQueueCapacity() {
        return messageQueueCapacity;
    }

    /**
     * We would override the interval into a negative number when we set the connector with bounded
     * stop cursor.
     */
    public boolean isEnablePartitionDiscovery() {
        return getPartitionDiscoveryIntervalMs() > 0;
    }

    /** The interval in millis for flink querying topic partition information. */
    public long getPartitionDiscoveryIntervalMs() {
        return partitionDiscoveryIntervalMs;
    }

    /**
     * This is used for all subscription type. But the behavior may not be the same among them. If
     * you don't enable the flink checkpoint, make sure this option is set to true.
     *
     * <ul>
     *   <li>{@link SubscriptionType#Shared} and {@link SubscriptionType#Key_Shared} would
     *       immediately acknowledge the message after consuming it.
     *   <li>{@link SubscriptionType#Failover} and {@link SubscriptionType#Exclusive} would perform
     *       a incremental acknowledge in a fixed {@link #getAutoCommitCursorInterval}.
     * </ul>
     */
    public boolean isEnableAutoAcknowledgeMessage() {
        return enableAutoAcknowledgeMessage;
    }

    /**
     * If we should deserialize the message with a specified Pulsar {@link Schema} instead the
     * default {@link Schema#BYTES}. This switch is only used for {@link PulsarSchemaWrapper}.
     */
    public boolean isEnableSchemaEvolution() {
        return enableSchemaEvolution;
    }

    /**
     * The interval in millis for acknowledge message when you enable {@link
     * #isEnableAutoAcknowledgeMessage} and use {@link SubscriptionType#Failover} or {@link
     * SubscriptionType#Exclusive} as your consuming subscription type.
     */
    public long getAutoCommitCursorInterval() {
        return autoCommitCursorInterval;
    }

    /**
     * Pulsar's transaction have a timeout mechanism for uncommitted transaction. We use transaction
     * for {@link SubscriptionType#Shared} and {@link SubscriptionType#Key_Shared} when user disable
     * {@link #isEnableAutoAcknowledgeMessage} and enable flink checkpoint. Since the checkpoint
     * interval couldn't be acquired from {@link SourceReaderContext#getConfiguration()}, we have to
     * expose this option. Make sure this value is greater than the checkpoint interval.
     */
    public long getTransactionTimeoutMillis() {
        return transactionTimeoutMillis;
    }

    /**
     * The fetch time for polling one message. We would stop polling message and return the message
     * in {@link RecordsWithSplitIds} when timeout and no message consumed.
     */
    public Duration getDefaultFetchTime() {
        return defaultFetchTime;
    }

    /**
     * The fetch time for flink split reader polling message. We would stop polling message and
     * return the message in {@link RecordsWithSplitIds} when timeout or exceed the {@link
     * #getMaxFetchRecords}.
     */
    public Duration getMaxFetchTime() {
        return maxFetchTime;
    }

    /**
     * The fetch counts for a split reader. We would stop polling message and return the message in
     * {@link RecordsWithSplitIds} when timeout {@link #getMaxFetchTime} or exceed this value.
     */
    public int getMaxFetchRecords() {
        return maxFetchRecords;
    }

    /** Validate the {@link CursorPosition} generated by {@link StartCursor}. */
    public CursorVerification getVerifyInitialOffsets() {
        return verifyInitialOffsets;
    }

    /**
     * The pulsar's subscription name for this flink source. All the readers would share this
     * subscription name.
     *
     * @see ConsumerBuilder#subscriptionName
     */
    public String getSubscriptionName() {
        return subscriptionName;
    }

    /**
     * The pulsar's subscription type for this flink source. All the readers would share this
     * subscription type.
     *
     * @see SubscriptionType
     */
    public SubscriptionType getSubscriptionType() {
        return subscriptionType;
    }

    /**
     * The pulsar's subscription mode for this flink source. All the readers would share this
     * subscription mode.
     *
     * @see SubscriptionMode
     */
    public SubscriptionMode getSubscriptionMode() {
        return subscriptionMode;
    }

    /** Whether to enable the out-of-order delivery in Key Shared subscription. */
    public boolean isAllowKeySharedOutOfOrderDelivery() {
        return allowKeySharedOutOfOrderDelivery;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        SourceConfiguration that = (SourceConfiguration) o;
        return partitionDiscoveryIntervalMs == that.partitionDiscoveryIntervalMs
                && enableAutoAcknowledgeMessage == that.enableAutoAcknowledgeMessage
                && autoCommitCursorInterval == that.autoCommitCursorInterval
                && transactionTimeoutMillis == that.transactionTimeoutMillis
                && Objects.equals(defaultFetchTime, that.defaultFetchTime)
                && Objects.equals(maxFetchTime, that.maxFetchTime)
                && maxFetchRecords == that.maxFetchRecords
                && verifyInitialOffsets == that.verifyInitialOffsets
                && Objects.equals(subscriptionName, that.subscriptionName)
                && subscriptionType == that.subscriptionType
                && subscriptionMode == that.subscriptionMode
                && allowKeySharedOutOfOrderDelivery == that.allowKeySharedOutOfOrderDelivery;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                super.hashCode(),
                partitionDiscoveryIntervalMs,
                enableAutoAcknowledgeMessage,
                autoCommitCursorInterval,
                transactionTimeoutMillis,
                defaultFetchTime,
                maxFetchTime,
                maxFetchRecords,
                verifyInitialOffsets,
                subscriptionName,
                subscriptionType,
                subscriptionMode,
                allowKeySharedOutOfOrderDelivery);
    }
}
