/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd.stages;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

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
    private final BlockingQueue<TraceContainer> traceContainerQueue;
    /**
     * @param stageName
     * @param queueCapacity
     */
    public TracingStage2(OSDRequestDispatcher master, String stageName, int queueCapacity) {
        super(stageName, queueCapacity);
        this.master = master;
        this.queueCapacity = queueCapacity;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Tracing stage 2");
        traceContainerQueue = new LinkedBlockingQueue<TraceContainer>();
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.osd.stages.Stage#processMethod(org.xtreemfs.osd.stages.Stage.StageRequest)
     */
    @Override
    protected void processMethod(StageRequest method) {
        // TODO Auto-generated method stub

    }

    public void prepareRequest(OSDRequest rq) {

        OSDOperation op = rq.getOperation();

        if (op instanceof ReadOperation) {
            Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "###########ReadOperation###########");
            createContainerRead(rq);
        } else {
            if (op instanceof WriteOperation) {
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "###########WriteOperation###########");
                createContainerWrite(rq);
            } else {
                if (op instanceof TruncateOperation) {
                    Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this,
                            "###########TruncateOperation###########");
                    createContainerTruncate(rq);
                }
            }
        }
    }

    private void createContainerRead(OSDRequest rq) {
        readRequest args = (readRequest) rq.getRequestArgs();
        String fileID = args.getFileId();
        int offset = args.getOffset();
        int length = args.getLength();
        
        String traceString = TimeSync.getLocalSystemTime() + "#" + fileID + "#" + length + "#" + offset;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, traceString);
    }

    private void createContainerWrite(OSDRequest rq) {
        writeRequest args = (writeRequest) rq.getRequestArgs();
        String fileID = args.getFileId();
        int offset = args.getOffset();
        int length = rq.getRPCRequest().getData().getData().length;

        String traceString = TimeSync.getLocalSystemTime() + "#" + fileID + "#" + length + "#" + offset;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, traceString);
    }

    private void createContainerTruncate(OSDRequest rq) {
        truncateRequest args = (truncateRequest) rq.getRequestArgs();
        String fileID = args.getFileId();
        long newSize = args.getNewFileSize();
        String traceString = TimeSync.getLocalSystemTime() + "#" + fileID + "#" + newSize;
        Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, traceString);
    }

}
