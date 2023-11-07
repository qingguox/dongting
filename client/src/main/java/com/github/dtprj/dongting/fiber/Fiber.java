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

/**
 * @author huangli
 */
public abstract class Fiber {
    protected final FiberGroup fiberGroup;
    protected final String fiberName;
    protected final boolean daemon;

    Fiber nextWaiter;

    boolean started;
    boolean ready;
    boolean finished;

    boolean interrupted;
    Throwable lastEx;

    WaitSource source;

    FiberFrame stackTop;

    public Fiber(String fiberName, FiberGroup fiberGroup, FiberFrame entryFrame, boolean daemon) {
        this.fiberGroup = fiberGroup;
        this.fiberName = fiberName;
        this.stackTop = entryFrame;
        this.daemon = daemon;
        entryFrame.fiber = this;
        entryFrame.group = fiberGroup;
    }

    FiberFrame popFrame() {
        if (stackTop == null) {
            return null;
        } else {
            FiberFrame f = stackTop;
            stackTop = f.prev;
            f.prev = null;
            return f;
        }
    }

    void pushFrame(FiberFrame frame) {
        if (stackTop != null) {
            frame.prev = stackTop;
        }
        stackTop = frame;
    }

    public void interrupt() {
        if (fiberGroup.isInGroupThread()) {
            fiberGroup.dispatcher.interrupt(this);
        } else {
            fiberGroup.dispatcher.shareQueue.offer(() -> fiberGroup.dispatcher.interrupt(this));
        }
    }

    public FiberCondition newCondition() {
        return fiberGroup.newCondition();
    }

    public FiberFuture newFuture() {
        return new FiberFuture(this.fiberGroup);
    }

    public String getFiberName() {
        return fiberName;
    }

    public FiberGroup getFiberGroup() {
        return fiberGroup;
    }
}
