/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.string;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.expression.function.AbstractScalarFunctionTestCase;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * MySQL-compatible behavior of the public {@code JSON_SET} and {@code JSON_REMOVE} functions,
 * including the deliberate divergences from the retired internal-only plan nodes:
 * no ancestor pruning, no dotted dual-match, and null setting JSON null.
 */
public class JsonSetRemoveBehaviorTests extends ESTestCase {

    // ---------- JSON_SET ----------

    public void testSetReplacesExistingMember() throws IOException {
        assertEquals("{\"a\":\"new\",\"b\":\"1\"}", set("{\"a\":\"old\",\"b\":\"1\"}", "$.a", "new"));
    }

    public void testSetAddsMissingMember() throws IOException {
        assertEquals("{\"a\":\"1\",\"b\":\"2\"}", set("{\"a\":\"1\"}", "$.b", "2"));
    }

    public void testSetCreatesMissingIntermediateObjects() throws IOException {
        assertEquals("{\"a\":{\"b\":{\"c\":\"v\"}}}", set("{}", "$.a.b.c", "v"));
        assertEquals("{\"a\":{\"b\":\"v\"},\"keep\":\"1\"}", set("{\"keep\":\"1\"}", "$.a.b", "v"));
    }

    public void testSetNullValueSetsJsonNullInsteadOfDeleting() throws IOException {
        assertEquals("{\"a\":null}", set("{\"a\":\"old\"}", "$.a", null));
        assertEquals("{\"a\":null,\"b\":\"1\"}", set("{\"b\":\"1\"}", "$.a", null));
    }

    public void testSetPairsApplyLeftToRight() throws IOException {
        assertEquals("{\"a\":\"second\"}", set(set("{}", "$.a", "first"), "$.a", "second"));
        Map<String, Object> document = JsonSet.readObject(new BytesRef("{}"));
        JsonSet.applySet(document, JsonPath.memberKeys("$.a"), "first");
        JsonSet.applySet(document, JsonPath.memberKeys("$.a"), "second");
        assertEquals("{\"a\":\"second\"}", JsonSet.writeObject(document).utf8ToString());
    }

    public void testSetOutputKeysAreSorted() throws IOException {
        assertEquals("{\"a\":\"1\",\"m\":\"2\",\"z\":\"3\"}", set("{\"z\":\"3\",\"m\":\"2\"}", "$.a", "1"));
        assertEquals("{\"a\":\"1\",\"b\":\"2\",\"c\":\"3\"}", set("{\"b\":\"2\",\"a\":\"1\"}", "$.c", "3"));
    }

    public void testSetQuotedMembersAddressLiteralDottedKeys() throws IOException {
        // Single- and double-quoted bracket forms are equivalent.
        assertEquals("{\"a.b\":\"v\"}", set("{}", "$['a.b']", "v"));
        assertEquals("{\"a.b\":\"v\"}", set("{}", "$[\"a.b\"]", "v"));
    }

    public void testSetNestedPathDoesNotTouchLiteralDottedKey() throws IOException {
        // Deliberate divergence from the internal JsonRemove dual-match: $.a.b only addresses nesting.
        assertEquals("{\"a\":{\"b\":\"v\"},\"a.b\":\"literal\"}", set("{\"a.b\":\"literal\"}", "$.a.b", "v"));
    }

    public void testSetStarIsLiteralMemberNotWildcard() throws IOException {
        // There are no wildcards: a bare "*" addresses a literal member of that name.
        assertEquals("{\"*\":\"v\"}", set("{}", "$.*", "v"));
        assertEquals("{\"ab\":\"keep\"}", remove("{\"ab\":\"keep\"}", "$.a*"));
    }

    public void testSetQuotedPathDoesNotTouchNestedMember() throws IOException {
        assertEquals("{\"a\":{\"b\":\"nested\"},\"a.b\":\"v\"}", set("{\"a\":{\"b\":\"nested\"}}", "$[\"a.b\"]", "v"));
    }

    public void testSetRejectsTraversalThroughNonObject() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> set("{\"a\":\"scalar\"}", "$.a.b", "v"));
        assertTrue(e.getMessage(), e.getMessage().contains("is not an object"));
    }

    public void testSetRejectsArrayIndexPaths() {
        for (String path : List.of("$[0]", "$.a[0]", "$.a[1].b")) {
            IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> set("{\"a\":[]}", path, "v"));
            assertTrue(e.getMessage(), e.getMessage().contains("array index"));
        }
    }

    public void testSetRejectsWholeDocumentPath() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> set("{\"a\":\"1\"}", "$", "v"));
        assertTrue(e.getMessage(), e.getMessage().contains("not the whole document"));
    }

    public void testSetRejectsMalformedPaths() {
        for (String path : List.of("", "$foo", "$.", "$.a.", "$.a..b", "$[", "$.a[", "$[\"unterminated")) {
            expectThrows(IllegalArgumentException.class, () -> set("{\"a\":\"1\"}", path, "v"));
        }
    }

    // ---------- JSON_REMOVE ----------

    public void testRemoveTopLevelMember() throws IOException {
        assertEquals("{\"b\":\"2\"}", remove("{\"a\":\"1\",\"b\":\"2\"}", "$.a"));
    }

    public void testRemoveKeepsEmptiedParentWithoutPruning() throws IOException {
        // Deliberate MySQL parity: MySQL keeps emptied parents, the internal JsonRemove prunes them.
        assertEquals("{\"a\":{}}", remove("{\"a\":{\"b\":\"1\"}}", "$.a.b"));
        assertEquals("{\"a\":{},\"keep\":\"1\"}", remove("{\"a\":{\"b\":\"1\"},\"keep\":\"1\"}", "$.a.b"));
    }

    public void testRemoveMissingPathIsNoOp() throws IOException {
        assertEquals("{\"a\":\"1\"}", remove("{\"a\":\"1\"}", "$.missing"));
        assertEquals("{\"a\":\"1\"}", remove("{\"a\":\"1\"}", "$.missing.deeply.nested"));
        assertEquals("{\"a\":\"1\"}", remove("{\"a\":\"1\"}", "$.a.b"));
    }

    public void testRemoveThroughNonObjectIsNoOp() throws IOException {
        assertEquals("{\"a\":\"scalar\"}", remove("{\"a\":\"scalar\"}", "$.a.b"));
        assertEquals("{\"a\":[\"x\"]}", remove("{\"a\":[\"x\"]}", "$.a.b"));
    }

    public void testRemoveMultiplePaths() throws IOException {
        assertEquals("{\"b\":\"2\"}", remove("{\"a\":\"1\",\"b\":\"2\",\"c\":\"3\"}", "$.a", "$.c"));
    }

    public void testRemoveNestedPathDoesNotTouchLiteralDottedKey() throws IOException {
        // Deliberate divergence from the internal JsonRemove dual-match: $.labels.instance only
        // addresses nesting, it never also drops a literal "labels.instance" member.
        assertEquals(
            "{\"labels\":{\"job\":\"api\"},\"labels.instance\":\"b\"}",
            remove("{\"labels\":{\"instance\":\"a\",\"job\":\"api\"},\"labels.instance\":\"b\"}", "$.labels.instance")
        );
    }

    public void testRemoveQuotedPathRemovesOnlyLiteralDottedKey() throws IOException {
        assertEquals(
            "{\"labels\":{\"instance\":\"a\"}}",
            remove("{\"labels\":{\"instance\":\"a\"},\"labels.instance\":\"b\"}", "$[\"labels.instance\"]")
        );
        assertEquals(
            "{\"labels\":{\"instance\":\"a\"}}",
            remove("{\"labels\":{\"instance\":\"a\"},\"labels.instance\":\"b\"}", "$['labels.instance']")
        );
    }

    public void testRemoveIsExactNotWildcardOrPrefix() throws IOException {
        assertEquals("{\"ab\":\"keep\",\"other\":null}", remove("{\"ab\":\"keep\",\"other\":null}", "$.a*"));
        assertEquals("{\"a\":\"1\"}", remove("{\"a\":\"1\"}", "$.ab"));
    }

    public void testRemoveRetainsEmptyNullAndMultivalueMembers() throws IOException {
        assertEquals(
            "{\"empty\":\"\",\"null\":null,\"values\":[\"a\",\"b\"]}",
            remove("{\"values\":[\"a\",\"b\"],\"null\":null,\"empty\":\"\",\"gone\":1}", "$.gone")
        );
    }

    public void testRemoveRejectsArrayIndexPaths() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> remove("{\"a\":[\"x\"]}", "$.a[0]"));
        assertTrue(e.getMessage(), e.getMessage().contains("array index"));
    }

    public void testRemoveRejectsWholeDocumentPath() {
        expectThrows(IllegalArgumentException.class, () -> remove("{\"a\":\"1\"}", "$"));
    }

    // ---------- shared strictness ----------

    public void testRejectTrailingContent() {
        expectThrows(IllegalArgumentException.class, () -> remove("{}{}", "$.a"));
        expectThrows(IllegalArgumentException.class, () -> set("{}{}", "$.a", "v"));
    }

    public void testRejectNonObjectsAndMalformedJson() {
        for (String input : List.of("[]", "null", "\"value\"", "1", "", "{\"a\":}")) {
            expectThrows(IllegalArgumentException.class, () -> remove(input, "$.a"));
            expectThrows(IllegalArgumentException.class, () -> set(input, "$.a", "v"));
        }
    }

    public void testStringsAreEscapedByTheJsonWriter() throws IOException {
        String input = "{\"quote\\\"key\":\"newline\\nbackslash\\\\\",\"other\":\"1\"}";
        assertEquals("{\"added\":\"v\",\"other\":\"1\",\"quote\\\"key\":\"newline\\nbackslash\\\\\"}", set(input, "$.added", "v"));
        assertEquals("{\"quote\\\"key\":\"newline\\nbackslash\\\\\"}", remove(input, "$.other"));
    }

    public void testBarePathsMatchJsonExtractConventions() throws IOException {
        assertEquals("{\"a\":{\"b\":\"v\"}}", set("{}", "a.b", "v"));
        assertEquals("{\"b\":\"2\"}", remove("{\"a\":\"1\",\"b\":\"2\"}", "a"));
    }

    // ---------- evaluator composition ----------

    public void testGeneratedEvaluatorsComposeNullsAndWarnings() {
        var bigArrays = new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, ByteSizeValue.ofMb(32)).withCircuitBreaking();
        CircuitBreaker breaker = bigArrays.breakerService().getBreaker(CircuitBreaker.REQUEST);
        BlockFactory blocks = BlockFactory.builder(bigArrays).build();
        DriverContext context = new DriverContext(bigArrays, blocks, null);
        var field = new FieldAttribute(
            Source.EMPTY,
            "json",
            new EsField("json", DataType.KEYWORD, Map.of(), true, EsField.TimeSeriesFieldType.NONE)
        );
        var edit = new JsonSet(
            Source.EMPTY,
            List.of(
                new JsonRemovePath(Source.EMPTY, List.of(field, Literal.keyword(Source.EMPTY, "$.drop"))),
                Literal.keyword(Source.EMPTY, "$.new"),
                Literal.keyword(Source.EMPTY, "value")
            )
        );
        try (
            var evaluator = AbstractScalarFunctionTestCase.evaluator(edit).get(context);
            BytesRefBlock.Builder builder = blocks.newBytesRefBlockBuilder(5)
        ) {
            builder.appendBytesRef(new BytesRef("{\"drop\":\"a\",\"keep\":\"b\"}"));
            builder.appendNull();
            builder.appendBytesRef(new BytesRef("{\"drop\":\"a\",\"keep\":\"b\"}"));
            builder.appendBytesRef(new BytesRef("{}"));
            builder.appendBytesRef(new BytesRef("not json"));
            Page page = new Page(builder.build());
            try (Block output = evaluator.eval(page)) {
                BytesRefBlock result = (BytesRefBlock) output;
                assertEquals(
                    "{\"keep\":\"b\",\"new\":\"value\"}",
                    result.getBytesRef(result.getFirstValueIndex(0), new BytesRef()).utf8ToString()
                );
                assertTrue(result.isNull(1));
                assertEquals(
                    result.getBytesRef(result.getFirstValueIndex(0), new BytesRef()),
                    result.getBytesRef(result.getFirstValueIndex(2), new BytesRef())
                );
                assertEquals("{\"new\":\"value\"}", result.getBytesRef(result.getFirstValueIndex(3), new BytesRef()).utf8ToString());
                // Malformed JSON nulls the row and records a warning instead of failing the query.
                assertTrue(result.isNull(4));
            } finally {
                page.releaseBlocks();
            }
        } finally {
            context.finish();
        }
        assertFalse(context.warnings().isEmpty());
        assertEquals(0L, breaker.getUsed());
    }

    public void testEvaluatorNullPathNullsRowAndNullValueSetsJsonNull() {
        var bigArrays = new MockBigArrays(PageCacheRecycler.NON_RECYCLING_INSTANCE, ByteSizeValue.ofMb(32)).withCircuitBreaking();
        BlockFactory blocks = BlockFactory.builder(bigArrays).build();
        DriverContext context = new DriverContext(bigArrays, blocks, null);
        var field = new FieldAttribute(
            Source.EMPTY,
            "json",
            new EsField("json", DataType.KEYWORD, Map.of(), true, EsField.TimeSeriesFieldType.NONE)
        );
        var nullPath = new JsonSet(
            Source.EMPTY,
            List.of(
                field,
                Literal.keyword(Source.EMPTY, "$.a"),
                Literal.keyword(Source.EMPTY, "v"),
                Literal.NULL,
                Literal.keyword(Source.EMPTY, "x")
            )
        );
        try (
            var evaluator = AbstractScalarFunctionTestCase.evaluator(nullPath).get(context);
            BytesRefBlock.Builder builder = blocks.newBytesRefBlockBuilder(1)
        ) {
            // A null path nulls the whole row, mirroring MySQL.
            builder.appendBytesRef(new BytesRef("{\"a\":\"old\"}"));
            Page page = new Page(builder.build());
            try (Block output = evaluator.eval(page)) {
                assertTrue(((BytesRefBlock) output).isNull(0));
            } finally {
                page.releaseBlocks();
            }
        } finally {
            context.finish();
        }
        var nullValue = new JsonSet(Source.EMPTY, List.of(field, Literal.keyword(Source.EMPTY, "$.b"), Literal.NULL));
        try (
            var evaluator = AbstractScalarFunctionTestCase.evaluator(nullValue).get(context);
            BytesRefBlock.Builder builder = blocks.newBytesRefBlockBuilder(1)
        ) {
            builder.appendBytesRef(new BytesRef("{\"a\":\"old\"}"));
            Page page = new Page(builder.build());
            try (Block output = evaluator.eval(page)) {
                BytesRefBlock result = (BytesRefBlock) output;
                assertEquals("{\"a\":\"old\",\"b\":null}", result.getBytesRef(result.getFirstValueIndex(0), new BytesRef()).utf8ToString());
            } finally {
                page.releaseBlocks();
            }
        } finally {
            context.finish();
        }
    }

    private static String set(String json, String path, String value) throws IOException {
        Map<String, Object> document = JsonSet.readObject(new BytesRef(json));
        JsonSet.applySet(document, JsonPath.memberKeys(path), value);
        return JsonSet.writeObject(document).utf8ToString();
    }

    private static String remove(String json, String... paths) throws IOException {
        Map<String, Object> document = JsonSet.readObject(new BytesRef(json));
        for (String path : paths) {
            JsonRemovePath.applyRemove(document, JsonPath.memberKeys(path));
        }
        return JsonSet.writeObject(document).utf8ToString();
    }

}
