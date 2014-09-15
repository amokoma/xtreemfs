/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.test.osd;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xtreemfs.common.libxtreemfs.AdminClient;
import org.xtreemfs.common.libxtreemfs.Client;
import org.xtreemfs.common.libxtreemfs.ClientFactory;
import org.xtreemfs.common.libxtreemfs.FileHandle;
import org.xtreemfs.common.libxtreemfs.Options;
import org.xtreemfs.common.libxtreemfs.Volume;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.pbrpc.client.RPCAuthentication;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.Auth;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.UserCredentials;
import org.xtreemfs.foundation.util.FSUtils;
import org.xtreemfs.osd.stages.TracingStage;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.SYSTEM_V_FCNTL;
import org.xtreemfs.pbrpc.generatedinterfaces.MRC.XATTR_FLAGS;
import org.xtreemfs.test.SetupUtils;
import org.xtreemfs.test.TestEnvironment;

/** TODO: Brief description of the purpose of this type and its relation to other types. */
public class TracingStageTest {

    private static TestEnvironment testEnv;
    private static UserCredentials userCredentials;

    private static String          dirAddress;
    private static String          mrcAddress;

    private static Auth            auth = RPCAuthentication.authNone;

    private static AdminClient     client;

    private static Options         options;

    @Before
    public void setUp() throws Exception {
        System.out.println("TEST: " + TracingStage.class.getSimpleName());

        FSUtils.delTree(new java.io.File(SetupUtils.TEST_DIR));
        Logging.start(Logging.LEVEL_WARN);

        testEnv = new TestEnvironment(new TestEnvironment.Services[] { TestEnvironment.Services.DIR_SERVICE,
                TestEnvironment.Services.DIR_CLIENT, TestEnvironment.Services.TIME_SYNC,
                TestEnvironment.Services.RPC_CLIENT, TestEnvironment.Services.MRC, TestEnvironment.Services.MRC_CLIENT,
                TestEnvironment.Services.OSD, TestEnvironment.Services.OSD_CLIENT });
        //
        // testEnv = new TestEnvironment(new TestEnvironment.Services[] { TestEnvironment.Services.DIR_SERVICE,
        // TestEnvironment.Services.DIR_CLIENT, TestEnvironment.Services.MRC, TestEnvironment.Services.MRC_CLIENT,
        // TestEnvironment.Services.OSD, TestEnvironment.Services.OSD_CLIENT, TestEnvironment.Services.TIME_SYNC });

        testEnv.start();

        userCredentials = UserCredentials.newBuilder().setUsername("test").addGroups("test").build();

        dirAddress = testEnv.getDIRAddress().getHostName() + ":" + testEnv.getDIRAddress().getPort();
        mrcAddress = testEnv.getMRCAddress().getHostName() + ":" + testEnv.getMRCAddress().getPort();
        
        options = new Options();
        client = ClientFactory.createAdminClient(dirAddress, userCredentials, null, options);
        client.start();


    }

    @After
    public void tearDown() {
        testEnv.shutdown();
    }

    @Test
    public void test1() throws Exception {
        Client client = ClientFactory.createClient(dirAddress, userCredentials, null, options);

        client.start();

        client.createVolume(mrcAddress, auth, userCredentials, "Test1");
        Volume v = client.openVolume("Test1", null, options);
        v.start();

        v.setXAttr(userCredentials, "/", "xtreemfs.tracing_enabled", "1", XATTR_FLAGS.XATTR_FLAGS_CREATE);
        v.setXAttr(userCredentials, "/", "xtreemfs.trace_target", "myVolume2", XATTR_FLAGS.XATTR_FLAGS_CREATE);
        v.setXAttr(userCredentials, "/", "xtreemfs.tracing_policy", "Nop", XATTR_FLAGS.XATTR_FLAGS_CREATE);

        FileHandle fileHandle = v.openFile(
                userCredentials,
                "/bla.tzt",
                SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_CREAT.getNumber()
                        | SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_TRUNC.getNumber()
                        | SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_RDWR.getNumber()
                        | SYSTEM_V_FCNTL.SYSTEM_V_FCNTL_H_O_SYNC.getNumber());

        
        // String data = "Hello";
        // for (int i = 0; i < 1000; i++) {
        // data += "Hello";
        // }
        //
        byte[] array = new byte[1000];
        for (int i = 0; i < array.length; i++) {
            array[i] = 1;
        }

        int writtenBytes = fileHandle.write(userCredentials, array, array.length, 0);
        // Logging.logMessage(Logging.LEVEL_INFO, Category.stage, this, "Written bytes: " + writtenBytes);
        System.out.println("Written Bytes" + writtenBytes);
        v.close();
        client.shutdown();
    }

}
