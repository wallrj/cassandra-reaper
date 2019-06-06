/*
 * Copyright 2015-2017 Spotify AB
 * Copyright 2016-2019 The Last Pickle Ltd
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
import io.cassandrareaper.ReaperApplicationConfiguration;
import io.cassandrareaper.ReaperApplicationConfiguration.DatacenterAvailability;
import io.cassandrareaper.ReaperException;
import io.cassandrareaper.core.Cluster;
import io.cassandrareaper.core.ClusterProperties;
import io.cassandrareaper.core.Node;
import io.cassandrareaper.core.NodeMetrics;
import io.cassandrareaper.core.RepairRun;
import io.cassandrareaper.core.RepairSegment;
import io.cassandrareaper.core.RepairUnit;
import io.cassandrareaper.core.Segment;
import io.cassandrareaper.jmx.ClusterFacade;
import io.cassandrareaper.jmx.JmxConnectionFactory;
import io.cassandrareaper.jmx.JmxProxy;
import io.cassandrareaper.jmx.JmxProxyTest;
import io.cassandrareaper.jmx.RepairStatusHandler;
import io.cassandrareaper.storage.CassandraStorage;
import io.cassandrareaper.storage.IDistributedStorage;
import io.cassandrareaper.storage.IStorage;
import io.cassandrareaper.storage.MemoryStorage;

import java.math.BigInteger;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.cassandra.locator.EndpointSnitchInfoMBean;
import org.apache.cassandra.service.ActiveRepairService;
import org.apache.cassandra.utils.progress.ProgressEventType;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.apache.cassandra.repair.RepairParallelism.DATACENTER_AWARE;
import static org.apache.cassandra.repair.RepairParallelism.PARALLEL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class SegmentRunnerTest {
  // TODO: Clean up tests. There's a lot of code duplication across these tests.

  private static final Set<String> TABLES = ImmutableSet.of("table1");
  private static final Set<String> COORDS = Collections.singleton("");

  @Before
  public void setUp() throws Exception {
    SegmentRunner.SEGMENT_RUNNERS.clear();
  }

  @Test
  public void timeoutTest() throws InterruptedException, ReaperException, ExecutionException {
    final AppContext context = new AppContext();
    context.config = Mockito.mock(ReaperApplicationConfiguration.class);
    when(context.config.getJmxConnectionTimeoutInSeconds()).thenReturn(30);
    when(context.config.getDatacenterAvailability()).thenReturn(DatacenterAvailability.ALL);
    context.storage = new MemoryStorage();

    RepairUnit cf = context.storage.addRepairUnit(
            RepairUnit.builder()
                .clusterName("reaper")
                .keyspaceName("reaper")
                .columnFamilies(Sets.newHashSet("reaper"))
                .incrementalRepair(false)
                .nodes(Sets.newHashSet("127.0.0.1"))
                .repairThreadCount(1));

    RepairRun run = context.storage.addRepairRun(
            RepairRun.builder("reaper", cf.getId())
                .intensity(0.5)
                .segmentCount(1)
                .repairParallelism(PARALLEL)
                .tables(TABLES),
            Collections.singleton(
                RepairSegment.builder(
                    Segment.builder()
                        .withTokenRange(new RingRange(BigInteger.ONE, BigInteger.ZERO))
                        .build(),
                    cf.getId())));

    context.storage.addCluster(
        new Cluster(cf.getClusterName(), Optional.of("murmur3"), cf.getNodes(),
            ClusterProperties.builder().withJmxPort(7199).build()));

    final UUID runId = run.getId();
    final UUID segmentId = context.storage.getNextFreeSegmentInRange(run.getId(),
        Optional.empty()).get().getId();

    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final MutableObject<Future<?>> future = new MutableObject<>();

    final JmxProxy jmx = JmxProxyTest.mockJmxProxyImpl();
    when(jmx.getClusterName()).thenReturn("reaper");
    when(jmx.isConnectionAlive()).thenReturn(true);

    EndpointSnitchInfoMBean endpointSnitchInfoMBean = mock(EndpointSnitchInfoMBean.class);
    when(endpointSnitchInfoMBean.getDatacenter()).thenReturn("dc1");
    try {
      when(endpointSnitchInfoMBean.getDatacenter(anyString())).thenReturn("dc1");
    } catch (UnknownHostException ex) {
      throw new AssertionError(ex);
    }
    JmxProxyTest.mockGetEndpointSnitchInfoMBean(jmx, endpointSnitchInfoMBean);

    when(jmx.triggerRepair(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .then(
            (invocation) -> {
              assertEquals(
                  RepairSegment.State.STARTED,
                  context.storage.getRepairSegment(runId, segmentId).get().getState());

              future.setValue(
                  executor.submit(
                      new Thread() {
                        @Override
                        public void run() {
                          ((RepairStatusHandler)invocation.getArgument(7))
                              .handle(
                                  1,
                                  Optional.of(ActiveRepairService.Status.STARTED),
                                  Optional.empty(),
                                  "Repair command 1 has started",
                                  jmx);

                          assertEquals(
                              RepairSegment.State.RUNNING,
                              context.storage.getRepairSegment(runId, segmentId).get().getState());
                        }
                      }));
              return 1;
            });

    context.jmxConnectionFactory = new JmxConnectionFactory(context) {
          @Override
          public JmxProxy connectImpl(Node host) throws ReaperException {
            return jmx;
          }
        };

    RepairRunner rr = mock(RepairRunner.class);
    RepairUnit ru = mock(RepairUnit.class);
    when(ru.getKeyspaceName()).thenReturn("reaper");

    ClusterFacade clusterFacade = mock(ClusterFacade.class);
    when(clusterFacade.connectAndAllowSidecar(any(), any())).thenReturn(jmx);
    when(clusterFacade.nodeIsAccessibleThroughJmx(any(), any())).thenReturn(true);

    when(clusterFacade.tokenRangeToEndpoint(any(), anyString(), any()))
        .thenReturn(Lists.newArrayList(cf.getNodes()));

    when(clusterFacade.nodeIsAccessibleThroughJmx(any(), any())).thenReturn(true);

    SegmentRunner sr = SegmentRunner
        .create(context, clusterFacade, segmentId, COORDS, 100, 0.5, PARALLEL, "reaper", ru, TABLES, rr);

    sr.run();

    future.getValue().get();
    executor.shutdown();

    assertEquals(RepairSegment.State.NOT_STARTED, context.storage.getRepairSegment(runId, segmentId).get().getState());
    assertEquals(1, context.storage.getRepairSegment(runId, segmentId).get().getFailCount());
  }

  @Test
  public void successTest() throws InterruptedException, ReaperException, ExecutionException {
    final IStorage storage = new MemoryStorage();

    RepairUnit cf = storage.addRepairUnit(
            RepairUnit.builder()
                .clusterName("reaper")
                .keyspaceName("reaper")
                .columnFamilies(Sets.newHashSet("reaper"))
                .incrementalRepair(false)
                .nodes(Sets.newHashSet("127.0.0.1"))
                .repairThreadCount(1));

    RepairRun run = storage.addRepairRun(
            RepairRun.builder("reaper", cf.getId())
                .intensity(0.5)
                .segmentCount(1)
                .repairParallelism(PARALLEL)
                .tables(TABLES),
            Collections.singleton(
                RepairSegment.builder(
                    Segment.builder()
                        .withTokenRange(new RingRange(BigInteger.ONE, BigInteger.ZERO))
                        .build(),
                    cf.getId())));

    storage.addCluster(new Cluster(cf.getClusterName(), Optional.of("murmur3"), cf.getNodes(),
            ClusterProperties.builder().withJmxPort(7199).build()));

    final UUID runId = run.getId();
    final UUID segmentId = storage.getNextFreeSegmentInRange(run.getId(), Optional.empty()).get().getId();
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final MutableObject<Future<?>> future = new MutableObject<>();

    AppContext context = new AppContext();
    context.storage = storage;
    context.config = Mockito.mock(ReaperApplicationConfiguration.class);
    when(context.config.getJmxConnectionTimeoutInSeconds()).thenReturn(30);
    when(context.config.getDatacenterAvailability()).thenReturn(DatacenterAvailability.ALL);

    final JmxProxy jmx = JmxProxyTest.mockJmxProxyImpl();
    when(jmx.getClusterName()).thenReturn("reaper");
    when(jmx.isConnectionAlive()).thenReturn(true);
    EndpointSnitchInfoMBean endpointSnitchInfoMBean = mock(EndpointSnitchInfoMBean.class);
    when(endpointSnitchInfoMBean.getDatacenter()).thenReturn("dc1");
    try {
      when(endpointSnitchInfoMBean.getDatacenter(anyString())).thenReturn("dc1");
    } catch (UnknownHostException ex) {
      throw new AssertionError(ex);
    }
    JmxProxyTest.mockGetEndpointSnitchInfoMBean(jmx, endpointSnitchInfoMBean);

    when(jmx.triggerRepair(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .then(
            (invocation) -> {
              assertEquals(
                  RepairSegment.State.STARTED,
                  storage.getRepairSegment(runId, segmentId).get().getState());

              future.setValue(
                  executor.submit(
                      () -> {
                        ((RepairStatusHandler)invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.STARTED),
                                Optional.empty(),
                                "Repair command 1 has started",
                                jmx);

                        assertEquals(
                            RepairSegment.State.RUNNING,
                            storage.getRepairSegment(runId, segmentId).get().getState());

                        // test an unrelated repair. Should throw exception
                        try {
                          ((RepairStatusHandler)invocation.getArgument(7))
                              .handle(
                                  2,
                                  Optional.of(ActiveRepairService.Status.SESSION_FAILED),
                                  Optional.empty(),
                                  "Repair command 2 has failed",
                                  jmx);

                          throw new AssertionError("illegal handle of wrong repairNo");
                        } catch (IllegalArgumentException ignore) { }

                        ((RepairStatusHandler)invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.SESSION_SUCCESS),
                                Optional.empty(),
                                "Repair session succeeded in command 1",
                                jmx);

                        assertEquals(
                            RepairSegment.State.DONE,
                            storage.getRepairSegment(runId, segmentId).get().getState());

                        ((RepairStatusHandler)invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.FINISHED),
                                Optional.empty(),
                                "Repair command 1 has finished",
                                jmx);

                        assertEquals(
                            RepairSegment.State.DONE,
                            storage.getRepairSegment(runId, segmentId).get().getState());
                      }));
              return 1;
            });

    context.jmxConnectionFactory = new JmxConnectionFactory(context) {
          @Override
          protected JmxProxy connectImpl(Node host) throws ReaperException {
            return jmx;
          }
        };

    RepairRunner rr = mock(RepairRunner.class);
    RepairUnit ru = mock(RepairUnit.class);
    when(ru.getKeyspaceName()).thenReturn("reaper");

    ClusterFacade clusterFacade = mock(ClusterFacade.class);
    when(clusterFacade.connectAndAllowSidecar(any(), any())).thenReturn(jmx);
    when(clusterFacade.nodeIsAccessibleThroughJmx(any(), any())).thenReturn(true);

    when(clusterFacade.tokenRangeToEndpoint(any(), anyString(), any()))
        .thenReturn(Lists.newArrayList(cf.getNodes()));

    SegmentRunner sr = SegmentRunner
        .create(context, clusterFacade, segmentId, COORDS, 5000, 0.5, PARALLEL, "reaper", ru, TABLES, rr);

    sr.run();

    future.getValue().get();
    executor.shutdown();

    assertEquals(RepairSegment.State.DONE, storage.getRepairSegment(runId, segmentId).get().getState());
    assertEquals(0, storage.getRepairSegment(runId, segmentId).get().getFailCount());
  }

  @Test
  public void failureTest() throws InterruptedException, ReaperException, ExecutionException {
    final IStorage storage = new MemoryStorage();

    RepairUnit cf = storage.addRepairUnit(
            RepairUnit.builder()
                .clusterName("reaper")
                .keyspaceName("reaper")
                .columnFamilies(Sets.newHashSet("reaper"))
                .incrementalRepair(false)
                .nodes(Sets.newHashSet("127.0.0.1"))
                .repairThreadCount(1));

    RepairRun run = storage.addRepairRun(
            RepairRun.builder("reaper", cf.getId())
                .intensity(0.5)
                .segmentCount(1)
                .repairParallelism(PARALLEL)
                .tables(TABLES),
            Collections.singleton(
                RepairSegment.builder(
                    Segment.builder()
                        .withTokenRange(new RingRange(BigInteger.ONE, BigInteger.ZERO))
                        .build(),
                    cf.getId())));

    storage.addCluster(new Cluster(cf.getClusterName(), Optional.of("murmur3"), cf.getNodes(),
            ClusterProperties.builder().withJmxPort(7199).build()));

    final UUID runId = run.getId();
    final UUID segmentId = storage.getNextFreeSegmentInRange(run.getId(), Optional.empty()).get().getId();

    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final MutableObject<Future<?>> future = new MutableObject<>();

    AppContext context = new AppContext();
    context.storage = storage;
    context.config = Mockito.mock(ReaperApplicationConfiguration.class);
    when(context.config.getJmxConnectionTimeoutInSeconds()).thenReturn(30);
    when(context.config.getDatacenterAvailability()).thenReturn(DatacenterAvailability.ALL);

    final JmxProxy jmx = JmxProxyTest.mockJmxProxyImpl();
    when(jmx.getClusterName()).thenReturn("reaper");
    when(jmx.isConnectionAlive()).thenReturn(true);

    EndpointSnitchInfoMBean endpointSnitchInfoMBean = mock(EndpointSnitchInfoMBean.class);
    when(endpointSnitchInfoMBean.getDatacenter()).thenReturn("dc1");
    try {
      when(endpointSnitchInfoMBean.getDatacenter(anyString())).thenReturn("dc1");
    } catch (UnknownHostException ex) {
      throw new AssertionError(ex);
    }
    JmxProxyTest.mockGetEndpointSnitchInfoMBean(jmx, endpointSnitchInfoMBean);

    when(jmx.triggerRepair(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .then(
            (invocation) -> {
              assertEquals(
                  RepairSegment.State.STARTED,
                  storage.getRepairSegment(runId, segmentId).get().getState());

              future.setValue(
                  executor.submit(
                      () -> {
                        ((RepairStatusHandler)invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.STARTED),
                                Optional.empty(),
                                "Repair command 1 has started",
                                jmx);

                        assertEquals(
                            RepairSegment.State.RUNNING,
                            storage.getRepairSegment(runId, segmentId).get().getState());

                        ((RepairStatusHandler)invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.SESSION_FAILED),
                                Optional.empty(),
                                "Repair command 1 has failed",
                                jmx);

                        assertEquals(
                            RepairSegment.State.NOT_STARTED,
                            storage.getRepairSegment(runId, segmentId).get().getState());

                        ((RepairStatusHandler)invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.FINISHED),
                                Optional.empty(),
                                "Repair command 1 has finished",
                                jmx);

                        assertEquals(
                            RepairSegment.State.NOT_STARTED,
                            storage.getRepairSegment(runId, segmentId).get().getState());
                      }));

              return 1;
            });

    context.jmxConnectionFactory = new JmxConnectionFactory(context) {
          @Override
          protected JmxProxy connectImpl(Node host) throws ReaperException {
            return jmx;
          }
        };

    RepairRunner rr = mock(RepairRunner.class);
    RepairUnit ru = mock(RepairUnit.class);
    when(ru.getKeyspaceName()).thenReturn("reaper");

    ClusterFacade clusterFacade = mock(ClusterFacade.class);
    when(clusterFacade.connectAndAllowSidecar(any(), any())).thenReturn(jmx);
    when(clusterFacade.nodeIsAccessibleThroughJmx(any(), any())).thenReturn(true);

    when(clusterFacade.tokenRangeToEndpoint(any(), anyString(), any()))
        .thenReturn(Lists.newArrayList(cf.getNodes()));

    SegmentRunner sr = SegmentRunner
        .create(context, clusterFacade, segmentId, COORDS, 5000, 0.5, PARALLEL, "reaper", ru, TABLES, rr);

    sr.run();

    future.getValue().get();
    executor.shutdown();

    assertEquals(RepairSegment.State.NOT_STARTED, storage.getRepairSegment(runId, segmentId).get().getState());
    assertEquals(2, storage.getRepairSegment(runId, segmentId).get().getFailCount());
  }

  @Test
  public void outOfOrderSuccessCass21Test()
      throws InterruptedException, ReaperException, ExecutionException {
    final IStorage storage = new MemoryStorage();

    RepairUnit cf = storage.addRepairUnit(
            RepairUnit.builder()
                .clusterName("reaper")
                .keyspaceName("reaper")
                .columnFamilies(Sets.newHashSet("reaper"))
                .incrementalRepair(false)
                .nodes(Sets.newHashSet("127.0.0.1"))
                .repairThreadCount(1));

    RepairRun run = storage.addRepairRun(
            RepairRun.builder("reaper", cf.getId())
                .intensity(0.5)
                .segmentCount(1)
                .repairParallelism(PARALLEL)
                .tables(TABLES),
            Collections.singleton(
                RepairSegment.builder(
                    Segment.builder()
                        .withTokenRange(new RingRange(BigInteger.ONE, BigInteger.ZERO))
                        .build(),
                    cf.getId())));

    storage.addCluster(new Cluster(cf.getClusterName(), Optional.of("murmur3"), cf.getNodes(),
            ClusterProperties.builder().withJmxPort(7199).build()));

    final UUID runId = run.getId();
    final UUID segmentId = storage.getNextFreeSegmentInRange(run.getId(), Optional.empty()).get().getId();

    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final MutableObject<Future<?>> future = new MutableObject<>();

    AppContext context = new AppContext();
    context.storage = storage;
    context.config = Mockito.mock(ReaperApplicationConfiguration.class);
    when(context.config.getJmxConnectionTimeoutInSeconds()).thenReturn(30);
    when(context.config.getDatacenterAvailability()).thenReturn(DatacenterAvailability.ALL);

    final JmxProxy jmx = JmxProxyTest.mockJmxProxyImpl();
    when(jmx.getClusterName()).thenReturn("reaper");
    when(jmx.isConnectionAlive()).thenReturn(true);

    EndpointSnitchInfoMBean endpointSnitchInfoMBean = mock(EndpointSnitchInfoMBean.class);
    when(endpointSnitchInfoMBean.getDatacenter()).thenReturn("dc1");
    try {
      when(endpointSnitchInfoMBean.getDatacenter(anyString())).thenReturn("dc1");
    } catch (UnknownHostException ex) {
      throw new AssertionError(ex);
    }
    JmxProxyTest.mockGetEndpointSnitchInfoMBean(jmx, endpointSnitchInfoMBean);

    when(jmx.triggerRepair(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .then(
            invocation -> {
              assertEquals(
                  RepairSegment.State.STARTED,
                  storage.getRepairSegment(runId, segmentId).get().getState());

              future.setValue(
                  executor.submit(
                      () -> {
                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.STARTED),
                                Optional.empty(),
                                "Repair command 1 has started",
                                jmx);

                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.FINISHED),
                                Optional.empty(),
                                "Repair command 1 has finished",
                                jmx);

                        assertEquals(
                            RepairSegment.State.RUNNING,
                            storage.getRepairSegment(runId, segmentId).get().getState());

                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.SESSION_SUCCESS),
                                Optional.empty(),
                                "Repair session succeeded in command 1",
                                jmx);

                        assertEquals(
                            RepairSegment.State.DONE,
                            storage.getRepairSegment(runId, segmentId).get().getState());
                      }));
              return 1;
            });

    context.jmxConnectionFactory = new JmxConnectionFactory(context) {
          @Override
          protected JmxProxy connectImpl(Node host) throws ReaperException {
            return jmx;
          }
        };

    RepairRunner rr = mock(RepairRunner.class);
    RepairUnit ru = mock(RepairUnit.class);
    when(ru.getKeyspaceName()).thenReturn("reaper");

    ClusterFacade clusterFacade = mock(ClusterFacade.class);
    when(clusterFacade.connectAndAllowSidecar(any(), any())).thenReturn(jmx);
    when(clusterFacade.nodeIsAccessibleThroughJmx(any(), any())).thenReturn(true);

    when(clusterFacade.tokenRangeToEndpoint(any(), anyString(), any()))
        .thenReturn(Lists.newArrayList(cf.getNodes()));

    SegmentRunner sr = SegmentRunner
        .create(context, clusterFacade, segmentId, COORDS, 5000, 0.5, PARALLEL, "reaper", ru, TABLES, rr);

    sr.run();

    future.getValue().get();
    executor.shutdown();

    assertEquals(
        RepairSegment.State.DONE, storage.getRepairSegment(runId, segmentId).get().getState());
    assertEquals(0, storage.getRepairSegment(runId, segmentId).get().getFailCount());
  }

  @Test
  public void outOfOrderSuccessCass22Test()
      throws InterruptedException, ReaperException, ExecutionException {
    final IStorage storage = new MemoryStorage();

    RepairUnit cf = storage.addRepairUnit(
            RepairUnit.builder()
                .clusterName("reaper")
                .keyspaceName("reaper")
                .columnFamilies(Sets.newHashSet("reaper"))
                .incrementalRepair(false)
                .nodes(Sets.newHashSet("127.0.0.1"))
                .repairThreadCount(1));

    RepairRun run = storage.addRepairRun(
            RepairRun.builder("reaper", cf.getId())
                .intensity(0.5)
                .segmentCount(1)
                .repairParallelism(PARALLEL)
                .tables(TABLES),
            Collections.singleton(
                RepairSegment.builder(
                    Segment.builder()
                        .withTokenRange(new RingRange(BigInteger.ONE, BigInteger.ZERO))
                        .build(),
                    cf.getId())));

    storage.addCluster(new Cluster(cf.getClusterName(), Optional.of("murmur3"), cf.getNodes(),
            ClusterProperties.builder().withJmxPort(7199).build()));

    final UUID runId = run.getId();
    final UUID segmentId = storage.getNextFreeSegmentInRange(run.getId(), Optional.empty()).get().getId();

    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final MutableObject<Future<?>> future = new MutableObject<>();

    AppContext context = new AppContext();
    context.storage = storage;
    context.config = Mockito.mock(ReaperApplicationConfiguration.class);
    when(context.config.getJmxConnectionTimeoutInSeconds()).thenReturn(30);
    when(context.config.getDatacenterAvailability()).thenReturn(DatacenterAvailability.ALL);

    final JmxProxy jmx = JmxProxyTest.mockJmxProxyImpl();
    when(jmx.getClusterName()).thenReturn("reaper");
    when(jmx.isConnectionAlive()).thenReturn(true);

    EndpointSnitchInfoMBean endpointSnitchInfoMBean = mock(EndpointSnitchInfoMBean.class);
    when(endpointSnitchInfoMBean.getDatacenter()).thenReturn("dc1");
    try {
      when(endpointSnitchInfoMBean.getDatacenter(anyString())).thenReturn("dc1");
    } catch (UnknownHostException ex) {
      throw new AssertionError(ex);
    }
    JmxProxyTest.mockGetEndpointSnitchInfoMBean(jmx, endpointSnitchInfoMBean);

    when(jmx.triggerRepair(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .then(
            invocation -> {
              assertEquals(
                  RepairSegment.State.STARTED,
                  storage.getRepairSegment(runId, segmentId).get().getState());

              future.setValue(
                  executor.submit(
                      () -> {
                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.empty(),
                                Optional.of(ProgressEventType.START),
                                "Repair command 1 has started",
                                jmx);

                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.empty(),
                                Optional.of(ProgressEventType.COMPLETE),
                                "Repair command 1 has finished",
                                jmx);

                        assertEquals(
                            RepairSegment.State.RUNNING,
                            storage.getRepairSegment(runId, segmentId).get().getState());

                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.empty(),
                                Optional.of(ProgressEventType.SUCCESS),
                                "Repair session succeeded in command 1",
                                jmx);

                        assertEquals(
                            RepairSegment.State.DONE,
                            storage.getRepairSegment(runId, segmentId).get().getState());
                      }));
              return 1;
            });

    context.jmxConnectionFactory = new JmxConnectionFactory(context) {
          @Override
          protected JmxProxy connectImpl(Node host) throws ReaperException {
            return jmx;
          }
        };

    RepairRunner rr = mock(RepairRunner.class);
    RepairUnit ru = mock(RepairUnit.class);
    when(ru.getKeyspaceName()).thenReturn("reaper");

    ClusterFacade clusterFacade = mock(ClusterFacade.class);
    when(clusterFacade.connectAndAllowSidecar(any(), any())).thenReturn(jmx);
    when(clusterFacade.nodeIsAccessibleThroughJmx(any(), any())).thenReturn(true);

    when(clusterFacade.tokenRangeToEndpoint(any(), anyString(), any()))
        .thenReturn(Lists.newArrayList(cf.getNodes()));

    SegmentRunner sr = SegmentRunner
        .create(context, clusterFacade, segmentId, COORDS, 5000, 0.5, PARALLEL, "reaper", ru, TABLES, rr);

    sr.run();

    future.getValue().get();
    executor.shutdown();

    assertEquals(
        RepairSegment.State.DONE, storage.getRepairSegment(runId, segmentId).get().getState());

    assertEquals(0, storage.getRepairSegment(runId, segmentId).get().getFailCount());
  }

  @Test
  public void outOfOrderFailureCass21Test()
      throws InterruptedException, ReaperException, ExecutionException {
    final IStorage storage = new MemoryStorage();

    RepairUnit cf = storage.addRepairUnit(
            RepairUnit.builder()
                .clusterName("reaper")
                .keyspaceName("reaper")
                .columnFamilies(Sets.newHashSet("reaper"))
                .incrementalRepair(false)
                .nodes(Sets.newHashSet("127.0.0.1"))
                .repairThreadCount(1));

    RepairRun run = storage.addRepairRun(
            RepairRun.builder("reaper", cf.getId())
                .intensity(0.5)
                .segmentCount(1)
                .repairParallelism(PARALLEL)
                .tables(TABLES),
            Collections.singleton(
                RepairSegment.builder(
                    Segment.builder()
                        .withTokenRange(new RingRange(BigInteger.ONE, BigInteger.ZERO))
                        .build(),
                    cf.getId())));

    storage.addCluster(new Cluster(cf.getClusterName(), Optional.of("murmur3"), cf.getNodes(),
            ClusterProperties.builder().withJmxPort(7199).build()));

    final UUID runId = run.getId();
    final UUID segmentId = storage.getNextFreeSegmentInRange(run.getId(), Optional.empty()).get().getId();

    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final MutableObject<Future<?>> future = new MutableObject<>();

    AppContext context = new AppContext();
    context.storage = storage;
    context.config = Mockito.mock(ReaperApplicationConfiguration.class);
    when(context.config.getJmxConnectionTimeoutInSeconds()).thenReturn(30);
    when(context.config.getDatacenterAvailability()).thenReturn(DatacenterAvailability.ALL);

    final JmxProxy jmx = JmxProxyTest.mockJmxProxyImpl();
    when(jmx.getClusterName()).thenReturn("reaper");
    when(jmx.isConnectionAlive()).thenReturn(true);

    EndpointSnitchInfoMBean endpointSnitchInfoMBean = mock(EndpointSnitchInfoMBean.class);
    when(endpointSnitchInfoMBean.getDatacenter()).thenReturn("dc1");
    try {
      when(endpointSnitchInfoMBean.getDatacenter(anyString())).thenReturn("dc1");
    } catch (UnknownHostException ex) {
      throw new AssertionError(ex);
    }
    JmxProxyTest.mockGetEndpointSnitchInfoMBean(jmx, endpointSnitchInfoMBean);

    when(jmx.triggerRepair(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .then(
            invocation -> {
              assertEquals(
                  RepairSegment.State.STARTED,
                  storage.getRepairSegment(runId, segmentId).get().getState());

              future.setValue(
                  executor.submit(
                      () -> {
                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.STARTED),
                                Optional.empty(),
                                "Repair command 1 has started",
                                jmx);

                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.FINISHED),
                                Optional.empty(),
                                "Repair command 1 has finished",
                                jmx);

                        assertEquals(
                            RepairSegment.State.RUNNING,
                            storage.getRepairSegment(runId, segmentId).get().getState());

                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.of(ActiveRepairService.Status.SESSION_FAILED),
                                Optional.empty(),
                                "Repair session succeeded in command 1",
                                jmx);

                        assertEquals(
                            RepairSegment.State.NOT_STARTED,
                            storage.getRepairSegment(runId, segmentId).get().getState());
                      }));
              return 1;
            });

    context.jmxConnectionFactory = new JmxConnectionFactory(context) {
          @Override
          protected JmxProxy connectImpl(Node host) throws ReaperException {
            return jmx;
          }
        };

    RepairRunner rr = mock(RepairRunner.class);
    RepairUnit ru = mock(RepairUnit.class);
    when(ru.getKeyspaceName()).thenReturn("reaper");

    ClusterFacade clusterFacade = mock(ClusterFacade.class);
    when(clusterFacade.connectAndAllowSidecar(any(), any())).thenReturn(jmx);
    when(clusterFacade.nodeIsAccessibleThroughJmx(any(), any())).thenReturn(true);

    when(clusterFacade.tokenRangeToEndpoint(any(), anyString(), any()))
        .thenReturn(Lists.newArrayList(cf.getNodes()));

    SegmentRunner sr = SegmentRunner
        .create(context, clusterFacade, segmentId, COORDS, 5000, 0.5, PARALLEL, "reaper", ru, TABLES, rr);

    sr.run();

    future.getValue().get();
    executor.shutdown();

    assertEquals(
        RepairSegment.State.NOT_STARTED,
        storage.getRepairSegment(runId, segmentId).get().getState());

    assertEquals(2, storage.getRepairSegment(runId, segmentId).get().getFailCount());
  }

  @Test
  public void outOfOrderFailureTestCass22()
      throws InterruptedException, ReaperException, ExecutionException {
    final IStorage storage = new MemoryStorage();

    RepairUnit cf = storage.addRepairUnit(
            RepairUnit.builder()
                .clusterName("reaper")
                .keyspaceName("reaper")
                .columnFamilies(Sets.newHashSet("reaper"))
                .incrementalRepair(false)
                .nodes(Sets.newHashSet("127.0.0.1"))
                .repairThreadCount(1));

    RepairRun run = storage.addRepairRun(
            RepairRun.builder("reaper", cf.getId())
                .intensity(0.5)
                .segmentCount(1)
                .repairParallelism(PARALLEL)
                .tables(TABLES),
            Collections.singleton(
                RepairSegment.builder(
                    Segment.builder()
                        .withTokenRange(new RingRange(BigInteger.ONE, BigInteger.ZERO))
                        .build(),
                    cf.getId())));

    storage.addCluster(new Cluster(cf.getClusterName(), Optional.of("murmur3"), cf.getNodes(),
            ClusterProperties.builder().withJmxPort(7199).build()));

    final UUID runId = run.getId();
    final UUID segmentId = storage.getNextFreeSegmentInRange(run.getId(), Optional.empty()).get().getId();

    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final MutableObject<Future<?>> future = new MutableObject<>();

    AppContext context = new AppContext();
    context.storage = storage;
    context.config = Mockito.mock(ReaperApplicationConfiguration.class);
    when(context.config.getJmxConnectionTimeoutInSeconds()).thenReturn(30);
    when(context.config.getDatacenterAvailability()).thenReturn(DatacenterAvailability.ALL);

    final JmxProxy jmx = JmxProxyTest.mockJmxProxyImpl();
    when(jmx.getClusterName()).thenReturn("reaper");
    when(jmx.isConnectionAlive()).thenReturn(true);

    EndpointSnitchInfoMBean endpointSnitchInfoMBean = mock(EndpointSnitchInfoMBean.class);
    when(endpointSnitchInfoMBean.getDatacenter()).thenReturn("dc1");
    try {
      when(endpointSnitchInfoMBean.getDatacenter(anyString())).thenReturn("dc1");
    } catch (UnknownHostException ex) {
      throw new AssertionError(ex);
    }
    JmxProxyTest.mockGetEndpointSnitchInfoMBean(jmx, endpointSnitchInfoMBean);

    when(jmx.triggerRepair(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyInt()))
        .then(
            invocation -> {
              assertEquals(
                  RepairSegment.State.STARTED,
                  storage.getRepairSegment(runId, segmentId).get().getState());

              future.setValue(
                  executor.submit(
                      () -> {
                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.empty(),
                                Optional.of(ProgressEventType.START),
                                "Repair command 1 has started",
                                jmx);

                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.empty(),
                                Optional.of(ProgressEventType.COMPLETE),
                                "Repair command 1 has finished",
                                jmx);

                        assertEquals(
                            RepairSegment.State.RUNNING,
                            storage.getRepairSegment(runId, segmentId).get().getState());

                        ((RepairStatusHandler) invocation.getArgument(7))
                            .handle(
                                1,
                                Optional.empty(),
                                Optional.of(ProgressEventType.ERROR),
                                "Repair session succeeded in command 1",
                                jmx);

                        assertEquals(
                            RepairSegment.State.NOT_STARTED,
                            storage.getRepairSegment(runId, segmentId).get().getState());
                      }));
              return 1;
            });

    context.jmxConnectionFactory = new JmxConnectionFactory(context) {
          @Override
          protected JmxProxy connectImpl(Node host) throws ReaperException {
            return jmx;
          }
        };

    RepairRunner rr = mock(RepairRunner.class);
    RepairUnit ru = mock(RepairUnit.class);
    when(ru.getKeyspaceName()).thenReturn("reaper");

    ClusterFacade clusterFacade = mock(ClusterFacade.class);
    when(clusterFacade.connectAndAllowSidecar(any(), any())).thenReturn(jmx);
    when(clusterFacade.nodeIsAccessibleThroughJmx(any(), any())).thenReturn(true);

    when(clusterFacade.tokenRangeToEndpoint(any(), anyString(), any()))
        .thenReturn(Lists.newArrayList(cf.getNodes()));

    SegmentRunner sr = SegmentRunner
        .create(context, clusterFacade, segmentId, COORDS, 5000, 0.5, PARALLEL, "reaper", ru, TABLES, rr);

    sr.run();

    future.getValue().get();
    executor.shutdown();

    assertEquals(
        RepairSegment.State.NOT_STARTED,
        storage.getRepairSegment(runId, segmentId).get().getState());

    assertEquals(2, storage.getRepairSegment(runId, segmentId).get().getFailCount());
  }

  @Test
  public void parseRepairIdTest() {
    String msg = "Repair session 883fd090-12f1-11e5-94c5-03d4762e50b7 for range (1,2] failed with";
    assertEquals("883fd090-12f1-11e5-94c5-03d4762e50b7", SegmentRunner.parseRepairId(msg));
    msg = "Two IDs: 883fd090-12f1-11e5-94c5-03d4762e50b7 883fd090-12f1-11e5-94c5-03d4762e50b7";
    assertEquals("883fd090-12f1-11e5-94c5-03d4762e50b7", SegmentRunner.parseRepairId(msg));
    msg = "No ID: foo bar baz";
    assertEquals(null, SegmentRunner.parseRepairId(msg));
    msg = "A message with bad ID 883fd090-fooo-11e5-94c5-03d4762e50b7";
    assertEquals(null, SegmentRunner.parseRepairId(msg));
    msg = "A message with good ID 883fd090-baad-11e5-94c5-03d4762e50b7";
    assertEquals("883fd090-baad-11e5-94c5-03d4762e50b7", SegmentRunner.parseRepairId(msg));
  }

  @Test
  public void isItOkToRepairTest() {
    assertFalse(SegmentRunner.okToRepairSegment(false, true, DatacenterAvailability.ALL));
    assertFalse(SegmentRunner.okToRepairSegment(false, false, DatacenterAvailability.ALL));
    assertTrue(SegmentRunner.okToRepairSegment(true, true, DatacenterAvailability.ALL));

    assertTrue(SegmentRunner.okToRepairSegment(false, true, DatacenterAvailability.LOCAL));
    assertFalse(SegmentRunner.okToRepairSegment(false, false, DatacenterAvailability.LOCAL));
    assertTrue(SegmentRunner.okToRepairSegment(true, true, DatacenterAvailability.LOCAL));

    assertFalse(SegmentRunner.okToRepairSegment(false, true, DatacenterAvailability.EACH));
    assertFalse(SegmentRunner.okToRepairSegment(false, false, DatacenterAvailability.EACH));
    assertTrue(SegmentRunner.okToRepairSegment(true, true, DatacenterAvailability.EACH));
  }

  @Test
  public void getNodeMetricsInLocalDCAvailabilityForRemoteDCNodeTest() throws Exception {
    final AppContext context = new AppContext();
    context.storage = Mockito.mock(CassandraStorage.class);
    when(((IDistributedStorage) context.storage).getNodeMetrics(any(), any()))
        .thenReturn(Optional.empty());
    JmxConnectionFactory jmxConnectionFactory = mock(JmxConnectionFactory.class);
    JmxProxy jmx = mock(JmxProxy.class);
    when(jmxConnectionFactory.connect(any())).thenReturn(jmx);
    context.jmxConnectionFactory = jmxConnectionFactory;
    context.config = new ReaperApplicationConfiguration();
    context.config.setDatacenterAvailability(DatacenterAvailability.LOCAL);

    ClusterFacade clusterFacade = mock(ClusterFacade.class);
    when(clusterFacade.connectAndAllowSidecar(any(), any())).thenReturn(jmx);

    SegmentRunner segmentRunner = SegmentRunner.create(
            context,
            clusterFacade,
            UUID.randomUUID(),
            Collections.emptyList(),
            1000,
            1.1,
            DATACENTER_AWARE,
            "test",
            mock(RepairUnit.class),
            TABLES,
            mock(RepairRunner.class));

    Pair<String, Callable<Optional<NodeMetrics>>> result = segmentRunner.getNodeMetrics("node-some", "dc1", "dc2");
    assertFalse(result.getRight().call().isPresent());
    verify(jmxConnectionFactory, times(0)).connect(any());
  }

  @Test
  public void getNodeMetricsInLocalDCAvailabilityForLocalDCNodeTest() throws Exception {
    final AppContext context = new AppContext();
    context.storage = Mockito.mock(CassandraStorage.class);

    Mockito.when(((CassandraStorage) context.storage).getCluster(any()))
        .thenReturn(
            Optional.of(new Cluster("test", Optional.of("murmur3"), new HashSet<String>(Arrays.asList("test")),
                ClusterProperties.builder().withJmxPort(7199).build())));

    JmxProxy proxy = JmxProxyTest.mockJmxProxyImpl();
    when(proxy.getClusterName()).thenReturn("test");
    when(proxy.getPendingCompactions()).thenReturn(3);
    when(proxy.isRepairRunning()).thenReturn(true);

    EndpointSnitchInfoMBean endpointSnitchInfoMBeanMock = mock(EndpointSnitchInfoMBean.class);
    when(endpointSnitchInfoMBeanMock.getDatacenter(any())).thenReturn("dc1");
    JmxProxyTest.mockGetEndpointSnitchInfoMBean(proxy, endpointSnitchInfoMBeanMock);

    JmxConnectionFactory jmxConnectionFactory = mock(JmxConnectionFactory.class);
    when(jmxConnectionFactory.connect(any())).thenReturn(proxy);
    when(jmxConnectionFactory.connectAny(any(Collection.class))).thenReturn(proxy);
    when(jmxConnectionFactory.getAccessibleDatacenters()).thenReturn(Sets.newHashSet("dc1"));
    context.jmxConnectionFactory = jmxConnectionFactory;
    context.config = new ReaperApplicationConfiguration();
    context.config.setDatacenterAvailability(DatacenterAvailability.LOCAL);

    ClusterFacade clusterFacade = mock(ClusterFacade.class);
    when(clusterFacade.connectAndAllowSidecar(any(), any())).thenReturn(proxy);
    when(clusterFacade.nodeIsAccessibleThroughJmx(any(), any())).thenReturn(true);

    SegmentRunner segmentRunner = SegmentRunner.create(
            context,
            clusterFacade,
            UUID.randomUUID(),
            Collections.emptyList(),
            1000,
            1.1,
            DATACENTER_AWARE,
            "test",
            mock(RepairUnit.class),
            TABLES,
            mock(RepairRunner.class));

    Pair<String, Callable<Optional<NodeMetrics>>> result = segmentRunner.getNodeMetrics("node-some", "dc1", "dc1");
    Optional<NodeMetrics> optional = result.getRight().call();
    assertTrue(optional.isPresent());
    NodeMetrics metrics = optional.get();
    assertEquals("test", metrics.getCluster());
    assertEquals(3, metrics.getPendingCompactions());
    assertTrue(metrics.hasRepairRunning());
  }
}
