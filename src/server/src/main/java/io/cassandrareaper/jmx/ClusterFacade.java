/*
 * Copyright 2018-2019 The Last Pickle Ltd
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

package io.cassandrareaper.jmx;

import io.cassandrareaper.AppContext;
import io.cassandrareaper.ReaperApplicationConfiguration.DatacenterAvailability;
import io.cassandrareaper.ReaperException;
import io.cassandrareaper.core.Cluster;
import io.cassandrareaper.core.Compaction;
import io.cassandrareaper.core.DroppedMessages;
import io.cassandrareaper.core.GenericMetric;
import io.cassandrareaper.core.JmxStat;
import io.cassandrareaper.core.MetricsHistogram;
import io.cassandrareaper.core.Node;
import io.cassandrareaper.core.Segment;
import io.cassandrareaper.core.Snapshot;
import io.cassandrareaper.core.Table;
import io.cassandrareaper.core.ThreadPoolStat;
import io.cassandrareaper.resources.view.NodesStatus;
import io.cassandrareaper.service.RingRange;
import io.cassandrareaper.storage.IDistributedStorage;
import io.cassandrareaper.storage.OpType;

import java.io.IOError;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.management.JMException;
import javax.management.MalformedObjectNameException;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.tuple.Pair;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class ClusterFacade {
  private static final Logger LOG = LoggerFactory.getLogger(ClusterFacade.class);

  private static final long CLUSTER_VERSIONS_TTL_SECONDS
      = Long.getLong(ClusterFacade.class.getPackage().getName() + ".cluster_versions_ttl_seconds", 60);

  private static final long TABLES_IN_KEYSPACE_TTL_SECONDS
      = Long.getLong(ClusterFacade.class.getPackage().getName() + ".tables_in_keyspace_ttl_seconds", 60);

  private static final long TOKEN_RANGES_IN_KEYSPACE_TTL_SECONDS
      = Long.getLong(ClusterFacade.class.getPackage().getName() + ".token_ranges_in_keyspace_ttl_seconds", 60);

  private static final Cache<Pair<Cluster,String>,String> CLUSTER_VERSIONS
      = CacheBuilder.newBuilder().expireAfterWrite(CLUSTER_VERSIONS_TTL_SECONDS, TimeUnit.SECONDS).build();

  private static final Cache<Pair<Cluster,String>,Set<Table>> TABLES_IN_KEYSPACE
      = CacheBuilder.newBuilder().expireAfterWrite(TABLES_IN_KEYSPACE_TTL_SECONDS, TimeUnit.SECONDS).build();

  private static final Cache<Pair<Cluster,String>,Map<List<String>, List<String>>> TOKEN_RANGES_IN_KEYSPACE
      = CacheBuilder.newBuilder().expireAfterWrite(TOKEN_RANGES_IN_KEYSPACE_TTL_SECONDS, TimeUnit.SECONDS).build();

  private static final String LOCALHOST = "127.0.0.1";
  private final AppContext context;

  private ClusterFacade(AppContext context) {
    this.context = context;
  }

  public static ClusterFacade create(AppContext context) {
    return new ClusterFacade(context);
  }

  private JmxProxy connectAnyNode(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    return context.jmxConnectionFactory.connectAny(
        endpoints
            .stream()
            .map(host -> Node.builder().withCluster(cluster).withHostname(host).build())
            .collect(Collectors.toList()));
  }

  private JmxProxy connectNode(Node node) throws ReaperException, InterruptedException {
    if (context.config.isInSidecarMode()) {
      return connectAndAllowSidecar(node.getCluster(), Arrays.asList(node.getHostname()));
    } else {
      return context.jmxConnectionFactory.connect(node);
    }
  }

  /**
   * Pre-heats JMX connections to all provided endpoints.
   * In EACH, LOCAL and ALL : connect directly to any available node
   * In SIDECAR : We skip that code path as we don’t need to pre-heat connections
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return a JmxProxy object
   * @throws ReaperException any runtime exception we catch
   */
  public JmxProxy preHeatJmxConnections(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    Preconditions.checkArgument(!context.config.isInSidecarMode());
    return connectAnyNode(cluster, endpoints);
  }

  /**
   * Connect to any of the provided endpoints and allow enforcing to localhost for sidecar mode.
   * In EACH, LOCAL and ALL : connect directly to any available node
   * In SIDECAR : We skip that code path as we don’t need to pre-heat connections
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return a JmxProxy object
   * @throws ReaperException any runtime exception we catch
   */
  public JmxProxy connectAndAllowSidecar(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    return connectAnyNode(cluster, enforceLocalNodeForSidecar(endpoints));
  }

  /**
   * Get the cluster name from any of the provided endpoints.
   * In EACH, LOCAL and ALL : connect directly to any available node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return the cluster name
   * @throws ReaperException any runtime exception we catch
   */
  public String getClusterName(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, enforceLocalNodeForSidecar(endpoints));
    return jmxProxy.getClusterName();
  }

  /**
   * Get the cluster name that the node belongs to.
   *
   * @param node the node to connect to
   * @return the cluster name
   * @throws ReaperException any runtime exception we catch in the process
   * @throws InterruptedException if the JMX connection gets interrupted
   */
  public String getClusterName(Node node) throws ReaperException {
    JmxProxy jmxProxy = connectAndAllowSidecar(node.getCluster(), Arrays.asList(node.getHostname()));
    return jmxProxy.getClusterName();
  }

  /**
   * Get the partitioner in use from any of the provided endpoints.
   * In EACH, LOCAL and ALL : connect directly to any available node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return the partitioner in use on the cluster
   * @throws ReaperException any runtime exception we catch
   */
  public String getPartitioner(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, enforceLocalNodeForSidecar(endpoints));
    return jmxProxy.getPartitioner();
  }

  /**
   * Get the list of live nodes in the cluster.
   * In EACH, LOCAL and ALL : connect directly to any available node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @return the list of live endpoints in the cluster
   * @throws ReaperException any runtime exception we catch
   */
  public List<String> getLiveNodes(Cluster cluster) throws ReaperException {
    return getLiveNodes(cluster, cluster.getSeedHosts());
  }

  /**
   * Get the list of live nodes in the cluster from any of the provided endpoints.
   * In EACH, LOCAL and ALL : connect directly to any available node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return the list of live endpoints in the cluster
   * @throws ReaperException any runtime exception we catch
   */
  public List<String> getLiveNodes(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, enforceLocalNodeForSidecar(endpoints));
    return jmxProxy.getLiveNodes();
  }

  /**
   * Get the status of all nodes in the cluster.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return a NodeStatus object with all nodes state
   * @throws ReaperException any runtime exception we catch
   */
  public NodesStatus getNodesStatus(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, enforceLocalNodeForSidecar(endpoints));
    FailureDetectorProxy proxy = FailureDetectorProxy.create(jmxProxy);

    return new NodesStatus(
        jmxProxy.getHost(), proxy.getAllEndpointsState(), proxy.getSimpleStates());
  }

  /**
   * Get the version of Cassandra in use in the cluster.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @return the version of Cassandra used
   * @throws ReaperException any runtime exception we catch
   */
  public String getCassandraVersion(Cluster cluster) throws ReaperException {
    return getCassandraVersion(cluster, cluster.getSeedHosts());
  }

  /**
   * Get the version of Cassandra in use in the cluster.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param endpoints the list of endpoints to connect to
   * @return the version of Cassandra used
   * @throws ReaperException any runtime exception we catch
   */
  public String getCassandraVersion(Cluster cluster, Collection<String> endpoints) throws ReaperException {
    for (String endpoint : endpoints) {
      String version = CLUSTER_VERSIONS.getIfPresent(Pair.of(cluster, endpoint));
      if (null != version) {
        return version;
      }
    }
    JmxProxy jmxProxy = connectAnyNode(cluster, enforceLocalNodeForSidecar(endpoints));
    String version = jmxProxy.getCassandraVersion();
    CLUSTER_VERSIONS.put(Pair.of(cluster, jmxProxy.getHost()), version);
    return version;
  }

  /**
   * Get the list of tokens of the cluster.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @return the list of tokens as BigInteger
   * @throws ReaperException any runtime exception we catch
   */
  public List<BigInteger> getTokens(Cluster cluster) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, enforceLocalNodeForSidecar(cluster.getSeedHosts()));
    return jmxProxy.getTokens();
  }

  /**
   * Get a map of all the token ranges with the list of replicas. In EACH, LOCAL and ALL : connect
   * directly to any provided node to get the information In SIDECAR : Enforce connecting to the
   * local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param keyspaceName the ks to get a map of token ranges for
   * @return a map of token ranges with endpoints as items
   * @throws ReaperException any runtime exception we catch
   */
  public Map<List<String>, List<String>> getRangeToEndpointMap(
      Cluster cluster,
      String keyspace) throws ReaperException {

    try {
      return TOKEN_RANGES_IN_KEYSPACE.get(Pair.of(cluster, keyspace), () -> {
        return getRangeToEndpointMapImpl(cluster, keyspace);
      });
    } catch (ExecutionException ex) {
      throw new ReaperException(ex);
    }
  }

  private Map<List<String>, List<String>> getRangeToEndpointMapImpl(
      Cluster cluster,
      String keyspace) throws ReaperException {

    return connectAndAllowSidecar(cluster, cluster.getSeedHosts()).getRangeToEndpointMap(keyspace);
  }

  /**
   * Get a list of tables for a specific keyspace.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @param keyspaceName a keyspace name
   * @return a list of table names
   * @throws ReaperException any runtime exception we catch
   */
  public Set<Table> getTablesForKeyspace(Cluster cluster, String keyspaceName) throws ReaperException {
    try {
      return TABLES_IN_KEYSPACE.get(Pair.of(cluster, keyspaceName), () -> {
        return getTablesForKeyspaceImpl(cluster, keyspaceName);
      });
    } catch (ExecutionException ex) {
      throw new ReaperException(ex);
    }
  }

  private Set<Table> getTablesForKeyspaceImpl(Cluster cluster, String keyspaceName) throws ReaperException {
    return connectAndAllowSidecar(cluster, cluster.getSeedHosts()).getTablesForKeyspace(keyspaceName);
  }

  /**
   * List all tables by keyspace.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @return a map of table names by keyspace
   * @throws ReaperException any runtime exception we catch
   */
  public Map<String, List<String>> listTablesByKeyspace(Cluster cluster) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, enforceLocalNodeForSidecar(cluster.getSeedHosts()));
    return jmxProxy.listTablesByKeyspace();
  }

  /**
   * List the keyspaces of the cluster.
   *
   * @param cluster the cluster to connect to
   * @return a list of keyspace names
   * @throws ReaperException any runtime exception
   */
  public List<String> getKeyspaces(Cluster cluster) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, enforceLocalNodeForSidecar(cluster.getSeedHosts()));
    return jmxProxy.getKeyspaces();
  }

  /**
   * Get a map of endpoints with the associated host id.
   * In EACH, LOCAL and ALL : connect directly to any provided node to get the information
   * In SIDECAR : Enforce connecting to the local node to get the information
   *
   * @param cluster the cluster to connect to
   * @return the map of endpoints to host id
   * @throws ReaperException any runtime exception we catch
   */
  public Map<String, String> getEndpointToHostId(Cluster cluster) throws ReaperException {
    JmxProxy jmxProxy = connectAnyNode(cluster, enforceLocalNodeForSidecar(cluster.getSeedHosts()));
    return jmxProxy.getEndpointToHostId();
  }

  /**
   * Get the list of replicas for a token range.
   *
   * @param cluster the cluster to connect to
   * @param keyspace the keyspace to get the replicas for
   * @param segment the token range for which we want the list of replicas
   * @return a list of endpoints
   */
  public List<String> tokenRangeToEndpoint(Cluster cluster, String keyspace, Segment segment) {
    Set<Map.Entry<List<String>, List<String>>> entries;
    try {
      entries = getRangeToEndpointMap(cluster, keyspace).entrySet();
    } catch (ReaperException e) {
      LOG.error("[tokenRangeToEndpoint] no replicas found for token range {}", segment, e);
      return Lists.newArrayList();
    }

    for (Map.Entry<List<String>, List<String>> entry : entries) {
      BigInteger rangeStart = new BigInteger(entry.getKey().get(0));
      BigInteger rangeEnd = new BigInteger(entry.getKey().get(1));
      if (new RingRange(rangeStart, rangeEnd).encloses(segment.getTokenRanges().get(0))) {
        return entry.getValue();
      }
    }
    LOG.error("[tokenRangeToEndpoint] no replicas found for token range {}", segment);
    LOG.debug("[tokenRangeToEndpoint] checked token ranges were {}", entries);
    return Lists.newArrayList();
  }

  /**
   * Get the ranges for the local node (only for sidecar mode).
   *
   * @param cluster the cluster to connect to
   * @param keyspace the keyspace we're getting the ranges for.
   * @return the list of local token ranges
   * @throws ReaperException any runtime exception we catch in the process
   */
  public List<RingRange> getRangesForLocalEndpoint(Cluster cluster, String keyspace) throws ReaperException {
    Preconditions.checkArgument(context.config.isInSidecarMode(), "This method is only allowed in sidecar mode");
    List<RingRange> localRanges = Lists.newArrayList();
    Map<List<String>, List<String>> ranges = getRangeToEndpointMap(cluster, keyspace);
    JmxProxy jmxProxy = connectAndAllowSidecar(cluster, Arrays.asList(LOCALHOST));
    String localEndpoint = jmxProxy.getLocalEndpoint();
    // Filtering ranges for which the local node is a replica
    // For local mode
    ranges
        .entrySet()
        .stream()
        .forEach(entry -> {
          if (entry.getValue().contains(localEndpoint)) {
            localRanges.add(
                new RingRange(new BigInteger(entry.getKey().get(0)), new BigInteger(entry.getKey().get(1))));
          }
        });
    return localRanges;
  }

  /**
   * Get the datacenter of a specific endpoint.
   *
   * @param cluster the cluster to connect to
   * @param endpoint the node which we're trying to locate in the topology
   * @return the datacenter this endpoint belongs to
   * @throws ReaperException any runtime exception we catch in the process
   */
  public String getDatacenter(Cluster cluster, String endpoint) throws ReaperException {
    JmxProxy jmxProxy = connectAndAllowSidecar(cluster, cluster.getSeedHosts());
    return EndpointSnitchInfoProxy.create(jmxProxy).getDataCenter(endpoint);
  }

  /**
   * Get the datacenter of a specific endpoint.
   *
   * @param node the node to connect to
   * @return the datacenter this endpoint belongs to
   * @throws ReaperException any runtime exception we catch in the process
   * @throws InterruptedException in case the JMX connection gets interrupted
   */
  public String getDatacenter(Node node) throws ReaperException, InterruptedException {
    JmxProxy jmxProxy = connectNode(node);
    return EndpointSnitchInfoProxy.create(jmxProxy).getDataCenter();
  }

  /**
   * Get the endpoint name/ip indentifying the node in the cluster.
   *
   * @param node the node to connect to
   * @return the endpoint as a string
   * @throws ReaperException any runtime exception we catch in the process
   * @throws InterruptedException if the JMX connection gets interrupted
   */
  public String getLocalEndpoint(Node node) throws ReaperException, InterruptedException {
    JmxProxy jmxProxy = connectNode(node);
    return jmxProxy.getLocalEndpoint();
  }

  /**
   * Retrieve a map of endpoints with the associated tokens.
   *
   * @param cluster the cluster we want to retrieve the tokens from
   * @return a map of nodes with the list of tokens as items
   * @throws ReaperException any runtime exception we catch in the process
   */
  public Map<String, List<String>> getTokensByNode(Cluster cluster) throws ReaperException {
    JmxProxy jmxProxy = connectAndAllowSidecar(cluster, cluster.getSeedHosts());
    return StorageServiceProxy.create(jmxProxy).getTokensByNode();
  }

  /**
   * List running compactions on a specific node either through JMX or through the backend.
   *
   * @param node the node to get the compactions from.
   * @return a list of compactions
   * @throws MalformedObjectNameException ¯\_(ツ)_/¯
   * @throws ReflectionException ¯\_(ツ)_/¯
   * @throws ReaperException any runtime exception we catch in the process
   * @throws InterruptedException in case the JMX connection gets interrupted
   * @throws IOException errors in parsing JSON encoded compaction objects
   */
  public List<Compaction> listActiveCompactions(Node node)
      throws MalformedObjectNameException, ReflectionException, ReaperException,
          InterruptedException {
    String nodeDc = getDatacenter(node);
    if (nodeIsAccessibleThroughJmx(nodeDc, node.getHostname())) {
      // We have direct JMX access to the node
      return listActiveCompactionsDirect(node);
    } else {
      // We don't have access to the node through JMX, so we'll get data from the database
      LOG.info("Node {} in DC {} is not accessible through JMX", node.getHostname(), nodeDc);
      String compactionsJson
          = ((IDistributedStorage)context.storage).listOperations(
              node.getCluster().getName(), OpType.OP_COMPACTION, node.getHostname());
      return parseJson(compactionsJson, new TypeReference<List<Compaction>>(){});
    }
  }

  /**
   * List running compactions on a specific node by connecting directly to it through JMX.
   *
   * @param node the node to get the compactions from.
   * @return a list of compactions
   * @throws MalformedObjectNameException ¯\_(ツ)_/¯
   * @throws ReflectionException ¯\_(ツ)_/¯
   * @throws ReaperException any runtime exception we catch in the process
   * @throws InterruptedException in case the JMX connection gets interrupted
   */
  public List<Compaction> listActiveCompactionsDirect(Node node)
      throws ReaperException, MalformedObjectNameException,
          ReflectionException {
    JmxProxy jmxProxy = connectAndAllowSidecar(node.getCluster(), Arrays.asList(node.getHostname()));
    return CompactionProxy.create(jmxProxy, context.metricRegistry).listActiveCompactions();
  }

  /**
   * Replaces the list of endpoints with LOCALHOST if we're in sidecar mode.
   *
   * @param endpoints the list of nodes to connect to
   * @return a list of endpoints possibly replaced by LOCALHOST only
   */
  private List<String> enforceLocalNodeForSidecar(Collection<String> endpoints) {
    List<String> actualEndpoints = new ArrayList<String>(endpoints);
    if (context.config.isInSidecarMode()) {
      actualEndpoints = Arrays.asList(context.config.getEnforcedLocalNode().orElse(LOCALHOST));
    }
    return actualEndpoints;
  }

  /**
   * Checks if Reaper can access the target node through JMX directly.
   * The result will depend on the chosen datacenterAvailability setting and the datacenter the node belongs to.
   *
   * @param nodeDc datacenter of the target node
   * @param node the target node
   * @return true if the node is supposedly accessible through JMX, otherwise false
   */
  public boolean nodeIsAccessibleThroughJmx(String nodeDc, String node) {
    return DatacenterAvailability.ALL == context.config.getDatacenterAvailability()
        || (Arrays.asList(DatacenterAvailability.EACH, DatacenterAvailability.LOCAL)
            .contains(context.config.getDatacenterAvailability())
            && context.jmxConnectionFactory.getAccessibleDatacenters().contains(nodeDc))
        || (DatacenterAvailability.SIDECAR == context.config.getDatacenterAvailability()
            && node.equals(context.getLocalNodeAddress()));
  }

  /**
   * Collect a set of metrics through JMX on a specific node.
   *
   * @param node the node to collect metrics on
   * @param collectedMetrics the list of metrics to collect
   * @return the list of collected metrics
   * @throws ReaperException any runtime exception we catch in the process
   */
  public Map<String, List<JmxStat>> collectMetrics(Node node, String[] collectedMetrics) throws ReaperException {
    try {
      JmxProxy jmxProxy = connectAndAllowSidecar(node.getCluster(), Arrays.asList(node.getHostname()));
      MetricsProxy proxy = MetricsProxy.create(jmxProxy);
      return proxy.collectMetrics(collectedMetrics);
    } catch (JMException | IOException e) {
      LOG.error("Failed collecting metrics for host {}", node, e);
      throw new ReaperException(e);
    }
  }

  /**
   * Collect ClientRequest metrics through JMX on a specific node.
   *
   * @param node the node to collect metrics on
   * @param collectedMetrics the list of metrics to collect
   * @return the list of collected metrics
   * @throws ReaperException any runtime exception we catch in the process
   */
  public List<MetricsHistogram> getClientRequestLatencies(Node node) throws ReaperException {
    try {
      String nodeDc = getDatacenter(node);
      if (nodeIsAccessibleThroughJmx(nodeDc, node.getHostname())) {
        MetricsProxy metricsProxy = MetricsProxy.create(connectNode(node));
        return convertToMetricsHistogram(
            MetricsProxy.convertToGenericMetrics(metricsProxy.collectLatencyMetrics(), node));
      } else {
        return convertToMetricsHistogram(((IDistributedStorage)context.storage)
            .getMetrics(
                node.getCluster().getName(),
                Optional.of(node.getHostname()),
                "org.apache.cassandra.metrics",
                "ClientRequest",
                DateTime.now().minusMinutes(1).getMillis()));
      }
    } catch (JMException | InterruptedException | IOException e) {
      LOG.error("Failed collecting tpstats for host {}", node, e);
      throw new ReaperException(e);
    }
  }

  /**
   * List dropped messages metrics
   *
   * @param node the node to get the metrics from
   * @return a list of dropped messages metrics objects
   * @throws ReaperException any runtime exception we can catch in the process
   */
  public List<DroppedMessages> getDroppedMessages(Node node) throws ReaperException {
    try {
      String nodeDc = getDatacenter(node);
      if (nodeIsAccessibleThroughJmx(nodeDc, node.getHostname())) {
        MetricsProxy proxy = MetricsProxy.create(connectNode(node));
        return convertToDroppedMessages(MetricsProxy.convertToGenericMetrics(proxy.collectDroppedMessages(), node));
      } else {
        return convertToDroppedMessages(((IDistributedStorage)context.storage)
            .getMetrics(
                node.getCluster().getName(),
                Optional.of(node.getHostname()),
                "org.apache.cassandra.metrics",
                "DroppedMessage",
                DateTime.now().minusMinutes(1).getMillis()));
      }
    } catch (JMException | InterruptedException | IOException e) {
      LOG.error("Failed collecting tpstats for host {}", node, e);
      throw new ReaperException(e);
    }
  }

  @VisibleForTesting
  public List<DroppedMessages> convertToDroppedMessages(List<GenericMetric> metrics) {
    List<DroppedMessages> droppedMessages = Lists.newArrayList();
    Map<String, List<GenericMetric>> metricsByScope
        = metrics.stream().collect(Collectors.groupingBy(GenericMetric::getMetricScope));
    for (Entry<String, List<GenericMetric>> pool : metricsByScope.entrySet()) {
      DroppedMessages.Builder builder = DroppedMessages.builder().withName(pool.getKey());
      for (GenericMetric stat : pool.getValue()) {
        builder = MetricsProxy.updateGenericMetricAttribute(stat, builder);
      }
      droppedMessages.add(builder.build());
    }
    return droppedMessages;
  }

  /**
   * List thread pool stats metrics.
   *
   * @param node the node to get the metrics from
   * @return a list of thread pools stats metrics objects
   * @throws ReaperException any runtime exception we can catch in the process
   */
  public List<ThreadPoolStat> getTpStats(Node node) throws ReaperException {
    try {
      String nodeDc = getDatacenter(node);
      if (nodeIsAccessibleThroughJmx(nodeDc, node.getHostname())) {
        MetricsProxy proxy = MetricsProxy.create(connectNode(node));
        return convertToThreadPoolStats(MetricsProxy.convertToGenericMetrics(proxy.collectTpStats(), node));
      } else {
        return convertToThreadPoolStats(((IDistributedStorage)context.storage)
            .getMetrics(
                node.getCluster().getName(),
                Optional.of(node.getHostname()),
                "org.apache.cassandra.metrics",
                "ThreadPools",
                DateTime.now().minusMinutes(1).getMillis()));
      }
    } catch (JMException | InterruptedException | IOException e) {
      LOG.error("Failed collecting tpstats for host {}", node, e);
      throw new ReaperException(e);
    }
  }

  @VisibleForTesting
  public List<ThreadPoolStat> convertToThreadPoolStats(List<GenericMetric> metrics) {
    List<ThreadPoolStat> tpstats = Lists.newArrayList();
    Map<String, List<GenericMetric>> metricsByScope
        = metrics.stream().collect(Collectors.groupingBy(GenericMetric::getMetricScope));
    for (Entry<String, List<GenericMetric>> pool : metricsByScope.entrySet()) {
      ThreadPoolStat.Builder builder = ThreadPoolStat.builder().withName(pool.getKey());
      for (GenericMetric stat : pool.getValue()) {
        builder = MetricsProxy.updateGenericMetricAttribute(stat, builder);
      }
      tpstats.add(builder.build());
    }
    return tpstats;
  }

  @VisibleForTesting
  public List<MetricsHistogram> convertToMetricsHistogram(List<GenericMetric> metrics) {
    List<MetricsHistogram> histograms = Lists.newArrayList();
    // We have several metric types that we need to process separately
    // We'll group on MetricsHistogram::getType in order to generate one histogram per type
    Map<String, List<GenericMetric>> metricsByScope
        = metrics.stream().collect(Collectors.groupingBy(GenericMetric::getMetricScope));

    for (Entry<String, List<GenericMetric>> metricByScope : metricsByScope.entrySet()) {
      Map<String, List<GenericMetric>> metricsByName
          = metricByScope
              .getValue()
              .stream()
              .collect(Collectors.groupingBy(GenericMetric::getMetricName));
      for (Entry<String, List<GenericMetric>> metricByName : metricsByName.entrySet()) {
        MetricsHistogram.Builder builder
            = MetricsHistogram.builder()
                .withName(metricByScope.getKey())
                .withType(metricByName.getKey());
        for (GenericMetric stat : metricByName.getValue()) {
          builder = MetricsProxy.updateGenericMetricAttribute(stat, builder);
        }
        histograms.add(builder.build());
      }
    }
    return histograms;
  }

  /**
   * Take a snapshot on one node.
   *
   * @param snapshotName the name of the snapshot
   * @param host the node to take the snapshot on
   * @param keyspaces the list of keyspaces to snapshot
   * @return a tuple of the node object and the snapshot name
   * @throws ReaperException  any runtime exception we can catch in the process
   */
  public Pair<Node, String> takeSnapshot(String snapshotName, Node host, String... keyspaces) throws ReaperException {
    try {
      Preconditions.checkArgument(!context.config.isInSidecarMode(), "Snapshots aren't yet supported in sidecar mode");
      JmxProxy jmx = connectNode(host);
      SnapshotProxy snapshotProxy = SnapshotProxy.create(jmx);
      LOG.info("Taking snapshot for node {} and keyspace {}", host, keyspaces);
      return Pair.of(host, snapshotProxy.takeSnapshot(snapshotName, keyspaces));
    } catch (InterruptedException e) {
      LOG.error("Interrupted taking snapshot for host {} and keyspaces {}", host, keyspaces, e);
      throw new ReaperException(e);
    }
  }

  /**
   * List all snapshots on a node.
   *
   * @param host the node to list the snapshot from
   * @return a list of snapshots
   * @throws ReaperException any runtime exception we can catch in the process
   */
  public List<Snapshot> listSnapshots(Node host) throws ReaperException {
    try {
      JmxProxy jmx = connectNode(host);
      SnapshotProxy snapshotProxy = SnapshotProxy.create(jmx);

      return snapshotProxy.listSnapshots();

    } catch (UnsupportedOperationException unsupported) {
      LOG.debug("Listing snapshot is unsupported with Cassandra 2.0 and prior");
      throw unsupported;
    } catch (InterruptedException e) {
      LOG.error("Interrupted listing snapshots for host {}", host, e);
      throw new ReaperException(e);
    }
  }

  /**
   * Delete a given snapshot from a node.
   *
   * @param snapshotName the name of the snapshot to clear
   * @param host the node to delete the snapshot from
   * @throws ReaperException any runtime exception we can catch in the process
   */
  public void clearSnapshot(String snapshotName, Node host) throws ReaperException {
    try {
      JmxProxy jmx = connectNode(host);
      SnapshotProxy snapshotProxy = SnapshotProxy.create(jmx);
      snapshotProxy.clearSnapshot(snapshotName);
    } catch (IOError e) {
      // StorageService.clearSnapshot(..) throws a FSWriteError when snapshot already deleted
      LOG.info("already cleared snapshot " + snapshotName, e);
    } catch (InterruptedException e) {
      LOG.error("Interrupted clearing snapshot {} for host {}", snapshotName, host, e);
      throw new ReaperException(e);
    }
  }

  /**
   * List ongoing streams on a specific node either through JMX or through the backend.
   *
   * @param node the node to get the streams from.
   * @return a set of streams
   * @throws ReaperException any runtime exception we catch in the process
   * @throws InterruptedException in case the JMX connection gets interrupted
   * @throws IOException errors in parsing JSON encoded compaction objects
   */
  public Set<CompositeData> listActiveStreams(Node node)
      throws ReaperException, InterruptedException {
    String nodeDc = getDatacenter(node);
    if (nodeIsAccessibleThroughJmx(nodeDc, node.getHostname())) {
      // We have direct JMX access to the node
      return listStreamsDirect(node);
    } else {
      // We don't have access to the node through JMX, so we'll get data from the database
      LOG.info("Node {} in DC {} is not accessible through JMX", node.getHostname(), nodeDc);
      String streamsJson = ((IDistributedStorage) context.storage)
          .listOperations(node.getCluster().getName(), OpType.OP_STREAMING ,node.getHostname());
      return parseJson(streamsJson, new TypeReference<Set<CompositeData>>(){});
    }
  }

  /**
   * List the ongoing streaming operations on a node.
   *
   * @param node the node to list streamings on
   * @return a list of CompositeData objects with streaming information
   * @throws ReaperException any runtime exception we can catch in the process
   */
  public Set<CompositeData> listStreamsDirect(Node node) throws ReaperException {
    JmxProxy jmxProxy = connectAndAllowSidecar(node.getCluster(), Arrays.asList(node.getHostname()));
    return StreamsProxy.create(jmxProxy).listStreams();
  }

  private static <T> T parseJson(String json, TypeReference<T> ref) {
    try {
      return new ObjectMapper().readValue(json, ref);
    } catch (IOException e) {
      LOG.error("error parsing json", e);
      throw new RuntimeException(e);
    }
  }
}
