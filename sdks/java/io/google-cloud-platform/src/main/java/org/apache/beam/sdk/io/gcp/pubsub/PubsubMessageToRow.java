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
package org.apache.beam.sdk.io.gcp.pubsub;

import static java.util.stream.Collectors.toList;
import static org.apache.beam.sdk.util.RowJsonUtils.newObjectMapperWith;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auto.value.AutoValue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.avro.AvroRuntimeException;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.coders.RowCoder;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.Schema.TypeName;
import org.apache.beam.sdk.schemas.utils.AvroUtils;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.util.RowJson.RowJsonDeserializer;
import org.apache.beam.sdk.util.RowJson.UnsupportedRowJsonException;
import org.apache.beam.sdk.util.RowJsonUtils;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Instant;

/** Read side converter for {@link PubsubMessage} with JSON/AVRO payload. */
@Internal
@Experimental
@AutoValue
abstract class PubsubMessageToRow extends PTransform<PCollection<PubsubMessage>, PCollectionTuple>
    implements Serializable {
  static final String TIMESTAMP_FIELD = "event_timestamp";
  static final String ATTRIBUTES_FIELD = "attributes";
  static final String PAYLOAD_FIELD = "payload";
  static final TupleTag<PubsubMessage> DLQ_TAG = new TupleTag<PubsubMessage>() {};
  static final TupleTag<Row> MAIN_TAG = new TupleTag<Row>() {};

  /**
   * Schema of the Pubsub message.
   *
   * <p>Required to have at least 'event_timestamp' field of type {@link Schema.FieldType#DATETIME}.
   *
   * <p>If {@code useFlatSchema()} is set every other field is assumed to be part of the payload.
   * Otherwise, the schema must contain exactly:
   *
   * <ul>
   *   <li>'attributes' of type {@link TypeName#MAP MAP&lt;VARCHAR,VARCHAR&gt;}
   *   <li>'payload' of type {@link TypeName#ROW ROW&lt;...&gt;}
   * </ul>
   *
   * <p>Only UTF-8 JSON and AVRO objects are supported.
   */
  public abstract Schema messageSchema();

  public abstract boolean useDlq();

  public abstract boolean useFlatSchema();

  public abstract PayloadFormat payloadFormat();

  public static Builder builder() {
    return new AutoValue_PubsubMessageToRow.Builder().payloadFormat(PayloadFormat.JSON);
  }

  @Override
  public PCollectionTuple expand(PCollection<PubsubMessage> input) {
    PCollectionTuple rows =
        input.apply(
            ParDo.of(
                    useFlatSchema()
                        ? new FlatSchemaPubsubMessageToRow(
                            messageSchema(), useDlq(), payloadFormat())
                        : new NestedSchemaPubsubMessageToRow(
                            messageSchema(), useDlq(), payloadFormat()))
                .withOutputTags(
                    MAIN_TAG, useDlq() ? TupleTagList.of(DLQ_TAG) : TupleTagList.empty()));
    rows.get(MAIN_TAG).setRowSchema(messageSchema());
    return rows;
  }

  static Row parsePayload(
      byte[] payload,
      Schema payloadSchema,
      PayloadFormat payloadFormat,
      @Nullable ObjectMapper objectMapper) {
    switch (payloadFormat) {
      case JSON:
        return parseJsonPayload(payload, payloadSchema, objectMapper);
      case AVRO:
        return AvroUtils.avroBytesToRow(payload, payloadSchema);
      case PROTO:
        return parseProtoPayload(payload, payloadSchema);
      default:
        throw new IllegalArgumentException("Unsupported payload format given: " + payloadFormat);
    }
  }

  private static Row parseProtoPayload(byte[] payload, Schema payloadSchema) {
    try {
      InputStream inputStream = new ByteArrayInputStream(payload);
      RowCoder rowCoder = RowCoder.of(payloadSchema);
      return rowCoder.decode(inputStream);
    } catch (IOException e) {
      throw new IllegalArgumentException("Could not decode row from proto payload.", e);
    }
  }

  private static Row parseJsonPayload(
      byte[] payload, Schema payloadSchema, ObjectMapper objectMapper) {
    String payloadJson = new String(payload, StandardCharsets.UTF_8);
    if (objectMapper == null) {
      objectMapper = newObjectMapperWith(RowJsonDeserializer.forSchema(payloadSchema));
    }
    return RowJsonUtils.jsonToRow(objectMapper, payloadJson);
  }

  /**
   * A {@link DoFn} to convert a flat schema{@link PubsubMessage} with JSON/AVRO payload to {@link
   * Row}.
   */
  @Internal
  private static class FlatSchemaPubsubMessageToRow extends DoFn<PubsubMessage, Row> {

    private final Schema messageSchema;

    private final Schema payloadSchema;

    private final boolean useDlq;

    private final PayloadFormat payloadFormat;

    private transient volatile @Nullable ObjectMapper objectMapper;

    protected FlatSchemaPubsubMessageToRow(
        Schema messageSchema, boolean useDlq, PayloadFormat payloadFormat) {
      this.messageSchema = messageSchema;
      // Construct flat payload schema.
      this.payloadSchema =
          new Schema(
              messageSchema.getFields().stream()
                  .filter(f -> !f.getName().equals(TIMESTAMP_FIELD))
                  .collect(Collectors.toList()));
      this.useDlq = useDlq;
      this.payloadFormat = payloadFormat;
    }

    /**
     * Get the value for a field from a given payload in the order they're specified in the flat
     * schema.
     */
    private Object getValueForFieldFlatSchema(Schema.Field field, Instant timestamp, Row payload) {
      String fieldName = field.getName();
      if (TIMESTAMP_FIELD.equals(fieldName)) {
        return timestamp;
      } else {
        return payload.getValue(fieldName);
      }
    }

    @ProcessElement
    public void processElement(
        @Element PubsubMessage element, @Timestamp Instant timestamp, MultiOutputReceiver o) {
      try {
        Row payload =
            PubsubMessageToRow.parsePayload(
                element.getPayload(), payloadSchema, payloadFormat, objectMapper);
        List<Object> values =
            messageSchema.getFields().stream()
                .map(field -> getValueForFieldFlatSchema(field, timestamp, payload))
                .collect(toList());
        o.get(MAIN_TAG).output(Row.withSchema(messageSchema).addValues(values).build());
      } catch (UnsupportedRowJsonException
          | AvroRuntimeException
          | IllegalArgumentException exception) {
        if (useDlq) {
          o.get(DLQ_TAG).output(element);
        } else {
          throw new RuntimeException("Error parsing message", exception);
        }
      }
    }
  }

  /**
   * A {@link DoFn} to convert a nested schema {@link PubsubMessage} with JSON/AVRO payload to
   * {@link Row}.
   */
  @Internal
  private static class NestedSchemaPubsubMessageToRow extends DoFn<PubsubMessage, Row> {

    private final Schema messageSchema;

    private final boolean useDlq;

    private final PayloadFormat payloadFormat;

    private transient volatile @Nullable ObjectMapper objectMapper;

    protected NestedSchemaPubsubMessageToRow(
        Schema messageSchema, boolean useDlq, PayloadFormat payloadFormat) {
      this.messageSchema = messageSchema;
      this.useDlq = useDlq;
      this.payloadFormat = payloadFormat;
    }

    /** Get the value for a field int the order they're specified in the nested schema. */
    private Object getValueForFieldNestedSchema(
        Schema.Field field, Instant timestamp, Map<String, String> attributeMap, Row payload) {
      switch (field.getName()) {
        case TIMESTAMP_FIELD:
          return timestamp;
        case ATTRIBUTES_FIELD:
          return attributeMap;
        case PAYLOAD_FIELD:
          return payload;
        default:
          throw new IllegalArgumentException(
              "Unexpected field '"
                  + field.getName()
                  + "' in top level schema"
                  + " for Pubsub message. Top level schema should only contain "
                  + "'timestamp', 'attributes', and 'payload' fields");
      }
    }

    private Row parsePayload(PubsubMessage pubsubMessage) {
      Schema payloadSchema = messageSchema.getField(PAYLOAD_FIELD).getType().getRowSchema();
      return PubsubMessageToRow.parsePayload(
          pubsubMessage.getPayload(), payloadSchema, payloadFormat, objectMapper);
    }

    @ProcessElement
    public void processElement(
        @Element PubsubMessage element, @Timestamp Instant timestamp, MultiOutputReceiver o) {
      try {
        Row payload = parsePayload(element);
        List<Object> values =
            messageSchema.getFields().stream()
                .map(
                    field ->
                        getValueForFieldNestedSchema(
                            field, timestamp, element.getAttributeMap(), payload))
                .collect(toList());
        o.get(MAIN_TAG).output(Row.withSchema(messageSchema).addValues(values).build());
      } catch (UnsupportedRowJsonException
          | AvroRuntimeException
          | IllegalArgumentException exception) {
        if (useDlq) {
          o.get(DLQ_TAG).output(element);
        } else {
          throw new RuntimeException("Error parsing message", exception);
        }
      }
    }
  }

  @AutoValue.Builder
  abstract static class Builder {
    public abstract Builder messageSchema(Schema messageSchema);

    public abstract Builder useDlq(boolean useDlq);

    public abstract Builder useFlatSchema(boolean useFlatSchema);

    public abstract Builder payloadFormat(PayloadFormat payloadFormat);

    public abstract PubsubMessageToRow build();
  }
}
