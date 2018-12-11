/*
 * Copyright 2017-2017 Spotify AB
 * Copyright 2017-2018 The Last Pickle Ltd
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

package io.cassandrareaper.storage;

import io.cassandrareaper.core.GenericMetric;
import io.cassandrareaper.core.NodeMetrics;
import io.cassandrareaper.core.RepairSegment;
import io.cassandrareaper.service.RingRange;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * Definition for a storage that can run in distributed (peer-to-peer) mode. For example Cassandra.
 */
public interface IDistributedStorage {

  boolean takeLead(UUID leaderId);

  boolean renewLead(UUID leaderId);

  List<UUID> getLeaders();

  void releaseLead(UUID leaderId);

  void forceReleaseLead(UUID leaderId);

  int countRunningReapers();

  void saveHeartbeat();

  Collection<NodeMetrics> getNodeMetrics(UUID runId);

  Optional<NodeMetrics> getNodeMetrics(UUID runId, String node);

  void storeNodeMetrics(UUID runId, NodeMetrics nodeMetrics);

  /**
   * Gets the next free segment from the backend that is both within the parallel range and the local node ranges.
   *
   * @param runId
   * @param parallelRange
   * @param ranges
   * @return an optional repair segment to process
   */
  Optional<RepairSegment> getNextFreeSegmentForRanges(
      UUID runId, Optional<RingRange> parallelRange, List<RingRange> ranges);

  List<GenericMetric> getMetrics(String clusterName, Optional<String> host, String metric, long since);

  void storeMetric(GenericMetric metric);

}
