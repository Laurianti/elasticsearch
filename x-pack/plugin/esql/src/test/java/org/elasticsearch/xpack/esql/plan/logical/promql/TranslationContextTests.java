/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.MetadataAttribute;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.grouping.TimeSeriesWithout;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.Project;
import org.elasticsearch.xpack.esql.plan.logical.local.EmptyLocalSupplier;
import org.elasticsearch.xpack.esql.plan.logical.local.LocalRelation;
import org.elasticsearch.xpack.esql.plan.logical.promql.TranslationContext.IntermediateResult;

import java.util.List;
import java.util.Set;

import static org.elasticsearch.xpack.esql.plan.logical.promql.TranslationConstraint.finite;
import static org.elasticsearch.xpack.esql.plan.logical.promql.TranslationConstraint.intersect;
import static org.elasticsearch.xpack.esql.plan.logical.promql.TranslationConstraint.open;
import static org.elasticsearch.xpack.esql.plan.logical.promql.TranslationConstraint.project;
import static org.elasticsearch.xpack.esql.plan.logical.promql.TranslationConstraint.subtract;
import static org.elasticsearch.xpack.esql.plan.logical.promql.TranslationConstraint.union;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

public class TranslationContextTests extends ESTestCase {

    public void testUnionMergesLabelsAndSkipSets() {
        TranslationConstraint header = union(
            union(union(finite(List.of("cluster")), open(Set.of("pod"))), open(Set.of("pod"))),
            finite(List.of("cluster", "region"))
        );

        assertThat(header.labels(), contains("cluster", "region"));
        assertThat(header.skips(), contains(Set.of("pod")));
        assertThat(union(header, TranslationConstraint.EMPTY), equalTo(header));
    }

    public void testSubtractDropsLabelsAndWidensSkipSets() {
        TranslationConstraint above = union(finite(List.of("cluster", "pod")), open(Set.of("region")));

        TranslationConstraint below = subtract(above, List.of("pod"));
        assertThat(below.labels(), contains("cluster"));
        assertThat(below.skips(), contains(Set.of("region", "pod")));

        // the regroup's own column composes as a second, finer skip set
        TranslationConstraint child = union(below, open(Set.of("pod")));
        assertThat(child.skips(), containsInAnyOrder(Set.of("region", "pod"), Set.of("pod")));
    }

    public void testIntersectIsTheUpwardCounterpartOfSubtract() {
        TranslationConstraint required = union(finite(List.of("cluster", "pod")), open(Set.of("region")));
        TranslationConstraint child = union(subtract(required, List.of("pod")), open(Set.of("pod")));

        TranslationConstraint lifted = intersect(child, List.of("pod"));

        // every column the parent required, apart from the dropped label, comes back; so does the regroup's own
        // packing, which already excludes the dropped label and fixes the grain of the result
        assertThat(lifted.labels(), contains("cluster"));
        assertThat(lifted.skips(), containsInAnyOrder(Set.of("region", "pod"), Set.of("pod")));
        // the regroup's own full label space does not survive dropping a label it still carries
        assertFalse(intersect(open(), List.of("pod")).isOpen());
        // without () keeps everything
        assertThat(intersect(child, List.of()), equalTo(child));
    }

    public void testProjectKeepsSkipSets() {
        TranslationConstraint header = union(finite(List.of("cluster", "pod", "region")), open(Set.of("pod")));

        TranslationConstraint retained = project(header, List.of("cluster", "missing"));

        assertThat(retained.labels(), contains("cluster"));
        assertThat(retained.skips(), contains(Set.of("pod")));
    }

    public void testPackedNameDerivesFromTheSkipSet() {
        assertThat(TranslationContext.mapOpen(Set.of()), equalTo(MetadataAttribute.TIMESERIES));
        assertThat(TranslationContext.mapOpen(Set.of("region", "pod")), equalTo(MetadataAttribute.TIMESERIES + "$pod$region"));
        assertThat(TranslationContext.mapOpen(Set.of("pod", "region")), equalTo(TranslationContext.mapOpen(Set.of("region", "pod"))));
    }

    public void testFindByNameMatchesCanonicalNamesAndPrefersPassthroughFields() {
        Attribute bare = attr("cluster");
        Attribute prefixed = new ReferenceAttribute(Source.EMPTY, "labels.cluster", DataType.KEYWORD);
        Attribute packed = attr(TranslationContext.mapOpen(Set.of("pod")));

        assertThat(TranslationContext.find(List.of(bare, prefixed), "cluster"), sameInstance(prefixed));
        assertThat(TranslationContext.find(List.of(bare), "cluster"), sameInstance(bare));
        assertThat(TranslationContext.find(List.of(bare, packed), TranslationContext.mapOpen(Set.of("pod"))), sameInstance(packed));
        assertNull(TranslationContext.find(List.of(bare), "pod"));
        assertThat(TranslationContext.mapFinite(List.of(bare, prefixed, attr("pod"))), contains("cluster", "pod"));
    }

    public void testUnionAllowsRestToOverlapPromoted() {
        TranslationConstraint header = union(finite(List.of("pod")), open(Set.of()));

        assertThat(header.labels(), contains("pod"));
        assertThat(header.skips(), contains(Set.of()));
    }

    public void testMultipleRestsCoexist() {
        TranslationConstraint header = union(open(Set.of()), open(Set.of("pod")));

        assertThat(header.skips(), containsInAnyOrder(Set.of(), Set.of("pod")));
    }

    public void testDeliveredLabelsExcludesStepValueAndPackings() {
        Attribute step = attr("step");
        Attribute value = attr("value");
        Attribute pod = attr("pod");
        Attribute prefixed = new ReferenceAttribute(Source.EMPTY, "labels.cluster", DataType.KEYWORD);
        var packing = packing(Set.of("region"));
        var plan = new Aggregate(
            Source.EMPTY,
            new LocalRelation(Source.EMPTY, List.of(step, value, pod, prefixed), EmptyLocalSupplier.EMPTY),
            List.of(step, packing, pod, prefixed),
            List.of(value, step, packing.toAttribute(), pod, prefixed)
        );

        assertThat(TranslationContext.deliveredLabels(plan, step, value), containsInAnyOrder("pod", "cluster"));
    }

    public void testDeliveredSkipsReadsPackingDefinitions() {
        Attribute step = attr("step");
        Attribute value = attr("value");
        var open = packing(Set.of());
        var pod = packing(Set.of("pod"));
        var plan = new Aggregate(
            Source.EMPTY,
            new LocalRelation(Source.EMPTY, List.of(step, value), EmptyLocalSupplier.EMPTY),
            List.of(step, open, pod),
            List.of(value, step, open.toAttribute(), pod.toAttribute())
        );

        assertThat(TranslationContext.deliveredSkips(plan), containsInAnyOrder(Set.of(), Set.of("pod")));

        // a packing projected away is not carried, even though its definition sits below
        var projected = new Project(Source.EMPTY, plan, List.of(value, step, open.toAttribute()));
        assertThat(TranslationContext.deliveredSkips(projected), contains(Set.of()));
    }

    public void testFinestPackingPicksFewestExclusions() {
        Attribute step = attr("step");
        Attribute value = attr("value");
        var open = packing(Set.of());
        var pod = packing(Set.of("pod"));
        var plan = new Aggregate(
            Source.EMPTY,
            new LocalRelation(Source.EMPTY, List.of(step, value), EmptyLocalSupplier.EMPTY),
            List.of(step, pod, open),
            List.of(value, step, pod.toAttribute(), open.toAttribute())
        );

        assertThat(TranslationContext.finestPacking(plan).name(), equalTo(TranslationContext.mapOpen(Set.of())));

        var unpacked = new LocalRelation(Source.EMPTY, List.of(value, step), EmptyLocalSupplier.EMPTY);
        assertNull(TranslationContext.finestPacking(unpacked));
    }

    public void testIntermediateResultRebuildsAroundNewPlanAndValue() {
        Attribute step = attr("step");
        Attribute value = attr("value");
        var plan = new LocalRelation(Source.EMPTY, List.of(value, step), EmptyLocalSupplier.EMPTY);
        var table = new IntermediateResult(plan, value, step, Literal.TRUE);

        assertThat(table.valueColumn(), sameInstance(value));
        assertThat(table.pendingFilter(), sameInstance(Literal.TRUE));
        assertThat(table.kind(), equalTo(IntermediateResult.Kind.BEFORE_INITIAL_AGGREGATE));

        var next = new LocalRelation(Source.EMPTY, List.of(value, step), EmptyLocalSupplier.EMPTY);
        var rebuilt = table.with(next, Literal.NULL);
        assertThat(rebuilt.plan(), sameInstance(next));
        assertThat(rebuilt.value(), sameInstance(Literal.NULL));
        assertThat(rebuilt.step(), sameInstance(step));
        assertThat(rebuilt.pendingFilter(), sameInstance(Literal.TRUE));
    }

    private static Alias packing(Set<String> skip) {
        List<Expression> excluded = skip.stream().<Expression>map(TranslationContextTests::attr).toList();
        return new Alias(Source.EMPTY, TranslationContext.mapOpen(skip), new TimeSeriesWithout(Source.EMPTY, excluded));
    }

    private static Attribute attr(String name) {
        return new ReferenceAttribute(Source.EMPTY, null, name, DataType.KEYWORD);
    }
}
