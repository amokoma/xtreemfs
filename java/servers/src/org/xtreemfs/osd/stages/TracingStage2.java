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

    private final BlockingQueue<OSDRequest>        osdrequestQueue;    // TODO replace this with osdrequestQueue2
    private final BlockingQueue<Object[]>          osdrequestQueue2;   // TODO make individual class for the
                                                                        // "container" object is not soo good


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
        traceContainerQueue = new LinkedBlockingQueue<TracingContainer2>();
        osdrequestQueue = new LinkedBlockingQueue<OSDRequest>();
        osdrequestQueue2 = new LinkedBlockingQueue<Object[]>();
        traceWriterThread = new TraceWriterThread("TraceWriterThread", 50, traceContainerQueue);
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

    public void prepareRequest(Object[] rqAndTime) {
        osdrequestQueue2.add(rqAndTime);
    }

    private TracingContainer2 createContainerRead(OSDRequest rq, long timeUsed) {
        readRequest args = (readRequest) rq.getRequestArgs();
        String fileID = args.getFileId();
        int offset = args.getOffset();
        int length = args.getLength();

        String traceString = TimeSync.getLocalSystemTime() + "#" + fileID + "#"
                + rq.getOperation().getClass().getSimpleName() + "#" + offset + "#" + length + "#" + timeUsed;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, traceString);
        return new TracingContainer2(traceString);
    }

    private TracingContainer2 createContainerWrite(OSDRequest rq, long timeUsed) {
        writeRequest args = (writeRequest) rq.getRequestArgs();
        String fileID = args.getFileId();
        int offset = args.getOffset();
        int length = rq.getRPCRequest().getData().getData().length;

        String traceString = TimeSync.getLocalSystemTime() + "#" + fileID + "#"
                + rq.getOperation().getClass().getSimpleName() + "#" + offset + "#" + length + "#" + timeUsed;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, traceString);
        return new TracingContainer2(traceString);
    }

    private TracingContainer2 createContainerTruncate(OSDRequest rq, long timeUsed) {
        truncateRequest args = (truncateRequest) rq.getRequestArgs();
        String fileID = args.getFileId();
        long newSize = args.getNewFileSize();
        String traceString = TimeSync.getLocalSystemTime() + "#" + fileID + "#"
                + rq.getOperation().getClass().getSimpleName() + "#" + newSize + "#" + timeUsed;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, traceString);
        return new TracingContainer2(traceString);
    }

    @Override
    public void run() {
        notifyStarted();

        while (!quit || !traceContainerQueue.isEmpty()) {

            //OSDRequest rq = osdrequestQueue.poll();

            Object[] rqAndTime = osdrequestQueue2.poll();

            if (rqAndTime != null) {
                OSDRequest rq = (OSDRequest) rqAndTime[0];
                long time = (long) rqAndTime[1];
                System.out.println("TraceRequest: " + rq.getCapability().getTraceConfig().getTraceRequests()
                        + " traceVolume: " + rq.getCapability().getTraceConfig().getTargetVolume() + " | On Volume: "
                        + rq.getCapability().getFileId());
                if (rq.getCapability().getXCap().getTraceConfig().getTraceRequests()) { // TODO activate Trace
                    // for one
                    // volume

                    OSDOperation op = rq.getOperation();
                    TracingContainer2 container = null;
                    if (op instanceof ReadOperation) {
                        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this,
                                "###########ReadOperation###########");
                        container = createContainerRead(rq, time);
                    } else {
                        if (op instanceof WriteOperation) {
                            Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this,
                                    "###########WriteOperation###########");
                            container = createContainerWrite(rq, time);
                        } else {
                            if (op instanceof TruncateOperation) {
                                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this,
                                        "###########TruncateOperation###########");
                                container = createContainerTruncate(rq, time);
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
