/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.storm.scheduler.resource;

import org.apache.storm.Config;
import org.apache.storm.scheduler.resource.strategies.eviction.IEvictionStrategy;
import org.apache.storm.scheduler.resource.strategies.priority.ISchedulingPriorityStrategy;
import org.apache.storm.scheduler.resource.strategies.scheduling.IStrategy;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.storm.scheduler.Cluster;
import org.apache.storm.scheduler.ExecutorDetails;
import org.apache.storm.scheduler.IScheduler;
import org.apache.storm.scheduler.Topologies;
import org.apache.storm.scheduler.TopologyDetails;
import org.apache.storm.scheduler.WorkerSlot;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ResourceAwareScheduler implements IScheduler {

    private Map<String, User> userMap;
    private Cluster cluster;
    private Topologies topologies;
    private RAS_Nodes nodes;

    private class SchedulingState {
        private Map<String, User> userMap = new HashMap<String, User>();
        private Cluster cluster;
        private Topologies topologies;
        private RAS_Nodes nodes;
        private Map conf = new Config();

        public SchedulingState(Map<String, User> userMap, Cluster cluster, Topologies topologies, RAS_Nodes nodes, Map conf) {
            for (Map.Entry<String, User> userMapEntry : userMap.entrySet()) {
                String userId = userMapEntry.getKey();
                User user = userMapEntry.getValue();
                this.userMap.put(userId, user.getCopy());
            }
            this.cluster = Cluster.getCopy(cluster);
            this.topologies = topologies.getCopy(topologies);
            this.nodes = new RAS_Nodes(this.cluster, this.topologies);
            this.conf.putAll(conf);
        }
    }

    @SuppressWarnings("rawtypes")
    private Map conf;

    private static final Logger LOG = LoggerFactory
            .getLogger(ResourceAwareScheduler.class);

    @Override
    public void prepare(Map conf) {
        this.conf = conf;

    }

    @Override
    public void schedule(Topologies topologies, Cluster cluster) {
        LOG.debug("\n\n\nRerunning ResourceAwareScheduler...");
        //initialize data structures
        initialize(topologies, cluster);
        //logs everything that is currently scheduled and the location at which they are scheduled
        LOG.info("Cluster scheduling:\n{}", ResourceUtils.printScheduling(cluster, topologies));
        //logs the resources available/used for every node
        LOG.info("Nodes:\n{}", this.nodes);
        //logs the detailed info about each user
        for (User user : getUserMap().values()) {
            LOG.info(user.getDetailedInfo());
        }

        ISchedulingPriorityStrategy schedulingPrioritystrategy = null;
        while (true) {

            if (schedulingPrioritystrategy == null) {
                try {
                    schedulingPrioritystrategy = (ISchedulingPriorityStrategy) Utils.newInstance((String) this.conf.get(Config.RESOURCE_AWARE_SCHEDULER_PRIORITY_STRATEGY));
                } catch (RuntimeException ex) {
                    LOG.error(String.format("failed to create instance of priority strategy: %s with error: %s! No topologies will be scheduled.",
                                    this.conf.get(Config.RESOURCE_AWARE_SCHEDULER_PRIORITY_STRATEGY), ex.getMessage()), ex);
                    break;
                }
            }
            TopologyDetails td = null;
            try {
                //need to re prepare since scheduling state might have been restored
                schedulingPrioritystrategy.prepare(this.topologies, this.cluster, this.userMap, this.nodes);
                //Call scheduling priority strategy
                td = schedulingPrioritystrategy.getNextTopologyToSchedule();
            } catch (Exception ex) {
                LOG.error(String.format("Exception thrown when running priority strategy %s. No topologies will be scheduled! Error: %s"
                        , schedulingPrioritystrategy.getClass().getName(), ex.getMessage()), ex.getStackTrace());
                break;
            }
            if (td == null) {
                break;
            }
            scheduleTopology(td);
        }
    }

    public void scheduleTopology(TopologyDetails td) {
        User topologySubmitter = this.userMap.get(td.getTopologySubmitter());
        if (cluster.getUnassignedExecutors(td).size() > 0) {
            LOG.debug("/********Scheduling topology {} from User {}************/", td.getName(), topologySubmitter);

            SchedulingState schedulingState = checkpointSchedulingState();
            IStrategy rasStrategy = null;
            try {
                rasStrategy = (IStrategy) Utils.newInstance((String) td.getConf().get(Config.TOPOLOGY_SCHEDULER_STRATEGY));
            } catch (RuntimeException e) {
                LOG.error("failed to create instance of IStrategy: {} with error: {}! Topology {} will not be scheduled.",
                        td.getName(), td.getConf().get(Config.TOPOLOGY_SCHEDULER_STRATEGY), e.getMessage());
                topologySubmitter = cleanup(schedulingState, td);
                topologySubmitter.moveTopoFromPendingToInvalid(td);
                this.cluster.setStatus(td.getId(), "Unsuccessful in scheduling - failed to create instance of topology strategy "
                        + td.getConf().get(Config.TOPOLOGY_SCHEDULER_STRATEGY) + ". Please check logs for details");
                return;
            }
            IEvictionStrategy evictionStrategy = null;
            while (true) {
                SchedulingResult result = null;
                try {
                    //Need to re prepare scheduling strategy with cluster and topologies in case scheduling state was restored
                    rasStrategy.prepare(this.topologies, this.cluster, this.userMap, this.nodes);
                    result = rasStrategy.schedule(td);
                } catch (Exception ex) {
                    LOG.error(String.format("Exception thrown when running strategy %s to schedule topology %s. Topology will not be scheduled!"
                            , rasStrategy.getClass().getName(), td.getName()), ex);
                    topologySubmitter = cleanup(schedulingState, td);
                    topologySubmitter.moveTopoFromPendingToInvalid(td);
                    this.cluster.setStatus(td.getId(), "Unsuccessful in scheduling - Exception thrown when running strategy {}"
                            + rasStrategy.getClass().getName() + ". Please check logs for details");
                }
                LOG.debug("scheduling result: {}", result);
                if (result != null && result.isValid()) {
                    if (result.isSuccess()) {
                        try {
                            if (mkAssignment(td, result.getSchedulingResultMap())) {
                                topologySubmitter.moveTopoFromPendingToRunning(td);
                                this.cluster.setStatus(td.getId(), "Running - " + result.getMessage());
                            } else {
                                topologySubmitter = this.cleanup(schedulingState, td);
                                topologySubmitter.moveTopoFromPendingToAttempted(td);
                                this.cluster.setStatus(td.getId(), "Unsuccessful in scheduling - Unable to assign executors to nodes. Please check logs for details");
                            }
                        } catch (IllegalStateException ex) {
                            LOG.error("Unsuccessful in scheduling - IllegalStateException thrown when attempting to assign executors to nodes.", ex);
                            topologySubmitter = cleanup(schedulingState, td);
                            topologySubmitter.moveTopoFromPendingToAttempted(td);
                            this.cluster.setStatus(td.getId(), "Unsuccessful in scheduling - IllegalStateException thrown when attempting to assign executors to nodes. Please check log for details.");
                        }
                        break;
                    } else {
                        if (result.getStatus() == SchedulingStatus.FAIL_NOT_ENOUGH_RESOURCES) {
                            if (evictionStrategy == null) {
                                try {
                                    evictionStrategy = (IEvictionStrategy) Utils.newInstance((String) this.conf.get(Config.RESOURCE_AWARE_SCHEDULER_EVICTION_STRATEGY));
                                } catch (RuntimeException e) {
                                    LOG.error("failed to create instance of eviction strategy: {} with error: {}! No topology eviction will be done.",
                                            this.conf.get(Config.RESOURCE_AWARE_SCHEDULER_EVICTION_STRATEGY), e.getMessage());
                                    topologySubmitter.moveTopoFromPendingToAttempted(td);
                                    break;
                                }
                            }
                            boolean madeSpace = false;
                            try {
                                //need to re prepare since scheduling state might have been restored
                                evictionStrategy.prepare(this.topologies, this.cluster, this.userMap, this.nodes);
                                madeSpace = evictionStrategy.makeSpaceForTopo(td);
                            } catch (Exception ex) {
                                LOG.error(String.format("Exception thrown when running eviction strategy %s to schedule topology %s. No evictions will be done! Error: %s"
                                        , evictionStrategy.getClass().getName(), td.getName(), ex.getClass().getName()), ex);
                                topologySubmitter = cleanup(schedulingState, td);
                                topologySubmitter.moveTopoFromPendingToAttempted(td);
                                break;
                            }
                            if (!madeSpace) {
                                LOG.debug("Could not make space for topo {} will move to attempted", td);
                                topologySubmitter = cleanup(schedulingState, td);
                                topologySubmitter.moveTopoFromPendingToAttempted(td);
                                this.cluster.setStatus(td.getId(), "Not enough resources to schedule - " + result.getErrorMessage());
                                break;
                            }
                            continue;
                        } else if (result.getStatus() == SchedulingStatus.FAIL_INVALID_TOPOLOGY) {
                            topologySubmitter = cleanup(schedulingState, td);
                            topologySubmitter.moveTopoFromPendingToInvalid(td, this.cluster);
                            break;
                        } else {
                            topologySubmitter = cleanup(schedulingState, td);
                            topologySubmitter.moveTopoFromPendingToAttempted(td, this.cluster);
                            break;
                        }
                    }
                } else {
                    LOG.warn("Scheduling results returned from topology {} is not vaild! Topology with be ignored.", td.getName());
                    topologySubmitter = cleanup(schedulingState, td);
                    topologySubmitter.moveTopoFromPendingToInvalid(td, this.cluster);
                    break;
                }
            }
        } else {
            LOG.warn("Topology {} is already fully scheduled!", td.getName());
            topologySubmitter.moveTopoFromPendingToRunning(td);
            if (this.cluster.getStatusMap().get(td.getId()) == null || this.cluster.getStatusMap().get(td.getId()).equals("")) {
                this.cluster.setStatus(td.getId(), "Fully Scheduled");
            }
        }
    }

    private User cleanup(SchedulingState schedulingState, TopologyDetails td) {
        restoreCheckpointSchedulingState(schedulingState);
        //since state is restored need the update User topologySubmitter to the new User object in userMap
        return this.userMap.get(td.getTopologySubmitter());
    }

    private boolean mkAssignment(TopologyDetails td, Map<WorkerSlot, Collection<ExecutorDetails>> schedulerAssignmentMap) {
        if (schedulerAssignmentMap != null) {
            double requestedMemOnHeap = td.getTotalRequestedMemOnHeap();
            double requestedMemOffHeap = td.getTotalRequestedMemOffHeap();
            double requestedCpu = td.getTotalRequestedCpu();
            double assignedMemOnHeap = 0.0;
            double assignedMemOffHeap = 0.0;
            double assignedCpu = 0.0;

            Set<String> nodesUsed = new HashSet<String>();
            for (Map.Entry<WorkerSlot, Collection<ExecutorDetails>> workerToTasksEntry : schedulerAssignmentMap.entrySet()) {
                WorkerSlot targetSlot = workerToTasksEntry.getKey();
                Collection<ExecutorDetails> execsNeedScheduling = workerToTasksEntry.getValue();
                RAS_Node targetNode = this.nodes.getNodeById(targetSlot.getNodeId());
                targetNode.assign(targetSlot, td, execsNeedScheduling);
                LOG.debug("ASSIGNMENT    TOPOLOGY: {}  TASKS: {} To Node: {} on Slot: {}",
                        td.getName(), execsNeedScheduling, targetNode.getHostname(), targetSlot.getPort());
                if (!nodesUsed.contains(targetNode.getId())) {
                    nodesUsed.add(targetNode.getId());
                }
                assignedMemOnHeap += targetSlot.getAllocatedMemOnHeap();
                assignedMemOffHeap += targetSlot.getAllocatedMemOffHeap();
                assignedCpu += targetSlot.getAllocatedCpu();
            }

            Double[] resources = {requestedMemOnHeap, requestedMemOffHeap, requestedCpu,
                    assignedMemOnHeap, assignedMemOffHeap, assignedCpu};
            LOG.debug("setResources for {}: requested on-heap mem, off-heap mem, cpu: {} {} {} " +
                            "assigned on-heap mem, off-heap mem, cpu: {} {} {}",
                    td.getId(), requestedMemOnHeap, requestedMemOffHeap, requestedCpu,
                    assignedMemOnHeap, assignedMemOffHeap, assignedCpu);
            this.cluster.setResources(td.getId(), resources);
            updateSupervisorsResources(this.cluster, this.topologies);
            return true;
        } else {
            LOG.warn("schedulerAssignmentMap for topo {} is null. This shouldn't happen!", td.getName());
            return false;
        }
    }

    private void updateSupervisorsResources(Cluster cluster, Topologies topologies) {
        Map<String, Double[]> supervisors_resources = new HashMap<String, Double[]>();
        Map<String, RAS_Node> nodes = RAS_Nodes.getAllNodesFrom(cluster, topologies);
        for (Map.Entry<String, RAS_Node> entry : nodes.entrySet()) {
            RAS_Node node = entry.getValue();
            Double totalMem = node.getTotalMemoryResources();
            Double totalCpu = node.getTotalCpuResources();
            Double usedMem = totalMem - node.getAvailableMemoryResources();
            Double usedCpu = totalCpu - node.getAvailableCpuResources();
            Double[] resources = {totalMem, totalCpu, usedMem, usedCpu};
            supervisors_resources.put(entry.getKey(), resources);
        }
        cluster.setSupervisorsResourcesMap(supervisors_resources);
    }

    public User getUser(String user) {
        return this.userMap.get(user);
    }

    public Map<String, User> getUserMap() {
        return this.userMap;
    }

    /**
     * Intialize scheduling and running queues
     *
     * @param topologies
     * @param cluster
     */
    private void initUsers(Topologies topologies, Cluster cluster) {
        this.userMap = new HashMap<String, User>();
        Map<String, Map<String, Double>> userResourcePools = getUserResourcePools();
        LOG.debug("userResourcePools: {}", userResourcePools);

        for (TopologyDetails td : topologies.getTopologies()) {

            String topologySubmitter = td.getTopologySubmitter();
            //additional safety check to make sure that topologySubmitter is going to be a valid value
            if (topologySubmitter == null || topologySubmitter.equals("")) {
                LOG.error("Cannot determine user for topology {}.  Will skip scheduling this topology", td.getName());
                continue;
            }
            if (!this.userMap.containsKey(topologySubmitter)) {
                this.userMap.put(topologySubmitter, new User(topologySubmitter, userResourcePools.get(topologySubmitter)));
            }
            if (cluster.getUnassignedExecutors(td).size() > 0) {
                LOG.debug("adding td: {} to pending queue", td.getName());
                this.userMap.get(topologySubmitter).addTopologyToPendingQueue(td);
            } else {
                LOG.debug("adding td: {} to running queue with existing status: {}", td.getName(), cluster.getStatusMap().get(td.getId()));
                this.userMap.get(topologySubmitter).addTopologyToRunningQueue(td);
                if (cluster.getStatusMap().get(td.getId()) == null || cluster.getStatusMap().get(td.getId()).equals("")) {
                    cluster.setStatus(td.getId(), "Fully Scheduled");
                }
            }
        }
    }

    private void initialize(Topologies topologies, Cluster cluster) {
        this.cluster = cluster;
        this.topologies = topologies;
        this.nodes = new RAS_Nodes(this.cluster, this.topologies);
        initUsers(topologies, cluster);
    }

    /**
     * Get resource guarantee configs
     *
     * @return a map that contains resource guarantees of every user of the following format
     * {userid->{resourceType->amountGuaranteed}}
     */
    private Map<String, Map<String, Double>> getUserResourcePools() {
        Object raw = this.conf.get(Config.RESOURCE_AWARE_SCHEDULER_USER_POOLS);
        Map<String, Map<String, Double>> ret = new HashMap<String, Map<String, Double>>();

        if (raw != null) {
            for (Map.Entry<String, Map<String, Number>> userPoolEntry : ((Map<String, Map<String, Number>>) raw).entrySet()) {
                String user = userPoolEntry.getKey();
                ret.put(user, new HashMap<String, Double>());
                for (Map.Entry<String, Number> resourceEntry : userPoolEntry.getValue().entrySet()) {
                    ret.get(user).put(resourceEntry.getKey(), resourceEntry.getValue().doubleValue());
                }
            }
        }

        Map fromFile = Utils.findAndReadConfigFile("user-resource-pools.yaml", false);
        Map<String, Map<String, Number>> tmp = (Map<String, Map<String, Number>>) fromFile.get(Config.RESOURCE_AWARE_SCHEDULER_USER_POOLS);
        if (tmp != null) {
            for (Map.Entry<String, Map<String, Number>> userPoolEntry : tmp.entrySet()) {
                String user = userPoolEntry.getKey();
                ret.put(user, new HashMap<String, Double>());
                for (Map.Entry<String, Number> resourceEntry : userPoolEntry.getValue().entrySet()) {
                    ret.get(user).put(resourceEntry.getKey(), resourceEntry.getValue().doubleValue());
                }
            }
        }
        return ret;
    }

    private SchedulingState checkpointSchedulingState() {
        LOG.debug("/*********Checkpoint scheduling state************/");
        for (User user : getUserMap().values()) {
            LOG.debug(user.getDetailedInfo());
        }
        LOG.debug(ResourceUtils.printScheduling(this.cluster, this.topologies));
        LOG.debug("nodes:\n{}", this.nodes);
        LOG.debug("/*********End************/");
        return new SchedulingState(this.userMap, this.cluster, this.topologies, this.nodes, this.conf);
    }

    private void restoreCheckpointSchedulingState(SchedulingState schedulingState) {
        LOG.debug("/*********restoring scheduling state************/");
        //reseting cluster
        //Cannot simply set this.cluster=schedulingState.cluster since clojure is immutable
        this.cluster.setAssignments(schedulingState.cluster.getAssignments());
        this.cluster.setSupervisorsResourcesMap(schedulingState.cluster.getSupervisorsResourcesMap());
        this.cluster.setStatusMap(schedulingState.cluster.getStatusMap());
        this.cluster.setResourcesMap(schedulingState.cluster.getResourcesMap());
        //don't need to explicitly set data structues like Cluster since nothing can really be changed
        //unless this.topologies is set to another object
        this.topologies = schedulingState.topologies;
        this.conf = schedulingState.conf;
        this.userMap = schedulingState.userMap;
        this.nodes = schedulingState.nodes;

        for (User user : getUserMap().values()) {
            LOG.debug(user.getDetailedInfo());
        }
        LOG.debug(ResourceUtils.printScheduling(cluster, topologies));
        LOG.debug("nodes:\n{}", this.nodes);
        LOG.debug("/*********End************/");
    }
}
