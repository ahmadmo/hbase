/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.Service;
import com.google.protobuf.ServiceException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.classification.InterfaceStability;
import org.apache.hadoop.hbase.client.AsyncProcess.AsyncRequestFuture;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.client.coprocessor.Batch.Callback;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.ipc.CoprocessorRpcChannel;
import org.apache.hadoop.hbase.ipc.PayloadCarryingRpcController;
import org.apache.hadoop.hbase.ipc.RegionCoprocessorRpcChannel;
import org.apache.hadoop.hbase.ipc.RpcControllerFactory;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.RequestConverter;
import org.apache.hadoop.hbase.protobuf.ResponseConverter;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.MultiRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.MutateRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.MutateResponse;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.RegionAction;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos.CompareType;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.ReflectionUtils;
import org.apache.hadoop.hbase.util.Threads;

/**
 * An implementation of {@link Table}. Used to communicate with a single HBase table.
 * Lightweight. Get as needed and just close when done.
 * Instances of this class SHOULD NOT be constructed directly.
 * Obtain an instance via {@link Connection}. See {@link ConnectionFactory}
 * class comment for an example of how.
 *
 * <p>This class is NOT thread safe for reads nor writes.
 * In the case of writes (Put, Delete), the underlying write buffer can
 * be corrupted if multiple threads contend over a single HTable instance.
 * In the case of reads, some fields used by a Scan are shared among all threads.
 *
 * <p>HTable is no longer a client API. Use {@link Table} instead. It is marked
 * InterfaceAudience.Private indicating that this is an HBase-internal class as defined in
 * <a href="https://hadoop.apache.org/docs/current/hadoop-project-dist/hadoop-common/InterfaceClassification.html">Hadoop
 * Interface Classification</a>
 * There are no guarantees for backwards source / binary compatibility and methods or class can
 * change or go away without deprecation.
 *
 * @see Table
 * @see Admin
 * @see Connection
 * @see ConnectionFactory
 */
@InterfaceAudience.Private
@InterfaceStability.Stable
public class HTable implements Table {
  private static final Log LOG = LogFactory.getLog(HTable.class);
  protected ClusterConnection connection;
  private final TableName tableName;
  private volatile Configuration configuration;
  private ConnectionConfiguration connConfiguration;
  protected BufferedMutatorImpl mutator;
  private boolean closed = false;
  protected int scannerCaching;
  protected long scannerMaxResultSize;
  private ExecutorService pool;  // For Multi & Scan
  private int operationTimeout; // global timeout for each blocking method with retrying rpc
  private int rpcTimeout; // timeout for each rpc request
  private final boolean cleanupPoolOnClose; // shutdown the pool in close()
  private final boolean cleanupConnectionOnClose; // close the connection in close()
  private Consistency defaultConsistency = Consistency.STRONG;
  private HRegionLocator locator;

  /** The Async process for batch */
  protected AsyncProcess multiAp;
  private RpcRetryingCallerFactory rpcCallerFactory;
  private RpcControllerFactory rpcControllerFactory;

  // Marked Private @since 1.0
  @InterfaceAudience.Private
  public static ThreadPoolExecutor getDefaultExecutor(Configuration conf) {
    int maxThreads = conf.getInt("hbase.htable.threads.max", Integer.MAX_VALUE);
    if (maxThreads == 0) {
      maxThreads = 1; // is there a better default?
    }
    int corePoolSize = conf.getInt("hbase.htable.threads.coresize", 1);
    long keepAliveTime = conf.getLong("hbase.htable.threads.keepalivetime", 60);

    // Using the "direct handoff" approach, new threads will only be created
    // if it is necessary and will grow unbounded. This could be bad but in HCM
    // we only create as many Runnables as there are region servers. It means
    // it also scales when new region servers are added.
    ThreadPoolExecutor pool = new ThreadPoolExecutor(corePoolSize, maxThreads, keepAliveTime,
      TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), Threads.newDaemonThreadFactory("htable"));
    pool.allowCoreThreadTimeOut(true);
    return pool;
  }

  /**
   * Creates an object to access a HBase table.
   * Used by HBase internally.  DO NOT USE. See {@link ConnectionFactory} class comment for how to
   * get a {@link Table} instance (use {@link Table} instead of {@link HTable}).
   * @param tableName Name of the table.
   * @param connection Connection to be used.
   * @param pool ExecutorService to be used.
   * @throws IOException if a remote or network exception occurs
   */
  @InterfaceAudience.Private
  protected HTable(TableName tableName, final ClusterConnection connection,
      final ConnectionConfiguration tableConfig,
      final RpcRetryingCallerFactory rpcCallerFactory,
      final RpcControllerFactory rpcControllerFactory,
      final ExecutorService pool) throws IOException {
    if (connection == null || connection.isClosed()) {
      throw new IllegalArgumentException("Connection is null or closed.");
    }
    this.tableName = tableName;
    this.cleanupConnectionOnClose = false;
    this.connection = connection;
    this.configuration = connection.getConfiguration();
    this.connConfiguration = tableConfig;
    this.pool = pool;
    if (pool == null) {
      this.pool = getDefaultExecutor(this.configuration);
      this.cleanupPoolOnClose = true;
    } else {
      this.cleanupPoolOnClose = false;
    }

    this.rpcCallerFactory = rpcCallerFactory;
    this.rpcControllerFactory = rpcControllerFactory;

    this.finishSetup();
  }

  /**
   * For internal testing. Uses Connection provided in {@code params}.
   * @throws IOException
   */
  @VisibleForTesting
  protected HTable(ClusterConnection conn, BufferedMutatorParams params) throws IOException {
    connection = conn;
    tableName = params.getTableName();
    connConfiguration = new ConnectionConfiguration(connection.getConfiguration());
    cleanupPoolOnClose = false;
    cleanupConnectionOnClose = false;
    // used from tests, don't trust the connection is real
    this.mutator = new BufferedMutatorImpl(conn, null, null, params);
  }

  /**
   * @return maxKeyValueSize from configuration.
   */
  public static int getMaxKeyValueSize(Configuration conf) {
    return conf.getInt("hbase.client.keyvalue.maxsize", -1);
  }

  /**
   * setup this HTable's parameter based on the passed configuration
   */
  private void finishSetup() throws IOException {
    if (connConfiguration == null) {
      connConfiguration = new ConnectionConfiguration(configuration);
    }

    this.operationTimeout = tableName.isSystemTable() ?
        connConfiguration.getMetaOperationTimeout() : connConfiguration.getOperationTimeout();
    this.rpcTimeout = configuration.getInt(HConstants.HBASE_RPC_TIMEOUT_KEY,
        HConstants.DEFAULT_HBASE_RPC_TIMEOUT);
    this.scannerCaching = connConfiguration.getScannerCaching();
    this.scannerMaxResultSize = connConfiguration.getScannerMaxResultSize();
    if (this.rpcCallerFactory == null) {
      this.rpcCallerFactory = connection.getNewRpcRetryingCallerFactory(configuration);
    }
    if (this.rpcControllerFactory == null) {
      this.rpcControllerFactory = RpcControllerFactory.instantiate(configuration);
    }

    // puts need to track errors globally due to how the APIs currently work.
    multiAp = this.connection.getAsyncProcess();
    this.locator = new HRegionLocator(getName(), connection);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Configuration getConfiguration() {
    return configuration;
  }

  @Override
  public TableName getName() {
    return tableName;
  }

  /**
   * <em>INTERNAL</em> Used by unit tests and tools to do low-level
   * manipulations.
   * @return A Connection instance.
   */
  @VisibleForTesting
  protected Connection getConnection() {
    return this.connection;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTableDescriptor getTableDescriptor() throws IOException {
    HTableDescriptor htd = HBaseAdmin.getTableDescriptor(tableName, connection, rpcCallerFactory,
      rpcControllerFactory, operationTimeout, rpcTimeout);
    if (htd != null) {
      return new UnmodifyableHTableDescriptor(htd);
    }
    return null;
  }

  /**
   * Get the corresponding start keys and regions for an arbitrary range of
   * keys.
   * <p>
   * @param startKey Starting row in range, inclusive
   * @param endKey Ending row in range
   * @param includeEndKey true if endRow is inclusive, false if exclusive
   * @return A pair of list of start keys and list of HRegionLocations that
   *         contain the specified range
   * @throws IOException if a remote or network exception occurs
   */
  private Pair<List<byte[]>, List<HRegionLocation>> getKeysAndRegionsInRange(
      final byte[] startKey, final byte[] endKey, final boolean includeEndKey)
      throws IOException {
    return getKeysAndRegionsInRange(startKey, endKey, includeEndKey, false);
  }

  /**
   * Get the corresponding start keys and regions for an arbitrary range of
   * keys.
   * <p>
   * @param startKey Starting row in range, inclusive
   * @param endKey Ending row in range
   * @param includeEndKey true if endRow is inclusive, false if exclusive
   * @param reload true to reload information or false to use cached information
   * @return A pair of list of start keys and list of HRegionLocations that
   *         contain the specified range
   * @throws IOException if a remote or network exception occurs
   */
  private Pair<List<byte[]>, List<HRegionLocation>> getKeysAndRegionsInRange(
      final byte[] startKey, final byte[] endKey, final boolean includeEndKey,
      final boolean reload) throws IOException {
    final boolean endKeyIsEndOfTable = Bytes.equals(endKey,HConstants.EMPTY_END_ROW);
    if ((Bytes.compareTo(startKey, endKey) > 0) && !endKeyIsEndOfTable) {
      throw new IllegalArgumentException(
        "Invalid range: " + Bytes.toStringBinary(startKey) +
        " > " + Bytes.toStringBinary(endKey));
    }
    List<byte[]> keysInRange = new ArrayList<byte[]>();
    List<HRegionLocation> regionsInRange = new ArrayList<HRegionLocation>();
    byte[] currentKey = startKey;
    do {
      HRegionLocation regionLocation = getRegionLocator().getRegionLocation(currentKey, reload);
      keysInRange.add(currentKey);
      regionsInRange.add(regionLocation);
      currentKey = regionLocation.getRegionInfo().getEndKey();
    } while (!Bytes.equals(currentKey, HConstants.EMPTY_END_ROW)
        && (endKeyIsEndOfTable || Bytes.compareTo(currentKey, endKey) < 0
            || (includeEndKey && Bytes.compareTo(currentKey, endKey) == 0)));
    return new Pair<List<byte[]>, List<HRegionLocation>>(keysInRange,
        regionsInRange);
  }

  /**
   * The underlying {@link HTable} must not be closed.
   * {@link Table#getScanner(Scan)} has other usage details.
   */
  @Override
  public ResultScanner getScanner(final Scan scan) throws IOException {
    if (scan.getBatch() > 0 && scan.isSmall()) {
      throw new IllegalArgumentException("Small scan should not be used with batching");
    }

    if (scan.getCaching() <= 0) {
      scan.setCaching(scannerCaching);
    }
    if (scan.getMaxResultSize() <= 0) {
      scan.setMaxResultSize(scannerMaxResultSize);
    }

    Boolean async = scan.isAsyncPrefetch();
    if (async == null) {
      async = connConfiguration.isClientScannerAsyncPrefetch();
    }

    if (scan.isReversed()) {
      if (scan.isSmall()) {
        return new ClientSmallReversedScanner(getConfiguration(), scan, getName(),
            this.connection, this.rpcCallerFactory, this.rpcControllerFactory,
            pool, connConfiguration.getReplicaCallTimeoutMicroSecondScan());
      } else {
        return new ReversedClientScanner(getConfiguration(), scan, getName(),
            this.connection, this.rpcCallerFactory, this.rpcControllerFactory,
            pool, connConfiguration.getReplicaCallTimeoutMicroSecondScan());
      }
    }

    if (scan.isSmall()) {
      return new ClientSmallScanner(getConfiguration(), scan, getName(),
          this.connection, this.rpcCallerFactory, this.rpcControllerFactory,
          pool, connConfiguration.getReplicaCallTimeoutMicroSecondScan());
    } else {
      if (async) {
        return new ClientAsyncPrefetchScanner(getConfiguration(), scan, getName(), this.connection,
            this.rpcCallerFactory, this.rpcControllerFactory,
            pool, connConfiguration.getReplicaCallTimeoutMicroSecondScan());
      } else {
        return new ClientSimpleScanner(getConfiguration(), scan, getName(), this.connection,
            this.rpcCallerFactory, this.rpcControllerFactory,
            pool, connConfiguration.getReplicaCallTimeoutMicroSecondScan());
      }
    }
  }

  /**
   * The underlying {@link HTable} must not be closed.
   * {@link Table#getScanner(byte[])} has other usage details.
   */
  @Override
  public ResultScanner getScanner(byte [] family) throws IOException {
    Scan scan = new Scan();
    scan.addFamily(family);
    return getScanner(scan);
  }

  /**
   * The underlying {@link HTable} must not be closed.
   * {@link Table#getScanner(byte[], byte[])} has other usage details.
   */
  @Override
  public ResultScanner getScanner(byte [] family, byte [] qualifier)
  throws IOException {
    Scan scan = new Scan();
    scan.addColumn(family, qualifier);
    return getScanner(scan);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Result get(final Get get) throws IOException {
    return get(get, get.isCheckExistenceOnly());
  }

  private Result get(Get get, final boolean checkExistenceOnly) throws IOException {
    // if we are changing settings to the get, clone it.
    if (get.isCheckExistenceOnly() != checkExistenceOnly || get.getConsistency() == null) {
      get = ReflectionUtils.newInstance(get.getClass(), get);
      get.setCheckExistenceOnly(checkExistenceOnly);
      if (get.getConsistency() == null){
        get.setConsistency(defaultConsistency);
      }
    }

    if (get.getConsistency() == Consistency.STRONG) {
      // Good old call.
      final Get getReq = get;
      RegionServerCallable<Result> callable = new RegionServerCallable<Result>(this.connection,
          getName(), get.getRow()) {
        @Override
        public Result call(int callTimeout) throws IOException {
          ClientProtos.GetRequest request =
            RequestConverter.buildGetRequest(getLocation().getRegionInfo().getRegionName(), getReq);
          PayloadCarryingRpcController controller = rpcControllerFactory.newController();
          controller.setPriority(tableName);
          controller.setCallTimeout(callTimeout);
          try {
            ClientProtos.GetResponse response = getStub().get(controller, request);
            if (response == null) return null;
            return ProtobufUtil.toResult(response.getResult(), controller.cellScanner());
          } catch (ServiceException se) {
            throw ProtobufUtil.getRemoteException(se);
          }
        }
      };
      return rpcCallerFactory.<Result>newCaller(rpcTimeout).callWithRetries(callable,
          this.operationTimeout);
    }

    // Call that takes into account the replica
    RpcRetryingCallerWithReadReplicas callable = new RpcRetryingCallerWithReadReplicas(
        rpcControllerFactory, tableName, this.connection, get, pool,
        connConfiguration.getRetriesNumber(),
        operationTimeout,
        connConfiguration.getPrimaryCallTimeoutMicroSecond());
    return callable.call(operationTimeout);
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public Result[] get(List<Get> gets) throws IOException {
    if (gets.size() == 1) {
      return new Result[]{get(gets.get(0))};
    }
    try {
      Object[] r1 = new Object[gets.size()];
      batch((List) gets, r1);

      // translate.
      Result [] results = new Result[r1.length];
      int i=0;
      for (Object o : r1) {
        // batch ensures if there is a failure we get an exception instead
        results[i++] = (Result) o;
      }

      return results;
    } catch (InterruptedException e) {
      throw (InterruptedIOException)new InterruptedIOException().initCause(e);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void batch(final List<? extends Row> actions, final Object[] results)
      throws InterruptedException, IOException {
    AsyncRequestFuture ars = multiAp.submitAll(pool, tableName, actions, null, results);
    ars.waitUntilDone();
    if (ars.hasError()) {
      throw ars.getErrors();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <R> void batchCallback(
    final List<? extends Row> actions, final Object[] results, final Batch.Callback<R> callback)
    throws IOException, InterruptedException {
    doBatchWithCallback(actions, results, callback, connection, pool, tableName);
  }

  public static <R> void doBatchWithCallback(List<? extends Row> actions, Object[] results,
    Callback<R> callback, ClusterConnection connection, ExecutorService pool, TableName tableName)
    throws InterruptedIOException, RetriesExhaustedWithDetailsException {
    AsyncRequestFuture ars = connection.getAsyncProcess().submitAll(
      pool, tableName, actions, callback, results);
    ars.waitUntilDone();
    if (ars.hasError()) {
      throw ars.getErrors();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void delete(final Delete delete)
  throws IOException {
    RegionServerCallable<Boolean> callable = new RegionServerCallable<Boolean>(connection,
        tableName, delete.getRow()) {
      @Override
      public Boolean call(int callTimeout) throws IOException {
        PayloadCarryingRpcController controller = rpcControllerFactory.newController();
        controller.setPriority(tableName);
        controller.setCallTimeout(callTimeout);

        try {
          MutateRequest request = RequestConverter.buildMutateRequest(
            getLocation().getRegionInfo().getRegionName(), delete);
          MutateResponse response = getStub().mutate(controller, request);
          return Boolean.valueOf(response.getProcessed());
        } catch (ServiceException se) {
          throw ProtobufUtil.getRemoteException(se);
        }
      }
    };
    rpcCallerFactory.<Boolean> newCaller(rpcTimeout).callWithRetries(callable,
        this.operationTimeout);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void delete(final List<Delete> deletes)
  throws IOException {
    Object[] results = new Object[deletes.size()];
    try {
      batch(deletes, results);
    } catch (InterruptedException e) {
      throw (InterruptedIOException)new InterruptedIOException().initCause(e);
    } finally {
      // mutate list so that it is empty for complete success, or contains only failed records
      // results are returned in the same order as the requests in list walk the list backwards,
      // so we can remove from list without impacting the indexes of earlier members
      for (int i = results.length - 1; i>=0; i--) {
        // if result is not null, it succeeded
        if (results[i] instanceof Result) {
          deletes.remove(i);
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   * @throws IOException
   */
  @Override
  public void put(final Put put) throws IOException {
    getBufferedMutator().mutate(put);
    flushCommits();
  }

  /**
   * {@inheritDoc}
   * @throws IOException
   */
  @Override
  public void put(final List<Put> puts) throws IOException {
    getBufferedMutator().mutate(puts);
    flushCommits();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void mutateRow(final RowMutations rm) throws IOException {
    final RetryingTimeTracker tracker = new RetryingTimeTracker();
    PayloadCarryingServerCallable<MultiResponse> callable =
      new PayloadCarryingServerCallable<MultiResponse>(connection, getName(), rm.getRow(),
          rpcControllerFactory) {
        @Override
        public MultiResponse call(int callTimeout) throws IOException {
          tracker.start();
          controller.setPriority(tableName);
          int remainingTime = tracker.getRemainingTime(callTimeout);
          if (remainingTime == 0) {
            throw new DoNotRetryIOException("Timeout for mutate row");
          }
          controller.setCallTimeout(remainingTime);
          try {
            RegionAction.Builder regionMutationBuilder = RequestConverter.buildRegionAction(
                getLocation().getRegionInfo().getRegionName(), rm);
            regionMutationBuilder.setAtomic(true);
            MultiRequest request =
                MultiRequest.newBuilder().addRegionAction(regionMutationBuilder.build()).build();
            ClientProtos.MultiResponse response = getStub().multi(controller, request);
            ClientProtos.RegionActionResult res = response.getRegionActionResultList().get(0);
            if (res.hasException()) {
              Throwable ex = ProtobufUtil.toException(res.getException());
              if (ex instanceof IOException) {
                throw (IOException) ex;
              }
              throw new IOException("Failed to mutate row: " +
                  Bytes.toStringBinary(rm.getRow()), ex);
            }
            return ResponseConverter.getResults(request, response, controller.cellScanner());
          } catch (ServiceException se) {
            throw ProtobufUtil.getRemoteException(se);
          }
        }
      };
    AsyncRequestFuture ars = multiAp.submitAll(pool, tableName, rm.getMutations(),
        null, null, callable, operationTimeout);
    ars.waitUntilDone();
    if (ars.hasError()) {
      throw ars.getErrors();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Result append(final Append append) throws IOException {
    if (append.numFamilies() == 0) {
      throw new IOException(
          "Invalid arguments to append, no columns specified");
    }

    NonceGenerator ng = this.connection.getNonceGenerator();
    final long nonceGroup = ng.getNonceGroup(), nonce = ng.newNonce();
    RegionServerCallable<Result> callable =
      new RegionServerCallable<Result>(this.connection, getName(), append.getRow()) {
        @Override
        public Result call(int callTimeout) throws IOException {
          PayloadCarryingRpcController controller = rpcControllerFactory.newController();
          controller.setPriority(getTableName());
          controller.setCallTimeout(callTimeout);
          try {
            MutateRequest request = RequestConverter.buildMutateRequest(
              getLocation().getRegionInfo().getRegionName(), append, nonceGroup, nonce);
            MutateResponse response = getStub().mutate(controller, request);
            if (!response.hasResult()) return null;
            return ProtobufUtil.toResult(response.getResult(), controller.cellScanner());
          } catch (ServiceException se) {
            throw ProtobufUtil.getRemoteException(se);
          }
        }
      };
    return rpcCallerFactory.<Result> newCaller(rpcTimeout).callWithRetries(callable,
        this.operationTimeout);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Result increment(final Increment increment) throws IOException {
    if (!increment.hasFamilies()) {
      throw new IOException(
          "Invalid arguments to increment, no columns specified");
    }
    NonceGenerator ng = this.connection.getNonceGenerator();
    final long nonceGroup = ng.getNonceGroup(), nonce = ng.newNonce();
    RegionServerCallable<Result> callable = new RegionServerCallable<Result>(this.connection,
        getName(), increment.getRow()) {
      @Override
      public Result call(int callTimeout) throws IOException {
        PayloadCarryingRpcController controller = rpcControllerFactory.newController();
        controller.setPriority(getTableName());
        controller.setCallTimeout(callTimeout);
        try {
          MutateRequest request = RequestConverter.buildMutateRequest(
            getLocation().getRegionInfo().getRegionName(), increment, nonceGroup, nonce);
          MutateResponse response = getStub().mutate(controller, request);
          return ProtobufUtil.toResult(response.getResult(), controller.cellScanner());
        } catch (ServiceException se) {
          throw ProtobufUtil.getRemoteException(se);
        }
      }
    };
    return rpcCallerFactory.<Result> newCaller(rpcTimeout).callWithRetries(callable,
        this.operationTimeout);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long incrementColumnValue(final byte [] row, final byte [] family,
      final byte [] qualifier, final long amount)
  throws IOException {
    return incrementColumnValue(row, family, qualifier, amount, Durability.SYNC_WAL);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long incrementColumnValue(final byte [] row, final byte [] family,
      final byte [] qualifier, final long amount, final Durability durability)
  throws IOException {
    NullPointerException npe = null;
    if (row == null) {
      npe = new NullPointerException("row is null");
    } else if (family == null) {
      npe = new NullPointerException("family is null");
    } else if (qualifier == null) {
      npe = new NullPointerException("qualifier is null");
    }
    if (npe != null) {
      throw new IOException(
          "Invalid arguments to incrementColumnValue", npe);
    }

    NonceGenerator ng = this.connection.getNonceGenerator();
    final long nonceGroup = ng.getNonceGroup(), nonce = ng.newNonce();
    RegionServerCallable<Long> callable =
      new RegionServerCallable<Long>(connection, getName(), row) {
        @Override
        public Long call(int callTimeout) throws IOException {
          PayloadCarryingRpcController controller = rpcControllerFactory.newController();
          controller.setPriority(getTableName());
          controller.setCallTimeout(callTimeout);
          try {
            MutateRequest request = RequestConverter.buildIncrementRequest(
              getLocation().getRegionInfo().getRegionName(), row, family,
              qualifier, amount, durability, nonceGroup, nonce);
            MutateResponse response = getStub().mutate(controller, request);
            Result result =
              ProtobufUtil.toResult(response.getResult(), controller.cellScanner());
            return Long.valueOf(Bytes.toLong(result.getValue(family, qualifier)));
          } catch (ServiceException se) {
            throw ProtobufUtil.getRemoteException(se);
          }
        }
      };
    return rpcCallerFactory.<Long> newCaller(rpcTimeout).callWithRetries(callable,
        this.operationTimeout);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean checkAndPut(final byte [] row,
      final byte [] family, final byte [] qualifier, final byte [] value,
      final Put put)
  throws IOException {
    RegionServerCallable<Boolean> callable =
      new RegionServerCallable<Boolean>(connection, getName(), row) {
        @Override
        public Boolean call(int callTimeout) throws IOException {
          PayloadCarryingRpcController controller = rpcControllerFactory.newController();
          controller.setPriority(tableName);
          controller.setCallTimeout(callTimeout);
          try {
            MutateRequest request = RequestConverter.buildMutateRequest(
                getLocation().getRegionInfo().getRegionName(), row, family, qualifier,
                new BinaryComparator(value), CompareType.EQUAL, put);
            MutateResponse response = getStub().mutate(controller, request);
            return Boolean.valueOf(response.getProcessed());
          } catch (ServiceException se) {
            throw ProtobufUtil.getRemoteException(se);
          }
        }
      };
    return rpcCallerFactory.<Boolean> newCaller(rpcTimeout).callWithRetries(callable,
        this.operationTimeout);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean checkAndPut(final byte [] row, final byte [] family,
      final byte [] qualifier, final CompareOp compareOp, final byte [] value,
      final Put put)
  throws IOException {
    RegionServerCallable<Boolean> callable =
      new RegionServerCallable<Boolean>(connection, getName(), row) {
        @Override
        public Boolean call(int callTimeout) throws IOException {
          PayloadCarryingRpcController controller = new PayloadCarryingRpcController();
          controller.setPriority(tableName);
          controller.setCallTimeout(callTimeout);
          try {
            CompareType compareType = CompareType.valueOf(compareOp.name());
            MutateRequest request = RequestConverter.buildMutateRequest(
              getLocation().getRegionInfo().getRegionName(), row, family, qualifier,
                new BinaryComparator(value), compareType, put);
            MutateResponse response = getStub().mutate(controller, request);
            return Boolean.valueOf(response.getProcessed());
          } catch (ServiceException se) {
            throw ProtobufUtil.getRemoteException(se);
          }
        }
      };
    return rpcCallerFactory.<Boolean> newCaller(rpcTimeout).callWithRetries(callable,
        this.operationTimeout);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean checkAndDelete(final byte [] row,
      final byte [] family, final byte [] qualifier, final byte [] value,
      final Delete delete)
  throws IOException {
    RegionServerCallable<Boolean> callable =
      new RegionServerCallable<Boolean>(connection, getName(), row) {
        @Override
        public Boolean call(int callTimeout) throws IOException {
          PayloadCarryingRpcController controller = rpcControllerFactory.newController();
          controller.setPriority(tableName);
          controller.setCallTimeout(callTimeout);
          try {
            MutateRequest request = RequestConverter.buildMutateRequest(
              getLocation().getRegionInfo().getRegionName(), row, family, qualifier,
                new BinaryComparator(value), CompareType.EQUAL, delete);
            MutateResponse response = getStub().mutate(controller, request);
            return Boolean.valueOf(response.getProcessed());
          } catch (ServiceException se) {
            throw ProtobufUtil.getRemoteException(se);
          }
        }
      };
    return rpcCallerFactory.<Boolean> newCaller(rpcTimeout).callWithRetries(callable,
        this.operationTimeout);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean checkAndDelete(final byte [] row, final byte [] family,
      final byte [] qualifier, final CompareOp compareOp, final byte [] value,
      final Delete delete)
  throws IOException {
    RegionServerCallable<Boolean> callable =
      new RegionServerCallable<Boolean>(connection, getName(), row) {
        @Override
        public Boolean call(int callTimeout) throws IOException {
          PayloadCarryingRpcController controller = rpcControllerFactory.newController();
          controller.setPriority(tableName);
          controller.setCallTimeout(callTimeout);
          try {
            CompareType compareType = CompareType.valueOf(compareOp.name());
            MutateRequest request = RequestConverter.buildMutateRequest(
              getLocation().getRegionInfo().getRegionName(), row, family, qualifier,
                new BinaryComparator(value), compareType, delete);
            MutateResponse response = getStub().mutate(controller, request);
            return Boolean.valueOf(response.getProcessed());
          } catch (ServiceException se) {
            throw ProtobufUtil.getRemoteException(se);
          }
        }
      };
    return rpcCallerFactory.<Boolean> newCaller(rpcTimeout).callWithRetries(callable,
        this.operationTimeout);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean checkAndMutate(final byte [] row, final byte [] family, final byte [] qualifier,
    final CompareOp compareOp, final byte [] value, final RowMutations rm)
    throws IOException {
    final RetryingTimeTracker tracker = new RetryingTimeTracker();
    PayloadCarryingServerCallable<MultiResponse> callable =
      new PayloadCarryingServerCallable<MultiResponse>(connection, getName(), rm.getRow(),
        rpcControllerFactory) {
        @Override
        public MultiResponse call(int callTimeout) throws IOException {
          tracker.start();
          controller.setPriority(tableName);
          int remainingTime = tracker.getRemainingTime(callTimeout);
          if (remainingTime == 0) {
            throw new DoNotRetryIOException("Timeout for mutate row");
          }
          controller.setCallTimeout(remainingTime);
          try {
            CompareType compareType = CompareType.valueOf(compareOp.name());
            MultiRequest request = RequestConverter.buildMutateRequest(
              getLocation().getRegionInfo().getRegionName(), row, family, qualifier,
              new BinaryComparator(value), compareType, rm);
            ClientProtos.MultiResponse response = getStub().multi(controller, request);
            ClientProtos.RegionActionResult res = response.getRegionActionResultList().get(0);
            if (res.hasException()) {
              Throwable ex = ProtobufUtil.toException(res.getException());
              if(ex instanceof IOException) {
                throw (IOException)ex;
              }
              throw new IOException("Failed to checkAndMutate row: "+
                                    Bytes.toStringBinary(rm.getRow()), ex);
            }
            return ResponseConverter.getResults(request, response, controller.cellScanner());
          } catch (ServiceException se) {
            throw ProtobufUtil.getRemoteException(se);
          }
        }
      };
    /**
     *  Currently, we use one array to store 'processed' flag which is returned by server.
     *  It is excessive to send such a large array, but that is required by the framework right now
     * */
    Object[] results = new Object[rm.getMutations().size()];
    AsyncRequestFuture ars = multiAp.submitAll(pool, tableName, rm.getMutations(),
      null, results, callable, operationTimeout);
    ars.waitUntilDone();
    if (ars.hasError()) {
      throw ars.getErrors();
    }

    return ((Result)results[0]).getExists();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean exists(final Get get) throws IOException {
    Result r = get(get, true);
    assert r.getExists() != null;
    return r.getExists();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean[] existsAll(final List<Get> gets) throws IOException {
    if (gets.isEmpty()) return new boolean[]{};
    if (gets.size() == 1) return new boolean[]{exists(gets.get(0))};

    ArrayList<Get> exists = new ArrayList<Get>(gets.size());
    for (Get g: gets){
      Get ge = new Get(g);
      ge.setCheckExistenceOnly(true);
      exists.add(ge);
    }

    Object[] r1= new Object[exists.size()];
    try {
      batch(exists, r1);
    } catch (InterruptedException e) {
      throw (InterruptedIOException)new InterruptedIOException().initCause(e);
    }

    // translate.
    boolean[] results = new boolean[r1.length];
    int i = 0;
    for (Object o : r1) {
      // batch ensures if there is a failure we get an exception instead
      results[i++] = ((Result)o).getExists();
    }

    return results;
  }

  /**
   * {@inheritDoc}
   * @throws IOException
   */
  void flushCommits() throws IOException {
    if (mutator == null) {
      // nothing to flush if there's no mutator; don't bother creating one.
      return;
    }
    getBufferedMutator().flush();
  }

  /**
   * Process a mixed batch of Get, Put and Delete actions. All actions for a
   * RegionServer are forwarded in one RPC call. Queries are executed in parallel.
   *
   * @param list The collection of actions.
   * @param results An empty array, same size as list. If an exception is thrown,
   *   you can test here for partial results, and to determine which actions
   *   processed successfully.
   * @throws IOException if there are problems talking to META. Per-item
   *   exceptions are stored in the results array.
   */
  public <R> void processBatchCallback(
    final List<? extends Row> list, final Object[] results, final Batch.Callback<R> callback)
    throws IOException, InterruptedException {
    this.batchCallback(list, results, callback);
  }


  /**
   * Parameterized batch processing, allowing varying return types for different
   * {@link Row} implementations.
   */
  public void processBatch(final List<? extends Row> list, final Object[] results)
    throws IOException, InterruptedException {
    this.batch(list, results);
  }


  @Override
  public void close() throws IOException {
    if (this.closed) {
      return;
    }
    flushCommits();
    if (cleanupPoolOnClose) {
      this.pool.shutdown();
      try {
        boolean terminated = false;
        do {
          // wait until the pool has terminated
          terminated = this.pool.awaitTermination(60, TimeUnit.SECONDS);
        } while (!terminated);
      } catch (InterruptedException e) {
        this.pool.shutdownNow();
        LOG.warn("waitForTermination interrupted");
      }
    }
    if (cleanupConnectionOnClose) {
      if (this.connection != null) {
        this.connection.close();
      }
    }
    this.closed = true;
  }

  // validate for well-formedness
  public void validatePut(final Put put) throws IllegalArgumentException {
    validatePut(put, connConfiguration.getMaxKeyValueSize());
  }

  // validate for well-formedness
  public static void validatePut(Put put, int maxKeyValueSize) throws IllegalArgumentException {
    if (put.isEmpty()) {
      throw new IllegalArgumentException("No columns to insert");
    }
    if (maxKeyValueSize > 0) {
      for (List<Cell> list : put.getFamilyCellMap().values()) {
        for (Cell cell : list) {
          if (KeyValueUtil.length(cell) > maxKeyValueSize) {
            throw new IllegalArgumentException("KeyValue size too large");
          }
        }
      }
    }
  }

  /**
   * Returns the maximum size in bytes of the write buffer for this HTable.
   * <p>
   * The default value comes from the configuration parameter
   * {@code hbase.client.write.buffer}.
   * @return The size of the write buffer in bytes.
   */
  @Override
  public long getWriteBufferSize() {
    if (mutator == null) {
      return connConfiguration.getWriteBufferSize();
    } else {
      return mutator.getWriteBufferSize();
    }
  }

  /**
   * Sets the size of the buffer in bytes.
   * <p>
   * If the new size is less than the current amount of data in the
   * write buffer, the buffer gets flushed.
   * @param writeBufferSize The new write buffer size, in bytes.
   * @throws IOException if a remote or network exception occurs.
   */
  @Override
  public void setWriteBufferSize(long writeBufferSize) throws IOException {
    getBufferedMutator();
    mutator.setWriteBufferSize(writeBufferSize);
  }

  /**
   * The pool is used for mutli requests for this HTable
   * @return the pool used for mutli
   */
  ExecutorService getPool() {
    return this.pool;
  }

  /**
   * Explicitly clears the region cache to fetch the latest value from META.
   * This is a power user function: avoid unless you know the ramifications.
   */
  public void clearRegionCache() {
    this.connection.clearRegionCache();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public CoprocessorRpcChannel coprocessorService(byte[] row) {
    return new RegionCoprocessorRpcChannel(connection, tableName, row);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T extends Service, R> Map<byte[],R> coprocessorService(final Class<T> service,
      byte[] startKey, byte[] endKey, final Batch.Call<T,R> callable)
      throws ServiceException, Throwable {
    final Map<byte[],R> results =  Collections.synchronizedMap(
        new TreeMap<byte[], R>(Bytes.BYTES_COMPARATOR));
    coprocessorService(service, startKey, endKey, callable, new Batch.Callback<R>() {
      @Override
      public void update(byte[] region, byte[] row, R value) {
        if (region != null) {
          results.put(region, value);
        }
      }
    });
    return results;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <T extends Service, R> void coprocessorService(final Class<T> service,
      byte[] startKey, byte[] endKey, final Batch.Call<T,R> callable,
      final Batch.Callback<R> callback) throws ServiceException, Throwable {

    // get regions covered by the row range
    List<byte[]> keys = getStartKeysInRange(startKey, endKey);

    Map<byte[],Future<R>> futures =
        new TreeMap<byte[],Future<R>>(Bytes.BYTES_COMPARATOR);
    for (final byte[] r : keys) {
      final RegionCoprocessorRpcChannel channel =
          new RegionCoprocessorRpcChannel(connection, tableName, r);
      Future<R> future = pool.submit(
          new Callable<R>() {
            @Override
            public R call() throws Exception {
              T instance = ProtobufUtil.newServiceStub(service, channel);
              R result = callable.call(instance);
              byte[] region = channel.getLastRegion();
              if (callback != null) {
                callback.update(region, r, result);
              }
              return result;
            }
          });
      futures.put(r, future);
    }
    for (Map.Entry<byte[],Future<R>> e : futures.entrySet()) {
      try {
        e.getValue().get();
      } catch (ExecutionException ee) {
        LOG.warn("Error calling coprocessor service " + service.getName() + " for row "
            + Bytes.toStringBinary(e.getKey()), ee);
        throw ee.getCause();
      } catch (InterruptedException ie) {
        throw new InterruptedIOException("Interrupted calling coprocessor service "
            + service.getName() + " for row " + Bytes.toStringBinary(e.getKey())).initCause(ie);
      }
    }
  }

  private List<byte[]> getStartKeysInRange(byte[] start, byte[] end)
  throws IOException {
    if (start == null) {
      start = HConstants.EMPTY_START_ROW;
    }
    if (end == null) {
      end = HConstants.EMPTY_END_ROW;
    }
    return getKeysAndRegionsInRange(start, end, true).getFirst();
  }

  @Override
  public void setOperationTimeout(int operationTimeout) {
    this.operationTimeout = operationTimeout;
  }

  @Override
  public int getOperationTimeout() {
    return operationTimeout;
  }

  @Override
  public int getRpcTimeout() {
    return rpcTimeout;
  }

  @Override
  public void setRpcTimeout(int rpcTimeout) {
    this.rpcTimeout = rpcTimeout;
  }

  @Override
  public String toString() {
    return tableName + ";" + connection;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <R extends Message> Map<byte[], R> batchCoprocessorService(
      Descriptors.MethodDescriptor methodDescriptor, Message request,
      byte[] startKey, byte[] endKey, R responsePrototype) throws ServiceException, Throwable {
    final Map<byte[], R> results = Collections.synchronizedMap(new TreeMap<byte[], R>(
        Bytes.BYTES_COMPARATOR));
    batchCoprocessorService(methodDescriptor, request, startKey, endKey, responsePrototype,
        new Callback<R>() {

          @Override
          public void update(byte[] region, byte[] row, R result) {
            if (region != null) {
              results.put(region, result);
            }
          }
        });
    return results;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public <R extends Message> void batchCoprocessorService(
      final Descriptors.MethodDescriptor methodDescriptor, final Message request,
      byte[] startKey, byte[] endKey, final R responsePrototype, final Callback<R> callback)
      throws ServiceException, Throwable {

    if (startKey == null) {
      startKey = HConstants.EMPTY_START_ROW;
    }
    if (endKey == null) {
      endKey = HConstants.EMPTY_END_ROW;
    }
    // get regions covered by the row range
    Pair<List<byte[]>, List<HRegionLocation>> keysAndRegions =
        getKeysAndRegionsInRange(startKey, endKey, true);
    List<byte[]> keys = keysAndRegions.getFirst();
    List<HRegionLocation> regions = keysAndRegions.getSecond();

    // check if we have any calls to make
    if (keys.isEmpty()) {
      LOG.info("No regions were selected by key range start=" + Bytes.toStringBinary(startKey) +
          ", end=" + Bytes.toStringBinary(endKey));
      return;
    }

    List<RegionCoprocessorServiceExec> execs = new ArrayList<RegionCoprocessorServiceExec>();
    final Map<byte[], RegionCoprocessorServiceExec> execsByRow =
        new TreeMap<byte[], RegionCoprocessorServiceExec>(Bytes.BYTES_COMPARATOR);
    for (int i = 0; i < keys.size(); i++) {
      final byte[] rowKey = keys.get(i);
      final byte[] region = regions.get(i).getRegionInfo().getRegionName();
      RegionCoprocessorServiceExec exec =
          new RegionCoprocessorServiceExec(region, rowKey, methodDescriptor, request);
      execs.add(exec);
      execsByRow.put(rowKey, exec);
    }

    // tracking for any possible deserialization errors on success callback
    // TODO: it would be better to be able to reuse AsyncProcess.BatchErrors here
    final List<Throwable> callbackErrorExceptions = new ArrayList<Throwable>();
    final List<Row> callbackErrorActions = new ArrayList<Row>();
    final List<String> callbackErrorServers = new ArrayList<String>();
    Object[] results = new Object[execs.size()];

    AsyncProcess asyncProcess =
        new AsyncProcess(connection, configuration, pool,
            RpcRetryingCallerFactory.instantiate(configuration, connection.getStatisticsTracker()),
            true, RpcControllerFactory.instantiate(configuration));

    AsyncRequestFuture future = asyncProcess.submitAll(tableName, execs,
        new Callback<ClientProtos.CoprocessorServiceResult>() {
          @Override
          public void update(byte[] region, byte[] row,
                              ClientProtos.CoprocessorServiceResult serviceResult) {
            if (LOG.isTraceEnabled()) {
              LOG.trace("Received result for endpoint " + methodDescriptor.getFullName() +
                  ": region=" + Bytes.toStringBinary(region) +
                  ", row=" + Bytes.toStringBinary(row) +
                  ", value=" + serviceResult.getValue().getValue());
            }
            try {
              Message.Builder builder = responsePrototype.newBuilderForType();
              ProtobufUtil.mergeFrom(builder, serviceResult.getValue().getValue());
              callback.update(region, row, (R) builder.build());
            } catch (IOException e) {
              LOG.error("Unexpected response type from endpoint " + methodDescriptor.getFullName(),
                  e);
              callbackErrorExceptions.add(e);
              callbackErrorActions.add(execsByRow.get(row));
              callbackErrorServers.add("null");
            }
          }
        }, results);

    future.waitUntilDone();

    if (future.hasError()) {
      throw future.getErrors();
    } else if (!callbackErrorExceptions.isEmpty()) {
      throw new RetriesExhaustedWithDetailsException(callbackErrorExceptions, callbackErrorActions,
          callbackErrorServers);
    }
  }

  public RegionLocator getRegionLocator() {
    return this.locator;
  }

  @VisibleForTesting
  BufferedMutator getBufferedMutator() throws IOException {
    if (mutator == null) {
      this.mutator = (BufferedMutatorImpl) connection.getBufferedMutator(
          new BufferedMutatorParams(tableName)
              .pool(pool)
              .writeBufferSize(connConfiguration.getWriteBufferSize())
              .maxKeyValueSize(connConfiguration.getMaxKeyValueSize())
      );
    }
    return mutator;
  }
}
