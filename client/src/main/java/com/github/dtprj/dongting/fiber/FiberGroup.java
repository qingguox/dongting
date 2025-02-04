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
package com.github.dtprj.dongting.fiber;

import com.github.dtprj.dongting.common.IndexedQueue;
import com.github.dtprj.dongting.common.IntObjMap;
import com.github.dtprj.dongting.log.BugLog;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;

import java.util.HashSet;

/**
 * @author huangli
 */
public class FiberGroup {
    private static final DtLog log = DtLogs.getLogger(FiberGroup.class);
    private final String name;
    final Dispatcher dispatcher;
    final IndexedQueue<Fiber> readyFibers = new IndexedQueue<>(64);
    private final HashSet<Fiber> normalFibers = new HashSet<>();
    private final HashSet<Fiber> daemonFibers = new HashSet<>();
    private final IntObjMap<FiberChannel<Object>> channels = new IntObjMap<>();

    boolean shouldStop = false;
    boolean finished;
    boolean ready;

    public FiberGroup(String name, Dispatcher dispatcher) {
        this.name = name;
        this.dispatcher = dispatcher;
    }

    /**
     * can call in any thread
     */
    public void fireMessage(int type, Object data) {
        dispatcher.shareQueue.offer(() -> {
            FiberChannel<Object> c = channels.get(type);
            if (c == null) {
                log.warn("channel not found: {}", type);
                return;
            }
            c.offer(data);
        });
    }

    /**
     * can call in any thread
     */
    public void requestShutdown() {
        dispatcher.doInDispatcherThread(() -> {
            shouldStop = true;
            updateFinishStatus();
        });
    }

    @SuppressWarnings("unchecked")
    <T> FiberChannel<T> createOrGetChannel(int type) {
        FiberChannel<Object> channel = channels.get(type);
        if (channel == null) {
            channel = new FiberChannel<>(this);
        }
        channels.put(type, channel);
        return (FiberChannel<T>) channel;
    }

    public String getName() {
        return name;
    }

    public FiberCondition newCondition() {
        return new FiberCondition(this);
    }

    public <T> FiberFuture<T> newFuture() {
        return new FiberFuture<>(this);
    }

    boolean isInGroupThread() {
        return Thread.currentThread() == dispatcher.thread;
    }

    void start(Fiber f) {
        if (f.started) {
            throw new FiberException("fiber already started: " + f.getFiberName());
        }
        if (f.daemon) {
            daemonFibers.add(f);
        } else {
            normalFibers.add(f);
        }
        tryMakeFiberReady(f, false);
    }

    void removeFiber(Fiber f) {
        boolean removed;
        if (f.daemon) {
            removed = daemonFibers.remove(f);
        } else {
            removed = normalFibers.remove(f);
            updateFinishStatus();
        }
        if (!removed) {
            BugLog.getLog().error("fiber is not in set: {}", f.getFiberName());
        }
    }

    void tryMakeFiberReady(Fiber f, boolean addFirst) {
        if (finished) {
            log.warn("group finished, ignore makeReady: {}", f.getFiberName());
            return;
        }
        if (f.finished) {
            log.warn("fiber already finished, ignore makeReady: {}", f.getFiberName());
            return;
        }
        if (!f.ready) {
            f.ready = true;
            if (addFirst) {
                readyFibers.addFirst(f);
            } else {
                readyFibers.addLast(f);
            }
            makeGroupReady();
        }
    }

    private void makeGroupReady() {
        if (ready) {
            return;
        }
        ready = true;
        dispatcher.readyGroups.addLast(this);
    }

    private void updateFinishStatus() {
        if (!finished) {
            finished = shouldStop && normalFibers.isEmpty();
        }
    }

}
