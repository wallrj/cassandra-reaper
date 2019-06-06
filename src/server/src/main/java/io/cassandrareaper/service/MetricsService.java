/*
 * Copyright 2018-2018 The Last Pickle Ltd
 *
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

package io.cassandrareaper.service;

import io.cassandrareaper.AppContext;
import io.cassandrareaper.ReaperException;
import io.cassandrareaper.core.Compaction;
import io.cassandrareaper.core.DroppedMessages;
import io.cassandrareaper.core.GenericMetric;
import io.cassandrareaper.core.JmxStat;
import io.cassandrareaper.core.MetricsHistogram;
import io.cassandrareaper.core.Node;
import io.cassandrareaper.core.ThreadPoolStat;
import io.cassandrareaper.jmx.ClusterFacade;
import io.cassandrareaper.storage.IDistributedStorage;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.management.JMException;
import javax.management.MalformedObjectNameException;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MetricsService {

  private static final Logger LOG = LoggerFactory.getLogger(MetricsService.class);
  private static final String[] COLLECTED_METRICS
    = {"org.apache.cassandra.metrics:type=ThreadPools,path=request,*",
       "org.apache.cassandra.metrics:type=ThreadPools,path=internal,*",
       "org.apache.cassandra.metrics:type=ClientRequest,*",
       "org.apache.cassandra.metrics:type=DroppedMessage,*"};

  private final AppContext context;
  private final ClusterFacade clusterFacade;

  private MetricsService(AppContext context, Supplier<ClusterFacade> clusterFacadeSupplier) {
    this.context = context;
    this.clusterFacade = clusterFacadeSupplier.get();
  }

  @VisibleForTesting
  static MetricsService create(AppContext context, Supplier<ClusterFacade> clusterFacadeSupplier) {
    return new MetricsService(context, clusterFacadeSupplier);
  }

  public static MetricsService create(AppContext context) {
    return new MetricsService(context, () -> ClusterFacade.create(context));
  }

  public List<ThreadPoolStat> getTpStats(Node host) throws ReaperException {
    return clusterFacade.getTpStats(host);
  }

  public List<DroppedMessages> getDroppedMessages(Node host) throws ReaperException {
    return clusterFacade.getDroppedMessages(host);
  }

  public List<MetricsHistogram> getClientRequestLatencies(Node host) throws ReaperException {
    return clusterFacade.getClientRequestLatencies(host);
  }

  public List<GenericMetric> convertToGenericMetrics(Map<String, List<JmxStat>> jmxStats, Node node) {
    List<GenericMetric> metrics = Lists.newArrayList();
    DateTime now = DateTime.now();
    for (Entry<String, List<JmxStat>> jmxStatEntry:jmxStats.entrySet()) {
      for (JmxStat jmxStat:jmxStatEntry.getValue()) {
        GenericMetric metric = GenericMetric.builder()
            .withClusterName(node.getCluster().getName())
            .withHost(node.getHostname())
            .withMetricDomain(jmxStat.getDomain())
            .withMetricType(jmxStat.getType())
            .withMetricScope(jmxStat.getScope())
            .withMetricName(jmxStat.getName())
            .withMetricAttribute(jmxStat.getAttribute())
            .withValue(jmxStat.getValue())
            .withTs(now)
            .build();
        metrics.add(metric);
      }
    }

    return metrics;
  }

  void grabAndStoreGenericMetrics()
      throws ReaperException, InterruptedException, JMException {
    Node node = Node.builder().withClusterName(context.localClusterName).withHostname(context.localNodeAddress).build();
    List<GenericMetric> metrics
        = convertToGenericMetrics(
            ClusterFacade.create(context).collectMetrics(node, COLLECTED_METRICS), node);

    for (GenericMetric metric:metrics) {
      ((IDistributedStorage)context.storage).storeMetric(metric);
    }
    LOG.debug("Grabbing and storing metrics for {}", context.localNodeAddress);

  }

  void grabAndStoreActiveCompactions()
      throws JsonProcessingException, MalformedObjectNameException, ReflectionException,
          ReaperException, InterruptedException {
    Node node = Node.builder().withClusterName(context.localClusterName).withHostname(context.localNodeAddress).build();
    List<Compaction> activeCompactions = ClusterFacade.create(context).listActiveCompactionsDirect(node);

    ((IDistributedStorage) context.storage)
        .storeCompactions(context.localClusterName, context.localNodeAddress, activeCompactions);

    LOG.debug("Grabbing and storing compactions for {}", context.localNodeAddress);
  }

  void grabAndStoreActiveStreams()
      throws JsonProcessingException, MalformedObjectNameException, ReflectionException,
          ReaperException, InterruptedException {
    Node node = Node.builder().withClusterName(context.localClusterName).withHostname(context.localNodeAddress).build();
    Set<CompositeData> activeStreams = ClusterFacade.create(context).listStreamsDirect(node);

    ((IDistributedStorage) context.storage)
        .storeStreams(context.localClusterName, context.localNodeAddress, activeStreams);

    LOG.debug("Grabbing and storing streams for {}", context.localNodeAddress);
  }

}
