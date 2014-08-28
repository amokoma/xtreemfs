/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.xtreemfs.osd.operations.OSDOperation;
import org.xtreemfs.osd.operations.ReadOperation;
import org.xtreemfs.osd.operations.TruncateOperation;
import org.xtreemfs.osd.operations.WriteOperation;
import org.xtreemfs.osd.stages.Stage;
import org.xtreemfs.osd.stages.TraceContainer;
import org.xtreemfs.osd.stages.TraceContainer.Operation;
import org.xtreemfs.osd.stages.TracingStage;

public class TracingThread extends Stage {


    BlockingQueue<TraceContainer>      queue;
    BlockingQueue<StageRequest>   masterQ;
    int                           id;

    public TracingThread(int id, OSDRequestDispatcher master, int maxQueueLength, BlockingQueue<TraceContainer> queue,
            BlockingQueue<StageRequest> masterQ, TracingStage tracingStage) {
        super("TracingThread" + id, maxQueueLength);
        this.queue = queue;
        this.id = id;
        this.masterQ = masterQ;

    }

    @Override
    public void run() {
        while (!quit || queue.size() > 0) {
            try {


                StageRequest request = masterQ.poll(OSDConfig.OFT_CLEAN_INTERVAL, TimeUnit.MILLISECONDS);

                if (request == null) {
                    continue;
                }

                processMethod(request);

            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

    }

    @Override
    protected void processMethod(StageRequest stageRequest) {
        OSDRequest rq = (OSDRequest) stageRequest.getArgs()[0];
        OSDOperation op = rq.getOperation();
        TraceContainer tc = null;
        // Filter Operation
        if (op instanceof ReadOperation) {
            tc = buildTraceContainer(stageRequest, Operation.ReadOperation);
        }
        if (op instanceof WriteOperation) {
            tc = buildTraceContainer(stageRequest, Operation.WriteOperation);
        }

        if (op instanceof TruncateOperation) {
            tc = buildTraceContainer(stageRequest, Operation.TruncateOperation);
        }

        try {
            this.queue.put(tc);

        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private TraceContainer buildTraceContainer(StageRequest stageRequest, Operation op) {
        TraceContainer tc;
        switch (op) {
        case ReadOperation: // 0 Read
            tc = new TraceContainer(stageRequest, Operation.ReadOperation);
            break;
        case WriteOperation: // 1 Write
            tc = new TraceContainer(stageRequest, Operation.WriteOperation);
            break;
        case TruncateOperation: // 2 Truncate
            tc = new TraceContainer(stageRequest, Operation.TruncateOperation);
            break;
        default:
            tc = new TraceContainer(stageRequest, Operation.Nop);
            break;
        }
        return tc;
    }

}
