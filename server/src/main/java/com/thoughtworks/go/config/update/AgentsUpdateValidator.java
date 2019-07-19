/*
 * Copyright 2019 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.thoughtworks.go.config.update;

import com.thoughtworks.go.config.Agent;
import com.thoughtworks.go.config.Agents;
import com.thoughtworks.go.config.EnvironmentsConfig;
import com.thoughtworks.go.config.ResourceConfigs;
import com.thoughtworks.go.config.exceptions.ElasticAgentsResourceUpdateException;
import com.thoughtworks.go.config.exceptions.EntityType;
import com.thoughtworks.go.config.exceptions.InvalidPendingAgentOperationException;
import com.thoughtworks.go.config.exceptions.RecordNotFoundException;
import com.thoughtworks.go.domain.AgentInstance;
import com.thoughtworks.go.server.domain.AgentInstances;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.service.GoConfigService;
import com.thoughtworks.go.server.service.result.LocalizedOperationResult;
import com.thoughtworks.go.util.TriState;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

import static com.thoughtworks.go.i18n.LocalizedMessage.forbiddenToEdit;
import static com.thoughtworks.go.serverhealth.HealthStateType.forbidden;

public class AgentsUpdateValidator {
    private AgentInstances agentInstances;
    private final Username username;
    private final LocalizedOperationResult result;
    private final List<String> uuids;
    private final TriState state;
    private final List<String> resourcesToAdd;
    private final List<String> resourcesToRemove;
    private final EnvironmentsConfig envsToAdd;
    private final List<String> envsToRemove;
    private GoConfigService goConfigService;
    public Agents agents;

    public AgentsUpdateValidator(AgentInstances agentInstances, Username username, LocalizedOperationResult result, List<String> uuids, TriState state, EnvironmentsConfig envsToAdd, List<String> envsToRemove, List<String> resourcesToAdd, List<String> resourcesToRemove, GoConfigService goConfigService) {
        this.agentInstances = agentInstances;
        this.username = username;
        this.result = result;
        this.uuids = uuids;
        this.state = state;
        this.resourcesToAdd = resourcesToAdd;
        this.resourcesToRemove = resourcesToRemove;
        this.envsToAdd = envsToAdd;
        this.envsToRemove = envsToRemove;
        this.goConfigService = goConfigService;
    }

    public boolean canContinue() {
        if (!isAuthorized()) {
            return false;
        }

        if (isAnyOperationPerformedOnAgents()) {
            return true;
        }

        result.badRequest("No Operation performed on agents.");
        return false;
    }

    public void validate() throws Exception {
        bombWhenAgentsDoesNotExist();
        bombWhenElasticAgentResourcesAreUpdated();
        bombWhenResourceNamesToAddAreInvalid();
        bombWhenAnyOperationOnPendingAgents();
    }

    private void bombWhenResourceNamesToAddAreInvalid() {
        ResourceConfigs resourceConfigs = new ResourceConfigs(StringUtils.join(resourcesToAdd, ","));
        resourceConfigs.validate(null);
        if (!resourceConfigs.errors().isEmpty()) {
            result.unprocessableEntity("Validations failed for bulk update of agents. Error(s): " + resourceConfigs.errors());
            throw new IllegalArgumentException(resourceConfigs.errors().toString());
        }
    }

    private void bombWhenAgentsDoesNotExist() {
        List<String> unknownUUIDs = new ArrayList<>();

        for (String uuid : uuids) {
            if (agentInstances.findAgent(uuid).isNullAgent()) {
                unknownUUIDs.add(uuid);
            }
        }
        if (!unknownUUIDs.isEmpty()) {
            result.badRequest(EntityType.Agent.notFoundMessage(unknownUUIDs));
            throw new RecordNotFoundException(EntityType.Agent, unknownUUIDs);
        }
    }

    private boolean isAuthorized() {
        if (goConfigService.isAdministrator(username.getUsername())) {
            return true;
        }
        result.forbidden(forbiddenToEdit(), forbidden());
        return false;
    }

    private boolean isAnyOperationPerformedOnAgents() {
        return !resourcesToAdd.isEmpty() || !resourcesToRemove.isEmpty() || !envsToAdd.isEmpty() || !envsToRemove.isEmpty() || state.isTrue() || state.isFalse();
    }

    private List<Agent> findPendingAgents() {
        List<Agent> pendingAgents = new ArrayList<>();
        for (String uuid : uuids) {
            AgentInstance agent = agentInstances.findAgent(uuid);
            if (agent.isPending()) {
                pendingAgents.add(agent.getAgent().deepClone());
            }
        }
        return pendingAgents;
    }

    private void bombWhenAnyOperationOnPendingAgents() throws InvalidPendingAgentOperationException {
        List<Agent> pendingAgents = findPendingAgents();
        if (pendingAgents.isEmpty()) {
            return;
        }

        List<String> pendingAgentUuids = getPendingAgentUuids(pendingAgents);
        if (!(state.isTrue() || state.isFalse())) {
            result.badRequest("Pending agents [" + StringUtils.join(pendingAgentUuids, ", ") + "] must be explicitly enabled or disabled when performing any operations on them.");
            throw new InvalidPendingAgentOperationException(pendingAgentUuids);
        }
    }

    private List<String> getPendingAgentUuids(List<Agent> pendingAgents) {
        List<String> pendingAgentUuids = new ArrayList<>();
        for (Agent pendingAgent : pendingAgents) {
            pendingAgentUuids.add(pendingAgent.getUuid());
        }
        return pendingAgentUuids;
    }

    private void bombWhenElasticAgentResourcesAreUpdated() throws ElasticAgentsResourceUpdateException {
        if (resourcesToAdd.isEmpty() && resourcesToRemove.isEmpty()) {
            return;
        }

        List<String> elasticAgentUUIDs = findAllElasticAgentUuids(uuids);
        if (elasticAgentUUIDs.isEmpty()) {
            return;
        }

        result.badRequest("Resources on elastic agents with uuids [" + StringUtils.join(elasticAgentUUIDs, ", ") + "] can not be updated.");
        throw new ElasticAgentsResourceUpdateException(elasticAgentUUIDs);
    }

    private List<String> findAllElasticAgentUuids(List<String> uuids) {
        ArrayList<String> elasticAgentUUIDs = new ArrayList<>();
        for (String uuid : uuids) {
            if (agentInstances.findAgent(uuid).isElastic()) {
                elasticAgentUUIDs.add(uuid);
            }
        }
        return elasticAgentUUIDs;
    }
}
