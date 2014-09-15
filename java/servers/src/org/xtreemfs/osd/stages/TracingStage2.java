/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd.stages;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.xtreemfs.common.libxtreemfs.Client;
import org.xtreemfs.foundation.TimeSync;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.logging.Logging.Category;
import org.xtreemfs.osd.OSDRequest;
import org.xtreemfs.osd.OSDRequestDispatcher;
import org.xtreemfs.osd.operations.OSDOperation;
import org.xtreemfs.osd.operations.ReadOperation;
import org.xtreemfs.osd.operations.TruncateOperation;
import org.xtreemfs.osd.operations.WriteOperation;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.readRequest;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.truncateRequest;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.writeRequest;

/** TODO: Brief description of the purpose of this type and its relation to other types. */
public class TracingStage2 extends Stage {

    private final OSDRequestDispatcher master;
    private final int                  queueCapacity;
    private final BlockingQueue<TracingContainer2> traceContainerQueue;

    private final BlockingQueue<OSDRequest>     osdrequestQueue;

    public Object                               lock;

    private final TraceWriterThread                   traceWriterThread;

    Client                                         client;



    /**
     * @param stageName
     * @param queueCapacity
     */
    public TracingStage2(OSDRequestDispatcher master, String stageName, int queueCapacity) {
        super(stageName, queueCapacity);
        this.master = master;
        this.queueCapacity = queueCapacity;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Tracing stage 2");
        traceContainerQueue = new LinkedBlockingQueue<TracingContainer2>();
        osdrequestQueue = new LinkedBlockingQueue<OSDRequest>();
        lock = new Object();
        traceWriterThread = new TraceWriterThread("TraceWriterThread", 10, traceContainerQueue);
        traceWriterThread.setLifeCycleListener(master);
        traceWriterThread.start();
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.osd.stages.Stage#processMethod(org.xtreemfs.osd.stages.Stage.StageRequest)
     */
    @Override
    protected void processMethod(StageRequest method) {
        // TODO Auto-generated method stub

    }

    public void prepareRequest(OSDRequest rq) {
        osdrequestQueue.add(rq);


    }

    private TracingContainer2 createContainerRead(OSDRequest rq) {
        readRequest args = (readRequest) rq.getRequestArgs();
        String fileID = args.getFileId();
        int offset = args.getOffset();
        int length = args.getLength();

        String traceString = TimeSync.getLocalSystemTime() + "#" + fileID + "#"
                + rq.getOperation().getClass().getSimpleName() + "#" + length
                + "#" + offset;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, traceString);
        return new TracingContainer2(traceString);
    }

    private TracingContainer2 createContainerWrite(OSDRequest rq) {
        writeRequest args = (writeRequest) rq.getRequestArgs();
        String fileID = args.getFileId();
        int offset = args.getOffset();
        int length = rq.getRPCRequest().getData().getData().length;

        String traceString = TimeSync.getLocalSystemTime() + "#" + fileID + "#"
                + rq.getOperation().getClass().getSimpleName() + "#" + length
                + "#" + offset;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, traceString);
        return new TracingContainer2(traceString);
    }

    private TracingContainer2 createContainerTruncate(OSDRequest rq) {
        truncateRequest args = (truncateRequest) rq.getRequestArgs();
        String fileID = args.getFileId();
        long newSize = args.getNewFileSize();
        String traceString = TimeSync.getLocalSystemTime() + "#" + fileID + "#"
                + rq.getOperation().getClass().getSimpleName() + "#" + newSize;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, traceString);
        return new TracingContainer2(traceString);
    }

    @Override
    public void run() {
        notifyStarted();

        while (!quit || !traceContainerQueue.isEmpty()) {

            OSDRequest rq = osdrequestQueue.poll();

            if (rq != null) {

                if (rq.getCapability().getXCap().getTraceConfig().getTraceRequests()) { // TODO activate Trace for one
                                                                                        // volume

                    OSDOperation op = rq.getOperation();
                    TracingContainer2 container = null;
                    if (op instanceof ReadOperation) {
                        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this,
                                "###########ReadOperation###########");
                        container = createContainerRead(rq);
                    } else {
                        if (op instanceof WriteOperation) {
                            Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this,
                                    "###########WriteOperation###########");
                            container = createContainerWrite(rq);
                        } else {
                            if (op instanceof TruncateOperation) {
                                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this,
                                        "###########TruncateOperation###########");
                                container = createContainerTruncate(rq);
                            }
                        }
                    }

                    traceContainerQueue.add(container);
                }

            } else {
                continue;
            }

        }

        notifyStopped();
    }


}
