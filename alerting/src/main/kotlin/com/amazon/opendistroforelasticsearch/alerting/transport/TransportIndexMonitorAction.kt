/*
 *   Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.alerting.transport

import com.amazon.opendistroforelasticsearch.alerting.action.IndexMonitorAction
import com.amazon.opendistroforelasticsearch.alerting.action.IndexMonitorRequest
import com.amazon.opendistroforelasticsearch.alerting.action.IndexMonitorResponse
import com.amazon.opendistroforelasticsearch.alerting.core.ScheduledJobIndices
import com.amazon.opendistroforelasticsearch.alerting.core.model.ScheduledJob
import com.amazon.opendistroforelasticsearch.alerting.core.model.ScheduledJob.Companion.SCHEDULED_JOBS_INDEX
import com.amazon.opendistroforelasticsearch.alerting.core.model.ScheduledJob.Companion.SCHEDULED_JOB_TYPE
import com.amazon.opendistroforelasticsearch.alerting.core.model.SearchInput
import com.amazon.opendistroforelasticsearch.alerting.model.Monitor
import com.amazon.opendistroforelasticsearch.alerting.model.destination.Destination
import com.amazon.opendistroforelasticsearch.alerting.settings.AlertingSettings.Companion.ALERTING_MAX_MONITORS
import com.amazon.opendistroforelasticsearch.alerting.settings.AlertingSettings.Companion.INDEX_TIMEOUT
import com.amazon.opendistroforelasticsearch.alerting.settings.AlertingSettings.Companion.MAX_ACTION_THROTTLE_VALUE
import com.amazon.opendistroforelasticsearch.alerting.settings.AlertingSettings.Companion.REQUEST_TIMEOUT
import com.amazon.opendistroforelasticsearch.alerting.settings.DestinationSettings.Companion.ALLOW_LIST
import com.amazon.opendistroforelasticsearch.alerting.util.AlertingException
import com.amazon.opendistroforelasticsearch.alerting.util.IndexUtils
import org.apache.logging.log4j.LogManager
import org.elasticsearch.ElasticsearchStatusException
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.get.GetResponse
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.action.support.ActionFilters
import org.elasticsearch.action.support.HandledTransportAction
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.client.Client
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.common.xcontent.ToXContent
import org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder
import org.elasticsearch.common.xcontent.XContentHelper
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.rest.RestRequest
import org.elasticsearch.rest.RestStatus
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.tasks.Task
import org.elasticsearch.transport.TransportService
import java.time.Duration

private val log = LogManager.getLogger(TransportIndexMonitorAction::class.java)

class TransportIndexMonitorAction @Inject constructor(
    transportService: TransportService,
    val client: Client,
    actionFilters: ActionFilters,
    val scheduledJobIndices: ScheduledJobIndices,
    val clusterService: ClusterService,
    settings: Settings,
    val xContentRegistry: NamedXContentRegistry
) : HandledTransportAction<IndexMonitorRequest, IndexMonitorResponse>(
        IndexMonitorAction.NAME, transportService, actionFilters, ::IndexMonitorRequest
) {

    @Volatile private var maxMonitors = ALERTING_MAX_MONITORS.get(settings)
    @Volatile private var requestTimeout = REQUEST_TIMEOUT.get(settings)
    @Volatile private var indexTimeout = INDEX_TIMEOUT.get(settings)
    @Volatile private var maxActionThrottle = MAX_ACTION_THROTTLE_VALUE.get(settings)
    @Volatile private var allowList = ALLOW_LIST.get(settings)

    init {
        clusterService.clusterSettings.addSettingsUpdateConsumer(ALERTING_MAX_MONITORS) { maxMonitors = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(REQUEST_TIMEOUT) { requestTimeout = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(INDEX_TIMEOUT) { indexTimeout = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(MAX_ACTION_THROTTLE_VALUE) { maxActionThrottle = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(ALLOW_LIST) { allowList = it }
    }

    override fun doExecute(task: Task, request: IndexMonitorRequest, actionListener: ActionListener<IndexMonitorResponse>) {

        if (!isValidIndex(request, actionListener))
            return

        client.threadPool().threadContext.stashContext().use {
            IndexMonitorHandler(client, actionListener, request).start()
        }
    }

    inner class IndexMonitorHandler(
        private val client: Client,
        private val actionListener: ActionListener<IndexMonitorResponse>,
        private val request: IndexMonitorRequest
    ) {

        fun start() {
            if (!scheduledJobIndices.scheduledJobIndexExists()) {
                scheduledJobIndices.initScheduledJobIndex(object : ActionListener<CreateIndexResponse> {
                    override fun onResponse(response: CreateIndexResponse) {
                        onCreateMappingsResponse(response)
                    }
                    override fun onFailure(t: Exception) {
                        actionListener.onFailure(AlertingException.wrap(t))
                    }
                })
            } else if (!IndexUtils.scheduledJobIndexUpdated) {
                IndexUtils.updateIndexMapping(SCHEDULED_JOBS_INDEX, SCHEDULED_JOB_TYPE,
                        ScheduledJobIndices.scheduledJobMappings(), clusterService.state(), client.admin().indices(),
                        object : ActionListener<AcknowledgedResponse> {
                            override fun onResponse(response: AcknowledgedResponse) {
                                onUpdateMappingsResponse(response)
                            }
                            override fun onFailure(t: Exception) {
                                actionListener.onFailure(AlertingException.wrap(t))
                            }
                        })
            } else {
                prepareMonitorIndexing()
            }
        }

        /**
         * This function prepares for indexing a new monitor.
         * If this is an update request we can simply update the monitor. Otherwise we first check to see how many monitors already exist,
         * and compare this to the [maxMonitorCount]. Requests that breach this threshold will be rejected.
         */
        private fun prepareMonitorIndexing() {

            checkForDisallowedDestinations(allowList)

            try {
                validateActionThrottle(request.monitor, maxActionThrottle, TimeValue.timeValueMinutes(1))
            } catch (e: RuntimeException) {
                actionListener.onFailure(AlertingException.wrap(e))
                return
            }

            if (request.method == RestRequest.Method.PUT) return updateMonitor()

            val query = QueryBuilders.boolQuery().filter(QueryBuilders.termQuery("${Monitor.MONITOR_TYPE}.type", Monitor.MONITOR_TYPE))
            val searchSource = SearchSourceBuilder().query(query).timeout(requestTimeout)
            val searchRequest = SearchRequest(SCHEDULED_JOBS_INDEX).source(searchSource)
            client.search(searchRequest, object : ActionListener<SearchResponse> {
                override fun onResponse(searchResponse: SearchResponse) {
                    onSearchResponse(searchResponse)
                }

                override fun onFailure(t: Exception) {
                    actionListener.onFailure(AlertingException.wrap(t))
                }
            })
        }

        private fun validateActionThrottle(monitor: Monitor, maxValue: TimeValue, minValue: TimeValue) {
            monitor.triggers.forEach { trigger ->
                trigger.actions.forEach { action ->
                    if (action.throttle != null) {
                        require(TimeValue(Duration.of(action.throttle.value.toLong(), action.throttle.unit).toMillis())
                                .compareTo(maxValue) <= 0, { "Can only set throttle period less than or equal to $maxValue" })
                        require(TimeValue(Duration.of(action.throttle.value.toLong(), action.throttle.unit).toMillis())
                                .compareTo(minValue) >= 0, { "Can only set throttle period greater than or equal to $minValue" })
                    }
                }
            }
        }

        /**
         * After searching for all existing monitors we validate the system can support another monitor to be created.
         */
        private fun onSearchResponse(response: SearchResponse) {
            val totalHits = response.hits.totalHits?.value
            if (totalHits != null && totalHits >= maxMonitors) {
                log.error("This request would wrap more than the allowed monitors [$maxMonitors].")
                actionListener.onFailure(
                    AlertingException.wrap(IllegalArgumentException(
                            "This request would create more than the allowed monitors [$maxMonitors]."))
                )
            } else {

                indexMonitor()
            }
        }

        private fun onCreateMappingsResponse(response: CreateIndexResponse) {
            if (response.isAcknowledged) {
                log.info("Created $SCHEDULED_JOBS_INDEX with mappings.")
                prepareMonitorIndexing()
                IndexUtils.scheduledJobIndexUpdated()
            } else {
                log.error("Create $SCHEDULED_JOBS_INDEX mappings call not acknowledged.")
                actionListener.onFailure(AlertingException.wrap(ElasticsearchStatusException(
                        "Create $SCHEDULED_JOBS_INDEX mappings call not acknowledged", RestStatus.INTERNAL_SERVER_ERROR))
                )
            }
        }

        private fun onUpdateMappingsResponse(response: AcknowledgedResponse) {
            if (response.isAcknowledged) {
                log.info("Updated  ${ScheduledJob.SCHEDULED_JOBS_INDEX} with mappings.")
                IndexUtils.scheduledJobIndexUpdated()
                prepareMonitorIndexing()
            } else {
                log.error("Update ${ScheduledJob.SCHEDULED_JOBS_INDEX} mappings call not acknowledged.")
                actionListener.onFailure(AlertingException.wrap(ElasticsearchStatusException(
                                "Updated ${ScheduledJob.SCHEDULED_JOBS_INDEX} mappings call not acknowledged.",
                                RestStatus.INTERNAL_SERVER_ERROR))
                )
            }
        }

        private fun indexMonitor() {
            request.monitor = request.monitor.copy(schemaVersion = IndexUtils.scheduledJobIndexSchemaVersion)
            val indexRequest = IndexRequest(SCHEDULED_JOBS_INDEX)
                    .setRefreshPolicy(request.refreshPolicy)
                    .source(request.monitor.toXContent(jsonBuilder(), ToXContent.MapParams(mapOf("with_type" to "true"))))
                    .setIfSeqNo(request.seqNo)
                    .setIfPrimaryTerm(request.primaryTerm)
                    .timeout(indexTimeout)
            client.index(indexRequest, object : ActionListener<IndexResponse> {
                override fun onResponse(response: IndexResponse) {
                    val failureReasons = checkShardsFailure(response)
                    if (failureReasons != null) {
                        actionListener.onFailure(
                                AlertingException.wrap(ElasticsearchStatusException(failureReasons.toString(), response.status())))
                        return
                    }
                    actionListener.onResponse(IndexMonitorResponse(response.id, response.version, response.seqNo,
                            response.primaryTerm, RestStatus.CREATED, request.monitor))
                }
                override fun onFailure(t: Exception) {
                    actionListener.onFailure(AlertingException.wrap(t))
                }
            })
        }

        private fun updateMonitor() {
            val getRequest = GetRequest(SCHEDULED_JOBS_INDEX, request.monitorId)
            client.get(getRequest, object : ActionListener<GetResponse> {
                override fun onResponse(response: GetResponse) {
                    onGetResponse(response)
                }
                override fun onFailure(t: Exception) {
                    actionListener.onFailure(AlertingException.wrap(t))
                }
            })
        }

        private fun onGetResponse(response: GetResponse) {
            if (!response.isExists) {
                actionListener.onFailure(AlertingException.wrap(
                        ElasticsearchStatusException("Monitor with ${request.monitorId} is not found", RestStatus.NOT_FOUND)))
                return
            }

            val xcp = XContentHelper.createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE,
                    response.sourceAsBytesRef, XContentType.JSON)
            val currentMonitor = ScheduledJob.parse(xcp, request.monitorId) as Monitor
            // If both are enabled, use the current existing monitor enabled time, otherwise the next execution will be
            // incorrect.
            if (request.monitor.enabled && currentMonitor.enabled)
                request.monitor = request.monitor.copy(enabledTime = currentMonitor.enabledTime)

            request.monitor = request.monitor.copy(schemaVersion = IndexUtils.scheduledJobIndexSchemaVersion)
            val indexRequest = IndexRequest(SCHEDULED_JOBS_INDEX)
                    .setRefreshPolicy(request.refreshPolicy)
                    .source(request.monitor.toXContent(jsonBuilder(), ToXContent.MapParams(mapOf("with_type" to "true"))))
                    .id(request.monitorId)
                    .setIfSeqNo(request.seqNo)
                    .setIfPrimaryTerm(request.primaryTerm)
                    .timeout(indexTimeout)

            client.index(indexRequest, object : ActionListener<IndexResponse> {
                override fun onResponse(response: IndexResponse) {
                    val failureReasons = checkShardsFailure(response)
                    if (failureReasons != null) {
                        actionListener.onFailure(
                                AlertingException.wrap(ElasticsearchStatusException(failureReasons.toString(), response.status())))
                        return
                    }
                    actionListener.onResponse(
                        IndexMonitorResponse(response.id, response.version, response.seqNo,
                                response.primaryTerm, RestStatus.CREATED, request.monitor)
                    )
                }
                override fun onFailure(t: Exception) {
                    actionListener.onFailure(AlertingException.wrap(t))
                }
            })
        }

        private fun checkShardsFailure(response: IndexResponse): String? {
            val failureReasons = StringBuilder()
            if (response.shardInfo.failed > 0) {
                response.shardInfo.failures.forEach {
                    entry -> failureReasons.append(entry.reason())
                }
                return failureReasons.toString()
            }
            return null
        }

        private fun checkForDisallowedDestinations(allowList: List<String>) {
            this.request.monitor.triggers.forEach { trigger ->
                trigger.actions.forEach { action ->
                    // Check for empty destinationId for test cases, otherwise we get test failures
                    if (action.destinationId.isNotEmpty()) checkIfDestinationIsAllowed(action.destinationId, allowList)
                }
            }
        }

        private fun checkIfDestinationIsAllowed(destinationId: String, allowList: List<String>) {
            val getRequest = GetRequest(SCHEDULED_JOBS_INDEX, destinationId)
            client.threadPool().threadContext.stashContext().use {
                client.get(getRequest, object : ActionListener<GetResponse> {
                    override fun onResponse(response: GetResponse) {
                        val xcp = XContentHelper.createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE,
                            response.sourceAsBytesRef, XContentType.JSON)
                        val destination = Destination.parseWithType(xcp)
                        if (!allowList.contains(destination.type.value)) {
                            actionListener.onFailure(
                                AlertingException.wrap(ElasticsearchStatusException(
                                    "Monitor contains a destination type that is not allowed: ${destination.type.value}",
                                    RestStatus.FORBIDDEN
                                ))
                            )
                        }
                    }

                    override fun onFailure(e: Exception) {
                        actionListener.onFailure(e)
                    }
                })
            }
        }
    }

    /**
     *  Check if user has permissions to read the configured indices on the monitor.
     *  Due to below issue with security plugin, we get security_exception when invalid index name is mentioned.
     *  https://github.com/opendistro-for-elasticsearch/security/issues/718
     */
    private fun isValidIndex(request: IndexMonitorRequest, actionListener: ActionListener<IndexMonitorResponse>): Boolean {
        var ret = true
        val searchInputs = request.monitor.inputs.filter { it.name() == SearchInput.SEARCH_FIELD }
        searchInputs.forEach {
            val searchInput = it as SearchInput
            val searchRequest = SearchRequest().indices(*searchInput.indices.toTypedArray())
                    .source(SearchSourceBuilder.searchSource().size(1).query(QueryBuilders.matchAllQuery()))
            client.search(searchRequest, object : ActionListener<SearchResponse> {
                override fun onResponse(searchResponse: SearchResponse) {
                    // ignore
                }

                override fun onFailure(t: Exception) {
                    val ex = ElasticsearchStatusException("User doesn't have read permissions for the configured index " +
                            "${searchInput.indices}", RestStatus.FORBIDDEN)
                    actionListener.onFailure(AlertingException.wrap(ex))
                    ret = false
                }
            })
        }
        return ret
    }
}
