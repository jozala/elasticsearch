/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.vector;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.xpack.esql.EsqlIllegalArgumentException;
import org.elasticsearch.xpack.esql.capabilities.PostAnalysisPlanVerificationAware;
import org.elasticsearch.xpack.esql.capabilities.TranslationAware;
import org.elasticsearch.xpack.esql.common.Failures;
import org.elasticsearch.xpack.esql.core.InvalidArgumentException;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.MapExpression;
import org.elasticsearch.xpack.esql.core.expression.TypeResolutions;
import org.elasticsearch.xpack.esql.core.querydsl.query.Query;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.util.Check;
import org.elasticsearch.xpack.esql.expression.function.Example;
import org.elasticsearch.xpack.esql.expression.function.FunctionAppliesTo;
import org.elasticsearch.xpack.esql.expression.function.FunctionAppliesToLifecycle;
import org.elasticsearch.xpack.esql.expression.function.FunctionInfo;
import org.elasticsearch.xpack.esql.expression.function.MapParam;
import org.elasticsearch.xpack.esql.expression.function.OptionalArgument;
import org.elasticsearch.xpack.esql.expression.function.Options;
import org.elasticsearch.xpack.esql.expression.function.Param;
import org.elasticsearch.xpack.esql.expression.function.fulltext.FullTextFunction;
import org.elasticsearch.xpack.esql.expression.function.fulltext.Match;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;
import org.elasticsearch.xpack.esql.optimizer.rules.physical.local.LucenePushdownPredicates;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.planner.TranslatorHandler;
import org.elasticsearch.xpack.esql.querydsl.query.KnnQuery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import static java.util.Map.entry;
import static org.elasticsearch.common.logging.LoggerMessageFormat.format;
import static org.elasticsearch.index.query.AbstractQueryBuilder.BOOST_FIELD;
import static org.elasticsearch.search.vectors.KnnVectorQueryBuilder.K_FIELD;
import static org.elasticsearch.search.vectors.KnnVectorQueryBuilder.NUM_CANDS_FIELD;
import static org.elasticsearch.search.vectors.KnnVectorQueryBuilder.VECTOR_SIMILARITY_FIELD;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.FIRST;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.FOURTH;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.SECOND;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.THIRD;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isFoldable;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isNotNull;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isType;
import static org.elasticsearch.xpack.esql.core.type.DataType.DENSE_VECTOR;
import static org.elasticsearch.xpack.esql.core.type.DataType.FLOAT;
import static org.elasticsearch.xpack.esql.core.type.DataType.INTEGER;
import static org.elasticsearch.xpack.esql.expression.function.FunctionUtils.TypeResolutionValidator.forPreOptimizationValidation;
import static org.elasticsearch.xpack.esql.expression.function.FunctionUtils.resolveTypeQuery;

public class Knn extends FullTextFunction implements OptionalArgument, VectorFunction, PostAnalysisPlanVerificationAware {
    private final Logger log = LogManager.getLogger(getClass());

    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(Expression.class, "Knn", Knn::readFrom);

    private final Expression field;
    // k is not serialized as it's already included in the query builder on the rewrite step before being sent to data nodes
    private final transient Expression k;
    private final Expression options;
    // Expressions to be used as prefilters in knn query
    private final List<Expression> filterExpressions;

    public static final Map<String, DataType> ALLOWED_OPTIONS = Map.ofEntries(
        entry(NUM_CANDS_FIELD.getPreferredName(), INTEGER),
        entry(VECTOR_SIMILARITY_FIELD.getPreferredName(), FLOAT),
        entry(BOOST_FIELD.getPreferredName(), FLOAT),
        entry(KnnQuery.RESCORE_OVERSAMPLE_FIELD, FLOAT)
    );

    @FunctionInfo(
        returnType = "boolean",
        preview = true,
        description = "Finds the k nearest vectors to a query vector, as measured by a similarity metric. "
            + "knn function finds nearest vectors through approximate search on indexed dense_vectors.",
        examples = { @Example(file = "knn-function", tag = "knn-function") },
        appliesTo = { @FunctionAppliesTo(lifeCycle = FunctionAppliesToLifecycle.DEVELOPMENT) }
    )
    public Knn(
        Source source,
        @Param(name = "field", type = { "dense_vector" }, description = "Field that the query will target.") Expression field,
        @Param(
            name = "query",
            type = { "dense_vector" },
            description = "Vector value to find top nearest neighbours for."
        ) Expression query,
        @Param(
            name = "k",
            type = { "integer" },
            description = "The number of nearest neighbors to return from each shard. "
                + "Elasticsearch collects k results from each shard, then merges them to find the global top results. "
                + "This value must be less than or equal to num_candidates."
        ) Expression k,
        @MapParam(
            name = "options",
            params = {
                @MapParam.MapParamEntry(
                    name = "boost",
                    type = "float",
                    valueHint = { "2.5" },
                    description = "Floating point number used to decrease or increase the relevance scores of the query."
                        + "Defaults to 1.0."
                ),
                @MapParam.MapParamEntry(
                    name = "num_candidates",
                    type = "integer",
                    valueHint = { "10" },
                    description = "The number of nearest neighbor candidates to consider per shard while doing knn search. "
                        + "Cannot exceed 10,000. Increasing num_candidates tends to improve the accuracy of the final results. "
                        + "Defaults to 1.5 * k"
                ),
                @MapParam.MapParamEntry(
                    name = "similarity",
                    type = "double",
                    valueHint = { "0.01" },
                    description = "The minimum similarity required for a document to be considered a match. "
                        + "The similarity value calculated relates to the raw similarity used, not the document score."
                ),
                @MapParam.MapParamEntry(
                    name = "rescore_oversample",
                    type = "double",
                    valueHint = { "3.5" },
                    description = "Applies the specified oversampling for rescoring quantized vectors. "
                        + "See [oversampling and rescoring quantized vectors]"
                        + "(docs-content://solutions/search/vector/knn.md#dense-vector-knn-search-rescoring) for details."
                ), },
            description = "(Optional) kNN additional options as <<esql-function-named-params,function named parameters>>."
                + " See <<query-dsl-knn-query,knn query>> for more information.",
            optional = true
        ) Expression options
    ) {
        this(source, field, query, k, options, null, List.of());
    }

    public Knn(
        Source source,
        Expression field,
        Expression query,
        Expression k,
        Expression options,
        QueryBuilder queryBuilder,
        List<Expression> filterExpressions
    ) {
        super(source, query, expressionList(field, query, k, options), queryBuilder);
        this.field = field;
        this.k = k;
        this.options = options;
        this.filterExpressions = filterExpressions;
    }

    private static List<Expression> expressionList(Expression field, Expression query, Expression k, Expression options) {
        List<Expression> result = new ArrayList<>();
        result.add(field);
        result.add(query);
        if (k != null) {
            result.add(k);
        }
        if (options != null) {
            result.add(options);
        }
        return result;
    }

    public Expression field() {
        return field;
    }

    public Expression k() {
        return k;
    }

    public Expression options() {
        return options;
    }

    public List<Expression> filterExpressions() {
        return filterExpressions;
    }

    @Override
    public DataType dataType() {
        return DataType.BOOLEAN;
    }

    @Override
    protected TypeResolution resolveParams() {
        return resolveField().and(resolveQuery()).and(resolveK()).and(Options.resolve(options(), source(), FOURTH, ALLOWED_OPTIONS));
    }

    private TypeResolution resolveField() {
        return isNotNull(field(), sourceText(), FIRST).and(isType(field(), dt -> dt == DENSE_VECTOR, sourceText(), FIRST, "dense_vector"));
    }

    private TypeResolution resolveQuery() {
        TypeResolution result = isType(query(), dt -> dt == DENSE_VECTOR, sourceText(), TypeResolutions.ParamOrdinal.SECOND, "dense_vector")
            .and(isNotNull(query(), sourceText(), SECOND));
        if (result.unresolved()) {
            return result;
        }
        result = resolveTypeQuery(query(), sourceText(), forPreOptimizationValidation(query()));
        if (result.equals(TypeResolution.TYPE_RESOLVED) == false) {
            return result;
        }
        return TypeResolution.TYPE_RESOLVED;
    }

    private TypeResolution resolveK() {
        if (k == null) {
            // Function has already been rewritten and included in QueryBuilder - otherwise parsing would have failed
            return TypeResolution.TYPE_RESOLVED;
        }

        return isType(k(), dt -> dt == INTEGER, sourceText(), THIRD, "integer").and(isFoldable(k(), sourceText(), THIRD))
            .and(isNotNull(k(), sourceText(), THIRD));
    }

    public List<Number> queryAsObject() {
        // we need to check that we got a list and every element in the list is a number
        Expression query = query();
        if (query instanceof Literal literal) {
            @SuppressWarnings("unchecked")
            List<Number> result = ((List<Number>) literal.value());
            return result;
        }
        throw new EsqlIllegalArgumentException(format(null, "Query value must be a list of numbers in [{}], found [{}]", source(), query));
    }

    int getKIntValue() {
        if (k() instanceof Literal literal) {
            return (int) (Number) literal.value();
        }
        throw new EsqlIllegalArgumentException(format(null, "K value must be a constant integer in [{}], found [{}]", source(), k()));
    }

    @Override
    public Expression replaceQueryBuilder(QueryBuilder queryBuilder) {
        return new Knn(source(), field(), query(), k(), options(), queryBuilder, filterExpressions());
    }

    @Override
    public Translatable translatable(LucenePushdownPredicates pushdownPredicates) {
        Translatable translatable = super.translatable(pushdownPredicates);
        // We need to check whether filter expressions are translatable as well
        for (Expression filterExpression : filterExpressions()) {
            translatable = translatable.merge(TranslationAware.translatable(filterExpression, pushdownPredicates));
        }

        return translatable;
    }

    @Override
    protected Query translate(LucenePushdownPredicates pushdownPredicates, TranslatorHandler handler) {
        var fieldAttribute = Match.fieldAsFieldAttribute(field());

        Check.notNull(fieldAttribute, "Knn must have a field attribute as the first argument");
        String fieldName = getNameFromFieldAttribute(fieldAttribute);
        List<Number> queryFolded = queryAsObject();
        float[] queryAsFloats = new float[queryFolded.size()];
        for (int i = 0; i < queryFolded.size(); i++) {
            queryAsFloats[i] = queryFolded.get(i).floatValue();
        }
        int kValue = getKIntValue();

        Map<String, Object> opts = queryOptions();
        opts.put(K_FIELD.getPreferredName(), kValue);

        List<QueryBuilder> filterQueries = new ArrayList<>();
        for (Expression filterExpression : filterExpressions()) {
            if (filterExpression instanceof TranslationAware translationAware) {
                // We can only translate filter expressions that are translatable. In case any is not translatable,
                // Knn won't be pushed down as it will not be translatable so it's safe not to translate all filters and check them
                // when creating an evaluator for the non-pushed down query
                if (translationAware.translatable(pushdownPredicates) == Translatable.YES) {
                    filterQueries.add(handler.asQuery(pushdownPredicates, filterExpression).toQueryBuilder());
                }
            }
        }

        return new KnnQuery(source(), fieldName, queryAsFloats, opts, filterQueries);
    }

    public Expression withFilters(List<Expression> filterExpressions) {
        return new Knn(source(), field(), query(), k(), options(), queryBuilder(), filterExpressions);
    }

    private Map<String, Object> queryOptions() throws InvalidArgumentException {
        Map<String, Object> options = new HashMap<>();
        if (options() != null) {
            Options.populateMap((MapExpression) options(), options, source(), FOURTH, ALLOWED_OPTIONS);
        }
        return options;
    }

    @Override
    public BiConsumer<LogicalPlan, Failures> postAnalysisPlanVerification() {
        return (plan, failures) -> {
            super.postAnalysisPlanVerification().accept(plan, failures);
            fieldVerifier(plan, this, field, failures);
        };
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new Knn(
            source(),
            newChildren.get(0),
            newChildren.get(1),
            newChildren.get(2),
            newChildren.size() > 3 ? newChildren.get(3) : null,
            queryBuilder(),
            filterExpressions()
        );
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, Knn::new, field(), query(), k(), options(), queryBuilder(), filterExpressions());
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    private static Knn readFrom(StreamInput in) throws IOException {
        Source source = Source.readFrom((PlanStreamInput) in);
        Expression field = in.readNamedWriteable(Expression.class);
        Expression query = in.readNamedWriteable(Expression.class);
        QueryBuilder queryBuilder = in.readOptionalNamedWriteable(QueryBuilder.class);
        List<Expression> filterExpressions = in.readNamedWriteableCollectionAsList(Expression.class);
        return new Knn(source, field, query, null, null, queryBuilder, filterExpressions);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        source().writeTo(out);
        out.writeNamedWriteable(field());
        out.writeNamedWriteable(query());
        out.writeOptionalNamedWriteable(queryBuilder());
        out.writeNamedWriteableCollection(filterExpressions());
    }

    @Override
    public boolean equals(Object o) {
        // Knn does not serialize options, as they get included in the query builder. We need to override equals and hashcode to
        // ignore options when comparing two Knn functions
        if (o == null || getClass() != o.getClass()) return false;
        Knn knn = (Knn) o;
        return Objects.equals(field(), knn.field())
            && Objects.equals(query(), knn.query())
            && Objects.equals(queryBuilder(), knn.queryBuilder())
            && Objects.equals(k(), knn.k())
            && Objects.equals(filterExpressions(), knn.filterExpressions());
    }

    @Override
    public int hashCode() {
        return Objects.hash(field(), query(), queryBuilder(), k(), filterExpressions());
    }

}
