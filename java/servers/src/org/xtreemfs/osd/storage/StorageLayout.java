/*
 * Copyright (c) 2009-2011 by Bjoern Kolbeck, Christian Lorenz,
 *                            Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.osd.storage;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import org.xtreemfs.common.xloc.StripingPolicyImpl;
import org.xtreemfs.foundation.buffer.BufferPool;
import org.xtreemfs.foundation.buffer.ReusableBuffer;
import org.xtreemfs.osd.InternalObjectData;
import org.xtreemfs.osd.OSDConfig;
import org.xtreemfs.osd.replication.ObjectSet;
import org.xtreemfs.osd.storage.VersionManager.ObjectVersionInfo;
import org.xtreemfs.pbrpc.generatedinterfaces.OSD.TruncateLog;

/**
 * Abstracts object data access from underlying on-disk storage layout.
 * 
 * @author bjko
 */
public abstract class StorageLayout {

    /**
     * read the full object (all available data)
     */
    public static final int       FULL_OBJECT_LENGTH = -1;

    /**
     * file to store the layout and version used to create on disk data
     */
    public static final String    VERSION_FILENAME   = ".version";

    /**
     * true, if we are on a windows platform
     */
    public final static boolean   WIN                = System.getProperty("os.name").toLowerCase().contains("win");

    /**
     * base directory in which to store files
     */
    protected final String        storageDir;

    /**
     * file metadata cache
     */
    protected final MetadataCache cache;

    protected StorageLayout(OSDConfig config, MetadataCache cache) throws IOException {

        this.cache = cache;

        // initialize the storage directory
        String tmp = config.getObjDir();
        if (!tmp.endsWith("/"))
            tmp = tmp + "/";
        storageDir = tmp;
        File stdir = new File(storageDir);
        stdir.mkdirs();

        // check the data version
        File versionMetaFile = new File(storageDir, VERSION_FILENAME);
        if (versionMetaFile.exists()) {
            FileReader in = new FileReader(versionMetaFile);
            char[] text = new char[(int) versionMetaFile.length()];
            in.read(text);
            in.close();
            int versionOnDisk = Integer.valueOf(new String(text));
            if (!isCompatibleVersion(versionOnDisk)) {
                throw new IOException("the OSD storage layout used to create the data on disk (" + versionOnDisk
                        + ") is not compatible with the storage layout loaded: " + this.getClass().getSimpleName());
            }
        }

        final File tmpFile = new File(versionMetaFile + ".tmp");

        FileWriter out = new FileWriter(tmpFile);
        out.write(Integer.toString(getLayoutVersionTag()));
        out.close();

        tmpFile.renameTo(versionMetaFile);

    }

    /**
     * Returns cached file metadata, or loads and caches it if it is not cached.
     * 
     * @param sp
     * @param fileId
     * @return
     * @throws IOException
     */
    public FileMetadata getFileMetadata(final StripingPolicyImpl sp, final String fileId) throws IOException {

        // try to retrieve metadata from cache
        FileMetadata fi = cache.getFileInfo(fileId);

        // if metadata is not cached ...
        if (fi == null) {

            // ... load metadata from disk
            fi = loadFileMetadata(fileId, sp);

            // ... cache metadata to speed up further accesses
            cache.setFileInfo(fileId, fi);
        }

        return fi;
    }

    /**
     * Returns cached file metadata, or loads it if it is not cached.
     * 
     * @param sp
     * @param fileId
     * @return
     * @throws IOException
     */
    public FileMetadata getFileMetadataNoCaching(final StripingPolicyImpl sp, final String fileId) throws IOException {

        // try to retrieve metadata from cache
        FileMetadata fi = cache.getFileInfo(fileId);

        // if metadata is not cached, load it
        if (fi == null)
            fi = loadFileMetadata(fileId, sp);

        return fi;
    }

    /**
     * Loads all metadata associated with a file on the OSD from the storage device. Amongst others, such
     * metadata may comprise object version numbers and checksums.
     * 
     * @param fileId
     *            the file ID
     * @param sp
     *            the striping policy assigned to the file
     * @return a <code>FileInfo</code> object comprising all metadata associated with the file
     * @throws IOException
     *             if an error occurred while trying to read the metadata
     */
    protected abstract FileMetadata loadFileMetadata(String fileId, StripingPolicyImpl sp) throws IOException;

    /**
     * must be called when a file is closed
     * 
     * @param metadata
     */
    public void closeFile(FileMetadata metadata) {
        // do nothing
    }

    /**
     * Reads a complete object from the storage device.
     * 
     * @param fileId
     *            fileId of the object
     * @param md
     *            the file metadata
     * @param objNo
     *            object number
     * @param offset
     *            the offset at which to be read
     * @param length
     *            the number of bytes to be read
     * @param versionInfo
     *            the version to be read
     * @throws java.io.IOException
     *             when the object cannot be read
     * @return on object containing information about the result
     */

    public abstract ObjectInformation readObject(String fileId, FileMetadata md, long objNo, int offset, int length,
            ObjectVersionInfo versionInfo) throws IOException;

    /**
     * Writes a partial object to the storage device.
     * 
     * @param fileId
     *            the file Id the object belongs to
     * @param md
     *            the file metadata
     * @param data
     *            buffer with the data to be written
     * @param objNo
     *            object number
     * @param offset
     *            the relative offset in the object at which to write the buffer
     * @param newVersion
     *            the version number to be assigned
     * @param newTimestamp
     *            the timestamp to be assigned (-1 means 'none')
     * @param sync
     *            flag indicating whether writes are performed synchronously with the underlying storage
     *            device
     * @param cowPolicy
     *            the current copy-on-write policy
     * @throws java.io.IOException
     *             when the object cannot be written
     */
    public abstract void writeObject(String fileId, FileMetadata md, ReusableBuffer data, long objNo, int offset,
            long newVersion, long newTimestamp, boolean sync, CowPolicy cowPolicy) throws IOException;

    /**
     * Truncates an object on the storage device.
     * 
     * @param fileId
     * @param md
     * @param objNo
     * @param newLength
     * @param cow
     * @throws IOException
     */
    public abstract void truncateObject(String fileId, FileMetadata md, long objNo, int newLength, long newVersion,
            long newTimestamp, CowPolicy cow) throws IOException;

    /**
     * Deletes all versions of all objects of a file.
     * 
     * @param fileId
     *            the ID of the file
     * @throws IOException
     *             if an error occurred while deleting the objects
     */
    public abstract void deleteFile(String fileId, boolean deleteMetadata) throws IOException;

    /**
     * Deletes a single version of a single object of a file.
     * 
     * @param fileId
     *            the ID of the file
     * @param objNo
     *            the number of the object to delete
     * @param version
     *            the version number of the object to delete (0 = "lateset version")
     * @param timestamp
     *            the timestamp of the object to delete (0 = "latest timestamp")
     * @throws IOException
     *             if an error occurred while deleting the object
     */
    public abstract void deleteObject(String fileId, FileMetadata md, long objNo, final long version,
            final long timestamp) throws IOException;

    /**
     * Creates and stores a zero-padded object.
     * 
     * @param fileId
     *            the ID of the file
     * @param objNo
     *            the number of the object to create
     * @param sp
     *            the striping policy assigned to the file
     * @param version
     *            the version of the object to create
     * @param size
     *            the size of the object to create
     * @return if OSD checksums are enabled, the newly calculated checksum; <code>null</code>, otherwise
     * @throws IOException
     *             if an error occurred when storing the object
     */
    public abstract void createPaddingObject(String fileId, FileMetadata md, long objNo, long version, long timestamp,
            int size) throws IOException;

    /**
     * Persistently stores a new truncate epoch for a file.
     * 
     * @param fileId
     *            the file ID
     * @param newTruncateEpoch
     *            the new truncate epoch
     * @throws IOException
     *             if an error occurred while storing the new epoch number
     */
    public abstract void setTruncateEpoch(String fileId, long newTruncateEpoch) throws IOException;

    /**
     * Checks whether the file with the given ID exists.
     * 
     * @param fileId
     *            the ID of the file
     * @return <code>true</code>, if the file exists, <code>false</code>, otherwise
     */
    public abstract boolean fileExists(String fileId);

    protected ReusableBuffer unwrapObjectData(String fileId, FileMetadata md, long objNo, ObjectVersionInfo oldVersion)
            throws IOException {
        ReusableBuffer data;
        final int stripeSize = md.getStripingPolicy().getStripeSizeForObject(objNo);
        final boolean isLastObj = (md.getLastObjectNumber() == objNo)
                || ((objNo == 0) && (md.getLastObjectNumber() == -1));
        ObjectInformation obj = readObject(fileId, md, objNo, 0, FULL_OBJECT_LENGTH, oldVersion);
        InternalObjectData oldObject = obj.getObjectData(isLastObj, 0, stripeSize);
        if (obj.getData() == null) {
            if (oldObject.getZero_padding() > 0) {
                // create a zero padded object
                data = BufferPool.allocate(oldObject.getZero_padding());
                for (int i = 0; i < oldObject.getZero_padding(); i++) {
                    data.put((byte) 0);
                }
                data.position(0);
            } else {
                data = BufferPool.allocate(0);
            }
        } else {
            if (oldObject.getZero_padding() > 0) {
                data = BufferPool.allocate(obj.getData().capacity() + oldObject.getZero_padding());
                data.put(obj.getData());
                for (int i = 0; i < oldObject.getZero_padding(); i++) {
                    data.put((byte) 0);
                }
            } else {
                data = obj.getData();
            }
        }
        return data;
    }

    protected ReusableBuffer cow(String fileId, FileMetadata md, long objNo, ReusableBuffer data, int offset,
            ObjectVersionInfo oldVersion) throws IOException {
        ReusableBuffer writeData = null;
        final int stripeSize = md.getStripingPolicy().getStripeSizeForObject(objNo);
        ObjectInformation obj = readObject(fileId, md, objNo, 0, FULL_OBJECT_LENGTH, oldVersion);
        final boolean isLastObj = (md.getLastObjectNumber() == objNo)
                || ((objNo == 0) && (md.getLastObjectNumber() == -1));
        InternalObjectData oldObject = obj.getObjectData(isLastObj, 0, stripeSize);
        if (obj.getData() == null) {
            if (oldObject.getZero_padding() > 0) {
                // create a zero padded object
                writeData = BufferPool.allocate(stripeSize);
                for (int i = 0; i < stripeSize; i++) {
                    writeData.put((byte) 0);
                }
                writeData.position(offset);
                writeData.put(data);
                writeData.position(0);
                BufferPool.free(data);
            } else {
                // write beyond EOF
                if (offset > 0) {
                    writeData = BufferPool.allocate(offset + data.capacity());
                    for (int i = 0; i < offset; i++) {
                        writeData.put((byte) 0);
                    }
                    writeData.put(data);
                    writeData.position(0);
                    BufferPool.free(data);
                } else {
                    writeData = data;
                }
            }
        } else {
            // object data exists on disk
            if (obj.getData().capacity() >= offset + data.capacity()) {
                // old object is large enough
                writeData = obj.getData();
                writeData.position(offset);
                writeData.put(data);
                BufferPool.free(data);
            } else {
                // copy old data and then new data
                writeData = BufferPool.allocate(offset + data.capacity());
                writeData.put(obj.getData());
                BufferPool.free(obj.getData());
                writeData.position(offset);
                writeData.put(data);
                BufferPool.free(data);
            }
        }
        return writeData;
    }

    public abstract long getFileInfoLoadCount();

    /**
     * returns a list of all local saved objects of this file
     * 
     * @param fileId
     * @return null, if file does not exist, otherwise objectList
     */
    public ObjectSet getObjectSet(String fileId, FileMetadata md) {

        int maxCount = (int) md.getVersionManager().getLastObjectId() + 1;
        ObjectSet objectSet = new ObjectSet(maxCount);
        for (long i = 0; i < maxCount; i++)
            if (md.getVersionManager().getLargestObjectVersion(i) != ObjectVersionInfo.MISSING)
                objectSet.add(i);

        return objectSet;
    }

    public abstract FileList getFileList(FileList l, int maxNumEntries);

    public abstract int getLayoutVersionTag();

    public abstract boolean isCompatibleVersion(int layoutVersionTag);

    /**
     * Retrieves the FleaseM master epoch stored for a file.
     * 
     * @param fileId
     * @return current master epoch stored on disk.
     */
    public abstract int getMasterEpoch(String fileId) throws IOException;

    /**
     * Stores the master epoch for a file on stable storage.
     * 
     * @param fileId
     * @param masterEpoch
     */
    public abstract void setMasterEpoch(String fileId, int masterEpoch) throws IOException;

    public abstract TruncateLog getTruncateLog(String fileId) throws IOException;

    public abstract void setTruncateLog(String fileId, TruncateLog log) throws IOException;

    /**
     * returns a list of all files on OSD as fileID
     * 
     * @return {@link HashMap}<String, String>
     */
    public abstract ArrayList<String> getFileIDList();

    public static final class FileList {
        // directories to scan
        final Stack<String>         status;

        // fileName->fileDetails
        final Map<String, FileData> files;

        boolean                     hasMore;

        public FileList(Stack<String> status, Map<String, FileData> files) {
            this.status = status;
            this.files = files;
        }
    }

    public static final class FileData {
        final long size;

        final int  objectSize;

        FileData(long size, int objectSize) {
            this.size = size;
            this.objectSize = objectSize;
        }
    }
}
