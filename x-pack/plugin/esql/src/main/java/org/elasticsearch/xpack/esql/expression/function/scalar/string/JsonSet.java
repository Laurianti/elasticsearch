/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.string;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.VersionedNamedWriteable;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParseException;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Expressions;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Nullability;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.Example;
import org.elasticsearch.xpack.esql.expression.function.FunctionAppliesTo;
import org.elasticsearch.xpack.esql.expression.function.FunctionAppliesToLifecycle;
import org.elasticsearch.xpack.esql.expression.function.FunctionDefinition;
import org.elasticsearch.xpack.esql.expression.function.FunctionInfo;
import org.elasticsearch.xpack.esql.expression.function.Param;
import org.elasticsearch.xpack.esql.expression.function.scalar.EsqlScalarFunction;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.elasticsearch.common.logging.LoggerMessageFormat.format;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isString;

/**
 * Sets members of a JSON object, following the MySQL {@code JSON_SET} modification spec
 * (replace-or-insert on exact member paths).
 * <p>
 * Each path/value pair replaces the member at that path, or adds it when it is missing.
 * Missing intermediate objects are created. A null value sets JSON {@code null}; it never
 * deletes the member (unlike JSON Merge Patch). Pairs apply left to right, so a later pair
 * overwrites an earlier one addressing the same member. Object keys in the result are sorted,
 * so output is deterministic regardless of input key order.
 * <p>
 * Only object member paths can be addressed; array index paths are rejected (documented
 * limitation). Malformed JSON input, non-object documents, and attempts to traverse through
 * a non-object member are per-row errors reported as warnings that null the row.
 */
public class JsonSet extends EsqlScalarFunction implements VersionedNamedWriteable {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(Expression.class, "JsonSet", JsonSet::new);
    public static final FunctionDefinition DEFINITION = FunctionDefinition.def(JsonSet.class).nAry(JsonSet::new).name("json_set");

    public JsonSet(Source source, List<Expression> children) {
        super(source, children);
    }

    @FunctionInfo(
        appliesTo = { @FunctionAppliesTo(lifeCycle = FunctionAppliesToLifecycle.EXPERIMENTAL, version = "9.6+") },
        returnType = "keyword",
        briefSummary = "Sets members of a JSON object, adding them when missing.",
        description = """
            Sets one or more members of a JSON object. Each path/value pair replaces the member
            at that path, or adds it when it is missing. Missing intermediate objects are created.
            A null value sets JSON `null`. Members are addressed with the same path syntax as
            `JSON_EXTRACT`; keys containing dots or other special characters use quoted bracket
            notation (`$["a.b"]`). Array index paths are not supported.
            Object keys in the result are sorted.""",
        examples = @Example(file = "json_set", tag = "json_set")
    )
    public JsonSet(
        Source source,
        @Param(name = "json", type = { "keyword", "text" }, description = "The JSON object to modify.") Expression json,
        @Param(
            name = "path",
            type = { "keyword", "text" },
            description = "A path identifying the member to set, followed by its new value, stored as a JSON string. "
                + "Provide arguments as alternating path/value pairs."
        ) List<Expression> rest
    ) {
        this(source, Stream.concat(Stream.of(json), rest.stream()).toList());
    }

    private JsonSet(StreamInput in) throws IOException {
        this(Source.readFrom((PlanStreamInput) in), in.readNamedWriteableCollectionAsList(Expression.class));
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        if (supportsVersion(out.getTransportVersion()) == false) {
            throw new IOException("JSON object edits are not supported by the recipient");
        }
        source().writeTo(out);
        out.writeNamedWriteableCollection(children());
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    // Placeholder minimum while the function is unused; raised to the packed-record version once translation emits it.
    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return FieldAttribute.ESQL_TIMESERIES_METADATA_ATTRIBUTE;
    }

    @Override
    public DataType dataType() {
        return DataType.KEYWORD;
    }

    @Override
    public Nullability nullable() {
        // A null value argument produces a non-null row ({"member":null}), so nullability is unknown.
        return Nullability.UNKNOWN;
    }

    @Override
    protected TypeResolution resolveType() {
        if (childrenResolved() == false) {
            return new TypeResolution("Unresolved children");
        }

        List<Expression> children = children();
        if (children.size() < 3 || (children.size() - 1) % 2 != 0) {
            return new TypeResolution(
                format(
                    null,
                    "function [{}] expects an odd number of arguments "
                        + "(a JSON document followed by one or more path/value pairs) but got [{}]",
                    sourceText(),
                    children.size()
                )
            );
        }

        for (int i = 0; i < children.size(); i++) {
            TypeResolution resolution = isString(children.get(i), sourceText(), ParamOrdinal.fromIndex(i));
            if (resolution.unresolved()) {
                return resolution;
            }
        }

        return TypeResolution.TYPE_RESOLVED;
    }

    @Override
    public boolean foldable() {
        return Expressions.foldable(children());
    }

    @Override
    public ExpressionEvaluator.Factory toEvaluator(ToEvaluator toEvaluator) {
        ExpressionEvaluator.Factory json = toEvaluator.apply(children().get(0));
        int pairs = (children().size() - 1) / 2;
        ExpressionEvaluator.Factory[] paths = new ExpressionEvaluator.Factory[pairs];
        ExpressionEvaluator.Factory[] values = new ExpressionEvaluator.Factory[pairs];
        List<List<String>> preparsed = new ArrayList<>(pairs);
        for (int i = 0; i < pairs; i++) {
            preparsed.add(null);
        }
        boolean[] pathAlwaysNull = new boolean[pairs];
        for (int i = 0; i < pairs; i++) {
            Expression path = children().get(1 + i * 2);
            paths[i] = toEvaluator.apply(path);
            values[i] = toEvaluator.apply(children().get(1 + i * 2 + 1));
            if (path.foldable()) {
                // Constant paths are parsed once up front and reused for every row.
                Object folded = path.fold(FoldContext.small());
                if (folded == null) {
                    pathAlwaysNull[i] = true;
                } else {
                    preparsed.set(i, JsonPath.memberKeys(((BytesRef) folded).utf8ToString()));
                }
            }
        }
        return new JsonSetEvaluator.Factory(source(), json, paths, values, preparsed, pathAlwaysNull);
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new JsonSet(source(), newChildren.get(0), newChildren.subList(1, newChildren.size()));
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, JsonSet::new, children().get(0), children().subList(1, children().size()));
    }

    /**
     * Applies one path/value pair to a parsed document. Missing intermediate objects are created;
     * a {@code null} value is stored as JSON {@code null}.
     *
     * @throws IllegalArgumentException if a non-final segment traverses a non-object member
     */
    static void applySet(Map<String, Object> document, List<String> keys, Object value) {
        Map<String, Object> current = document;
        for (int i = 0; i < keys.size(); i++) {
            String name = keys.get(i);
            if (i == keys.size() - 1) {
                current.put(name, value);
                return;
            }
            Object child = current.get(name);
            if (child == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(name, created);
                current = created;
            } else if (child instanceof Map<?, ?> nested) {
                current = uncheckedObjectMap(nested);
            } else {
                throw new IllegalArgumentException("Invalid JSON path [" + String.join(".", keys) + "]: [" + name + "] is not an object");
            }
        }
    }

    static Map<String, Object> readObject(BytesRef json) throws IOException {
        try (
            var parser = XContentType.JSON.xContent().createParser(XContentParserConfiguration.EMPTY, json.bytes, json.offset, json.length)
        ) {
            if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
                throw new IllegalArgumentException("expected a JSON object");
            }
            Map<String, Object> result = parser.mapOrdered();
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("trailing content after JSON object");
            }
            return result;
        } catch (XContentParseException e) {
            throw new IllegalArgumentException("invalid JSON object", e);
        }
    }

    static BytesRef writeObject(Map<String, Object> object) throws IOException {
        try (var builder = XContentFactory.jsonBuilder()) {
            builder.value(ordered(object));
            return BytesReference.bytes(builder).toBytesRef();
        }
    }

    private static Object ordered(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put((String) key, ordered(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(JsonSet::ordered).toList();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uncheckedObjectMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * This function uses a hand-written {@link ExpressionEvaluator} rather than the generated
     * {@code @Evaluator} machinery because a null value argument must produce JSON {@code null}
     * instead of nulling the row, and because the path/value pairs are variadic.
     */
    static final class JsonSetEvaluator implements ExpressionEvaluator {
        private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(JsonSetEvaluator.class);

        private final Source source;
        private final ExpressionEvaluator json;
        private final ExpressionEvaluator[] paths;
        private final ExpressionEvaluator[] values;
        private final List<List<String>> preparsedPaths;
        private final boolean[] pathAlwaysNull;
        private final DriverContext driverContext;
        private Warnings warnings;

        JsonSetEvaluator(
            Source source,
            ExpressionEvaluator json,
            ExpressionEvaluator[] paths,
            ExpressionEvaluator[] values,
            List<List<String>> preparsedPaths,
            boolean[] pathAlwaysNull,
            DriverContext driverContext
        ) {
            this.source = source;
            this.json = json;
            this.paths = paths;
            this.values = values;
            this.preparsedPaths = preparsedPaths;
            this.pathAlwaysNull = pathAlwaysNull;
            this.driverContext = driverContext;
        }

        @Override
        public Block eval(Page page) {
            BytesRefBlock jsonBlock = (BytesRefBlock) json.eval(page);
            BytesRefBlock[] pathBlocks = new BytesRefBlock[paths.length];
            Block[] valueBlocks = new Block[values.length];
            try (var jsonRelease = Releasables.wrap(jsonBlock)) {
                try (var pathsRelease = Releasables.wrap(pathBlocks); var valuesRelease = Releasables.wrap(valueBlocks)) {
                    for (int i = 0; i < paths.length; i++) {
                        pathBlocks[i] = (BytesRefBlock) paths[i].eval(page);
                    }
                    for (int i = 0; i < values.length; i++) {
                        valueBlocks[i] = values[i].eval(page);
                    }
                    return eval(page.getPositionCount(), jsonBlock, pathBlocks, valueBlocks);
                }
            }
        }

        private Block eval(int positionCount, BytesRefBlock jsonBlock, BytesRefBlock[] pathBlocks, Block[] valueBlocks) {
            BytesRef scratch = new BytesRef();
            try (BytesRefBlock.Builder result = driverContext.blockFactory().newBytesRefBlockBuilder(positionCount)) {
                position: for (int p = 0; p < positionCount; p++) {
                    if (jsonBlock.isNull(p)) {
                        result.appendNull();
                        continue;
                    }
                    if (jsonBlock.getValueCount(p) != 1) {
                        warnings().registerException(new IllegalArgumentException("single-value function encountered multi-value"));
                        result.appendNull();
                        continue;
                    }
                    Map<String, Object> document;
                    try {
                        document = readObject(jsonBlock.getBytesRef(jsonBlock.getFirstValueIndex(p), scratch));
                    } catch (IOException | IllegalArgumentException e) {
                        warnings().registerException(e);
                        result.appendNull();
                        continue;
                    }
                    for (int i = 0; i < paths.length; i++) {
                        if (pathAlwaysNull[i] || pathBlocks[i].isNull(p)) {
                            // A null path nulls the row, mirroring MySQL.
                            result.appendNull();
                            continue position;
                        }
                        if (pathBlocks[i].getValueCount(p) != 1 || valueBlocks[i].getValueCount(p) > 1) {
                            warnings().registerException(new IllegalArgumentException("single-value function encountered multi-value"));
                            result.appendNull();
                            continue position;
                        }
                        List<String> keys = preparsedPaths.get(i);
                        if (keys == null) {
                            try {
                                keys = JsonPath.memberKeys(
                                    pathBlocks[i].getBytesRef(pathBlocks[i].getFirstValueIndex(p), scratch).utf8ToString()
                                );
                            } catch (IllegalArgumentException e) {
                                warnings().registerException(e);
                                result.appendNull();
                                continue position;
                            }
                        }
                        Block valueBlock = valueBlocks[i];
                        Object value = valueBlock.isNull(p)
                            ? null
                            : ((BytesRefBlock) valueBlock).getBytesRef(valueBlock.getFirstValueIndex(p), scratch).utf8ToString();
                        try {
                            applySet(document, keys, value);
                        } catch (IllegalArgumentException e) {
                            warnings().registerException(e);
                            result.appendNull();
                            continue position;
                        }
                    }
                    try {
                        result.appendBytesRef(writeObject(document));
                    } catch (IOException e) {
                        warnings().registerException(e);
                        result.appendNull();
                    }
                }
                return result.build();
            }
        }

        @Override
        public long baseRamBytesUsed() {
            long ram = BASE_RAM_BYTES_USED;
            ram += json.baseRamBytesUsed();
            for (ExpressionEvaluator e : paths) {
                ram += e.baseRamBytesUsed();
            }
            for (ExpressionEvaluator e : values) {
                ram += e.baseRamBytesUsed();
            }
            return ram;
        }

        @Override
        public void close() {
            Releasables.closeExpectNoException(
                () -> Releasables.close(json),
                () -> Releasables.close(paths),
                () -> Releasables.close(values)
            );
        }

        private Warnings warnings() {
            if (warnings == null) {
                this.warnings = driverContext.createWarnings(source);
            }
            return warnings;
        }

        @Override
        public String toString() {
            return "JsonSetEvaluator[paths=" + Arrays.toString(paths) + ", values=" + Arrays.toString(values) + "]";
        }

        static final class Factory implements ExpressionEvaluator.Factory {
            private final Source source;
            private final ExpressionEvaluator.Factory json;
            private final ExpressionEvaluator.Factory[] paths;
            private final ExpressionEvaluator.Factory[] values;
            private final List<List<String>> preparsedPaths;
            private final boolean[] pathAlwaysNull;

            Factory(
                Source source,
                ExpressionEvaluator.Factory json,
                ExpressionEvaluator.Factory[] paths,
                ExpressionEvaluator.Factory[] values,
                List<List<String>> preparsedPaths,
                boolean[] pathAlwaysNull
            ) {
                this.source = source;
                this.json = json;
                this.paths = paths;
                this.values = values;
                this.preparsedPaths = preparsedPaths;
                this.pathAlwaysNull = pathAlwaysNull;
            }

            @Override
            public ExpressionEvaluator get(DriverContext context) {
                ExpressionEvaluator jsonEvaluator = json.get(context);
                ExpressionEvaluator[] pathEvaluators = new ExpressionEvaluator[paths.length];
                ExpressionEvaluator[] valueEvaluators = new ExpressionEvaluator[values.length];
                try {
                    for (int i = 0; i < paths.length; i++) {
                        pathEvaluators[i] = paths[i].get(context);
                    }
                    for (int i = 0; i < values.length; i++) {
                        valueEvaluators[i] = values[i].get(context);
                    }
                    JsonSetEvaluator evaluator = new JsonSetEvaluator(
                        source,
                        jsonEvaluator,
                        pathEvaluators,
                        valueEvaluators,
                        preparsedPaths,
                        pathAlwaysNull,
                        context
                    );
                    jsonEvaluator = null;
                    pathEvaluators = null;
                    valueEvaluators = null;
                    return evaluator;
                } finally {
                    Releasables.close(
                        jsonEvaluator == null ? () -> {} : jsonEvaluator,
                        pathEvaluators == null ? () -> {} : Releasables.wrap(pathEvaluators),
                        valueEvaluators == null ? () -> {} : Releasables.wrap(valueEvaluators)
                    );
                }
            }

            @Override
            public String toString() {
                return "JsonSetEvaluator[paths=" + Arrays.toString(paths) + ", values=" + Arrays.toString(values) + "]";
            }
        }
    }

}
