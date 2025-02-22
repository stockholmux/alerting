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

package com.amazon.opendistroforelasticsearch.alerting.resthandler

import com.amazon.opendistroforelasticsearch.alerting.AlertingPlugin
import com.amazon.opendistroforelasticsearch.alerting.action.GetDestinationsAction
import com.amazon.opendistroforelasticsearch.alerting.action.GetDestinationsRequest
import com.amazon.opendistroforelasticsearch.alerting.model.Table
import com.amazon.opendistroforelasticsearch.alerting.settings.AlertingSettings
import com.amazon.opendistroforelasticsearch.alerting.util.context
import com.amazon.opendistroforelasticsearch.commons.ConfigConstants
import com.amazon.opendistroforelasticsearch.commons.authuser.AuthUser
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.rest.BaseRestHandler
import org.elasticsearch.rest.RestHandler
import org.elasticsearch.rest.RestRequest
import org.elasticsearch.rest.action.RestActions
import org.elasticsearch.rest.action.RestToXContentListener
import org.elasticsearch.search.fetch.subphase.FetchSourceContext
import org.apache.logging.log4j.LogManager
import org.elasticsearch.client.RestClient
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.index.query.TermsQueryBuilder

/**
 * This class consists of the REST handler to retrieve destinations .
 */
class RestGetDestinationsAction(
    val settings: Settings,
    clusterService: ClusterService,
    private val restClient: RestClient
) : BaseRestHandler() {

    private val log = LogManager.getLogger(RestGetDestinationsAction::class.java)
    @Volatile private var filterBy = AlertingSettings.FILTER_BY_BACKEND_ROLES.get(settings)

    init {
        clusterService.clusterSettings.addSettingsUpdateConsumer(AlertingSettings.FILTER_BY_BACKEND_ROLES) { filterBy = it }
    }

    override fun getName(): String {
        return "get_destinations_action"
    }

    override fun routes(): List<RestHandler.Route> {
        return listOf(
                // Get a specific destination
                RestHandler.Route(RestRequest.Method.GET, "${AlertingPlugin.DESTINATION_BASE_URI}/{destinationID}"),
                RestHandler.Route(RestRequest.Method.GET, "${AlertingPlugin.DESTINATION_BASE_URI}")
        )
    }

    override fun prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer {
        log.debug("${request.method()} ${request.path()}")

        val destinationId: String? = request.param("destinationID")

        var srcContext = context(request)
        if (request.method() == RestRequest.Method.HEAD) {
            srcContext = FetchSourceContext.DO_NOT_FETCH_SOURCE
        }

        val sortString = request.param("sortString", "destination.name.keyword")
        val sortOrder = request.param("sortOrder", "asc")
        val missing: String? = request.param("missing")
        val size = request.paramAsInt("size", 20)
        val startIndex = request.paramAsInt("startIndex", 0)
        val searchString = request.param("searchString", "")
        val destinationType = request.param("destinationType", "ALL")

        val table = Table(
                sortOrder,
                sortString,
                missing,
                size,
                startIndex,
                searchString
        )

        var filter: TermsQueryBuilder? = null
        if (filterBy) {
            val user = AuthUser(settings, restClient, request.headers[ConfigConstants.AUTHORIZATION]).get()
            if (user != null) {
                filter = QueryBuilders.termsQuery("destination.user.backend_roles", user.backendRoles)
            }
        }

        val getDestinationsRequest = GetDestinationsRequest(
                destinationId,
                RestActions.parseVersion(request),
                srcContext,
                table,
                destinationType,
                filter
        )
        return RestChannelConsumer {
            channel -> client.execute(GetDestinationsAction.INSTANCE, getDestinationsRequest, RestToXContentListener(channel))
        }
    }
}
