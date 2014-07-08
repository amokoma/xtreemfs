/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd.stages;

import org.xtreemfs.foundation.TimeSync;
import org.xtreemfs.osd.OSDRequest;
import org.xtreemfs.osd.operations.OSDOperation;
import org.xtreemfs.osd.stages.Stage.StageRequest;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.readRequest;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.truncateRequest;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.writeRequest;

public class TraceContainer {

    public static final String ENTRYSEPERATOR = "#";

    public OSDOperation        op;
    /**
     * Format : Time - Op - fileID - accessMode
     */
    public String              traceLogString;

    public enum Operation {
        ReadOperation, WriteOperation, TruncateOperation, Nop
    }

    public TraceContainer(StageRequest stageRequest, Operation op) {
        OSDRequest osdRequest = (OSDRequest) stageRequest.getArgs()[0];
        this.op = osdRequest.getOperation();

        switch (op) {
        case ReadOperation:
            initForReadOperation(stageRequest);
            break;
        case WriteOperation:
            initForWriteOperation(stageRequest);
            break;
        case TruncateOperation:
            initForTruncateOperation(stageRequest);
            break;
        default:
            this.traceLogString = TimeSync.getLocalSystemTime() + " - no more Informations";
            break;
        }
    }

    private String getTraceLogString(StageRequest stageRequest, Operation operation) {
        String traceLogString = "";
        switch (operation) {
        case ReadOperation:
            traceLogString = getTraceLogStringForRead(stageRequest);
            break;
        case WriteOperation:
            traceLogString = getTraceLogStringForWrite(stageRequest);
            break;
        case TruncateOperation:
            traceLogString = getTraceLogStringForTruncate(stageRequest);
            break;
        case Nop:
            traceLogString = getTraceLogStringForNop(stageRequest);
            break;
        }
        return traceLogString;
    }

    /**
     * @return traceLogString in format : Time - Operation - fileID - accessMode - readLength - offset
     */
    private String getTraceLogStringForRead(StageRequest stageRequest) {
        OSDRequest osdRequest = (OSDRequest) stageRequest.getArgs()[0];
        readRequest readRequestArgs = (readRequest) osdRequest.getRequestArgs();

        String fileID = osdRequest.getFileId();
        String op = osdRequest.getOperation().getClass().getSimpleName();
        int readLength = readRequestArgs.getLength();
        int offset = readRequestArgs.getOffset();
        int accessMode = osdRequest.getCapability().getAccessMode();

        String traceLogString = TimeSync.getLocalSystemTime() + ENTRYSEPERATOR + op + ENTRYSEPERATOR + fileID
                + ENTRYSEPERATOR + accessMode + ENTRYSEPERATOR + readLength + ENTRYSEPERATOR + offset;

        return traceLogString;
    }

    /**
     * @return traceLogString in format : Time - Operation - fileID - accessMode - offset
     */
    private String getTraceLogStringForWrite(StageRequest stageRequest) {
        OSDRequest osdRequest = (OSDRequest) stageRequest.getArgs()[0];
        writeRequest writeRequestArgs = (writeRequest) osdRequest.getRequestArgs();

        String fileID = osdRequest.getFileId();
        String op = osdRequest.getOperation().getClass().getSimpleName();
        int offset = writeRequestArgs.getOffset();
        int accessMode = osdRequest.getCapability().getAccessMode();
        String traceLogString = TimeSync.getLocalSystemTime() + ENTRYSEPERATOR + op + ENTRYSEPERATOR + fileID
                + ENTRYSEPERATOR + accessMode + ENTRYSEPERATOR + offset;
        return traceLogString;
    }

    /**
     * @param stageRequest
     * @return traceLogString in format : Time - Operation - FileID - AccessMode - newFileSize
     */
    private String getTraceLogStringForTruncate(StageRequest stageRequest) {
        OSDRequest osdRequest = (OSDRequest) stageRequest.getArgs()[0];
        truncateRequest truncateRequestArgs = (truncateRequest) osdRequest.getRequestArgs();

        String fileID = osdRequest.getFileId();
        String op = osdRequest.getOperation().getClass().getSimpleName();
        long newFileSize = truncateRequestArgs.getNewFileSize();
        int accessMode = osdRequest.getCapability().getAccessMode();

        String traceLogString = TimeSync.getLocalSystemTime() + ENTRYSEPERATOR + op + ENTRYSEPERATOR + fileID
                + ENTRYSEPERATOR + accessMode + ENTRYSEPERATOR + newFileSize;

        return traceLogString;
    }

    /**
     * @param stageRequest
     * @return
     */
    private String getTraceLogStringForNop(StageRequest stageRequest) {

        return TimeSync.getLocalSystemTime() + ENTRYSEPERATOR + " No more informations";
    }

    public void initForReadOperation(StageRequest stageRequest) {
        this.traceLogString = getTraceLogString(stageRequest, Operation.ReadOperation);
    }

    public void initForWriteOperation(StageRequest stageRequest) {
        this.traceLogString = getTraceLogString(stageRequest, Operation.WriteOperation);
    }

    public void initForTruncateOperation(StageRequest stageRequest) {

        this.traceLogString = getTraceLogString(stageRequest, Operation.TruncateOperation);
    }

    public OSDOperation getOP() {
        return op;
    }

    public String getLogString() {
        return traceLogString;
    }

    public void setOp(OSDOperation op) {
        this.op = op;
    }

    public void setLogString(String logString) {
        this.traceLogString = logString;
    }

}


