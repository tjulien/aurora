"""Get a handle to the scheduler, working around prod/corp networking issues.
"""

__author__ = 'Alex Roetter'

import os, sys
from twitter.common import log

# Load this first.  Because of general Python interpreter pyc path caching stupidity, you can't mix
# thrift versions, so whenever you import autogenerated thrift code and the scheduler_client module,
# the scheduler_client module must come first.
try:
  from thrift.protocol import TBinaryProtocol
  from thrift.transport import TSocket
  from thrift.transport import TTransport
  from thrift.transport import TSSLSocket
except ImportError, e:
  log.fatal("Local thrift imports failed: %s" % e)
  sys.exit(1)

import zookeeper

from endpoint.ttypes import *

from twitter.mesos import clusters
from gen.twitter.mesos import MesosAdmin
from gen.twitter.mesos.ttypes import *
from twitter.mesos.location import Location
from twitter.mesos.tunnel_helper import TunnelHelper
from twitter.mesos.zookeeper_helper import ZookeeperHelper

class SchedulerClient(object):
  def __init__(self, verbose=False, ssl=False):
    self._client = None
    self._verbose = verbose
    self._ssl = ssl

  def get_thrift_client(self):
    if self._client is None:
      self._client = self._connect()
    return self._client

  # per-class implementation -- mostly meant to set up a valid host/port
  # pair and then delegate the opening to SchedulerClient._connect_scheduler
  def _connect(self):
    return None

  @staticmethod
  def _connect_scheduler(host, port, with_ssl=False):
    if with_ssl:
      socket = TSSLSocket.TSSLSocket(host, port, validate=False)
    else:
      socket = TSocket.TSocket(host, port)

    transport = TTransport.TBufferedTransport(socket)
    protocol = TBinaryProtocol.TBinaryProtocol(transport)
    schedulerClient = MesosAdmin.Client(protocol)
    transport.open()
    return schedulerClient

class ZookeeperSchedulerClient(SchedulerClient):
  LOCAL_SCHEDULER_TUNNEL_PORT = 8888
  SCHEDULER_ZK_PATH = '/twitter/service/mesos-scheduler'

  def __init__(self, cluster, port=2181, force_notunnel=False, ssl=False, verbose=False):
    SchedulerClient.__init__(self, verbose=verbose, ssl=ssl)
    self._cluster = cluster
    self._zkport = port
    self._notunnel = force_notunnel

  def _connect(self):
    self._zh = ZookeeperHelper.get_zookeeper_handle(self._cluster, self._zkport)
    (host, port) = ZookeeperSchedulerClient._get_scheduler_host_port(
      self._zh, self._cluster, self._notunnel, verbose=self._verbose)
    zookeeper.close(self._zh)
    return SchedulerClient._connect_scheduler(host, port, self._ssl)

  @staticmethod
  def _parse_endpoint(data):
    transportIn = TTransport.TMemoryBuffer(data)
    protocolIn = TBinaryProtocol.TBinaryProtocol(transportIn)
    si = ServiceInstance()
    si.read(protocolIn)
    return si.serviceEndpoint.host, si.serviceEndpoint.port

  @staticmethod
  def _open_scheduler_tunnel(cluster, remote_host, remote_port):
    host, port = TunnelHelper.create_tunnel(
      TunnelHelper.get_tunnel_host(cluster),
      ZookeeperSchedulerClient.LOCAL_SCHEDULER_TUNNEL_PORT,
      remote_host,
      remote_port)
    return host, port

  @staticmethod
  def _get_scheduler_host_port(zh, cluster, no_tunnel, verbose=False):
    """ Use Zookeeper to determine the host and port of the scheduler.
    Returns a host/port reachable from either corp or prod, depending on
    the argument location. Sets up ssh tunnels as appropriate."""
    if verbose:
      zookeeper.set_debug_level(zookeeper.LOG_LEVEL_DEBUG)
    else:
      zookeeper.set_debug_level(zookeeper.LOG_LEVEL_WARN)

    scheduler_zk_path = clusters.get_scheduler_zk_path(cluster)
    children = ZookeeperHelper.get_zookeeper_children_or_die(zh, scheduler_zk_path)

    if not children:
      log.fatal('Failed to discover scheduler in ZooKeeper!')
      sys.exit(1)

    master = sorted(children)[0]
    (data, stat) = zookeeper.get(zh, os.path.join(scheduler_zk_path, master))

    host, port = ZookeeperSchedulerClient._parse_endpoint(data)

    # Open a tunnel to the scheduler if necessary
    if Location.is_corp() and not no_tunnel:
      host, port = ZookeeperSchedulerClient._open_scheduler_tunnel(cluster, host, port)

    zookeeper.set_debug_level(zookeeper.LOG_LEVEL_WARN)
    return host, port

class LocalSchedulerClient(SchedulerClient):
  def __init__(self, port, ssl=False):
    SchedulerClient.__init__(self, verbose=True, ssl=ssl)
    self._host = 'localhost'
    self._port = port

  def _connect(self):
    return SchedulerClient._connect_scheduler(self._host, self._port, with_ssl=self._ssl)
