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
import java.util.function.Supplier;

import static org.hamcrest.Matchers.equalTo;

public class JsonRemovePathTests extends AbstractScalarFunctionTestCase {

    public JsonRemovePathTests(@Name("TestCase") Supplier<TestCaseSupplier.TestCase> testCaseSupplier) {
        this.testCase = testCaseSupplier.get();
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() {
        List<TestCaseSupplier> suppliers = new ArrayList<>();
        // Every combination of string types for (json, path). This also covers the
        // supported-types matrix required by the function info checks.
        for (DataType jsonType : List.of(DataType.KEYWORD, DataType.TEXT)) {
            for (DataType pathType : List.of(DataType.KEYWORD, DataType.TEXT)) {
                suppliers.add(singlePath(jsonType, pathType));
            }
        }
        suppliers.add(twoPaths());
        suppliers.add(nestedKeepsEmptyParent());
        return parameterSuppliersFromTypedData(randomizeBytesRefsOffset(suppliers));
    }

    private static TestCaseSupplier singlePath(DataType jsonType, DataType pathType) {
        List<DataType> types = List.of(jsonType, pathType);
        return new TestCaseSupplier(types, () -> {
            String drop = randomAlphaOfLengthBetween(1, 8);
            String keep = randomAlphaOfLengthBetween(1, 8);
            while (keep.equals(drop)) {
                keep = randomAlphaOfLengthBetween(1, 8);
            }
            String json = "{\"" + drop + "\":\"gone\",\"" + keep + "\":\"stays\"}";
            String expected = "{\"" + keep + "\":\"stays\"}";
            List<TestCaseSupplier.TypedData> data = List.of(
                new TestCaseSupplier.TypedData(new BytesRef(json), jsonType, "json"),
                new TestCaseSupplier.TypedData(new BytesRef("$." + drop), pathType, "path")
            );
            return new TestCaseSupplier.TestCase(data, expectedToString(1), DataType.KEYWORD, equalTo(new BytesRef(expected)));
        });
    }

    private static TestCaseSupplier twoPaths() {
        List<DataType> types = List.of(DataType.KEYWORD, DataType.KEYWORD, DataType.KEYWORD);
        return new TestCaseSupplier("two paths", types, () -> {
            List<TestCaseSupplier.TypedData> data = List.of(
                new TestCaseSupplier.TypedData(new BytesRef("{\"a\":\"1\",\"b\":\"2\",\"c\":\"3\"}"), DataType.KEYWORD, "json"),
                new TestCaseSupplier.TypedData(new BytesRef("$.a"), DataType.KEYWORD, "path0"),
                new TestCaseSupplier.TypedData(new BytesRef("$.c"), DataType.KEYWORD, "path1")
            );
            return new TestCaseSupplier.TestCase(data, expectedToString(2), DataType.KEYWORD, equalTo(new BytesRef("{\"b\":\"2\"}")));
        });
    }

    private static TestCaseSupplier nestedKeepsEmptyParent() {
        List<DataType> types = List.of(DataType.KEYWORD, DataType.KEYWORD);
        return new TestCaseSupplier("nested removal keeps the emptied parent", types, () -> {
            List<TestCaseSupplier.TypedData> data = List.of(
                new TestCaseSupplier.TypedData(new BytesRef("{\"a\":{\"b\":\"1\"}}"), DataType.KEYWORD, "json"),
                new TestCaseSupplier.TypedData(new BytesRef("$.a.b"), DataType.KEYWORD, "path")
            );
            return new TestCaseSupplier.TestCase(data, expectedToString(1), DataType.KEYWORD, equalTo(new BytesRef("{\"a\":{}}")));
        });
    }

    private static String expectedToString(int paths) {
        StringBuilder rendered = new StringBuilder("[");
        for (int i = 0; i < paths; i++) {
            if (i > 0) {
                rendered.append(", ");
            }
            rendered.append("Attribute[channel=").append(i + 1).append("]");
        }
        rendered.append("]");
        return "JsonRemovePathEvaluator[paths=" + rendered + "]";
    }

    @Override
    protected Expression build(Source source, List<Expression> args) {
        return new JsonRemovePath(source, args);
    }
}
