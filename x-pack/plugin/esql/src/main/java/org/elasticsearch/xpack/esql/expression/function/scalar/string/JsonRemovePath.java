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
import org.elasticsearch.xpack.esql.core.expression.AnyNullIsNull;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Expressions;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.elasticsearch.common.logging.LoggerMessageFormat.format;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isString;

/**
 * Removes members from a JSON object, following the MySQL {@code JSON_REMOVE} modification spec.
 * <p>
 * Each path addresses an exact member: a missing path, or a path that traverses a missing or
 * non-object member, is a no-op that leaves the document unchanged. There are no wildcards.
 * Emptied parent objects are kept (MySQL parity): removing the last member of a nested object
 * leaves behind an empty object rather than pruning it. Object keys in the result are sorted,
 * so output is deterministic regardless of input key order.
 * <p>
 * Only object member paths can be addressed; array index paths are rejected (documented
 * limitation). Malformed JSON input and non-object documents are per-row errors reported
 * as warnings that null the row.
 * <p>
 * The {@code Path} suffix distinguishes this path-addressed removal from value-based JSON
 * functions.
 */
public class JsonRemovePath extends EsqlScalarFunction implements AnyNullIsNull, VersionedNamedWriteable {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        Expression.class,
        "JsonRemovePath",
        JsonRemovePath::new
    );
    public static final FunctionDefinition DEFINITION = FunctionDefinition.def(JsonRemovePath.class)
        .nAry(JsonRemovePath::new)
        .name("json_remove");

    public JsonRemovePath(Source source, List<Expression> children) {
        super(source, children);
    }

    @FunctionInfo(
        appliesTo = { @FunctionAppliesTo(lifeCycle = FunctionAppliesToLifecycle.EXPERIMENTAL, version = "9.6+") },
        returnType = "keyword",
        briefSummary = "Removes members from a JSON object.",
        description = """
            Removes one or more members from a JSON object. Each path addresses an exact member;
            a path that does not exist is a no-op. Emptied parent objects are kept. Members are
            addressed with the same path syntax as `JSON_EXTRACT`; keys containing dots or other
            special characters use quoted bracket notation (`$["a.b"]`). Array index paths are
            not supported. Object keys in the result are sorted.""",
        examples = @Example(file = "json_remove", tag = "json_remove")
    )
    public JsonRemovePath(
        Source source,
        @Param(name = "json", type = { "keyword", "text" }, description = "The JSON object to modify.") Expression json,
        @Param(
            name = "path",
            type = { "keyword", "text" },
            description = "A path identifying the member to remove. Additional paths may follow."
        ) List<Expression> rest
    ) {
        this(source, Stream.concat(Stream.of(json), rest.stream()).toList());
    }

    private JsonRemovePath(StreamInput in) throws IOException {
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

    // Placeholder minimum while the function is unused; raised to the packed-record version once translation emits it.
    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return FieldAttribute.ESQL_TIMESERIES_METADATA_ATTRIBUTE;
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    @Override
    public DataType dataType() {
        return DataType.KEYWORD;
    }

    @Override
    protected TypeResolution resolveType() {
        if (childrenResolved() == false) {
            return new TypeResolution("Unresolved children");
        }

        List<Expression> children = children();
        if (children.size() < 2) {
            return new TypeResolution(
                format(
                    null,
                    "function [{}] expects at least two arguments " + "(a JSON document followed by one or more paths) but got [{}]",
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
        ExpressionEvaluator.Factory[] paths = new ExpressionEvaluator.Factory[children().size() - 1];
        List<List<String>> preparsed = new ArrayList<>(paths.length);
        for (int i = 0; i < paths.length; i++) {
            preparsed.add(null);
        }
        boolean[] pathAlwaysNull = new boolean[paths.length];
        for (int i = 0; i < paths.length; i++) {
            Expression path = children().get(i + 1);
            paths[i] = toEvaluator.apply(path);
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
        return new JsonRemovePathEvaluator.Factory(source(), json, paths, preparsed, pathAlwaysNull);
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new JsonRemovePath(source(), newChildren.get(0), newChildren.subList(1, newChildren.size()));
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, JsonRemovePath::new, children().get(0), children().subList(1, children().size()));
    }

    /**
     * Removes the addressed member from a parsed document. A missing path, or a path that
     * traverses a missing or non-object member, is a no-op. Emptied parents are deliberately
     * kept rather than pruned.
     */
    static void applyRemove(Map<String, Object> document, List<String> keys) {
        Map<String, Object> current = document;
        for (int i = 0; i < keys.size() - 1; i++) {
            Object child = current.get(keys.get(i));
            if (child instanceof Map<?, ?> nested) {
                current = uncheckedObjectMap(nested);
            } else {
                return;
            }
        }
        current.remove(keys.get(keys.size() - 1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uncheckedObjectMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * This function uses a hand-written {@link ExpressionEvaluator} rather than the generated
     * {@code @Evaluator} machinery because the paths are variadic.
     */
    static final class JsonRemovePathEvaluator implements ExpressionEvaluator {
        private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(JsonRemovePathEvaluator.class);

        private final Source source;
        private final ExpressionEvaluator json;
        private final ExpressionEvaluator[] paths;
        private final List<List<String>> preparsedPaths;
        private final boolean[] pathAlwaysNull;
        private final DriverContext driverContext;
        private Warnings warnings;

        JsonRemovePathEvaluator(
            Source source,
            ExpressionEvaluator json,
            ExpressionEvaluator[] paths,
            List<List<String>> preparsedPaths,
            boolean[] pathAlwaysNull,
            DriverContext driverContext
        ) {
            this.source = source;
            this.json = json;
            this.paths = paths;
            this.preparsedPaths = preparsedPaths;
            this.pathAlwaysNull = pathAlwaysNull;
            this.driverContext = driverContext;
        }

        @Override
        public Block eval(Page page) {
            BytesRefBlock jsonBlock = (BytesRefBlock) json.eval(page);
            BytesRefBlock[] pathBlocks = new BytesRefBlock[paths.length];
            try (var jsonRelease = Releasables.wrap(jsonBlock)) {
                try (var pathsRelease = Releasables.wrap(pathBlocks)) {
                    for (int i = 0; i < paths.length; i++) {
                        pathBlocks[i] = (BytesRefBlock) paths[i].eval(page);
                    }
                    return eval(page.getPositionCount(), jsonBlock, pathBlocks);
                }
            }
        }

        private Block eval(int positionCount, BytesRefBlock jsonBlock, BytesRefBlock[] pathBlocks) {
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
                        document = JsonSet.readObject(jsonBlock.getBytesRef(jsonBlock.getFirstValueIndex(p), scratch));
                    } catch (IOException | IllegalArgumentException e) {
                        warnings().registerException(e);
                        result.appendNull();
                        continue;
                    }
                    for (int i = 0; i < paths.length; i++) {
                        if (pathAlwaysNull[i] || pathBlocks[i].isNull(p)) {
                            result.appendNull();
                            continue position;
                        }
                        if (pathBlocks[i].getValueCount(p) != 1) {
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
                        applyRemove(document, keys);
                    }
                    try {
                        result.appendBytesRef(JsonSet.writeObject(document));
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
            return ram;
        }

        @Override
        public void close() {
            Releasables.closeExpectNoException(() -> Releasables.close(json), () -> Releasables.close(paths));
        }

        private Warnings warnings() {
            if (warnings == null) {
                this.warnings = driverContext.createWarnings(source);
            }
            return warnings;
        }

        @Override
        public String toString() {
            return "JsonRemovePathEvaluator[paths=" + Arrays.toString(paths) + "]";
        }

        static final class Factory implements ExpressionEvaluator.Factory {
            private final Source source;
            private final ExpressionEvaluator.Factory json;
            private final ExpressionEvaluator.Factory[] paths;
            private final List<List<String>> preparsedPaths;
            private final boolean[] pathAlwaysNull;

            Factory(
                Source source,
                ExpressionEvaluator.Factory json,
                ExpressionEvaluator.Factory[] paths,
                List<List<String>> preparsedPaths,
                boolean[] pathAlwaysNull
            ) {
                this.source = source;
                this.json = json;
                this.paths = paths;
                this.preparsedPaths = preparsedPaths;
                this.pathAlwaysNull = pathAlwaysNull;
            }

            @Override
            public ExpressionEvaluator get(DriverContext context) {
                ExpressionEvaluator jsonEvaluator = json.get(context);
                ExpressionEvaluator[] pathEvaluators = new ExpressionEvaluator[paths.length];
                try {
                    for (int i = 0; i < paths.length; i++) {
                        pathEvaluators[i] = paths[i].get(context);
                    }
                    JsonRemovePathEvaluator evaluator = new JsonRemovePathEvaluator(
                        source,
                        jsonEvaluator,
                        pathEvaluators,
                        preparsedPaths,
                        pathAlwaysNull,
                        context
                    );
                    jsonEvaluator = null;
                    pathEvaluators = null;
                    return evaluator;
                } finally {
                    Releasables.close(
                        jsonEvaluator == null ? () -> {} : jsonEvaluator,
                        pathEvaluators == null ? () -> {} : Releasables.wrap(pathEvaluators)
                    );
                }
            }

            @Override
            public String toString() {
                return "JsonRemovePathEvaluator[paths=" + Arrays.toString(paths) + "]";
            }
        }
    }
}
