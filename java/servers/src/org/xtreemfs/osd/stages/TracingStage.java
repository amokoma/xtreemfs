/*
 * Copyright (c) 2014 by Martin Wichner, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd.stages;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.xtreemfs.common.libxtreemfs.Client;
import org.xtreemfs.common.libxtreemfs.ClientFactory;
import org.xtreemfs.common.libxtreemfs.FileHandle;
import org.xtreemfs.common.libxtreemfs.Options;
import org.xtreemfs.common.libxtreemfs.Volume;
import org.xtreemfs.common.libxtreemfs.exceptions.AddressToUUIDNotFoundException;
import org.xtreemfs.common.libxtreemfs.exceptions.PosixErrorException;
import org.xtreemfs.foundation.TimeSync;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.logging.Logging.Category;
import org.xtreemfs.foundation.pbrpc.client.RPCAuthentication;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.UserCredentials;
import org.xtreemfs.osd.OSDRequest;
import org.xtreemfs.osd.OSDRequestDispatcher;
import org.xtreemfs.osd.operations.OSDOperation;
import org.xtreemfs.osd.operations.ReadOperation;
import org.xtreemfs.osd.operations.TruncateOperation;
import org.xtreemfs.osd.operations.WriteOperation;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.SYSTEM_V_FCNTL;
import org.xtreemfs.pbrpc.generatedinterfaces.MRC.DirectoryEntry;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.readRequest;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.truncateRequest;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.writeRequest;

public class TracingStage extends Stage {

    private final OSDRequestDispatcher          master;

    // time left to next clean op
    private long                                timeToNextOFTclean;

    // last check of the OFT
    private long                                lastOFTcheck;

    private final static long                   OFT_CLEAN_INTERVAL    = 1000 * 60;

    private final BlockingQueue<TraceContainer> traceContainerQueue;

    public int                                  traceQCapacity;

    Client                                      client;

    Volume                                      v;

    UserCredentials                             userCredentials;

    Options                                     xtreemfsoptions;

    // private final String volumeID;

    private final String                        traceLogVolumeName    = "myVolume2";
    private final String                        traceLogDirectoryName = "TraceLogs";
    private final String                        traceLogFileName      = "traceLog.sav";

    int                                         countedByte           = 0;

    public TracingStage(OSDRequestDispatcher master, int maxRequestsQueueLength, int traceQCapacity) {
        super("OSD TraceStage", maxRequestsQueueLength);
        this.master = master;

        this.traceContainerQueue = new LinkedBlockingQueue<TraceContainer>();
        this.traceQCapacity = traceQCapacity;

        userCredentials = buildUserCredentials("amokoma", "xtreemfs");

        xtreemfsoptions = createXtreemfsOptions();

        this.client = ClientFactory.createClient("localhost", userCredentials, null, xtreemfsoptions);

        try {
            client.start();
            boolean foundVolume = checkForVolume(client, traceLogVolumeName);

            if (!foundVolume) {
                client.createVolume("localhost", RPCAuthentication.authNone, userCredentials, traceLogVolumeName);
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Volume created");

            } else {
                // client.deleteVolume(RPCAuthentication.authNone,
                // userCredentials, "myVolume2");
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Volume not created");
            }

            // Volume erstellt mit -a NULL
            v = client.openVolume(traceLogVolumeName, null, xtreemfsoptions);
            // v.start();
            boolean foundDir = checkForDirectory(v, userCredentials, traceLogDirectoryName);

            if (!foundDir) {
                v.createDirectory(userCredentials, traceLogDirectoryName, 0777);
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Dir created");
            } else {
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Dir not created");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private UserCredentials buildUserCredentials(String username, String group) {
        return UserCredentials.newBuilder().setUsername(username).addGroups(group).build();
    }

    private Options createXtreemfsOptions() {
        Options xtreemfsOptions = new Options();
        xtreemfsOptions.setMetadataCacheSize(0);
        return xtreemfsOptions;
    }

    private boolean checkForVolume(Client client, String volumeName) {
        boolean foundVolume = false;
        try {
            String[] list;
            list = client.listVolumeNames();
            for (int i = 0; i < list.length; i++) {
                if (list[i].equals(volumeName)) {
                    foundVolume = true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return foundVolume;

    }

    private boolean checkForDirectory(Volume volume, UserCredentials userCredentials, String directoryName) {
        boolean foundDirectory = false;
        try {
            for (DirectoryEntry e : v.readDir(userCredentials, "", 0, 0, true).getEntriesList()) {
                if (e.getName().equals(directoryName)) {
                    foundDirectory = true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return foundDirectory;

    }

    public void prepareRequest(OSDRequest request) {
        // Check if the request should be traced
        if (request.getCapability().getXCap().getTraceConfig().getTraceRequests()) {
            this.enqueueOperation(0, new Object[] { request }, null, null);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.xtreemfs.osd.stages.Stage#processMethod(org.xtreemfs.osd.stages.Stage .StageRequest)
     */
    @Override
    protected void processMethod(StageRequest stageRequest) {
        OSDRequest rq = (OSDRequest) stageRequest.getArgs()[0];
        OSDOperation op = rq.getOperation();

        // Filter Operation
        if (op instanceof ReadOperation) {
            log(stageRequest, Operation.ReadOperation);
        }
        if (op instanceof WriteOperation) {
            log(stageRequest, Operation.WriteOperation);
        }

        if (op instanceof TruncateOperation) {
            log(stageRequest, Operation.TruncateOperation);
        }

    }

    public void log(StageRequest stageRequest, Operation op) {
        TraceContainer tc = buildTraceContainer(stageRequest, op);
        try {
            if (this.traceContainerQueue.size() < this.traceQCapacity) {
                this.traceContainerQueue.put(tc);
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Add a TraceContainer");
            } else {
                writeTraceLogLibXtreemFS(userCredentials, xtreemfsoptions, traceLogDirectoryName, traceLogFileName);
                this.traceContainerQueue.clear();
                this.traceContainerQueue.put(tc);
                Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Add a TraceContainer");
            }
        } catch (InterruptedException e) {
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

    public void writeTraceLogLibXtreemFS(UserCredentials userCredentials, Options xtreemfsoptions,
            String directoryName, String traceLogFileName) {
        try {
            String pathToTraceLogFile = "/" + directoryName + "/" + traceLogFileName;
            boolean foundTraceLogFile = checkForTraceLogFile(v, userCredentials, directoryName, traceLogFileName);
            String stringToWrite = getTraceLogMessage(traceContainerQueue);
            FileHandle fh = openTraceLogFile(foundTraceLogFile, userCredentials, pathToTraceLogFile);

            int w = fh.write(userCredentials, stringToWrite.getBytes(), stringToWrite.getBytes().length, countedByte);

            countedByte += w;

            fh.flush();
            fh.close();

            Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Bytes written: " + w);

        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private boolean checkForTraceLogFile(Volume volume, UserCredentials userCredentials, String directoryName,
            String traceLogFileName) {
        boolean foundTraceLogFile = false;
        try {
            for (DirectoryEntry e : volume.readDir(userCredentials, directoryName, 0, 0, true).getEntriesList()) {
                if (e.getName().equals(traceLogFileName)) {
                    foundTraceLogFile = true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return foundTraceLogFile;
    }

    private String getTraceLogMessage(BlockingQueue<TraceContainer> traceQ) {

        String stringToWrite = "";
        for (TraceContainer t : traceQ) {
            stringToWrite += t.getLogString() + "\r\n";
        }
        return stringToWrite;
    }

    private FileHandle openTraceLogFile(boolean foundTraceLogFile, UserCredentials userCredentials,
            String pathToTraceLogFile) {
        FileHandle fileHandle = null;
        try {
            fileHandle = !foundTraceLogFile ?

            v.openFile(userCredentials, pathToTraceLogFile, SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_CREAT.getNumber()
                    | SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_RDWR.getNumber(), 0666) :

            v.openFile(userCredentials, pathToTraceLogFile, SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_WRONLY.getNumber());
        } catch (PosixErrorException e) {
            e.printStackTrace();
        } catch (AddressToUUIDNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return fileHandle;
    }

    @Override
    public void run() {
        notifyStarted();

        timeToNextOFTclean = OFT_CLEAN_INTERVAL;
        lastOFTcheck = TimeSync.getLocalSystemTime();
        while (!quit || q.size() > 0) {
            try {
                StageRequest op = q.poll(timeToNextOFTclean, TimeUnit.MILLISECONDS);

                if (op == null) {
                    continue;
                }

                processMethod(op);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }

        v.close();
        client.shutdown();
        notifyStopped();
    }

    public class TraceContainer {

        public static final String ENTRYSEPERATOR = "#";

        public OSDOperation        op;
        /**
         * Format : Time - Op - fileID - accessMode
         */
        public String              traceLogString;

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

    public enum Operation {
        ReadOperation, WriteOperation, TruncateOperation, Nop
    }
}
