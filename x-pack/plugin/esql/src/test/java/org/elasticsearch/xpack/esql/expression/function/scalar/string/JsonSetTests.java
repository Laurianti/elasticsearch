/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.string;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.AbstractScalarFunctionTestCase;
import org.elasticsearch.xpack.esql.expression.function.TestCaseSupplier;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.equalTo;

public class JsonSetTests extends AbstractScalarFunctionTestCase {

    public JsonSetTests(@Name("TestCase") Supplier<TestCaseSupplier.TestCase> testCaseSupplier) {
        this.testCase = testCaseSupplier.get();
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() {
        List<TestCaseSupplier> suppliers = new ArrayList<>();
        // A single path/value pair for every combination of string types. This also covers the
        // supported-types matrix required by the function info checks.
        for (DataType jsonType : List.of(DataType.KEYWORD, DataType.TEXT)) {
            for (DataType pathType : List.of(DataType.KEYWORD, DataType.TEXT)) {
                for (DataType valueType : List.of(DataType.KEYWORD, DataType.TEXT)) {
                    suppliers.add(singlePair(jsonType, pathType, valueType));
                }
            }
        }
        suppliers.add(twoPairs());
        suppliers.add(nullValue());
        suppliers.add(escaping());
        return parameterSuppliersFromTypedData(randomizeBytesRefsOffset(suppliers));
    }

    private static TestCaseSupplier singlePair(DataType jsonType, DataType pathType, DataType valueType) {
        List<DataType> types = List.of(jsonType, pathType, valueType);
        return new TestCaseSupplier(types, () -> {
            String member = randomAlphaOfLengthBetween(1, 8);
            String value = randomAlphaOfLengthBetween(1, 8);
            String json = "{\"keep\":\"0\"}";
            TreeMap<String, String> members = new TreeMap<>();
            members.put("keep", "0");
            members.put(member, value);
            StringBuilder expected = new StringBuilder("{");
            String prefix = "";
            for (var entry : members.entrySet()) {
                expected.append(prefix).append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                prefix = ",";
            }
            expected.append("}");
            List<TestCaseSupplier.TypedData> data = List.of(
                new TestCaseSupplier.TypedData(new BytesRef(json), jsonType, "json"),
                new TestCaseSupplier.TypedData(new BytesRef("$." + member), pathType, "path"),
                new TestCaseSupplier.TypedData(new BytesRef(value), valueType, "value")
            );
            return new TestCaseSupplier.TestCase(data, expectedToString(1), DataType.KEYWORD, equalTo(new BytesRef(expected.toString())));
        });
    }

    private static TestCaseSupplier twoPairs() {
        List<DataType> types = List.of(DataType.KEYWORD, DataType.KEYWORD, DataType.KEYWORD, DataType.KEYWORD, DataType.KEYWORD);
        return new TestCaseSupplier("two pairs", types, () -> {
            String m0 = randomAlphaOfLengthBetween(1, 8);
            String v0 = randomAlphaOfLengthBetween(1, 8);
            String m1 = randomAlphaOfLengthBetween(1, 8);
            String v1 = randomAlphaOfLengthBetween(1, 8);
            TreeMap<String, String> members = new TreeMap<>();
            members.put(m0, v0);
            members.put(m1, v1);
            StringBuilder expected = new StringBuilder("{");
            String prefix = "";
            for (var entry : members.entrySet()) {
                expected.append(prefix).append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                prefix = ",";
            }
            expected.append("}");
            List<TestCaseSupplier.TypedData> data = List.of(
                new TestCaseSupplier.TypedData(new BytesRef("{}"), DataType.KEYWORD, "json"),
                new TestCaseSupplier.TypedData(new BytesRef("$." + m0), DataType.KEYWORD, "path0"),
                new TestCaseSupplier.TypedData(new BytesRef(v0), DataType.KEYWORD, "value0"),
                new TestCaseSupplier.TypedData(new BytesRef("$." + m1), DataType.KEYWORD, "path1"),
                new TestCaseSupplier.TypedData(new BytesRef(v1), DataType.KEYWORD, "value1")
            );
            return new TestCaseSupplier.TestCase(data, expectedToString(2), DataType.KEYWORD, equalTo(new BytesRef(expected.toString())));
        });
    }

    private static TestCaseSupplier nullValue() {
        List<DataType> types = List.of(DataType.KEYWORD, DataType.KEYWORD, DataType.NULL);
        return new TestCaseSupplier("null value sets JSON null", types, () -> {
            List<TestCaseSupplier.TypedData> data = List.of(
                new TestCaseSupplier.TypedData(new BytesRef("{\"a\":\"x\"}"), DataType.KEYWORD, "json"),
                new TestCaseSupplier.TypedData(new BytesRef("$.b"), DataType.KEYWORD, "path"),
                TestCaseSupplier.TypedData.NULL
            );
            return new TestCaseSupplier.TestCase(
                data,
                expectedToString(1),
                DataType.KEYWORD,
                equalTo(new BytesRef("{\"a\":\"x\",\"b\":null}"))
            );
        });
    }

    private static TestCaseSupplier escaping() {
        List<DataType> types = List.of(DataType.KEYWORD, DataType.KEYWORD, DataType.KEYWORD);
        return new TestCaseSupplier("escaping", types, () -> {
            // value: line1<newline>line2<tab>"q"
            BytesRef value = new BytesRef("line1\nline2\t\"q\"");
            String json = "{\"a\\\"b\\\\c\":\"line1\\nline2\\t\\\"q\\\"\"}";
            List<TestCaseSupplier.TypedData> data = List.of(
                new TestCaseSupplier.TypedData(new BytesRef("{}"), DataType.KEYWORD, "json"),
                new TestCaseSupplier.TypedData(new BytesRef("$[\"a\\\"b\\\\c\"]"), DataType.KEYWORD, "path"),
                new TestCaseSupplier.TypedData(value, DataType.KEYWORD, "value")
            );
            return new TestCaseSupplier.TestCase(data, expectedToString(1), DataType.KEYWORD, equalTo(new BytesRef(json)));
        });
    }

    private static String expectedToString(int pairs) {
        StringBuilder paths = new StringBuilder("[");
        StringBuilder values = new StringBuilder("[");
        for (int i = 0; i < pairs; i++) {
            if (i > 0) {
                paths.append(", ");
                values.append(", ");
            }
            paths.append("Attribute[channel=").append(1 + i * 2).append("]");
            values.append("Attribute[channel=").append(1 + i * 2 + 1).append("]");
        }
        paths.append("]");
        values.append("]");
        return "JsonSetEvaluator[paths=" + paths + ", values=" + values + "]";
    }

    @Override
    protected Expression build(Source source, List<Expression> args) {
        return new JsonSet(source, args);
    }
}
