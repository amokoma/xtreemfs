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
import org.xtreemfs.common.libxtreemfs.exceptions.VolumeNotFoundException;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.logging.Logging.Category;
import org.xtreemfs.foundation.pbrpc.client.RPCAuthentication;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.UserCredentials;
import org.xtreemfs.osd.OSDConfig;
import org.xtreemfs.osd.OSDRequest;
import org.xtreemfs.osd.OSDRequestDispatcher;
import org.xtreemfs.osd.operations.OSDOperation;
import org.xtreemfs.osd.operations.ReadOperation;
import org.xtreemfs.osd.operations.TruncateOperation;
import org.xtreemfs.osd.operations.WriteOperation;
import org.xtreemfs.osd.stages.TraceContainer.Operation;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.SYSTEM_V_FCNTL;
import org.xtreemfs.pbrpc.generatedinterfaces.MRC.DirectoryEntry;

public class TracingStage extends Stage {

    private final OSDRequestDispatcher          master;

    // time left to next clean op
    private long                                timeToNextOFTclean;

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

        } catch (VolumeNotFoundException e) {
            try {
                client.createVolume("localhost", RPCAuthentication.authNone, userCredentials, traceLogVolumeName);
            } catch (IOException e1) {
                Logging.logError(Logging.LEVEL_ERROR, this, e1);
            }
            Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Volume created");
        } catch (IOException e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        } catch (Exception e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);
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

    private boolean checkForDirectory(Volume volume, UserCredentials userCredentials, String directoryName) {
        boolean foundDirectory = false;
        try {
            for (DirectoryEntry e : v.readDir(userCredentials, "", 0, 0, true).getEntriesList()) {
                if (e.getName().equals(directoryName)) {
                    foundDirectory = true;
                }
            }
        } catch (IOException e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        }
        return foundDirectory;

    }

    public void prepareRequest(OSDRequest request) {
        // Check if the request should be traced
        if (request.getCapability().getXCap().getTraceConfig().getTraceRequests()) {
            this.enqueueOperation(0, new Object[] { request }, null, null);
        }
    }

    @Override
    protected void processMethod(StageRequest stageRequest) {
        OSDRequest rq = (OSDRequest) stageRequest.getArgs()[0];
        OSDOperation op = rq.getOperation();

        // Filter Operation
        if (op instanceof ReadOperation) {
            logTrace(stageRequest, Operation.ReadOperation);
        }
        if (op instanceof WriteOperation) {
            logTrace(stageRequest, Operation.WriteOperation);
        }

        if (op instanceof TruncateOperation) {
            logTrace(stageRequest, Operation.TruncateOperation);
        }

    }

    public void logTrace(StageRequest stageRequest, Operation op) {
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
            Logging.logError(Logging.LEVEL_ERROR, this, e);
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
            Logging.logError(Logging.LEVEL_ERROR, this, e);
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
            Logging.logError(Logging.LEVEL_ERROR, this, e);
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
        } catch (IOException e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        }
        return fileHandle;
    }

    @Override
    public void run() {
        notifyStarted();

        timeToNextOFTclean = OSDConfig.OFT_CLEAN_INTERVAL;
        while (!quit || q.size() > 0) {
            try {
                StageRequest op = q.poll(timeToNextOFTclean, TimeUnit.MILLISECONDS);

                if (op == null) {
                    continue;
                }

                processMethod(op);
            } catch (InterruptedException e) {
                Logging.logError(Logging.LEVEL_ERROR, this, e);
            }

        }

        v.close();
        client.shutdown();
        notifyStopped();
    }
}
