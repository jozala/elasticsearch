/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.datastreams.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.datastreams.CreateDataStreamAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.AcknowledgedTransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.MetadataCreateDataStreamService;
import org.elasticsearch.cluster.project.ProjectResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.indices.SystemDataStreamDescriptor;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

public class TransportCreateDataStreamAction extends AcknowledgedTransportMasterNodeAction<CreateDataStreamAction.Request> {

    private final MetadataCreateDataStreamService metadataCreateDataStreamService;
    private final SystemIndices systemIndices;
    private final ProjectResolver projectResolver;

    @Inject
    public TransportCreateDataStreamAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        ProjectResolver projectResolver,
        MetadataCreateDataStreamService metadataCreateDataStreamService,
        SystemIndices systemIndices
    ) {
        super(
            CreateDataStreamAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            CreateDataStreamAction.Request::new,
            EsExecutors.DIRECT_EXECUTOR_SERVICE
        );
        this.projectResolver = projectResolver;
        this.metadataCreateDataStreamService = metadataCreateDataStreamService;
        this.systemIndices = systemIndices;
    }

    @Override
    protected void masterOperation(
        Task task,
        CreateDataStreamAction.Request request,
        ClusterState state,
        ActionListener<AcknowledgedResponse> listener
    ) throws Exception {
        final SystemDataStreamDescriptor systemDataStreamDescriptor = systemIndices.validateDataStreamAccess(
            request.getName(),
            threadPool.getThreadContext()
        );
        MetadataCreateDataStreamService.CreateDataStreamClusterStateUpdateRequest updateRequest =
            new MetadataCreateDataStreamService.CreateDataStreamClusterStateUpdateRequest(
                projectResolver.getProjectId(),
                request.getName(),
                request.getStartTime(),
                systemDataStreamDescriptor,
                request.masterNodeTimeout(),
                request.ackTimeout(),
                true
            );
        metadataCreateDataStreamService.createDataStream(updateRequest, listener);
    }

    @Override
    protected ClusterBlockException checkBlock(CreateDataStreamAction.Request request, ClusterState state) {
        return state.blocks().globalBlockedException(projectResolver.getProjectId(), ClusterBlockLevel.METADATA_WRITE);
    }
}
