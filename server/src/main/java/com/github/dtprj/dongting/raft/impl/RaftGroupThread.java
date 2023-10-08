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
package com.github.dtprj.dongting.raft.impl;

import com.github.dtprj.dongting.buf.RefBuffer;
import com.github.dtprj.dongting.common.DtUtil;
import com.github.dtprj.dongting.common.Pair;
import com.github.dtprj.dongting.common.Timestamp;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.RaftException;
import com.github.dtprj.dongting.raft.server.LogItem;
import com.github.dtprj.dongting.raft.server.RaftGroupConfig;
import com.github.dtprj.dongting.raft.server.RaftInput;
import com.github.dtprj.dongting.raft.server.RaftOutput;
import com.github.dtprj.dongting.raft.server.RaftServerConfig;
import com.github.dtprj.dongting.raft.sm.Snapshot;
import com.github.dtprj.dongting.raft.sm.SnapshotManager;
import com.github.dtprj.dongting.raft.sm.StateMachine;
import com.github.dtprj.dongting.raft.store.RaftLog;
import com.github.dtprj.dongting.raft.store.StatusManager;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author huangli
 */
public class RaftGroupThread extends Thread {
    private static final DtLog log = DtLogs.getLogger(RaftGroupThread.class);

    private final Random random = new Random();

    private RaftServerConfig config;
    private RaftStatusImpl raftStatus;
    private Raft raft;
    private MemberManager memberManager;
    private VoteManager voteManager;
    private StateMachine stateMachine;
    private RaftLog raftLog;
    private SnapshotManager snapshotManager;
    private ApplyManager applyManager;
    private CommitManager commitManager;

    private long heartbeatIntervalNanos;
    private long electTimeoutNanos;
    private StatusManager statusManager;

    private RaftGroupConfig groupConfig;

    private volatile boolean prepared;

    private final CompletableFuture<Void> prepareFuture = new CompletableFuture<>();

    // TODO optimise blocking queue
    private LinkedBlockingQueue<Object> queue;

    private Dispatcher dispatcher;

    public RaftGroupThread() {
    }

    public void init(RaftGroupImpl g) {
        GroupComponents gc = g.getGroupComponents();
        this.config = gc.getServerConfig();
        this.raftStatus = gc.getRaftStatus();
        this.queue = gc.getRaftExecutor().getQueue();
        this.raft = gc.getRaft();
        this.memberManager = gc.getMemberManager();
        this.stateMachine = gc.getStateMachine();
        this.raftLog = gc.getRaftLog();
        this.voteManager = gc.getVoteManager();
        this.snapshotManager = gc.getSnapshotManager();
        this.applyManager = gc.getApplyManager();
        this.statusManager = gc.getStatusManager();
        this.groupConfig = gc.getGroupConfig();
        this.commitManager = gc.getCommitManager();

        this.dispatcher = new Dispatcher(queue, raftStatus, raft);
        electTimeoutNanos = Duration.ofMillis(config.getElectTimeout()).toNanos();
        raftStatus.setElectTimeoutNanos(electTimeoutNanos);
        heartbeatIntervalNanos = Duration.ofMillis(config.getHeartbeatInterval()).toNanos();

        RaftGroupConfig groupConfig = gc.getGroupConfig();
        setName("raft-" + groupConfig.getGroupId());

    }

    private boolean prepareEventLoop() {
        try {
            statusManager.initStatusFile();
            Pair<Integer, Long> snapshotResult = recoverStateMachine();
            int snapshotTerm = snapshotResult == null ? 0 : snapshotResult.getLeft();
            long snapshotIndex = snapshotResult == null ? 0 : snapshotResult.getRight();
            log.info("load snapshot to term={}, index={}, groupId={}", snapshotTerm, snapshotIndex, groupConfig.getGroupId());
            raftStatus.setLastApplied(snapshotIndex);
            if (snapshotIndex > raftStatus.getCommitIndex()) {
                raftStatus.setCommitIndex(snapshotIndex);
            }
            RaftUtil.checkStop(raftStatus::isStop);

            Pair<Integer, Long> initResult = raftLog.init(commitManager);
            int initResultTerm = initResult.getLeft();
            long initResultIndex = initResult.getRight();
            if (initResultIndex < snapshotIndex || initResultIndex < raftStatus.getCommitIndex()) {
                log.error("raft log last index invalid, {}, {}, {}", initResultIndex, snapshotIndex, raftStatus.getCommitIndex());
                throw new RaftException("raft log last index invalid");
            }
            if (initResultTerm < snapshotTerm || initResultTerm < raftStatus.getCurrentTerm()) {
                log.error("raft log last term invalid, {}, {}, {}", initResultTerm, snapshotTerm, raftStatus.getCurrentTerm());
                throw new RaftException("raft log last term invalid");
            }
            RaftUtil.checkStop(raftStatus::isStop);

            log.info("init raft log, maxTerm={}, maxIndex={}, groupId={}",
                    initResult.getLeft(), initResult.getRight(), groupConfig.getGroupId());
            raftStatus.setLastLogTerm(initResultTerm);
            raftStatus.setLastLogIndex(initResultIndex);
            raftStatus.setLastPersistLogTerm(initResultTerm);
            raftStatus.setLastPersistLogIndex(initResultIndex);
            prepareFuture.complete(null);
            return true;
        } catch (Throwable e) {
            log.error("prepare raft event loop failed, groupId={}", groupConfig.getGroupId(), e);
            prepareFuture.completeExceptionally(e);
            return false;
        }
    }

    private Pair<Integer, Long> recoverStateMachine() throws Exception {
        if (snapshotManager == null) {
            return null;
        }
        try (Snapshot snapshot = snapshotManager.init(raftStatus::isStop)) {
            if (snapshot == null) {
                return null;
            }
            long offset = 0;
            while (true) {
                RaftUtil.checkStop(raftStatus::isStop);
                CompletableFuture<RefBuffer> f = snapshot.readNext();
                RefBuffer rb = f.get();
                try {
                    long count = rb == null ? 0 : rb.getBuffer() == null ? 0 : rb.getBuffer().remaining();
                    if (count == 0) {
                        stateMachine.installSnapshot(snapshot.getLastIncludedIndex(), snapshot.getLastIncludedTerm(),
                                offset, true, rb);
                        break;
                    }
                    stateMachine.installSnapshot(snapshot.getLastIncludedIndex(), snapshot.getLastIncludedTerm(),
                            offset, false, rb);
                    offset += count;
                } finally {
                    if (rb != null) {
                        rb.release();
                    }
                }
            }
            return new Pair<>(snapshot.getLastIncludedTerm(), snapshot.getLastIncludedIndex());
        }
    }

    public CompletableFuture<Void> readyFuture() {
        return CompletableFuture.allOf(prepareFuture, memberReadyFuture(), applyReadyFuture());
    }

    private CompletableFuture<Void> memberReadyFuture() {
        int electQuorum = raftStatus.getElectQuorum();
        if (electQuorum <= 1) {
            return CompletableFuture.completedFuture(null);
        }
        return memberManager.createReadyFuture(electQuorum);
    }

    private CompletableFuture<Void> applyReadyFuture() {
        return applyManager.initReadyFuture();
    }

    private void clean() {
        DtUtil.close(stateMachine);
        DtUtil.close(raftLog);
        DtUtil.close(statusManager);
    }

    @Override
    public void run() {
        try {
            if (!prepareEventLoop()) {
                return;
            }
            prepared = true;
            RaftUtil.checkStop(raftStatus::isStop);
            if (raftStatus.getElectQuorum() == 1 && raftStatus.getNodeIdOfMembers().contains(config.getNodeId())) {
                RaftUtil.changeToLeader(raftStatus);
                raft.sendHeartBeat();
            }
            applyManager.apply(raftStatus);
            raftLoop();
            log.info("raft thread exit, groupId={}", raftStatus.getGroupId());
        } catch (Throwable e) {
            Throwable cause = DtUtil.rootCause(e);
            if (cause instanceof InterruptedException) {
                log.info("raft thread interrupted, groupId={}", raftStatus.getGroupId());
            } else if (cause instanceof StoppedException) {
                log.info("raft thread stopped, groupId={}", raftStatus.getGroupId());
            } else {
                BugLog.getLog().error("raft thread error", e);
            }
        } finally {
            clean();
        }
    }

    private void raftLoop() {
        Timestamp ts = raftStatus.getTs();
        long lastCleanTime = ts.getNanoTime();
        while (!raftStatus.isStop()) {
            if (raftStatus.getRole() != RaftRole.observer) {
                memberManager.ensureRaftMemberStatus();
            }
            try {
                dispatcher.runOnce();
            } catch (InterruptedException e) {
                return;
            }
            if (ts.getNanoTime() - lastCleanTime > 5 * 1000 * 1000) {
                idle(ts);
                lastCleanTime = ts.getNanoTime();
            }
        }
    }

    public void requestShutdown() {
        raftStatus.setStop(true);
        this.interrupt();
        log.info("request raft thread shutdown");
    }

    private void idle(Timestamp ts) {
        RaftStatusImpl raftStatus = this.raftStatus;
        if (raftStatus.isError()) {
            return;
        }

        raftStatus.getTailCache().cleanPending(raftStatus, config.getMaxPendingWrites(),
                config.getMaxPendingWriteBytes());

        raftLog.doDelete();

        if (raftStatus.getElectQuorum() <= 1) {
            return;
        }
        long roundTimeNanos = ts.getNanoTime();

        RaftRole role = raftStatus.getRole();
        if (roundTimeNanos - raftStatus.getHeartbeatTime() > heartbeatIntervalNanos) {
            if (role == RaftRole.leader) {
                raftStatus.setHeartbeatTime(roundTimeNanos);
                raft.sendHeartBeat();
                raftStatus.copyShareStatus();
            }
        }
        if (role == RaftRole.follower || role == RaftRole.candidate) {
            if (roundTimeNanos - raftStatus.getLastElectTime() > electTimeoutNanos + random.nextInt(200)) {
                voteManager.tryStartPreVote();
                raftStatus.copyShareStatus();
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public CompletableFuture<RaftOutput> submitRaftTask(RaftInput input) {
        CompletableFuture f = new CompletableFuture<>();
        RaftTask t = new RaftTask(raftStatus.getTs(), LogItem.TYPE_NORMAL, input, f);
        queue.offer(t);
        return f;
    }

    public boolean isPrepared() {
        return prepared;
    }
}
