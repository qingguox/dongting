/*
 * Copyright The Dongting Project
 *
 * The Dongting Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.github.dtprj.dongting.raft.server;

import com.github.dtprj.dongting.common.AbstractLifeCircle;
import com.github.dtprj.dongting.common.DtTime;
import com.github.dtprj.dongting.common.IntObjMap;
import com.github.dtprj.dongting.common.ObjUtil;
import com.github.dtprj.dongting.common.Timestamp;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.net.Commands;
import com.github.dtprj.dongting.net.HostPort;
import com.github.dtprj.dongting.net.NioClient;
import com.github.dtprj.dongting.net.NioClientConfig;
import com.github.dtprj.dongting.net.NioConfig;
import com.github.dtprj.dongting.net.NioServer;
import com.github.dtprj.dongting.net.NioServerConfig;
import com.github.dtprj.dongting.raft.client.RaftException;
import com.github.dtprj.dongting.raft.impl.MemberManager;
import com.github.dtprj.dongting.raft.impl.NodeManager;
import com.github.dtprj.dongting.raft.impl.Raft;
import com.github.dtprj.dongting.raft.impl.RaftExecutor;
import com.github.dtprj.dongting.raft.impl.RaftGroup;
import com.github.dtprj.dongting.raft.impl.RaftNode;
import com.github.dtprj.dongting.raft.impl.RaftRole;
import com.github.dtprj.dongting.raft.impl.RaftStatus;
import com.github.dtprj.dongting.raft.impl.RaftUtil;
import com.github.dtprj.dongting.raft.impl.ShareStatus;
import com.github.dtprj.dongting.raft.impl.VoteManager;
import com.github.dtprj.dongting.raft.rpc.AppendProcessor;
import com.github.dtprj.dongting.raft.rpc.InstallSnapshotProcessor;
import com.github.dtprj.dongting.raft.rpc.RaftPingProcessor;
import com.github.dtprj.dongting.raft.rpc.VoteProcessor;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author huangli
 */
public class RaftServer extends AbstractLifeCircle {
    private static final DtLog log = DtLogs.getLogger(RaftServer.class);
    private final NioServer raftServer;
    private final NioClient raftClient;

    private IntObjMap<GroupComponents> groupComponents = new IntObjMap<>();

    private CompletableFuture<Void> nodeReadyFuture;

    private final RaftServerConfig config;

    private final NodeManager nodeManager;

    private final int maxPendingWrites;
    private final long maxPendingWriteBytes;
    @SuppressWarnings({"unused"})
    private volatile int pendingWrites;
    @SuppressWarnings({"unused"})
    private volatile long pendingWriteBytes;

    private static final VarHandle PENDING_WRITES;
    private static final VarHandle PENDING_WRITE_BYTES;

    private final Timestamp readTimestamp = new Timestamp();


    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            PENDING_WRITES = lookup.findVarHandle(RaftServer.class, "pendingWrites", int.class);
            PENDING_WRITE_BYTES = lookup.findVarHandle(RaftServer.class, "pendingWriteBytes", long.class);
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    public RaftServer(RaftServerConfig config, List<RaftGroupConfig> groupConfig,
                      List<RaftLog> raftLogs, List<StateMachine> stateMachines) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(groupConfig);
        Objects.requireNonNull(raftLogs);
        Objects.requireNonNull(stateMachines);
        Objects.requireNonNull(config.getServers());

        if (groupConfig.size() != raftLogs.size() || groupConfig.size() != stateMachines.size()) {
            throw new IllegalArgumentException("groupConfig, raftLogs, stateMachines size not match");
        }

        ObjUtil.checkPositive(config.getId(), "id");
        ObjUtil.checkPositive(config.getRaftPort(), "port");
        this.config = config;
        this.maxPendingWrites = config.getMaxPendingWrites();
        this.maxPendingWriteBytes = config.getMaxPendingWriteBytes();

        List<RaftNode> allRaftServers = RaftUtil.parseServers(config.getServers());
        HashSet<Integer> allNodeIds = new HashSet<>();
        HashSet<HostPort> allNodeHosts = new HashSet<>();
        for (RaftNode rn : allRaftServers) {
            if (!allNodeIds.add(rn.getId())) {
                throw new IllegalArgumentException("duplicate server id");
            }
            if (!allNodeHosts.add(rn.getHostPort())) {
                throw new IllegalArgumentException("duplicate server host");
            }
        }

        LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        RaftExecutor raftExecutor = new RaftExecutor(queue);

        NioClientConfig nioClientConfig = new NioClientConfig();
        nioClientConfig.setName("RaftClient");
        setupNioConfig(nioClientConfig);
        raftClient = new NioClient(nioClientConfig);

        nodeManager = new NodeManager(config, allRaftServers, raftClient, this::onNodeReadyStatusChange);

        for (int i = 0; i < groupConfig.size(); i++) {
            RaftGroupConfig rgc = groupConfig.get(i);
            StateMachine stateMachine = stateMachines.get(i);
            RaftLog raftLog = raftLogs.get(i);

            String[] idsStr = rgc.getIds().split(",");
            HashSet<Integer> ids = new HashSet<>();
            for (String idStr : idsStr) {
                int id = Integer.parseInt(idStr.trim());
                if (!allNodeIds.contains(id)) {
                    throw new IllegalArgumentException("group config id not in servers");
                }
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("duplicated raft member id");
                }
            }

            int electQuorum = ids.size() / 2 + 1;
            int rwQuorum = ids.size() >= 4 && ids.size() % 2 == 0 ? ids.size() / 2 : electQuorum;
            RaftStatus raftStatus = new RaftStatus(electQuorum, rwQuorum);
            raftStatus.setRaftExecutor(raftExecutor);

            MemberManager memberManager = new MemberManager(config, raftClient, raftExecutor,
                    raftStatus, rgc.getGroupId(), ids);
            Raft raft = new Raft(config, rgc, raftStatus, raftLog, stateMachine, raftClient, raftExecutor);
            VoteManager voteManager = new VoteManager(config, raftStatus, raftClient, raftExecutor, raft);
            RaftGroup raftGroup = new RaftGroup(config, rgc, raftStatus, raftLog, stateMachine, raftExecutor,
                    raft, nodeManager, memberManager, voteManager);
            GroupComponents gc = new GroupComponents(config, rgc,raftLog, stateMachine, raftGroup, raftStatus, memberManager, voteManager);
            groupComponents.put(rgc.getGroupId(), gc);
        }

        NioServerConfig nioServerConfig = new NioServerConfig();
        nioServerConfig.setPort(config.getRaftPort());
        nioServerConfig.setName("RaftServer");
        nioServerConfig.setBizThreads(0);
        nioServerConfig.setIoThreads(1);
        setupNioConfig(nioServerConfig);
        raftServer = new NioServer(nioServerConfig);
        raftServer.register(Commands.RAFT_PING, new RaftPingProcessor(groupComponents), raftExecutor);
        AppendProcessor ap = new AppendProcessor(groupComponents);
        raftServer.register(Commands.RAFT_APPEND_ENTRIES, ap, raftExecutor);
        raftServer.register(Commands.RAFT_REQUEST_VOTE, new VoteProcessor(raftStatus), raftExecutor);
        raftServer.register(Commands.RAFT_INSTALL_SNAPSHOT, new InstallSnapshotProcessor(raftStatus, stateMachine), raftExecutor);
    }

    private void setupNioConfig(NioConfig nc) {
        nc.setMaxOutRequests(0);
        nc.setMaxInRequests(0);
        nc.setMaxInBytes(0);
        nc.setMaxBodySize(Integer.MAX_VALUE);
        nc.setMaxFrameSize(Integer.MAX_VALUE);
    }

    @Override
    protected void doStart() {
        groupComponents.forEach((groupId, gc) -> {
            gc.getRaftGroup().init();
            return true;
        });

        raftServer.start();
        raftClient.start();
        raftClient.waitStart();

        nodeReadyFuture = new CompletableFuture<>();
        nodeManager.start();
        try {
            nodeReadyFuture.get();
            nodeReadyFuture = null;
        } catch (Exception e) {
            log.error("error during wait node ready", e);
            throw new RaftException(e);
        }

        groupComponents.forEach((groupId, gc) -> {
            gc.getRaftGroup().start();
            return true;
        });
    }

    @Override
    protected void doStop() {
        raftServer.stop();
        raftClient.stop();

        groupComponents.forEach((groupId, gc) -> {
            RaftGroup raftGroup = gc.getRaftGroup();
            raftGroup.requestShutdown();
            raftGroup.interrupt();
            return true;
        });
    }

    public CompletableFuture<RaftOutput> submitLinearTask(RaftInput input) throws RaftException {
        if (raftStatus.isError()) {
            throw new RaftException("raft status is error");
        }
        int size = input.size();
        if (size > config.getMaxBodySize()) {
            throw new RaftException("request size too large, size=" + size + ", maxBodySize=" + config.getMaxBodySize());
        }
        int currentPendingWrites = (int) PENDING_WRITES.getAndAddRelease(this, 1);
        if (currentPendingWrites >= maxPendingWrites) {
            String msg = "submitRaftTask failed: too many pending writes, currentPendingWrites=" + currentPendingWrites;
            log.warn(msg);
            PENDING_WRITES.getAndAddRelease(this, -1);
            throw new RaftException(msg);
        }
        long currentPendingWriteBytes = (long) PENDING_WRITE_BYTES.getAndAddRelease(this, size);
        if (currentPendingWriteBytes >= maxPendingWriteBytes) {
            String msg = "submitRaftTask failed: too many pending write bytes,currentPendingWriteBytes="
                    + currentPendingWriteBytes + ", currentRequestBytes=" + size;
            log.warn(msg);
            PENDING_WRITE_BYTES.getAndAddRelease(this, -size);
            throw new RaftException(msg);
        }
        CompletableFuture<RaftOutput> f = raftGroup.submitRaftTask(input);
        registerCallback(f, size);
        return f;
    }

    private void registerCallback(CompletableFuture<?> f, int size) {
        f.whenComplete((o, ex) -> {
            PENDING_WRITES.getAndAddRelease(this, -1);
            PENDING_WRITE_BYTES.getAndAddRelease(this, -size);
        });
    }

    public long getLogIndexForRead(DtTime deadline)
            throws RaftException, InterruptedException, TimeoutException {
        if (raftStatus.isError()) {
            throw new RaftException("raft status error");
        }
        ShareStatus ss = raftStatus.getShareStatus();
        readTimestamp.refresh(1);
        if (ss.role != RaftRole.leader) {
            throw new NotLeaderException(RaftUtil.getLeader(ss.currentLeader));
        }
        long t = readTimestamp.getNanoTime();
        if (ss.leaseEndNanos - t < 0) {
            throw new NotLeaderException(null);
        }
        if (ss.firstCommitOfApplied != null) {
            try {
                ss.firstCommitOfApplied.get(deadline.rest(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS);
            } catch (ExecutionException e) {
                BugLog.log(e);
                throw new RaftException(e);
            }
        }
        return ss.lastApplied;
    }

    public void onNodeReadyStatusChange(int oldReady, int newReady) {
        CompletableFuture<Void> f = nodeReadyFuture;
        RaftUtil.onReadyStatusChange(newReady, f, raftStatus);
    }

}
