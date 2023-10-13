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
import com.github.dtprj.dongting.common.RunnableEx;
import com.github.dtprj.dongting.log.DtLog;
import com.github.dtprj.dongting.log.DtLogs;
import com.github.dtprj.dongting.raft.RaftException;
import com.github.dtprj.dongting.raft.impl.FileUtil;
import com.github.dtprj.dongting.raft.server.RaftGroupConfig;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author huangli
 */
abstract class FileQueue implements AutoCloseable {
    private static final DtLog log = DtLogs.getLogger(FileQueue.class);
    private static final Pattern PATTERN = Pattern.compile("^(\\d{20})$");
    protected final IndexedQueue<LogFile> queue = new IndexedQueue<>(32);
    protected final File dir;

    protected final ExecutorService ioExecutor;
    protected final Executor raftExecutor;
    protected final Supplier<Boolean> stopIndicator;
    protected final RaftGroupConfig groupConfig;

    protected long queueStartPosition;
    protected long queueEndPosition;

    protected CompletableFuture<LogFile> allocateFuture;

    private boolean deleting;

    public FileQueue(File dir, RaftGroupConfig groupConfig) {
        this.dir = dir;
        this.ioExecutor = groupConfig.getIoExecutor();
        this.raftExecutor = groupConfig.getRaftExecutor();
        this.stopIndicator = groupConfig.getStopIndicator();
        this.groupConfig = groupConfig;
    }

    protected abstract long getFileSize();

    protected abstract int getFileLenShiftBits();

    public void init() throws IOException {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        Arrays.sort(files);
        int count = 0;
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            Matcher matcher = PATTERN.matcher(f.getName());
            if (matcher.matches()) {
                if (f.length() != getFileSize()) {
                    throw new RaftException("file size error: " + f.getPath() + ", size=" + f.length());
                }
                long startPos = Long.parseLong(matcher.group(1));
                HashSet<OpenOption> openOptions = new HashSet<>();
                openOptions.add(StandardOpenOption.READ);
                openOptions.add(StandardOpenOption.WRITE);
                AsynchronousFileChannel channel = AsynchronousFileChannel.open(f.toPath(), openOptions, ioExecutor);
                queue.addLast(new LogFile(startPos, startPos + getFileSize(), channel, f));
                count++;
            }
        }
        for (int i = 0; i < queue.size(); i++) {
            LogFile lf = queue.get(i);
            if ((lf.startPos & (getFileSize() - 1)) != 0) {
                throw new RaftException("file start index error: " + lf.startPos);
            }
            if (i != 0 && lf.startPos != queue.get(i - 1).endPos) {
                throw new RaftException("not follow previous file " + lf.startPos);
            }
        }

        if (queue.size() > 0) {
            queueStartPosition = queue.get(0).startPos;
            queueEndPosition = queue.get(queue.size() - 1).endPos;
            log.info("load {} files in {}, first={}, last={}", count, dir.getPath(),
                    queue.get(0).file.getName(), queue.get(queue.size() - 1).file.getName());
        }
    }

    protected LogFile getLogFile(long filePos) {
        int index = (int) ((filePos - queueStartPosition) >>> getFileLenShiftBits());
        return queue.get(index);
    }

    private RunnableEx<Exception> ensureWritePosReadyCallback = () -> {
        try {
            tryAllocate();
            LogFile newFile = allocateFuture.get();
            queue.addLast(newFile);
            queueEndPosition = newFile.endPos;
        } finally {
            allocateFuture = null;
        }
    };

    protected void ensureWritePosReady(long pos, boolean retry, boolean closing) throws InterruptedException {
        while (pos >= queueEndPosition) {
            int[] retryIntervals = retry ? groupConfig.getIoRetryInterval() : null;
            FileUtil.doWithRetry(ensureWritePosReadyCallback, groupConfig.getStopIndicator(), closing, retryIntervals);
        }
        // pre allocate next file
        tryAllocate();
    }

    protected void tryAllocate() {
        if (allocateFuture == null) {
            allocateFuture = allocate(queueEndPosition);
        }
    }

    private CompletableFuture<LogFile> allocate(long currentEndPosition) {
        CompletableFuture<LogFile> future = new CompletableFuture<>();
        ioExecutor.execute(() -> {
            AsynchronousFileChannel channel = null;
            try {
                File f = new File(dir, String.format("%020d", currentEndPosition));
                HashSet<OpenOption> openOptions = new HashSet<>();
                openOptions.add(StandardOpenOption.READ);
                openOptions.add(StandardOpenOption.WRITE);
                openOptions.add(StandardOpenOption.CREATE);
                channel = AsynchronousFileChannel.open(f.toPath(), openOptions, ioExecutor);
                ByteBuffer buf = ByteBuffer.allocate(1);
                LogFile logFile = new LogFile(currentEndPosition, currentEndPosition + getFileSize(), channel, f);
                AsyncIoTask t = new AsyncIoTask(logFile.channel, stopIndicator, null);
                t.write(true, true, buf, getFileSize() - 1).whenComplete((v, ex) -> {
                    if (ex != null) {
                        log.info("allocate log file failed: {}", logFile.file.getPath(), ex);
                        future.completeExceptionally(ex);
                    } else {
                        log.info("allocate log file done: {}", logFile.file.getPath());
                        future.complete(logFile);
                    }
                });
            } catch (Throwable e) {
                if (channel != null) {
                    DtUtil.close(channel);
                }
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public void close() {
        for (int i = 0; i < queue.size(); i++) {
            DtUtil.close(queue.get(i).channel);
        }
    }

    protected void submitDeleteTask(Predicate<LogFile> shouldDelete) {
        if (deleting) {
            log.info("deleting, ignore delete request");
            return;
        }
        if (stopIndicator.get()) {
            return;
        }
        if (queue.size() <= 1) {
            return;
        }
        LogFile logFile = queue.get(0);
        if (!shouldDelete.test(logFile)) {
            return;
        }
        deleting = true;
        ioExecutor.execute(() -> {
            try {
                if (stopIndicator.get()) {
                    return;
                }
                log.debug("close log file: {}", logFile.file.getPath());
                DtUtil.close(logFile.channel);
                log.info("delete log file: {}", logFile.file.getPath());
                Files.delete(logFile.file.toPath());
                raftExecutor.execute(() -> processDeleteResult(true, shouldDelete));
            } catch (Throwable e) {
                log.error("delete file fail: ", logFile.file.getPath(), e);
                raftExecutor.execute(() -> processDeleteResult(false, shouldDelete));
            }
        });
    }

    private void processDeleteResult(boolean success, Predicate<LogFile> shouldDelete) {
        // access variable deleting in raft thread
        deleting = false;
        if (success) {
            queue.removeFirst();
            queueStartPosition = queue.get(0).startPos;
            submitDeleteTask(shouldDelete);
            afterDelete();
        }
    }

    protected void afterDelete() {
    }

}
