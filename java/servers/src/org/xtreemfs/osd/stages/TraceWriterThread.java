/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd.stages;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
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
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.SYSTEM_V_FCNTL;
import org.xtreemfs.pbrpc.generatedinterfaces.MRC.DirectoryEntry;

/** TODO: Brief description of the purpose of this type and its relation to other types. */
public class TraceWriterThread extends Stage {

    private final BlockingQueue<TracingContainer2> traceContainerQueue;

    private final List<TracingContainer2>          traceContainerList;

    private final int                                    maxContainer;

    Volume                                         v;

    UserCredentials                                userCredentials;

    Options                                        xtreemfsoptions;

    Client                                         client;

    // TODO recieve informations from a XCap
    private final String                           traceLogVolumeName    = "myVolume2";
    private final String                           traceLogDirectoryName = "TraceLogs";
    private final String                           traceLogFileName      = "traceLog.sav";

    int                                            countedByte           = 0;

    /**
     * @param stageName
     * @param queueCapacity
     * @param traceContainerQueue
     */
    public TraceWriterThread(String stageName, int queueCapacity, BlockingQueue<TracingContainer2> traceContainerQueue) {
        super(stageName, queueCapacity);
        this.traceContainerQueue = traceContainerQueue;
        this.traceContainerList = new ArrayList<TracingContainer2>();
        this.maxContainer = queueCapacity;

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.xtreemfs.osd.stages.Stage#processMethod(org.xtreemfs.osd.stages.Stage.StageRequest)
     */
    @Override
    protected void processMethod(StageRequest method) {
        // TODO Auto-generated method stub

    }

    @Override
    public void run() {

        while (!quit) {

            TracingContainer2 container;
            try {
                container = traceContainerQueue.poll(6000, TimeUnit.MILLISECONDS);

                System.out.println("TraceWriterThread");
                synchronized (traceContainerList) {

                    if (container != null) {
                        if (traceContainerList.size() < maxContainer) {
                            traceContainerList.add(container);
                        } else {
                            writeTraceLogLibXtreemFS(traceLogDirectoryName, traceLogFileName);
                            traceContainerList.clear();
                            traceContainerList.add(container);
                            // write trace
                            // clear containerlist
                            // add Container
                        }
                    } else {
                        continue;
                    }

                }

            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

        }
    }
    
    public String buildTraceString() {
        String result = "";

        for (TracingContainer2 container : traceContainerList) {
            result += container.traceString + "\n";
        }

        return result;
    }

    public void writeTraceLogLibXtreemFS(String directoryName, String traceLogFileName) {
        try {

            prepareClient();
            prepareVolume();

            String pathToTraceLogFile = "/" + directoryName + "/" + traceLogFileName;
            boolean foundTraceLogFile = checkForTraceLogFile(v, userCredentials, directoryName, traceLogFileName);

            String stringToWrite = buildTraceString();
            FileHandle fh = openTraceLogFile(foundTraceLogFile, userCredentials, pathToTraceLogFile);

            int w = fh.write(userCredentials, stringToWrite.getBytes(), stringToWrite.getBytes().length, countedByte);

            countedByte += w;

            fh.flush();
            fh.close();

            Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Bytes written: " + w);

            closeVolume();
            shutdownClient();

        } catch (IOException e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        }

    }

    /**
     * 
     */
    private void shutdownClient() {
        this.client.shutdown();
    }

    /**
     * 
     */
    private void closeVolume() {
        v.close();

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

    private FileHandle openTraceLogFile(boolean foundTraceLogFile, UserCredentials userCredentials,
            String pathToTraceLogFile) {
        FileHandle fileHandle = null;
        try {
            fileHandle = !foundTraceLogFile ? v.openFile(
                    userCredentials,
                    pathToTraceLogFile,
                    SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_CREAT.getNumber()
                            | SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_RDWR.getNumber(), 0666) : v.openFile(userCredentials,
                    pathToTraceLogFile, SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_WRONLY.getNumber());

        } catch (IOException e) {
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        }
        return fileHandle;
    }

    private void prepareClient() {
        xtreemfsoptions = createXtreemfsOptions();
        userCredentials = buildUserCredentials("amokoma", "xtreemfs"); // TODO flexibler
        this.client = ClientFactory.createClient("localhost", userCredentials, null, xtreemfsoptions);
        try {
            this.client.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *
     */
    private void prepareVolume() {
        try {
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

        } catch (VolumeNotFoundException e) {
            try {
                client.createVolume("localhost", RPCAuthentication.authNone, userCredentials, traceLogVolumeName);

            } catch (IOException e1) {
                e1.printStackTrace();
            }
            Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Volume created");

        } catch (IOException e) {
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
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        }
        return foundDirectory;

    }

}
