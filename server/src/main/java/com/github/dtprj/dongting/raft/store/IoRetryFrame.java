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
package com.github.dtprj.dongting.raft.store;

import com.github.dtprj.dongting.common.DtUtil;
import com.github.dtprj.dongting.fiber.FiberCancelException;
import com.github.dtprj.dongting.fiber.FiberFrame;
import com.github.dtprj.dongting.fiber.FiberFuture;
import com.github.dtprj.dongting.fiber.FiberInterruptException;
import com.github.dtprj.dongting.fiber.FrameCallResult;
import com.github.dtprj.dongting.raft.server.RaftGroupConfig;

import java.util.function.Supplier;

/**
 * @author huangli
 */
public class IoRetryFrame<O> extends FiberFrame<O> {

    private final RaftGroupConfig groupConfig;
    private final Supplier<FiberFuture<O>> futureSupplier;
    private final long timeoutMillis;
    private int retryCount;

    public IoRetryFrame(RaftGroupConfig groupConfig, Supplier<FiberFuture<O>> futureSupplier, long timeoutMillis) {
        this.groupConfig = groupConfig;
        this.futureSupplier = futureSupplier;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public FrameCallResult execute(Void input) {
        FiberFuture<O> f = futureSupplier.get();
        return awaitOn(f, timeoutMillis, this::resume);
    }

    private FrameCallResult resume(O o) {
        setResult(o);
        return frameReturn();
    }

    @Override
    protected FrameCallResult handle(Throwable ex) throws Throwable {
        if (isGroupShouldStop()) {
            throw ex;
        }
        Throwable root = DtUtil.rootCause(ex);
        if (root instanceof FiberInterruptException || root instanceof FiberCancelException) {
            throw ex;
        }
        if (retryCount >= groupConfig.getIoRetryInterval().length) {
            throw ex;
        }
        long sleepTime = groupConfig.getIoRetryInterval()[retryCount++];
        return sleep(sleepTime, this);
    }

    @Override
    protected FrameCallResult doFinally() {
        retryCount = 0;
        return frameReturn();
    }
}
