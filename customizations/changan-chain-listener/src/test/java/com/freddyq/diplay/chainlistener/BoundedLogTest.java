package com.freddyq.diplay.chainlistener;

import java.io.*;
import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class BoundedLogTest {
    @Test public void aNewManualRunCannotMixOldSlotsIntoTheNewObservation() throws Exception {
        File directory=Files.createTempDirectory("chain-listener-restart-").toFile();
        try {
            Files.writeString(new File(directory,"chain-1.log").toPath(),"old-session");
            try(BoundedLog writer=new BoundedLog(directory,80,3)) { writer.line("new-session"); }
            assertEquals(1,directory.listFiles().length);
            assertEquals("new-session\n",Files.readString(new File(directory,"chain-0.log").toPath()));
        } finally { for(File file:directory.listFiles()) file.delete(); directory.delete(); }
    }
    @Test public void manyRecordsRemainBoundedAndLastRecordSurvives() throws Exception {
        File directory=Files.createTempDirectory("chain-listener-test-").toFile();
        try {
            try(BoundedLog writer=new BoundedLog(directory,80,3)) { for(int i=0;i<100;i++) writer.line("record-"+i+"-abcdef"); }
            File[] files=directory.listFiles(); assertNotNull(files); assertTrue(files.length<=3);
            long bytes=0; boolean latest=false;
            for(File file:files) { bytes+=file.length(); assertTrue(file.length()<=80); latest|=Files.readString(file.toPath()).contains("record-99"); }
            assertTrue(bytes<=240); assertTrue(latest);
        } finally { for(File file:directory.listFiles()) file.delete(); directory.delete(); }
    }
    @Test public void oversizedRecordFailsWithoutAnUnboundedAllocationOnDisk() throws Exception {
        File directory=Files.createTempDirectory("chain-listener-limit-").toFile();
        try {
            try(BoundedLog writer=new BoundedLog(directory,8,2)) {
                try { writer.line("0123456789"); fail(); } catch(IOException expected) {}
            }
            assertEquals(0,directory.listFiles()[0].length());
        } finally { for(File file:directory.listFiles()) file.delete(); directory.delete(); }
    }
}
