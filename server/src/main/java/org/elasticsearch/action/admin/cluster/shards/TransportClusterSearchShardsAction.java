/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.admin.cluster.shards;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeReadAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver.ResolvedExpression;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.search.internal.AliasFilter;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TransportClusterSearchShardsAction extends TransportMasterNodeReadAction<
    ClusterSearchShardsRequest,
    ClusterSearchShardsResponse> {

    public static final ActionType<ClusterSearchShardsResponse> TYPE = new ActionType<>("indices:admin/shards/search_shards");

    private final IndicesService indicesService;
    private final ProjectResolver projectResolver;
    private final IndexNameExpressionResolver indexNameExpressionResolver;

    @Inject
    public TransportClusterSearchShardsAction(
        TransportService transportService,
        ClusterService clusterService,
        IndicesService indicesService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        ProjectResolver projectResolver,
        IndexNameExpressionResolver indexNameExpressionResolver
    ) {
        super(
            TYPE.name(),
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            ClusterSearchShardsRequest::new,
            ClusterSearchShardsResponse::new,
            threadPool.executor(ThreadPool.Names.SEARCH_COORDINATION)
        );
        this.indicesService = indicesService;
        this.projectResolver = projectResolver;
        this.indexNameExpressionResolver = indexNameExpressionResolver;
    }

    @Override
    protected ClusterBlockException checkBlock(ClusterSearchShardsRequest request, ClusterState state) {
        final ProjectMetadata projectMetadata = projectResolver.getProjectMetadata(state);
        return state.blocks()
            .indicesBlockedException(
                projectMetadata.id(),
                ClusterBlockLevel.METADATA_READ,
                indexNameExpressionResolver.concreteIndexNames(projectMetadata, request)
            );
    }

    @Override
    protected void masterOperation(
        Task task,
        final ClusterSearchShardsRequest request,
        final ClusterState state,
        final ActionListener<ClusterSearchShardsResponse> listener
    ) {
        ClusterState clusterState = clusterService.state();
        ProjectState project = projectResolver.getProjectState(clusterState);
        String[] concreteIndices = indexNameExpressionResolver.concreteIndexNames(project.metadata(), request);
        Map<String, Set<String>> routingMap = indexNameExpressionResolver.resolveSearchRouting(
            project.metadata(),
            request.routing(),
            request.indices()
        );
        Map<String, AliasFilter> indicesAndFilters = new HashMap<>();
        Set<ResolvedExpression> indicesAndAliases = indexNameExpressionResolver.resolveExpressions(project.metadata(), request.indices());
        for (String index : concreteIndices) {
            final AliasFilter aliasFilter = indicesService.buildAliasFilter(project, index, indicesAndAliases);
            final String[] aliases = indexNameExpressionResolver.allIndexAliases(project.metadata(), index, indicesAndAliases);
            indicesAndFilters.put(index, AliasFilter.of(aliasFilter.getQueryBuilder(), aliases));
        }

        Set<String> nodeIds = new HashSet<>();
        List<ShardIterator> groupShardsIterator = clusterService.operationRouting()
            .searchShards(project, concreteIndices, routingMap, request.preference());
        ShardRouting shard;
        ClusterSearchShardsGroup[] groupResponses = new ClusterSearchShardsGroup[groupShardsIterator.size()];
        int currentGroup = 0;
        for (ShardIterator shardIt : groupShardsIterator) {
            ShardId shardId = shardIt.shardId();
            ShardRouting[] shardRoutings = new ShardRouting[shardIt.size()];
            int currentShard = 0;
            shardIt.reset();
            while ((shard = shardIt.nextOrNull()) != null) {
                shardRoutings[currentShard++] = shard;
                nodeIds.add(shard.currentNodeId());
            }
            groupResponses[currentGroup++] = new ClusterSearchShardsGroup(shardId, shardRoutings);
        }
        DiscoveryNode[] nodes = new DiscoveryNode[nodeIds.size()];
        int currentNode = 0;
        for (String nodeId : nodeIds) {
            nodes[currentNode++] = clusterState.getNodes().get(nodeId);
        }
        listener.onResponse(new ClusterSearchShardsResponse(groupResponses, nodes, indicesAndFilters));
    }
}
