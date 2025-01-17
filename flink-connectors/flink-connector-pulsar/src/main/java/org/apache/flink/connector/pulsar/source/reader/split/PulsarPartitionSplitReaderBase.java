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

package org.apache.flink.connector.pulsar.source.reader.split;

import org.apache.flink.api.common.time.Deadline;
import org.apache.flink.connector.base.source.reader.RecordsBySplits;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.splitreader.SplitReader;
import org.apache.flink.connector.base.source.reader.splitreader.SplitsAddition;
import org.apache.flink.connector.base.source.reader.splitreader.SplitsChange;
import org.apache.flink.connector.pulsar.common.request.PulsarAdminRequest;
import org.apache.flink.connector.pulsar.source.config.SourceConfiguration;
import org.apache.flink.connector.pulsar.source.enumerator.cursor.StopCursor;
import org.apache.flink.connector.pulsar.source.enumerator.cursor.StopCursor.StopCondition;
import org.apache.flink.connector.pulsar.source.enumerator.topic.TopicPartition;
import org.apache.flink.connector.pulsar.source.split.PulsarPartitionSplit;
import org.apache.flink.util.Preconditions;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.CryptoKeyReader;
import org.apache.pulsar.client.api.KeySharedPolicy;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.apache.flink.connector.pulsar.common.utils.PulsarExceptionUtils.sneakyClient;
import static org.apache.flink.connector.pulsar.source.config.PulsarSourceConfigUtils.createConsumerBuilder;
import static org.apache.flink.connector.pulsar.source.enumerator.topic.range.RangeGenerator.KeySharedMode.JOIN;
import static org.apache.pulsar.client.api.KeySharedPolicy.stickyHashRange;

/** The common partition split reader. */
abstract class PulsarPartitionSplitReaderBase
        implements SplitReader<Message<byte[]>, PulsarPartitionSplit> {
    private static final Logger LOG = LoggerFactory.getLogger(PulsarPartitionSplitReaderBase.class);

    protected final PulsarClient pulsarClient;
    protected final PulsarAdminRequest adminRequest;
    protected final SourceConfiguration sourceConfiguration;
    protected final Schema<byte[]> schema;
    @Nullable protected final CryptoKeyReader cryptoKeyReader;

    protected Consumer<byte[]> pulsarConsumer;
    protected PulsarPartitionSplit registeredSplit;

    protected PulsarPartitionSplitReaderBase(
            PulsarClient pulsarClient,
            PulsarAdminRequest adminRequest,
            SourceConfiguration sourceConfiguration,
            Schema<byte[]> schema,
            @Nullable CryptoKeyReader cryptoKeyReader) {
        this.pulsarClient = pulsarClient;
        this.adminRequest = adminRequest;
        this.sourceConfiguration = sourceConfiguration;
        this.schema = schema;
        this.cryptoKeyReader = cryptoKeyReader;
    }

    @Override
    public RecordsWithSplitIds<Message<byte[]>> fetch() throws IOException {
        RecordsBySplits.Builder<Message<byte[]>> builder = new RecordsBySplits.Builder<>();

        // Return when no split registered to this reader.
        if (pulsarConsumer == null || registeredSplit == null) {
            return builder.build();
        }

        StopCursor stopCursor = registeredSplit.getStopCursor();
        String splitId = registeredSplit.splitId();
        Deadline deadline = Deadline.fromNow(sourceConfiguration.getMaxFetchTime());

        // Consume messages from pulsar until it was waked up by flink reader.
        for (int messageNum = 0;
                messageNum < sourceConfiguration.getMaxFetchRecords() && deadline.hasTimeLeft();
                messageNum++) {
            try {
                Message<byte[]> message = pollMessage(sourceConfiguration.getDefaultFetchTime());
                if (message == null) {
                    break;
                }

                StopCondition condition = stopCursor.shouldStop(message);

                if (condition == StopCondition.CONTINUE || condition == StopCondition.EXACTLY) {
                    // Deserialize message.
                    builder.add(splitId, message);

                    // Acknowledge message if needed.
                    finishedPollMessage(message);
                }

                if (condition == StopCondition.EXACTLY || condition == StopCondition.TERMINATE) {
                    builder.addFinishedSplit(splitId);
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                LOG.error("Error in polling message from pulsar consumer.", e);
                break;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        return builder.build();
    }

    @Override
    public void handleSplitsChanges(SplitsChange<PulsarPartitionSplit> splitsChanges) {
        LOG.debug("Handle split changes {}", splitsChanges);

        // Get all the partition assignments and stopping offsets.
        if (!(splitsChanges instanceof SplitsAddition)) {
            throw new UnsupportedOperationException(
                    String.format(
                            "The SplitChange type of %s is not supported.",
                            splitsChanges.getClass()));
        }

        if (registeredSplit != null) {
            throw new IllegalStateException("This split reader have assigned split.");
        }

        List<PulsarPartitionSplit> newSplits = splitsChanges.splits();
        Preconditions.checkArgument(
                newSplits.size() == 1, "This pulsar split reader only support one split.");
        this.registeredSplit = newSplits.get(0);

        // Open stop cursor.
        registeredSplit.open(adminRequest.pulsarAdmin());

        // Before creating the consumer.
        beforeCreatingConsumer(registeredSplit);

        // Create pulsar consumer.
        this.pulsarConsumer = createPulsarConsumer(registeredSplit);

        // After creating the consumer.
        afterCreatingConsumer(registeredSplit, pulsarConsumer);

        LOG.info("Register split {} consumer for current reader.", registeredSplit);
    }

    @Override
    public void wakeUp() {
        // Nothing to do on this method.
    }

    @Override
    public void close() {
        if (pulsarConsumer != null) {
            sneakyClient(() -> pulsarConsumer.close());
        }
    }

    @Nullable
    protected abstract Message<byte[]> pollMessage(Duration timeout)
            throws ExecutionException, InterruptedException, PulsarClientException;

    protected abstract void finishedPollMessage(Message<?> message);

    protected void beforeCreatingConsumer(PulsarPartitionSplit split) {
        // Nothing to do by default.
    }

    protected void afterCreatingConsumer(PulsarPartitionSplit split, Consumer<byte[]> consumer) {
        // Nothing to do by default.
    }

    // --------------------------- Helper Methods -----------------------------

    /** Create a specified {@link Consumer} by the given split information. */
    protected Consumer<byte[]> createPulsarConsumer(PulsarPartitionSplit split) {
        return createPulsarConsumer(split.getPartition());
    }

    /** Create a specified {@link Consumer} by the given topic partition. */
    protected Consumer<byte[]> createPulsarConsumer(TopicPartition partition) {
        ConsumerBuilder<byte[]> consumerBuilder =
                createConsumerBuilder(pulsarClient, schema, sourceConfiguration);

        consumerBuilder.topic(partition.getFullTopicName());

        // Add CryptoKeyReader if it exists for supporting end-to-end encryption.
        if (cryptoKeyReader != null) {
            consumerBuilder.cryptoKeyReader(cryptoKeyReader);
        }

        // Add KeySharedPolicy for Key_Shared subscription.
        if (sourceConfiguration.getSubscriptionType() == SubscriptionType.Key_Shared) {
            KeySharedPolicy policy = stickyHashRange().ranges(partition.getPulsarRanges());
            // We may enable out of order delivery for speeding up. It was turned off by default.
            policy.setAllowOutOfOrderDelivery(
                    sourceConfiguration.isAllowKeySharedOutOfOrderDelivery());
            consumerBuilder.keySharedPolicy(policy);

            if (partition.getMode() == JOIN) {
                // Override the key shared subscription into exclusive for making it behaviors like
                // a Pulsar Reader which supports partial key hash ranges.
                consumerBuilder.subscriptionType(SubscriptionType.Exclusive);
            }
        }

        // Create the consumer configuration by using common utils.
        return sneakyClient(consumerBuilder::subscribe);
    }
}
