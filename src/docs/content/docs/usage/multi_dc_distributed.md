+++
[menu.docs]
name = "Operating with Multi DC Cluster using Multiple Reaper instances"
weight = 50
identifier = "multi_dc"
parent = "usage"
+++


# Operating with a Multi DC Cluster using Multiple Reaper instances

Multiple Reaper instances can operate clusters which has multi datacenter deployment. Multiple Reaper instances, also known as Distributed mode, can only be used when using the Apache Cassandra backend. Using multiple Reaper instances allows improved availability and fault tolerance. It is more likely that a Reaper UI is available via one of the Reaper instances, and that scheduled repairs are executed by one of the running Reaper instances.

The `datacenterAvailability` setting in the Reaper YAML file indicates to Reaper its deployment in relation to cluster data center network locality.

## Multiple Reaper instances with JMX accessible for all DCs

In the case where the JMX port is accessible (with or without authentication) from the running Reaper instance for all nodes in all DCs, it is possible to have multiple instances of Reaper handle one or multiple clusters by using the following setting in the configuration yaml file :  

```
datacenterAvailability: ALL
```


{{< screenshot src="/img/singlereaper-multidc-all.png">}}

{{< /screenshot >}}

Reaper must be able to access the JMX port (7199 by default) and port 9042 if the cluster is also used as Cassandra backend, on the local DC.

The keyspaces must be replicated using NetworkTopologyStrategy (NTS) and have replicas at least on the DC Reaper can access through JMX. Repairing the remote DC will be handled internally by Cassandra.

## Multiple Reaper instances with JMX accessible for limited DCs

In the case where the JMX port is accessible (with or without authentication) from the running Reaper instance for all nodes in only some of the DCs, it is possible to have multiple instances of Reaper handle one or multiple clusters by using the following setting in the configuration yaml file :  

```
datacenterAvailability: LOCAL
```

Be aware that this setup will not allow to handle backpressure for those remote DCs as JMX metrics (pending compactions, running repairs) from those remote nodes are not made available to Reaper.

If multiple clusters are registered in Reaper it is required that the Reaper instances can access all nodes in at least one datacenter in each of the registered clusters.

If all Reaper instances that have JMX access to a specific datacenter stop then backpressure for the nodes in that datacenter is not handled. 

`LOCAL` mode allows you to register multiple clusters in a distributed Reaper installation. `LOCAL` mode also allows you to prioritise repairs running according to their schedules over worrying about the load on remote and unaccessible datacenters and nodes. 


{{< screenshot src="/img/singlereaper-multidc-local.png">}}

{{< /screenshot >}}

Reaper must be able to access the JMX port (7199 by default) and port 9042 if the cluster is also used as Cassandra backend, on the local DC.

The keyspaces must be replicated using NetworkTopologyStrategy (NTS) and have replicas at least on the DC Reaper can access through JMX. Repairing the remote DC will be handled internally by Cassandra.
  
  
## Multiple Reaper instances with JMX accessible locally to each DC

In the case where the JMX port is accessible (with or without authentication) from the running Reaper instance for all nodes in the current DC only, it is possible to have a multiple instances of Reaper running in different DCs by using the following setting in the configuration yaml file :  

```
datacenterAvailability: EACH
```

This setup prioritises handling backpressure on all nodes over running repairs. Where latency of requests and availability of nodes takes precedence over scheduled repairs this is the safest setup in Reaper. 

There must be installed and running a Reaper instance in every datacenter of every registered Cassandra cluster. And every Reaper instance must have CQL access to the backend Cassandra cluster it uses as a backend.

{{< screenshot src="/img/multireaper-multidc.png">}}

{{< /screenshot >}}

Reaper must be able to access the JMX port (7199 by default) and port 9042 if the cluster is also used as Cassandra backend, on the local DC.

