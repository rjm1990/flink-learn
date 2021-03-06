/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.format.jsonformat;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.formats.common.TimestampFormat;
import org.apache.flink.formats.json.*;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.RowType;

import java.util.Objects;

import static java.lang.String.format;
import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Serialization schema that serializes an object of Flink types into a JSON bytes.
 *
 * <p>Serializes the input Flink object into a JSON string and
 * converts it into <code>byte[]</code>.
 *
 * <p>Result <code>byte[]</code> messages can be deserialized using {@link CustomJsonDeserialization}.
 */
@PublicEvolving
public class CustomJsonSerialization implements SerializationSchema<RowData> {
	private static final long serialVersionUID = 1L;

	/** RowType to generate the runtime converter. */
	private final RowType rowType;

	/** The converter that converts internal data formats to JsonNode. */
	private final RowDataToJsonConverters.RowDataToJsonConverter runtimeConverter;

	/** Object mapper that is used to create output JSON objects. */
	private final ObjectMapper mapper = new ObjectMapper();

	/** Reusable object node. */
	private transient ObjectNode node;

	/** Timestamp format specification which is used to parse timestamp. */
	private final TimestampFormat timestampFormat;

	/** The handling mode when serializing null keys for map data. */
	private final JsonOptions.MapNullKeyMode mapNullKeyMode;

	/** The string literal when handling mode for map null key LITERAL. is */
	private final String mapNullKeyLiteral;

	public CustomJsonSerialization(
			RowType rowType,
			TimestampFormat timestampFormat,
			JsonOptions.MapNullKeyMode mapNullKeyMode,
			String mapNullKeyLiteral) {
		this.rowType = rowType;
		this.timestampFormat = timestampFormat;
		this.mapNullKeyMode = mapNullKeyMode;
		this.mapNullKeyLiteral = mapNullKeyLiteral;
		this.runtimeConverter =
				new RowDataToJsonConverters(timestampFormat, mapNullKeyMode, mapNullKeyLiteral)
						.createConverter(rowType);
	}

	@Override
	public byte[] serialize(RowData row) {
		if (node == null) {
			node = mapper.createObjectNode();
		}

		try {
			runtimeConverter.convert(mapper, node, row);
			return mapper.writeValueAsBytes(node);
		} catch (Throwable t) {
			throw new RuntimeException("Could not serialize row '" + row + "'. ", t);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		CustomJsonSerialization that = (CustomJsonSerialization) o;
		return rowType.equals(that.rowType)
				&& timestampFormat.equals(that.timestampFormat)
				&& mapNullKeyMode.equals(that.mapNullKeyMode)
				&& mapNullKeyLiteral.equals(that.mapNullKeyLiteral);
	}

	@Override
	public int hashCode() {
		return Objects.hash(rowType, timestampFormat, mapNullKeyMode, mapNullKeyLiteral);
	}
}

