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

import com.github.dtprj.dongting.common.DtTime;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.net.Commands;
import com.github.dtprj.dongting.net.Decoder;
import com.github.dtprj.dongting.net.NioClient;
import com.github.dtprj.dongting.net.PbZeroCopyDecoder;
import com.github.dtprj.dongting.net.ReadFrame;
import com.github.dtprj.dongting.raft.rpc.VoteReq;
import com.github.dtprj.dongting.raft.rpc.VoteResp;
import com.github.dtprj.dongting.raft.server.RaftGroupConfig;
import com.github.dtprj.dongting.raft.server.RaftServerConfig;

import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author huangli
 */
public class VoteManager {

    private static final DtLog log = DtLogs.getLogger(VoteManager.class);
    private final Raft raft;
    private final NioClient client;
    private final RaftStatus raftStatus;
    private final RaftServerConfig config;
    private final int groupId;
    private final RaftExecutor raftExecutor;

    private static final Decoder RESP_DECODER = new PbZeroCopyDecoder(c -> new VoteResp.Callback());

    private boolean voting;
    private HashSet<Integer> votes;
    private int votePendingCount;
    private int currentVoteId;


    public VoteManager(RaftServerConfig serverConfig, RaftGroupConfig groupConfig, RaftStatus raftStatus, NioClient client, RaftExecutor executor, Raft raft) {
        this.raft = raft;
        this.client = client;
        this.raftStatus = raftStatus;
        this.config = serverConfig;
        this.groupId = groupConfig.getGroupId();
        this.raftExecutor = executor;
    }

    public void cancelVote() {
        if (voting) {
            log.info("cancel current voting: groupId={}, voteId={}", groupId, currentVoteId);
            voting = false;
            votes = null;
            currentVoteId++;
            votePendingCount = 0;
        }
    }

    private void initStatusForVoting(int count) {
        voting = true;
        currentVoteId++;
        votes = new HashSet<>();
        votes.add(config.getNodeId());
        votePendingCount = count - 1;
    }

    private void descPending(int voteIdOfRequest) {
        if (voteIdOfRequest != currentVoteId) {
            return;
        }
        if (--votePendingCount == 0) {
            voting = false;
            votes = null;
        }
    }

    private int readyCount() {
        int count = 0;
        for (RaftMember member : raftStatus.getAllMembers()) {
            if (member.isReady()) {
                // include self
                count++;
            }
        }
        return count;
    }

    public void tryStartPreVote() {
        if (voting) {
            return;
        }
        int count = readyCount();

        // move last elect time 1 seconds, prevent pre-vote too frequently if failed
        long newLastElectTime = raftStatus.getLastElectTime() + TimeUnit.SECONDS.toNanos(1);
        raftStatus.setLastElectTime(newLastElectTime);

        if (count >= raftStatus.getElectQuorum()) {
            initStatusForVoting(count);
            log.info("{} node is ready, start pre vote. groupId={}, term={}, voteId={}",
                    count, groupId, raftStatus.getCurrentTerm(), currentVoteId);
            startPreVote();
        } else if (count < raftStatus.getElectQuorum()) {
            log.warn("only {} node is ready, can't start pre vote. groupId={}, term={}",
                    count, groupId, raftStatus.getCurrentTerm());
        }
    }

    private void startPreVote() {
        for (RaftMember member : raftStatus.getAllMembers()) {
            if (!member.getNode().isSelf() && member.isReady()) {
                sendRequest(member, true, 0);
            }
        }
    }

    private void sendRequest(RaftMember member, boolean preVote, long leaseStartTime) {
        VoteReq req = new VoteReq();
        int currentTerm = raftStatus.getCurrentTerm();
        req.setGroupId(groupId);
        req.setTerm(preVote ? currentTerm + 1 : currentTerm);
        req.setCandidateId(config.getNodeId());
        req.setLastLogIndex(raftStatus.getLastLogIndex());
        req.setLastLogTerm(raftStatus.getLastLogTerm());
        req.setPreVote(preVote);
        VoteReq.WriteFrame wf = new VoteReq.WriteFrame(req);
        wf.setCommand(Commands.RAFT_REQUEST_VOTE);
        DtTime timeout = new DtTime(config.getRpcTimeout(), TimeUnit.MILLISECONDS);

        final int voteIdOfRequest = this.currentVoteId;

        CompletableFuture<ReadFrame> f = client.sendRequest(member.getNode().getPeer(), wf, RESP_DECODER, timeout);
        log.info("send {} request. remoteNode={}, groupId={}, term={}, lastLogIndex={}, lastLogTerm={}",
                preVote ? "pre-vote" : "vote", member.getNode().getNodeId(), groupId,
                currentTerm, req.getLastLogIndex(), req.getLastLogTerm());
        if (preVote) {
            f.handleAsync((rf, ex) -> processPreVoteResp(rf, ex, member, req, voteIdOfRequest), raftExecutor);
        } else {
            f.handleAsync((rf, ex) -> processVoteResp(rf, ex, member, req, voteIdOfRequest, leaseStartTime), raftExecutor);
        }
    }

    private Object processPreVoteResp(ReadFrame rf, Throwable ex, RaftMember remoteMember, VoteReq req, int voteIdOfRequest) {
        if (voteIdOfRequest != currentVoteId) {
            return null;
        }
        int currentTerm = raftStatus.getCurrentTerm();
        if (ex == null) {
            VoteResp preVoteResp = (VoteResp) rf.getBody();
            if (preVoteResp.isVoteGranted() && raftStatus.getRole() == RaftRole.follower
                    && preVoteResp.getTerm() == req.getTerm()) {
                log.info("receive pre-vote grant success. term={}, remoteNode={}, groupId={}",
                        currentTerm, remoteMember.getNode().getNodeId(), groupId);
                int oldCount = votes.size();
                votes.add(remoteMember.getNode().getNodeId());
                int newCount = votes.size();
                if (newCount > oldCount && newCount == raftStatus.getElectQuorum()) {
                    log.info("pre-vote success, start elect. groupId={}. term={}", groupId, currentTerm);
                    startVote();
                }
            } else {
                log.info("receive pre-vote grant fail. term={}, remoteNode={}, groupId={}",
                        currentTerm, remoteMember.getNode().getNodeId(), groupId);
            }
        } else {
            log.warn("pre-vote rpc fail. term={}, remoteNode={}, groupId={}, error={}",
                    currentTerm, remoteMember.getNode().getNodeId(), groupId, ex.toString());
            // don't send more request for simplification
        }
        descPending(voteIdOfRequest);

        return null;
    }

    private void startVote() {
        int count = readyCount();
        if (count < raftStatus.getElectQuorum()) {
            log.warn("only {} node is ready, can't start vote. groupId={}, term={}",
                    count, groupId, raftStatus.getCurrentTerm());
            return;
        }
        RaftUtil.resetStatus(raftStatus);
        if (raftStatus.getRole() != RaftRole.candidate) {
            log.info("change to candidate. groupId={}, oldTerm={}", groupId, raftStatus.getCurrentTerm());
            raftStatus.setRole(RaftRole.candidate);
        }

        raftStatus.setCurrentTerm(raftStatus.getCurrentTerm() + 1);
        raftStatus.setVotedFor(config.getNodeId());
        initStatusForVoting(raftStatus.getAllMembers().size());
        StatusUtil.updateStatusFile(raftStatus);

        log.info("start vote. groupId={}, newTerm={}, voteId={}", groupId, raftStatus.getCurrentTerm(), currentVoteId);

        long leaseStartTime = raftStatus.getTs().getNanoTime();
        for (RaftMember member : raftStatus.getAllMembers()) {
            if (!member.getNode().isSelf()) {
                if (member.isReady()) {
                    sendRequest(member, false, leaseStartTime);
                } else {
                    descPending(currentVoteId);
                }
            } else {
                member.setLastConfirm(true, leaseStartTime);
            }
        }
    }

    private Object processVoteResp(ReadFrame rf, Throwable ex, RaftMember remoteMember,
                                   VoteReq voteReq, int voteIdOfRequest, long leaseStartTime) {
        if (voteIdOfRequest != currentVoteId) {
            return null;
        }
        if (ex == null) {
            processVoteResp(rf, remoteMember, voteReq, leaseStartTime);
        } else {
            log.warn("vote rpc fail. groupId={}, term={}, remote={}, error={}",
                    groupId,  voteReq.getTerm(), remoteMember.getNode().getHostPort(), ex.toString());
            // don't send more request for simplification
        }
        descPending(voteIdOfRequest);
        return null;
    }

    private void processVoteResp(ReadFrame rf, RaftMember remoteMember, VoteReq voteReq, long leaseStartTime) {
        VoteResp voteResp = (VoteResp) rf.getBody();
        int remoteTerm = voteResp.getTerm();
        if (remoteTerm < raftStatus.getCurrentTerm()) {
            log.warn("receive outdated vote resp, ignore, remoteTerm={}, reqTerm={}, remoteId={}, groupId={}",
                    voteResp.getTerm(), voteReq.getTerm(), remoteMember.getNode().getNodeId(), groupId);
        } else if (remoteTerm == raftStatus.getCurrentTerm()) {
            if (raftStatus.getRole() != RaftRole.candidate) {
                log.warn("receive vote resp, not candidate, ignore. remoteTerm={}, reqTerm={}, remoteId={}, groupId={}",
                        voteResp.getTerm(), voteReq.getTerm(), remoteMember.getNode().getNodeId(), groupId);
            } else {
                int oldCount = votes.size();
                log.info("receive vote resp, granted={}, remoteTerm={}, reqTerm={}, oldVotes={}, remoteId={}, groupId={}",
                        voteResp.isVoteGranted(), voteResp.getTerm(),
                        voteReq.getTerm(), oldCount, remoteMember.getNode().getNodeId(), groupId);
                if (voteResp.isVoteGranted()) {
                    votes.add(remoteMember.getNode().getNodeId());
                    remoteMember.setLastConfirm(true, leaseStartTime);
                    int newCount = votes.size();
                    if (newCount > oldCount && newCount == raftStatus.getElectQuorum()) {
                        RaftUtil.changeToLeader(raftStatus);
                        RaftUtil.updateLease(leaseStartTime, raftStatus);
                        raft.sendHeartBeat();
                    }
                }
            }
        } else {
            RaftUtil.incrTermAndConvertToFollower(remoteTerm, raftStatus, -1, true);
        }
    }
}
